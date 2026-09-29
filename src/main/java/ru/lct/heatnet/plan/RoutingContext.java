package ru.lct.heatnet.plan;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.ObjectType;
import ru.lct.heatnet.input.Obstacle;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.reference.RestrictionRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Geometry of one routing problem (one pipe DN, one search area): blocking zones of forbidden objects
 * and corridors of objects that may be crossed with a special section. Evaluates straight route edges.
 */
public final class RoutingContext {

    /**
     * Prepared geometries build their index on the first query; the planning threads share the zones, so the
     * index is built here, before the object is published.
     */
    private static void warmUp(PreparedGeometry... prepared) {
        org.locationtech.jts.geom.Point far = ru.lct.heatnet.geo.Crs.UTM_FACTORY
                .createPoint(new Coordinate(-1e12, -1e12));
        for (PreparedGeometry p : prepared) {
            if (p != null) {
                p.intersects(far);
                p.getGeometry().getEnvelopeInternal();
            }
        }
    }

    /** A forbidden object with its clearance. */
    public static final class Zone {
        public final String objectId;
        public final String kind;
        public final Geometry raw;
        public final Geometry block;
        public final PreparedGeometry blockPrepared;
        /** Stricter core used when a route endpoint lies inside the block (endpoint escape). */
        public final PreparedGeometry corePrepared;
        final SegmentClipper blockClip;
        /** A route end inside the block may stay inside it only this close to that end. */
        double escapeRadiusM;

        /** Clearance the block was buffered with: part of the cache key of the corners. */
        double blockDistanceM;

        Zone(String objectId, String kind, Geometry raw, Geometry block, Geometry core) {
            this.objectId = objectId;
            this.kind = kind;
            this.raw = raw;
            this.block = block;
            this.blockPrepared = PreparedGeometryFactory.prepare(block);
            this.blockClip = new SegmentClipper(block);
            this.corePrepared = core == null || core.isEmpty() ? null : PreparedGeometryFactory.prepare(core);
            // zones are shared by the planning threads: everything lazy is built here, in one thread
            raw.getEnvelopeInternal();
            block.getEnvelopeInternal();
            warmUp(blockPrepared, corePrepared);
        }
    }

    /** An object that may be crossed with a special section (road, tram, gas, cable, existing network). */
    public static final class Special {
        public final String objectId;
        public final String type;
        public final RestrictionRule rule;
        public final Geometry raw;
        public final Geometry boundary;
        public final Geometry corridor;
        public final PreparedGeometry corridorPrepared;
        public final Geometry boundsArea;
        public final boolean areal;
        public final double exclusionM;
        /** Clearance corridor half-width around the object (no turning points inside). */
        public final double corridorM;
        /** Boundary of a polygon crossed at a minimal angle (road, tram tracks); null otherwise. */
        final PolygonBoundary crossedBoundary;
        final SegmentClipper rawClip;
        final SegmentClipper corridorClip;
        final SegmentClipper boundsClip;

        Special(String objectId, String type, RestrictionRule rule, Geometry raw, double corridorM) {
            this.objectId = objectId;
            this.type = type;
            this.rule = rule;
            this.raw = raw;
            this.areal = raw instanceof Polygonal;
            this.boundary = areal ? raw.getBoundary() : raw;
            this.corridor = raw.buffer(corridorM, 8);
            this.corridorPrepared = PreparedGeometryFactory.prepare(corridor);
            this.boundsArea = rule.getBounds() == RestrictionRule.Bounds.AREA_PLUS
                    ? raw.buffer(rule.getBoundsM(), 8) : null;
            this.exclusionM = Math.max(corridorM, rule.getBoundsM());
            this.corridorM = corridorM;
            this.rawClip = new SegmentClipper(raw);
            this.crossedBoundary = areal && rule.getMinAngleDeg() > 0 ? new PolygonBoundary(raw, rawClip) : null;
            this.corridorClip = new SegmentClipper(corridor);
            this.boundsClip = boundsArea != null ? new SegmentClipper(boundsArea) : null;
            raw.getEnvelopeInternal();
            corridor.getEnvelopeInternal();
            boundary.getEnvelopeInternal();
            if (boundsArea != null) {
                boundsArea.getEnvelopeInternal();
            }
            warmUp(corridorPrepared);
        }
    }

