package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * New network of one variant under construction: a forest, every tree grows from one tie-in (R-NET-3, R-NET-4).
 * Nodes are tie-ins, branching chambers (junctions) and OKS connection points; edges are polylines directed
 * from the tie-in towards the OKS, with special-section intervals in absolute positions along the edge.
 */
final class Draft {

    enum Kind { TIE, JUNCTION, CP }

    static final class Node {
        final int id;
        final Kind kind;
        final Coordinate c;
        final InputModel.Oks oks;
        final TieOption tie;
        Edge up;
        final List<Edge> down = new ArrayList<>();

        Node(int id, Kind kind, Coordinate c, InputModel.Oks oks, TieOption tie) {
            this.id = id;
            this.kind = kind;
            this.c = c;
            this.oks = oks;
            this.tie = tie;
        }

        int degree() {
            return (up != null ? 1 : 0) + down.size();
        }
    }

    static final class Edge {
        final int id;
        Node up;
        Node down;
        List<Coordinate> coords;
        List<RoutingContext.Interval> intervals;
        int dn;
        double flow;
        /** DN the geometry was checked for (clearances grow with DN). */
        int checkedDn;
        /** How the edge appeared (diagnostics): route of an OKS, split or merge. */
        String origin = "";

        Edge(int id) {
            this.id = id;
        }

        double length() {
            double l = 0;
            for (int i = 0; i + 1 < coords.size(); i++) {
                l += coords.get(i).distance(coords.get(i + 1));
            }
            return l;
        }

        /** Length with special sections weighted by their coefficient, for positions [from, to]. */
        double weightedLength(double from, double to) {
            double l = to - from;
            for (RoutingContext.Interval iv : intervals) {
                double o = Math.min(to, iv.to) - Math.max(from, iv.from);
                if (o > 0) {
                    l += o * (iv.k - 1);
                }
            }
            return l;
        }
    }

    final List<Node> roots = new ArrayList<>();
    final Map<Integer, Node> nodes = new HashMap<>();
    final Map<Integer, Edge> edges = new HashMap<>();
    private int nextId = 1;

    // ------------------------------------------------------------------ building

    private Node node(Kind kind, Coordinate c, InputModel.Oks oks, TieOption tie) {
        Node n = new Node(nextId++, kind, c, oks, tie);
        nodes.put(n.id, n);
        return n;
    }

    private Edge edge(Node up, Node down, List<Coordinate> coords, List<RoutingContext.Interval> intervals, int dn) {
        coords = dedupe(coords);
        Edge e = new Edge(nextId++);
        e.up = up;
        e.down = down;
        e.coords = coords;
        e.intervals = intervals;
        e.dn = dn;
        e.checkedDn = dn;
        up.down.add(e);
        down.up = e;
        edges.put(e.id, e);
        return e;
    }

    /** Drops repeated consecutive points (a zero-length piece is not a route piece). */
    private static List<Coordinate> dedupe(List<Coordinate> in) {
        List<Coordinate> out = new ArrayList<>(in.size());
        for (Coordinate c : in) {
            if (out.isEmpty() || out.get(out.size() - 1).distance(c) > 1e-6) {
                out.add(c);
            }
        }
        if (out.size() == 1) {
            out.add(new Coordinate(out.get(0)));
        }
        return out;
    }

    /** Adds a route found by {@link RouteFinder}: a new tree (tie-in goal) or a branch of an existing one. */
    Node insert(Route r) {
        List<Coordinate> coords = new ArrayList<>(r.coords);
        List<RoutingContext.Interval> ivs = new ArrayList<>();
        double pos = 0;
        for (int i = 0; i + 1 < coords.size(); i++) {
            for (RoutingContext.Interval iv : r.edges.get(i).intervals) {
                ivs.add(new RoutingContext.Interval(pos + iv.from, pos + iv.to, iv.k, iv.objectId, iv.type));
            }
            pos += coords.get(i).distance(coords.get(i + 1));
        }
        Node cp = node(Kind.CP, coords.get(coords.size() - 1), r.oks, null);
        Node start;
        TieOption goal = r.tie;
        if (goal.attachEdgeId > 0) {
            start = split(edges.get(goal.attachEdgeId), goal.attachPos);
        } else if (goal.attachNodeId > 0) {
            start = nodes.get(goal.attachNodeId);
        } else {
            start = node(Kind.TIE, goal.point, null, goal);
            roots.add(start);
        }
        coords.set(0, new Coordinate(start.c));
        edge(start, cp, coords, ivs, r.pipe.getDn()).origin = "route " + r.oks.id;
        return cp;
    }

