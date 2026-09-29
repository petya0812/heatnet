package ru.lct.heatnet.service;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.geo.GeoJsonGeometry;
import ru.lct.heatnet.plan.Unconnected;
import ru.lct.heatnet.plan.Variant;

import javax.sql.DataSource;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * Result of a run in PostGIS ({@code data.run_<id>}) for the map: the features of the output file as delivered,
 * plus layers derived for the map and the API (flow changes of the existing network, continuous parts of one DN,
 * unconnected OKS with reasons). The output file itself stays the source of the download.
 */
@Repository
public class ResultStore {

    /** Object types added for the map only; they are never written to the output file. */
    public static final String FLOW_CHANGE = "flow_change";
    /** Where a part of the new network joins the existing one; not an object of the output file. */
    public static final String ATTACHMENT = "attachment";
    public static final String LENGTH_RUN = "length_run";
    public static final String UNCONNECTED_OKS = "unconnected_oks";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public ResultStore(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public static String table(UUID runId) {
        return "data.run_" + runId.toString().replace("-", "");
    }

    /** Loads the output file and the derived layers of the variants (null — output file only). */
    public void store(UUID runId, UUID datasetId, Path resultFile, List<Variant> variants)
            throws IOException, SQLException {
        String t = table(runId);
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + t);
                st.execute("CREATE TABLE " + t + " (variant_id text NOT NULL, fid text, object_type text NOT NULL, "
                        + "derived boolean NOT NULL, props jsonb NOT NULL, geom geometry(Geometry, 4326))");
            }
            CopyIn copy = conn.unwrap(PGConnection.class).getCopyAPI()
                    .copyIn("COPY " + t + " (variant_id, fid, object_type, derived, props, geom) FROM STDIN");
            Rows rows = new Rows(copy);
            try {
                copyOutput(resultFile, rows);
                if (variants != null) {
                    for (Variant v : variants) {
                        derived(v, rows);
                    }
                }
                rows.flush();
                copy.endCopy();
            } finally {
                if (copy.isActive()) {
                    copy.cancelCopy();
                }
            }
            try (Statement st = conn.createStatement()) {
                // unconnected OKS are drawn with their footprint from the dataset
                st.execute("UPDATE " + t + " r SET geom = d.geom FROM " + DatasetRepository.table(datasetId)
                        + " d WHERE r.object_type = '" + UNCONNECTED_OKS + "' AND d.object_type = 'oks_future' "
                        + "AND d.fid = r.fid");
                // without a footprint (the contest dataset): the connection point of the OKS
                st.execute("UPDATE " + t + " r SET geom = d.geom FROM " + DatasetRepository.table(datasetId)
                        + " d WHERE r.object_type = '" + UNCONNECTED_OKS + "' AND r.geom IS NULL AND "
                        + "d.object_type = 'oks_connection_point' AND (d.fid = r.fid OR d.props->>'oks_id' = r.fid)");
                st.execute("CREATE INDEX ON " + t + " (variant_id)");
            }
        }
        jdbc.update("UPDATE run SET result_table = true WHERE id = ?", runId);
    }

    public void drop(UUID runId) {
        jdbc.execute("DROP TABLE IF EXISTS " + table(runId));
    }

    /** Streams the features of one variant as a GeoJSON FeatureCollection (EPSG:4326). */
    public void writeVariant(UUID runId, String variantId, boolean withDerived, OutputStream out) throws IOException {
        String sql = "SELECT fid, object_type, derived, props::text, ST_AsGeoJSON(geom, 8) FROM " + table(runId)
                + " WHERE variant_id = ?" + (withDerived ? "" : " AND NOT derived") + " ORDER BY derived, object_type";
        try (JsonGenerator g = MAPPER.getFactory().createGenerator(out, JsonEncoding.UTF8)) {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeArrayFieldStart("features");
            jdbc.query(sql, rs -> {
                try {
                    g.writeStartObject();
                    g.writeStringField("type", "Feature");
                    if (rs.getString(1) != null) {
                        g.writeStringField("id", rs.getString(1));
                    }
                    g.writeFieldName("properties");
                    ObjectNode props = (ObjectNode) MAPPER.readTree(rs.getString(4));
                    props.put("object_type", rs.getString(2));
                    props.put("derived", rs.getBoolean(3));
                    g.writeTree(props);
                    g.writeFieldName("geometry");
                    String geom = rs.getString(5);
                    if (geom == null) {
                        g.writeNull();
                    } else {
                        g.writeRawValue(geom);
                    }
                    g.writeEndObject();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, variantId);
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    // ------------------------------------------------------------------ loading

    private static void copyOutput(Path file, Rows rows) throws IOException, SQLException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
             JsonParser p = MAPPER.getFactory().createParser(in)) {
            while (p.nextToken() != null) {
                if (p.currentToken() == JsonToken.FIELD_NAME && "features".equals(p.getCurrentName())) {
                    p.nextToken();
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode f = MAPPER.readTree(p);
                        ObjectNode props = (ObjectNode) f.path("properties");
                        String variant = props.path("variant_id").asText();
                        String type = props.path("object_type").asText();
                        JsonNode g = f.get("geometry");
                        Geometry geom = g == null || g.isNull() ? null : GeoJsonGeometry.read(g, Crs.WGS84_FACTORY);
                        rows.add(variant, props.path("id").asText(null), type, false, props, geom);
                    }
                    return;
                }
            }
        }
    }

    private static void derived(Variant v, Rows rows) throws SQLException, IOException {
        String p = "v" + v.variantId + "-";
        int n = 0;
        for (Variant.FlowChange fc : v.flowChanges) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("existing_object_id", fc.existingObjectId);
            o.put("existing_flow_tph", round(fc.existingFlowTph));
            o.put("added_flow_tph", round(fc.addedFlowTph));
            o.put("calculated_flow_tph", round(fc.existingFlowTph + fc.addedFlowTph));
            o.put("existing_diameter", fc.existingDn);
            o.put("required_diameter", fc.requiredDn);
            o.put("reconstructed", fc.requiredDn > fc.existingDn);
            o.put("length", round(fc.line.getLength()));
            rows.add(v.variantId, p + "flow-" + (++n), FLOW_CHANGE, true, o, Crs.toWgs84(fc.line));
        }
        n = 0;
        for (Variant.TieIn t : v.tieIns) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("existing_object_id", t.existingObjectId);
            o.put("existing_object_type", t.existingObjectType);
            o.put("existing_diameter", t.existingDn);
            o.put("diameter", t.requiredDn);
            o.put("flow_tph", round(t.flowTph));
            o.put("existing_chamber", t.existingChamber);
            o.put("cost", round(t.cost));
            rows.add(v.variantId, t.id, ATTACHMENT, true, o, Crs.toWgs84(t.point));
        }
        n = 0;
        for (Variant.LengthRun r : v.lengthRuns) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("diameter", r.dn);
            o.put("path_length", round(r.pathLength));
            o.put("total_length", round(r.totalLength));
            o.put("measured_length", round(r.measured));
            o.put("max_length", r.limit);
            o.put("usage", r.limit > 0 ? round(r.measured / r.limit) : 0);
            o.put("segments", String.join(",", r.segmentIds));
            rows.add(v.variantId, p + "run-" + (++n), LENGTH_RUN, true, o, Crs.toWgs84(r.line));
        }
        for (Unconnected u : v.unconnected) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("oks_id", u.oksId);
            o.put("flow_tph", u.flowTph);
            o.put("reason", u.reason.name());
            o.put("reason_text", u.reason.text());
            if (u.detail != null) {
                o.put("detail", u.detail);
            }
            rows.add(v.variantId, u.oksId, UNCONNECTED_OKS, true, o, null);
        }
    }

    private static double round(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    /** COPY text rows, flushed in batches. */
    private static final class Rows {
        private final CopyIn copy;
        private final WKBWriter wkb = new WKBWriter(2, true);
        private final StringBuilder sb = new StringBuilder(1 << 16);

        Rows(CopyIn copy) {
            this.copy = copy;
        }

        void add(String variant, String fid, String type, boolean derived, JsonNode props, Geometry geom)
                throws SQLException, IOException {
            field(variant).append('\t');
            if (fid == null) {
                sb.append("\\N");
            } else {
                field(fid);
            }
            sb.append('\t');
            field(type).append('\t').append(derived ? 't' : 'f').append('\t');
            field(MAPPER.writeValueAsString(props)).append('\t');
            if (geom == null) {
                sb.append("\\N");
            } else {
                geom.setSRID(Crs.WGS84);
                sb.append(WKBWriter.toHex(wkb.write(geom)));
            }
            sb.append('\n');
            if (sb.length() > (1 << 20)) {
                flush();
            }
        }

        private StringBuilder field(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\') {
                    sb.append("\\\\");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else {
                    sb.append(c);
                }
            }
            return sb;
        }

        void flush() throws SQLException {
            if (sb.length() > 0) {
                byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
                copy.writeToCopy(b, 0, b.length);
                sb.setLength(0);
            }
        }
    }
}