    /** Special-section interval along an edge, distances from the edge start. */
    public static final class Interval {
        public final double from;
        public final double to;
        public final double k;
        public final String objectId;
        public final String type;

        public Interval(double from, double to, double k, String objectId, String type) {
            this.from = from;
            this.to = to;
            this.k = k;
            this.objectId = objectId;
            this.type = type;
        }

        public double length() {
            return to - from;
        }
    }

    /** Result of an edge check. */
    public static final class EdgeEval {
        public final boolean valid;
        public final double length;
        public final double score;
        public final List<Interval> intervals;
        public final String reason;

        EdgeEval(boolean valid, double length, double score, List<Interval> intervals, String reason) {
            this.valid = valid;
            this.length = length;
            this.score = score;
            this.intervals = intervals;
            this.reason = reason;
        }

        static EdgeEval invalid(String reason) {
            return new EdgeEval(false, 0, 0, Collections.emptyList(), reason);
        }
    }

    /** Route endpoint (connection point or tie-in) that may lie inside clearances. */
    public static final class Endpoint {
        public final Coordinate c;
        public final List<Zone> escapedZones = new ArrayList<>();
        public final List<String> excusedSpecials = new ArrayList<>();
        /** Route ends (connection point, tie-in) may start inside a corridor; a branching chamber may not. */
        public boolean mayTouchCorridors = true;

        Endpoint(Coordinate c) {
            this.c = c;
        }
    }

    private final ReferenceData ref;
    private final PlanParams params;
    private final PipeSpec pipe;
    private final Geometry area;
    private final List<Zone> zones = new ArrayList<>();
    private final List<Special> specials = new ArrayList<>();
    private final STRtree zoneIndex = new STRtree();
    private final STRtree specialIndex = new STRtree();

    private final ZoneCache cache;
    /** Zones of objects that do not change during planning; shared between searches of one calculation. */
    private final Static shared;
    private List<Coordinate> ownVertices;

    /**
     * Zones of the obstacles of one search area for one DN: they do not depend on what the variant has already
     * built, so they are computed once and reused by every later search with the same area and DN.
     */
    static final class Static {
        final List<Zone> zones;
        final List<Special> specials;
        final STRtree zoneIndex;
        final STRtree specialIndex;
        /** Corners of the zones, already checked against these zones. */
        final List<Coordinate> vertices;

        Static(List<Zone> zones, List<Special> specials, STRtree zoneIndex, STRtree specialIndex,
               List<Coordinate> vertices) {
            this.zones = zones;
            this.specials = specials;
            this.zoneIndex = zoneIndex;
            this.specialIndex = specialIndex;
            this.vertices = vertices;
        }
    }

    public RoutingContext(ReferenceData ref, PlanParams params, PipeSpec pipe, Geometry area) {
        this(ref, params, pipe, area, new ZoneCache(), null);
    }

    RoutingContext(ReferenceData ref, PlanParams params, PipeSpec pipe, Geometry area, ZoneCache cache,
                   Static shared) {
        this.ref = ref;
        this.params = params;
        this.pipe = pipe;
        this.area = area;
        this.cache = cache;
        this.shared = shared;
    }

    /** The zones added so far as a reusable static part (the context must be built). */
    Static toStatic() {
        return new Static(zones, specials, zoneIndex, specialIndex, buildVertices());
    }

    public PipeSpec getPipe() {
        return pipe;
    }

    public Geometry getArea() {
        return area;
    }

    public List<Zone> getZones() {
        if (shared == null) {
            return zones;
        }
        List<Zone> all = new ArrayList<>(shared.zones);
        all.addAll(zones);
        return all;
    }

    public List<Special> getSpecials() {
        if (shared == null) {
            return specials;
        }
        List<Special> all = new ArrayList<>(shared.specials);
        all.addAll(specials);
        return all;
    }

