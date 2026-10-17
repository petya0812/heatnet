package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.InputModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the route leaves the building of an OKS whose connection point lies inside it. Appendix 2.2 and
 * clarification 3: one straight piece from the point of the outline nearest to the connection point to the point.
 * The exits are the feet of the perpendiculars from the connection point to the walls of the outline, nearest first:
 * the piece inside the building is then perpendicular to its wall. A corner is never an exit — near an inner corner
 * the nearest perpendicular to a wall is taken.
 */
public final class BuildingEntry {

    /** An exit: a point on a wall and the outward normal of the wall there. */
    public static final class Exit {
        public final Coordinate c;
        /** Length of the piece inside the building, from the connection point to the exit, metres. */
        public final double insideM;
        /** Unit vector from the connection point to the exit (outward, perpendicular to the wall). */
        public final double nx;
        public final double ny;

        Exit(Coordinate c, double insideM, double nx, double ny) {
            this.c = c;
            this.insideM = insideM;
            this.nx = nx;
            this.ny = ny;
        }

        /** The point at {@code d} metres out of the building along the normal. */
        Coordinate out(double d) {
            return new Coordinate(c.x + nx * d, c.y + ny * d);
        }
    }

    /** Exits closer than this to a nearer one (the same wall split into several edges) are dropped, metres. */
    private static final double SAME_EXIT_M = 0.5;

    private BuildingEntry() {
    }

    /**
     * Up to {@code max} exits of the building of the OKS, by the length of the piece inside it; empty when the
     * connection point is not inside the building.
     */
    public static List<Exit> exits(InputModel.Oks oks, int max) {
        if (oks.polygon == null || max <= 0) {
            return Collections.emptyList();
        }
        Coordinate cp = oks.connectionPoint.getCoordinate();
        if (!oks.connectionInside()) {
            return onOutline(oks, cp);
        }
        List<double[]> feet = new ArrayList<>();
        Geometry boundary = oks.polygon.getBoundary();
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Coordinate[] cs = ((LineString) boundary.getGeometryN(g)).getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                double ex = cs[i + 1].x - cs[i].x;
                double ey = cs[i + 1].y - cs[i].y;
                double l2 = ex * ex + ey * ey;
                if (l2 < 1e-12) {
                    continue;
                }
                double t = ((cp.x - cs[i].x) * ex + (cp.y - cs[i].y) * ey) / l2;
                if (t < 0 || t > 1) {
                    continue; // the perpendicular misses this wall
                }
                double fx = cs[i].x + t * ex;
                double fy = cs[i].y + t * ey;
                double d = Math.hypot(fx - cp.x, fy - cp.y);
                if (d > 1e-9) {
                    feet.add(new double[]{fx, fy, d});
                }
            }
        }
        feet.sort((a, b) -> Double.compare(a[2], b[2]));
        List<Exit> out = new ArrayList<>();
        PreparedGeometry inner = null;
        for (double[] f : feet) {
            Coordinate c = new Coordinate(f[0], f[1]);
            boolean apart = true;
            for (Exit other : out) {
                apart &= other.c.distance(c) >= SAME_EXIT_M;
            }
            if (!apart) {
                continue;
            }
            // the piece must stay inside the building: a perpendicular to a farther wall, or one past an inner
            // corner, may cross the outline
            if (inner == null) {
                inner = PreparedGeometryFactory.prepare(oks.polygon.buffer(0.05));
            }
            if (!inner.covers(Crs.UTM_FACTORY.createLineString(new Coordinate[]{cp, c}))) {
                continue;
            }
            out.add(new Exit(c, f[2], (f[0] - cp.x) / f[2], (f[1] - cp.y) / f[2]));
            if (out.size() >= max) {
                break;
            }
        }
        if (out.isEmpty()) {
            // no perpendicular stays inside (a degenerate outline): the nearest point of the outline
            Coordinate r = oks.routePoint.getCoordinate();
            double d = r.distance(cp);
            out.add(new Exit(new Coordinate(r), d, (r.x - cp.x) / d, (r.y - cp.y) / d));
        }
        return out;
    }

    /**
     * A connection point right on the outline of an existing building (a restriction of type oks): the route leaves
     * it along the outward normal of the wall it lies on, as from inside. Empty for other points.
     */
    private static List<Exit> onOutline(InputModel.Oks oks, Coordinate cp) {
        if (oks.ownBuildingId == null) {
            return Collections.emptyList();
        }
        Geometry boundary = oks.polygon.getBoundary();
        Coordinate a = null;
        Coordinate b = null;
        double best = Double.POSITIVE_INFINITY;
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Coordinate[] cs = ((LineString) boundary.getGeometryN(g)).getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                double d = org.locationtech.jts.algorithm.Distance.pointToSegment(cp, cs[i], cs[i + 1]);
                if (cs[i].distance(cs[i + 1]) > 1e-6 && d < best) {
                    best = d;
                    a = cs[i];
                    b = cs[i + 1];
                }
            }
        }
        if (a == null || best > ON_OUTLINE_M) {
            return Collections.emptyList();
        }
        double l = a.distance(b);
        double nx = -(b.y - a.y) / l;
        double ny = (b.x - a.x) / l;
        if (oks.polygon.covers(Crs.UTM_FACTORY.createPoint(new Coordinate(cp.x + nx * 0.2, cp.y + ny * 0.2)))) {
            nx = -nx;
            ny = -ny;
        }
        return Collections.singletonList(new Exit(new Coordinate(cp), 0, nx, ny));
    }

    /** A connection point this close to the outline lies on it, metres (as in the input model). */
    private static final double ON_OUTLINE_M = 0.05;
}
