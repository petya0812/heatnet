package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.Obstacle;
import ru.lct.heatnet.input.ObstacleSource;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.reference.RestrictionRule;
import ru.lct.heatnet.reference.RuleOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the cheapest route (in score units, section 6 of the appendix) from an OKS connection point to
 * the existing network: A* over a visibility graph whose vertices are convex corners of blocking zones.
 * Straight edges between corners give routes of few straight pieces without grid staircases (R-NET-6).
 */
public final class RouteFinder {

    /** A route already built for another OKS; new routes keep clear of it (R-NET-7). */
    public static final class Built {
        public final LineString line;
        public final PipeSpec pipe;
        public final String oksId;

        public Built(LineString line, PipeSpec pipe, String oksId) {
            this.line = line;
            this.pipe = pipe;
            this.oksId = oksId;
        }
    }

    private final ReferenceData ref;
    private final ZoneCache zoneCache;
    /** Static zones of recent search areas; bounded, the planner comes back to the same OKS many times. */
    private final Map<String, RoutingContext.Static> sharedCache;
    /** Exits from the building of every OKS (they depend on its outline and connection point only). */
    private final Map<InputModel.Oks, List<BuildingEntry.Exit>> exitCache;
    /** Hints why an OKS has no route from its nearest wall, by OKS and DN; empty when there is none. */
    private final Map<String, String> entryHints = new java.util.concurrent.ConcurrentHashMap<>();
    private final PlanParams params;
    private final InputModel model;
    private final ObstacleSource obstacles;