    /** Clearance between the pipe axis and the raw geometry of a forbidden object. */
    public double clearanceFor(Obstacle o) {
        if (o.getObjectType() == ObjectType.OKS_EXISTING || o.getObjectType() == ObjectType.OKS_FUTURE) {
            return ref.buildingDistance(pipe.getDn()) + pipe.getHalfWidthM() + params.extraClearanceM;
        }
        RestrictionRule r = ref.restriction(o.getRestrictionType());
        return r.getMinDistanceM() + pipe.getHalfWidthM() + r.getObjectWidthM() / 2.0 + params.extraClearanceM;
    }

    /**
     * Angle a crossing must reach in the planner: the rule plus a margin (the check of the result runs on the
     * written geometry), or the stricter preference of the engineer when it is set above the rule.
     */
    double requiredCrossingAngle(RestrictionRule rule) {
        double required = rule.getMinAngleDeg() + ANGLE_MARGIN_DEG;
        if (params.minCrossingAngleDeg > rule.getMinAngleDeg()) {
            required = Math.max(required, Math.min(params.minCrossingAngleDeg, 90) - 0.5);
        }
        return required;
    }

    public void addForbidden(String objectId, String kind, Geometry raw, double clearance) {
        double d = clearance + params.simplifyToleranceM;
        Zone z = cache.object(objectId, "zone/" + kind, d, () -> {
            Geometry block = cache.buffer(objectId, kind, d, () -> mitreBuffer(simplify(raw), d));
            Geometry core = raw instanceof Polygonal ? raw.buffer(-0.05) : null;
            Zone zone = new Zone(objectId, kind, raw, block, core);
            zone.blockDistanceM = d;
            zone.escapeRadiusM = escapeRadius(clearance);
            return zone;
        });
        zones.add(z);
        zoneIndex.insert(z.block.getEnvelopeInternal(), z);
    }

    /**
     * Radius around a route end (connection point, tie-in) that lies within a clearance: only there the route may
     * be closer than the clearance. The validator uses the same radius.
     */
    public static double escapeRadius(double clearance) {
        return 1.1 * clearance + 1.5;
    }

    /** The building being connected: its interior is forbidden, the boundary is reachable. */
    public void addOwnBuilding(String objectId, Geometry polygon) {
        Geometry block = polygon.buffer(-0.05);
        if (block.isEmpty()) {
            return;
        }
        Zone z = new Zone(objectId, "own_oks", polygon, block, null);
        zones.add(z);
        zoneIndex.insert(block.getEnvelopeInternal(), z);
    }

    public void addObstacle(Obstacle o) {
        if (o.getObjectType() == ObjectType.RESTRICTION) {
            RestrictionRule r = ref.restriction(o.getRestrictionType());
            if (!r.isForbidden()) {
                double corridor = r.getMinDistanceM() + pipe.getHalfWidthM() + r.getObjectWidthM() / 2.0;
                addSpecial(o.getId(), o.getRestrictionType(), r, o.getGeometry(), corridor);
                return;
            }
        }
        addForbidden(o.getId(), o.getRestrictionType() != null ? o.getRestrictionType()
                : o.getObjectType().getCode(), o.getGeometry(), clearanceFor(o));
    }

    public void addExistingNetwork(InputModel.Segment s) {
        RestrictionRule r = ref.restriction("heat_network");
        double objHalf = ref.pipeAtLeast(s.dn).getHalfWidthM();
        double corridor = r.getMinDistanceM() + pipe.getHalfWidthM() + objHalf;
        addSpecial(s.id, "heat_network", r, s.line, corridor);
    }

    private void addSpecial(String id, String type, RestrictionRule rule, Geometry raw, double corridorM) {
        Special sp = cache.object(id, "special/" + type, corridorM, () -> new Special(id, type, rule, raw, corridorM));
        specials.add(sp);
        specialIndex.insert(sp.corridor.getEnvelopeInternal(), sp);
    }

    public void build() {
        zoneIndex.build();
        specialIndex.build();
    }

    /** Zones of this search and of the shared static part that may contain the envelope, without merging lists. */
    @SuppressWarnings("unchecked")
    private void forEachZone(Envelope env, java.util.function.Predicate<Zone> action) {
        for (Object o : (List<Object>) zoneIndex.query(env)) {
            if (!action.test((Zone) o)) {
                return;
            }
        }
        if (shared != null) {
            for (Object o : (List<Object>) shared.zoneIndex.query(env)) {
                if (!action.test((Zone) o)) {
                    return;
                }
            }
        }
    }

