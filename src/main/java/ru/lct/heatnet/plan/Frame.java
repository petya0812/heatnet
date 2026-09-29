package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Directions a route may take in one search: {@code phi + k·step}, step 30°, 45° or 90°. Pieces along them meet only
 * at multiples of the step up to 90° (30° — 30, 60 and 90°; 45° — 45 and 90°; 90° — right angles only).
 * {@code phi} follows the dominant direction of the outlines and of the network around the connection point, so
 * routes run along facades and streets rather than in a staircase.
 */
final class Frame {

    /** Heading of a route start without a direction of its own (the connection point itself). */
    static final int FREE = Integer.MAX_VALUE;
    /** A piece counts as going along a direction when it deviates from it by no more than this, degrees. */
    static final double ALIGN_DEG = 0.25;
    /** Radius around the connection point whose outlines give the directions, metres. */
    static final double RADIUS_M = 150;
    /** Outlines shorter than this in total (weighted) give no direction: the way to the network is taken. */
    static final double MIN_WEIGHT_M = 20;

    final double phi;
    /** Angle between neighbouring directions, degrees. */
    final int stepDeg;
    /** Number of directions: 360° / step. */
    final int headings;
    private final double step;

    Frame(double phi, int stepDeg) {
        if (stepDeg <= 0 || 360 % stepDeg != 0 || stepDeg > 90) {
            throw new IllegalArgumentException("step of directions must divide 360° and be at most 90°: " + stepDeg);
        }
        this.phi = phi;
        this.stepDeg = stepDeg;
        this.headings = 360 / stepDeg;
        this.step = Math.PI / (180.0 / stepDeg);
    }

    /**
     * Directions of the outlines (polygons and lines) around a point: length-weighted mean of edge directions modulo
     * the step, nearer edges weigh more. Without enough outlines — the direction to {@code fallback}.
     */
    static Frame around(Coordinate p, Iterable<Geometry> outlines, Coordinate fallback, int stepDeg) {
        int n = 360 / stepDeg;
        double c = 0;
        double s = 0;
        double w = 0;
        Envelope env = new Envelope(p);
        env.expandBy(RADIUS_M);
        for (Geometry g : outlines) {
            if (!g.getEnvelopeInternal().intersects(env)) {
                continue;
            }
            for (int i = 0; i < g.getNumGeometries(); i++) {
                Geometry part = g.getGeometryN(i);
                if (part instanceof org.locationtech.jts.geom.Polygon) {
                    org.locationtech.jts.geom.Polygon poly = (org.locationtech.jts.geom.Polygon) part;
                    double[] acc = accumulate(poly.getExteriorRing().getCoordinates(), p, n);
                    c += acc[0];
                    s += acc[1];
                    w += acc[2];
                } else {
                    double[] acc = accumulate(part.getCoordinates(), p, n);
                    c += acc[0];
                    s += acc[1];
                    w += acc[2];
                }
            }
        }
        if (w < MIN_WEIGHT_M || Math.hypot(c, s) < 1e-9) {
            double phi = fallback == null ? 0 : Math.atan2(fallback.y - p.y, fallback.x - p.x);
            return new Frame(normalize(phi, stepDeg), stepDeg);
        }
        return new Frame(normalize(Math.atan2(s, c) / n, stepDeg), stepDeg);
    }

    private static double[] accumulate(Coordinate[] cs, Coordinate p, int n) {
        double c = 0;
        double s = 0;
        double w = 0;
        for (int k = 0; k + 1 < cs.length; k++) {
            double dx = cs[k + 1].x - cs[k].x;
            double dy = cs[k + 1].y - cs[k].y;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) {
                continue;
            }
            double mx = (cs[k].x + cs[k + 1].x) / 2 - p.x;
            double my = (cs[k].y + cs[k + 1].y) / 2 - p.y;
            double d = Math.hypot(mx, my);
            if (d > RADIUS_M) {
                continue;
            }
            double weight = len / (1 + d / 50);
            double th = Math.atan2(dy, dx) * n;
            c += weight * Math.cos(th);
            s += weight * Math.sin(th);
            w += weight;
        }
        return new double[]{c, s, w};
    }

    private static double normalize(double phi, int stepDeg) {
        double q = Math.PI / (180.0 / stepDeg);
        return ((phi % q) + q) % q;
    }

    /** Heading index of a direction, or -1 if it is not along one of the directions. */
    int heading(double vx, double vy) {
        double k = (Math.atan2(vy, vx) - phi) / step;
        long r = Math.round(k);
        if (Math.abs(k - r) * stepDeg > ALIGN_DEG) {
            return -1;
        }
        return (int) Math.floorMod(r, (long) headings);
    }

    /** Whether a route may go on from heading h1 to h2: straight on or a turn of at most 90°. */
    boolean allowedTurn(int h1, int h2) {
        int d = Math.floorMod(h1 - h2, headings);
        return Math.min(d, headings - d) * stepDeg <= 90;
    }

    /**
     * Ways from a to b along the directions: a list with {@code null} when b lies on one of them (one straight leg),
     * otherwise the two turning points of the ways of two legs along the two directions around a→b — the leg along
     * an even heading first (for 45° — the axis before the diagonal), then the other. The legs meet at the step angle.
     * For 45° and 90° the legs come straight from the components of a→b in the frame, without solving for them:
     * exact arithmetic keeps the choice between equal ways, and so the result, the same on every run and machine.
     */
    List<Coordinate> mids(Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        if (Math.hypot(dx, dy) < 1e-9 || heading(dx, dy) >= 0) {
            return Collections.singletonList(null);
        }
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        // a→b in the frame: x along phi, y across
        double x = dx * cos + dy * sin;
        double y = -dx * sin + dy * cos;
        double[] first;
        double[] second;
        if (stepDeg == 45) {
            // straight leg along the longer axis by the difference of the two, the diagonal by the shorter one
            double ax = Math.abs(x);
            double ay = Math.abs(y);
            double small = Math.min(ax, ay);
            double sx = Math.signum(x);
            double sy = Math.signum(y);
            first = new double[]{ax >= ay ? sx * (ax - small) : 0, ax >= ay ? 0 : sy * (ay - small)};
            second = new double[]{sx * small, sy * small};
        } else if (stepDeg == 90) {
            first = new double[]{x, 0};
            second = new double[]{0, y};
        } else {
            int k = (int) Math.floor(Math.atan2(y, x) / step);
            double[] u = {Math.cos(k * step), Math.sin(k * step)};
            double[] w = {Math.cos((k + 1) * step), Math.sin((k + 1) * step)};
            // a→b = s·u + t·w with s, t > 0
            double det = u[0] * w[1] - u[1] * w[0];
            double s = (x * w[1] - y * w[0]) / det;
            double t = (u[0] * y - u[1] * x) / det;
            boolean even = Math.floorMod(k, 2) == 0;
            first = even ? new double[]{s * u[0], s * u[1]} : new double[]{t * w[0], t * w[1]};
            second = even ? new double[]{t * w[0], t * w[1]} : new double[]{s * u[0], s * u[1]};
        }
        List<Coordinate> out = new ArrayList<>(2);
        out.add(toGlobal(a, first[0], first[1], cos, sin));
        out.add(toGlobal(a, second[0], second[1], cos, sin));
        return out;
    }

    private static Coordinate toGlobal(Coordinate a, double x, double y, double cos, double sin) {
        return new Coordinate(a.x + x * cos - y * sin, a.y + x * sin + y * cos);
    }

    /** Unit vector of a heading. */
    double[] unit(int h) {
        double a = phi + h * step;
        return new double[]{Math.cos(a), Math.sin(a)};
    }
}
