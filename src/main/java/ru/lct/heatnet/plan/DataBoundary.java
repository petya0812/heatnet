package ru.lct.heatnet.plan;

import org.locationtech.jts.algorithm.ConvexHull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.ObstacleSource;

import java.util.ArrayList;
import java.util.List;

/**
 * The area where the dataset has data ({@link PlanParams#dataBoundaryM}). Outside the objects of the dataset nothing
 * is known — no buildings, no restrictions — and an empty space there is not a free one: a route around the outside of
 * the edge buildings would be the shortest only because the data ends. The area is the convex hull of all objects of
 * the dataset (buildings, restrictions, the existing network, chambers, the source, the OKS) grown by the margin: the
 * clearance of an edge building (5–9 m) is wider than the margin, so a route cannot pass outside it. A convex polygon
 * of a few dozen vertices — the check costs about as much as a bounding box.
 */
public final class DataBoundary {

    /**
     * Extra room around the OKS buildings, metres, on top of the margin: the straight piece out of the building crosses
     * its clearance (5–9 m) and then turns, so an OKS on the edge of the data can still leave by its outer wall.
     */
    static final double OKS_ROOM_M = 12;
    /** Segments of a quarter circle in the buffers. */
    private static final int QUADRANT_SEGMENTS = 4;
    /** Points gathered before they are reduced to their hull (the obstacles are streamed and not kept). */
    private static final int BATCH = 20_000;

    private final Geometry area;
    private final PreparedGeometry prepared;
    /** Time to build the area, milliseconds (for the measurements). */
    public final long buildMs;

    private DataBoundary(Geometry area, long buildMs) {
        this.area = area;
        this.buildMs = buildMs;
        this.prepared = PreparedGeometryFactory.prepare(area);
        // the indexes of a prepared geometry are built on first use; the searches of several threads share it
        prepared.intersects(area.getCentroid());
        prepared.covers(area.getCentroid());
    }

    /** The area of the data with the margin; null when the margin is 0 (no boundary) or there are no objects. */
    public static DataBoundary build(InputModel model, ObstacleSource obstacles, PlanParams params) {
        double margin = params.dataBoundaryM;
        if (!(margin > 0)) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        Hull hull = new Hull();
        Envelope core = new Envelope();
        for (InputModel.Segment s : model.getSegments().values()) {
            hull.add(s.line);
            core.expandToInclude(s.line.getEnvelopeInternal());
        }
        for (InputModel.Chamber c : model.getChambers().values()) {
            hull.add(c.point);
            core.expandToInclude(c.point.getCoordinate());
        }
        if (model.getSource() != null) {
            hull.add(model.getSource().point);
            core.expandToInclude(model.getSource().point.getCoordinate());
        }
        java.util.Set<String> oksBuildings = new java.util.HashSet<>();
        for (InputModel.Oks o : model.getOks()) {
            hull.add(o.connectionPoint);
            hull.add(o.routePoint);
            core.expandToInclude(o.connectionPoint.getCoordinate());
            if (o.polygon != null) {
                hull.add(o.polygon.buffer(OKS_ROOM_M, QUADRANT_SEGMENTS));
                core.expandToInclude(o.polygon.getEnvelopeInternal());
            }
            if (o.ownBuildingId != null) {
                oksBuildings.add(o.ownBuildingId);
            }
        }
        if (core.isNull()) {
            return null;
        }
        // obstacles a search can ever load: around the core objects within the largest search radius
        Envelope reach = new Envelope(core);
        reach.expandBy(params.searchRadiusMaxM + params.obstacleMarginM);
        obstacles.forEachIntersecting(Crs.UTM_FACTORY.toGeometry(reach), o -> hull.add(
                oksBuildings.contains(o.getId()) ? o.getGeometry().buffer(OKS_ROOM_M, QUADRANT_SEGMENTS)
                        : o.getGeometry()));
        Geometry area = hull.get().buffer(margin, QUADRANT_SEGMENTS);
        return new DataBoundary(area, System.currentTimeMillis() - t0);
    }

    /** The convex hull of the objects as they come: the points of a batch are reduced to the hull with the ones before. */
    private static final class Hull {
        private final List<Coordinate> points = new ArrayList<>();

        void add(Geometry g) {
            for (Coordinate c : g.getCoordinates()) {
                points.add(c);
            }
            if (points.size() >= BATCH) {
                Coordinate[] h = get().getCoordinates();
                points.clear();
                for (Coordinate c : h) {
                    points.add(c);
                }
            }
        }

        Geometry get() {
            return new ConvexHull(points.toArray(new Coordinate[0]), Crs.UTM_FACTORY).getConvexHull();
        }
    }

    /**
     * The whole area lies within the search circle around {@code c} (the circle is a polygon of 64 sides inscribed in
     * the radius: its inner radius is the bound).
     */
    boolean insideCircle(org.locationtech.jts.geom.Coordinate c, double radius) {
        Envelope e = area.getEnvelopeInternal();
        double r = radius * Math.cos(Math.PI / 64) - 1;
        return Math.hypot(Math.max(Math.abs(e.getMinX() - c.x), Math.abs(e.getMaxX() - c.x)),
                Math.max(Math.abs(e.getMinY() - c.y), Math.abs(e.getMaxY() - c.y))) <= r;
    }

    public Geometry getArea() {
        return area;
    }

    /**
     * What of {@code region} lies outside the area of the data: a forbidden zone of a search there. Null when the
     * region lies wholly inside.
     */
    Geometry outsideWithin(Geometry region) {
        if (prepared.covers(region)) {
            return null;
        }
        if (!prepared.intersects(region)) {
            return region;
        }
        Geometry outside = region.difference(area);
        return outside.isEmpty() ? null : outside;
    }
}
