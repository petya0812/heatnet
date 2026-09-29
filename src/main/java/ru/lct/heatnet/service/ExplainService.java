package ru.lct.heatnet.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.geo.GeoJsonGeometry;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.ObstacleSource;
import ru.lct.heatnet.plan.NetworkState;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.plan.RouteFinder;
import ru.lct.heatnet.plan.SearchTrace;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Explanations of a result: the trace of the route search of one OKS, the comparison of two variants (of one or of
 * different calculations and versions of the data), the report of a variant for printing.
 */
@Service
public class ExplainService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final RunService runs;
    private final DatasetRepository datasets;
    private final ReferenceData ref;
    private final PlanParams defaults;
    private final ParamCatalog catalog;

    /** Models of recently traced datasets: building one reads the network of the whole dataset. */
    private final Map<String, InputModel> models = Collections.synchronizedMap(new LinkedHashMap<String, InputModel>(
            8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, InputModel> e) {
            return size() > 4;
        }
    });
    private final Map<String, Map<String, Object>> traces = Collections.synchronizedMap(
            new LinkedHashMap<String, Map<String, Object>>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> e) {
                    return size() > 32;
                }
            });

    public ExplainService(JdbcTemplate jdbc, RunService runs, DatasetRepository datasets, ReferenceData ref,
                          PlanParams defaults, ParamCatalog catalog) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.datasets = datasets;
        this.ref = ref;
        this.defaults = defaults;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ search trace

    /**
     * The route search of one OKS of a calculation, recorded: against the existing network alone (as in the separate
     * connection), with the parameters of the calculation.
     */
    public Map<String, Object> searchTrace(UUID runId, String oksId) {
        String key = runId + "|" + oksId;
        Map<String, Object> cached = traces.get(key);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> run = runs.get(runId);
        UUID ds = (UUID) run.get("dataset_id");
        PlanParams params = paramsOf(run);
        ObstacleSource obstacles = datasets.obstacleSource(ds);
        String modelKey = ds + "|" + params.rules;
        InputModel model = models.get(modelKey);
        if (model == null) {
            model = InputModel.build(datasets.coreFeatures(ds), new Diagnostics(), ref, params.rules, obstacles);
            models.put(modelKey, model);
        }
        InputModel.Oks oks = null;
        for (InputModel.Oks o : model.getOks()) {
            if (o.id.equals(oksId)) {
                oks = o;
            }
        }
        if (oks == null) {
            throw new NotFoundException("OKS " + oksId + " is not routable in this dataset");
        }
        long t0 = System.currentTimeMillis();
        SearchTrace t = new RouteFinder(ref, params, model, obstacles).trace(oks, new NetworkState(model, ref));
        UnaryOperator<Coordinate> tr = Crs.utmToWgs84();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("oks_id", oksId);
        m.put("millis", System.currentTimeMillis() - t0);
        m.put("radius_m", Math.round(t.radius));
        m.put("attempts", t.attempts);
        m.put("limit_reached", t.limitReached);
        m.put("max_expansions", params.maxExpansions);
        List<double[]> verts = new ArrayList<>();
        for (Coordinate c : t.vertices) {
            verts.add(pt(tr.apply(c)));
        }
        m.put("vertices", verts);
        m.put("starts", t.starts);
        m.put("connection_point", pt(tr.apply(oks.connectionPoint.getCoordinate())));
        m.put("expansions", t.expansions);
        List<double[][]> rejected = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        Map<String, Integer> byObject = new LinkedHashMap<>();
        for (int i = 0; i < t.rejected.size(); i++) {
            Coordinate[] e = t.rejected.get(i);
            rejected.add(new double[][]{pt(tr.apply(e[0])), pt(tr.apply(e[1]))});
            String kind = reasonKind(t.rejectedReasons.get(i));
            kinds.add(kind);
            byObject.merge(kind, 1, Integer::sum);
        }
        m.put("rejected", rejected);
        m.put("rejected_kinds", kinds);
        List<Map<String, Object>> reasons = new ArrayList<>();
        byObject.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", e.getKey());
            x.put("title", kindTitle(e.getKey()));
            x.put("count", e.getValue());
            reasons.add(x);
        });
        m.put("rejected_by_kind", reasons);
        m.put("zones", features(t.zones, t.zoneKinds));
        m.put("corridors", features(t.corridors, t.corridorKinds));
        if (t.route != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            List<double[]> coords = new ArrayList<>();
            for (Coordinate c : t.route.coords) {
                coords.add(pt(tr.apply(c)));
            }
            r.put("coords", coords);
            r.put("length", Math.round(t.route.length() * 10) / 10.0);
            r.put("target_object_id", t.route.tie.existingObjectId());
            r.put("target_kind", t.route.tie.chamber != null ? "tie_chamber" : "tie_segment");
            m.put("route", r);
        } else {
            m.put("failure", t.failure == null ? null : t.failure.name());
            m.put("failure_text", t.failure == null ? null : t.failure.text());
            m.put("failure_detail", t.failureDetail);
        }
        traces.put(key, m);
        return m;
    }

    private static double[] pt(Coordinate c) {
        return new double[]{Math.round(c.x * 1e7) / 1e7, Math.round(c.y * 1e7) / 1e7};
    }

    /** "crosses park 12" / "clearance of oks 7" → the kind of the object in the way. */
    static String reasonKind(String reason) {
        if (reason == null) {
            return "other";
        }
        String r = reason.startsWith("crosses ") ? reason.substring(8)
                : reason.startsWith("clearance of ") ? reason.substring(13) : null;
        if (r == null) {
            return reason.contains("angle") ? "angle" : reason.contains("corridor") || reason.contains("along")
                    ? "along" : "other";
        }
        int sp = r.indexOf(' ');
        return sp > 0 ? r.substring(0, sp) : r;
    }

    static String kindTitle(String kind) {
        switch (kind) {
            case "own_oks":
                return "здание подключаемого ОКС";
            case "new_route":
                return "уже построенная трасса";
            case "oks_future":
                return "другой перспективный ОКС";
            case "oks_existing":
                return "существующее здание";
            case "angle":
                return "угол пересечения меньше допустимого";
            case "along":
                return "трасса вдоль коммуникации ближе допустимого";
            case "other":
                return "прочие правила";
            default:
                return ParamCatalog.RESTRICTION_TITLES.getOrDefault(kind, kind);
        }
    }

    private Map<String, Object> features(List<Geometry> geoms, List<String> kinds) {
        List<Object> fs = new ArrayList<>();
        for (int i = 0; i < geoms.size(); i++) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("kind", kinds.get(i));
            p.put("title", kindTitle(kinds.get(i)));
            f.put("properties", p);
            f.put("geometry", geoJson(Crs.toWgs84(geoms.get(i))));
            fs.add(f);
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", fs);
        return fc;
    }

    private static JsonNode geoJson(Geometry g) {
        try {
            StringWriter w = new StringWriter();
            try (JsonGenerator gen = MAPPER.getFactory().createGenerator(w)) {
                GeoJsonGeometry.write(gen, g, 7);
            }
            return MAPPER.readTree(w.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private PlanParams paramsOf(Map<String, Object> run) {
        Object p = run.get("params");
        RunParams rp = p instanceof JsonNode ? MAPPER.convertValue(p, RunParams.class) : new RunParams();
        return rp.apply(defaults);
    }

    // ------------------------------------------------------------------ comparison

    /** Rows of the comparison: key of the summary, title, unit, whether smaller is better. */
    static final String[][] METRICS = {
            {"score", "Оценка S", "", "lower"},
            {"calculated_cost", "Стоимость", "руб.", "lower"},
            {"segment_cost", "новые участки", "руб.", "lower"},
            {"chamber_construction_cost", "новые камеры", "руб.", "lower"},
            {"existing_chamber_tie_in_cost", "врезки в существующие камеры", "руб.", "lower"},
            {"unconnected_penalty", "штраф за неподключённые ОКС", "руб.", "lower"},
            {"new_network_length", "Длина новой сети", "м", "lower"},
            {"tie_ins", "Врезок", "", "lower"},
            {"new_chambers", "Новых камер", "", "lower"},
            {"branching_chambers", "из них разветвлений", "", null},
            {"capacity_shortfalls", "Участков существующей сети с нехваткой пропускной способности", "", "lower"},
            {"special_segments", "Участков в спецпроходах", "", "lower"},
            {"unconnected", "ОКС без маршрута", "", "lower"},
            {"turns_per_km", "Поворотов на 1 км", "", "lower"},
            {"max_detour_ratio", "Наибольшее удлинение трассы к прямой", "", "lower"},
    };

    /** Two variants side by side: parameters and data behind them, figures with the difference, the geometry. */
    public Map<String, Object> compare(UUID runA, String va, UUID runB, String vb) {
        Map<String, Object> a = runs.get(runA);
        Map<String, Object> b = runs.get(runB);
        JsonNode sa = variantSummary(a, va);
        JsonNode sb = variantSummary(b, vb);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", side(a, va, sa));
        m.put("b", side(b, vb, sb));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] k : METRICS) {
            Map<String, Object> r = new LinkedHashMap<>();
            double x = value(sa, k[0]);
            double y = value(sb, k[0]);
            r.put("key", k[0]);
            r.put("title", k[1]);
            r.put("unit", k[2]);
            r.put("a", x);
            r.put("b", y);
            r.put("delta", y - x);
            String better = null;
            if (k[3] != null && Math.abs(y - x) > 1e-9 * Math.max(1, Math.abs(x))) {
                better = y < x ? "b" : "a";
            } else if (k[3] != null) {
                better = "equal";
            }
            r.put("better", better);
            rows.add(r);
        }
        m.put("metrics", rows);
        m.put("params", paramDiff(a.get("params"), b.get("params")));
        m.put("data", dataDiff((UUID) a.get("dataset_id"), (UUID) b.get("dataset_id")));
        m.put("geometry", geometryDiff(runA, va, runB, vb));
        m.put("explanation", explanation(sa, sb));
        return m;
    }

    private JsonNode variantSummary(Map<String, Object> run, String variant) {
        if (!"DONE".equals(run.get("status"))) {
            throw new IllegalStateException("Run " + run.get("id") + " is " + run.get("status") + ", expected DONE");
        }
        Object s = run.get("summary");
        if (s instanceof JsonNode) {
            for (JsonNode v : (JsonNode) s) {
                if (variant.equals(v.path("variant_id").asText())) {
                    return v;
                }
            }
        }
        throw new NotFoundException("Variant " + variant + " of run " + run.get("id") + " not found");
    }

    private static double value(JsonNode s, String key) {
        JsonNode n = s.path(key);
        return n.isArray() ? n.size() : n.asDouble(0);
    }

    private static Map<String, Object> side(Map<String, Object> run, String variant, JsonNode s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", run.get("id"));
        m.put("variant_id", variant);
        m.put("rank", s.path("rank").asInt());
        m.put("name", run.get("name"));
        m.put("created_at", run.get("created_at"));
        m.put("dataset_id", run.get("dataset_id"));
        m.put("dataset_name", run.get("dataset_name"));
        m.put("dataset_version", run.get("dataset_version"));
        m.put("strategy", s.path("strategy").asText());
        m.put("plain", s.path("plain").asText());
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> paramDiff(Object pa, Object pb) {
        Map<String, Object> a = pa == null ? Collections.emptyMap() : MAPPER.convertValue(pa, Map.class);
        Map<String, Object> b = pb == null ? Collections.emptyMap() : MAPPER.convertValue(pb, Map.class);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : catalog.entries()) {
            String key = (String) e.get("key");
            if (!Boolean.TRUE.equals(e.get("editable")) || Objects.equals(a.get(key), b.get(key))) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", key);
            r.put("title", e.get("title"));
            r.put("class", e.get("class"));
            r.put("a", label(e, a.get(key)));
            r.put("b", label(e, b.get(key)));
            out.add(r);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static String label(Map<String, Object> entry, Object value) {
        if (value == null) {
            return "—";
        }
        Object options = entry.get("options");
        if (options instanceof List) {
            for (Map<String, Object> o : (List<Map<String, Object>>) options) {
                if (String.valueOf(value).equals(o.get("value"))) {
                    return (String) o.get("label");
                }
            }
        }
        return String.valueOf(value).replace('.', ',') + (entry.get("unit") == null ? "" : " " + entry.get("unit"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataDiff(UUID a, UUID b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("same_dataset", a.equals(b));
        List<Map<String, Object>> la = datasets.lineage(a);
        List<Map<String, Object>> lb = datasets.lineage(b);
        m.put("same_family", !la.isEmpty() && !lb.isEmpty() && la.get(0).get("id").equals(lb.get(0).get("id")));
        Map<String, Map<String, Object>> ea = edits(la);
        Map<String, Map<String, Object>> eb = edits(lb);
        List<Map<String, Object>> onlyA = new ArrayList<>();
        List<Map<String, Object>> onlyB = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : ea.entrySet()) {
            if (!eb.containsKey(e.getKey())) {
                onlyA.add(e.getValue());
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : eb.entrySet()) {
            if (!ea.containsKey(e.getKey())) {
                onlyB.add(e.getValue());
            }
        }
        m.put("edits_only_a", onlyA);
        m.put("edits_only_b", onlyB);
        return m;
    }

    /** All edits along a lineage (without the geometry), by id. */
    private static Map<String, Map<String, Object>> edits(List<Map<String, Object>> lineage) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> v : lineage) {
            Object e = v.get("edits");
            if (e instanceof JsonNode) {
                for (JsonNode x : (JsonNode) e) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", x.path("id").asText());
                    m.put("version", v.get("version"));
                    m.put("op", x.path("op").asText("add_restriction"));
                    m.put("restriction_type", x.path("restriction_type").asText());
                    m.put("title", editTitle(x));
                    m.put("comment", x.path("comment").isNull() ? null : x.path("comment").asText(null));
                    m.put("area_m2", x.path("area_m2").asLong());
                    out.put(x.path("id").asText(), m);
                }
            }
        }
        return out;
    }

    /** Short name of an edit: the restriction it adds, or the object it removes or changes. */
    private static String editTitle(JsonNode e) {
        String type = e.path("restriction_type").asText();
        switch (e.path("op").asText("add_restriction")) {
            case "remove_object":
                return "исключён объект " + e.path("target_id").asText();
            case "set_attribute":
                return e.path("attribute").asText() + " объекта " + e.path("target_id").asText();
            default:
                return ParamCatalog.RESTRICTION_TITLES.getOrDefault(type, type);
        }
    }

    /** An edit in one phrase, for the report. */
    private static String editText(JsonNode e) {
        switch (e.path("op").asText("add_restriction")) {
            case "remove_object":
                return "исключён объект " + e.path("target_id").asText() + " ("
                        + e.path("object_type").asText() + ")";
            case "set_attribute":
                return "у объекта " + e.path("target_id").asText() + " изменён атрибут "
                        + e.path("attribute").asText() + " — "
                        + (e.path("value").isNull() ? "не задан" : e.path("value").asText());
            default:
                return "добавлено ограничение «" + editTitle(e) + "», "
                        + RunService.fmt(e.path("area_m2").asDouble(), 0) + " м²";
        }
    }

    /** Tolerance of "the same place" when the new networks of two variants are compared, metres. */
    public static final double SAME_PLACE_M = 1.0;

    private Map<String, Object> geometryDiff(UUID runA, String va, UUID runB, String vb) {
        runs.ensureStored(runA);
        runs.ensureStored(runB);
        String sql = "WITH a AS (SELECT COALESCE(ST_Union(ST_Transform(geom, 32637)), 'LINESTRING EMPTY'::geometry) g "
                + "FROM " + ResultStore.table(runA) + " WHERE variant_id = ? AND object_type = 'heat_network'), "
                + "b AS (SELECT COALESCE(ST_Union(ST_Transform(geom, 32637)), 'LINESTRING EMPTY'::geometry) g FROM "
                + ResultStore.table(runB) + " WHERE variant_id = ? AND object_type = 'heat_network') "
                + "SELECT ST_Length(ST_Intersection(a.g, ST_Buffer(b.g, ?))), ST_Length(ST_Difference(a.g, "
                + "ST_Buffer(b.g, ?))), ST_Length(ST_Difference(b.g, ST_Buffer(a.g, ?))) FROM a, b";
        double[] r = jdbc.queryForObject(sql, (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)},
                va, vb, SAME_PLACE_M, SAME_PLACE_M, SAME_PLACE_M);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tolerance_m", SAME_PLACE_M);
        m.put("common_length", Math.round(r[0] * 10) / 10.0);
        m.put("only_a_length", Math.round(r[1] * 10) / 10.0);
        m.put("only_b_length", Math.round(r[2] * 10) / 10.0);
        return m;
    }

    /** New network of both variants as pieces "common / only in A / only in B" and tie-ins of both (GeoJSON). */
    public void writeCompareGeoJson(UUID runA, String va, UUID runB, String vb, java.io.OutputStream out)
            throws IOException {
        runs.get(runA);
        runs.get(runB);
        runs.ensureStored(runA);
        runs.ensureStored(runB);
        String ta = ResultStore.table(runA);
        String tb = ResultStore.table(runB);
        String sql = "WITH a AS (SELECT COALESCE(ST_Union(ST_Transform(geom, 32637)), 'LINESTRING EMPTY'::geometry) g "
                + "FROM " + ta + " WHERE variant_id = ? AND object_type = 'heat_network'), "
                + "b AS (SELECT COALESCE(ST_Union(ST_Transform(geom, 32637)), 'LINESTRING EMPTY'::geometry) g FROM " + tb
                + " WHERE variant_id = ? AND object_type = 'heat_network'), "
                + "parts AS (SELECT 'common' AS side, ST_Intersection(a.g, ST_Buffer(b.g, ?)) AS g FROM a, b UNION ALL "
                + "SELECT 'only_a', ST_Difference(a.g, ST_Buffer(b.g, ?)) FROM a, b UNION ALL "
                + "SELECT 'only_b', ST_Difference(b.g, ST_Buffer(a.g, ?)) FROM a, b), "
                + "ties AS (SELECT 'a' AS run, geom FROM " + ta + " WHERE variant_id = ? AND object_type = '"
                + ResultStore.ATTACHMENT + "' UNION ALL SELECT 'b', geom FROM " + tb + " WHERE variant_id = ? AND object_type = '"
                + ResultStore.ATTACHMENT + "') "
                + "SELECT side, 'network', ST_AsGeoJSON(ST_Transform(ST_CollectionExtract(g, 2), 4326), 7) FROM parts "
                + "WHERE NOT ST_IsEmpty(ST_CollectionExtract(g, 2)) UNION ALL "
                + "SELECT CASE WHEN EXISTS (SELECT 1 FROM ties o WHERE o.run <> t.run AND ST_DWithin(ST_Transform(o.geom, "
                + "32637), ST_Transform(t.geom, 32637), ?)) THEN 'common' ELSE 'only_' || t.run END, 'tie_in', "
                + "ST_AsGeoJSON(t.geom, 7) FROM ties t";
        try (JsonGenerator g = MAPPER.getFactory().createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeArrayFieldStart("features");
            jdbc.query(sql, rs -> {
                try {
                    g.writeStartObject();
                    g.writeStringField("type", "Feature");
                    g.writeObjectFieldStart("properties");
                    g.writeStringField("side", rs.getString(1));
                    g.writeStringField("kind", rs.getString(2));
                    g.writeEndObject();
                    g.writeFieldName("geometry");
                    g.writeRawValue(rs.getString(3));
                    g.writeEndObject();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, va, vb, SAME_PLACE_M, SAME_PLACE_M, SAME_PLACE_M, va, vb, SAME_PLACE_M);
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    /** Why one variant ranks above the other: the parts of S and the largest cost differences. */
    private String explanation(JsonNode sa, JsonNode sb) {
        double scoreA = value(sa, "score");
        double scoreB = value(sb, "score");
        if (Math.abs(scoreA - scoreB) < 1e-6) {
            return "Оценки S одинаковы.";
        }
        boolean aBetter = scoreA < scoreB;
        JsonNode best = aBetter ? sa : sb;
        JsonNode worst = aBetter ? sb : sa;
        double dCost = value(worst, "calculated_cost") - value(best, "calculated_cost");
        double dLen = value(worst, "new_network_length") - value(best, "new_network_length");
        StringBuilder text = new StringBuilder();
        text.append("Вариант ").append(aBetter ? "А" : "Б").append(" лучше на ")
                .append(RunService.fmt(Math.abs(scoreA - scoreB), 2)).append(" единицы S: ");
        text.append(dCost >= 0 ? "дешевле на " : "дороже на ").append(RunService.fmt(Math.abs(dCost) / 1e6, 1))
                .append(" млн руб. (вклад в S ").append(RunService.fmt(ref.scoreOfCost(dCost), 2)).append(")");
        text.append(dLen >= 0 ? ", короче на " : ", длиннее на ").append(RunService.fmt(Math.abs(dLen), 0))
                .append(" м (вклад ").append(RunService.fmt(ref.scoreOfLength(dLen), 2)).append(").");
        List<String[]> parts = new ArrayList<>();
        for (String[] k : METRICS) {
            if ("руб.".equals(k[2]) && !"calculated_cost".equals(k[0])) {
                double d = value(worst, k[0]) - value(best, k[0]);
                if (Math.abs(d) >= 1e5) {
                    parts.add(new String[]{k[1], String.valueOf(d)});
                }
            }
        }
        parts.sort((x, y) -> Double.compare(Math.abs(Double.parseDouble(y[1])), Math.abs(Double.parseDouble(x[1]))));
        if (!parts.isEmpty()) {
            text.append(" Больше всего разница в статьях: ");
            List<String> txt = new ArrayList<>();
            for (String[] p : parts.subList(0, Math.min(3, parts.size()))) {
                double d = Double.parseDouble(p[1]);
                txt.add(p[0] + " " + (d >= 0 ? "−" : "+") + RunService.fmt(Math.abs(d) / 1e6, 1) + " млн");
            }
            text.append(String.join(", ", txt)).append('.');
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ report

    /** Report of one variant: one HTML page for printing (map, figures, OKS, rules, parameters, data). */
    @SuppressWarnings("unchecked")
    public String report(UUID runId, String variant) {
        Map<String, Object> run = runs.get(runId);
        JsonNode s = variantSummary(run, variant);
        UUID ds = (UUID) run.get("dataset_id");
        List<Map<String, Object>> lineage = datasets.lineage(ds);
        Map<String, Object> p = run.get("params") == null ? Collections.emptyMap()
                : MAPPER.convertValue(run.get("params"), Map.class);
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><title>Отчёт по варианту ")
                .append(esc(variant)).append("</title><style>")
                .append("body{font:13px/1.45 -apple-system,'Segoe UI',Roboto,Arial,sans-serif;color:#1d2327;margin:24px auto;max-width:980px;padding:0 16px}")
                .append("h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:24px 0 8px;border-bottom:1px solid #ddd;padding-bottom:4px}")
                .append("table{border-collapse:collapse;width:100%;margin:6px 0}td,th{border-bottom:1px solid #eee;padding:4px 6px;text-align:left;vertical-align:top}")
                .append("th{background:#f6f5f1;font-weight:600}.num{text-align:right;white-space:nowrap}.muted{color:#6b7280}")
                .append(".tag{display:inline-block;padding:1px 6px;border-radius:9px;font-size:11px;background:#eef}")
                .append(".bad{color:#b91c1c}.ok{color:#15803d}.lead{font-size:15px;margin:8px 0 12px}svg{border:1px solid #ddd;background:#faf9f6;width:100%;height:auto}")
                .append("@media print{body{margin:0}h2{page-break-after:avoid}}</style></head><body>");
        h.append("<h1>Вариант ").append(esc(variant)).append(" — ").append(s.path("rank").asInt()).append(" место</h1>");
        h.append("<div class=\"muted\">Расчёт «").append(esc(run.get("name"))).append("» от ")
                .append(esc(when(run.get("created_at")))).append(" (МСК) · набор «")
                .append(esc(run.get("dataset_name"))).append("», версия ").append(run.get("dataset_version"))
                .append("</div>");
        h.append("<p class=\"lead\">").append(esc(s.path("plain").asText())).append("</p>");
        h.append(svgMap(runId, variant, ds));

        h.append("<h2>Показатели</h2><table>");
        for (String[] k : METRICS) {
            double v = value(s, k[0]);
            String txt = "руб.".equals(k[2]) ? RunService.fmt(v / 1e6, 2) + " млн руб."
                    : "м".equals(k[2]) ? RunService.fmt(v, 0) + " м"
                    : "score".equals(k[0]) ? RunService.fmt(v, 4) : RunService.fmt(v, k[0].contains("ratio")
                    || k[0].contains("per_km") ? 2 : 0);
            boolean sub = Character.isLowerCase(k[1].charAt(0));
            h.append("<tr><td>").append(sub ? "&nbsp;&nbsp;· " : "").append(esc(k[1])).append("</td><td class=\"num\">")
                    .append(txt).append("</td></tr>");
        }
        h.append("</table><p class=\"muted\">").append(esc(s.path("explanation").asText())).append("</p>");

        h.append("<h2>Перспективные ОКС</h2><table><tr><th>ОКС</th><th class=\"num\">Расход, т/ч</th><th>Подключение</th>"
                + "<th class=\"num\">ДУ</th><th class=\"num\">Своя трасса, м</th><th class=\"num\">Стоимость, млн</th>"
                + "<th class=\"num\">Поворотов</th></tr>");
        for (JsonNode o : s.path("oks")) {
            h.append("<tr><td>").append(esc(o.path("oks_id").asText())).append("</td><td class=\"num\">")
                    .append(RunService.fmt(o.path("flow_tph").asDouble(), 2)).append("</td>");
            if (o.path("connected").asBoolean()) {
                h.append("<td>").append("tie_in".equals(o.path("joins").asText())
                        ? "своя врезка в " + esc(o.path("tie_object_id").asText())
                        : "к новой сети (врезка в " + esc(o.path("tie_object_id").asText()) + ")").append("</td>")
                        .append("<td class=\"num\">").append(o.path("diameter").asInt()).append("</td><td class=\"num\">")
                        .append(RunService.fmt(o.path("own_length").asDouble(), 0)).append("</td><td class=\"num\">")
                        .append(RunService.fmt(o.path("own_cost").asDouble() / 1e6, 2)).append("</td><td class=\"num\">")
                        .append(o.path("turns").asInt()).append("</td>");
            } else {
                h.append("<td colspan=\"5\" class=\"bad\">без маршрута: ").append(esc(o.path("reason_text").asText()))
                        .append(o.path("detail").isTextual() ? " (" + esc(o.path("detail").asText()) + ")" : "")
                        .append("</td>");
            }
            h.append("</tr>");
        }
        h.append("</table>");

        Object val = run.get("validation");
        h.append("<h2>Проверка обязательных правил</h2>");
        if (val instanceof JsonNode) {
            JsonNode v = (JsonNode) val;
            int errors = v.path("errors").asInt();
            h.append(errors == 0 ? "<p class=\"ok\">Нарушений нет.</p>" : "<p class=\"bad\">Нарушений: " + errors + "</p>");
            h.append("<p class=\"muted\">Результат перечитан из файла выгрузки и проверен независимо от расчёта: схема, "
                    + "дерево и врезки, расходы и ДУ, предельная длина по каждому пути, отступы и спецпроходы, повороты, "
                    + "стоимость и S.</p>");
            if (errors > 0) {
                h.append("<ul>");
                int n = 0;
                for (JsonNode x : v.path("violations")) {
                    if (n++ >= 50) {
                        break;
                    }
                    h.append("<li>").append(esc(x.asText())).append("</li>");
                }
                h.append("</ul>");
            }
        }

        h.append("<h2>Параметры расчёта</h2><table><tr><th>Параметр</th><th>Значение</th><th>Основание</th></tr>");
        for (Map<String, Object> e : catalog.entries()) {
            String key = (String) e.get("key");
            String value = Boolean.TRUE.equals(e.get("editable")) ? label(e, p.get(key)) : (String) e.get("value");
            if (e.containsKey("depends_on")) {
                Map<String, Object> dep = (Map<String, Object>) e.get("depends_on");
                Object actual = p.get(dep.get("key"));
                if (dep.containsKey("not") ? Objects.equals(String.valueOf(actual), String.valueOf(dep.get("not")))
                        : !Objects.equals(String.valueOf(actual), String.valueOf(dep.get("value")))) {
                    continue;
                }
            }
            h.append("<tr><td>").append(esc(e.get("title"))).append("</td><td>").append(esc(value)).append("</td><td>")
                    .append("<span class=\"tag\">").append(esc(e.get("class_title"))).append("</span> ")
                    .append(e.get("source") != null ? esc(e.get("source")) : "").append("</td></tr>");
        }
        h.append("</table>");

        h.append("<h2>Данные</h2><p>Версии набора:</p><ul>");
        for (Map<String, Object> v : lineage) {
            h.append("<li>версия ").append(v.get("version")).append(v.get("note") != null ? " — " + esc(v.get("note")) : "");
            Object e = v.get("edits");
            if (e instanceof JsonNode && ((JsonNode) e).size() > 0) {
                h.append("<ul>");
                for (JsonNode x : (JsonNode) e) {
                    h.append("<li>").append(esc(editText(x)))
                            .append(x.path("comment").isTextual() ? ": " + esc(x.path("comment").asText()) : "")
                            .append("</li>");
                }
                h.append("</ul>");
            } else if (v.get("version") != null && ((Number) v.get("version")).intValue() == 1) {
                h.append(" — загруженный файл «").append(esc(v.get("name"))).append("»");
            }
            h.append("</li>");
        }
        h.append("</ul><p>Что восполнено или замечено при проверке данных:</p><table>");
        for (Map<String, Object> i : datasets.issueCodes(ds)) {
            Map<String, Object> d = IssueCatalog.describe((String) i.get("code"));
            h.append("<tr><td>").append(esc(d.get("title"))).append("<div class=\"muted\">").append(esc(d.get("meaning")))
                    .append("</div></td><td class=\"num\">").append(i.get("count")).append("</td></tr>");
        }
        h.append("</table><p class=\"muted\">Сформировано сервисом трассировки подключений. Файл результата — "
                + "отдельная выгрузка GeoJSON; этот отчёт в него не входит.</p></body></html>");
        return h.toString();
    }

    /** Simple map of the variant: existing network, new network by DN, attachments, capacity shortfalls, source. */
    private String svgMap(UUID runId, String variant, UUID ds) {
        runs.ensureStored(runId);
        List<Object[]> lines = new ArrayList<>();
        List<Object[]> points = new ArrayList<>();
        jdbc.query("SELECT object_type, ST_AsGeoJSON(geom, 7), props->>'diameter', props->>'reconstructed' FROM "
                + ResultStore.table(runId)
                + " WHERE variant_id = ? AND object_type IN ('heat_network', '" + ResultStore.FLOW_CHANGE + "', '"
                + ResultStore.ATTACHMENT + "') AND geom IS NOT NULL", rs -> {
                    String type = rs.getString(1);
                    JsonNode g = readTree(rs.getString(2));
                    if (ResultStore.ATTACHMENT.equals(type)) {
                        points.add(new Object[]{type, g});
                    } else if (ResultStore.FLOW_CHANGE.equals(type)) {
                        // only the pieces of the existing network that run out of capacity are drawn
                        if ("true".equals(rs.getString(4))) {
                            lines.add(new Object[]{type, g, rs.getString(3)});
                        }
                    } else {
                        lines.add(new Object[]{type, g, rs.getString(3)});
                    }
                }, variant);
        double[] env = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Object[] l : lines) {
            extend(env, ((JsonNode) l[1]).path("coordinates"));
        }
        for (Object[] pt : points) {
            extend(env, ((JsonNode) pt[1]).path("coordinates"));
        }
        if (env[0] == Double.MAX_VALUE) {
            return "";
        }
        double padX = Math.max(0.002, (env[2] - env[0]) * 0.08);
        double padY = Math.max(0.0012, (env[3] - env[1]) * 0.08);
        env[0] -= padX;
        env[2] += padX;
        env[1] -= padY;
        env[3] += padY;
        List<Object[]> existing = new ArrayList<>();
        jdbc.query("SELECT object_type, ST_AsGeoJSON(geom, 7) FROM " + DatasetRepository.table(ds) + " WHERE object_type IN "
                        + "('heat_network', 'source') AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326) LIMIT 20000",
                rs -> {
                    existing.add(new Object[]{rs.getString(1), readTree(rs.getString(2))});
                }, env[0], env[1], env[2], env[3]);
        double k = Math.cos(Math.toRadians((env[1] + env[3]) / 2));
        double w = 900;
        double hgt = Math.max(300, Math.min(700, w * (env[3] - env[1]) / ((env[2] - env[0]) * k)));
        double sx = w / (env[2] - env[0]);
        double sy = hgt / (env[3] - env[1]);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "<svg viewBox=\"0 0 %.0f %.0f\" xmlns=\"http://www.w3.org/2000/svg\">", w, hgt));
        for (Object[] e : existing) {
            JsonNode g = (JsonNode) e[1];
            if ("source".equals(e[0])) {
                JsonNode c = g.path("coordinates");
                sb.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"7\" fill=\"#1d2327\" "
                                + "stroke=\"#fbbf24\" stroke-width=\"3\"/>", (c.get(0).asDouble() - env[0]) * sx,
                        (env[3] - c.get(1).asDouble()) * sy));
            } else {
                sb.append(path(g, env, sx, sy, "#94a3b8", 2.5));
            }
        }
        for (Object[] l : lines) {
            if (ResultStore.FLOW_CHANGE.equals(l[0])) {
                sb.append(path((JsonNode) l[1], env, sx, sy, "#d946ef", 7));
            }
        }
        for (Object[] l : lines) {
            if ("heat_network".equals(l[0])) {
                int dn = l[2] == null ? 50 : (int) Double.parseDouble((String) l[2]);
                String color = dn <= 50 ? "#fbbf24" : dn <= 100 ? "#f97316" : dn <= 200 ? "#ea580c" : dn <= 400 ? "#dc2626" : "#9f1239";
                sb.append(path((JsonNode) l[1], env, sx, sy, color, 1.5 + Math.min(5, dn / 80.0)));
            }
        }
        for (Object[] pt : points) {
            JsonNode c = ((JsonNode) pt[1]).path("coordinates");
            sb.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"5\" fill=\"#dc2626\" "
                    + "stroke=\"#fff\" stroke-width=\"2\"/>", (c.get(0).asDouble() - env[0]) * sx, (env[3] - c.get(1).asDouble()) * sy));
        }
        sb.append("</svg><p class=\"muted\">Серым — существующая сеть, цветом — новая сеть (темнее — больший ДУ), "
                + "красные точки — врезки, сиреневым — участки существующей сети, которым не хватит пропускной способности.</p>");
        return sb.toString();
    }

    private static String path(JsonNode g, double[] env, double sx, double sy, String color, double width) {
        StringBuilder sb = new StringBuilder();
        JsonNode c = g.path("coordinates");
        List<JsonNode> parts = new ArrayList<>();
        if ("LineString".equals(g.path("type").asText())) {
            parts.add(c);
        } else if ("MultiLineString".equals(g.path("type").asText())) {
            c.forEach(parts::add);
        }
        for (JsonNode line : parts) {
            sb.append("<polyline fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke=\"").append(color)
                    .append(String.format(Locale.ROOT, "\" stroke-width=\"%.1f\" points=\"", width));
            for (JsonNode p : line) {
                sb.append(String.format(Locale.ROOT, "%.1f,%.1f ", (p.get(0).asDouble() - env[0]) * sx,
                        (env[3] - p.get(1).asDouble()) * sy));
            }
            sb.append("\"/>");
        }
        return sb.toString();
    }

    private static void extend(double[] env, JsonNode c) {
        if (c.isArray() && c.size() >= 2 && c.get(0).isNumber()) {
            env[0] = Math.min(env[0], c.get(0).asDouble());
            env[1] = Math.min(env[1], c.get(1).asDouble());
            env[2] = Math.max(env[2], c.get(0).asDouble());
            env[3] = Math.max(env[3], c.get(1).asDouble());
        } else if (c.isArray()) {
            c.forEach(x -> extend(env, x));
        }
    }

    private static JsonNode readTree(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String when(Object t) {
        java.time.Instant i = t instanceof java.sql.Timestamp ? ((java.sql.Timestamp) t).toInstant()
                : t instanceof java.time.OffsetDateTime ? ((java.time.OffsetDateTime) t).toInstant() : null;
        return i == null ? String.valueOf(t) : java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(java.time.ZoneId.of("Europe/Moscow")).format(i);
    }

    private static String esc(Object o) {
        if (o == null) {
            return "";
        }
        return o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

}