    /** Splits an edge at a position, returns the new junction. */
    Node split(Edge e, double pos) {
        List<Coordinate> a = new ArrayList<>();
        List<Coordinate> b = new ArrayList<>();
        double acc = 0;
        Coordinate at = null;
        a.add(e.coords.get(0));
        for (int i = 0; i + 1 < e.coords.size(); i++) {
            Coordinate p = e.coords.get(i);
            Coordinate q = e.coords.get(i + 1);
            double l = p.distance(q);
            if (at == null && acc + l >= pos) {
                double f = l > 0 ? (pos - acc) / l : 0;
                at = new Coordinate(p.x + (q.x - p.x) * f, p.y + (q.y - p.y) * f);
                if (at.distance(a.get(a.size() - 1)) > 1e-9) {
                    a.add(at);
                }
                b.add(at);
                if (at.distance(q) > 1e-9) {
                    b.add(q);
                }
            } else if (at == null) {
                a.add(q);
            } else {
                b.add(q);
            }
            acc += l;
        }
        List<RoutingContext.Interval> ia = new ArrayList<>();
        List<RoutingContext.Interval> ib = new ArrayList<>();
        for (RoutingContext.Interval iv : e.intervals) {
            if (iv.to <= pos) {
                ia.add(iv);
            } else if (iv.from >= pos) {
                ib.add(new RoutingContext.Interval(iv.from - pos, iv.to - pos, iv.k, iv.objectId, iv.type));
            } else {
                ia.add(new RoutingContext.Interval(iv.from, pos, iv.k, iv.objectId, iv.type));
                ib.add(new RoutingContext.Interval(0, iv.to - pos, iv.k, iv.objectId, iv.type));
            }
        }
        Node j = node(Kind.JUNCTION, at, null, null);
        Node down = e.down;
        e.up.down.remove(e);
        edges.remove(e.id);
        Edge ea = edge(e.up, j, a, ia, e.dn);
        Edge eb = edge(j, down, b, ib, e.dn);
        ea.checkedDn = e.checkedDn;
        eb.checkedDn = e.checkedDn;
        ea.origin = "split-a(" + e.origin + ")";
        eb.origin = "split-b(" + e.origin + ")";
        return j;
    }

    /**
     * Removes the private tail of an OKS (from its connection point up to the first node shared with other OKS).
     * A junction left with one outgoing edge is dissolved; a tree left empty is removed.
     */
    void remove(InputModel.Oks oks) {
        Node cp = cpOf(oks);
        if (cp == null) {
            return;
        }
        Node n = cp;
        while (true) {
            Edge up = n.up;
            Node parent = up.up;
            parent.down.remove(up);
            edges.remove(up.id);
            nodes.remove(n.id);
            if (parent.kind == Kind.TIE) {
                if (parent.down.isEmpty()) {
                    roots.remove(parent);
                    nodes.remove(parent.id);
                }
                return;
            }
            if (parent.down.isEmpty()) {
                n = parent; // a junction always has >= 2 children; an empty one is walked through like a plain node
                continue;
            }
            if (parent.down.size() == 1) {
                dissolve(parent);
            }
            return;
        }
    }

    private void dissolve(Node j) {
        Edge a = j.up;
        Edge b = j.down.get(0);
        List<Coordinate> coords = new ArrayList<>(a.coords);
        coords.addAll(b.coords.subList(1, b.coords.size()));
        double la = a.length();
        List<RoutingContext.Interval> ivs = new ArrayList<>(a.intervals);
        for (RoutingContext.Interval iv : b.intervals) {
            ivs.add(new RoutingContext.Interval(iv.from + la, iv.to + la, iv.k, iv.objectId, iv.type));
        }
        ivs = RoutingContext.merge(ivs, la + b.length());
        Node up = a.up;
        Node down = b.down;
        up.down.remove(a);
        edges.remove(a.id);
        edges.remove(b.id);
        nodes.remove(j.id);
        Edge e = edge(up, down, coords, ivs, Math.max(a.dn, b.dn));
        e.checkedDn = Math.max(a.checkedDn, b.checkedDn);
        e.origin = "merge(" + a.origin + "+" + b.origin + ")";
    }