    /** Specials as one list (used where the loop cannot be interrupted). */
    private List<Special> querySpecialsList(Envelope env) {
        List<Special> out = new ArrayList<>();
        forEachSpecial(env, out::add);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void forEachSpecial(Envelope env, java.util.function.Consumer<Special> action) {
        for (Object o : (List<Object>) specialIndex.query(env)) {
            action.accept((Special) o);
        }
        if (shared != null) {
            for (Object o : (List<Object>) shared.specialIndex.query(env)) {
                action.accept((Special) o);
            }
        }
    }

    private Geometry simplify(Geometry g) {
        if (g instanceof Point) {
            return g;
        }
        Geometry s = g instanceof Polygonal
                ? TopologyPreservingSimplifier.simplify(g, params.simplifyToleranceM)
                : DouglasPeuckerSimplifier.simplify(g, params.simplifyToleranceM);
        return s.isEmpty() ? g : s;
    }

    // ------------------------------------------------------------------ vertices

    /** Candidate turning points: convex corners of blocking zones, slightly outside them. */
    public List<Coordinate> buildVertices() {
        if (shared != null) {
            // corners of the shared zones are already checked against them; both sets are checked against the
            // zones of this search (the routes already built for the variant)
            List<Coordinate> all = new ArrayList<>(shared.vertices);
            all.addAll(ownCorners());
            List<Coordinate> free = new ArrayList<>();
            for (Coordinate c : all) {
                if (isFreeVertex(c)) {
                    free.add(c);
                }
            }
            return free;
        }
        List<Coordinate> out = ownCorners();
        List<Coordinate> filtered = new ArrayList<>();
        for (Coordinate c : out) {
            if (isFreeVertex(c)) {
                filtered.add(c);
            }
        }
        return filtered;
    }

    /** Corners of the zones added to this context (without the shared ones), unfiltered. */
    private List<Coordinate> ownCorners() {
        if (ownVertices != null) {
            return ownVertices;
        }
        List<Coordinate> out = new ArrayList<>();
        double d0 = params.vertexOffsetM;
        for (Zone z : zones) {
            // corners of the own building are useful to go around it towards the connection point
            boolean own = "own_oks".equals(z.kind);
            Geometry source = own ? z.raw : z.block;
            double d = own ? d0 + 0.3 : d0;
            // the corners depend on the clearance the block was buffered with, not only on the offset
            collectConvex(mitreBuffer(source, d), out);
        }
        ownVertices = out;
        return out;
    }

    private void collectConvex(Geometry g, List<Coordinate> out) {
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry part = g.getGeometryN(i);
            if (!(part instanceof Polygon)) {
                continue;
            }
            Polygon p = (Polygon) part;
            collectRing(p.getExteriorRing(), true, out);
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                collectRing(p.getInteriorRingN(h), false, out);
            }
        }
    }

    private void collectRing(LinearRing ring, boolean shell, List<Coordinate> out) {
        Coordinate[] cs = ring.getCoordinates();
        int n = cs.length - 1;
        if (n < 3) {
            return;
        }
        boolean ccw = Orientation.isCCW(cs);
        for (int i = 0; i < n; i++) {
            Coordinate prev = cs[(i - 1 + n) % n];
            Coordinate cur = cs[i];
            Coordinate next = cs[(i + 1) % n];
            double cross = (cur.x - prev.x) * (next.y - cur.y) - (cur.y - prev.y) * (next.x - cur.x);
            if (Math.abs(cross) < 1e-9) {
                continue;
            }
            boolean insideConvex = ccw ? cross > 0 : cross < 0;
            boolean useful = shell ? insideConvex : !insideConvex;
            if (useful && area.getEnvelopeInternal().contains(cur)) {
                out.add(new Coordinate(cur));
            }
        }
    }

    public boolean isFreeVertex(Coordinate c) {
        Point p = Crs.UTM_FACTORY.createPoint(c);
        if (!area.contains(p)) {
            return false;
        }
        boolean[] free = {true};
        forEachZone(new Envelope(c), z -> {
            if (z.blockPrepared.intersects(p)) {
                free[0] = false;
            }
            return free[0];
        });
        if (!free[0]) {
            return false;
        }
        Envelope env = new Envelope(c);
        env.expandBy(10);
        forEachSpecial(env, sp -> {
            if (sp.raw.isWithinDistance(p, sp.corridorM + 0.05)) {
                free[0] = false;
            }
        });
        return free[0];
    }

    // ------------------------------------------------------------------ endpoints

    /** Describes a route endpoint: zones containing it are checked by their core only. */
    public Endpoint endpoint(Coordinate c, Collection<String> excusedSpecialIds) {
        Endpoint e = new Endpoint(c);
        Point p = Crs.UTM_FACTORY.createPoint(c);
        forEachZone(new Envelope(c), z -> {
            if (z.blockPrepared.intersects(p)) {
                e.escapedZones.add(z);
            }
            return true;
        });
        if (excusedSpecialIds != null) {
            e.excusedSpecials.addAll(excusedSpecialIds);
        }
        return e;
    }

    /** Ids of special objects whose corridor contains the point (used to excuse them at a tie-in). */
    public List<String> specialsAround(Coordinate c, double extraM) {
        List<String> ids = new ArrayList<>();
        Point p = Crs.UTM_FACTORY.createPoint(c);
        Envelope env = new Envelope(c);
        env.expandBy(extraM + 10);
        forEachSpecial(env, sp -> {
            if (sp.corridor.isWithinDistance(p, extraM)) {
                ids.add(sp.objectId);
            }
        });
        return ids;
    }

    // ------------------------------------------------------------------ edges

    /**
     * Checks a straight edge a→b. {@code ea}/{@code eb} are non-null when the end is a route endpoint.
     */
    public EdgeEval evaluate(Coordinate a, Coordinate b, Endpoint ea, Endpoint eb) {
        double len = a.distance(b);
        if (len < 1e-6) {
            return new EdgeEval(true, 0, 0, Collections.emptyList(), null);
        }
        LineString seg = Crs.UTM_FACTORY.createLineString(new Coordinate[]{a, b});
        Envelope env = seg.getEnvelopeInternal();
        EdgeEval[] bad = {null};
        forEachZone(env, z -> {
            boolean escaped = (ea != null && ea.escapedZones.contains(z)) || (eb != null && eb.escapedZones.contains(z));
            if (escaped) {
                if (z.corePrepared != null && z.corePrepared.intersects(seg)) {
                    bad[0] = EdgeEval.invalid("crosses " + z.kind + " " + z.objectId);
                    return false;
                }
                boolean escA = ea != null && ea.escapedZones.contains(z);
                boolean escB = eb != null && eb.escapedZones.contains(z);
                if (z.corePrepared == null && z.raw.getDimension() < 2 && z.raw.intersects(seg)) {
                    // linear zone (e.g. an earlier new route) touching the shared endpoint: only that touch is allowed
                    for (Coordinate c : z.raw.intersection(seg).getCoordinates()) {
                        boolean atEnd = (escA && c.distance(a) < 0.05) || (escB && c.distance(b) < 0.05);
                        if (!atEnd) {
                            bad[0] = EdgeEval.invalid("crosses " + z.kind + " " + z.objectId);
                            return false;
                        }
                    }
                }
                // inside the clearance only near the end that is inside it
                double r = z.escapeRadiusM / len;
                for (double[] in : z.blockClip.inside(a, b, z.blockClip.hits(a, b))) {
                    boolean nearA = escA && in[1] <= r;
                    boolean nearB = escB && in[0] >= 1 - r;
                    if (!nearA && !nearB) {
                        bad[0] = EdgeEval.invalid("clearance of " + z.kind + " " + z.objectId);
                        return false;
                    }
                }
            } else if (z.blockClip.intersects(a, b)) {
                bad[0] = EdgeEval.invalid("clearance of " + z.kind + " " + z.objectId);
                return false;
            }
            return true;
        });
        if (bad[0] != null) {
            return bad[0];
        }
        List<Interval> raw = new ArrayList<>();
        for (Special sp : querySpecialsList(env)) {
            List<SegmentClipper.Hit> ch = sp.corridorClip.hits(a, b);
            if (ch.isEmpty() && !sp.corridorClip.contains(a)) {
                continue;
            }
            boolean excusedA = ea != null && ea.excusedSpecials.contains(sp.objectId);
            boolean excusedB = eb != null && eb.excusedSpecials.contains(sp.objectId);
            String reason = special(a, b, len, sp, ch, ea, eb, excusedA, excusedB, raw);
            if (reason != null) {
                return EdgeEval.invalid(reason);
            }
        }
        List<Interval> merged = merge(raw, len);
        double specialWeighted = 0;
        double specialLen = 0;
        for (Interval iv : merged) {
            specialLen += iv.length();
            specialWeighted += iv.length() * iv.k;
        }
        double cost = pipe.getNewCostPerM() * (len - specialLen + specialWeighted);
        double score = ref.scoreOfCost(cost) + ref.scoreOfLength(len);
        return new EdgeEval(true, len, score, merged, null);
    }

    /** The planner keeps this much above the required crossing angle, so the check of the result never fails. */
    static final double ANGLE_MARGIN_DEG = 5.0;

    private String special(Coordinate a, Coordinate b, double len, Special sp, List<SegmentClipper.Hit> corridorHits,
                           Endpoint ea, Endpoint eb, boolean excusedA, boolean excusedB, List<Interval> out) {
        double tol = 1e-3 / len;
        List<double[]> pieces = sp.corridorClip.inside(a, b, corridorHits);
        List<SegmentClipper.Hit> rawHits = sp.rawClip.hits(a, b);
        List<double[]> rawIn = sp.areal ? sp.rawClip.inside(a, b, rawHits) : Collections.<double[]>emptyList();
        boolean anyCrossing = !rawHits.isEmpty() || !rawIn.isEmpty();
        boolean onlyAtEnds = (excusedA || excusedB) && anyCrossing;
        for (SegmentClipper.Hit h : rawHits) {
            onlyAtEnds &= (excusedA && h.t <= tol) || (excusedB && h.t >= 1 - tol);
        }
        for (double[] r : rawIn) {
            onlyAtEnds &= (excusedA && r[1] <= tol) || (excusedB && r[0] >= 1 - tol);
        }
        // Every stretch of the edge inside the corridor must be a crossing (or touch an excused endpoint).
        for (double[] pc : pieces) {
            if ((pc[1] - pc[0]) * len < 1e-6) {
                continue;
            }
            boolean touchesA = ea != null && ea.mayTouchCorridors && pc[0] <= tol;
            boolean touchesB = eb != null && eb.mayTouchCorridors && pc[1] >= 1 - tol;
            boolean crosses = !onlyAtEnds && crossingWithin(rawHits, rawIn, pc[0] - tol, pc[1] + tol);
            if (!crosses && !touchesA && !touchesB) {
                return "runs along " + sp.type + " " + sp.objectId;
            }
        }
        if (!anyCrossing) {
            return null;
        }
        // The angle of a crossing is checked even next to the ends of the route: the exemption around an endpoint
        // covers the clearance (R-RST-9), not the rule of the crossing itself.
        if (sp.rule.getMinAngleDeg() > 0) {
            // polygons: at the entry point, against the boundary the route passes through (appendix 4,
            // clarification 6); a linear restriction is measured against its own line
            List<Double> angles = new ArrayList<>();
            if (sp.crossedBoundary != null) {
                angles.addAll(sp.crossedBoundary.crossingAngles(a, b));
            } else {
                for (SegmentClipper.Hit h : rawHits) {
                    double cos = Math.abs((b.x - a.x) * h.ex + (b.y - a.y) * h.ey) / (len * Math.hypot(h.ex, h.ey));
                    angles.add(Math.toDegrees(Math.acos(Math.min(1, cos))));
                }
            }
            double required = requiredCrossingAngle(sp.rule);
            for (double ang : angles) {
                // a small margin over the rule: the check of the result runs on the written geometry, and the
                // direction of a boundary may differ slightly there
                if (ang < required) {
                    return String.format("crosses %s %s at %.1f° < %.0f°", sp.type, sp.objectId, ang,
                            Math.max(sp.rule.getMinAngleDeg(), params.minCrossingAngleDeg));
                }
            }
        }
        if (onlyAtEnds) {
            return null;
        }
        if (sp.rule.getBounds() == RestrictionRule.Bounds.AREA_PLUS) {
            for (double[] bi : sp.boundsClip.inside(a, b, sp.boundsClip.hits(a, b))) {
                if ((bi[1] - bi[0]) * len < 1e-6 || !crossingWithin(rawHits, rawIn, bi[0] - tol, bi[1] + tol)) {
                    continue;
                }
                // a special section is one straight piece: the edge may not turn inside its bounds
                if ((bi[0] <= tol && ea == null) || (bi[1] >= 1 - tol && eb == null)) {
                    return "turns inside the special section of " + sp.type + " " + sp.objectId;
                }
                out.add(new Interval(bi[0] * len, bi[1] * len, sp.rule.getK(), sp.objectId, sp.type));
            }
        } else {
            double half = sp.rule.getBoundsM();
            List<double[]> parts = new ArrayList<>(rawIn);
            for (SegmentClipper.Hit h : rawHits) {
                parts.add(new double[]{h.t, h.t});
            }
            for (double[] ft : parts) {
                if ((excusedA && ft[1] <= tol) || (excusedB && ft[0] >= 1 - tol)) {
                    continue;
                }
                if ((ft[0] * len < half - 1e-6 && ea == null) || ((1 - ft[1]) * len < half - 1e-6 && eb == null)) {
                    return "turns inside the special section of " + sp.type + " " + sp.objectId;
                }
                out.add(new Interval(Math.max(0, ft[0] * len - half), Math.min(len, ft[1] * len + half),
                        sp.rule.getK(), sp.objectId, sp.type));
            }
        }
        return null;
    }

    private static boolean crossingWithin(List<SegmentClipper.Hit> hits, List<double[]> inside, double t0, double t1) {
        for (SegmentClipper.Hit h : hits) {
            if (h.t >= t0 && h.t <= t1) {
                return true;
            }
        }
        for (double[] r : inside) {
            if (r[1] >= t0 && r[0] <= t1) {
                return true;
            }
        }
        return false;
    }

    /** Union of overlapping intervals; the stricter (larger) coefficient wins on overlaps. */
    static List<Interval> merge(List<Interval> in, double len) {
        if (in.isEmpty()) {
            return Collections.emptyList();
        }
        List<Interval> sorted = new ArrayList<>(in);
        sorted.sort((x, y) -> Double.compare(x.from, y.from));
        List<Interval> out = new ArrayList<>();
        Interval cur = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Interval n = sorted.get(i);
            if (n.from <= cur.to + 0.01) {
                Interval hi = n.k > cur.k ? n : cur;
                cur = new Interval(cur.from, Math.max(cur.to, n.to), hi.k, hi.objectId, hi.type);
            } else {
                out.add(cur);
                cur = n;
            }
        }
        out.add(cur);
        List<Interval> clipped = new ArrayList<>();
        for (Interval iv : out) {
            double f = Math.max(0, iv.from);
            double t = Math.min(len, iv.to);
            if (t - f > 1e-6) {
                clipped.add(new Interval(f, t, iv.k, iv.objectId, iv.type));
            }
        }
        return clipped;
    }

    /**
     * Buffer with sharp (mitred) corners and square line ends: every edge is exactly at distance d, corners lie
     * farther, so the buffer contains the true clearance area and a corner of a building gives one turning point
     * instead of an arc of several.
     */
    static Geometry mitreBuffer(Geometry g, double d) {
        BufferParameters bp = new BufferParameters();
        bp.setJoinStyle(BufferParameters.JOIN_MITRE);
        bp.setMitreLimit(3.0);
        bp.setEndCapStyle(BufferParameters.CAP_SQUARE);
        return org.locationtech.jts.operation.buffer.BufferOp.bufferOp(g, d, bp);
    }
}
