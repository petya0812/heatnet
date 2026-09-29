package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.reference.ReferenceData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Construction journal of a variant: the steps the planner took, as plain maps (UTM coordinates; the service turns
 * them into WGS 84). Every step carries the change of the new network against the previous step, the tie-ins and
 * branching chambers, the loaded pieces of the existing network and, for a connection, the route with the
 * alternatives the search reached. Replaying the steps gives the network of the variant before its final assembly.
 *
 * <pre>
 * start       {strategy, explanation, order[], oks[{id, flow_tph, point}]}
 * connect     {oks, flow_tph, route{coords, dn, length, turns, score, target}, alternatives[], ...network}
 * unconnected {oks, flow_tph, reason, detail}
 * rebuild     {oks, flow_tph, route?, alternatives[], ...network}   (the OKS taken out and connected again)
 * network:    edges_removed[], edges[{id, coords, dn, flow_tph, special[[from, to, k]]}], nodes[], recon[],
 *             connected, network_length
 * </pre>
 */
final class JournalRecorder {

    private final ReferenceData ref;
    private final InputModel model;

    JournalRecorder(ReferenceData ref, InputModel model) {
        this.ref = ref;
        this.model = model;
    }

    /** Journal of one candidate: events and the edge signatures of the last recorded state. */
    static final class Log {
        final List<Map<String, Object>> events;
        final Map<Integer, String> edges;

        Log(List<Map<String, Object>> events, Map<Integer, String> edges) {
            this.events = events;
            this.edges = edges;
        }

        Log copy() {
            return new Log(new ArrayList<>(events), new HashMap<>(edges));
        }
    }

    Log start(String strategy, String explanation, List<InputModel.Oks> order) {
        Map<String, Object> e = event("start");
        e.put("strategy", strategy);
        e.put("explanation", explanation);
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> oks = new ArrayList<>();
        for (InputModel.Oks o : order) {
            ids.add(o.id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id);
            m.put("flow_tph", o.flowTph);
            m.put("point", new double[]{round(o.connectionPoint.getX(), 3), round(o.connectionPoint.getY(), 3)});
            oks.add(m);
        }
        e.put("order", ids);
        e.put("oks", oks);
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(e);
        return new Log(events, new HashMap<>());
    }

    Map<String, Object> connected(Log log, String type, InputModel.Oks oks, Route r, Draft d) {
        Map<String, Object> e = event(type);
        e.put("oks", oks.id);
        e.put("flow_tph", oks.flowTph);
        if (r != null) {
            e.put("route", route(r, d));
            List<Map<String, Object>> alts = new ArrayList<>();
            for (Route.Alternative a : r.alternatives) {
                Map<String, Object> m = target(a.tie);
                m.put("score", round(a.score, 4));
                m.put("chosen", a.tie.key().equals(r.tie.key()));
                m.put("estimate", a.estimate);
                alts.add(m);
            }
            e.put("alternatives", alts);
        }
        network(log, e, d);
        return add(log, e);
    }

    Map<String, Object> unconnected(Log log, InputModel.Oks oks, Unconnected u) {
        Map<String, Object> e = event("unconnected");
        e.put("oks", oks.id);
        e.put("flow_tph", oks.flowTph);
        e.put("reason", u.reason.name());
        e.put("reason_text", u.reason.text());
        if (u.detail != null) {
            e.put("detail", u.detail);
        }
        return add(log, e);
    }

    private static Map<String, Object> add(Log log, Map<String, Object> e) {
        e.put("seq", log.events.size());
        log.events.add(e);
        return e;
    }

