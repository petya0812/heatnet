package ru.lct.heatnet.input;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.reference.RuleOptions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core input objects in UTM 37N: source, existing network with orientation towards the source,
 * chambers, prospective buildings with their connection points. Obstacles (existing buildings and
 * restrictions) are not kept here — they are served by an {@link ObstacleSource}, because they can be huge.
 */
public final class InputModel {

    /** Endpoint tolerance for network topology, metres. */
    public static final double TOPOLOGY_TOLERANCE_M = 0.5;
    /** Segment ends up to this far from a chamber are still treated as connected to it (with a warning). */
    public static final double CHAMBER_GAP_M = 2.0;

    public static final class Source {
        public final String id;
        public final Point point;

        Source(String id, Point point) {
            this.id = id;
            this.point = point;
        }
    }

    public static final class Segment {
        public final String id;
        /** Geometry oriented from the upstream end (towards the source) to the downstream end. */
        public final LineString line;
        public final int dn;
        public final double flowTph;
        public final String upstreamId;
        /** true if the input coordinate order runs from the downstream end to the upstream end. */
        public final boolean inputReversed;

        Segment(String id, LineString line, int dn, double flowTph, String upstreamId, boolean inputReversed) {
            this.id = id;
            this.line = line;
            this.dn = dn;
            this.flowTph = flowTph;
            this.upstreamId = upstreamId;
            this.inputReversed = inputReversed;
        }

        public double length() {
            return line.getLength();
        }
    }

    public static final class Chamber {
        public final String id;
        public final Point point;
        public final int dn;
        public final String upstreamId;
        /** Existing network segments adjacent to the chamber. */
        public final List<String> adjacentSegments;
        /** Segments meeting the chamber, a line passing through it counts twice (limit of 4, R-NET-5). */
        public int existingDegree;
        /** Set when the chamber lies inside its upstream segment (the line is not broken at the chamber). */
        public Segment insideSegment;
        /** Position of the chamber along {@link #insideSegment} from its upstream end. */
        public double insideT;

        Chamber(String id, Point point, int dn, String upstreamId, List<String> adjacentSegments) {
            this.id = id;
            this.point = point;
            this.dn = dn;
            this.upstreamId = upstreamId;
            this.adjacentSegments = adjacentSegments;
            this.existingDegree = adjacentSegments.size();
        }
    }

    public static final class Oks {
        public final String id;
        /** Outline of the building, or null when the input has only a connection point without a building. */
        public final Geometry polygon;
        /**
         * Id of the existing building the OKS connects to, when the building comes from the obstacles
         * (the contest dataset delivers buildings as restrictions). Such a building is not an obstacle
         * for the route of this OKS.
         */
        public final String ownBuildingId;
        public final double flowTph;
        public final double heatLoad;
        public final String connectionPointId;
        public final Point connectionPoint;
        /**
         * Where routing starts: the connection point, or the nearest point of the building outline when the
         * connection point lies inside the building (the last piece then goes from there to the point).
         */
        public final Point routePoint;

        Oks(String id, Geometry polygon, String ownBuildingId, double flowTph, double heatLoad,
            String connectionPointId, Point connectionPoint, Point routePoint) {
            this.id = id;
            this.polygon = polygon;
            this.ownBuildingId = ownBuildingId;
            this.flowTph = flowTph;
            this.heatLoad = heatLoad;
            this.connectionPointId = connectionPointId;
            this.connectionPoint = connectionPoint;
            this.routePoint = routePoint;
        }

        public boolean connectionInside() {
            return !routePoint.equalsExact(connectionPoint);
        }
    }

    private final Source source;
    private final Map<String, Segment> segments;
    private final Map<String, Chamber> chambers;
    private final List<Oks> oks;
    private final Map<String, Double> unroutableOks;
    private final Set<String> attached = new HashSet<>();
    private final STRtree segmentIndex = new STRtree();
    private final STRtree chamberIndex = new STRtree();

