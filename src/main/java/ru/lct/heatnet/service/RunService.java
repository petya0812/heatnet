package ru.lct.heatnet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.ObstacleSource;
import ru.lct.heatnet.output.ResultWriter;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.reference.RuleOptions;
import ru.lct.heatnet.plan.Planner;
import ru.lct.heatnet.plan.Unconnected;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.validate.OutputValidator;
import ru.lct.heatnet.validate.ValidationReport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Calculation runs: queued, executed by a bounded pool, result file and reports stored on disk and in the DB,
 * result features in PostGIS for the map. A run can be cancelled while queued or running.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final DatasetRepository datasets;
    private final ResultStore results;
    private final ReferenceData ref;
    private final PlanParams defaults;
    private final HeatnetProperties props;
    private final AppConfig.StoragePaths paths;
    private final ThreadPoolTaskExecutor executor;
    private final Map<UUID, Future<?>> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Object>> progress = new ConcurrentHashMap<>();
    /** Construction journal of running calculations (one build is shown live), with finished builds. */
    private final Map<UUID, LiveJournal> live = new ConcurrentHashMap<>();
    private final ParamCatalog catalog;

    /** The build shown while a calculation runs: joint connection from the OKS nearest to the network. */
    static final String LIVE_BUILD = "joint-near";
    private static final int LIVE_LIMIT = 50_000;

    static final class LiveJournal {
        final List<Map<String, Object>> events = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Map<String, Object>> builds = java.util.Collections.synchronizedList(new ArrayList<>());
    }

    public RunService(JdbcTemplate jdbc, DatasetRepository datasets, ResultStore results, ReferenceData ref,
                      PlanParams defaults, HeatnetProperties props, AppConfig.StoragePaths paths,
                      @Qualifier("runExecutor") ThreadPoolTaskExecutor executor, ParamCatalog catalog) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.datasets = datasets;
        this.results = results;
        this.ref = ref;
        this.defaults = defaults;
        this.props = props;
        this.paths = paths;
        this.executor = executor;
    }

    /** Service defaults of the run parameters. */
    public RunParams defaults() {
        return RunParams.of(defaults);
    }

    public UUID start(UUID datasetId, RunParams request) {
        Map<String, Object> ds = datasets.get(datasetId);
        if (ds == null) {
            throw new NotFoundException("Dataset " + datasetId + " not found");
        }
        if (!"READY".equals(ds.get("status"))) {
            throw new IllegalStateException("Dataset is " + ds.get("status") + ", expected READY");
        }
        requireCalculable(ds);
        RunParams req = request == null ? new RunParams() : request;
        PlanParams params = req.apply(defaults);
        params.journal = true;
        UUID id = UUID.randomUUID();
        String name = req.name != null && !req.name.trim().isEmpty() ? req.name.trim() : defaultName(params);
        String note = req.note != null && !req.note.trim().isEmpty() ? req.note.trim() : null;
        try {
            jdbc.update("INSERT INTO run(id, dataset_id, status, params, name, note) VALUES (?, ?, 'QUEUED', "
                    + "?::jsonb, ?, ?)", id, datasetId, MAPPER.writeValueAsString(RunParams.of(params)), name, note);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jobs.put(id, executor.submit(() -> execute(id, datasetId, params)));
        return id;
    }

    void execute(UUID id, UUID datasetId, PlanParams params) {
        if (jdbc.update("UPDATE run SET status = 'RUNNING', started_at = now() WHERE id = ? AND status = 'QUEUED'",
                id) == 0) {
            jobs.remove(id);
            return; // cancelled while queued
        }
        Path out = paths.results.resolve(id + ".geojson");
        try {
            stage(id, "чтение набора", 0, 0);
            Diagnostics diag = new Diagnostics();
            ObstacleSource obstacles = datasets.obstacleSource(datasetId);
            InputModel model = InputModel.build(datasets.coreFeatures(datasetId), diag, ref, params.rules, obstacles);
            long t0 = System.currentTimeMillis();
            LiveJournal lj = new LiveJournal();
            live.put(id, lj);
            List<Variant> variants = new Planner(ref, params)
                    .withProgress((stage, done, total) -> stage(id, stage, done, total))
                    .withJournal((build, event) -> {
                        if ("candidate".equals(event.get("type"))) {
                            Map<String, Object> c = new LinkedHashMap<>(event);
                            c.put("build", build);
                            lj.builds.add(c);
                        } else if (LIVE_BUILD.equals(build) && lj.events.size() < LIVE_LIMIT) {
                            lj.events.add(event);
                        }
                    })
                    .plan(model, obstacles, diag);
            long planMs = System.currentTimeMillis() - t0;
            checkCancelled();
            stage(id, "запись результата", 0, 0);
            ensureDiskSpace();
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
                new ResultWriter().write(os, variants);
            }
            stage(id, "проверка правил", 0, 0);
            ValidationReport rep;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(out), 1 << 16)) {
                rep = new OutputValidator(ref, params.rules).validate(model, obstacles, in);
            }
            checkCancelled();
            stage(id, "слои для карты", 0, 0);
            results.store(id, datasetId, out, variants);
            String journalPrefix = writeJournals(id, variants);
            jdbc.update("UPDATE run SET status = 'DONE', finished_at = now(), result_path = ?, result_bytes = ?, "
                            + "summary = ?::jsonb, validation = ?::jsonb, diagnostics = ?::jsonb, journal_path = ? "
                            + "WHERE id = ? AND status = 'RUNNING'",
                    out.toString(), Files.size(out), MAPPER.writeValueAsString(summary(variants, planMs)),
                    MAPPER.writeValueAsString(validation(rep)), MAPPER.writeValueAsString(diagnostics(diag)),
                    journalPrefix, id);
            log.info("Run {} done in {} ms, validation errors: {}", id, planMs, rep.errorCount());
            retain(datasetId);
        } catch (CancellationException e) {
            log.info("Run {} cancelled", id);
            cleanup(id, out);
            jdbc.update("UPDATE run SET status = 'CANCELLED', finished_at = now() WHERE id = ?", id);
        } catch (Exception e) {
            log.error("Run {} failed", id, e);
            cleanup(id, out);
            jdbc.update("UPDATE run SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ? "
                    + "AND status = 'RUNNING'", String.valueOf(e.getMessage()), id);
        } finally {
            jobs.remove(id);
            progress.remove(id);
            live.remove(id);
            Thread.interrupted(); // the pool thread is reused
        }
    }

    /** A calculation needs a source, an existing network and prospective OKS. */
    @SuppressWarnings("unchecked")
    private static void requireCalculable(Map<String, Object> ds) {
        Object tc = ds.get("type_counts");
        if (!(tc instanceof com.fasterxml.jackson.databind.JsonNode)) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode t = (com.fasterxml.jackson.databind.JsonNode) tc;
        if (t.path("source").asLong() == 0) {
            throw new IllegalStateException("The dataset has no source: nothing to calculate");
        }
        if (t.path("heat_network").asLong() == 0) {
            throw new IllegalStateException("The dataset has no existing network: nothing to connect to");
        }
        if (t.path("oks_future").asLong() == 0 && t.path("oks_connection_point").asLong() == 0) {
            throw new IllegalStateException("The dataset has no prospective OKS: nothing to connect");
        }
    }

    /** Name of a calculation from its parameters that differ from the defaults of the service. */
    @SuppressWarnings("unchecked")
    String defaultName(PlanParams p) {
        List<String> parts = new ArrayList<>();
        PlanParams d = defaults;
        if (p.extraClearanceM != d.extraClearanceM) {
            parts.add("отступ +" + num(p.extraClearanceM) + " м");
        }
        if (p.minCrossingAngleDeg != d.minCrossingAngleDeg && p.minCrossingAngleDeg > 45) {
            parts.add("дороги под " + num(p.minCrossingAngleDeg) + "°");
        }
        if (p.turnStepDeg != d.turnStepDeg || p.turnStepDeg > 0 && p.turnStepStrict != d.turnStepStrict) {
            parts.add(p.turnStepDeg == 0 ? "любые углы поворота"
                    : "повороты " + PlanParams.turnAnglesText(p.turnStepDeg) + (p.turnStepStrict ? " строго" : ""));
        }
        if (p.jointConnection != d.jointConnection) {
            parts.add(p.jointConnection ? "совместное подключение" : "только раздельное подключение");
        }
        if (p.turnPenaltyScore != d.turnPenaltyScore) {
            parts.add("штраф за поворот " + num(p.turnPenaltyScore));
        }
        if (p.rules.existingFlow != d.rules.existingFlow
                || p.rules.existingFlow == RuleOptions.ExistingFlow.CAPACITY_SHARE
                && p.rules.existingFlowShare != d.rules.existingFlowShare) {
            parts.add(p.rules.existingFlow == RuleOptions.ExistingFlow.ZERO ? "сеть свободна"
                    : "сеть занята на " + Math.round(p.rules.existingFlowShare * 100) + " %");
        }
        if (parts.isEmpty()) {
            // three runs with default parameters must still be told apart in every list
            return "Расчёт от " + java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Moscow"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
        }
        return String.join(", ", parts);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v).replace('.', ',');
    }

    /** Journals of the variants, one file per variant (WGS 84); returns the common prefix of the files. */
    private String writeJournals(UUID id, List<Variant> variants) throws IOException {
        String prefix = paths.results.resolve(id + ".journal-").toString();
        for (Variant v : variants) {
            if (v.journal == null) {
                continue;
            }
            java.util.function.UnaryOperator<org.locationtech.jts.geom.Coordinate> tr = ru.lct.heatnet.geo.Crs.utmToWgs84();
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(
                    java.nio.file.Paths.get(prefix + v.variantId + ".json")), 1 << 16)) {
                MAPPER.writeValue(os, JournalGeo.toWgs84(v.journal, tr));
            }
        }
        return prefix;
    }

    /**
     * Construction journal of a variant: from the file of a finished calculation, or — while it runs — the steps of
     * the build shown live, starting at {@code after}.
     */
    public Map<String, Object> journal(UUID id, String variant, int after) throws IOException {
        Map<String, Object> run = get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", id);
        m.put("status", run.get("status"));
        LiveJournal lj = live.get(id);
        if (lj != null) {
            List<Map<String, Object>> events;
            synchronized (lj.events) {
                int from = Math.max(0, Math.min(after, lj.events.size()));
                events = new ArrayList<>(lj.events.subList(from, lj.events.size()));
            }
            m.put("live", true);
            m.put("build", LIVE_BUILD);
            m.put("events", JournalGeo.toWgs84(events, ru.lct.heatnet.geo.Crs.utmToWgs84()));
            m.put("next", after + events.size());
            synchronized (lj.builds) {
                m.put("builds", new ArrayList<>(lj.builds));
            }
            return m;
        }
        List<String> l = jdbc.queryForList("SELECT journal_path FROM run WHERE id = ?", String.class, id);
        if (!"DONE".equals(run.get("status")) || l.isEmpty() || l.get(0) == null) {
            m.put("live", false);
            m.put("events", java.util.Collections.emptyList());
            m.put("next", 0);
            return m;
        }
        Path file = java.nio.file.Paths.get(l.get(0) + variant + ".json");
        if (!variant.matches("[0-9A-Za-z_-]{1,20}") || !Files.exists(file)) {
            throw new NotFoundException("No journal of variant " + variant);
        }
        List<Object> events = MAPPER.readValue(file.toFile(), List.class);
        m.put("live", false);
        m.put("variant", variant);
        m.put("events", after > 0 ? events.subList(Math.min(after, events.size()), events.size()) : events);
        m.put("next", events.size());
        return m;
    }

    /** Name, note and the pin (kept by the retention) of a calculation. */
    public Map<String, Object> update(UUID id, Map<String, Object> patch) {
        get(id);
        if (patch.containsKey("name")) {
            Object n = patch.get("name");
            String name = n == null ? "" : n.toString().trim();
            if (name.isEmpty() || name.length() > 200) {
                throw new IllegalArgumentException("Name must be 1…200 characters");
            }
            jdbc.update("UPDATE run SET name = ? WHERE id = ?", name, id);
        }
        if (patch.containsKey("note")) {
            Object n = patch.get("note");
            String note = n == null ? null : n.toString().trim();
            if (note != null && note.length() > 2000) {
                throw new IllegalArgumentException("Note is longer than 2000 characters");
            }
            jdbc.update("UPDATE run SET note = ? WHERE id = ?", note == null || note.isEmpty() ? null : note, id);
        }
        if (patch.containsKey("pinned")) {
            jdbc.update("UPDATE run SET pinned = ? WHERE id = ?", Boolean.TRUE.equals(patch.get("pinned")), id);
        }
        return get(id);
    }

    private void stage(UUID id, String stage, int done, int total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("done", done);
        m.put("total", total);
        progress.put(id, m);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Run cancelled");
        }
    }

    private void ensureDiskSpace() throws IOException {
        long free = Files.getFileStore(paths.results).getUsableSpace();
        if (free < props.getMinFreeDiskMb() * 1024L * 1024L) {
            throw new IOException("Not enough disk space for the result: " + free / (1024 * 1024) + " MB free");
        }
    }

    private void deleteJournals(String prefix) {
        Path p = java.nio.file.Paths.get(prefix);
        String name = p.getFileName().toString();
        try (java.util.stream.Stream<Path> files = Files.list(p.getParent())) {
            files.filter(f -> f.getFileName().toString().startsWith(name)).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    log.warn("Cannot delete {}: {}", f, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Cannot list journals {}: {}", prefix, e.getMessage());
        }
    }

    private void cleanup(UUID id, Path out) {
        try {
            deleteJournals(paths.results.resolve(id + ".journal-").toString());
            Files.deleteIfExists(out);
            results.drop(id);
        } catch (Exception e) {
            log.warn("Cleanup of run {} failed: {}", id, e.getMessage());
        }
    }

    /** Cancels a queued or running calculation. */
    public void cancel(UUID id) {
        Map<String, Object> run = get(id);
        String status = (String) run.get("status");
        if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
            throw new IllegalStateException("Run is " + status + ", nothing to cancel");
        }
        jdbc.update("UPDATE run SET status = 'CANCELLED', finished_at = now() WHERE id = ? AND status = 'QUEUED'", id);
        Future<?> f = jobs.get(id);
        if (f != null) {
            f.cancel(true);
        }
    }

    /** Deletes a finished run: result file, result table, record. */
    public void delete(UUID id) {
        Map<String, Object> run = get(id);
        if ("QUEUED".equals(run.get("status")) || "RUNNING".equals(run.get("status"))) {
            cancel(id);
            awaitStop(id);
        }
        deleteStored(id);
    }

    /** Cancels and removes every run of a dataset (before the dataset is deleted). */
    public void deleteAllOf(UUID datasetId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM run WHERE dataset_id = ?", UUID.class, datasetId);
        for (UUID id : ids) {
            delete(id);
        }
    }

    private void awaitStop(UUID id) {
        long until = System.currentTimeMillis() + 30_000;
        while (jobs.containsKey(id) && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void deleteStored(UUID id) {
        List<String> paths = jdbc.queryForList("SELECT result_path FROM run WHERE id = ?", String.class, id);
        for (String prefix : jdbc.queryForList("SELECT journal_path FROM run WHERE id = ? AND journal_path IS NOT NULL",
                String.class, id)) {
            deleteJournals(prefix);
        }
        results.drop(id);
        for (String p : paths) {
            if (p != null) {
                try {
                    Files.deleteIfExists(java.nio.file.Paths.get(p));
                } catch (IOException e) {
                    log.warn("Cannot delete {}: {}", p, e.getMessage());
                }
            }
        }
        jdbc.update("DELETE FROM run WHERE id = ?", id);
    }

    /**
     * Keeps the last {@code heatnet.keep-runs-per-dataset} finished runs of a dataset; older ones are removed only
     * after {@code heatnet.keep-runs-min-hours}, so nobody loses a result that has just been calculated.
     */
    private void retain(UUID datasetId) {
        List<UUID> old = jdbc.queryForList("SELECT id FROM run WHERE id IN (SELECT id FROM run WHERE dataset_id = ? "
                        + "AND status NOT IN ('QUEUED', 'RUNNING') AND NOT pinned ORDER BY created_at DESC OFFSET ?) "
                        + "AND finished_at < now() - make_interval(hours => ?)", UUID.class, datasetId,
                props.getKeepRunsPerDataset(), props.getKeepRunsMinHours());
        for (UUID id : old) {
            log.info("Run {} removed by retention", id);
            deleteStored(id);
        }
    }

    private static List<Map<String, Object>> summary(List<Variant> variants, long planMs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Variant v : variants) {
            Map<String, Object> m = new LinkedHashMap<>();
            Variant.Summary s = v.summary;
            m.put("variant_id", v.variantId);
            m.put("rank", s.rank);
            m.put("strategy", v.metrics.strategy);
            m.put("explanation", v.metrics.explanation);
            m.put("approach", v.metrics.approach);
            m.put("network_parts", v.metrics.trees);
            m.put("branching_chambers", v.metrics.junctions);
            m.put("score", s.score);
            m.put("calculated_cost", s.calculatedCost);
            m.put("construction_cost", s.constructionCost);
            m.put("segment_cost", s.constructionCost - s.chamberConstructionCost - s.existingChamberTieInCost);
            m.put("chamber_construction_cost", s.chamberConstructionCost);
            m.put("existing_chamber_tie_in_count", s.existingChamberTieInCount);
            m.put("existing_chamber_tie_in_cost", s.existingChamberTieInCost);
            m.put("unconnected_penalty", s.unconnectedPenalty);
            m.put("new_network_length", s.newNetworkLength);
            m.put("unconnected_oks_ids", s.unconnectedOksIds);
            List<Map<String, Object>> un = new ArrayList<>();
            for (Unconnected u : v.unconnected) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("oks_id", u.oksId);
                x.put("flow_tph", u.flowTph);
                x.put("reason", u.reason.name());
                x.put("reason_text", u.reason.text());
                x.put("detail", u.detail);
                un.add(x);
            }
            m.put("unconnected", un);
            m.put("routes", v.metrics.routes);
            m.put("turns", v.metrics.turns);
            m.put("turns_per_km", v.metrics.turnsPerKm);
            m.put("small_turns", v.metrics.smallTurns);
            m.put("sharp_turns", v.metrics.sharpTurns);
            m.put("min_straight_m", v.metrics.minStraightM);
            m.put("mean_detour_ratio", v.metrics.meanDetourRatio);
            m.put("max_detour_ratio", v.metrics.maxDetourRatio);
            m.put("tie_ins", v.tieIns.size());
            m.put("new_chambers", v.chambers.size());
            m.put("capacity_shortfalls", (int) v.flowChanges.stream()
                    .filter(f -> f.requiredDn > f.existingDn).count());
            m.put("technical_nodes", v.technicalNodes.size());
            m.put("special_segments", v.segments.stream().filter(x -> "special".equals(x.layingMethod)).count());
            m.put("plan_millis", planMs);
            m.put("plain", plain(v));
            List<Map<String, Object>> oks = new ArrayList<>();
            Map<String, Unconnected> unByOks = new java.util.HashMap<>();
            for (Unconnected u : v.unconnected) {
                unByOks.put(u.oksId, u);
            }
            for (Variant.OksInfo o : v.oks) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("oks_id", o.oksId);
                x.put("flow_tph", o.flowTph);
                x.put("connected", o.connected);
                if (o.connected) {
                    x.put("joins", o.joins);
                    x.put("tie_in_id", o.tieInId);
                    x.put("tie_object_id", o.tieObjectId);
                    x.put("tie_object_type", o.tieObjectType);
                    x.put("diameter", o.dn);
                    x.put("own_length", o.ownLength);
                    x.put("own_cost", o.ownCost);
                    x.put("turns", o.turns);
                    x.put("specials", o.specials);
                    x.put("path_length", o.pathLength);
                    x.put("path_segment_ids", o.pathSegmentIds);
                    x.put("shared_with", o.sharedWith);
                    x.put("existing_chain", o.existingChain.size() > 200 ? o.existingChain.subList(0, 200)
                            : o.existingChain);
                } else {
                    Unconnected u = unByOks.get(o.oksId);
                    if (u != null) {
                        x.put("reason", u.reason.name());
                        x.put("reason_text", u.reason.text());
                        x.put("detail", u.detail);
                    }
                }
                oks.add(x);
            }
            m.put("oks", oks);
            out.add(m);
        }
        return out;
    }

    /** The variant in words: connected OKS, attachments, capacity shortfalls of the existing network, cost. */
    static String plain(Variant v) {
        Variant.Summary s = v.summary;
        int total = v.oks.isEmpty() ? v.metrics.routes + v.unconnected.size() : v.oks.size();
        int connected = total - v.unconnected.size();
        StringBuilder sb = new StringBuilder();
        if (connected == 0) {
            sb.append("Ни один ОКС не подключён");
        } else {
            sb.append(connected == total ? (total == 1 ? "ОКС подключён" : "Все " + total + " ОКС подключены")
                    : connected + " из " + total + " ОКС подключены");
            sb.append(" через ").append(v.tieIns.size()).append(' ')
                    .append(plural(v.tieIns.size(), "врезку", "врезки", "врезок"));
            if (v.metrics.junctions > 0) {
                sb.append(", ").append(v.metrics.junctions).append(' ')
                        .append(plural(v.metrics.junctions, "камеру-разветвление", "камеры-разветвления",
                                "камер-разветвлений"));
            }
        }
        sb.append(". ");
        java.util.Set<String> segs = new java.util.LinkedHashSet<>();
        java.util.Set<String> dn = new java.util.TreeSet<>();
        for (Variant.FlowChange f : v.flowChanges) {
            if (f.requiredDn > f.existingDn) {
                segs.add(f.existingObjectId);
                dn.add("ДУ " + f.existingDn + " → " + f.requiredDn);
            }
        }
        if (!segs.isEmpty()) {
            // informational only: the appendix does not rebuild the existing network
            sb.append("Справочно: пропускной способности не хватит на ").append(segs.size()).append(' ')
                    .append(plural(segs.size(), "участке", "участках", "участках"))
                    .append(" существующей сети (").append(String.join(", ", dn)).append("). ");
        }
        sb.append("Новая сеть ").append(fmt(s.newNetworkLength, 0)).append(" м, стоимость ")
                .append(fmt(s.calculatedCost / 1e6, 1)).append(" млн руб.");
        if (!v.unconnected.isEmpty()) {
            sb.append(", из них штраф за неподключённые ОКС ").append(fmt(s.unconnectedPenalty / 1e6, 1))
                    .append(" млн руб.");
        }
        return sb.toString();
    }

    static String plural(int n, String one, String few, String many) {
        int m10 = n % 10;
        int m100 = n % 100;
        if (m10 == 1 && m100 != 11) {
            return one;
        }
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) {
            return few;
        }
        return many;
    }

    static String fmt(double v, int decimals) {
        java.text.DecimalFormatSymbols sym = new java.text.DecimalFormatSymbols(java.util.Locale.ROOT);
        sym.setDecimalSeparator(',');
        sym.setGroupingSeparator('\u00a0');
        java.text.DecimalFormat f = new java.text.DecimalFormat(decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals),
                sym);
        return f.format(v);
    }

    private static Map<String, Object> validation(ValidationReport rep) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errors", rep.errorCount());
        m.put("warnings", rep.warningCount());
        m.put("checked", rep.getChecked());
        List<String> v = new ArrayList<>();
        rep.getViolations().stream().limit(500).forEach(x -> v.add(x.toString()));
        m.put("violations", v);
        return m;
    }

    private static Map<String, Object> diagnostics(Diagnostics diag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", diag.getCounts());
        List<String> issues = new ArrayList<>();
        diag.getIssues().stream().limit(500).forEach(i -> issues.add(i.toString()));
        m.put("issues", issues);
        return m;
    }

    /**
     * A variant of a finished run: 400 when the number is not 1, 2 or 3, 409 when the run is not DONE, 404 when the
     * run has no such variant (a run with joint connection off has one).
     */
    public void requireVariant(UUID id, String variant) {
        if (!variant.matches("[123]")) {
            throw new IllegalArgumentException("variant must be 1, 2 or 3, got " + variant);
        }
        Map<String, Object> run = get(id);
        if (!"DONE".equals(run.get("status"))) {
            throw new IllegalStateException("Run " + id + " is " + run.get("status") + ", expected DONE");
        }
        Object s = run.get("summary");
        if (s instanceof com.fasterxml.jackson.databind.JsonNode) {
            for (com.fasterxml.jackson.databind.JsonNode v : (com.fasterxml.jackson.databind.JsonNode) s) {
                if (variant.equals(v.path("variant_id").asText())) {
                    return;
                }
            }
        }
        throw new NotFoundException("Variant " + variant + " of run " + id + " not found");
    }

    public Map<String, Object> get(UUID id) {
        List<Map<String, Object>> l = jdbc.queryForList("SELECT r.id, r.dataset_id, r.status, r.created_at, "
                + "r.started_at, r.finished_at, r.result_bytes, r.params::text AS params, r.summary::text AS summary, "
                + "r.validation::text AS validation, r.diagnostics::text AS diagnostics, r.error, r.name, r.note, "
                + "r.pinned, d.name AS dataset_name, d.version AS dataset_version, d.parent_id AS dataset_parent_id, "
                + "COALESCE(d.root_id, d.id) AS dataset_root_id FROM run r JOIN dataset d ON d.id = r.dataset_id "
                + "WHERE r.id = ?", id);
        if (l.isEmpty()) {
            throw new NotFoundException("Run " + id + " not found");
        }
        Map<String, Object> m = new LinkedHashMap<>(l.get(0));
        for (String k : new String[]{"params", "summary", "validation", "diagnostics"}) {
            Object v = m.get(k);
            if (v != null) {
                try {
                    m.put(k, MAPPER.readTree(v.toString()));
                } catch (Exception ignored) {
                    // keep text
                }
            }
        }
        Map<String, Object> p = progress.get(id);
        if (p != null) {
            m.put("progress", p);
        }
        return m;
    }

    public List<Map<String, Object>> list(UUID datasetId) {
        List<Map<String, Object>> l = jdbc.queryForList("SELECT r.id, r.dataset_id, r.status, r.created_at, "
                + "r.started_at, r.finished_at, r.params::text AS params, (SELECT min((x->>'score')::float8) FROM "
                + "jsonb_array_elements(r.summary) x) AS best_score, (r.validation->>'errors')::int AS validation_errors, "
                + "r.error, r.name, r.note, r.pinned, d.version AS dataset_version, jsonb_array_length(r.summary) "
                + "AS variants FROM run r JOIN dataset d ON d.id = r.dataset_id WHERE r.dataset_id = ? "
                + "ORDER BY r.created_at DESC", datasetId);
        for (Map<String, Object> m : l) {
            try {
                if (m.get("params") != null) {
                    m.put("params", MAPPER.readTree(m.get("params").toString()));
                }
            } catch (Exception ignored) {
                // keep text
            }
            Map<String, Object> p = progress.get((UUID) m.get("id"));
            if (p != null) {
                m.put("progress", p);
            }
        }
        return l;
    }

    public Path resultFile(UUID id) {
        List<String> l = jdbc.queryForList("SELECT result_path FROM run WHERE id = ? AND status = 'DONE'", String.class, id);
        if (l.isEmpty() || l.get(0) == null) {
            throw new NotFoundException("Result of run " + id + " is not available");
        }
        return java.nio.file.Paths.get(l.get(0));
    }

    /**
     * Streams the features of one variant; the result table is created from the result file on first request if
     * missing.
     */
    public void writeVariant(UUID id, String variantId, boolean derived, OutputStream out) throws Exception {
        ensureStored(id);
        results.writeVariant(id, variantId, derived, out);
    }

    /** The result table of a finished run exists (created from the result file if missing). */
    public void ensureStored(UUID id) {
        Map<String, Object> run = get(id);
        Path file = resultFile(id);
        Boolean stored = jdbc.queryForObject("SELECT result_table FROM run WHERE id = ?", Boolean.class, id);
        if (!Boolean.TRUE.equals(stored)) {
            synchronized (this) {
                try {
                    results.store(id, (UUID) run.get("dataset_id"), file, null);
                } catch (IOException | java.sql.SQLException e) {
                    throw new IllegalStateException("Cannot load the result of run " + id, e);
                }
            }
        }
    }

    /** Runs that are queued or running right now (for the dashboard of the service). */
    public Map<String, Object> load() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", executor.getActiveCount());
        m.put("queued", executor.getThreadPoolExecutor().getQueue().size());
        m.put("threads", executor.getMaxPoolSize());
        return m;
    }
}