    private static Map<String, Object> event(String type) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("seq", 0);
        e.put("type", type);
        return e;
    }

    private Map<String, Object> route(Route r, Draft d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coords", coords(r.coords));
        Draft.Node cp = d.cpOf(r.oks);
        m.put("dn", cp != null && cp.up != null ? cp.up.dn : r.pipe.getDn());
        m.put("length", round(r.length(), 2));
        m.put("turns", r.turns() - (r.oks.connectionInside() ? 1 : 0));
        m.put("score", round(r.score, 4));
        m.put("target", target(r.tie));
        return m;
    }

    private static Map<String, Object> target(TieOption t) {
        Map<String, Object> m = new LinkedHashMap<>();
        String kind;
        if (t.isAttach()) {
            kind = t.attachNodeId > 0 ? "join_junction" : "join_edge";
        } else {
            kind = t.chamber != null ? "tie_chamber" : "tie_segment";
        }
        m.put("kind", kind);
        m.put("object_id", t.existingObjectId());
        m.put("point", new double[]{round(t.point.x, 3), round(t.point.y, 3)});
        return m;
    }

    /** Changes of the network against the previous step, tie-ins and junctions, loaded pieces of the existing network. */
    private void network(Log log, Map<String, Object> e, Draft d) {
        Map<Integer, String> now = new HashMap<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        double length = 0;
        List<Integer> ids = new ArrayList<>(d.edges.keySet());
        java.util.Collections.sort(ids);
        for (Integer id : ids) {
            Draft.Edge x = d.edges.get(id);
            List<double[]> c = coords(x.coords);
            StringBuilder sig = new StringBuilder().append(x.dn).append('|').append(round(x.flow, 3));
            for (double[] p : c) {
                sig.append('|').append(p[0]).append(',').append(p[1]);
            }
            now.put(id, sig.toString());
            length += x.length();
            if (!sig.toString().equals(log.edges.get(id))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("coords", c);
                m.put("dn", x.dn);
                m.put("flow_tph", round(x.flow, 3));
                List<double[]> sp = new ArrayList<>();
                for (RoutingContext.Interval iv : x.intervals) {
                    sp.add(new double[]{round(iv.from, 2), round(iv.to, 2), iv.k});
                }
                m.put("special", sp);
                changed.add(m);
            }
        }
        List<Integer> removed = new ArrayList<>();
        for (Integer id : log.edges.keySet()) {
            if (!now.containsKey(id)) {
                removed.add(id);
            }
        }
        java.util.Collections.sort(removed);
        log.edges.clear();
        log.edges.putAll(now);
        e.put("edges_removed", removed);
        e.put("edges", changed);

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<ReconstructionCalculator.Load> loads = new ArrayList<>();
        int connected = 0;
        List<Integer> nids = new ArrayList<>(d.nodes.keySet());
        java.util.Collections.sort(nids);
        for (Integer id : nids) {
            Draft.Node n = d.nodes.get(id);
            if (n.kind == Draft.Kind.CP) {
                connected++;
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id);
            m.put("kind", n.kind == Draft.Kind.TIE ? "tie" : "junction");
            m.put("point", new double[]{round(n.c.x, 3), round(n.c.y, 3)});
            if (n.kind == Draft.Kind.TIE) {
                m.put("object_id", n.tie.existingObjectId());
                m.put("object_type", n.tie.chamber != null ? "heat_chamber" : "heat_network");
                double flow = Draft.treeFlow(n);
                m.put("flow_tph", round(flow, 3));
                loads.add(n.tie.chamber != null
                        ? new ReconstructionCalculator.Load(n.tie.chamber.id, true, 0, flow)
                        : new ReconstructionCalculator.Load(n.tie.segment.id, false, n.tie.t, flow));
            }
            nodes.add(m);
        }
        e.put("nodes", nodes);
        List<Map<String, Object>> recon = new ArrayList<>();
        for (ReconstructionCalculator.Piece p : new ReconstructionCalculator(model, ref).compute(loads, new Diagnostics())) {
            if (p.to - p.from < ReconstructionCalculator.MIN_RECON_PIECE_M) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("segment", p.segment.id);
            m.put("coords", coords(java.util.Arrays.asList(p.line().getCoordinates())));
            m.put("existing_dn", p.segment.dn);
            m.put("required_dn", Math.max(p.segment.dn, p.requiredDn));
            m.put("added_flow_tph", round(p.addedFlow, 3));
            recon.add(m);
        }
        e.put("recon", recon);
        e.put("connected", connected);
        e.put("network_length", round(length, 2));
    }

    private static List<double[]> coords(List<Coordinate> cs) {
        List<double[]> out = new ArrayList<>(cs.size());
        for (Coordinate c : cs) {
            out.add(new double[]{round(c.x, 3), round(c.y, 3)});
        }
        return out;
    }

    private static double round(double v, int decimals) {
        return ru.lct.heatnet.geo.GeoJsonGeometry.round(v, decimals);
    }
}
