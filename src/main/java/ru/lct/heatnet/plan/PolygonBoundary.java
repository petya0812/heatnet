package ru.lct.heatnet.plan;

import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.List;

/**
 * Crossing angle of a straight route piece with a polygon obstacle (a road or tram tracks given as a polygon).
 * For a polygonal restriction the angle is checked at the point where the special section enters, against the
 * boundary the route passes through (appendix 4, clarification 6). The direction of the boundary is taken over a
 * stretch of {@link #WINDOW_M} on both sides of that point, so short breaks of the outline do not decide the angle.
 *
 * The planner and the output validator use this class, so both measure the same angle.
 */
public final class PolygonBoundary {

    /** Neighbouring edges are joined into one direction while they turn by less than this, degrees. */
    public static final double COLLINEAR_DEG = 20.0;

    /**
     * A piece that stays inside the polygon for less than this is grazing its outline, not crossing it: the
     * angle rule applies to a crossing, and the distance rule keeps the route away from the outline anyway.
     */
    public static final double MIN_CROSSING_M = 0.2;

    private static final class Ring {
        final Coordinate[] cs;
        /** Distance along the ring to every vertex. */
        final double[] cum;

        Ring(Coordinate[] cs) {
            this.cs = cs;
            this.cum = new double[cs.length];
            for (int i = 1; i < cs.length; i++) {
                cum[i] = cum[i - 1] + cs[i - 1].distance(cs[i]);
            }
        }

        double length() {
            return cum[cum.length - 1];
        }

        Coordinate at(double pos) {
            double len = length();
            pos = ((pos % len) + len) % len;
            int lo = 0;
            int hi = cum.length - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (cum[mid] <= pos) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double seg = cum[hi] - cum[lo];
            double f = seg > 0 ? (pos - cum[lo]) / seg : 0;
            return new Coordinate(cs[lo].x + (cs[hi].x - cs[lo].x) * f, cs[lo].y + (cs[hi].y - cs[lo].y) * f);
        }
    }

    private final List<Ring> rings = new ArrayList<>();
    /** Edges of all rings: {ring index, vertex index}. */
    private final STRtree edges = new STRtree();
    private final SegmentClipper clipper;

    public PolygonBoundary(Geometry polygonal) {
        this(polygonal, new SegmentClipper(polygonal));
    }

