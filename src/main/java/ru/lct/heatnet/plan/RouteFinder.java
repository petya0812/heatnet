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
    private final ZoneCache zoneCache = new ZoneCache();
    /** Static zones of recent search areas; bounded, the planner comes back to the same OKS many times. */
    private final Map<String, RoutingContext.Static> sharedCache = new java.util.LinkedHashMap<String,
            RoutingContext.Static>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RoutingContext.Static> eldest) {
            return size() > params.contextCache;
        }
    };
    private final PlanParams params;
    private final InputModel model;
    private final ObstacleSource obstacles;

    public RouteFinder(ReferenceData ref, PlanParams params, InputModel model, ObstacleSource obstacles) {
        this.ref = ref;
        this.params = params;
        this.model = model;
        this.obstacles = obstacles;
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
        Coordinate p = oks.routePoint.getCoordinate();
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
            // first a route along the directions of the chosen turn angles (tidier trace, fewer states), then — unless
            // the angles are strict — a free search with any angles up to 90°, which the appendix allows (2.1)
            Frame frame = params.turnStepDeg > 0 ? frameFor(ctx, p) : null;
            Route r = null;
            for (Frame f : frame == null ? java.util.Collections.<Frame>singletonList(null)
                    : params.turnStepStrict ? java.util.Collections.singletonList(frame)
                    : java.util.Arrays.asList(frame, null)) {
                if (trace != null) {
                    trace.reset(radius);
                }
                r = search(oks, pipe, ctx, state, attach, diag, fail, slack, trace, f);
                if (r != null) {
                    break;
                }
            }
            if (r != null) {
                return r;
            }
            if (fail.reason != Unconnected.Reason.SEARCH_LIMIT) {
                fail.set(Unconnected.Reason.NO_ROUTE, String.format("радиус поиска %.0f м, ДУ %d, до сети %.0f м%s",
                        radius, pipe.getDn(), dNear, frame != null && params.turnStepStrict
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
                ctx.addForbidden("new:" + b.oksId, "new_route", b.line,
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
        Geometry loadArea = Crs.UTM_FACTORY.createPoint(p).buffer(radius + params.obstacleMarginM, 16);
        RoutingContext ctx = new RoutingContext(ref, params, pipe, area, zoneCache, null);
        String ownBuilding = oks == null ? null : oks.ownBuildingId;
        for (Obstacle o : obstacles.intersecting(loadArea)) {
            if (o.getId().equals(ownBuilding)) {
                // the building this OKS belongs to: the route may come to its outline and enter it
                ctx.addOwnBuilding(o.getId(), o.getGeometry());
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
        ctx.build();
        RoutingContext.Static shared = ctx.toStatic();
        synchronized (sharedCache) {
            sharedCache.put(key, shared);
        }
        return shared;
    }

    /** Extra goals of the search: points on the new network of the variant (joint connection). */
    public interface AttachProvider {
        /** Attach candidates reachable from a vertex; terminal scores estimate the whole cost of joining there. */
        List<TieOption> attachOptions(Coordinate v, PipeSpec pipe, double flow);

        /** Distance from a point to the new network (for the search heuristic). */
        double distance(Coordinate v);
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

    /** A route start: a point on the outline of the building (or the connection point) and the way into it. */
    static final class Start {
        final Coordinate c;
        /** Heading of the piece from the connection point to this exit, {@link Frame#FREE} if there is none. */
        final int heading;

        Start(Coordinate c, int heading) {
            this.c = c;
            this.heading = heading;
        }
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

    private Route search(InputModel.Oks oks, PipeSpec pipe, RoutingContext ctx, NetworkState state,
                         AttachProvider attach, Diagnostics diag, Unconnected.Failure fail, double slack,
                         SearchTrace trace, Frame frame) {
        Coordinate p = oks.routePoint.getCoordinate();
        // where the route may leave the building: the search picks the exit itself, the piece inside the building
        // from the exit to the connection point is paid for by the starting cost
        List<Start> starts = entryPoints(oks, p, frame);
        double[] startCost = new double[starts.size()];
        List<Coordinate> verts = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            double inside = starts.get(i).c.distance(oks.connectionPoint.getCoordinate());
            startCost[i] = ref.scoreOfCost(pipe.getNewCostPerM() * inside) + ref.scoreOfLength(inside);
            verts.add(starts.get(i).c);
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
        RoutingContext.Endpoint[] startEps = new RoutingContext.Endpoint[nStarts];
        for (int i = 0; i < nStarts; i++) {
            startEps[i] = ctx.endpoint(verts.get(i), ctx.specialsAround(verts.get(i), 0.0));
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
        double[] h = new double[n];
        for (int i = 0; i < n; i++) {
            double d = model.distanceToNetwork(verts.get(i));
            if (attach != null) {
                d = Math.min(d, attach.distance(verts.get(i)));
            }
            h[i] = d * minPerM + goalMin;
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
        double bestGoal = Double.POSITIVE_INFINITY;
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
                            pq.g[it], ctx, startEps, frame);
                    if (r == null) {
                        // the route has a turn sharper than 90° that straightening could not remove: this goal
                        // is not usable, the search goes on
                        continue;
                    }
                    if (reached != null) {
                        r.alternatives.addAll(alternatives(reached, pq, itTie));
                    }
                    if (trace != null) {
                        trace.expansions.add(new int[]{-1, from / hs});
                    }
                    return r;
                }
                RoutingContext.Endpoint et = goalEndpoint(ctx, itTie);
                if (et == null) {
                    continue;
                }
                int fv = from / hs;
                Pending pd = (Pending) payload;
                Step e = cachedStep(wayCache, "g" + fv + ":" + pd.variant + ":" + itTie.key(), ctx, frame,
                        verts.get(fv), fv < nStarts ? startEps[fv] : null, heading(frame, from, hs), fv < nStarts,
                        pd.mid, itTie.point, et, trace);
                if (e != null) {
                    double total = g[from] + e.score + itTie.terminalScore;
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
            List<TieOption> goals = goalsOf.get(uv);
            if (goals == null) {
                goals = new ArrayList<>(tieOptions(cu, pipe, oks.flowTph, state, chainMemo, frame));
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
                    double lower = g[u] + turn + wayLength(cu, mid, tie.point) * minPerM + tie.terminalScore;
                    if (lower < bestGoal) {
                        pq.push(lower, -1, u, tie, new Pending(mid, m), lower);
                    }
                }
            }
            for (int w : neighbours(vertexIndex, verts, cu, n,
                    frame == null ? params.neighbourRadiusM : params.frameNeighbourRadiusM)) {
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
        RoutingContext.EdgeEval e1 = ctx.evaluate(a, end1, ea, mid == null ? eb : null);
        if (!e1.valid) {
            if (trace != null) {
                trace.reject(a, end1, e1.reason);
            }
            return null;
        }
        int last = frame == null ? Frame.FREE : lastHeading(frame, a, mid, b);
        if (mid == null) {
            return new Step(Collections.singletonList(b), Collections.singletonList(e1), turn + e1.score, last);
        }
        RoutingContext.EdgeEval e2 = ctx.evaluate(mid, b, null, eb);
        if (!e2.valid) {
            if (trace != null) {
                trace.reject(mid, b, e2.reason);
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
     * Points where the route may leave the building of the OKS: the connection point itself when it is outside,
     * otherwise points of the outline from which a straight piece inside the building reaches the connection point.
     * Which one is used is decided by the search: the nearest exit is often walled in by neighbours. With directions,
     * the exits lie on the rays from the connection point along the directions, so the bend where the route
     * leaves the building is a standard one too.
     */
    List<Start> entryPoints(InputModel.Oks oks, Coordinate routePoint, Frame frame) {
        List<Start> out = new ArrayList<>();
        if (!oks.connectionInside() || oks.polygon == null) {
            out.add(new Start(routePoint, Frame.FREE));
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
                    out.add(new Start(exit, k));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        out.add(new Start(routePoint, Frame.FREE));
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
                out.add(new Start(c, Frame.FREE));
            }
        }
        return out;
    }

    /**
     * Tie-in candidates near a vertex: the nearest point of every segment and, with directions, the point where
     * the direction closest to the segment meets it (reached by one straight leg).
     */
    List<TieOption> tieOptions(Coordinate v, PipeSpec pipe, double flow, NetworkState state,
                               Map<String, Double> chainMemo, Frame frame) {
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
                addTiePoint(out, s, t, q, pipe, flow, state, chainMemo);
            }
            if (frame != null) {
                double[] ray = alongDirection(frame, v, s.line);
                if (ray != null && ray[1] <= reach) {
                    Coordinate qr = lil.extractPoint(ray[0]);
                    if (qr.distance(q) > 0.5) {
                        addTiePoint(out, s, ray[0], qr, pipe, flow, state, chainMemo);
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
        return new TieOption(q, null, s, t, score);
    }

    private TieOption chamberOption(InputModel.Chamber c, PipeSpec pipe, double flow, NetworkState state,
                                    Map<String, Double> chainMemo) {
        // one tie-in per new segment ending in an existing chamber; the chamber itself is not rebuilt
        // (appendix 3.2, clarification 13)
        double score = ref.scoreOfCost(ref.getTieInCost());
        return new TieOption(c.point.getCoordinate(), c, null, 0, score);
    }

    /** Endpoint description of a goal, or null if the goal point itself is not allowed. */
    private RoutingContext.Endpoint goalEndpoint(RoutingContext ctx, TieOption goal) {
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
            et.escapedZones.removeIf(z -> !"new_route".equals(z.kind));
            et.mayTouchCorridors = false;
            return et;
        }
        return ctx.endpoint(goal.point, ctx.specialsAround(goal.point, 0.05));
    }

    /** Alternatives of a tie-in kept for the journal. */
    private static final int MAX_ALTERNATIVES = 6;

    /** Deflection below which a turning point is not worth keeping, degrees. */
    private static final double MIN_TURN_DEG = 3;
    /** Straight pieces shorter than this are merged away when the direct edge is allowed, metres. */
    private static final double MIN_STRAIGHT_M = 1.0;

    private Route buildRoute(InputModel.Oks oks, PipeSpec pipe, List<Coordinate> verts, int hs, int[] parent,
                             Step[] inStep, TieOption goalTie, int goalFrom, Step goalStep, double total,
                             RoutingContext ctx, RoutingContext.Endpoint[] startEps, Frame frame) {
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
        RoutingContext.Endpoint goalEp = goalEndpoint(ctx, goalTie);
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
        // a turn sharper than 90° is not allowed (appendix 2.1): if straightening could not remove it, the route
        // is rejected and the search looks for another one
        for (int i = 1; i + 1 < fwd.size(); i++) {
            if (!ref.isAllowedTurn(Planner.deflectionDeg(fwd.get(i - 1), fwd.get(i), fwd.get(i + 1)))) {
                return null;
            }
        }
        return new Route(oks, pipe, goalTie, fwd, edges, total);
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
