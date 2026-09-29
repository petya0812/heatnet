package ru.lct.heatnet.service;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.Array;
import java.util.List;
import java.util.UUID;

/**
 * Map data of a dataset straight from PostGIS: Mapbox vector tiles ({@code ST_AsMVT}) of the input layers, the
 * extent, single features. Tiles are cut with the spatial index on {@code geom_utm}, so the map never loads the whole
 * dataset; small layers are shown from low zooms, dense ones (buildings, restrictions) only when zoomed in.
 */
@Repository
public class MapRepository {

    /** Tiles are generated up to this zoom; the map over-zooms beyond it. */
    public static final int MAX_ZOOM = 16;
    /** Below this zoom tiles are empty (a city-scale dataset; the UTM zone transform needs a bounded tile). */
    public static final int MIN_ZOOM = 9;
    public static final int BUILDINGS_MIN_ZOOM = 14;
    public static final int RESTRICTIONS_MIN_ZOOM = 13;
    public static final int POINTS_MIN_ZOOM = 13;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public MapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Vector tile of the input layers (EPSG:3857 tile grid). */
    public byte[] tile(UUID datasetId, int z, int x, int y) {
        if (z < 0 || z > 24 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) {
            throw new IllegalArgumentException("Tile out of range");
        }
        if (z < MIN_ZOOM) {
            return new byte[0];
        }
        String t = DatasetRepository.table(datasetId);
        StringBuilder sql = new StringBuilder();
        sql.append("WITH b AS (SELECT ST_TileEnvelope(?, ?, ?) AS env), ")
                // index filter in UTM; the margin covers the curvature of the tile edges and the MVT buffer
                .append("u AS (SELECT ST_Expand(ST_Transform(env, 32637), ")
                .append("(ST_XMax(env) - ST_XMin(env)) * 0.1) AS g FROM b) SELECT ");
        String num = "CASE WHEN jsonb_typeof(t.props->'%1$s') = 'number' THEN (t.props->>'%1$s')::float8 END";
        sql.append(layer("network", t, "t.object_type = 'heat_network'",
                String.format(num, "diameter") + " AS diameter, " + String.format(num, "flow_tph") + " AS flow_tph, "
                        + "t.props->>'upstream_object_id' AS upstream_object_id"));
        sql.append(" || ").append(layer("oks", t, "t.object_type = 'oks_future'",
                String.format(num, "flow_tph") + " AS flow_tph, " + String.format(num, "heat_load") + " AS heat_load"));
        sql.append(" || ").append(layer("source", t, "t.object_type = 'source'", null));
        if (z >= POINTS_MIN_ZOOM) {
            sql.append(" || ").append(layer("chambers", t, "t.object_type = 'heat_chamber'",
                    String.format(num, "diameter") + " AS diameter, t.props->>'upstream_object_id' AS upstream_object_id"));
            sql.append(" || ").append(layer("connection_points", t, "t.object_type = 'oks_connection_point'",
                    "t.props->>'oks_id' AS oks_id"));
        }
        if (z >= RESTRICTIONS_MIN_ZOOM) {
            sql.append(" || ").append(layer("restrictions", t, "t.object_type = 'restriction'",
                    "t.restriction_type AS restriction_type, (t.props->>'source') AS source, "
                            + "t.props->>'comment' AS comment"));
        }
        if (z >= BUILDINGS_MIN_ZOOM) {
            sql.append(" || ").append(layer("buildings", t, "t.object_type = 'oks_existing'", null));
        }
        byte[] out = jdbc.queryForObject(sql.toString(), byte[].class, z, x, y);
        return out == null ? new byte[0] : out;
    }

    private static String layer(String name, String table, String where, String attrs) {
        return "COALESCE((SELECT ST_AsMVT(q, '" + name + "', 4096, 'geom') FROM (SELECT t.fid AS id, "
                + (attrs == null ? "" : attrs + ", ")
                + "ST_AsMVTGeom(ST_Transform(t.geom, 3857), b.env, 4096, 64, true) AS geom FROM " + table + " t, b, u "
                + "WHERE " + where + " AND t.geom_utm && u.g) q WHERE q.geom IS NOT NULL), ''::bytea)";
    }

    /** Extent of the dataset [minLon, minLat, maxLon, maxLat], computed once and stored. */
    public double[] bbox(UUID datasetId) {
        List<Array> stored = jdbc.queryForList("SELECT bbox FROM dataset WHERE id = ?", Array.class, datasetId);
        if (stored.isEmpty()) {
            throw new NotFoundException("Dataset " + datasetId + " not found");
        }
        try {
            if (stored.get(0) != null) {
                Object[] a = (Object[]) stored.get(0).getArray();
                return new double[]{(Double) a[0], (Double) a[1], (Double) a[2], (Double) a[3]};
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        double[] b = jdbc.queryForObject("SELECT ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) FROM (SELECT "
                        + "ST_Extent(geom) AS e FROM " + DatasetRepository.table(datasetId) + ") s",
                (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)});
        jdbc.update("UPDATE dataset SET bbox = ARRAY[?, ?, ?, ?]::double precision[] WHERE id = ?",
                b[0], b[1], b[2], b[3], datasetId);
        return b;
    }

    /** Features of the dataset with this id (normally one; duplicates are a data issue) as GeoJSON. */
    public void writeFeatures(UUID datasetId, String fid, OutputStream out) throws IOException {
        String sql = "SELECT fid, object_type, props::text, ST_AsGeoJSON(geom, 8) FROM "
                + DatasetRepository.table(datasetId) + " WHERE fid = ? LIMIT 100";
        try (JsonGenerator g = MAPPER.getFactory().createGenerator(out, JsonEncoding.UTF8)) {
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
                    throw new UncheckedIOException(e);
                }
            }, fid);
            g.writeEndArray();
            g.writeEndObject();
        }
    }
}