    PolygonBoundary(Geometry polygonal, SegmentClipper clipperOfPolygon) {
        for (int i = 0; i < polygonal.getNumGeometries(); i++) {
            Geometry g = polygonal.getGeometryN(i);
            if (!(g instanceof Polygon)) {
                continue;
            }
            Polygon p = (Polygon) g;
            addRing(p.getExteriorRing());
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                addRing(p.getInteriorRingN(h));
            }
        }
        edges.build();
        clipper = clipperOfPolygon;
    }

    private void addRing(LineString ring) {
        Ring r = new Ring(ring.getCoordinates());
        if (r.cs.length < 2 || r.length() <= 0) {
            return;
        }
        int ri = rings.size();
        rings.add(r);
        for (int k = 0; k + 1 < r.cs.length; k++) {
            edges.insert(new Envelope(r.cs[k], r.cs[k + 1]), new int[]{ri, k});
        }
    }

    /** Angles (0–90°) of every crossing of the piece a→b with the polygon, in order along the piece. */
    public List<Double> crossingAngles(Coordinate a, Coordinate b) {
        List<Double> out = new ArrayList<>();
        double len = a.distance(b);
        if (len < 1e-9) {
            return out;
        }
        List<SegmentClipper.Hit> hits = clipper.hits(a, b);
        if (hits.isEmpty()) {
            return out;
        }
        List<double[]> inside = clipper.inside(a, b, hits);
        double tol = 1e-6;
        List<double[]> used = new ArrayList<>();
        for (double[] in : inside) {
            if ((in[1] - in[0]) * len < MIN_CROSSING_M) {
                used.add(in);
                continue;
            }
            // the angle is taken where the route enters the polygon; if the piece starts inside, the exit
            // boundary is the only one it passes through
            double[] dir = null;
            if (onBoundary(a, b, in[0], hits)) {
                dir = directionAt(point(a, b, in[0]));
            } else if (in[1] - in[0] > tol && onBoundary(a, b, in[1], hits)) {
                dir = directionAt(point(a, b, in[1]));
            }
            if (dir != null) {
                out.add(angle(a, b, dir));
                // the section has to cross the polygon, not run along inside it: in the middle of the inside
                // stretch the route must not be nearly parallel to the outline next to it either
                Coordinate mid = point(a, b, (in[0] + in[1]) / 2);
                out.add(alongAngle(a, b, mid));
                used.add(in);
            }
        }
        return out;
    }

    private static boolean onBoundary(Coordinate a, Coordinate b, double t, List<SegmentClipper.Hit> hits) {
        double tol = 1e-6;
        for (SegmentClipper.Hit h : hits) {
            if (Math.abs(h.t - t) <= tol) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far the route is from running along the outline at a point inside the polygon: the smallest angle to
     * the sides of the polygon around that point. Only sides longer than {@link #MIN_SIDE_M} count — a notch or a
     * bay of the outline does not decide the angle of a crossing, a kerb does.
     */
    @SuppressWarnings("unchecked")
    private double alongAngle(Coordinate a, Coordinate b, Coordinate mid) {
        Coordinate near = nearestOnBoundary(mid);
        double radius = near.distance(mid) * 2 + 1;
        Envelope env = new Envelope(mid);
        env.expandBy(radius);
        double worst = 90;
        boolean any = false;
        for (int[] e : (List<int[]>) edges.query(env)) {
            Ring r = rings.get(e[0]);
            Coordinate s0 = r.cs[e[1]];
            Coordinate s1 = r.cs[e[1] + 1];
            if (Distance.pointToSegment(mid, s0, s1) > radius) {
                continue;
            }
            org.locationtech.jts.geom.LineSegment side =
                    new org.locationtech.jts.geom.LineSegment(s0, s1);
            double[] dir = directionAt(side.closestPoint(mid));
            if (Math.hypot(dir[0], dir[1]) < MIN_SIDE_M) {
                continue;
            }
            any = true;
            worst = Math.min(worst, angle(a, b, dir));
        }
        return any ? worst : 90;
    }

    /** Sides of the outline shorter than this do not decide the angle of a crossing. */
    public static final double MIN_SIDE_M = 3.0;

    /** Point of the outline nearest to a point inside the polygon. */
    @SuppressWarnings("unchecked")
    private Coordinate nearestOnBoundary(Coordinate p) {
        Envelope env = new Envelope(p);
        double radius = 1;
        for (int step = 0; step < 24; step++) {
            env = new Envelope(p);
            env.expandBy(radius);
            List<int[]> found = (List<int[]>) edges.query(env);
            if (!found.isEmpty()) {
                Coordinate best = null;
                double bestD = Double.MAX_VALUE;
                for (int[] e : found) {
                    Ring r = rings.get(e[0]);
                    org.locationtech.jts.geom.LineSegment seg =
                            new org.locationtech.jts.geom.LineSegment(r.cs[e[1]], r.cs[e[1] + 1]);
                    Coordinate c = seg.closestPoint(p);
                    double d = c.distance(p);
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
                return best;
            }
            radius *= 2;
        }
        return p;
    }

    private static Coordinate point(Coordinate a, Coordinate b, double t) {
        return new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
    }

    /**
     * Direction of the boundary edge nearest to a point. Only that edge decides, with its neighbours joined in
     * while the outline stays nearly straight: a window across a corner would answer with a direction the route
     * never crosses (a kerb instead of the end of a road).
     */
    @SuppressWarnings("unchecked")
    double[] directionAt(Coordinate p) {
        Envelope env = new Envelope(p);
        env.expandBy(0.01);
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] e : (List<int[]>) edges.query(env)) {
            Ring r = rings.get(e[0]);
            double d = Distance.pointToSegment(p, r.cs[e[1]], r.cs[e[1] + 1]);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        if (best == null) {
            return new double[]{1, 0};
        }
        Ring r = rings.get(best[0]);
        int n = r.cs.length - 1;
        int from = best[1];
        int to = best[1];
        double base = Math.atan2(r.cs[to + 1].y - r.cs[from].y, r.cs[to + 1].x - r.cs[from].x);
        for (int step = 0; step < n; step++) {
            int prev = (from - 1 + n) % n;
            if (!nearlyStraight(base, r.cs[prev], r.cs[prev + 1])) {
                break;
            }
            from = prev;
        }
        for (int step = 0; step < n; step++) {
            int next = (to + 1) % n;
            if (!nearlyStraight(base, r.cs[next], r.cs[next + 1])) {
                break;
            }
            to = next;
        }
        double dx = r.cs[(to + 1) % n == 0 ? n : to + 1].x - r.cs[from].x;
        double dy = r.cs[(to + 1) % n == 0 ? n : to + 1].y - r.cs[from].y;
        if (Math.hypot(dx, dy) < 1e-9) {
            dx = r.cs[best[1] + 1].x - r.cs[best[1]].x;
            dy = r.cs[best[1] + 1].y - r.cs[best[1]].y;
        }
        return new double[]{dx, dy};
    }

    private static boolean nearlyStraight(double base, Coordinate a, Coordinate b) {
        double th = Math.atan2(b.y - a.y, b.x - a.x);
        double diff = Math.abs(th - base) % Math.PI;
        if (diff > Math.PI / 2) {
            diff = Math.PI - diff;
        }
        return Math.toDegrees(diff) <= COLLINEAR_DEG;
    }

    /** Angle between the piece and the boundary it passes through, 0–90°. */
    private static double angle(Coordinate a, Coordinate b, double[] dir) {
        double piece = Math.atan2(b.y - a.y, b.x - a.x);
        return between(piece, Math.atan2(dir[1], dir[0]));
    }

    private static double between(double piece, double axis) {
        double diff = Math.abs(piece - axis) % Math.PI;
        if (diff > Math.PI / 2) {
            diff = Math.PI - diff;
        }
        return Math.toDegrees(diff);
    }
}
