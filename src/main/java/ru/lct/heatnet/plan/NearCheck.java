package ru.lct.heatnet.plan;

import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.util.LinearComponentExtracter;

import java.util.List;

/**
 * {@code geometry.isWithinDistance(point, d)} of JTS for a fixed line or polygon geometry: a point inside a polygon is
 * at distance 0, otherwise the smallest {@code Distance.pointToSegment} to a segment decides, as in {@code DistanceOp}.
 * Segments whose envelope is farther than {@code d} are skipped. Immutable: shared by the planning threads.
 */
final class NearCheck {

    /** A segment is skipped only when its envelope is farther than the distance by more than this, metres. */
    private static final double MARGIN_M = 1e-6;

    private final Geometry geometry;
    /** x0, y0, x1, y1 of every segment of the lines and rings. */
    private final double[] segments;
    /** Point in polygon (polygonal geometries), null otherwise. */
    private final SegmentClipper area;
    /** The geometry is handled by JTS itself (empty, or a collection of mixed parts). */
    private final boolean generic;

    NearCheck(Geometry g, SegmentClipper clip) {
        this.geometry = g;
        this.generic = g.isEmpty() || g.getDimension() == 0 || g instanceof GeometryCollection
                && !(g instanceof Polygonal) && !(g instanceof org.locationtech.jts.geom.MultiLineString);
        this.area = g instanceof Polygonal ? clip : null;
        @SuppressWarnings("unchecked")
        List<LineString> lines = LinearComponentExtracter.getLines(g);
        int n = 0;
        for (LineString l : lines) {
            n += Math.max(0, l.getNumPoints() - 1);
        }
        segments = new double[4 * n];
        int k = 0;
        for (LineString l : lines) {
            Coordinate[] cs = l.getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                segments[k++] = cs[i].x;
                segments[k++] = cs[i].y;
                segments[k++] = cs[i + 1].x;
                segments[k++] = cs[i + 1].y;
            }
        }
    }

    boolean within(Point p, double d) {
        if (generic) {
            return geometry.isWithinDistance(p, d);
        }
        Coordinate c = p.getCoordinate();
        if (area != null && area.contains(c)) {
            return true;
        }
        double lim = d + MARGIN_M;
        Coordinate a = new Coordinate();
        Coordinate b = new Coordinate();
        for (int k = 0; k < segments.length; k += 4) {
            double x0 = segments[k];
            double y0 = segments[k + 1];
            double x1 = segments[k + 2];
            double y1 = segments[k + 3];
            double dx = Math.max(0, Math.max(Math.min(x0, x1) - c.x, c.x - Math.max(x0, x1)));
            double dy = Math.max(0, Math.max(Math.min(y0, y1) - c.y, c.y - Math.max(y0, y1)));
            if (dx > lim || dy > lim || dx * dx + dy * dy > lim * lim) {
                continue;
            }
            a.x = x0;
            a.y = y0;
            b.x = x1;
            b.y = y1;
            if (Distance.pointToSegment(c, a, b) <= d) {
                return true;
            }
        }
        return false;
    }
}