    Node cpOf(InputModel.Oks oks) {
        for (Node n : nodes.values()) {
            if (n.kind == Kind.CP && n.oks == oks) {
                return n;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ flows, DN, length limit

    /**
     * Flows (sum of OKS below), minimal DN per edge, then the length limit (R-NET-10): a connected group of edges of
     * one DN may not be longer than the limit of that DN. A group that is too long gets the next DN of table 1, up to
     * the minimal DN that meets both the flow and the limit (appendix 2.3, clarification 1). Returns false if no DN
     * meets the limit.
     */
    boolean recompute(ReferenceData ref) {
        for (Node r : roots) {
            flows(r);
        }
        for (Edge e : edges.values()) {
            PipeSpec p = ref.minPipeForFlow(e.flow);
            if (p == null) {
                return false;
            }
            e.dn = p.getDn();
        }
        // The DN is the minimal one that satisfies both the flow and the length limit (appendix 2.3,
        // clarifications 1 and 2). Raising a DN may only go upwards along the route, so every round raises at
        // least one edge and the loop ends.
        for (int iter = 0; iter <= edges.size() * ref.getPipes().size(); iter++) {
            keepDnNonDecreasing();
            List<Edge> violating = longRun(ref);
            if (violating == null) {
                return true;
            }
            PipeSpec next = ref.nextPipe(violating.get(0).dn);
            if (next == null) {
                return false;
            }
            for (Edge e : violating) {
                e.dn = next.getDn();
            }
        }
        return false;
    }

    /**
     * Towards the place of attachment the DN never decreases (appendix 2.3): an edge is at least as wide as
     * every edge below it.
     */
    private void keepDnNonDecreasing() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Edge e : edges.values()) {
                for (Edge below : e.down.down) {
                    if (below.dn > e.dn) {
                        e.dn = below.dn;
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * First continuous run of one DN along a single path from a connection point to its place of attachment
     * whose length exceeds the limit of that DN, or null. Paths are checked one by one: a shared edge counts in
     * every path through it, lengths of parallel branches never add up (appendix 2.3, clarification 2).
     */
    private List<Edge> longRun(ReferenceData ref) {
        List<Node> cps = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.kind == Kind.CP) {
                cps.add(n);
            }
        }
        cps.sort((a, b) -> Integer.compare(a.id, b.id));
        for (Node cp : cps) {
            List<Edge> path = pathToRoot(cp);
            int i = 0;
            while (i < path.size()) {
                int dn = path.get(i).dn;
                double len = 0;
                List<Edge> run = new ArrayList<>();
                int k = i;
                while (k < path.size() && path.get(k).dn == dn) {
                    run.add(path.get(k));
                    len += path.get(k).length();
                    k++;
                }
                if (len > ref.pipe(dn).getMaxLengthM() + 1e-6) {
                    return run;
                }
                i = k;
            }
        }
        return null;
    }

    private double flows(Node n) {
        double f = n.kind == Kind.CP ? n.oks.flowTph : 0;
        for (Edge e : n.down) {
            e.flow = flows(e.down);
            f += e.flow;
        }
        return f;
    }

    /**
     * A turn of the route sharper than the limit of the appendix (2.1). Checked on the whole draft, because an
     * edge may be split or re-checked for a larger DN after the route was found.
     */
    boolean hasSharpTurns(ReferenceData ref) {
        for (Edge e : edges.values()) {
            for (int k = 1; k + 1 < e.coords.size(); k++) {
                if (!ref.isAllowedTurn(Planner.deflectionDeg(e.coords.get(k - 1), e.coords.get(k),
                        e.coords.get(k + 1)))) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean hasCrossings() {
        List<Edge> list = new java.util.ArrayList<>(edges.values());
        org.locationtech.jts.index.strtree.STRtree index = new org.locationtech.jts.index.strtree.STRtree();
        java.util.Map<Edge, org.locationtech.jts.geom.LineString> lines = new java.util.HashMap<>();
        for (Edge e : list) {
            if (e.coords.size() < 2) {
                continue;
            }
            org.locationtech.jts.geom.LineString line = ru.lct.heatnet.geo.Crs.UTM_FACTORY
                    .createLineString(e.coords.toArray(new Coordinate[0]));
            lines.put(e, line);
            index.insert(line.getEnvelopeInternal(), e);
        }
        for (Edge a : list) {
            org.locationtech.jts.geom.LineString la = lines.get(a);
            if (la == null) {
                continue;
            }
            if (!la.isSimple()) {
                return true; // the route crosses itself
            }
            for (Object o : index.query(la.getEnvelopeInternal())) {
                Edge b = (Edge) o;
                if (b.id <= a.id) {
                    continue;
                }
                org.locationtech.jts.geom.LineString lb = lines.get(b);
                if (!la.intersects(lb)) {
                    continue;
                }
                for (Coordinate c : la.intersection(lb).getCoordinates()) {
                    boolean shared = isEnd(la, c) && isEnd(lb, c);
                    if (!shared) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isEnd(org.locationtech.jts.geom.LineString l, Coordinate c) {
        return l.getCoordinateN(0).distance(c) <= 0.05
                || l.getCoordinateN(l.getNumPoints() - 1).distance(c) <= 0.05;
    }

    static double treeFlow(Node root) {
        double f = 0;
        for (Edge e : root.down) {
            f += e.flow;
        }
        return f;
    }

    /** Edges from a node up to its tie-in (nearest first). */
    static List<Edge> pathToRoot(Node n) {
        List<Edge> out = new ArrayList<>();
        while (n.up != null) {
            out.add(n.up);
            n = n.up.up;
        }
        return out;
    }

    static Node rootOf(Node n) {
        while (n.up != null) {
            n = n.up.up;
        }
        return n;
    }

    List<InputModel.Oks> connected() {
        List<InputModel.Oks> out = new ArrayList<>();
        for (Node n : nodes.values()) {
            if (n.kind == Kind.CP) {
                out.add(n.oks);
            }
        }
        return out;
    }

    /** Deep copy (ids preserved, so tie/attach options referring to ids stay valid). */
    Draft copy() {
        Draft d = new Draft();
        d.nextId = nextId;
        Map<Node, Node> nm = new IdentityHashMap<>();
        for (Node n : nodes.values()) {
            Node c = new Node(n.id, n.kind, n.c, n.oks, n.tie);
            nm.put(n, c);
            d.nodes.put(c.id, c);
        }
        for (Edge e : edges.values()) {
            Edge c = new Edge(e.id);
            c.up = nm.get(e.up);
            c.down = nm.get(e.down);
            c.coords = new ArrayList<>(e.coords);
            c.intervals = new ArrayList<>(e.intervals);
            c.dn = e.dn;
            c.flow = e.flow;
            c.checkedDn = e.checkedDn;
            c.origin = e.origin;
            d.edges.put(c.id, c);
        }
        for (Node n : nodes.values()) {
            Node c = nm.get(n);
            if (n.up != null) {
                c.up = d.edges.get(n.up.id);
            }
            for (Edge e : n.down) {
                c.down.add(d.edges.get(e.id));
            }
        }
        for (Node r : roots) {
            d.roots.add(nm.get(r));
        }
        return d;
    }

    /** Structure signature: tie-in object and the OKS of every tree (for distinct variants, ТЗ 2.8). */
    String signature() {
        List<String> parts = new ArrayList<>();
        for (Node r : roots) {
            List<String> ids = new ArrayList<>();
            collectOks(r, ids);
            Collections.sort(ids);
            parts.add(r.tie.existingObjectId() + ":" + String.join(",", ids));
        }
        Collections.sort(parts);
        return String.join(";", parts);
    }

    private static void collectOks(Node n, List<String> out) {
        if (n.kind == Kind.CP) {
            out.add(n.oks.id);
        }
        for (Edge e : n.down) {
            collectOks(e.down, out);
        }
    }
}
