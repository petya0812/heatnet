package ru.lct.heatnet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.ObjectType;
import ru.lct.heatnet.input.Obstacle;
import ru.lct.heatnet.input.ObstacleSource;
import ru.lct.heatnet.input.ParsedFeature;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Storage of datasets in PostGIS. Every dataset gets its own table {@code data.ds_<id>} filled by COPY;
 * indexes are built after the load (much faster than maintaining them row by row).
 */
@Repository
public class DatasetRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public DatasetRepository(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public static String table(UUID datasetId) {
        return "data.ds_" + datasetId.toString().replace("-", "");
    }

    public void create(UUID id, String name, long bytes) {
        jdbc.update("INSERT INTO dataset(id, name, status, file_bytes) VALUES (?, ?, 'UPLOADED', ?)", id, name, bytes);
    }

    public void setStatus(UUID id, String status) {
        jdbc.update("UPDATE dataset SET status = ? WHERE id = ?", status, id);
    }

    /** Changes the status only if it is {@code from}; returns whether it changed. */
    public boolean setStatusIf(UUID id, String from, String to) {
        return jdbc.update("UPDATE dataset SET status = ? WHERE id = ? AND status = ?", to, id, from) == 1;
    }

    public void dropTable(UUID id) {
        jdbc.execute("DROP TABLE IF EXISTS " + table(id));
    }

    /** Removes the dataset record with its issues and runs (cascade). */
    public void delete(UUID id) {
        jdbc.update("DELETE FROM dataset WHERE id = ?", id);
    }

    public void finish(UUID id, String status, long features, long imported, long millis, Map<String, Long> typeCounts,
                       Map<String, Long> issueCounts, String error) throws IOException {
        jdbc.update("UPDATE dataset SET status = ?, finished_at = now(), feature_count = ?, imported_count = ?, "
                        + "import_millis = ?, type_counts = ?::jsonb, issue_counts = ?::jsonb, error = ? WHERE id = ?",
                status, features, imported, millis, MAPPER.writeValueAsString(typeCounts),
                MAPPER.writeValueAsString(issueCounts), error, id);
    }

    public void saveIssues(UUID id, Diagnostics diag) {
        jdbc.update("DELETE FROM dataset_issue WHERE dataset_id = ?", id);
        List<Object[]> rows = new ArrayList<>();
        for (Diagnostics.Issue i : diag.getIssues()) {
            rows.add(new Object[]{id, i.getSeverity().name(), i.getCode(), i.getFeatureId(), i.getMessage()});
        }
        jdbc.batchUpdate("INSERT INTO dataset_issue(dataset_id, severity, code, feature_id, message) VALUES (?,?,?,?,?)",
                rows);
    }

    private static final String SELECT = "SELECT d.id, d.name, d.status, d.created_at, d.finished_at, d.file_bytes, "
                + "d.feature_count, d.imported_count, d.import_millis, d.type_counts::text AS type_counts, "
                + "d.issue_counts::text AS issue_counts, d.bbox::text AS bbox, d.error, d.parent_id, "
                + "COALESCE(d.root_id, d.id) AS root_id, d.version, d.edits::text AS edits, d.note, "
                + "(SELECT count(*) FROM run r WHERE r.dataset_id = d.id) AS run_count FROM dataset d ";

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> l = jdbc.queryForList(SELECT + "ORDER BY d.created_at DESC");
        l.forEach(DatasetRepository::decode);
        return l;
    }

    public Map<String, Object> get(UUID id) {
        List<Map<String, Object>> l = jdbc.queryForList(SELECT + "WHERE d.id = ?", id);
        if (l.isEmpty()) {
            return null;
        }
        decode(l.get(0));
        return l.get(0);
    }

    /** JSON columns as objects, the extent as an array of numbers. */
    private static void decode(Map<String, Object> m) {
        for (String k : new String[]{"type_counts", "issue_counts", "edits"}) {
            Object v = m.get(k);
            if (v != null) {
                try {
                    m.put(k, MAPPER.readTree(v.toString()));
                } catch (IOException ignored) {
                    // keep text
                }
            }
        }
        Object b = m.get("bbox");
        if (b != null) {
            String t = b.toString().replace("{", "").replace("}", "");
            List<Double> a = new ArrayList<>();
            for (String x : t.split(",")) {
                a.add(Double.parseDouble(x.trim()));
            }
            m.put("bbox", a);
        }
    }

    public List<Map<String, Object>> issues(UUID id, String severity, int limit) {
        if (severity == null) {
            return jdbc.queryForList("SELECT severity, code, feature_id, message FROM dataset_issue WHERE dataset_id = ? "
                    + "ORDER BY severity, code LIMIT ?", id, limit);
        }
        return jdbc.queryForList("SELECT severity, code, feature_id, message FROM dataset_issue WHERE dataset_id = ? "
                + "AND severity = ? ORDER BY code LIMIT ?", id, severity, limit);
    }

    // ------------------------------------------------------------------ bulk load

    /** Streaming COPY writer into the dataset table. Not thread-safe; one per import. */
    public final class Loader implements AutoCloseable {
        private final Connection conn;
        private final CopyIn copy;
        private final String table;
        private final WKBWriter wkb = new WKBWriter(2, true);
        private final StringBuilder sb = new StringBuilder(1 << 16);
        private final int batchRows;
        private int pending;
        private long rows;

        Loader(UUID datasetId, int batchRows) throws SQLException {
            this.table = table(datasetId);
            this.batchRows = batchRows;
            this.conn = dataSource.getConnection();
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + table);
                st.execute("CREATE TABLE " + table + " (fid text NOT NULL, object_type text NOT NULL, "
                        + "restriction_type text, props jsonb NOT NULL, geom geometry(Geometry, 4326) NOT NULL, "
                        + "geom_utm geometry(Geometry, 32637) NOT NULL)");
            }
            this.copy = conn.unwrap(PGConnection.class).getCopyAPI().copyIn("COPY " + table
                    + " (fid, object_type, restriction_type, props, geom, geom_utm) FROM STDIN");
        }

        public void add(ParsedFeature f) {
            try {
                field(f.getId()).append('\t');
                field(f.getObjectType().getCode()).append('\t');
                if (f.getRestrictionType() == null) {
                    sb.append("\\N");
                } else {
                    field(f.getRestrictionType());
                }
                sb.append('\t');
                field(MAPPER.writeValueAsString(f.getProperties())).append('\t');
                sb.append(WKBWriter.toHex(wkb.write(f.getWgs84()))).append('\t');
                sb.append(WKBWriter.toHex(wkb.write(f.getUtm()))).append('\n');
                rows++;
                if (++pending >= batchRows) {
                    flush();
                }
            } catch (IOException | SQLException e) {
                throw new IllegalStateException("COPY failed: " + e.getMessage(), e);
            }
        }

        private StringBuilder field(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    default:
                        sb.append(c);
                }
            }
            return sb;
        }

        private void flush() throws SQLException {
            if (sb.length() > 0) {
                byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
                copy.writeToCopy(b, 0, b.length);
                sb.setLength(0);
            }
            pending = 0;
        }

        /** Finishes COPY and builds indexes. */
        public long complete() throws SQLException {
            flush();
            copy.endCopy();
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX ON " + table + " USING gist (geom_utm)");
                st.execute("CREATE INDEX ON " + table + " (object_type)");
                st.execute("CREATE INDEX ON " + table + " (fid)");
                st.execute("ANALYZE " + table);
            }
            return rows;
        }

        @Override
        public void close() throws SQLException {
            try {
                if (copy.isActive()) {
                    copy.cancelCopy();
                }
            } finally {
                conn.close();
            }
        }
    }

    public Loader loader(UUID datasetId, int batchRows) throws SQLException {
        return new Loader(datasetId, batchRows);
    }

    // ------------------------------------------------------------------ versions

    /** A new version of a dataset: same data plus the edits, rebuilt by {@link #rebuildVersion}. */
    public void createVersion(UUID id, UUID parentId, String editsJson, String note) {
        jdbc.update("INSERT INTO dataset(id, name, status, parent_id, root_id, version, edits, note, file_bytes) "
                        + "SELECT ?, p.name, 'IMPORTING', p.id, COALESCE(p.root_id, p.id), "
                        + "(SELECT max(x.version) + 1 FROM dataset x WHERE COALESCE(x.root_id, x.id) = "
                        + "COALESCE(p.root_id, p.id)), ?::jsonb, ?, p.file_bytes FROM dataset p WHERE p.id = ?",
                id, editsJson, note, parentId);
    }

    public void setEdits(UUID id, String editsJson) {
        jdbc.update("UPDATE dataset SET edits = ?::jsonb, status = 'IMPORTING', error = NULL WHERE id = ?", editsJson, id);
    }

    public void fail(UUID id, String error) {
        jdbc.update("UPDATE dataset SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ?", error, id);
    }

    public void setImportMillis(UUID id, long millis) {
        jdbc.update("UPDATE dataset SET import_millis = ? WHERE id = ?", millis, id);
    }

    public List<UUID> children(UUID id) {
        return jdbc.queryForList("SELECT id FROM dataset WHERE parent_id = ? ORDER BY version", UUID.class, id);
    }

    /** Attribute names a {@code set_attribute} edit may carry; the name goes into SQL, so it is checked again here. */
    private static final java.util.regex.Pattern ATTRIBUTE = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,40}");

    /**
     * Builds the table of a version: the parent's objects, then every edit applied to that copy — an added
     * restriction, an object removed or one attribute of an object changed — and the indexes; the issues of the
     * parent are kept (except those of its own edits and of removed objects) and the edits are noted.
     * Returns the number of objects.
     */
    public long rebuildVersion(UUID id, UUID parentId, List<Map<String, Object>> edits) throws IOException {
        String t = table(id);
        jdbc.execute("DROP TABLE IF EXISTS " + t);
        jdbc.execute("CREATE TABLE " + t + " AS SELECT * FROM " + table(parentId));
        List<String> removed = new ArrayList<>();
        for (Map<String, Object> e : edits) {
            String op = String.valueOf(e.getOrDefault("op", "add_restriction"));
            switch (op) {
                case "remove_object":
                    removeObject(t, e, removed);
                    break;
                case "set_attribute":
                    setAttribute(t, e);
                    break;
                default:
                    addRestriction(t, e);
            }
        }
        jdbc.execute("CREATE INDEX ON " + t + " USING gist (geom_utm)");
        jdbc.execute("CREATE INDEX ON " + t + " (object_type)");
        jdbc.execute("CREATE INDEX ON " + t + " (fid)");
        jdbc.execute("ANALYZE " + t);
        jdbc.update("DELETE FROM dataset_issue WHERE dataset_id = ?", id);
        jdbc.update("INSERT INTO dataset_issue(dataset_id, severity, code, feature_id, message) SELECT ?, severity, "
                + "code, feature_id, message FROM dataset_issue WHERE dataset_id = ? AND code NOT LIKE 'edit.%'",
                id, parentId);
        for (String fid : removed) {
            // the object is gone: its issues would point at nothing
            jdbc.update("DELETE FROM dataset_issue WHERE dataset_id = ? AND feature_id = ?", id, fid);
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> e : edits) {
            String op = String.valueOf(e.getOrDefault("op", "add_restriction"));
            switch (op) {
                case "remove_object":
                    rows.add(new Object[]{id, "INFO", "edit.object_removed", e.get("target_id"),
                            "Object " + e.get("target_id") + " (" + e.get("object_type")
                                    + ") removed by an edit of the input"});
                    break;
                case "set_attribute":
                    rows.add(new Object[]{id, "INFO", "edit.attribute_changed", e.get("target_id"),
                            "Attribute " + e.get("attribute") + " of " + e.get("target_id") + " set to "
                                    + e.get("value") + " by an edit of the input"});
                    break;
                default:
                    rows.add(new Object[]{id, "INFO", "edit.restriction_added", e.get("id"),
                            "Restriction " + e.get("restriction_type") + " added by an edit of the input"});
            }
        }
        jdbc.batchUpdate("INSERT INTO dataset_issue(dataset_id, severity, code, feature_id, message) VALUES (?,?,?,?,?)",
                rows);
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + t, Long.class);
        jdbc.update("UPDATE dataset d SET status = 'READY', finished_at = now(), feature_count = ?, imported_count = ?, "
                        + "type_counts = (SELECT jsonb_object_agg(object_type, c) FROM (SELECT object_type, count(*) c "
                        + "FROM " + t + " GROUP BY object_type) x), "
                        + "issue_counts = (SELECT jsonb_object_agg(code, c) FROM (SELECT code, count(*) c "
                        + "FROM dataset_issue WHERE dataset_id = ? GROUP BY code) y), "
                        + "bbox = (SELECT bbox FROM dataset p WHERE p.id = ?) WHERE d.id = ?",
                n, n, id, parentId, id);
        return n == null ? 0 : n;
    }

    /** An edit that adds a restriction: one more object of the input, marked as coming from an edit. */
    private void addRestriction(String t, Map<String, Object> e) throws IOException {
        String geom = MAPPER.writeValueAsString(e.get("geometry"));
        ObjectNode props = MAPPER.createObjectNode();
        props.put("id", (String) e.get("id"));
        props.put("object_type", "restriction");
        props.put("restriction_type", (String) e.get("restriction_type"));
        if (e.get("comment") != null) {
            props.put("comment", (String) e.get("comment"));
        }
        props.put("source", "edit");
        jdbc.update("INSERT INTO " + t + " (fid, object_type, restriction_type, props, geom, geom_utm) VALUES "
                        + "(?, 'restriction', ?, ?::jsonb, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), "
                        + "ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), 32637))",
                e.get("id"), e.get("restriction_type"), MAPPER.writeValueAsString(props), geom, geom);
    }

    /** An edit that excludes an object of the parent version. */
    private void removeObject(String t, Map<String, Object> e, List<String> removed) {
        String fid = String.valueOf(e.get("target_id"));
        if (jdbc.update("DELETE FROM " + t + " WHERE fid = ?", fid) == 0) {
            throw new IllegalStateException("Edit " + e.get("id") + ": object " + fid + " is not in the version");
        }
        removed.add(fid);
    }

    /**
     * An edit that changes one attribute of an object: the value in {@code props} and, where the table has one,
     * the typed column of the same attribute ({@code restriction_type}). A null value removes the attribute.
     */
    private void setAttribute(String t, Map<String, Object> e) throws IOException {
        String fid = String.valueOf(e.get("target_id"));
        String attr = String.valueOf(e.get("attribute"));
        if (!ATTRIBUTE.matcher(attr).matches()) {
            throw new IllegalStateException("Edit " + e.get("id") + ": bad attribute name " + attr);
        }
        Object value = e.get("value");
        boolean empty = value == null || value instanceof com.fasterxml.jackson.databind.JsonNode
                && ((com.fasterxml.jackson.databind.JsonNode) value).isNull();
        int changed = empty
                ? jdbc.update("UPDATE " + t + " SET props = props - '" + attr + "' WHERE fid = ?", fid)
                : jdbc.update("UPDATE " + t + " SET props = jsonb_set(props, '{" + attr + "}', ?::jsonb, true) "
                        + "WHERE fid = ?", MAPPER.writeValueAsString(value), fid);
        if (changed == 0) {
            throw new IllegalStateException("Edit " + e.get("id") + ": object " + fid + " is not in the version");
        }
        if ("restriction_type".equals(attr)) {
            jdbc.update("UPDATE " + t + " SET restriction_type = ? WHERE fid = ?",
                    empty ? null : String.valueOf(text(value)), fid);
        }
    }

    private static Object text(Object value) {
        return value instanceof com.fasterxml.jackson.databind.JsonNode
                ? ((com.fasterxml.jackson.databind.JsonNode) value).asText() : value;
    }

    /** What the checks of an edit need to know about the version the edit is applied to. */
    public interface Targets {
        /** Type of the object with this id, or {@code null} when the version has no such object. */
        String objectType(String fid);

        /** How many objects of this type the version has. */
        long count(String objectType);
    }

    /** Lookup of the objects of a dataset for the checks of the edits; answers are remembered per instance. */
    public Targets targets(UUID datasetId) {
        String t = table(datasetId);
        Map<String, String> types = new java.util.HashMap<>();
        Map<String, Long> counts = new java.util.HashMap<>();
        return new Targets() {
            @Override
            public String objectType(String fid) {
                String type = types.computeIfAbsent(fid, k -> {
                    List<String> l = jdbc.queryForList("SELECT object_type FROM " + t + " WHERE fid = ? LIMIT 1",
                            String.class, k);
                    return l.isEmpty() ? "" : l.get(0);
                });
                return type.isEmpty() ? null : type;
            }

            @Override
            public long count(String objectType) {
                return counts.computeIfAbsent(objectType, k -> jdbc.queryForObject("SELECT count(*) FROM " + t
                        + " WHERE object_type = ?", Long.class, k));
            }
        };
    }

    /** Ids of the dataset and of its ancestors, from the root down. */
    public List<Map<String, Object>> lineage(UUID id) {
        List<Map<String, Object>> out = new ArrayList<>();
        UUID cur = id;
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        while (cur != null && seen.add(cur)) {
            Map<String, Object> d = get(cur);
            if (d == null) {
                break;
            }
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", d.get("id"));
            m.put("version", d.get("version"));
            m.put("name", d.get("name"));
            m.put("note", d.get("note"));
            m.put("edits", d.get("edits"));
            out.add(0, m);
            cur = (UUID) d.get("parent_id");
        }
        return out;
    }

    // ------------------------------------------------------------------ overview

    /** What the dataset contains: counts by type, network by DN, OKS flows, restrictions by type. */
    public Map<String, Object> contents(UUID id) {
        String t = table(id);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        Map<String, Long> types = new java.util.TreeMap<>();
        jdbc.query("SELECT object_type, count(*) FROM " + t + " GROUP BY object_type",
                rs -> {
                    types.put(rs.getString(1), rs.getLong(2));
                });
        m.put("types", types);
        List<Map<String, Object>> byDn = jdbc.queryForList("SELECT CASE WHEN jsonb_typeof(props->'diameter') = "
                + "'number' THEN (props->>'diameter')::float8::int END AS dn, count(*) AS segments, "
                + "round(sum(ST_Length(geom_utm))::numeric, 1)::float8 AS length_m FROM " + t
                + " WHERE object_type = 'heat_network' GROUP BY 1 ORDER BY 1");
        m.put("network_by_dn", byDn);
        // prospective OKS: oks_future objects, or connection points that carry the flow themselves
        Map<String, Object> oks = jdbc.queryForMap("SELECT count(*) AS count, round(COALESCE(sum(f), 0)::numeric, 2)"
                + "::float8 AS flow_total, min(f) AS flow_min, max(f) AS flow_max FROM (SELECT CASE WHEN "
                + "jsonb_typeof(props->'flow_tph') = 'number' THEN (props->>'flow_tph')::float8 END AS f FROM " + t
                + " WHERE object_type = 'oks_future' OR (object_type = 'oks_connection_point' AND "
                + "(props->>'oks_id') IS NULL)) x");
        m.put("oks", oks);
        m.put("restrictions", jdbc.queryForList("SELECT restriction_type AS type, count(*) AS count, "
                + "count(*) FILTER (WHERE props->>'source' = 'edit') AS edits FROM " + t
                + " WHERE object_type = 'restriction' GROUP BY restriction_type ORDER BY count(*) DESC"));
        return m;
    }

    /** Issues grouped by code with their severity. */
    public List<Map<String, Object>> issueCodes(UUID id) {
        return jdbc.queryForList("SELECT code, min(severity) AS severity, count(*) AS count, count(feature_id) AS "
                + "with_features FROM dataset_issue WHERE dataset_id = ? GROUP BY code ORDER BY min(severity), "
                + "count(*) DESC", id);
    }

    /** Streams the objects referred to by the issues of one code as GeoJSON. */
    public void writeIssueFeatures(UUID id, String code, java.io.OutputStream out) throws IOException {
        String sql = "SELECT t.fid, t.object_type, t.props::text, ST_AsGeoJSON(t.geom, 8) FROM " + table(id) + " t "
                + "WHERE t.fid IN (SELECT feature_id FROM dataset_issue WHERE dataset_id = ? AND code = ? "
                + "AND feature_id IS NOT NULL LIMIT 5000)";
        try (com.fasterxml.jackson.core.JsonGenerator g = MAPPER.getFactory().createGenerator(out,
                com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeArrayFieldStart("features");
            jdbc.query(sql, rs -> {
                try {
                    g.writeStartObject();
                    g.writeStringField("type", "Feature");
                    g.writeStringField("id", rs.getString(1));
                    g.writeFieldName("properties");
                    g.writeRawValue(rs.getString(3));
                    g.writeFieldName("geometry");
                    g.writeRawValue(rs.getString(4));
                    g.writeEndObject();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }, id, code);
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    // ------------------------------------------------------------------ read back

    /** Core objects (network, chambers, OKS, connection points, source) of a dataset. */
    public List<ParsedFeature> coreFeatures(UUID datasetId) {
        String sql = "SELECT fid, object_type, restriction_type, props::text, ST_AsBinary(geom), ST_AsBinary(geom_utm) "
                + "FROM " + table(datasetId) + " WHERE object_type NOT IN ('oks_existing', 'restriction')";
        List<ParsedFeature> out = new ArrayList<>();
        WKBReader r4326 = new WKBReader(Crs.WGS84_FACTORY);
        WKBReader rUtm = new WKBReader(Crs.UTM_FACTORY);
        long[] seq = {0};
        jdbc.query(sql, rs -> {
            try {
                out.add(new ParsedFeature(++seq[0], rs.getString(1), ObjectType.fromCode(rs.getString(2)),
                        rs.getString(3), (ObjectNode) MAPPER.readTree(rs.getString(4)),
                        r4326.read(rs.getBytes(5)), rUtm.read(rs.getBytes(6))));
            } catch (IOException | ParseException e) {
                throw new IllegalStateException(e);
            }
        });
        return out;
    }

    /** Ids used by more than one feature of the dataset (up to {@code limit}). */
    public List<String> duplicateIds(UUID datasetId, int limit) {
        return jdbc.queryForList("SELECT fid FROM " + table(datasetId) + " GROUP BY fid HAVING count(*) > 1 LIMIT ?",
                String.class, limit);
    }

    /** Existing buildings and restrictions intersecting an area, straight from the spatial index. */
    public ObstacleSource obstacleSource(UUID datasetId) {
        String sql = "SELECT fid, object_type, restriction_type, ST_AsBinary(geom_utm) FROM " + table(datasetId)
                + " WHERE object_type IN ('oks_existing', 'restriction') AND geom_utm && ST_GeomFromWKB(?, 32637) "
                + "AND ST_Intersects(geom_utm, ST_GeomFromWKB(?, 32637)) ORDER BY fid COLLATE \"C\"";
        // called from several planning threads at once: no shared writers or readers
        return area -> {
            byte[] a = new WKBWriter(2, false).write(area);
            WKBReader reader = new WKBReader(Crs.UTM_FACTORY);
            List<Obstacle> out = new ArrayList<>();
            jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(sql);
                ps.setBytes(1, a);
                ps.setBytes(2, a);
                return ps;
            }, (ResultSet rs) -> {
                try {
                    Geometry g = reader.read(rs.getBytes(4));
                    out.add(new Obstacle(rs.getString(1), ObjectType.fromCode(rs.getString(2)), rs.getString(3), g));
                } catch (ParseException e) {
                    throw new IllegalStateException(e);
                }
            });
            return out;
        };
    }
}
