package ru.lct.heatnet.service;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Copies of journal events with the coordinates ({@code coords}, {@code point}) turned from UTM into WGS 84. */
final class JournalGeo {

    private JournalGeo() {
    }

    static List<Object> toWgs84(List<?> events, UnaryOperator<Coordinate> tr) {
        List<Object> out = new ArrayList<>(events.size());
        for (Object e : events) {
            out.add(convert(null, e, tr));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object convert(String key, Object v, UnaryOperator<Coordinate> tr) {
        if (v instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                m.put(e.getKey(), convert(e.getKey(), e.getValue(), tr));
            }
            return m;
        }
        if ("point".equals(key) && v instanceof double[]) {
            return point((double[]) v, tr);
        }
        if ("coords".equals(key) && v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object p : (List<Object>) v) {
                out.add(p instanceof double[] ? point((double[]) p, tr) : p);
            }
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object x : (List<Object>) v) {
                out.add(convert(null, x, tr));
            }
            return out;
        }
        return v;
    }

    private static double[] point(double[] p, UnaryOperator<Coordinate> tr) {
        Coordinate c = tr.apply(new Coordinate(p[0], p[1]));
        return new double[]{Math.round(c.x * 1e7) / 1e7, Math.round(c.y * 1e7) / 1e7};
    }
}
