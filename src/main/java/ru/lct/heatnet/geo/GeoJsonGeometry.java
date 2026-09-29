package ru.lct.heatnet.geo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;

/** Conversion between GeoJSON geometry objects and JTS (2D; Z is ignored on read). */
public final class GeoJsonGeometry {

    private GeoJsonGeometry() {
    }

    public static Geometry read(JsonNode g, GeometryFactory f) {
        if (g == null || g.isNull()) {
            return null;
        }
        String type = text(g, "type");
        JsonNode c = g.get("coordinates");
        switch (type) {
            case "Point":
                return f.createPoint(coord(c));
            case "LineString":
                return f.createLineString(coords(c));
            case "Polygon":
                return polygon(c, f);
            case "MultiPoint": {
                Point[] pts = new Point[c.size()];
                for (int i = 0; i < c.size(); i++) {
                    pts[i] = f.createPoint(coord(c.get(i)));
                }
                return f.createMultiPoint(pts);
            }
            case "MultiLineString": {
                LineString[] ls = new LineString[c.size()];
                for (int i = 0; i < c.size(); i++) {
                    ls[i] = f.createLineString(coords(c.get(i)));
                }
                return f.createMultiLineString(ls);
            }
            case "MultiPolygon": {
                Polygon[] ps = new Polygon[c.size()];
                for (int i = 0; i < c.size(); i++) {
                    ps[i] = polygon(c.get(i), f);
                }
                return f.createMultiPolygon(ps);
            }
            case "GeometryCollection": {
                JsonNode gs = g.get("geometries");
                Geometry[] parts = new Geometry[gs.size()];
                for (int i = 0; i < gs.size(); i++) {
                    parts[i] = read(gs.get(i), f);
                }
                return f.createGeometryCollection(parts);
            }
            default:
                throw new IllegalArgumentException("Unsupported geometry type: " + type);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) {
            throw new IllegalArgumentException("Geometry without '" + field + "'");
        }
        return v.asText();
    }

    private static Polygon polygon(JsonNode rings, GeometryFactory f) {
        if (rings == null || rings.size() == 0) {
            throw new IllegalArgumentException("Polygon without rings");
        }
        LinearRing shell = f.createLinearRing(coords(rings.get(0)));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = f.createLinearRing(coords(rings.get(i)));
        }
        return f.createPolygon(shell, holes);
    }

    private static Coordinate coord(JsonNode c) {
        if (c == null || !c.isArray() || c.size() < 2 || !c.get(0).isNumber() || !c.get(1).isNumber()) {
            throw new IllegalArgumentException("Bad coordinate: " + c);
        }
        return new Coordinate(c.get(0).asDouble(), c.get(1).asDouble());
    }

    private static Coordinate[] coords(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("Bad coordinate array");
        }
        Coordinate[] out = new Coordinate[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = coord(arr.get(i));
        }
        return out;
    }

    /** Writes a geometry as a GeoJSON object; coordinates are rounded to {@code decimals}. */
    public static void write(JsonGenerator gen, Geometry g, int decimals) throws IOException {
        if (g == null) {
            gen.writeNull();
            return;
        }
        gen.writeStartObject();
        if (g instanceof Point) {
            gen.writeStringField("type", "Point");
            gen.writeFieldName("coordinates");
            writeCoord(gen, g.getCoordinate(), decimals);
        } else if (g instanceof LineString) {
            gen.writeStringField("type", "LineString");
            gen.writeFieldName("coordinates");
            writeCoords(gen, g.getCoordinates(), decimals);
        } else if (g instanceof Polygon) {
            gen.writeStringField("type", "Polygon");
            gen.writeFieldName("coordinates");
            writePolygon(gen, (Polygon) g, decimals);
        } else if (g instanceof MultiPoint || g instanceof MultiLineString || g instanceof MultiPolygon) {
            gen.writeStringField("type", g.getGeometryType());
            gen.writeFieldName("coordinates");
            gen.writeStartArray();
            for (int i = 0; i < g.getNumGeometries(); i++) {
                Geometry part = g.getGeometryN(i);
                if (part instanceof Point) {
                    writeCoord(gen, part.getCoordinate(), decimals);
                } else if (part instanceof LineString) {
                    writeCoords(gen, part.getCoordinates(), decimals);
                } else {
                    writePolygon(gen, (Polygon) part, decimals);
                }
            }
            gen.writeEndArray();
        } else {
            throw new IllegalArgumentException("Unsupported geometry " + g.getGeometryType());
        }
        gen.writeEndObject();
    }

    private static void writePolygon(JsonGenerator gen, Polygon p, int decimals) throws IOException {
        gen.writeStartArray();
        writeCoords(gen, p.getExteriorRing().getCoordinates(), decimals);
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            writeCoords(gen, p.getInteriorRingN(i).getCoordinates(), decimals);
        }
        gen.writeEndArray();
    }

    private static void writeCoords(JsonGenerator gen, Coordinate[] cs, int decimals) throws IOException {
        gen.writeStartArray();
        for (Coordinate c : cs) {
            writeCoord(gen, c, decimals);
        }
        gen.writeEndArray();
    }

    private static void writeCoord(JsonGenerator gen, Coordinate c, int decimals) throws IOException {
        gen.writeStartArray();
        gen.writeNumber(round(c.x, decimals));
        gen.writeNumber(round(c.y, decimals));
        if (!Double.isNaN(c.getZ())) {
            gen.writeNumber(round(c.getZ(), 3));
        }
        gen.writeEndArray();
    }

    public static double round(double v, int decimals) {
        double m = Math.pow(10, decimals);
        return Math.round(v * m) / m;
    }
}
