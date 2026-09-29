package ru.lct.heatnet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.FeatureParser;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Upload and import of input files. The upload is written to disk (multipart threshold 0), then parsed
 * feature by feature and streamed into PostGIS with COPY — memory does not depend on the file size.
 * The uploaded file is removed once the import has finished: the data lives in PostGIS.
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final DatasetRepository repo;
    private final ru.lct.heatnet.plan.PlanParams planParams;
    private final MapRepository map;
    private final RunService runs;
    private final ReferenceData ref;
    private final HeatnetProperties props;
    private final AppConfig.StoragePaths paths;
    private final ThreadPoolTaskExecutor executor;
    private final Map<UUID, Future<?>> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> bytesRead = new ConcurrentHashMap<>();

    public DatasetService(DatasetRepository repo, MapRepository map, RunService runs, ReferenceData ref,
                          ru.lct.heatnet.plan.PlanParams planParams, HeatnetProperties props,
                          AppConfig.StoragePaths paths,
                          @Qualifier("importExecutor") ThreadPoolTaskExecutor executor) {
        this.repo = repo;
        this.planParams = planParams;
        this.map = map;
        this.runs = runs;
        this.ref = ref;
        this.props = props;
        this.paths = paths;
        this.executor = executor;
    }

    public UUID upload(MultipartFile file) throws IOException {
        UUID id = UUID.randomUUID();
        Path target = paths.uploads.resolve(id + ".geojson");
        long free = Files.getFileStore(paths.uploads).getUsableSpace();
        // the COPY into PostGIS needs about the size of the file again (same volume in the compose setup)
        if (free - file.getSize() < props.getMinFreeDiskMb() * 1024L * 1024L) {
            throw new InsufficientStorageException(String.format("Not enough disk space: %d MB free, file %d MB",
                    free >> 20, file.getSize() >> 20));
        }
        file.transferTo(target);
        repo.create(id, displayName(file.getOriginalFilename()), Files.size(target));
        bytesRead.put(id, new AtomicLong());
        jobs.put(id, executor.submit(() -> importFile(id, target)));
        return id;
    }

    /** The name of a dataset is the file name without the extension: it is shown as is in the interface. */
    static String displayName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "набор";
        }
        String n = fileName.trim().replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 9) {
            n = n.substring(0, dot);
        }
        return n.isEmpty() ? "набор" : n;
    }

    /** Imports a file already on disk (used by the upload and by tests). */
    public void importFile(UUID id, Path file) {
        if (!repo.setStatusIf(id, "UPLOADED", "IMPORTING")) {
            jobs.remove(id);
            return; // cancelled while queued
        }
        long t0 = System.currentTimeMillis();
        Diagnostics diag = new Diagnostics();
        Map<String, Long> typeCounts = new TreeMap<>();
        long total = 0;
        long imported = 0;
        String error = null;
        boolean cancelled = false;
        AtomicLong read = bytesRead.computeIfAbsent(id, k -> new AtomicLong());
        try (InputStream in = new BufferedInputStream(new Counting(Files.newInputStream(file), read), 1 << 20);
             DatasetRepository.Loader loader = repo.loader(id, props.getCopyBatchRows())) {
            total = new FeatureParser(ref).parse(in, diag, f -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Import cancelled");
                }
                loader.add(f);
                typeCounts.merge(f.getObjectType().getCode(), 1L, Long::sum);
            });
            imported = loader.complete();
            for (String dup : repo.duplicateIds(id, 1000)) {
                diag.error("feature.duplicate_id", dup, "Duplicate id");
            }
            // model-level checks: references, chains to the source, connection points, completion of the input
            InputModel.build(repo.coreFeatures(id), diag, ref, planParams.rules, repo.obstacleSource(id));
            map.bbox(id);
        } catch (CancellationException e) {
            cancelled = true;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || e.getCause() instanceof CancellationException) {
                cancelled = true;
            } else {
                log.error("Import of dataset {} failed", id, e);
                error = e.getMessage();
            }
        } finally {
            jobs.remove(id);
            bytesRead.remove(id);
            Thread.interrupted(); // the pool thread is reused
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Cannot delete upload {}: {}", file, e.getMessage());
            }
        }
        try {
            if (cancelled) {
                repo.dropTable(id);
                repo.setStatus(id, "CANCELLED");
                log.info("Import of dataset {} cancelled", id);
                return;
            }
            repo.saveIssues(id, diag);
            String status = error != null ? "FAILED" : "READY";
            repo.finish(id, status, total, imported, System.currentTimeMillis() - t0, typeCounts, diag.getCounts(),
                    error);
            log.info("Dataset {} {}: {} features, {} imported, {} ms", id, status, total, imported,
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("Cannot store import result of {}", id, e);
        }
    }

    /** Import progress: bytes of the file read so far (only while importing). */
    public Map<String, Object> get(UUID id) {
        Map<String, Object> ds = repo.get(id);
        if (ds == null) {
            throw new NotFoundException("Dataset " + id + " not found");
        }
        Map<String, Object> m = new LinkedHashMap<>(ds);
        AtomicLong read = bytesRead.get(id);
        if (read != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("bytes_read", read.get());
            p.put("bytes_total", ds.get("file_bytes"));
            m.put("progress", p);
        }
        return m;
    }

    /** Cancels an import that is queued or running. */
    public void cancel(UUID id) {
        Map<String, Object> ds = get(id);
        String status = (String) ds.get("status");
        if (!"UPLOADED".equals(status) && !"IMPORTING".equals(status)) {
            throw new IllegalStateException("Dataset is " + status + ", nothing to cancel");
        }
        if (repo.setStatusIf(id, "UPLOADED", "CANCELLED")) {
            try {
                Files.deleteIfExists(paths.uploads.resolve(id + ".geojson"));
            } catch (IOException e) {
                log.warn("Cannot delete upload of {}: {}", id, e.getMessage());
            }
        }
        Future<?> f = jobs.get(id);
        if (f != null) {
            f.cancel(true);
        }
    }

    // ------------------------------------------------------------------ versions with edits of the input

    /**
     * A new version of the dataset with edits of the input (added restrictions); the dataset itself is not changed.
     * The version is built in the background like an import: status IMPORTING, then READY.
     */
    public UUID createVersion(UUID parentId, List<Map<String, Object>> edits, String note) {
        Map<String, Object> parent = get(parentId);
        if (!"READY".equals(parent.get("status"))) {
            throw new IllegalStateException("Dataset is " + parent.get("status") + ", expected READY");
        }
        List<Map<String, Object>> normalized = normalizeEdits(edits, UUID.randomUUID(), repo.targets(parentId));
        UUID id = UUID.fromString(String.valueOf(normalized.get(0).get("version_id")));
        normalized.forEach(e -> e.remove("version_id"));
        repo.createVersion(id, parentId, json(normalized), note == null || note.trim().isEmpty() ? null : note.trim());
        jobs.put(id, executor.submit(() -> buildVersion(id, parentId, normalized)));
        return id;
    }

    /** Replaces the edits of a version; allowed while the version has no calculations. */
    public void updateEdits(UUID id, List<Map<String, Object>> edits) {
        Map<String, Object> ds = get(id);
        if (ds.get("parent_id") == null) {
            throw new IllegalStateException("The uploaded dataset is not edited: create a version");
        }
        if (((Number) ds.get("run_count")).longValue() > 0) {
            throw new IllegalStateException("The version already has calculations: create a new version");
        }
        if (!"READY".equals(ds.get("status")) && !"FAILED".equals(ds.get("status"))) {
            throw new IllegalStateException("Dataset is " + ds.get("status"));
        }
        UUID parentId = (UUID) ds.get("parent_id");
        List<Map<String, Object>> normalized = normalizeEdits(edits, id, repo.targets(parentId));
        normalized.forEach(e -> e.remove("version_id"));
        repo.setEdits(id, json(normalized));
        jobs.put(id, executor.submit(() -> buildVersion(id, parentId, normalized)));
    }

    private void buildVersion(UUID id, UUID parentId, List<Map<String, Object>> edits) {
        long t0 = System.currentTimeMillis();
        try {
            repo.rebuildVersion(id, parentId, edits);
            jdbcTime(id, System.currentTimeMillis() - t0);
            log.info("Version {} of {} built: {} edits, {} ms", id, parentId, edits.size(),
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("Version {} failed", id, e);
            repo.fail(id, String.valueOf(e.getMessage()));
        } finally {
            jobs.remove(id);
            Thread.interrupted();
        }
    }

    private void jdbcTime(UUID id, long millis) {
        repo.setImportMillis(id, millis);
    }

    private static final java.util.Set<String> EDIT_OPS = new java.util.LinkedHashSet<>(
            java.util.Arrays.asList("add_restriction", "remove_object", "set_attribute"));

    /** Attributes a {@code set_attribute} edit may change and the object types they belong to. */
    static final Map<String, java.util.Set<String>> EDITABLE_ATTRIBUTES;

    /** Objects of the network an {@code upstream_object_id} may point at. */
    private static final java.util.Set<String> NETWORK_TYPES = new java.util.LinkedHashSet<>(
            java.util.Arrays.asList("heat_network", "heat_chamber", "source"));

    static {
        Map<String, java.util.Set<String>> a = new LinkedHashMap<>();
        a.put("diameter", new java.util.LinkedHashSet<>(java.util.Arrays.asList("heat_network", "heat_chamber")));
        a.put("flow_tph", new java.util.LinkedHashSet<>(java.util.Arrays.asList("oks_connection_point",
                "heat_network")));
        a.put("upstream_object_id", java.util.Collections.singleton("heat_network"));
        a.put("restriction_type", java.util.Collections.singleton("restriction"));
        EDITABLE_ATTRIBUTES = java.util.Collections.unmodifiableMap(a);
    }

    /**
     * Checks the edits against the version they are applied to and gives every one an id; {@code version_id} is
     * carried in the first element.
     */
    List<Map<String, Object>> normalizeEdits(List<Map<String, Object>> edits, UUID versionId,
                                             DatasetRepository.Targets targets) {
        if (edits == null || edits.isEmpty()) {
            throw new IllegalArgumentException("No edits");
        }
        if (edits.size() > 500) {
            throw new IllegalArgumentException("Too many edits in one version (max 500)");
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        int n = 0;
        long removedPoints = 0;
        String prefix = "edit-" + versionId.toString().substring(0, 8) + "-";
        for (Map<String, Object> e : edits) {
            String op = String.valueOf(e.getOrDefault("op", "add_restriction"));
            if (!EDIT_OPS.contains(op)) {
                throw new IllegalArgumentException("Unknown edit operation: " + op);
            }
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            if (n == 0) {
                m.put("version_id", versionId.toString());
            }
            m.put("id", prefix + (++n));
            m.put("op", op);
            switch (op) {
                case "remove_object":
                    if ("oks_connection_point".equals(removeObject(e, m, targets, removedPoints))) {
                        removedPoints++;
                    }
                    break;
                case "set_attribute":
                    setAttribute(e, m, targets);
                    break;
                default:
                    addRestriction(e, m);
            }
            out.add(m);
        }
        return out;
    }

    /** An added restriction: type of table 2 of the appendix and a valid polygon in EPSG:4326. */
    private void addRestriction(Map<String, Object> e, Map<String, Object> m) {
        String type = String.valueOf(e.get("restriction_type"));
        if (!ref.isKnownRestriction(type) || "heat_network".equals(type)) {
            throw new IllegalArgumentException("Restriction type must be one of table 2: " + type);
        }
        com.fasterxml.jackson.databind.JsonNode gj = MAPPER.valueToTree(e.get("geometry"));
        org.locationtech.jts.geom.Geometry g;
        try {
            g = ru.lct.heatnet.geo.GeoJsonGeometry.read(gj, ru.lct.heatnet.geo.Crs.WGS84_FACTORY);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Bad geometry of an edit: " + ex.getMessage());
        }
        if (!(g instanceof org.locationtech.jts.geom.Polygonal) || !g.isValid()) {
            throw new IllegalArgumentException("The geometry of an edit must be a valid polygon");
        }
        for (org.locationtech.jts.geom.Coordinate c : g.getCoordinates()) {
            if (Math.abs(c.x) > 180 || Math.abs(c.y) > 90) {
                throw new IllegalArgumentException("Coordinates of an edit are not longitude/latitude");
            }
        }
        double area = ru.lct.heatnet.geo.Crs.toUtm(g).getArea();
        if (area < 1 || area > 25e6) {
            throw new IllegalArgumentException(String.format("Area of an edit must be 1 m² … 25 km², got %.0f m²",
                    area));
        }
        m.put("restriction_type", type);
        m.put("comment", comment(e));
        m.put("area_m2", Math.round(area));
        m.put("geometry", gj);
    }

    /**
     * An object of the parent version excluded from this one. The source is not removed (there would be nothing to
     * feed the network) and the last connection point is not removed (there would be nothing to connect).
     * Returns the type of the removed object.
     */
    private String removeObject(Map<String, Object> e, Map<String, Object> m, DatasetRepository.Targets targets,
                               long removedPoints) {
        String fid = targetId(e);
        String type = objectType(fid, targets);
        if ("source".equals(type)) {
            throw new IllegalArgumentException("The source " + fid + " cannot be removed: without it the network has "
                    + "nowhere to take the flow from");
        }
        if ("oks_connection_point".equals(type) && targets.count("oks_connection_point") - removedPoints - 1 < 1) {
            throw new IllegalArgumentException("The connection point " + fid + " cannot be removed: it is the last "
                    + "one and the dataset would be left without an OKS to connect");
        }
        m.put("target_id", fid);
        m.put("object_type", type);
        m.put("comment", comment(e));
        return type;
    }

    /** One attribute of one object: the attribute has to belong to the type of the object and the value to fit it. */
    private void setAttribute(Map<String, Object> e, Map<String, Object> m, DatasetRepository.Targets targets) {
        String fid = targetId(e);
        String type = objectType(fid, targets);
        Object attrRaw = e.get("attribute");
        String attr = attrRaw == null ? "" : attrRaw.toString();
        java.util.Set<String> types = EDITABLE_ATTRIBUTES.get(attr);
        if (types == null) {
            throw new IllegalArgumentException("Attribute cannot be edited: " + attr + " (one of "
                    + EDITABLE_ATTRIBUTES.keySet() + ")");
        }
        if (!types.contains(type)) {
            throw new IllegalArgumentException("Attribute " + attr + " does not belong to an object of type " + type
                    + " (" + fid + "): it belongs to " + types);
        }
        m.put("target_id", fid);
        m.put("object_type", type);
        m.put("attribute", attr);
        m.put("value", value(attr, e.get("value"), fid, targets));
        m.put("comment", comment(e));
    }

    /** The value of an attribute in the form it is stored in, or an error explaining what is allowed. */
    private Object value(String attr, Object raw, String fid, DatasetRepository.Targets targets) {
        // a value may arrive as a JSON node (stored edits) or as a plain object (request body)
        Object v = raw instanceof com.fasterxml.jackson.databind.JsonNode
                ? MAPPER.convertValue(raw, Object.class) : raw;
        switch (attr) {
            case "diameter": {
                if (!(v instanceof Number)) {
                    throw new IllegalArgumentException("Diameter must be a number (mm): " + v);
                }
                double d = ((Number) v).doubleValue();
                if (d != Math.rint(d) || !ref.isKnownDn((int) d)) {
                    throw new IllegalArgumentException("Diameter must be one of the standard DN of the reference "
                            + "data, got " + v);
                }
                return (int) d;
            }
            case "flow_tph": {
                if (!(v instanceof Number)) {
                    throw new IllegalArgumentException("Flow must be a number (t/h): " + v);
                }
                double f = ((Number) v).doubleValue();
                if (!(f >= 0) || f > 10000) {
                    throw new IllegalArgumentException("Flow must be 0 … 10000 t/h, got " + f);
                }
                return f;
            }
            case "upstream_object_id": {
                if (v == null) {
                    return null;
                }
                String up = v.toString().trim();
                if (up.isEmpty()) {
                    return null;
                }
                if (up.equals(fid)) {
                    throw new IllegalArgumentException("An object cannot point at itself: " + up);
                }
                String type = targets.objectType(up);
                if (type == null || !NETWORK_TYPES.contains(type)) {
                    throw new IllegalArgumentException("upstream_object_id must be an object of the network "
                            + NETWORK_TYPES + ", got " + up + (type == null ? " (no such object)" : " (" + type + ")"));
                }
                return up;
            }
            default: {
                String type = v == null ? null : v.toString();
                if (type == null || !ref.isKnownRestriction(type) || "heat_network".equals(type)) {
                    throw new IllegalArgumentException("Restriction type must be one of table 2: " + type);
                }
                return type;
            }
        }
    }

    private static String targetId(Map<String, Object> e) {
        Object id = e.get("target_id");
        if (id == null || id.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("An edit of an object needs target_id — the id of the object");
        }
        return id.toString().trim();
    }

    private static String objectType(String fid, DatasetRepository.Targets targets) {
        String type = targets.objectType(fid);
        if (type == null) {
            throw new IllegalArgumentException("Object " + fid + " is not in the dataset this version is built from");
        }
        return type;
    }

    private static String comment(Map<String, Object> e) {
        Object comment = e.get("comment");
        if (comment != null && comment.toString().length() > 500) {
            throw new IllegalArgumentException("Comment is longer than 500 characters");
        }
        return comment == null || comment.toString().trim().isEmpty() ? null : comment.toString().trim();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ overview for the check step

    /**
     * Everything the check of a dataset shows: lineage of versions, contents, issues grouped by code with their
     * meaning, readiness for a calculation.
     */
    public Map<String, Object> overview(UUID id) {
        Map<String, Object> ds = get(id);
        Map<String, Object> m = new LinkedHashMap<>(ds);
        m.put("lineage", repo.lineage(id));
        if (!"READY".equals(ds.get("status"))) {
            return m;
        }
        Map<String, Object> contents = repo.contents(id);
        for (Map<String, Object> r : (List<Map<String, Object>>) contents.get("restrictions")) {
            String type = (String) r.get("type");
            boolean building = ref.isBuildingRestriction(type);
            r.put("title", ParamCatalog.RESTRICTION_TITLES.getOrDefault(type, type));
            r.put("known", building || ref.isKnownRestriction(type));
            r.put("rule", building ? "существующее здание: обходить с отступом 5/7/9 м по ДУ"
                    : ParamCatalog.ruleText(ref.isKnownRestriction(type) ? ref.restriction(type)
                    : ref.getUnknownRestriction()));
        }
        m.put("contents", contents);
        List<Map<String, Object>> issues = new java.util.ArrayList<>();
        Map<String, Long> counts = new java.util.HashMap<>();
        // the stored examples are limited per code; the full counts are kept with the dataset
        Object ic = ds.get("issue_counts");
        com.fasterxml.jackson.databind.JsonNode totals = ic instanceof com.fasterxml.jackson.databind.JsonNode
                ? (com.fasterxml.jackson.databind.JsonNode) ic : MAPPER.createObjectNode();
        for (Map<String, Object> i : repo.issueCodes(id)) {
            String code = (String) i.get("code");
            Map<String, Object> x = new LinkedHashMap<>(IssueCatalog.describe(code));
            long n = Math.max(((Number) i.get("count")).longValue(), totals.path(code).asLong(0));
            x.put("severity", i.get("severity"));
            x.put("count", n);
            x.put("whole_dataset", ((Number) i.get("with_features")).longValue() == 0);
            counts.put(code, n);
            issues.add(x);
        }
        m.put("issues", issues);
        m.put("readiness", readiness(contents, counts));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readiness(Map<String, Object> contents, Map<String, Long> issues) {
        Map<String, Long> types = (Map<String, Long>) contents.get("types");
        long sources = types.getOrDefault("source", 0L);
        long segments = types.getOrDefault("heat_network", 0L);
        long oks = ((Number) ((Map<String, Object>) contents.get("oks")).get("count")).longValue();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.add(check("source", "Есть источник", sources >= 1, true,
                sources == 1 ? "1 источник" : sources == 0 ? "источника нет — некуда отдавать расход"
                        : sources + " источника, используется первый"));
        out.add(check("network", "Есть существующая сеть", segments > 0, true,
                segments > 0 ? segments + " участков" : "участков сети нет — подключаться некуда"));
        long detached = sum(issues, "network.detached_part", "network.no_upstream", "network.chain_broken",
                "network.chain_cycle", "network.bad_upstream_ref");
        out.add(check("attached", "Сеть связана с источником", detached == 0, false,
                detached == 0 ? "вся сеть доходит до источника"
                        : "есть части без связи с источником: в них не врезаемся (" + detached + ")"));
        out.add(check("oks", "Есть перспективные ОКС с расходом", oks > 0, true,
                oks > 0 ? oks + " ОКС" : "подключать нечего"));
        long badOks = sum(issues, "oks.no_connection_point", "oks.no_flow", "oks.flow_exceeds_max_dn");
        out.add(check("oks_points", "У каждого ОКС есть точка подключения и расход", badOks == 0, false,
                badOks == 0 ? "да" : badOks + " ОКС останутся без маршрута"));
        long skipped = 0;
        for (Map.Entry<String, Long> e : issues.entrySet()) {
            if (e.getKey().startsWith("feature.")) {
                skipped += e.getValue();
            }
        }
        out.add(check("features", "Все объекты прочитаны", skipped == 0, false,
                skipped == 0 ? "да" : skipped + " объектов с ошибками пропущены"));
        return out;
    }

    private static long sum(Map<String, Long> m, String... keys) {
        long s = 0;
        for (String k : keys) {
            s += m.getOrDefault(k, 0L);
        }
        return s;
    }

    private static Map<String, Object> check(String id, String title, boolean ok, boolean blocking, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("ok", ok);
        m.put("blocking", blocking && !ok);
        m.put("detail", detail);
        return m;
    }

    /** Deletes a dataset with its versions, runs, tables and files. */
    public void delete(UUID id) {
        for (UUID child : repo.children(id)) {
            delete(child);
        }
        Map<String, Object> ds = get(id);
        String status = (String) ds.get("status");
        if ("UPLOADED".equals(status) || "IMPORTING".equals(status)) {
            cancel(id);
            long until = System.currentTimeMillis() + 30_000;
            while (jobs.containsKey(id) && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        runs.deleteAllOf(id);
        repo.dropTable(id);
        repo.delete(id);
        try {
            Files.deleteIfExists(paths.uploads.resolve(id + ".geojson"));
        } catch (IOException e) {
            log.warn("Cannot delete upload of {}: {}", id, e.getMessage());
        }
        log.info("Dataset {} deleted", id);
    }

    /** Counts bytes read from the upload, for the progress of the import. */
    private static final class Counting extends FilterInputStream {
        private final AtomicLong counter;

        Counting(InputStream in, AtomicLong counter) {
            super(in);
            this.counter = counter;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                counter.incrementAndGet();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                counter.addAndGet(n);
            }
            return n;
        }
    }

    /** Not enough disk space for an upload (HTTP 507). */
    public static final class InsufficientStorageException extends RuntimeException {
        public InsufficientStorageException(String message) {
            super(message);
        }
    }
}
