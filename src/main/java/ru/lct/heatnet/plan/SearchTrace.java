package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Record of one route search for its replay (UTM): turning points of the graph, the order in which the search
 * closed them, straight edges it tried and rejected with the reason, blocking zones and special corridors.
 * Only the last attempt is kept when the search is repeated with a larger radius.
 */
public final class SearchTrace {

    /** Limit of recorded rejected edges: enough to see where the search runs into restrictions. */
    public static final int MAX_REJECTED = 4000;

    public final List<Coordinate> vertices = new ArrayList<>();
    /** The first {@code starts} vertices are exits from the building of the OKS. */
    public int starts;
    /** Closed vertices in search order: {vertex, parent vertex or -1}. */
    public final List<int[]> expansions = new ArrayList<>();
    public final List<Coordinate[]> rejected = new ArrayList<>();
    public final List<String> rejectedReasons = new ArrayList<>();
    public final List<Geometry> zones = new ArrayList<>();
    public final List<String> zoneKinds = new ArrayList<>();
    public final List<Geometry> corridors = new ArrayList<>();
    public final List<String> corridorKinds = new ArrayList<>();
    public double radius;
    public int attempts;
    public boolean limitReached;
    public Route route;
    public Unconnected.Reason failure;
    public String failureDetail;

    void reset(double radius) {
        vertices.clear();
        expansions.clear();
        rejected.clear();
        rejectedReasons.clear();
        zones.clear();
        zoneKinds.clear();
        corridors.clear();
        corridorKinds.clear();
        starts = 0;
        limitReached = false;
        this.radius = radius;
        attempts++;
    }

    void reject(Coordinate a, Coordinate b, String reason) {
        if (rejected.size() < MAX_REJECTED) {
            rejected.add(new Coordinate[]{a, b});
            rejectedReasons.add(reason);
        }
    }
}