    private InputModel(Source source, Map<String, Segment> segments, Map<String, Chamber> chambers, List<Oks> oks,
                       Map<String, Double> unroutableOks) {
        this.source = source;
        this.segments = segments;
        this.chambers = chambers;
        this.oks = oks;
        this.unroutableOks = unroutableOks;
        // JTS geometries compute their envelope on first use; the planning threads share these objects,
        // so everything lazy is computed here, while the model is still being built in one thread
        for (Segment s : segments.values()) {
            s.line.getEnvelopeInternal();
            segmentIndex.insert(s.line.getEnvelopeInternal(), s);
        }
        segmentIndex.build();
        for (Chamber c : chambers.values()) {
            c.point.getEnvelopeInternal();
            chamberIndex.insert(c.point.getEnvelopeInternal(), c);
        }
        for (Oks o : oks) {
            o.connectionPoint.getEnvelopeInternal();
            o.routePoint.getEnvelopeInternal();
            if (o.polygon != null) {
                o.polygon.getEnvelopeInternal();
            }
        }
        chamberIndex.build();
    }

    public Source getSource() {
        return source;
    }

    public Map<String, Segment> getSegments() {
        return segments;
    }

    public Map<String, Chamber> getChambers() {
        return chambers;
    }

    public List<Oks> getOks() {
        return oks;
    }

    /** OKS without usable connection data (id → flow); they are reported as unconnected. */
    public Map<String, Double> getUnroutableOks() {
        return unroutableOks;
    }

    @SuppressWarnings("unchecked")
    public List<Segment> segmentsNear(Envelope env) {
        return segmentIndex.query(env);
    }