    public RouteFinder(ReferenceData ref, PlanParams params, InputModel model, ObstacleSource obstacles) {
        this.ref = ref;
        this.params = params;
        this.model = model;
        this.obstacles = obstacles;
        this.zoneCache = new ZoneCache();
        this.sharedCache = new java.util.LinkedHashMap<String, RoutingContext.Static>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RoutingContext.Static> eldest) {
                return size() > params.contextCache;
            }
        };
        this.exitCache = new java.util.concurrent.ConcurrentHashMap<>();
        for (InputModel.Oks o : model.getOks()) {
            if (o.ownBuildingId != null) {
                oksBuildings.add(o.ownBuildingId);
            }
        }
        crossing = RestrictionRule.crossing(RuleOptions.BUILDING_CROSSING, params.rules.buildingCrossingK);
    }

    /**
     * A finder for one sequence of searches (one build of a variant and its local search): the caches that depend only
     * on the data (zones, static parts of the search areas, exits from buildings) are shared, the hints why an OKS has
     * no route are its own. A hint is found on the network of the first failed search of that OKS; with a shared cache
     * which build fails first would depend on the threads, so would the reason reported.
     */
    RouteFinder session() {
        return new RouteFinder(this);
    }

    /**
     * Legs evaluated by the searches of this finder so far: the work of a sequence of searches, the same whatever the
     * threads and caches (a measure for bounding optional moves of the local search without a clock).
     */
    private long work;

    long work() {
        return work;
    }

    private RouteFinder(RouteFinder base) {
        this.ref = base.ref;
        this.params = base.params;
        this.model = base.model;
        this.obstacles = base.obstacles;
        this.zoneCache = base.zoneCache;
        this.sharedCache = base.sharedCache;
        this.exitCache = base.exitCache;
        this.oksBuildings.addAll(base.oksBuildings);
        this.crossing = base.crossing;
        this.bannedTieObjects = base.bannedTieObjects;
    }

    /** Buildings of the OKS being connected. */
    private final java.util.Set<String> oksBuildings = new java.util.HashSet<>();
    /** Rule of a crossing of a building the route may go through ({@link RuleOptions#throughBuildings}). */
    private final RestrictionRule crossing;

    /** An existing building other than the own one that the route may go through by the settings. */
    private boolean crossable(Obstacle o) {
        RuleOptions.ThroughBuildings t = params.rules.throughBuildings;
        return t != RuleOptions.ThroughBuildings.NONE && o.getObjectType() == ru.lct.heatnet.input.ObjectType.OKS_EXISTING
                && (t == RuleOptions.ThroughBuildings.ANY || !oksBuildings.contains(o.getId()));
    }

    /** Route of one OKS; when no route is found {@code fail} tells why. */
    public Route find(InputModel.Oks oks, PipeSpec pipe, List<Built> built, NetworkState state, AttachProvider attach,
                      Diagnostics diag, Unconnected.Failure fail) {
        return find(oks, pipe, built, state, attach, diag, fail, null);
    }

    /**
     * The search of one OKS against the existing network alone (nothing built yet) with its trace recorded —
     * to show how the search goes around restrictions.
     */
    public SearchTrace trace(InputModel.Oks oks, NetworkState state) {
        SearchTrace trace = new SearchTrace();
        PipeSpec pipe = ref.minPipeForFlow(oks.flowTph);
        if (pipe == null) {
            trace.failure = Unconnected.Reason.FLOW_EXCEEDS_MAX_DN;
            return trace;
        }
        Unconnected.Failure fail = new Unconnected.Failure();
        trace.route = find(oks, pipe, java.util.Collections.<Built>emptyList(), state, null, new Diagnostics(), fail,
                trace);
        if (trace.route == null) {
            trace.failure = fail.reason;
            trace.failureDetail = fail.detail;
        }
        return trace;
    }

    private Route find(InputModel.Oks oks, PipeSpec pipe, List<Built> built, NetworkState state, AttachProvider attach,
                       Diagnostics diag, Unconnected.Failure fail, SearchTrace trace) {
        Ties ties = new Ties(params.rules.tieInInClearance);
        Route r = find(oks, entryWalls(oks), pipe, built, state, attach, diag, fail, trace, ties);
        if (r == null && params.rules.entry == RuleOptions.Entry.NEAREST
                && (fail.reason == Unconnected.Reason.NO_ROUTE || fail.reason == Unconnected.Reason.SEARCH_LIMIT
                || fail.reason == Unconnected.Reason.ENTRY_BLOCKED)) {
            String hint = entryHint(oks, pipe, built, state, attach);
            if (hint != null) {
                fail.set(Unconnected.Reason.ENTRY_BLOCKED, hint);
            }
        }
        if (r == null && ties.closed && (fail.reason == Unconnected.Reason.NO_ROUTE
                || fail.reason == Unconnected.Reason.SEARCH_LIMIT)) {
            String hint = tieHint(oks, pipe, built, state, attach);
            if (hint != null) {
                fail.set(Unconnected.Reason.TIE_IN_IN_CLEARANCE, hint);
            }
        }
        return r;
    }

    /**
     * Whether a tie-in into the existing network may lie within the clearance to an object
     * ({@link RuleOptions#tieInInClearance}), and what one search met of such tie-ins.
     */
    private static final class Ties {
        final boolean inClearance;
        /** A tie-in point was not taken: it lies within a clearance. */
        boolean closed;
        /** Tie-ins taken within clearances, by key: the objects, for the reason of an OKS left without a route. */
        final Map<String, String> within = new HashMap<>();

        Ties(boolean inClearance) {
            this.inClearance = inClearance;
        }
    }

    /** Hints why an OKS has no route with tie-ins outside the clearances, by OKS and DN; empty when there is none. */
    private final Map<String, String> tieHints = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Why an OKS left without a route would have one with a tie-in within a clearance: where that tie-in is.
     * Null when such tie-ins are not the cause.
     */
    private String tieHint(InputModel.Oks oks, PipeSpec pipe, List<Built> built, NetworkState state,
                           AttachProvider attach) {
        String key = oks.id + '|' + pipe.getDn();
        String hint = tieHints.get(key);
        if (hint == null) {
            Ties allowed = new Ties(true);
            Route r = find(oks, entryWalls(oks), pipe, built, state, attach, new Diagnostics(),
                    new Unconnected.Failure(), null, allowed);
            String where = r == null ? null : allowed.within.get(r.tie.key());
            hint = where == null ? "" : (r.tie.chamber != null ? "врезка в существующую камеру " + r.tie.chamber.id
                    : "врезка в участок " + r.tie.segment.id) + " — в зоне отступа к " + where + "; по техническим "
                    + "требованиям (п. 2.2, разд. 4) отступ действует на всю трассу, кроме прямого участка ввода в "
                    + "здание. Разрешить такую врезку — настройка «Врезка в зоне отступа»";
            tieHints.put(key, hint);
        }
        return hint.isEmpty() ? null : hint;
    }

    /** The objects whose clearances contain the point: "зданию 10 (3,2 м от него)". */
    private static String clearancesText(List<RoutingContext.Zone> zones, Coordinate c) {
        List<String> out = new ArrayList<>();
        org.locationtech.jts.geom.Point p = Crs.UTM_FACTORY.createPoint(c);
        for (RoutingContext.Zone z : zones) {
            out.add((z.kind.startsWith("oks") || "own_oks".equals(z.kind) ? "зданию " : "ограничению " + z.kind
                    + " ") + z.objectId + " (" + metres(z.raw.distance(p)) + " м от него)");
        }
        return String.join(", ", out);
    }

    /** Exits from the building of the OKS, nearest first, as many as {@link PlanParams#entryWalls}. */
    private List<BuildingEntry.Exit> exits(InputModel.Oks oks) {
        return exitCache.computeIfAbsent(oks, o -> BuildingEntry.exits(o, Math.max(1, params.entryWalls)));
    }

    /**
     * Exits the route may leave the building by in the entry mode, nearest first; empty when the connection point is
     * outside its building. The search takes the first one it can leave by.
     */
    private List<BuildingEntry.Exit> entryWalls(InputModel.Oks oks) {
        if (!params.rules.entry.alongNormal()) {
            return Collections.emptyList(); // the starts are points of the outline, see outlineStarts
        }
        List<BuildingEntry.Exit> all = exits(oks);
        if (all.isEmpty() || params.rules.entry == RuleOptions.Entry.NEAREST) {
            return all.isEmpty() ? all : all.subList(0, 1);
        }
        if (params.rules.entry == RuleOptions.Entry.ANY) {
            return all;
        }
        List<BuildingEntry.Exit> out = new ArrayList<>();
        for (BuildingEntry.Exit x : all) {
            if (x.insideM <= all.get(0).insideM + params.rules.entrySlackM + 1e-9) {
                out.add(x);
            }
        }
        return out;
    }

    /**
     * Why an OKS left without a route from its nearest wall would have one in another entry mode: a route from
     * another wall exists, with so much more pipe inside the building. Null when the walls are not the cause.
     */
    private String entryHint(InputModel.Oks oks, PipeSpec pipe, List<Built> built, NetworkState state,
                             AttachProvider attach) {
        List<BuildingEntry.Exit> all = exits(oks);
        if (all.size() < 2) {
            return null;
        }
        String key = oks.id + '|' + pipe.getDn();
        String hint = entryHints.get(key);
        if (hint == null) {
            Route r = find(oks, all.subList(1, all.size()), pipe, built, state, attach, new Diagnostics(),
                    new Unconnected.Failure(), null, new Ties(params.rules.tieInInClearance));
            hint = "";
            if (r != null) {
                double nearest = all.get(0).insideM;
                double other = r.coords.get(r.coords.size() - 1).distance(oks.connectionPoint.getCoordinate());
                RuleOptions.Entry mode = other <= nearest + params.rules.entrySlackM + 1e-9
                        ? RuleOptions.Entry.NEAR : RuleOptions.Entry.ANY;
                hint = "с ближайшей стены (" + metres(nearest) + " м внутри здания) выйти нельзя; со стены в "
                        + metres(other) + " м внутри здания трасса есть — ввод в здание «" + mode.label() + "»";
            }
            entryHints.put(key, hint);
        }
        return hint.isEmpty() ? null : hint;
    }

    private static String metres(double m) {
        return String.valueOf(Math.round(m * 10) / 10.0).replace('.', ',');
    }

    private Route find(InputModel.Oks oks, List<BuildingEntry.Exit> walls, PipeSpec pipe, List<Built> built,
                       NetworkState state, AttachProvider attach, Diagnostics diag, Unconnected.Failure fail,
                       SearchTrace trace, Ties ties) {
        Coordinate p = oks.routePoint.getCoordinate();
        fail.set(Unconnected.Reason.NO_ROUTE, null);
        double dNear = model.distanceToNetwork(p);
        if (Double.isInfinite(dNear)) {
            fail.set(Unconnected.Reason.NO_NETWORK, null);
            return null;
        }
        double radius = Math.max(params.searchRadiusMinM,
                Math.min(params.searchRadiusMaxM, params.searchRadiusFactor * dNear + params.searchRadiusMarginM));
        for (int attempt = 0; attempt <= params.radiusRetries; attempt++) {
            RoutingContext ctx = context(oks, pipe, built, p, radius);
            double slack = params.corridorSlack * (1 << attempt);
            // A route goes along the directions of the chosen turn angles (a tidier trace, fewer states). It leaves its
            // building along the normal of the wall (the straight piece through the clearance, appendix 2.2) and then
            // follows the directions of the network piece it is nearest to, so that it meets the pipe at a right angle;
            // one turn off the directions links the two. The directions of the wall are the other choice (a route
            // along the facades): the cheaper of the two routes is taken. With strict angles only the directions of
            // the wall, so that every bend is a standard one. Unless the angles are strict, a free search with any
            // angles up to 90° (appendix 2.1) is the last resort.
            Frame own = params.turnStepDeg <= 0 ? null : walls.isEmpty() ? frameFor(ctx, p)
                    : Frame.squareTo(walls.get(0).nx, walls.get(0).ny, params.turnStepDeg);
            List<Frame> frames = new ArrayList<>();
            if (own != null) {
                Frame net = params.turnStepStrict ? null : networkFrame(p, attach);
                if (net != null && !net.sameAs(own)) {
                    frames.add(net);
                }
                frames.add(own);
            }
            Route r = null;
            Frame chosen = null;
            for (int k = 0; k < frames.size(); k++) {
                Frame f = frames.get(k);
                if (trace != null) {
                    trace.reset(radius);
                }
                // the other directions only for a cheaper route: the search stops at the score of the one found
                double bound = r == null ? Double.POSITIVE_INFINITY : r.preference + r.score - 1e-9;
                Route x = search(oks, walls, pipe, ctx, state, attach, diag, fail, slack, trace, f, ties, bound);
                if (x != null && (r == null || x.preference + x.score < r.preference + r.score - 1e-9)) {
                    r = x;
                    chosen = f;
                }
            }
            if (r == null && (own == null || !params.turnStepStrict)) {
                if (trace != null) {
                    trace.reset(radius);
                }
                r = search(oks, walls, pipe, ctx, state, attach, diag, fail, slack, trace, null, ties,
                        Double.POSITIVE_INFINITY);
            } else if (r != null && trace != null && chosen != frames.get(frames.size() - 1)) {
                // the trace shows the search that gave the route
                trace.reset(radius);
                r = search(oks, walls, pipe, ctx, state, attach, diag, fail, slack, trace, chosen, ties,
                        Double.POSITIVE_INFINITY);
            }
            if (r != null) {
                return r;
            }
            if (fail.reason == Unconnected.Reason.ENTRY_BLOCKED) {
                return null; // no way out of the building: a larger area does not help
            }
            if (fail.reason != Unconnected.Reason.SEARCH_LIMIT) {
                fail.set(Unconnected.Reason.NO_ROUTE, String.format("радиус поиска %.0f м, ДУ %d, до сети %.0f м%s",
                        radius, pipe.getDn(), dNear, own != null && params.turnStepStrict
                                ? ", повороты только " + PlanParams.turnAnglesText(params.turnStepDeg) : ""));
            }
            if (radius >= params.searchRadiusMaxM) {
                break;
            }
            radius = Math.min(params.searchRadiusMaxM, radius * 2);
        }
        return null;
    }

    RoutingContext context(InputModel.Oks oks, PipeSpec pipe, List<Built> built, Coordinate p, double radius) {
        Geometry area = Crs.UTM_FACTORY.createPoint(p).buffer(radius, 16);
        RoutingContext.Static shared = sharedZones(oks, pipe, p, radius, area);
        RoutingContext ctx = new RoutingContext(ref, params, pipe, area, zoneCache, shared);
        // routes already built for this variant are the only obstacles that change while planning
        for (Built b : built) {
            if (b.line.intersects(area)) {
                ctx.addForbidden("new:" + b.oksId, RoutingContext.NEW_ROUTE, b.line,
                        b.pipe.getHalfWidthM() + params.newRouteClearanceM + pipe.getHalfWidthM());
            }
        }
        ctx.build();
        return ctx;
    }

    /**
     * Zones of everything that does not change while planning — obstacles, other OKS, the existing network —
     * for one search area and DN. They are the expensive part of a search, so they are computed once and reused.
     */
    private RoutingContext.Static sharedZones(InputModel.Oks oks, PipeSpec pipe, Coordinate p, double radius,
                                              Geometry area) {
        // exact values: the area is a circle of this radius around this point, and the zones follow from it
        String key = (oks == null ? "-" : oks.id) + '|' + pipe.getDn() + '|' + Double.doubleToLongBits(radius)
                + '|' + Double.doubleToLongBits(p.x) + '|' + Double.doubleToLongBits(p.y);
        synchronized (sharedCache) {
            RoutingContext.Static cached = sharedCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        RoutingContext ctx = new RoutingContext(ref, params, pipe, area, zoneCache, null);
        addStaticObjects(ctx, oks, p, radius);
        ctx.build();
        RoutingContext.Static shared = ctx.toStatic();
        synchronized (sharedCache) {
            sharedCache.put(key, shared);
        }
        return shared;
    }

    /** Obstacles, other OKS and the existing network around {@code p} as zones and specials of {@code ctx}. */
    private void addStaticObjects(RoutingContext ctx, InputModel.Oks oks, Coordinate p, double radius) {
        Geometry loadArea = Crs.UTM_FACTORY.createPoint(p).buffer(radius + params.obstacleMarginM, 16);
        // the own building keeps its clearance (appendix 2.2) except on the leg of the start; with the entry from any
        // point of the outline it keeps none. With throughOwnParts only its main part is an obstacle
        String ownBuilding = oks == null ? null : oks.ownBuildingId;
        for (Obstacle o : obstacles.intersecting(loadArea)) {
            if (o.getId().equals(ownBuilding)) {
                Geometry own = params.rules.throughOwnParts ? oks.mainPart() : o.getGeometry();
                if (!params.rules.entry.alongNormal()) {
                    ctx.addOwnBuilding(o.getId(), own);
                } else if (own == o.getGeometry()) {
                    ctx.addObstacle(o);
                } else {
                    // the main part depends on the OKS (a building may hold several connection points in different
                    // parts): the cached zone is that OKS's, never one made for another OKS of the building
                    ctx.addForbidden(o.getId(), "oks_existing/main", own, ctx.clearanceFor(o),
                            o.getId() + "/main-of/" + oks.id);
                }
            } else if (crossable(o)) {
                ctx.addCrossing(o.getId(), crossing, o.getGeometry());
            } else {
                ctx.addObstacle(o);
            }
        }
        // oks_future polygons (earlier input format) are not obstacles; OKS outlines of the current format come as
        // restrictions of type oks and are obstacles (appendix 2.2 and 4, clarification 3). The own oks_future
        // footprint only keeps the route outside up to its connection point
        if (oks != null && oks.polygon != null && oks.ownBuildingId == null) {
            ctx.addOwnBuilding(oks.id, oks.polygon);
        }
        for (InputModel.Segment s : model.segmentsNear(loadArea.getEnvelopeInternal())) {
            if (s.line.intersects(loadArea)) {
                ctx.addExistingNetwork(s);
            }
        }
    }

    /** Extra goals of the search: points on the new network of the variant (joint connection). */
    public interface AttachProvider {
        /** Attach candidates reachable from a vertex; terminal scores estimate the whole cost of joining there. */
        List<TieOption> attachOptions(Coordinate v, PipeSpec pipe, double flow);

        /** Distance from a point to the new network (for the search heuristic). */
        double distance(Coordinate v);

        /** Pieces of the new network: {from, to}. */
        default List<Coordinate[]> pieces() {
            return Collections.emptyList();
        }

        /** The piece of the new network nearest to a point: {dx, dy, distance}, or null without one. */
        default double[] nearestPiece(Coordinate v) {
            return null;
        }
    }

    private java.util.Set<String> bannedTieObjects = java.util.Collections.emptySet();

    /** Existing objects that must not be used for tie-ins (to build an alternative variant). */
    public void setBannedTieObjects(java.util.Set<String> ids) {
        this.bannedTieObjects = ids;
    }

    /**
     * Checks an already built polyline again (after its DN grew): clearances and special sections for the new DN.
     * {@code ownOks} is the OKS whose connection point ends the polyline, or null.
     */
    boolean checkPolyline(List<Coordinate> coords, PipeSpec pipe, InputModel.Oks ownOks, boolean startsAtTie) {
        return evaluatePolyline(coords, pipe, ownOks, startsAtTie) != null;
    }

    /** Evaluations of the straight parts of a polyline, or null if one of them is invalid. */
    List<RoutingContext.EdgeEval> evaluatePolyline(List<Coordinate> coords, PipeSpec pipe, InputModel.Oks ownOks,
                                                   boolean startsAtTie) {
        org.locationtech.jts.geom.Envelope env = new org.locationtech.jts.geom.Envelope();
        for (Coordinate c : coords) {
            env.expandToInclude(c);
        }
        Coordinate center = env.centre();
        double radius = Math.hypot(env.getWidth(), env.getHeight()) / 2 + 30;
        RoutingContext ctx = context(ownOks, pipe, java.util.Collections.<Built>emptyList(), center, radius);
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (startsAtTie && !params.rules.tieInInClearance && !ctx.objectClearancesAt(first).isEmpty()) {
            return null; // with this DN the tie-in lies within a clearance
        }
        RoutingContext.Endpoint eStart = startsAtTie ? ctx.endpoint(first, ctx.specialsAround(first, 0.05)) : null;
        RoutingContext.Endpoint eEnd = ownOks != null ? ctx.endpoint(last, ctx.specialsAround(last, 0.0)) : null;
        List<RoutingContext.EdgeEval> out = new ArrayList<>();
        // the entry piece into the building (connection point inside the outline) is not a route piece
        int pieces = coords.size() - 1 - (ownOks != null && ownOks.connectionInside() ? 1 : 0);
        if (pieces < coords.size() - 1) {
            last = coords.get(pieces);
            eEnd = ctx.endpoint(last, ctx.specialsAround(last, 0.0));
        }
        for (int i = 0; i < pieces; i++) {
            RoutingContext.EdgeEval e = ctx.evaluate(coords.get(i), coords.get(i + 1), i == 0 ? eStart : null,
                    i + 1 == pieces ? eEnd : null);
            if (!e.valid) {
                return null;
            }
            out.add(e);
        }
        return out;
    }

    /**
     * A new way for a piece of the new network between its two nodes, for a larger DN (the piece is shared and grew,
     * the old way no longer keeps the clearances). The same graph and rules as the search of a route: turning points
     * of the zones around the piece, legs checked by {@link RoutingContext#evaluate} for this DN, turns up to 90°, the
     * turn penalty; first along the directions of the old piece, then (unless the angles are strict) with any angle.
     * The nodes stay where they are; at a tie-in the end is excused like the tie-in of a route. {@code built} — the
     * other new pieces (kept apart like any built route). Null when there is no such way.
     */
    List<Coordinate> relay(List<Coordinate> old, PipeSpec pipe, boolean startsAtTie, List<Built> built) {
        Coordinate a = old.get(0);
        Coordinate b = old.get(old.size() - 1);
        double oldLength = 0;
        double[] longest = null;
        for (int i = 0; i + 1 < old.size(); i++) {
            double l = old.get(i).distance(old.get(i + 1));
            oldLength += l;
            if (longest == null || l > longest[2]) {
                longest = new double[]{old.get(i + 1).x - old.get(i).x, old.get(i + 1).y - old.get(i).y, l};
            }
        }
        // the new way may be somewhat longer than the old one: points beyond that ellipse are not used
        double budget = Math.max(oldLength * RELAY_SLACK, a.distance(b)) + RELAY_MARGIN_M;
        Coordinate centre = new Coordinate((a.x + b.x) / 2, (a.y + b.y) / 2);
        RoutingContext ctx = context(null, pipe, built, centre, budget / 2 + RELAY_MARGIN_M);
        if (startsAtTie && !params.rules.tieInInClearance && !ctx.objectClearancesAt(a).isEmpty()) {
            return null; // with this DN the tie-in itself lies within a clearance
        }
        RoutingContext.Endpoint ea = startsAtTie ? ctx.endpoint(a, ctx.specialsAround(a, 0.05)) : null;
        List<Coordinate> verts = new ArrayList<>();
        verts.add(a);
        verts.add(b);
        for (Coordinate c : ctx.buildVertices()) {
            if (c.distance(a) + c.distance(b) <= budget) {
                verts.add(c);
            }
        }
        if (verts.size() > params.maxVertices) {
            verts.subList(params.maxVertices, verts.size()).clear();
        }
        Frame frame = params.turnStepDeg <= 0 || longest == null ? null
                : Frame.squareTo(longest[0], longest[1], params.turnStepDeg);
        List<Coordinate> way = relaySearch(ctx, frame, verts, ea, pipe);
        if (way == null && frame != null && !params.turnStepStrict) {
            way = relaySearch(ctx, null, verts, ea, pipe);
        }
        return way;
    }

    /** The new way may be this many times longer than the old one (plus {@link #RELAY_MARGIN_M}). */
    private static final double RELAY_SLACK = 1.5;
    private static final double RELAY_MARGIN_M = 30;

    /**
     * A* from {@code verts[0]} to {@code verts[1]}: a state is a point with the heading of arrival (without directions,
     * a point with the point it was reached from, so that turns sharper than 90° are never taken).
     */
    private List<Coordinate> relaySearch(RoutingContext ctx, Frame frame, List<Coordinate> verts,
                                         RoutingContext.Endpoint ea, PipeSpec pipe) {
        int n = verts.size();
        Coordinate goal = verts.get(1);
        // headings along the directions; without them the previous point (n — none) plays the part of the heading
        int hs = frame == null ? n + 1 : frame.headings + 1;
        double minPerM = ref.scoreOfCost(pipe.getNewCostPerM()) + ref.scoreOfLength(1);
        Map<Integer, Double> best = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Step> inStep = new HashMap<>();
        java.util.Set<Integer> closed = new java.util.HashSet<>();
        java.util.PriorityQueue<double[]> pq = new java.util.PriorityQueue<>(
                (x, y) -> x[0] != y[0] ? Double.compare(x[0], y[0]) : Double.compare(x[2], y[2]));
        int start = hs - 1; // the first point, no heading yet
        best.put(start, 0.0);
        pq.add(new double[]{goal.distance(verts.get(0)) * minPerM, 0, start});
        org.locationtech.jts.index.strtree.STRtree index = new org.locationtech.jts.index.strtree.STRtree();
        for (int i = 0; i < n; i++) {
            index.insert(new Envelope(verts.get(i)), i);
        }
        index.build();
        Map<Object, Step> cache = new HashMap<>();
        double radius = frame == null ? params.neighbourRadiusM : params.frameNeighbourRadiusM;
        int expansions = 0;
        while (!pq.isEmpty()) {
            double[] it = pq.poll();
            int s = (int) it[2];
            if (closed.contains(s) || it[1] > best.getOrDefault(s, Double.POSITIVE_INFINITY) + 1e-12) {
                continue;
            }
            closed.add(s);
            int u = s / hs;
            if (u == 1) {
                List<Coordinate> way = new ArrayList<>();
                for (int c = s; parent.containsKey(c); c = parent.get(c)) {
                    List<Coordinate> pts = inStep.get(c).points;
                    for (int k = pts.size() - 1; k >= 0; k--) {
                        way.add(pts.get(k));
                    }
                }
                way.add(verts.get(0));
                Collections.reverse(way);
                for (int i = 1; i + 1 < way.size(); i++) {
                    if (!ref.isAllowedTurn(Planner.deflectionDeg(way.get(i - 1), way.get(i), way.get(i + 1)))) {
                        return null;
                    }
                }
                return way;
            }
            if (++expansions > params.maxExpansions) {
                return null;
            }
            int hin = s % hs;
            Coordinate cu = verts.get(u);
            boolean atStart = u == 0;
            Coordinate prev = frame == null && hin < n ? verts.get(hin) : null;
            List<Integer> near = neighbours(index, verts, cu, n, radius);
            for (int w : near) {
                if (w == u || w == 0) {
                    continue;
                }
                Coordinate cw = verts.get(w);
                List<Coordinate> mids = ways(frame, cu, cw);
                for (int m = 0; m < mids.size(); m++) {
                    Coordinate mid = mids.get(m);
                    if (shortLeg(cu, mid, cw) || cu.distance(mid == null ? cw : mid) < MIN_STRAIGHT_M) {
                        continue;
                    }
                    if (frame == null && mid != null) {
                        continue; // without directions every way is one leg
                    }
                    Coordinate next = mid == null ? cw : mid;
                    if (prev != null && !ref.isAllowedTurn(Planner.deflectionDeg(prev, cu, next))) {
                        continue; // without directions the heading is the previous point: no turn sharper than 90°
                    }
                    // the turn at cu is paid by the step (from the heading of arrival; without directions at every
                    // point but the first, as in the search of a route)
                    Step e = cachedStep(cache, ((long) u * n + w) << 3 | m, ctx, frame, cu, atStart ? ea : null,
                            frame == null || hin == hs - 1 ? Frame.FREE : hin, atStart, mid, cw, null, null);
                    if (e == null) {
                        continue;
                    }
                    int hout = frame == null ? u : e.heading;
                    int ws = w * hs + hout;
                    double g = it[1] + e.score;
                    if (closed.contains(ws) || g >= best.getOrDefault(ws, Double.POSITIVE_INFINITY)) {
                        continue;
                    }
                    best.put(ws, g);
                    parent.put(ws, s);
                    inStep.put(ws, e);
                    pq.add(new double[]{g + cw.distance(goal) * minPerM, g, ws});
                }
            }
        }
        return null;
    }

    /**
     * A route start: the connection point outside its building, or an exit from the building — right at the wall or
     * at the end of the straight leg out of it.
     */
    static final class Start {
        final Coordinate c;
        /** Heading of the way out of the building along the directions, {@link Frame#FREE} if it is off them. */
        final int heading;
        /** The exit this start leaves the building by; null when the connection point is outside it. */
        final BuildingEntry.Exit exit;
        /** The straight leg from the exit to {@link #c}; null when the route starts right at the exit. */
        final RoutingContext.EdgeEval leg;
        /** Order of preference of the starts, in score units: a start is used only when the preferred ones fail. */
        final double preference;

        Start(Coordinate c, int heading, BuildingEntry.Exit exit, RoutingContext.EdgeEval leg, double preference) {
            this.c = c;
            this.heading = heading;
            this.exit = exit;
            this.leg = leg;
            this.preference = preference;
        }
    }

    /**
     * Score that orders the starts: far above any route, so the search leaves by a less preferred start (a farther
     * wall, no leg) only when it finds no route from the preferred one. It is taken off the route score again.
     */
    private static final double PREFERENCE = 1e4;

    /**
     * Where the route may start: the connection point outside its building, or for each allowed wall, nearest first,
     * the end of the straight piece out of the building along the normal of the wall. The piece crosses the whole
     * clearance to the own building (appendix 2.2), the route turns only beyond it. A wall whose straight piece runs
     * into a neighbour or a restriction gives no start.
     */
    private List<Start> starts(InputModel.Oks oks, List<BuildingEntry.Exit> walls, Coordinate p, Frame frame,
                               RoutingContext ctx, boolean[] intoOwnBuilding) {
        List<Start> out = new ArrayList<>();
        if (walls.isEmpty()) {
            out.add(new Start(p, Frame.FREE, null, null, 0));
            return out;
        }
        PipeSpec pipe = ctx.getPipe();
        // a footprint of an earlier input format has no clearance: the route may turn right at its wall
        double zone = oks.ownBuildingId == null ? 0 : ref.buildingDistance(pipe.getDn()) + pipe.getHalfWidthM()
                + params.extraClearanceM + params.simplifyToleranceM + params.vertexOffsetM;
        double min = Math.max(params.entryLegM, zone);
        int rank = 0;
        for (BuildingEntry.Exit x : walls) {
            int heading = frame == null ? Frame.FREE : frame.heading(x.nx, x.ny);
            if (heading < 0) {
                heading = Frame.FREE;
            }
            RoutingContext.Endpoint ex = ctx.endpoint(x.c, ctx.specialsAround(x.c, 0.0));
            // appendix 2.2: the clearance to the own building does not hold on the straight piece out of it, the
            // building itself (another part or wing of it) may not be crossed
            ex.clearanceExcused = oks.ownBuildingId;
            if (min <= 0) {
                out.add(new Start(new Coordinate(x.c), heading, x, null, PREFERENCE * rank++));
                continue;
            }
            Start leg = null;
            for (double len = min; leg == null && len <= min + MAX_LEG_EXTRA_M + 1e-9; len += LEG_STEP_M) {
                Coordinate q = x.out(len);
                // the route goes on from the end of the leg as from any turning point: outside every clearance
                if (!ctx.endpoint(q, null).escapedZones.isEmpty() || !ctx.specialsAround(q, 0.0).isEmpty()) {
                    continue;
                }
                RoutingContext.EdgeEval e = ctx.evaluate(x.c, q, ex, null);
                if (e.valid) {
                    leg = new Start(q, heading, x, e, PREFERENCE * rank++);
                } else if (e.reason() != null && e.reason().startsWith("crosses ")
                        && e.reason().endsWith(" " + oks.ownBuildingId)) {
                    intoOwnBuilding[0] = true;
                }
            }
            if (leg != null) {
                out.add(leg);
            } else if (zone == 0) {
                out.add(new Start(new Coordinate(x.c), heading, x, null, PREFERENCE * rank++));
            }
        }
        return out;
    }

    /**
     * Whether a route of the new network runs through the way out of the building of an OKS: within the clearance kept
     * between new routes of the straight piece from an allowed wall (as long as {@link #starts} may make it). Such a
     * route can be what leaves the OKS without a start ({@code ENTRY_BLOCKED}).
     */
    boolean blocksEntry(InputModel.Oks oks, PipeSpec pipe, List<Coordinate> route, PipeSpec routePipe) {
        if (oks.ownBuildingId == null) {
            return false;
        }
        double zone = ref.buildingDistance(pipe.getDn()) + pipe.getHalfWidthM() + params.extraClearanceM
                + params.simplifyToleranceM + params.vertexOffsetM;
        double legMax = Math.max(params.entryLegM, zone) + MAX_LEG_EXTRA_M;
        double apart = routePipe.getHalfWidthM() + params.newRouteClearanceM + pipe.getHalfWidthM()
                + params.simplifyToleranceM;
        org.locationtech.jts.geom.LineString line = Crs.UTM_FACTORY.createLineString(route.toArray(new Coordinate[0]));
        for (BuildingEntry.Exit x : entryWalls(oks)) {
            org.locationtech.jts.geom.LineString leg = Crs.UTM_FACTORY.createLineString(
                    new Coordinate[]{x.c, x.out(legMax)});
            if (line.isWithinDistance(leg, apart)) {
                return true;
            }
        }
        return false;
    }

    /** The straight piece out of the building is made longer by this step when its end is still in a clearance. */
    private static final double LEG_STEP_M = 0.5;
    /** At most this much longer than the clearance of the building, metres. */
    private static final double MAX_LEG_EXTRA_M = 10;

    /**
     * Score of the turn from the way out of the building to the first leg of a start off the directions:
     * nothing straight on, the turn penalty for a turn up to 90°, -1 for a sharper one.
     */
    private double startTurn(Start s, Coordinate a, Coordinate b) {
        if (s.exit == null || s.heading != Frame.FREE) {
            return 0; // along the directions the turns are checked by the heading of the start
        }
        double vx = b.x - a.x;
        double vy = b.y - a.y;
        double l = Math.hypot(vx, vy);
        if (l < 1e-9) {
            return 0;
        }
        double defl = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (vx * s.exit.nx + vy * s.exit.ny) / l))));
        if (!ref.isAllowedTurn(defl)) {
            return -1;
        }
        return defl <= ref.getStraightDeg() ? 0 : params.turnPenaltyScore;
    }

    /** A step from a start with the turn from the way out of the building; null if that turn is not allowed. */
    private Step fromStart(Step e, Start s, Coordinate a, Coordinate firstEnd) {
        if (e == null) {
            return null;
        }
        double turn = startTurn(s, a, firstEnd);
        return turn < 0 ? null : turn == 0 ? e : new Step(e.points, e.evals, e.score + turn, e.heading);
    }

    /** One step of the search from a turning point to the next point: one straight leg or two. */
    private static final class Step {
        /** Points after the start of the step, in the direction of the search (towards the network). */
        final List<Coordinate> points;
        final List<RoutingContext.EdgeEval> evals;
        final double score;
        /** Heading of the last leg ({@link Frame#FREE} without directions). */
        final int heading;

        Step(List<Coordinate> points, List<RoutingContext.EdgeEval> evals, double score, int heading) {
            this.points = points;
            this.evals = evals;
            this.score = score;
            this.heading = heading;
        }
    }

    /** A step queued with its lower bound, not evaluated yet: the turning point of its way (null — one leg). */
    private static final class Pending {
        final Coordinate mid;
        final int variant;

        Pending(Coordinate mid, int variant) {
            this.mid = mid;
            this.variant = variant;
        }
    }

    /** A way whose legs were found not allowed (cached like the allowed ones). */
    private static final Step BLOCKED = new Step(Collections.<Coordinate>emptyList(),
            Collections.<RoutingContext.EdgeEval>emptyList(), 0, Frame.FREE);

    /**
     * Directions of the network piece nearest to the connection point: of the new network of the variant or of the
     * existing network fed from the source, whichever is nearer. Null when there is none.
     */
    private Frame networkFrame(Coordinate p, AttachProvider attach) {
        double best = Double.POSITIVE_INFINITY;
        double[] dir = null;
        if (attach != null) {
            double[] d = attach.nearestPiece(p);
            if (d != null) {
                best = d[2];
                dir = d;
            }
        }
        double dn = model.distanceToNetwork(p);
        if (Double.isFinite(dn) && dn < best) {
            Envelope env = new Envelope(p);
            env.expandBy(dn + 1);
            for (InputModel.Segment s : model.segmentsNear(env)) {
                if (!model.isAttached(s.id)) {
                    continue;
                }
                Coordinate[] cs = s.line.getCoordinates();
                for (int i = 0; i + 1 < cs.length; i++) {
                    double d = org.locationtech.jts.algorithm.Distance.pointToSegment(p, cs[i], cs[i + 1]);
                    if (d < best && cs[i].distance(cs[i + 1]) >= MIN_STRAIGHT_M) {
                        best = d;
                        dir = new double[]{cs[i + 1].x - cs[i].x, cs[i + 1].y - cs[i].y, d};
                    }
                }
            }
        }
        return dir == null ? null : Frame.squareTo(dir[0], dir[1], params.turnStepDeg);
    }

    /**
     * Score of joining a pipe at {@code tie} by a last leg coming from {@code from}. Where the pipe goes on both ways
     * (inside a piece, at a bend, at a junction): nothing for a T — every direction of the pipe at the point is at
     * 90° to the branch or opposite to it (inside a piece the branch is at 90°, at a bend it goes straight on from one
     * piece and at 90° to the other); otherwise two turns, as the branch makes an odd angle with both ways of the pipe.
     * At the end of a pipe the route goes on along it: a bend like any other — nothing straight on, one turn up to
     * 90°, two for a sharper one. With strict angles a joint that is not a T, or a bend off the standard angles, is not
     * allowed: -1.
     */
    double junctionScore(TieOption tie, Coordinate from, Frame frame) {
        if (tie.pipeDirs == null) {
            return 0;
        }
        double bx = from.x - tie.point.x;
        double by = from.y - tie.point.y;
        double bl = Math.hypot(bx, by);
        if (bl < 1e-9) {
            return 0;
        }
        double tol = ref.getStraightDeg();
        boolean strict = frame != null && params.turnStepStrict;
        if (tie.pipeDirs.length == 1) {
            double[] d = tie.pipeDirs[0];
            double bend = 180 - angleDeg(bx, by, d[0], d[1]);
            if (bend <= tol) {
                return 0;
            }
            if (strict) {
                double r = bend % frame.stepDeg;
                return Math.min(r, frame.stepDeg - r) <= tol && bend <= 90 + tol ? params.turnPenaltyScore : -1;
            }
            return bend <= 90 + tol ? params.turnPenaltyScore : params.oddJointTurns * params.turnPenaltyScore;
        }
        boolean t = true;
        for (double[] d : tie.pipeDirs) {
            if (Math.hypot(d[0], d[1]) < 1e-9) {
                continue;
            }
            double a = angleDeg(bx, by, d[0], d[1]);
            t &= Math.abs(a - 90) <= tol || a >= 180 - tol;
        }
        if (t) {
            return 0;
        }
        return strict ? -1 : params.oddJointTurns * params.turnPenaltyScore;
    }

    /** Angle between two directions, degrees (0 — the same way). */
    private static double angleDeg(double ux, double uy, double vx, double vy) {
        double cos = (ux * vx + uy * vy) / (Math.hypot(ux, uy) * Math.hypot(vx, vy));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /**
     * Where a goal of a search along {@code frame} may join at no cost (for the bound of the remaining cost): pieces of
     * pipes in the area that run along the directions (a T or straight on from the end of a pipe is possible only at
     * them) and existing chambers with a free branch (they take any angle), as {from, to}. Elsewhere a joint costs two
     * turns, at the end of an existing pipe one ({@code pipeEnds} gets the ends). Null without directions: then any
     * joint may be a T.
     */
    private List<Coordinate[]> freeJoints(Frame frame, RoutingContext ctx, AttachProvider attach, NetworkState state,
                                          List<Coordinate[]> pipeEnds) {
        if (frame == null) {
            return null;
        }
        List<Coordinate[]> out = new ArrayList<>();
        Envelope area = ctx.getArea().getEnvelopeInternal();
        Coordinate c = area.centre();
        double radius = Math.max(area.getWidth(), area.getHeight()) / 2;
        for (InputModel.Chamber ch : model.chambersNear(c, radius)) {
            if (state.freeBranches(ch) > 0 && model.isAttached(ch.id)) {
                Coordinate p = ch.point.getCoordinate();
                out.add(new Coordinate[]{p, p});
            }
        }
        for (InputModel.Segment s : model.segmentsNear(area)) {
            Coordinate[] cs = s.line.getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                if (alongFrame(frame, cs[i], cs[i + 1])) {
                    out.add(new Coordinate[]{cs[i], cs[i + 1]});
                }
            }
            pipeEnds.add(new Coordinate[]{cs[0], cs[0]});
            pipeEnds.add(new Coordinate[]{cs[cs.length - 1], cs[cs.length - 1]});
        }
        if (attach != null) {
            for (Coordinate[] pc : attach.pieces()) {
                if (alongFrame(frame, pc[0], pc[1])) {
                    out.add(pc);
                }
            }
        }
        return out;
    }

    /** Distance from a point to the nearest of the pieces. */
    private static double nearest(Coordinate v, List<Coordinate[]> pieces) {
        double best = Double.POSITIVE_INFINITY;
        for (Coordinate[] f : pieces) {
            best = Math.min(best, org.locationtech.jts.algorithm.Distance.pointToSegment(v, f[0], f[1]));
        }
        return best;
    }

    /** Whether a piece runs along one of the directions (within the straight tolerance). */
    private boolean alongFrame(Frame frame, Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        if (Math.hypot(dx, dy) < 1e-9) {
            return false;
        }
        double d = Math.toDegrees(Math.atan2(dy, dx) - frame.phi);
        double r = ((d % frame.stepDeg) + frame.stepDeg) % frame.stepDeg;
        return Math.min(r, frame.stepDeg - r) <= ref.getStraightDeg();
    }

    /** Directions of the search around the connection point: outlines of obstacles and of the network. */
    private Frame frameFor(RoutingContext ctx, Coordinate p) {
        List<Geometry> outlines = new ArrayList<>();
        for (RoutingContext.Zone z : ctx.getZones()) {
            outlines.add(z.raw);
        }
        Coordinate nearest = null;
        double best = Double.POSITIVE_INFINITY;
        Envelope env = new Envelope(p);
        env.expandBy(Frame.RADIUS_M);
        for (InputModel.Segment s : model.segmentsNear(env)) {
            outlines.add(s.line);
        }
        if (Double.isFinite(model.distanceToNetwork(p))) {
            Envelope wide = new Envelope(p);
            wide.expandBy(model.distanceToNetwork(p) + 1);
            for (InputModel.Segment s : model.segmentsNear(wide)) {
                Coordinate q = new LengthIndexedLine(s.line).extractPoint(new LengthIndexedLine(s.line).project(p));
                if (q.distance(p) < best) {
                    best = q.distance(p);
                    nearest = q;
                }
            }
        }
        return Frame.around(p, outlines, nearest, params.turnStepDeg);
    }

    private Route search(InputModel.Oks oks, List<BuildingEntry.Exit> walls, PipeSpec pipe, RoutingContext ctx,
                         NetworkState state, AttachProvider attach, Diagnostics diag, Unconnected.Failure fail,
                         double slack, SearchTrace trace, Frame frame, Ties ties, double bound) {
        Coordinate p = oks.routePoint.getCoordinate();
        // where the route may leave the building, in the order of preference; the piece inside the building and the
        // leg out of it are paid for by the starting cost
        boolean[] intoOwnBuilding = {false};
        List<Start> starts = params.rules.entry.alongNormal() ? starts(oks, walls, p, frame, ctx, intoOwnBuilding)
                : outlineStarts(oks, p, frame);
        if (starts.isEmpty()) {
            fail.set(Unconnected.Reason.ENTRY_BLOCKED, (walls.size() == 1 ? "с ближайшей стены ("
                    + metres(walls.get(0).insideM) + " м внутри здания)" : "ни с одной из " + walls.size()
                    + " ближайших стен") + " не выйти: прямой участок через зону отступа к зданию упирается "
                    + (intoOwnBuilding[0] ? "в другую часть этого же здания" : "в соседнее здание или ограничение"));
            return null;
        }
        double[] startCost = new double[starts.size()];
        List<Coordinate> verts = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            Start s = starts.get(i);
            double inside = s.exit != null ? s.exit.insideM
                    : params.rules.entry.alongNormal() ? 0 : s.c.distance(oks.connectionPoint.getCoordinate());
            startCost[i] = s.preference + ref.scoreOfCost(pipe.getNewCostPerM() * inside) + ref.scoreOfLength(inside)
                    + (s.leg == null ? 0 : s.leg.score);
            verts.add(s.c);
        }
        int nStarts = starts.size();
        List<Coordinate> corners = ctx.buildVertices();
        // keep the turning points through which the route can stay reasonably short
        double straight = model.distanceToNetwork(p);
        if (attach != null) {
            straight = Math.min(straight, attach.distance(p));
        }
        double budget = straight * (1 + slack) + params.corridorMarginM;
        List<Coordinate> inCorridor = new ArrayList<>(corners.size());
        for (Coordinate c : corners) {
            double toNetwork = model.distanceToNetwork(c);
            if (attach != null) {
                toNetwork = Math.min(toNetwork, attach.distance(c));
            }
            if (c.distance(p) + toNetwork <= budget) {
                inCorridor.add(c);
            }
        }
        corners = inCorridor;
        if (corners.size() > params.maxVertices) {
            corners.sort((a, b) -> Double.compare(a.distance(p), b.distance(p)));
            corners = new ArrayList<>(corners.subList(0, params.maxVertices));
            diag.warning("route.vertices_truncated", oks.id, "Routing graph truncated to "
                    + params.maxVertices + " vertices");
        }
        verts.addAll(corners);
        int n = verts.size();
        // the clearance may be broken only around the route end, the exit from the building: not at the end of a leg
        RoutingContext.Endpoint[] startEps = new RoutingContext.Endpoint[nStarts];
        for (int i = 0; i < nStarts; i++) {
            startEps[i] = starts.get(i).leg != null ? null
                    : ctx.endpoint(verts.get(i), ctx.specialsAround(verts.get(i), 0.0));
        }
        double goalRadius = ctx.getArea().getEnvelopeInternal().getWidth() / 2 - 1;
        if (trace != null) {
            trace.vertices.addAll(verts);
            trace.starts = nStarts;
            for (RoutingContext.Zone z : ctx.getZones()) {
                trace.zones.add(z.block);
                trace.zoneKinds.add(z.kind);
            }
            for (RoutingContext.Special sp : ctx.getSpecials()) {
                trace.corridors.add(sp.corridor);
                trace.corridorKinds.add(sp.type);
            }
        }
        // goals reached with a valid last edge: the alternatives of the chosen tie-in for the journal
        Map<String, Route.Alternative> reached = params.journal ? new LinkedHashMap<>() : null;

        double minPerM = ref.scoreOfCost(pipe.getNewCostPerM()) + ref.scoreOfLength(1);
        // Admissible bound of the remaining cost: straight pipe to the network plus the cheapest possible goal.
        // Joining the new network costs at least a chamber; a tie-in costs at least the tie-in itself.
        double goalMin = attach == null ? ref.scoreOfCost(ref.getTieInCost())
                : ref.scoreOfCost(Math.min(ref.getTieInCost(), ref.chamberCost(pipe.getDn())));
        // the joint with a pipe is part of a goal: only at a pipe along the directions or an existing chamber can it
        // cost nothing, any other goal costs at least one turn
        List<Coordinate[]> pipeEnds = new ArrayList<>();
        List<Coordinate[]> freeJoints = freeJoints(frame, ctx, attach, state, pipeEnds);
        double[] h = new double[n];
        for (int i = 0; i < n; i++) {
            Coordinate v = verts.get(i);
            double d = model.distanceToNetwork(v);
            if (attach != null) {
                d = Math.min(d, attach.distance(v));
            }
            double rest = d * minPerM;
            if (freeJoints != null) {
                rest = Math.min(rest + params.oddJointTurns * params.turnPenaltyScore,
                        Math.min(nearest(v, pipeEnds) * minPerM + params.turnPenaltyScore,
                                nearest(v, freeJoints) * minPerM));
            }
            h[i] = rest + goalMin;
        }
        // index of the turning points: a step of the search looks only at the neighbours of the current point
        org.locationtech.jts.index.strtree.STRtree vertexIndex = new org.locationtech.jts.index.strtree.STRtree();
        for (int i = 0; i < n; i++) {
            vertexIndex.insert(new org.locationtech.jts.geom.Envelope(verts.get(i)), i);
        }
        vertexIndex.build();
        // a state is a turning point with the heading the route arrives with
        int hs = frame == null ? 1 : frame.headings + 1;
        int states = n * hs;
        double[] best = new double[states];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        double[] g = new double[states];
        int[] parent = new int[states];
        Arrays.fill(parent, -1);
        Step[] inStep = new Step[states];
        boolean[] closed = new boolean[states];
        ItemHeap pq = heap.get();
        pq.reset();
        for (int i = 0; i < nStarts; i++) {
            int st = i * hs + (frame == null ? 0 : slot(frame, starts.get(i).heading));
            best[st] = startCost[i];
            pq.push(startCost[i] + h[i], st, -1, null, null, startCost[i]);
        }
        Map<String, Double> chainMemo = new HashMap<>();
        // ways between points do not depend on the heading the route arrives with: evaluated once
        Map<Object, Step> wayCache = new HashMap<>();
        List<List<TieOption>> goalsOf = new ArrayList<>(Collections.<List<TieOption>>nCopies(n, null));
        Map<GoalKey, RoutingContext.Endpoint> goalEnds = new HashMap<>();
        Moves[] movesOf = new Moves[n];
        // the neighbours of a point are the same for every heading it is reached with
        List<List<Integer>> neighboursOf = new ArrayList<>(Collections.<List<Integer>>nCopies(n, null));
        // routes not cheaper than the bound are not looked for
        double bestGoal = bound;
        int expansions = 0;
        while (!pq.isEmpty()) {
            int it = pq.pop();
            double f = pq.key[it];
            int from = pq.from[it];
            TieOption itTie = (TieOption) pq.tie[it];
            Object payload = pq.edge[it];
            boolean pending = payload instanceof Pending;
            if (f >= bestGoal && pending) {
                continue;
            }
            if (itTie != null) {
                if (!pending) {
                    Route r = buildRoute(oks, pipe, verts, hs, parent, inStep, itTie, from, (Step) payload,
                            pq.g[it], ctx, startEps, starts, frame, ties);
                    if (r == null) {
                        // the route has a turn sharper than 90° that straightening could not remove: this goal
                        // is not usable, the search goes on
                        continue;
                    }
                    if (reached != null) {
                        for (Route.Alternative a : alternatives(reached, pq, itTie)) {
                            // without the preference of the start it was reached from (scores stay far below it)
                            r.alternatives.add(new Route.Alternative(a.tie, a.score % PREFERENCE, a.estimate));
                        }
                    }
                    if (trace != null) {
                        trace.expansions.add(new int[]{-1, from / hs});
                    }
                    return r;
                }
                // the end at a goal depends on the goal only; its effect on ties is the same every time
                GoalKey gk = new GoalKey(itTie);
                RoutingContext.Endpoint et;
                if (goalEnds.containsKey(gk)) {
                    et = goalEnds.get(gk);
                } else {
                    et = goalEndpoint(ctx, itTie, ties);
                    goalEnds.put(gk, et);
                }
                if (et == null) {
                    continue;
                }
                int fv = from / hs;
                Pending pd = (Pending) payload;
                Step e = cachedStep(wayCache, "g" + fv + ":" + pd.variant + ":" + itTie.key(), ctx, frame,
                        verts.get(fv), fv < nStarts ? startEps[fv] : null, heading(frame, from, hs), fv < nStarts,
                        pd.mid, itTie.point, et, trace);
                if (fv < nStarts && parent[from] < 0) {
                    e = fromStart(e, starts.get(fv), verts.get(fv), pd.mid == null ? itTie.point : pd.mid);
                }
                if (e != null) {
                    // the goal was queued only with an allowed joint (its score is not negative)
                    double total = g[from] + e.score + itTie.terminalScore
                            + junctionScore(itTie, pd.mid == null ? verts.get(fv) : pd.mid, frame);
                    if (reached != null) {
                        Route.Alternative prev = reached.get(itTie.key());
                        if (prev == null || total < prev.score) {
                            reached.put(itTie.key(), new Route.Alternative(itTie, total, false));
                        }
                    }
                    if (total < bestGoal) {
                        bestGoal = total;
                        pq.push(total, -1, from, itTie, e, total);
                    }
                }
                continue;
            }
            int u = pq.node[it];
            if (closed[u]) {
                continue;
            }
            if (pending) {
                int fv = from / hs;
                Pending pd = (Pending) payload;
                Step e = cachedStep(wayCache, (((long) fv * n + u / hs) << 1 | pd.variant), ctx, frame, verts.get(fv),
                        fv < nStarts ? startEps[fv] : null, heading(frame, from, hs), fv < nStarts, pd.mid,
                        verts.get(u / hs), null, trace);
                if (fv < nStarts && parent[from] < 0) {
                    e = fromStart(e, starts.get(fv), verts.get(fv), pd.mid == null ? verts.get(u / hs) : pd.mid);
                }
                if (e == null) {
                    continue;
                }
                double gt = g[from] + e.score;
                if (gt < best[u]) {
                    best[u] = gt;
                    pq.push(gt + h[u / hs], u, from, null, e, gt);
                }
                continue;
            }
            if (pq.g[it] > best[u] + 1e-12) {
                continue;
            }
            closed[u] = true;
            g[u] = pq.g[it];
            parent[u] = from;
            inStep[u] = (Step) payload;
            int uv = u / hs;
            if (trace != null) {
                trace.expansions.add(new int[]{uv, from < 0 ? -1 : from / hs});
            }
            if (++expansions > params.maxExpansions) {
                if (trace != null) {
                    trace.limitReached = true;
                }
                diag.warning("route.search_limit", oks.id, "Search stopped after " + params.maxExpansions
                        + " expansions");
                fail.set(Unconnected.Reason.SEARCH_LIMIT, params.maxExpansions + " раскрытий вершин");
                return null;
            }
            if ((expansions & 255) == 0 && Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Planning cancelled");
            }
            Coordinate cu = verts.get(uv);
            int hin = heading(frame, u, hs);
            boolean atStart = uv < nStarts;
            // the first leg of a route turns from the way out of the building
            Start start = atStart && from < 0 ? starts.get(uv) : null;
            List<TieOption> goals = goalsOf.get(uv);
            if (goals == null) {
                goals = new ArrayList<>(tieOptions(cu, pipe, oks.flowTph, state, chainMemo, frame, ctx, ties));
                if (attach != null) {
                    goals.addAll(attach.attachOptions(cu, pipe, oks.flowTph));
                }
                goalsOf.set(uv, goals);
            }
            for (TieOption tie : goals) {
                if (tie.point.distance(p) > goalRadius) {
                    continue; // obstacles are loaded only around the search area
                }
                List<Coordinate> mids = ways(frame, cu, tie.point);
                for (int m = 0; m < mids.size(); m++) {
                    Coordinate mid = mids.get(m);
                    double turn = turnScore(frame, hin, firstHeading(frame, cu, mid, tie.point), atStart);
                    if (turn < 0 || shortLeg(cu, mid, tie.point)) {
                        continue;
                    }
                    if (start != null) {
                        double out = startTurn(start, cu, mid == null ? tie.point : mid);
                        if (out < 0) {
                            continue;
                        }
                        turn += out;
                    }
                    double joint = junctionScore(tie, mid == null ? cu : mid, frame);
                    if (joint < 0) {
                        continue;
                    }
                    double lower = g[u] + turn + wayLength(cu, mid, tie.point) * minPerM + tie.terminalScore + joint;
                    if (lower < bestGoal) {
                        pq.push(lower, -1, u, tie, new Pending(mid, m), lower);
                    }
                }
            }
            List<Integer> near = neighboursOf.get(uv);
            if (near == null) {
                near = neighbours(vertexIndex, verts, cu, n,
                        frame == null ? params.neighbourRadiusM : params.frameNeighbourRadiusM);
                neighboursOf.set(uv, near);
            }
            if (frame != null) {
                // with directions a point is expanded once per heading: its moves are laid out once
                Moves mv = movesOf[uv];
                if (mv == null) {
                    mv = moves(frame, verts, uv, near);
                    movesOf[uv] = mv;
                }
                for (int k = 0; k < mv.size; k++) {
                    Coordinate mid = mv.mid[k];
                    double turn = turnScore(frame, hin, mv.first[k], atStart);
                    if (turn < 0) {
                        continue;
                    }
                    int w = mv.w[k];
                    if (start != null) {
                        double out = startTurn(start, cu, mid == null ? verts.get(w) : mid);
                        if (out < 0) {
                            continue;
                        }
                        turn += out;
                    }
                    int ws = w * hs + mv.last[k];
                    double lower = g[u] + turn + (mid == null ? 0 : params.turnPenaltyScore) + mv.length[k] * minPerM;
                    if (closed[ws] || lower >= best[ws] || lower + h[w] >= bestGoal) {
                        continue;
                    }
                    pq.push(lower + h[w], ws, u, null, new Pending(mid, mv.variant[k]), lower);
                }
                continue;
            }
            for (int w : near) {
                if (w == uv) {
                    continue;
                }
                Coordinate cw = verts.get(w);
                List<Coordinate> mids = ways(frame, cu, cw);
                for (int m = 0; m < mids.size(); m++) {
                    Coordinate mid = mids.get(m);
                    double turn = turnScore(frame, hin, firstHeading(frame, cu, mid, cw), atStart);
                    if (turn < 0 || shortLeg(cu, mid, cw)) {
                        continue;
                    }
                    if (start != null) {
                        double out = startTurn(start, cu, mid == null ? cw : mid);
                        if (out < 0) {
                            continue;
                        }
                        turn += out;
                    }
                    int ws = w * hs + (frame == null ? 0 : lastHeading(frame, cu, mid, cw));
                    double lower = g[u] + turn + (mid == null ? 0 : params.turnPenaltyScore)
                            + wayLength(cu, mid, cw) * minPerM;
                    if (closed[ws] || lower >= best[ws] || lower + h[w] >= bestGoal) {
                        continue;
                    }
                    pq.push(lower + h[w], ws, u, null, new Pending(mid, m), lower);
                }
            }
        }
        return null;
    }

    /**
     * Moves from a point to its neighbours along the directions, in the order the search looks at them: the
     * neighbour, the turning point of the way (null — one leg) and its number among the ways, the first and the last
     * heading, the length. Ways with a too short leg are left out (the search never takes them).
     */
    private static final class Moves {
        int size;
        int[] w;
        Coordinate[] mid;
        int[] variant;
        int[] first;
        int[] last;
        double[] length;

        Moves(int capacity) {
            w = new int[capacity];
            mid = new Coordinate[capacity];
            variant = new int[capacity];
            first = new int[capacity];
            last = new int[capacity];
            length = new double[capacity];
        }

        void add(int to, Coordinate m, int v, int fh, int lh, double len) {
            if (size == w.length) {
                int c = Math.max(4, size * 2);
                w = Arrays.copyOf(w, c);
                mid = Arrays.copyOf(mid, c);
                variant = Arrays.copyOf(variant, c);
                first = Arrays.copyOf(first, c);
                last = Arrays.copyOf(last, c);
                length = Arrays.copyOf(length, c);
            }
            w[size] = to;
            mid[size] = m;
            variant[size] = v;
            first[size] = fh;
            last[size] = lh;
            length[size] = len;
            size++;
        }
    }

    private Moves moves(Frame frame, List<Coordinate> verts, int uv, List<Integer> near) {
        Coordinate cu = verts.get(uv);
        Moves mv = new Moves(near.size() * 2);
        for (int w : near) {
            if (w == uv) {
                continue;
            }
            Coordinate cw = verts.get(w);
            List<Coordinate> mids = ways(frame, cu, cw);
            for (int m = 0; m < mids.size(); m++) {
                Coordinate mid = mids.get(m);
                if (shortLeg(cu, mid, cw)) {
                    continue;
                }
                mv.add(w, mid, m, firstHeading(frame, cu, mid, cw), lastHeading(frame, cu, mid, cw),
                        wayLength(cu, mid, cw));
            }
        }
        return mv;
    }

    /** Heading of arrival of a search state; the last slot of a point is the start without a direction. */
    private static int heading(Frame frame, int state, int hs) {
        int s = state % hs;
        return frame == null || s == hs - 1 ? Frame.FREE : s;
    }

    /** Slot of a heading among the states of a point. */
    private static int slot(Frame frame, int heading) {
        return heading == Frame.FREE ? frame.headings : heading;
    }

    private static List<Coordinate> ways(Frame frame, Coordinate a, Coordinate b) {
        return frame == null ? Collections.<Coordinate>singletonList(null) : frame.mids(a, b);
    }

    private static double wayLength(Coordinate a, Coordinate mid, Coordinate b) {
        return mid == null ? a.distance(b) : a.distance(mid) + mid.distance(b);
    }

    private boolean shortLeg(Coordinate a, Coordinate mid, Coordinate b) {
        return mid != null && (a.distance(mid) < params.minLegM || mid.distance(b) < params.minLegM);
    }

    private static int firstHeading(Frame frame, Coordinate a, Coordinate mid, Coordinate b) {
        if (frame == null) {
            return Frame.FREE;
        }
        Coordinate end = mid == null ? b : mid;
        return frame.heading(end.x - a.x, end.y - a.y);
    }

    private static int lastHeading(Frame frame, Coordinate a, Coordinate mid, Coordinate b) {
        Coordinate start = mid == null ? a : mid;
        return frame.heading(b.x - start.x, b.y - start.y);
    }

    /**
     * Score of turning at a point from the heading the route arrived with to the next one: nothing straight on or
     * at a start without a direction, the turn penalty for a turn up to 90°, -1 for a sharper turn (not allowed along
     * the directions). Without directions every turning point except a start pays the penalty.
     */
    private double turnScore(Frame frame, int hin, int hout, boolean atStart) {
        if (frame == null) {
            return atStart ? 0 : params.turnPenaltyScore;
        }
        if (hin == Frame.FREE) {
            return 0;
        }
        return hin == hout ? 0 : frame.allowedTurn(hin, hout) ? params.turnPenaltyScore : -1;
    }

    /** {@link #step} with the legs evaluated once per way (the turn at its start is added every time). */
    private Step cachedStep(Map<Object, Step> cache, Object key, RoutingContext ctx, Frame frame, Coordinate a,
                            RoutingContext.Endpoint ea, int hin, boolean atStart, Coordinate mid, Coordinate b,
                            RoutingContext.Endpoint eb, SearchTrace trace) {
        double turn = turnScore(frame, hin, firstHeading(frame, a, mid, b), atStart);
        if (turn < 0) {
            return null;
        }
        Step legs = cache.get(key);
        if (legs == null) {
            // Frame.FREE as the arriving heading: no turn at the start, only the legs
            legs = step(ctx, frame, a, ea, Frame.FREE, true, mid, b, eb, trace);
            if (legs == null) {
                legs = BLOCKED;
            }
            cache.put(key, legs);
        }
        if (legs == BLOCKED) {
            return null;
        }
        return new Step(legs.points, legs.evals, legs.score + turn, legs.heading);
    }

    /** Evaluates a way from a to b through {@code mid} (null — one leg); null if a leg is not allowed. */
    private Step step(RoutingContext ctx, Frame frame, Coordinate a, RoutingContext.Endpoint ea, int hin,
                      boolean atStart, Coordinate mid, Coordinate b, RoutingContext.Endpoint eb, SearchTrace trace) {
        double turn = turnScore(frame, hin, firstHeading(frame, a, mid, b), atStart);
        if (turn < 0) {
            return null;
        }
        Coordinate end1 = mid == null ? b : mid;
        work++;
        RoutingContext.EdgeEval e1 = ctx.evaluate(a, end1, ea, mid == null ? eb : null);
        if (!e1.valid) {
            if (trace != null) {
                trace.reject(a, end1, e1.reason());
            }
            return null;
        }
        int last = frame == null ? Frame.FREE : lastHeading(frame, a, mid, b);
        if (mid == null) {
            return new Step(Collections.singletonList(b), Collections.singletonList(e1), turn + e1.score, last);
        }
        work++;
        RoutingContext.EdgeEval e2 = ctx.evaluate(mid, b, null, eb);
        if (!e2.valid) {
            if (trace != null) {
                trace.reject(mid, b, e2.reason());
            }
            return null;
        }
        return new Step(Arrays.asList(mid, b), Arrays.asList(e1, e2),
                turn + params.turnPenaltyScore + e1.score + e2.score, last);
    }

    /**
     * Places the search could connect to, cheapest first, for the journal: goals it reached with a valid last edge
     * (exact score of the best route to them) and goals it only queued (lower bound). The chosen goal is included.
     */
    private static List<Route.Alternative> alternatives(Map<String, Route.Alternative> reached, ItemHeap pq,
                                                        TieOption chosen) {
        Map<String, Route.Alternative> all = new LinkedHashMap<>(reached);
        Route.Alternative c = reached.get(chosen.key());
        double chosenScore = c == null ? 0 : c.score;
        for (int i = 0; i < pq.items(); i++) {
            TieOption t = (TieOption) pq.tie[i];
            // queued goals below the chosen score were already tried: their last edge was not allowed
            if (t == null || pq.edge[i] != null || pq.key[i] < chosenScore) {
                continue;
            }
            Route.Alternative prev = all.get(t.key());
            if (prev == null || (prev.estimate && pq.key[i] < prev.score)) {
                all.put(t.key(), new Route.Alternative(t, pq.key[i], true));
            }
        }
        List<Route.Alternative> list = new ArrayList<>(all.values());
        list.sort((a, b) -> Double.compare(a.score, b.score));
        List<Route.Alternative> out = new ArrayList<>(list.subList(0, Math.min(MAX_ALTERNATIVES, list.size())));
        boolean hasChosen = false;
        for (Route.Alternative a : out) {
            hasChosen |= a.tie.key().equals(chosen.key());
        }
        if (!hasChosen && all.containsKey(chosen.key())) {
            out.set(out.size() - 1, all.get(chosen.key()));
        }
        return out;
    }

    /** Whether the polyline stays simple (does not cross itself) after the turning point {@code i} is removed. */
    private static boolean simpleWithout(List<Coordinate> pts, int i) {
        List<Coordinate> without = new ArrayList<>(pts.size() - 1);
        for (int k = 0; k < pts.size(); k++) {
            if (k != i) {
                without.add(pts.get(k));
            }
        }
        return without.size() < 3
                || Crs.UTM_FACTORY.createLineString(without.toArray(new Coordinate[0])).isSimple();
    }

    private final ThreadLocal<ItemHeap> heap = ThreadLocal.withInitial(ItemHeap::new);

    /** Turning points that may follow the current one: those within the radius, at least {@code neighboursMin}. */
    @SuppressWarnings("unchecked")
    private List<Integer> neighbours(org.locationtech.jts.index.strtree.STRtree index, List<Coordinate> verts,
                                     Coordinate c, int n, double radius) {
        org.locationtech.jts.geom.Envelope env = new org.locationtech.jts.geom.Envelope(c);
        env.expandBy(radius);
        List<Integer> near = index.query(env);
        if (near.size() >= Math.min(params.neighboursMin, n) || n <= params.neighboursMin) {
            return near;
        }
        // an empty neighbourhood: take the nearest points of the whole graph, so the search never gets stuck
        List<Integer> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        all.sort(java.util.Comparator.comparingDouble(i -> verts.get(i).distance(c)));
        return all.subList(0, Math.min(params.neighboursMin, n));
    }

    /**
     * Points where the route may leave the building of the OKS with the entry from any point of the outline
     * ({@link RuleOptions.Entry#OUTLINE}): the connection point itself when it is outside, otherwise points of the
     * outline from which a straight piece inside the building reaches the connection point. Which one is used is
     * decided by the search: the nearest exit is often walled in by neighbours. With directions, the exits lie on the
     * rays from the connection point along the directions, so the bend where the route leaves the building is a
     * standard one too.
     */
    List<Start> outlineStarts(InputModel.Oks oks, Coordinate routePoint, Frame frame) {
        List<Start> out = new ArrayList<>();
        if (!oks.connectionInside() || oks.polygon == null) {
            out.add(new Start(routePoint, Frame.FREE, null, null, 0));
            return out;
        }
        Coordinate cp = oks.connectionPoint.getCoordinate();
        org.locationtech.jts.geom.Geometry inner = oks.polygon.buffer(0.05);
        if (frame != null) {
            Geometry boundary = oks.polygon.getBoundary();
            Envelope env = oks.polygon.getEnvelopeInternal();
            double reach = Math.hypot(env.getWidth(), env.getHeight()) + 1;
            for (int k = 0; k < frame.headings; k++) {
                double[] u = frame.unit(k);
                LineString ray = Crs.UTM_FACTORY.createLineString(new Coordinate[]{cp,
                        new Coordinate(cp.x + u[0] * reach, cp.y + u[1] * reach)});
                Coordinate exit = null;
                for (Coordinate c : ray.intersection(boundary).getCoordinates()) {
                    if (c.distance(cp) > 1e-6 && (exit == null || c.distance(cp) < exit.distance(cp))) {
                        exit = c;
                    }
                }
                if (exit != null && inner.covers(Crs.UTM_FACTORY.createLineString(new Coordinate[]{exit, cp}))) {
                    out.add(new Start(exit, k, null, null, 0));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        out.add(new Start(routePoint, Frame.FREE, null, null, 0));
        List<Coordinate> outline = new ArrayList<>(Arrays.asList(oks.polygon.getBoundary().getCoordinates()));
        outline.sort(java.util.Comparator.comparingDouble(c -> c.distance(cp)));
        for (Coordinate c : outline) {
            if (out.size() >= params.entryPoints) {
                break;
            }
            boolean apart = true;
            for (Start other : out) {
                apart &= other.c.distance(c) >= params.entryPointSpacingM;
            }
            if (!apart) {
                continue;
            }
            // the piece inside the building must stay inside it
            if (inner.covers(Crs.UTM_FACTORY.createLineString(new Coordinate[]{c, cp}))) {
                out.add(new Start(c, Frame.FREE, null, null, 0));
            }
        }
        return out;
    }

    /**
     * Tie-in candidates near a vertex: the nearest point of every segment and, with directions, the point where
     * the direction closest to the segment meets it (reached by one straight leg). Unless tie-ins may lie within
     * clearances, a point within the clearance to an object is moved along the segment just beyond it, both ways.
     */
    private List<TieOption> tieOptions(Coordinate v, PipeSpec pipe, double flow, NetworkState state,
                                       Map<String, Double> chainMemo, Frame frame, RoutingContext ctx, Ties ties) {
        double dNear = model.distanceToNetwork(v);
        double reach = dNear + params.tieSearchSlackM;
        Envelope env = new Envelope(v);
        env.expandBy(reach);
        Map<String, TieOption> out = new LinkedHashMap<>();
        for (InputModel.Segment s : model.segmentsNear(env)) {
            if (!model.isAttached(s.id)) {
                continue; // part of the network not connected to the source
            }
            LengthIndexedLine lil = new LengthIndexedLine(s.line);
            double t = lil.project(v);
            Coordinate q = lil.extractPoint(t);
            if (q.distance(v) <= reach) {
                addTiePoints(out, s, lil, t, pipe, flow, state, chainMemo, ctx, ties);
            }
            if (frame != null) {
                double[] ray = alongDirection(frame, v, s.line);
                if (ray != null && ray[1] <= reach) {
                    Coordinate qr = lil.extractPoint(ray[0]);
                    if (qr.distance(q) > 0.5) {
                        addTiePoints(out, s, lil, ray[0], pipe, flow, state, chainMemo, ctx, ties);
                    }
                }
            }
        }
        for (InputModel.Chamber c : model.chambersNear(v, reach)) {
            if (state.freeBranches(c) > 0 && model.isAttached(c.id) && !out.containsKey("c:" + c.id)) {
                TieOption o = chamberOption(c, pipe, flow, state, chainMemo);
                out.put(o.key(), o);
            }
        }
        List<TieOption> res = new ArrayList<>();
        for (TieOption o : out.values()) {
            if (!bannedTieObjects.contains(o.existingObjectId())) {
                res.add(o);
            }
        }
        return res;
    }

    /** The tie-in at {@code t} of the segment, or the ones just beyond the clearance it lies within. */
    private void addTiePoints(Map<String, TieOption> out, InputModel.Segment s, LengthIndexedLine lil, double t,
                              PipeSpec pipe, double flow, NetworkState state, Map<String, Double> chainMemo,
                              RoutingContext ctx, Ties ties) {
        double[] at = ties.inClearance ? new double[]{t} : ctx.outsideObjectClearances(s, t);
        if (at.length != 1 || at[0] != t) {
            ties.closed = true; // the point lies within a clearance: moved out of it, or none is left on the segment
        }
        for (double a : at) {
            addTiePoint(out, s, a, lil.extractPoint(a), pipe, flow, state, chainMemo);
        }
    }

    private void addTiePoint(Map<String, TieOption> out, InputModel.Segment s, double t, Coordinate q, PipeSpec pipe,
                             double flow, NetworkState state, Map<String, Double> chainMemo) {
        if (state.tooCloseToSegmentTie(q)) {
            return; // another part of this variant already ties into the segment here
        }
        // an attachment within 10 m of chambers with a free branch goes into one of them; with several, the
        // search takes the cheapest (R-TIE-1, appendix 2.4, clarification 11)
        List<InputModel.Chamber> near = eligibleChambers(q, ref.getTieInChamberRadiusM(), state);
        if (near.isEmpty()) {
            TieOption o = segmentOption(s, t, q, pipe, flow, state, chainMemo);
            out.put(o.key(), o);
        }
        for (InputModel.Chamber c : near) {
            if (!out.containsKey("c:" + c.id)) {
                TieOption o = chamberOption(c, pipe, flow, state, chainMemo);
                out.put(o.key(), o);
            }
        }
    }

    /**
     * The nearest point of a line reached from v by one of the directions: {position along the line, distance},
     * or null.
     */
    private static double[] alongDirection(Frame frame, Coordinate v, LineString line) {
        double[] best = null;
        Coordinate[] cs = line.getCoordinates();
        for (int k = 0; k < frame.headings; k++) {
            double[] u = frame.unit(k);
            double acc = 0;
            for (int i = 0; i + 1 < cs.length; i++) {
                double ex = cs[i + 1].x - cs[i].x;
                double ey = cs[i + 1].y - cs[i].y;
                double el = Math.hypot(ex, ey);
                double denom = u[0] * ey - u[1] * ex;
                if (Math.abs(denom) > 1e-9 && el > 0) {
                    double px = cs[i].x - v.x;
                    double py = cs[i].y - v.y;
                    double d = (px * ey - py * ex) / denom;
                    double f = (px * u[1] - py * u[0]) / denom;
                    if (d > 1e-6 && f >= 0 && f <= 1 && (best == null || d < best[1])) {
                        best = new double[]{acc + f * el, d};
                    }
                }
                acc += el;
            }
        }
        return best;
    }

    private List<InputModel.Chamber> eligibleChambers(Coordinate q, double radius, NetworkState state) {
        List<InputModel.Chamber> out = new ArrayList<>();
        for (InputModel.Chamber c : model.chambersNear(q, radius)) {
            if (state.freeBranches(c) > 0 && model.isAttached(c.id)
                    && c.point.getCoordinate().distance(q) <= radius) {
                out.add(c);
            }
        }
        return out;
    }

    private TieOption segmentOption(InputModel.Segment s, double t, Coordinate q, PipeSpec pipe, double flow,
                                    NetworkState state, Map<String, Double> chainMemo) {
        // a new chamber right in the chosen point of the existing segment; its cost already includes the
        // attachment, no separate tie-in is charged (appendix 2.4, 3.2)
        int chamberDn = Math.max(pipe.getDn(), s.dn);
        double score = ref.scoreOfCost(ref.chamberCost(chamberDn));
        TieOption o = new TieOption(q, null, s, t, score);
        o.pipeDirs = pipeDirsAt(s, t, q);
        o.place = model.placeOf(s, q, PLACE_TOL_M);
        return o;
    }

    /**
     * Directions of the existing network at a point of a segment, away from it: of this segment and, at its end, of
     * the segments that go on from there.
     */
    private double[][] pipeDirsAt(InputModel.Segment s, double t, Coordinate q) {
        double[][] own = TieOption.dirsAt(java.util.Arrays.asList(s.line.getCoordinates()), t);
        if (own == null || own.length != 1) {
            return own;
        }
        List<double[]> out = new ArrayList<>(Arrays.asList(own));
        Envelope env = new Envelope(q);
        env.expandBy(0.01);
        for (InputModel.Segment o : model.segmentsNear(env)) {
            if (o == s || !model.isAttached(o.id)) {
                continue;
            }
            LengthIndexedLine lil = new LengthIndexedLine(o.line);
            double to = lil.project(q);
            if (lil.extractPoint(to).distance(q) <= 0.01) {
                double[][] d = TieOption.dirsAt(java.util.Arrays.asList(o.line.getCoordinates()), to);
                if (d != null) {
                    out.addAll(Arrays.asList(d));
                }
            }
        }
        return out.toArray(new double[0][]);
    }

    /** Points of the network closer than this are one place of attachment (the check of the result uses 0.05 m). */
    private static final double PLACE_TOL_M = 0.05;

    private TieOption chamberOption(InputModel.Chamber c, PipeSpec pipe, double flow, NetworkState state,
                                    Map<String, Double> chainMemo) {
        // one tie-in per new segment ending in an existing chamber; the chamber itself is not rebuilt
        // (appendix 3.2, clarification 13)
        double score = ref.scoreOfCost(ref.getTieInCost());
        return new TieOption(c.point.getCoordinate(), c, null, 0, score);
    }

    /**
     * Endpoint description of a goal, or null if the goal point itself is not allowed: a tie-in into the existing
     * network within the clearance to an object, unless such tie-ins are allowed.
     */
    private RoutingContext.Endpoint goalEndpoint(RoutingContext ctx, TieOption goal, Ties ties) {
        if (goal.isAttach()) {
            // a branching chamber respects all clearances and corridors; only the new network may be touched.
            // The corridors are those of the network being joined (its DN may be larger than the one searched for):
            // a chamber inside them would split the stretch of that network around a crossing
            double wider = goal.attachDn > 0
                    ? Math.max(0, ref.pipeAtLeast(goal.attachDn).getHalfWidthM() - ctx.getPipe().getHalfWidthM()) : 0;
            if (!ctx.specialsAround(goal.point, wider).isEmpty()) {
                return null;
            }
            RoutingContext.Endpoint et = ctx.endpoint(goal.point, null);
            et.escapedZones.removeIf(z -> !RoutingContext.NEW_ROUTE.equals(z.kind));
            et.mayTouchCorridors = false;
            return et;
        }
        List<RoutingContext.Zone> within = ctx.objectClearancesAt(goal.point);
        if (!within.isEmpty()) {
            if (!ties.inClearance) {
                ties.closed = true;
                return null;
            }
            ties.within.put(goal.key(), clearancesText(within, goal.point));
        }
        return ctx.endpoint(goal.point, ctx.specialsAround(goal.point, 0.05));
    }

    /**
     * What {@link #goalEndpoint} depends on: the point of the goal, whether it joins the new network, its DN; and the
     * key of the goal, under which it notes a tie-in within a clearance.
     */
    private static final class GoalKey {
        final String key;
        final long x;
        final long y;
        final boolean attach;
        final int dn;

        GoalKey(TieOption t) {
            key = t.key();
            x = Double.doubleToLongBits(t.point.x);
            y = Double.doubleToLongBits(t.point.y);
            attach = t.isAttach();
            dn = t.attachDn;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof GoalKey)) {
                return false;
            }
            GoalKey k = (GoalKey) o;
            return x == k.x && y == k.y && attach == k.attach && dn == k.dn && key.equals(k.key);
        }

        @Override
        public int hashCode() {
            long h = x * 31 + y;
            return ((int) (h ^ (h >>> 32)) * 31 + dn + (attach ? 1 : 0)) * 31 + key.hashCode();
        }
    }

    /** Alternatives of a tie-in kept for the journal. */
    private static final int MAX_ALTERNATIVES = 6;

    /** Deflection below which a turning point is not worth keeping, degrees. */
    private static final double MIN_TURN_DEG = 3;
    /** Straight pieces shorter than this are merged away when the direct edge is allowed, metres. */
    private static final double MIN_STRAIGHT_M = 1.0;

    private Route buildRoute(InputModel.Oks oks, PipeSpec pipe, List<Coordinate> verts, int hs, int[] parent,
                             Step[] inStep, TieOption goalTie, int goalFrom, Step goalStep, double total,
                             RoutingContext ctx, RoutingContext.Endpoint[] startEps, List<Start> starts,
                             Frame frame, Ties ties) {
        // steps from the goal back to the start, then the route in the search direction: start ... goal
        List<Step> steps = new ArrayList<>();
        steps.add(goalStep);
        int cur = goalFrom;
        int startVertex = goalFrom / hs;
        while (cur != -1) {
            if (inStep[cur] != null) {
                steps.add(inStep[cur]);
            }
            startVertex = cur / hs;
            cur = parent[cur];
        }
        Collections.reverse(steps);
        List<Coordinate> path = new ArrayList<>();
        List<RoutingContext.EdgeEval> pathEdges = new ArrayList<>();
        path.add(verts.get(startVertex));
        for (Step st : steps) {
            path.addAll(st.points);
            pathEdges.addAll(st.evals);
        }
        // the route runs from the goal to the start: fwd[i] → fwd[i+1] with edges flipped to that direction
        List<Coordinate> fwd = new ArrayList<>(path);
        Collections.reverse(fwd);
        List<RoutingContext.EdgeEval> edges = new ArrayList<>();
        for (int k = pathEdges.size() - 1; k >= 0; k--) {
            edges.add(flip(pathEdges.get(k)));
        }
        RoutingContext.Endpoint startEp = startVertex < startEps.length ? startEps[startVertex] : null;
        RoutingContext.Endpoint goalEp = goalEndpoint(ctx, goalTie, ties);
        // straighten: drop turning points that are not worth keeping where the direct edge is allowed; along the
        // directions only straight-on points go (anything else would break the standard bends)
        for (int i = 1; i + 1 < fwd.size(); ) {
            Coordinate a = fwd.get(i - 1);
            Coordinate b = fwd.get(i);
            Coordinate c = fwd.get(i + 1);
            double defl = Planner.deflectionDeg(a, b, c);
            boolean worth = frame != null ? defl < Frame.ALIGN_DEG
                    : a.distance(b) < MIN_STRAIGHT_M || b.distance(c) < MIN_STRAIGHT_M || defl < MIN_TURN_DEG
                            || !ref.isAllowedTurn(defl);
            if (worth) {
                RoutingContext.EdgeEval direct = ctx.evaluate(a, c, i - 1 == 0 ? goalEp : null,
                        i + 1 == fwd.size() - 1 ? startEp : null);
                if (direct.valid && simpleWithout(fwd, i)) {
                    fwd.remove(i);
                    edges.remove(i);
                    edges.set(i - 1, direct);
                    continue;
                }
            }
            i++;
        }
        Start start = starts.get(startVertex);
        if (frame != null) {
            total += cutShapes(fwd, edges, frame, ctx, pipe, goalTie, goalEp, startEp, beyondStart(oks, start));
        }
        if (start.leg != null) {
            // the leg out of the building ends the route at the wall; a route that goes on straight from the leg
            // has no turning point at its end
            Coordinate exit = new Coordinate(start.exit.c);
            fwd.add(exit);
            edges.add(flip(start.leg));
            int q = fwd.size() - 2;
            if (q >= 1 && Planner.deflectionDeg(fwd.get(q - 1), fwd.get(q), exit) < Frame.ALIGN_DEG) {
                RoutingContext.EdgeEval direct = ctx.evaluate(fwd.get(q - 1), exit, q - 1 == 0 ? goalEp : null,
                        ctx.endpoint(exit, ctx.specialsAround(exit, 0.0)));
                if (direct.valid) {
                    fwd.remove(q);
                    edges.remove(q);
                    edges.set(q - 1, direct);
                }
            }
        }
        // a turn sharper than 90° is not allowed (appendix 2.1): if straightening could not remove it, the route
        // is rejected and the search looks for another one
        for (int i = 1; i + 1 < fwd.size(); i++) {
            if (!ref.isAllowedTurn(Planner.deflectionDeg(fwd.get(i - 1), fwd.get(i), fwd.get(i + 1)))) {
                return null;
            }
        }
        Route route = new Route(oks, pipe, goalTie, fwd, edges, total - start.preference);
        route.preference = start.preference;
        return route;
    }

    /**
     * The point the route goes on to after its start (the last point of the found polyline): the wall at the end of
     * the straight piece out of the building, a point inside the building along the normal when the route starts
     * right at the wall, the connection point inside the building for a start on its outline; null when the route
     * ends at its connection point. The turn there is a turn of the route like any other.
     */
    private static Coordinate beyondStart(InputModel.Oks oks, Start start) {
        if (start.exit != null) {
            return start.leg != null ? new Coordinate(start.exit.c)
                    : new Coordinate(start.exit.c.x - start.exit.nx, start.exit.c.y - start.exit.ny);
        }
        Coordinate cp = oks.connectionPoint.getCoordinate();
        return oks.connectionInside() && start.c.distance(cp) > 1e-6 ? new Coordinate(cp) : null;
    }

    /** Most passes of {@link #cutShapes} over one route (each takes one shortening). */
    private static final int MAX_CUTS = 50;
    /**
     * Longest part of a route {@link #cutShapes} replaces, pieces: a U and a staircase are local (three and four
     * pieces); the search itself has found the way as a whole.
     */
    private static final int MAX_CUT_PIECES = 4;

    /**
     * Shapes of a found route cut short along the directions. Turning points of the search are only corners of the
     * zones and one point between two of them, so a route that needs two turns in a row away from the corners goes
     * round by a far corner (a U), or steps where one turn would do. Here every part fwd[i..j] of the route (from the
     * goal to the start) of up to {@link #MAX_CUT_PIECES} pieces is replaced by one or two legs along the directions when they pass the same checks as the
     * search (clearances, special sections, turns up to 90°, legs not shorter than the minimum, the joint with the
     * pipe) and the route gets cheaper — or as cheap with fewer points. The straight piece out of the building is not
     * a part of {@code fwd} and stays as it is; the turn after it is counted and checked. Returns the change of the
     * score (not positive).
     */
    double cutShapes(List<Coordinate> fwd, List<RoutingContext.EdgeEval> edges, Frame frame,
                             RoutingContext ctx, PipeSpec pipe, TieOption goalTie, RoutingContext.Endpoint goalEp,
                             RoutingContext.Endpoint startEp, Coordinate beyond) {
        double minPerM = ref.scoreOfCost(pipe.getNewCostPerM()) + ref.scoreOfLength(1);
        double change = 0;
        // the pieces checked once: the points of the route stay where they are from pass to pass
        Map<RoutingContext.EdgeKey, RoutingContext.EdgeEval> checked = new HashMap<>();
        for (int pass = 0; pass < MAX_CUTS; pass++) {
            boolean cut = false;
            outer:
            for (int i = 0; i + 2 < fwd.size(); i++) {
                for (int j = Math.min(fwd.size() - 1, i + MAX_CUT_PIECES); j >= i + 2; j--) {
                    double old = partScore(fwd, i, j, beyond, goalTie, frame);
                    for (int k = i; k < j; k++) {
                        old += edges.get(k).score;
                    }
                    Coordinate a = fwd.get(i);
                    Coordinate b = fwd.get(j);
                    for (Coordinate mid : frame.mids(a, b)) {
                        if (mid != null && (a.distance(mid) < params.minLegM || mid.distance(b) < params.minLegM)) {
                            continue;
                        }
                        List<Coordinate> cand = new ArrayList<>(fwd.size());
                        cand.addAll(fwd.subList(0, i + 1));
                        if (mid != null) {
                            cand.add(mid);
                        }
                        cand.addAll(fwd.subList(j, fwd.size()));
                        int nj = i + (mid == null ? 1 : 2);
                        double score = partScore(cand, i, nj, beyond, goalTie, frame);
                        if (score < 0) {
                            continue; // a turn sharper than 90° or a joint not allowed
                        }
                        double bound = score + (a.distance(mid == null ? b : mid) + (mid == null ? 0 : mid.distance(b)))
                                * minPerM;
                        boolean fewer = cand.size() < fwd.size();
                        if (bound > old + 1e-9 || bound > old - 1e-9 && !fewer) {
                            continue;
                        }
                        List<RoutingContext.EdgeEval> ne = new ArrayList<>(2);
                        boolean ok = true;
                        for (int k = i; k < nj && ok; k++) {
                            final int kk = k;
                            RoutingContext.EdgeEval e = checked.computeIfAbsent(
                                    new RoutingContext.EdgeKey(cand.get(k), cand.get(k + 1)),
                                    key -> ctx.evaluate(cand.get(kk), cand.get(kk + 1), kk == 0 ? goalEp : null,
                                            kk + 1 == cand.size() - 1 ? startEp : null));
                            ok = e.valid;
                            score += e.score;
                            ne.add(e);
                        }
                        if (!ok || score > old + 1e-9 || score > old - 1e-9 && !fewer || !simple(cand, beyond)) {
                            continue;
                        }
                        List<RoutingContext.EdgeEval> nedges = new ArrayList<>(edges.size());
                        nedges.addAll(edges.subList(0, i));
                        nedges.addAll(ne);
                        nedges.addAll(edges.subList(j, edges.size()));
                        fwd.clear();
                        fwd.addAll(cand);
                        edges.clear();
                        edges.addAll(nedges);
                        change += score - old;
                        cut = true;
                        break outer;
                    }
                }
            }
            if (!cut) {
                break;
            }
        }
        return change;
    }

    /**
     * Score of the turns at the points i..j of a route (the last one turns to {@code beyond}) and of its joint with
     * the pipe when i is the goal; -1 when a turn is sharper than 90° or the joint is not allowed.
     */
    private double partScore(List<Coordinate> c, int i, int j, Coordinate beyond, TieOption goalTie, Frame frame) {
        double s = 0;
        if (i == 0) {
            double joint = junctionScore(goalTie, c.get(1), frame);
            if (joint < 0) {
                return -1;
            }
            s += joint;
        }
        int last = c.size() - 1;
        for (int k = Math.max(1, i); k <= j; k++) {
            Coordinate next = k < last ? c.get(k + 1) : beyond;
            if (next == null) {
                continue;
            }
            double d = Planner.deflectionDeg(c.get(k - 1), c.get(k), next);
            if (!ref.isAllowedTurn(d)) {
                return -1;
            }
            if (d > ref.getStraightDeg()) {
                s += params.turnPenaltyScore;
            }
        }
        return s;
    }

    /** Whether a route (and the piece to the point beyond its start) does not cross itself. */
    private static boolean simple(List<Coordinate> c, Coordinate beyond) {
        List<Coordinate> all = new ArrayList<>(c);
        if (beyond != null) {
            all.add(beyond);
        }
        return Crs.UTM_FACTORY.createLineString(all.toArray(new Coordinate[0])).isSimple();
    }

    private static RoutingContext.EdgeEval flip(RoutingContext.EdgeEval e) {
        List<RoutingContext.Interval> ivs = new ArrayList<>();
        for (RoutingContext.Interval iv : e.intervals) {
            ivs.add(new RoutingContext.Interval(e.length - iv.to, e.length - iv.from, iv.k, iv.objectId, iv.type));
        }
        Collections.reverse(ivs);
        return new RoutingContext.EdgeEval(true, e.length, e.score, ivs, null);
    }
}
