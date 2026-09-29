package ru.lct.heatnet.plan;

import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fast intersection of a straight segment with a fixed geometry: parameters {@code t ∈ [0,1]} along the segment
 * where it meets the geometry's lines/rings, and the parts of the segment inside a polygon. Replaces generic JTS
 * overlay in the hot loop of the router.
 */
final class SegmentClipper {

    /** Intersection with one edge of the geometry. */
    static final class Hit {
        final double t;
        final double ex;
        final double ey;

        Hit(double t, double ex, double ey) {
            this.t = t;
            this.ex = ex;
            this.ey = ey;
        }
    }

    private static final int BRUTE_FORCE_EDGES = 48;

    private final STRtree edges = new STRtree();
    private final double[][] edgeArray;
    private final IndexedPointInAreaLocator locator;
    private final boolean empty;

    SegmentClipper(Geometry g) {
        List<LineString> lines = new ArrayList<>();
        collect(g, lines);
        List<double[]> all = new ArrayList<>();
        for (LineString l : lines) {
            Coordinate[] cs = l.getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                all.add(new double[]{cs[i].x, cs[i].y, cs[i + 1].x, cs[i + 1].y});
            }
        }
        if (all.size() <= BRUTE_FORCE_EDGES) {
            this.edgeArray = all.toArray(new double[0][]);
        } else {
            this.edgeArray = null;
            for (double[] e : all) {
                edges.insert(new Envelope(e[0], e[2], e[1], e[3]), e);
            }
        }
        edges.build();
        this.empty = all.isEmpty();
        this.locator = g instanceof Polygonal && !g.isEmpty() ? new IndexedPointInAreaLocator(g) : null;
        if (locator != null) {
            // the locator builds its index on the first call; do it here, while only this thread has the object
            locator.locate(new Coordinate(Double.MAX_VALUE, Double.MAX_VALUE));
        }
    }

    private static void collect(Geometry g, List<LineString> out) {
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry p = g.getGeometryN(i);
            if (p instanceof Polygon) {
                Polygon poly = (Polygon) p;
                out.add(poly.getExteriorRing());
                for (int h = 0; h < poly.getNumInteriorRing(); h++) {
                    out.add(poly.getInteriorRingN(h));
                }
            } else if (p instanceof LineString) {
                out.add((LineString) p);
            } else if (p.getNumGeometries() > 1) {
                collect(p, out);
            }
        }
    }

    /** Intersections of segment a→b with the edges, sorted by t. */
    List<Hit> hits(Coordinate a, Coordinate b) {
        if (empty) {
            return Collections.emptyList();
        }
        List<Hit> out = new ArrayList<>();
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        Envelope env = new Envelope(a, b);
        List<?> candidates = edgeArray != null ? java.util.Arrays.asList(edgeArray) : edges.query(env);
        for (Object o : candidates) {
            double[] e = (double[]) o;
            if (edgeArray != null && (Math.max(e[0], e[2]) < env.getMinX() || Math.min(e[0], e[2]) > env.getMaxX()
                    || Math.max(e[1], e[3]) < env.getMinY() || Math.min(e[1], e[3]) > env.getMaxY())) {
                continue;
            }
            double ex = e[2] - e[0];
            double ey = e[3] - e[1];
            double denom = dx * ey - dy * ex;
            double px = e[0] - a.x;
            double py = e[1] - a.y;
            double len2 = dx * dx + dy * dy;
            if (Math.abs(denom) < 1e-12 * Math.sqrt(len2 * (ex * ex + ey * ey)) + 1e-18) {
                // parallel: overlapping collinear edges contribute their ends
                if (Math.abs(px * dy - py * dx) > 1e-9 * Math.sqrt(len2) + 1e-12) {
                    continue;
                }
                double t0 = (px * dx + py * dy) / len2;
                double t1 = ((e[2] - a.x) * dx + (e[3] - a.y) * dy) / len2;
                double lo = Math.max(0, Math.min(t0, t1));
                double hi = Math.min(1, Math.max(t0, t1));
                if (lo <= hi) {
                    out.add(new Hit(lo, ex, ey));
                    out.add(new Hit(hi, ex, ey));
                }
                continue;
            }
            double t = (px * ey - py * ex) / denom;
            double u = (px * dy - py * dx) / denom;
            if (t >= -1e-12 && t <= 1 + 1e-12 && u >= -1e-12 && u <= 1 + 1e-12) {
                out.add(new Hit(Math.max(0, Math.min(1, t)), ex, ey));
            }
        }
        out.sort((x, y) -> Double.compare(x.t, y.t));
        return out;
    }

    /** Parameter intervals of segment a→b lying inside the polygon (boundary included). Polygons only. */
    List<double[]> inside(Coordinate a, Coordinate b, List<Hit> hits) {
        List<double[]> out = new ArrayList<>();
        if (locator == null) {
            return out;
        }
        List<Double> ts = new ArrayList<>();
        ts.add(0.0);
        for (Hit h : hits) {
            ts.add(h.t);
        }
        ts.add(1.0);
        double[] cur = null;
        Coordinate mid = new Coordinate();
        for (int i = 0; i + 1 < ts.size(); i++) {
            double t0 = ts.get(i);
            double t1 = ts.get(i + 1);
            if (t1 - t0 < 1e-12) {
                continue;
            }
            double tm = (t0 + t1) / 2;
            mid.x = a.x + (b.x - a.x) * tm;
            mid.y = a.y + (b.y - a.y) * tm;
            boolean in = locator.locate(mid) != Location.EXTERIOR;
            if (in) {
                if (cur != null && Math.abs(cur[1] - t0) < 1e-12) {
                    cur[1] = t1;
                } else {
                    cur = new double[]{t0, t1};
                    out.add(cur);
                }
            }
        }
        return out;
    }

    /** true if the segment touches or crosses the geometry (polygons: also lies inside). */
    boolean intersects(Coordinate a, Coordinate b) {
        return !hits(a, b).isEmpty() || contains(a);
    }

    boolean contains(Coordinate c) {
        return locator != null && locator.locate(c) != Location.EXTERIOR;
    }
}