    /** Distance from a point to the nearest existing network segment (infinite if there is no network). */
    public double distanceToNetwork(Coordinate c) {
        if (segments.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        Point p = ru.lct.heatnet.geo.Crs.UTM_FACTORY.createPoint(c);
        Object nearest = segmentIndex.nearestNeighbour(new Envelope(c), p,
                (a, b) -> {
                    Object ia = a.getItem();
                    Object ib = b.getItem();
                    Geometry ga = ia instanceof Segment ? ((Segment) ia).line : (Geometry) ia;
                    Geometry gb = ib instanceof Segment ? ((Segment) ib).line : (Geometry) ib;
                    return ga.distance(gb);
                });
        return nearest == null ? Double.POSITIVE_INFINITY : ((Segment) nearest).line.distance(p);
    }

    public List<Chamber> chambersNear(Coordinate c, double radius) {
        List<Chamber> out = new ArrayList<>();
        Envelope env = new Envelope(c);
        env.expandBy(radius);
        for (Object o : chamberIndex.query(env)) {
            Chamber ch = (Chamber) o;
            if (ch.point.getCoordinate().distance(c) <= radius) {
                out.add(ch);
            }
        }
        return out;
    }

    /** Upstream object id of a segment or chamber, null for the source or unknown ids. */
    public String upstreamOf(String objectId) {
        Segment s = segments.get(objectId);
        if (s != null) {
            return s.upstreamId;
        }
        Chamber c = chambers.get(objectId);
        return c != null ? c.upstreamId : null;
    }

    /** The object's upstream chain reaches the source (tie-ins into detached parts are not allowed). */
    public boolean isAttached(String id) {
        return attached.contains(id);
    }

    public boolean isSource(String id) {
        return source != null && source.id.equals(id);
    }

    /** Flow assumed for an existing segment whose flow is not in the data ({@link RuleOptions#existingFlow}). */
    static double assumedFlow(ReferenceData ref, RuleOptions rules, int dn) {
        if (rules.existingFlow != RuleOptions.ExistingFlow.CAPACITY_SHARE) {
            return 0;
        }
        PipeSpec pipe = ref.isKnownDn(dn) ? ref.pipe(dn) : ref.pipeAtLeast(dn);
        return pipe == null ? 0 : pipe.getCapacityTph() * rules.existingFlowShare;
    }

    private static List<ParsedFeature> concatFeatures(Collection<ParsedFeature> a, Collection<ParsedFeature> b) {
        List<ParsedFeature> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** The smallest existing building that contains the point, or null. */
    private static Obstacle buildingAt(ObstacleSource obstacles, Point p) {
        if (obstacles == null) {
            return null;
        }
        Obstacle best = null;
        for (Obstacle o : obstacles.intersecting(p.buffer(0.01, 2))) {
            if (o.getObjectType() != ObjectType.OKS_EXISTING || !o.getGeometry().covers(p)) {
                continue;
            }
            if (best == null || o.getGeometry().getArea() < best.getGeometry().getArea()) {
                best = o;
            }
        }
        return best;
    }

    /**
     * Builds the model from core features (all types except oks_existing and restriction).
     * What the input does not carry is completed here and reported to the diagnostics: orientation towards the
     * source (from geometry), the DN of a chamber (the largest DN of its segments), the flow of an existing
     * segment ({@link RuleOptions#existingFlow}), the building of a connection point (from {@code obstacles}).
     */
    public static InputModel build(Collection<ParsedFeature> features, Diagnostics diag, ReferenceData ref,
                                   RuleOptions rules, ObstacleSource obstacles) {
        List<ParsedFeature> sources = new ArrayList<>();
        Map<String, ParsedFeature> segF = new LinkedHashMap<>();
        Map<String, ParsedFeature> chF = new LinkedHashMap<>();
        Map<String, ParsedFeature> oksF = new LinkedHashMap<>();
        Map<String, List<ParsedFeature>> cpByOks = new LinkedHashMap<>();
        List<ParsedFeature> ownCps = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ParsedFeature f : features) {
            if (!ids.add(f.getId())) {
                diag.error("feature.duplicate_id", f.getId(), "Duplicate id, later feature skipped");
                continue;
            }
            switch (f.getObjectType()) {
                case SOURCE:
                    sources.add(f);
                    break;
                case HEAT_NETWORK:
                    segF.put(f.getId(), f);
                    break;
                case HEAT_CHAMBER:
                    chF.put(f.getId(), f);
                    break;
                case OKS_FUTURE:
                    oksF.put(f.getId(), f);
                    break;
                case OKS_CONNECTION_POINT:
                    // without oks_id the connection point is the prospective OKS itself (the contest dataset)
                    if (f.getText("oks_id") == null || f.getText("oks_id").isEmpty()) {
                        ownCps.add(f);
                    } else {
                        cpByOks.computeIfAbsent(f.getText("oks_id"), k -> new ArrayList<>()).add(f);
                    }
                    break;
                default:
                    break;
            }
        }
        Source source = null;
        if (sources.isEmpty()) {
            diag.error("network.no_source", null, "No source object");
        } else {
            if (sources.size() > 1) {
                diag.error("network.many_sources", null, sources.size() + " sources, the first one is used");
            }
            source = new Source(sources.get(0).getId(), (Point) sources.get(0).getUtm());
        }

        boolean upstreamGiven = true;
        for (ParsedFeature f : concatFeatures(segF.values(), chF.values())) {
            upstreamGiven &= f.getText("upstream_object_id") != null;
        }
        Map<String, String> derivedUpstream = upstreamGiven ? Collections.emptyMap()
                : Topology.derive(segF, chF, sources.isEmpty() ? null : sources.get(0), diag);

        Map<String, Segment> segments = new LinkedHashMap<>();
        for (ParsedFeature f : segF.values()) {
            LineString line = (LineString) f.getUtm();
            String up = f.getText("upstream_object_id");
            if (up == null) {
                up = derivedUpstream.get(f.getId());
            }
            Geometry upGeom = null;
            if (source != null && source.id.equals(up)) {
                upGeom = source.point;
            } else if (segF.containsKey(up)) {
                upGeom = segF.get(up).getUtm();
            } else if (chF.containsKey(up)) {
                upGeom = chF.get(up).getUtm();
            } else if (up != null) {
                diag.error("network.bad_upstream_ref", f.getId(), "upstream_object_id '" + up + "' not found");
            } else {
                diag.warning("network.no_upstream", f.getId(),
                        "Segment is not connected to the source: no tie-ins are made there");
            }
            boolean reversed = false;
            if (upGeom != null) {
                double ds = upGeom.distance(line.getStartPoint());
                double de = upGeom.distance(line.getEndPoint());
                reversed = de < ds;
                if (Math.min(ds, de) > TOPOLOGY_TOLERANCE_M) {
                    diag.warning("network.upstream_not_adjacent", f.getId(), String.format(
                            "Segment does not touch its upstream object '%s' (gap %.2f m)", up, Math.min(ds, de)));
                }
            }
            LineString oriented = reversed ? line.reverse() : line;
            int dn = f.getInt("diameter");
            double flow;
            if (f.has("flow_tph")) {
                flow = f.getDouble("flow_tph");
            } else {
                flow = assumedFlow(ref, rules, dn);
                diag.warning("network.flow_assumed", f.getId(), String.format(
                        "flow_tph is not in the data; assumed %.1f t/h (%s)", flow, rules.existingFlow));
            }
            segments.put(f.getId(), new Segment(f.getId(), oriented, dn, flow, up, reversed));
        }

        STRtree segTree = new STRtree();
        for (Segment s : segments.values()) {
            segTree.insert(s.line.getEnvelopeInternal(), s);
        }
        STRtree endpoints = new STRtree();
        for (Segment s : segments.values()) {
            endpoints.insert(new Envelope(s.line.getStartPoint().getCoordinate()), s);
            endpoints.insert(new Envelope(s.line.getEndPoint().getCoordinate()), s);
        }
        Map<String, Chamber> chambers = new LinkedHashMap<>();
        for (ParsedFeature f : chF.values()) {
            Point p = (Point) f.getUtm();
            String up = f.getText("upstream_object_id");
            if (up == null) {
                up = derivedUpstream.get(f.getId());
            }
            if (up == null) {
                diag.warning("network.no_upstream", f.getId(),
                        "Chamber is not connected to the source: no tie-ins are made there");
            } else if (!(source != null && source.id.equals(up)) && !segF.containsKey(up) && !chF.containsKey(up)) {
                diag.error("network.bad_upstream_ref", f.getId(), "upstream_object_id '" + up + "' not found");
            }
            Envelope env = new Envelope(p.getCoordinate());
            env.expandBy(CHAMBER_GAP_M);
            Set<String> adj = new java.util.LinkedHashSet<>();
            double worstGap = 0;
            for (Object o : endpoints.query(env)) {
                Segment s = (Segment) o;
                double gap = Math.min(s.line.getStartPoint().distance(p), s.line.getEndPoint().distance(p));
                if (gap <= CHAMBER_GAP_M) {
                    adj.add(s.id);
                    worstGap = Math.max(worstGap, gap);
                }
            }
            if (worstGap > TOPOLOGY_TOLERANCE_M) {
                diag.warning("network.chamber_gap", f.getId(), String.format(
                        "Segment end %.2f m from the chamber, treated as connected", worstGap));
            }
            int chDn = f.getInt("diameter");
            if (!f.has("diameter")) {
                for (String sid : adj) {
                    chDn = Math.max(chDn, segments.get(sid).dn);
                }
                diag.warning("network.chamber_dn_assumed", f.getId(), "diameter is not in the data; assumed "
                        + chDn + " mm — the largest DN of the adjacent segments");
            }
            Chamber ch = new Chamber(f.getId(), p, chDn, up, new ArrayList<>(adj));
            // a line passing through the chamber without a break: two of its pieces meet the chamber
            Envelope near = new Envelope(p.getCoordinate());
            near.expandBy(TOPOLOGY_TOLERANCE_M);
            for (Object o : segTree.query(near)) {
                Segment s = (Segment) o;
                if (adj.contains(s.id) || !s.line.isWithinDistance(p, TOPOLOGY_TOLERANCE_M)) {
                    continue;
                }
                ch.adjacentSegments.add(s.id);
                ch.existingDegree += 2;
                if (s.id.equals(up)) {
                    ch.insideSegment = s;
                    ch.insideT = new org.locationtech.jts.linearref.LengthIndexedLine(s.line).project(p.getCoordinate());
                }
                diag.warning("network.chamber_inside_segment", f.getId(), "Segment " + s.id
                        + " passes through the chamber without a break; counted as two segments");
            }
            chambers.put(f.getId(), ch);
        }

        // Chains must reach the source without cycles.
        Set<String> attached = new HashSet<>();
        for (String id : concat(segments.keySet(), chambers.keySet())) {
            Set<String> seen = new HashSet<>();
            String cur = id;
            while (true) {
                if (source != null && source.id.equals(cur)) {
                    attached.add(id);
                    break;
                }
                if (!seen.add(cur)) {
                    diag.error("network.chain_cycle", id, "upstream chain has a cycle");
                    break;
                }
                String next = segments.containsKey(cur) ? segments.get(cur).upstreamId
                        : chambers.containsKey(cur) ? chambers.get(cur).upstreamId : null;
                if (next == null) {
                    diag.error("network.chain_broken", id, "upstream chain does not reach the source (stops at '"
                            + cur + "')");
                    break;
                }
                cur = next;
            }
        }
        // Flow and DN must not decrease towards the source. Only a warning: data as given.
        for (Segment s : segments.values()) {
            Segment up = segments.get(s.upstreamId);
            if (up != null && up.dn < s.dn) {
                diag.warning("network.dn_decreases_upstream", s.id, "DN " + s.dn + " > upstream DN " + up.dn);
            }
        }

        List<Oks> oks = new ArrayList<>();
        Map<String, Double> unroutable = new LinkedHashMap<>();
        for (ParsedFeature f : oksF.values()) {
            List<ParsedFeature> cps = cpByOks.remove(f.getId());
            if (cps == null || cps.isEmpty()) {
                diag.error("oks.no_connection_point", f.getId(), "oks_future without oks_connection_point");
                unroutable.put(f.getId(), f.getDouble("flow_tph"));
                continue;
            }
            if (cps.size() > 1) {
                diag.warning("oks.many_connection_points", f.getId(), cps.size()
                        + " connection points, the first one is used");
            }
            Point cp = (Point) cps.get(0).getUtm();
            double gap = f.getUtm().getBoundary().distance(cp);
            Point routePoint = cp;
            if (gap > 0.05 && f.getUtm().contains(cp)) {
                Coordinate onEdge = org.locationtech.jts.operation.distance.DistanceOp
                        .nearestPoints(f.getUtm().getBoundary(), cp)[0];
                routePoint = cp.getFactory().createPoint(onEdge);
                diag.warning("oks.connection_point_inside", cps.get(0).getId(), String.format(
                        "Connection point is %.2f m inside the building; the route comes to the outline and goes in", gap));
            } else if (gap > 1.0) {
                diag.warning("oks.connection_point_off_boundary", cps.get(0).getId(),
                        String.format("Connection point is %.2f m from the OKS boundary", gap));
            }
            oks.add(new Oks(f.getId(), f.getUtm(), null, f.getDouble("flow_tph"), f.getDouble("heat_load"),
                    cps.get(0).getId(), cp, routePoint));
        }
        // a connection point without oks_id is the prospective OKS itself; its building is looked up
        // among the existing buildings (the contest dataset delivers them as restrictions)
        for (ParsedFeature f : ownCps) {
            Point cp = (Point) f.getUtm();
            if (!f.has("flow_tph")) {
                diag.error("oks.no_flow", f.getId(), "oks_connection_point without oks_id and without flow_tph");
                unroutable.put(f.getId(), 0.0);
                continue;
            }
            Obstacle building = buildingAt(obstacles, cp);
            Geometry polygon = building == null ? null : building.getGeometry();
            Point routePoint = cp;
            if (polygon == null) {
                diag.warning("oks.point_without_building", f.getId(),
                        "The connection point is not inside any existing building: the route comes to the point itself");
            } else {
                double gap = polygon.getBoundary().distance(cp);
                if (gap > 0.05) {
                    Coordinate onEdge = org.locationtech.jts.operation.distance.DistanceOp
                            .nearestPoints(polygon.getBoundary(), cp)[0];
                    routePoint = cp.getFactory().createPoint(onEdge);
                    diag.info("oks.building_from_restriction", f.getId(), String.format(
                            "The OKS is building %s; the connection point is %.2f m inside it, the route comes to the "
                                    + "outline and goes in", building.getId(), gap));
                }
            }
            oks.add(new Oks(f.getId(), polygon, building == null ? null : building.getId(),
                    f.getDouble("flow_tph"), f.getDouble("heat_load", 0), f.getId(), cp, routePoint));
        }
        for (Map.Entry<String, List<ParsedFeature>> e : cpByOks.entrySet()) {
            for (ParsedFeature cp : e.getValue()) {
                diag.error("oks.connection_point_orphan", cp.getId(), "oks_id '" + e.getKey() + "' not found");
            }
        }
        if (segments.isEmpty()) {
            diag.error("network.empty", null, "No existing heat_network segments");
        }
        InputModel m = new InputModel(source, segments, chambers, oks, unroutable);
        m.attached.addAll(attached);
        return m;
    }

    private static List<String> concat(Collection<String> a, Collection<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }
}
