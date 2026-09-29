package ru.lct.heatnet.validate;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.linemerge.LineMerger;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.geo.GeoJsonGeometry;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.input.ObjectType;
import ru.lct.heatnet.input.Obstacle;
import ru.lct.heatnet.input.ObstacleSource;
import ru.lct.heatnet.plan.ReconstructionCalculator;
import ru.lct.heatnet.plan.RoutingContext;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.reference.RestrictionRule;
import ru.lct.heatnet.reference.RuleOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independent check of a result file against the mandatory rules (docs/requirements.md): output schema,
 * network topology, flows and DN, length limit, clearances and special sections, attachment and chamber rules,
 * costs and score. Everything is recomputed here from the input, not taken from the planner.
 */
public final class OutputValidator {

    static final double POS_TOL = 0.05;
    static final double LEN_TOL = 0.05;
    static final double DIST_TOL = 0.02;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, List<String>> ATTRS = new LinkedHashMap<>();
    private static final Map<String, String> GEOM = new LinkedHashMap<>();

    static {
        attrs("heat_network", "LineString", "start_node_id:sn", "end_node_id:sn", "flow_tph:n", "diameter:i",
                "length:n", "laying_method:s", "depth_start:nn", "depth_end:nn", "cost:n");
        attrs("heat_chamber", "Point", "diameter:i", "cost:n");
        attrs("technical_node", "Point");
        attrs("variant_summary", null, "rank:i", "construction_cost:n", "chamber_construction_cost:n",
                "existing_chamber_tie_in_count:i", "existing_chamber_tie_in_cost:n", "unconnected_penalty:n",
                "calculated_cost:n", "new_network_length:n", "score:n", "unconnected_oks_ids:a");
    }

    private static void attrs(String type, String geom, String... names) {
        List<String> l = new ArrayList<>(Arrays.asList("id:s", "object_type:s", "variant_id:s"));
        l.addAll(Arrays.asList(names));
        ATTRS.put(type, l);
        GEOM.put(type, geom);
    }

    // ------------------------------------------------------------------ parsed output

    static final class Out {
        String type;
        String id;
        String variant;
        JsonNode props;
        Geometry utm;

        String s(String f) {
            return props.path(f).asText();
        }

        double n(String f) {
            return props.path(f).asDouble();
        }

        int i(String f) {
            return props.path(f).asInt();
        }
    }

    static final class Seg {
        Out f;
        LineString line;
        /** The part of the segment outside the building of its OKS: the piece inside it is an inlet, not a route. */
        LineString route;
        String start;
        String end;
        double flow;
        int dn;
        double length;
        boolean special;
        double cost;
        double impliedK;
        /** Types of the objects the special section crosses (the largest coefficient of them applies). */
        final Set<String> crossedTypes = new LinkedHashSet<>();
        final List<Geometry> allowedSpecialArea = new ArrayList<>();
        int component = -1;
    }

    private final ReferenceData ref;

    private final RuleOptions rules;

    public OutputValidator(ReferenceData ref) {
        this(ref, RuleOptions.defaults());
    }

    public OutputValidator(ReferenceData ref, RuleOptions rules) {
        this.ref = ref;
        this.rules = rules;
    }

    public ValidationReport validate(LoadedInput input, InputStream output) throws IOException {
        return validate(input.getModel(), input.obstacleSource(), output);
    }

    public ValidationReport validate(InputModel model, ObstacleSource obstacles, InputStream output) throws IOException {
        ValidationReport rep = new ValidationReport();
        List<Out> all = read(output, rep);
        Map<String, List<Out>> byVariant = new TreeMap<>();
        Set<String> ids = new HashSet<>();
        for (Out o : all) {
            if (!ids.add(o.id)) {
                rep.error("OUT-ID", o.variant, o.id, "Duplicate id in the output");
            }
            byVariant.computeIfAbsent(o.variant, k -> new ArrayList<>()).add(o);
        }
        if (byVariant.isEmpty()) {
            rep.error("OUT-EMPTY", "-", null, "No variants in the output");
        }
        if (byVariant.size() > 3) {
            rep.error("R-VAR", "-", null, byVariant.size() + " variants, at most 3 allowed");
        }
        List<double[]> scores = new ArrayList<>();
        Map<String, String> signatures = new LinkedHashMap<>();
        for (Map.Entry<String, List<Out>> e : byVariant.entrySet()) {
            VariantCheck vc = new VariantCheck(model, obstacles, e.getKey(), e.getValue(), rep);
            Out summary = vc.run();
            if (summary != null) {
                scores.add(new double[]{summary.n("score"), summary.i("rank")});
                java.util.Collections.sort(vc.structure);
                List<String> un = new ArrayList<>();
                for (JsonNode x : summary.props.path("unconnected_oks_ids")) {
                    un.add(x.asText());
                }
                java.util.Collections.sort(un);
                String sig = String.join(";", vc.structure) + "|" + String.join(",", un);
                for (Map.Entry<String, String> other : signatures.entrySet()) {
                    if (other.getValue().equals(sig)) {
                        rep.error("R-VAR", e.getKey(), null, "Variant does not differ from variant " + other.getKey()
                                + " in tie-ins or grouping of OKS");
                    }
                }
                signatures.put(e.getKey(), sig);
            }
        }
        rep.checked("R-VAR", signatures.size());
        scores.sort((a, b) -> Double.compare(a[0], b[0]));
        for (int k = 0; k < scores.size(); k++) {
            if ((int) scores.get(k)[1] != k + 1) {
                rep.error("R-RANK", "-", null, "rank " + (int) scores.get(k)[1] + " for the variant with the "
                        + (k + 1) + "-th smallest score");
            }
        }
        return rep;
    }

    private List<Out> read(InputStream in, ValidationReport rep) throws IOException {
        List<Out> out = new ArrayList<>();
        try (JsonParser p = MAPPER.getFactory().createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                rep.error("OUT-FORMAT", "-", null, "Output is not a JSON object");
                return out;
            }
            boolean fc = false;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.getCurrentName();
                JsonToken t = p.nextToken();
                if ("type".equals(field)) {
                    fc = "FeatureCollection".equals(p.getValueAsString());
                } else if ("features".equals(field) && t == JsonToken.START_ARRAY) {
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        Out o = parseFeature(MAPPER.readTree(p), rep);
                        if (o != null) {
                            out.add(o);
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
            if (!fc) {
                rep.error("OUT-FORMAT", "-", null, "type is not FeatureCollection");
            }
        }
        return out;
    }

    private Out parseFeature(JsonNode node, ValidationReport rep) {
        JsonNode props = node.get("properties");
        if (props == null || !props.isObject()) {
            rep.error("OUT-SCHEMA", "-", null, "Feature without properties");
            return null;
        }
        Out o = new Out();
        o.type = props.path("object_type").asText();
        o.id = props.path("id").asText();
        o.variant = props.path("variant_id").asText("?");
        o.props = props;
        List<String> attrs = ATTRS.get(o.type);
        if (attrs == null) {
            rep.error("OUT-SCHEMA", o.variant, o.id, "Unknown object_type '" + o.type + "'");
            return null;
        }
        Set<String> expected = new HashSet<>();
        for (String a : attrs) {
            String[] nt = a.split(":");
            expected.add(nt[0]);
            JsonNode v = props.get(nt[0]);
            boolean ok;
            if (v == null) {
                ok = false;
            } else {
                switch (nt[1]) {
                    case "s":
                        ok = v.isTextual() && !v.asText().isEmpty();
                        break;
                    case "i":
                        ok = v.isIntegralNumber();
                        break;
                    case "n":
                        ok = v.isNumber();
                        break;
                    case "nn":
                        ok = v.isNull() || v.isNumber();
                        break;
                    case "sn":
                        // an identifier of an input object: string or number (appendix 7.2)
                        ok = v.isNumber() || (v.isTextual() && !v.asText().isEmpty());
                        break;
                    default:
                        ok = v.isArray();
                        if (ok) {
                            for (JsonNode x : v) {
                                ok &= x.isNumber() || (x.isTextual() && !x.asText().isEmpty());
                            }
                        }
                }
            }
            if (!ok) {
                rep.error("OUT-SCHEMA", o.variant, o.id, o.type + ": attribute '" + nt[0] + "' missing or of wrong type");
            }
        }
        props.fieldNames().forEachRemaining(name -> {
            if (!expected.contains(name)) {
                rep.error("OUT-SCHEMA", o.variant, o.id, o.type + ": extra attribute '" + name + "'");
            }
        });
        String geomType = GEOM.get(o.type);
        JsonNode g = node.get("geometry");
        if (geomType == null) {
            if (g != null && !g.isNull()) {
                rep.error("OUT-SCHEMA", o.variant, o.id, "variant_summary must have geometry null");
            }
        } else {
            try {
                Geometry wgs = GeoJsonGeometry.read(g, Crs.WGS84_FACTORY);
                if (wgs == null || !geomType.equals(wgs.getGeometryType())) {
                    rep.error("OUT-SCHEMA", o.variant, o.id, o.type + " must be " + geomType);
                    return null;
                }
                org.locationtech.jts.geom.Envelope env = wgs.getEnvelopeInternal();
                if (env.getMinX() < -180 || env.getMaxX() > 180 || env.getMinY() < -90 || env.getMaxY() > 90) {
                    rep.error("OUT-CRS", o.variant, o.id, "Coordinates are not EPSG:4326 lon/lat");
                    return null;
                }
                o.utm = Crs.toUtm(wgs);
            } catch (RuntimeException ex) {
                rep.error("OUT-SCHEMA", o.variant, o.id, "Bad geometry: " + ex.getMessage());
                return null;
            }
        }
        return o;
    }

    // ------------------------------------------------------------------ one variant

    private final class VariantCheck {
        final InputModel model;
        final String v;
        final List<Out> features;
        final ValidationReport rep;
        final List<Seg> segs = new ArrayList<>();
        /** Nodes where a part of the new network joins the existing one: node id -> existing object id. */
        final Map<String, String> attachTo = new LinkedHashMap<>();
        /** Of them, the ones that are existing chambers (paid as tie-ins). */
        final Set<String> attachChamber = new LinkedHashSet<>();
        /** Candidates that really are the root of a part of the new network. */
        final Set<String> attachRoots = new LinkedHashSet<>();
        final Map<String, Out> newChambers = new LinkedHashMap<>();
        final Map<String, Out> techNodes = new LinkedHashMap<>();
        final Map<String, InputModel.Oks> oksByCp = new HashMap<>();
        final Map<String, InputModel.Oks> oksById = new LinkedHashMap<>();
        Out summary;
        final List<String> structure = new ArrayList<>();
        /** node id -> cluster id (tie-in and the new chamber at the same point form one cluster). */
        final Map<String, String> cluster = new HashMap<>();
        final Map<String, Coordinate> nodePos = new HashMap<>();
        /** new segment -> connection points it feeds; the building of such an OKS is not an obstacle for it. */
        final Map<Seg, Set<String>> feeds = new HashMap<>();
        final ObstacleSource obstacles;

        VariantCheck(InputModel model, ObstacleSource obstacles, String v, List<Out> features, ValidationReport rep) {
            this.model = model;
            this.v = v;
            this.features = features;
            this.rep = rep;
            this.obstacles = obstacles;
        }

        Out run() {
            for (InputModel.Oks o : model.getOks()) {
                oksByCp.put(o.connectionPointId, o);
                oksById.put(o.id, o);
            }
            for (Out o : features) {
                switch (o.type) {
                    case "heat_network":
                        segs.add(seg(o));
                        break;
                    case "heat_chamber":
                        newChambers.put(o.id, o);
                        break;
                    case "technical_node":
                        techNodes.put(o.id, o);
                        break;
                    default:
                        if (summary != null) {
                            rep.error("R-SUM", v, o.id, "More than one variant_summary");
                        }
                        summary = o;
                }
            }
            if (summary == null) {
                rep.error("R-SUM", v, null, "No variant_summary");
            }
            nodes();
            routeGeometry();
            Map<Integer, List<Seg>> comps = topology();
            restrictions();
            crossingsBetweenNewSegments();
            attachments();
            chambers();
            if (summary != null) {
                summary(comps);
            }
            return summary;
        }

        Seg seg(Out o) {
            Seg s = new Seg();
            s.f = o;
            s.line = (LineString) o.utm;
            s.start = o.s("start_node_id");
            s.end = o.s("end_node_id");
            s.flow = o.n("flow_tph");
            s.dn = o.i("diameter");
            s.length = o.n("length");
            s.special = "special".equals(o.s("laying_method"));
            s.cost = o.n("cost");
            if (!"base".equals(o.s("laying_method")) && !s.special) {
                rep.error("OUT-SCHEMA", v, o.id, "laying_method must be base or special");
            }
            if (!o.props.path("depth_start").isNull() || !o.props.path("depth_end").isNull()) {
                rep.error("OUT-SCHEMA", v, o.id, "depth_start/depth_end must be null in the 2D task");
            }
            if (Math.abs(s.length - s.line.getLength()) > LEN_TOL) {
                rep.error("R-LEN", v, o.id, String.format("length %.3f != geometry length %.3f", s.length,
                        s.line.getLength()));
            }
            if (!ref.isKnownDn(s.dn)) {
                rep.error("R-NET-9", v, o.id, "diameter " + s.dn + " is not in table 1");
                s.impliedK = 1;
            } else {
                s.impliedK = s.cost / (s.length * ref.pipe(s.dn).getNewCostPerM());
            }
            return s;
        }

        // ---------------------------------------------------------- nodes and topology

        void nodes() {
            for (InputModel.Chamber c : model.getChambers().values()) {
                nodePos.put(c.id, c.point.getCoordinate());
                cluster.put(c.id, c.id);
            }
            for (Out c : newChambers.values()) {
                nodePos.put(c.id, c.utm.getCoordinate());
                cluster.put(c.id, c.id);
            }
            // a part of the new network joins the existing one either in an existing chamber or through a new
            // chamber standing right on an existing segment (appendix 2.4)
            for (InputModel.Chamber c : model.getChambers().values()) {
                attachTo.put(c.id, c.id);
                attachChamber.add(c.id);
            }
            for (Out c : newChambers.values()) {
                for (InputModel.Segment s : model.getSegments().values()) {
                    if (s.line.distance(c.utm) <= POS_TOL) {
                        attachTo.put(c.id, s.id);
                        break;
                    }
                }
            }
            for (Out n : techNodes.values()) {
                nodePos.put(n.id, n.utm.getCoordinate());
                cluster.put(n.id, n.id);
            }
            for (InputModel.Oks o : model.getOks()) {
                nodePos.put(o.connectionPointId, o.connectionPoint.getCoordinate());
                cluster.put(o.connectionPointId, o.connectionPointId);
            }
        }

        Map<Integer, List<Seg>> topology() {
            Map<String, List<Seg>> adj = new LinkedHashMap<>();
            for (Seg s : segs) {
                boolean ok = true;
                for (String[] end : new String[][]{{s.start, "0"}, {s.end, "1"}}) {
                    Coordinate p = nodePos.get(end[0]);
                    if (p == null) {
                        rep.error("R-TOPO", v, s.f.id, "Unknown node '" + end[0] + "'");
                        ok = false;
                        continue;
                    }
                    Coordinate c = "0".equals(end[1]) ? s.line.getCoordinateN(0)
                            : s.line.getCoordinateN(s.line.getNumPoints() - 1);
                    if (c.distance(p) > POS_TOL) {
                        rep.error("R-TOPO", v, s.f.id, String.format("Segment end is %.3f m from node %s",
                                c.distance(p), end[0]));
                    }
                }
                if (!ok) {
                    continue;
                }
                adj.computeIfAbsent(cluster.get(s.start), k -> new ArrayList<>()).add(s);
                adj.computeIfAbsent(cluster.get(s.end), k -> new ArrayList<>()).add(s);
            }
            rep.checked("R-TOPO", segs.size());
            // components
            Map<Integer, List<Seg>> comps = new LinkedHashMap<>();
            Map<String, Integer> compOf = new HashMap<>();
            int nComp = 0;
            for (String start : adj.keySet()) {
                if (compOf.containsKey(start)) {
                    continue;
                }
                int id = nComp++;
                Deque<String> dq = new ArrayDeque<>();
                dq.add(start);
                compOf.put(start, id);
                Set<Seg> cs = new LinkedHashSet<>();
                while (!dq.isEmpty()) {
                    String n = dq.poll();
                    for (Seg s : adj.get(n)) {
                        cs.add(s);
                        s.component = id;
                        for (String m : new String[]{cluster.get(s.start), cluster.get(s.end)}) {
                            if (!compOf.containsKey(m)) {
                                compOf.put(m, id);
                                dq.add(m);
                            }
                        }
                    }
                }
                comps.put(id, new ArrayList<>(cs));
            }
            Set<String> reachedCp = new HashSet<>();
            for (Map.Entry<Integer, List<Seg>> e : comps.entrySet()) {
                int id = e.getKey();
                Set<String> nodes = new HashSet<>();
                for (Map.Entry<String, Integer> ce : compOf.entrySet()) {
                    if (ce.getValue() == id) {
                        nodes.add(ce.getKey());
                    }
                }
                List<String> tieClusters = new ArrayList<>();
                for (String n : nodes) {
                    if (attachTo.containsKey(n)) {
                        tieClusters.add(n);
                    }
                }
                String first = e.getValue().get(0).f.id;
                if (e.getValue().size() != nodes.size() - 1) {
                    rep.error("R-NET-4", v, first, "Part of the new network is not a tree (" + e.getValue().size()
                            + " segments, " + nodes.size() + " nodes)");
                }
                if (tieClusters.size() != 1) {
                    rep.error("R-NET-3", v, first, "Part of the new network has " + tieClusters.size()
                            + " tie-ins, exactly 1 required");
                    continue;
                }
                attachRoots.add(tieClusters.get(0));
                flowsAndDiameters(tieClusters.get(0), adj, e.getValue());
                for (String n : nodes) {
                    List<Seg> deg = adj.get(n);
                    if (oksByCp.containsKey(n)) {
                        if (!reachedCp.add(n)) {
                            rep.error("R-TOPO", v, n, "Connection point in several parts of the network");
                        }
                        if (deg.size() != 1) {
                            rep.error("R-TOPO", v, n, "Connection point must be a leaf, degree " + deg.size());
                        }
                    } else if (techNodes.containsKey(n)) {
                        technicalNode(n, deg);
                    } else if (!newChambers.containsKey(n) && !model.getChambers().containsKey(n)) {
                        rep.error("R-TOPO", v, n, "Node is neither a chamber, technical node nor "
                                + "connection point");
                    }
                    boolean chamberHere = isChamberCluster(n);
                    if (deg.size() >= 3 && !chamberHere) {
                        rep.error("R-NET-5", v, n, "Branching outside a heat chamber");
                    }
                    if (newChambers.containsKey(n) && deg.size() > ref.getMaxChamberDegree()) {
                        rep.error("R-NET-5", v, n, "Branching chamber with " + deg.size() + " segments, at most "
                                + ref.getMaxChamberDegree());
                    }
                }
                // structure signature: tie-in object and the OKS connected through it (ТЗ 2.8)
                List<String> cps = new ArrayList<>();
                for (String nd : nodes) {
                    if (oksByCp.containsKey(nd)) {
                        cps.add(oksByCp.get(nd).id);
                    }
                }
                java.util.Collections.sort(cps);
                structure.add(attachTo.get(tieClusters.get(0)) + ":" + String.join(",", cps));
            }
            // every OKS is either connected or listed as unconnected
            Set<String> unconnected = new LinkedHashSet<>();
            if (summary != null) {
                for (JsonNode x : summary.props.path("unconnected_oks_ids")) {
                    unconnected.add(x.asText());
                }
            }
            for (InputModel.Oks o : model.getOks()) {
                boolean connected = reachedCp.contains(o.connectionPointId);
                boolean listed = unconnected.contains(o.id);
                if (connected == listed) {
                    rep.error("R-NET-1", v, o.id, connected ? "OKS is connected and also listed as unconnected"
                            : "OKS is neither connected nor listed as unconnected");
                }
            }
            for (String id : model.getUnroutableOks().keySet()) {
                if (!unconnected.contains(id)) {
                    rep.error("R-NET-1", v, id, "OKS without connection data is not listed as unconnected");
                }
            }
            rep.checked("R-NET-1", model.getOks().size());
            return comps;
        }

        boolean isChamberCluster(String n) {
            if (newChambers.containsKey(n)) {
                return true;
            }
            for (Map.Entry<String, String> e : cluster.entrySet()) {
                if (e.getValue().equals(n) && newChambers.containsKey(e.getKey())) {
                    return true;
                }
            }
            return model.getChambers().containsKey(n);
        }

        void technicalNode(String n, List<Seg> deg) {
            if (deg.size() != 2) {
                rep.error("R-NET-11", v, n, "Technical node must join exactly 2 segments, has " + deg.size());
                return;
            }
            Seg a = deg.get(0);
            Seg b = deg.get(1);
            boolean differ = a.dn != b.dn || a.special != b.special || Math.abs(a.impliedK - b.impliedK) > 1e-3;
            if (!differ) {
                rep.warning("R-NET-11", v, n, "Technical node between segments with equal parameters");
            }
        }

        /**
         * Turns of the new network: an arbitrary angle up to 90° inclusive is allowed, a sharper one is not
         * (appendix 2.1, clarification 5). A turn costs nothing extra. Checked inside a segment and at a
         * technical node, where two segments meet.
         */
        void turns(List<Seg> comp, Map<String, List<Seg>> adj, Map<String, Seg> parentSeg) {
            int count = 0;
            for (Seg s : comp) {
                Coordinate[] cs = s.line.getCoordinates();
                for (int k = 1; k + 1 < cs.length; k++) {
                    count++;
                    double deg = deflectionDeg(cs[k - 1], cs[k], cs[k + 1]);
                    if (!ref.isAllowedTurn(deg)) {
                        rep.error("R-TURN", v, s.f.id, String.format("turn of %.1f° is sharper than %.0f°",
                                deg, ref.getMaxTurnDeg()));
                    }
                }
            }
            for (Map.Entry<String, Seg> e : parentSeg.entrySet()) {
                String n = e.getKey();
                if (!techNodes.containsKey(n)) {
                    continue;
                }
                Seg up = e.getValue();
                Coordinate at = nodePos.get(n);
                Coordinate[] a = towards(up.line, at);
                for (Seg s : adj.get(n)) {
                    if (s == up) {
                        continue;
                    }
                    Coordinate[] b = towards(s.line, at);
                    count++;
                    if (a.length >= 2 && b.length >= 2) {
                        double deg = deflectionDeg(a[a.length - 2], at, b[b.length - 2]);
                        if (!ref.isAllowedTurn(deg)) {
                            rep.error("R-TURN", v, s.f.id, String.format(
                                    "turn of %.1f° at technical node %s is sharper than %.0f°",
                                    deg, n, ref.getMaxTurnDeg()));
                        }
                    }
                }
            }
            rep.checked("R-TURN", count);
        }

        /** Coordinates of a line ordered so that it ends at the point (nearest end). */
        private Coordinate[] towards(LineString line, Coordinate at) {
            Coordinate[] cs = line.getCoordinates();
            if (cs[0].distance(at) < cs[cs.length - 1].distance(at)) {
                Coordinate[] r = new Coordinate[cs.length];
                for (int i = 0; i < cs.length; i++) {
                    r[i] = cs[cs.length - 1 - i];
                }
                return r;
            }
            return cs;
        }

        void flowsAndDiameters(String root, Map<String, List<Seg>> adj, List<Seg> comp) {
            // orient from the tie-in, collect OKS flow of every subtree
            Map<Seg, String> down = new HashMap<>();
            Deque<String> order = new ArrayDeque<>();
            Deque<String> dq = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            dq.add(root);
            seen.add(root);
            Map<String, Seg> parentSeg = new HashMap<>();
            while (!dq.isEmpty()) {
                String n = dq.poll();
                order.push(n);
                for (Seg s : adj.get(n)) {
                    String other = cluster.get(s.start).equals(n) ? cluster.get(s.end) : cluster.get(s.start);
                    if (seen.add(other)) {
                        down.put(s, other);
                        parentSeg.put(other, s);
                        dq.add(other);
                    }
                }
            }
            turns(comp, adj, parentSeg);
            Map<String, Double> subtree = new HashMap<>();
            Map<String, Set<String>> subtreeCps = new HashMap<>();
            while (!order.isEmpty()) {
                String n = order.pop();
                double f = oksByCp.containsKey(n) ? oksByCp.get(n).flowTph : 0;
                Set<String> cpsBelow = new HashSet<>();
                if (oksByCp.containsKey(n)) {
                    cpsBelow.add(n);
                }
                for (Seg s : adj.get(n)) {
                    if (n.equals(down.get(s)) || !down.containsKey(s)) {
                        continue;
                    }
                    f += subtree.getOrDefault(down.get(s), 0.0);
                    cpsBelow.addAll(subtreeCps.getOrDefault(down.get(s), Collections.emptySet()));
                }
                subtree.put(n, f);
                subtreeCps.put(n, cpsBelow);
            }
            for (Seg s : comp) {
                if (down.containsKey(s)) {
                    feeds.put(s, subtreeCps.getOrDefault(down.get(s), Collections.emptySet()));
                }
            }
            for (Seg s : comp) {
                String d = down.get(s);
                if (d == null) {
                    continue;
                }
                double expected = subtree.get(d);
                if (Math.abs(expected - s.flow) > 1e-4 + 1e-6 * expected) {
                    rep.error("R-NET-8", v, s.f.id, String.format("flow_tph %.6f, expected %.6f (sum of OKS "
                            + "downstream)", s.flow, expected));
                }
                PipeSpec min = ref.minPipeForFlow(s.flow);
                if (min == null || s.dn < min.getDn()) {
                    rep.error("R-NET-9", v, s.f.id, "DN " + s.dn + " is too small for " + s.flow + " t/h");
                }
            }
            rep.checked("R-NET-8", comp.size());
            // The DN never decreases towards the place of attachment (appendix 2.3, clarification 1).
            for (Seg s : comp) {
                String d = down.get(s);
                if (d == null) {
                    continue;
                }
                for (Seg below : adj.getOrDefault(d, Collections.<Seg>emptyList())) {
                    if (below != s && below.dn > s.dn) {
                        rep.error("R-NET-9", v, s.f.id, "DN " + s.dn + " is smaller than DN " + below.dn
                                + " of segment " + below.f.id + " farther from the attachment");
                    }
                }
            }
            // The length limit is checked along every single path from a connection point to the place of
            // attachment: a shared segment counts in each path, parallel branches never add up
            // (appendix 2.3, clarification 2).
            Map<Seg, Double> run = new HashMap<>();
            for (String cp : oksByCp.keySet()) {
                if (!adj.containsKey(cp)) {
                    continue;
                }
                List<Seg> path = new ArrayList<>();
                String cur = cp;
                Seg up = parentSeg.get(cur);
                while (up != null) {
                    path.add(up);
                    cur = cluster.get(up.start).equals(cur) ? cluster.get(up.end) : cluster.get(up.start);
                    up = parentSeg.get(cur);
                }
                int i = 0;
                while (i < path.size()) {
                    int dn = path.get(i).dn;
                    double total = 0;
                    int k = i;
                    while (k < path.size() && path.get(k).dn == dn) {
                        total += path.get(k).length;
                        k++;
                    }
                    for (int m = i; m < k; m++) {
                        run.merge(path.get(m), total, Math::max);
                    }
                    i = k;
                }
            }
            for (Map.Entry<Seg, Double> e : run.entrySet()) {
                Seg s = e.getKey();
                if (!ref.isKnownDn(s.dn)) {
                    continue;
                }
                double limit = ref.pipe(s.dn).getMaxLengthM();
                if (e.getValue() > limit + LEN_TOL) {
                    rep.error("R-NET-10", v, s.f.id, String.format("Continuous DN %d part of a path is %.1f m "
                            + "> limit %.0f m", s.dn, e.getValue(), limit));
                }
            }
            // A DN above the minimal one is allowed only where the minimal one would break the length limit
            // (appendix 2.3): raising it for any other reason is not.
            for (Seg s : comp) {
                PipeSpec min = ref.minPipeForFlow(s.flow);
                if (min == null || s.dn <= min.getDn()) {
                    continue;
                }
                if (run.getOrDefault(s, 0.0) <= min.getMaxLengthM() + LEN_TOL && !raisedBelow(s, adj, down, min)) {
                    rep.warning("R-NET-9", v, s.f.id, "DN " + s.dn + " is larger than needed (" + min.getDn() + ")");
                }
            }
            rep.checked("R-NET-10", comp.size());
        }

        /** A wider DN is justified when a segment farther from the attachment already has it. */
        boolean raisedBelow(Seg s, Map<String, List<Seg>> adj, Map<Seg, String> down, PipeSpec min) {
            String d = down.get(s);
            if (d == null) {
                return false;
            }
            for (Seg below : adj.getOrDefault(d, Collections.<Seg>emptyList())) {
                if (below != s && below.dn >= s.dn) {
                    return true;
                }
            }
            return false;
        }

        // ---------------------------------------------------------- restrictions

        Set<Coordinate> routeEndpoints() {
            Set<Coordinate> pts = new HashSet<>();
            for (String n : attachTo.keySet()) {
                Coordinate c = nodePos.get(n);
                if (c != null) {
                    pts.add(c);
                }
            }
            for (Seg s : segs) {
                // the outdoor part of a route ends where it leaves the building of its OKS, not at the
                // connection point inside it: the clearance may be broken only around that end
                if (oksByCp.containsKey(s.end)) {
                    pts.add(s.route.isEmpty() ? nodePos.get(s.end)
                            : s.route.getCoordinateN(s.route.getNumPoints() - 1));
                }
                if (oksByCp.containsKey(s.start)) {
                    pts.add(s.route.isEmpty() ? nodePos.get(s.start) : s.route.getCoordinateN(0));
                }
            }
            return pts;
        }

        /** The obstacle is the building of an OKS this segment feeds. */
        boolean feedsBuilding(Seg s, String obstacleId) {
            for (String cp : feeds.getOrDefault(s, Collections.emptySet())) {
                InputModel.Oks o = oksByCp.get(cp);
                if (o != null && obstacleId.equals(o.ownBuildingId)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The part of every segment that is a route: the piece from the outline of the building into the
         * connection point inside it is an inlet of the consumer and is not checked against restrictions
         * (the planner does not check it either).
         */
        void routeGeometry() {
            for (Seg s : segs) {
                s.route = s.line;
                for (String node : new String[]{s.end, s.start}) {
                    InputModel.Oks o = oksByCp.get(node);
                    if (o == null || !o.connectionInside() || o.polygon == null) {
                        continue;
                    }
                    Coordinate[] cs = s.line.getCoordinates();
                    boolean atEnd = node.equals(s.end);
                    Coordinate outline = atEnd ? cs[cs.length - 2] : cs[1];
                    if (o.polygon.getBoundary().distance(Crs.UTM_FACTORY.createPoint(outline)) > 0.6) {
                        rep.error("R-RESTR", v, s.f.id, "Entry into OKS " + o.id + " does not start at its outline");
                    }
                    s.route = cs.length > 2
                            ? Crs.UTM_FACTORY.createLineString(atEnd ? java.util.Arrays.copyOf(cs, cs.length - 1)
                                    : java.util.Arrays.copyOfRange(cs, 1, cs.length))
                            : Crs.UTM_FACTORY.createLineString(new Coordinate[0]);
                }
            }
        }

        void restrictions() {
            Set<Coordinate> endpoints = routeEndpoints();
            List<Special> specials = new ArrayList<>();
            Map<String, Special> specialById = new HashMap<>();
            for (Seg s : segs) {
                if (s.route.isEmpty()) {
                    continue; // the whole segment is the inlet into the building
                }
                Geometry around = s.route.buffer(20);
                PipeSpec pipe = ref.isKnownDn(s.dn) ? ref.pipe(s.dn) : ref.pipeAtLeast(s.dn);
                InputModel.Oks ownOks = oksByCp.containsKey(s.end) ? oksByCp.get(s.end) : oksByCp.get(s.start);
                String ownBuilding = ownOks == null ? null : ownOks.ownBuildingId;
                for (Obstacle o : obstacles.intersecting(around)) {
                    if (o.getId().equals(ownBuilding)) {
                        continue; // the building of this OKS: checked below as its own OKS
                    }
                    if (feedsBuilding(s, o.getId())) {
                        // the building this segment feeds: no clearance to it, but the route may not go through it
                        if (o.getGeometry() instanceof Polygonal && s.route.intersects(o.getGeometry().buffer(-0.05))) {
                            rep.error("R-RESTR", v, s.f.id, "Segment goes through building " + o.getId()
                                    + " of the OKS it feeds");
                        }
                        continue;
                    }
                    RestrictionRule r = o.getObjectType() == ObjectType.RESTRICTION
                            ? ref.restriction(o.getRestrictionType()) : null;
                    if (r != null && !r.isForbidden()) {
                        specialById.computeIfAbsent(o.getId(), k -> new Special(o.getId(), o.getRestrictionType(),
                                r, o.getGeometry(), r.getObjectWidthM() / 2.0));
                        continue;
                    }
                    double clearance = r == null ? ref.buildingDistance(s.dn) + pipe.getHalfWidthM()
                            : r.getMinDistanceM() + pipe.getHalfWidthM() + r.getObjectWidthM() / 2.0;
                    forbidden(s, o.getId(), o.getGeometry(), clearance, endpoints);
                }
                for (InputModel.Oks other : model.getOks()) {
                    if (other.polygon == null || !other.polygon.intersects(around)) {
                        continue;
                    }
                    if (other.ownBuildingId != null && other != ownOks) {
                        continue; // an existing building: already checked among the obstacles
                    }
                    // oks_future polygons (earlier input format) are not obstacles; OKS outlines of the current
                    // format come as restrictions of type oks and are obstacles (appendix 2.2 and 4, clarification 3)
                    boolean own = other.connectionPointId.equals(s.end) || other.connectionPointId.equals(s.start);
                    // s.route is the segment without the inlet piece: the route itself must stay outside
                    if (own && !s.route.isEmpty() && s.route.intersects(other.polygon.buffer(-0.05))) {
                        rep.error("R-RESTR", v, s.f.id, "Segment enters its own OKS " + other.id);
                    }
                }
                for (InputModel.Segment e : model.segmentsNear(around.getEnvelopeInternal())) {
                    specialById.computeIfAbsent(e.id, k -> new Special(e.id, "heat_network",
                            ref.restriction("heat_network"), e.line, ref.pipeAtLeast(e.dn).getHalfWidthM()));
                }
            }
            specials.addAll(specialById.values());
            rep.checked("R-RESTR", segs.size());
            for (Special sp : specials) {
                special(sp, endpoints);
            }
            for (Seg s : segs) {
                if (s.special) {
                    if (s.crossedTypes.isEmpty()) {
                        rep.error("R-SPEC", v, s.f.id, "Special section does not cross any object");
                        continue;
                    }
                    double k = 1;
                    for (String t : s.crossedTypes) {
                        k = Math.max(k, ref.restriction(t).getK());
                    }
                    if (Math.abs(s.impliedK - k) > 1e-3) {
                        rep.error("R-COST", v, s.f.id, String.format("cost implies K=%.4f, expected %.2f for %s",
                                s.impliedK, k, s.crossedTypes));
                    }
                    Geometry allowed = UnaryUnionOp.union(s.allowedSpecialArea);
                    double outside = s.route.difference(allowed).getLength();
                    if (outside > LEN_TOL) {
                        rep.error("R-SPEC", v, s.f.id, String.format("Special section exceeds its bounds by %.2f m",
                                outside));
                    }
                } else if (Math.abs(s.impliedK - 1) > 1e-4) {
                    rep.error("R-COST", v, s.f.id, String.format(
                            "cost %.2f implies K=%.4f, expected 1.00", s.cost, s.impliedK));
                }
            }
            rep.checked("R-SPEC", specials.size());
        }

        void forbidden(Seg s, String objectId, Geometry raw, double clearance, Set<Coordinate> endpoints) {
            double d = s.route.distance(raw);
            if (d >= clearance - DIST_TOL) {
                return;
            }
            Geometry rest = s.route;
            for (Coordinate c : new Coordinate[]{s.route.getCoordinateN(0),
                    s.route.getCoordinateN(s.route.getNumPoints() - 1)}) {
                if (containsCoord(endpoints, c)) {
                    rest = rest.difference(Crs.UTM_FACTORY.createPoint(c).buffer(RoutingContext.escapeRadius(clearance)));
                }
            }
            boolean core = raw instanceof Polygonal && s.route.intersects(raw.buffer(-0.05));
            if (!core && (rest.isEmpty() || rest.distance(raw) >= clearance - DIST_TOL)) {
                rep.warning("R-RESTR", v, s.f.id, String.format("Route end is %.2f m from %s (clearance %.2f m): "
                        + "the connection point or tie-in itself lies within the clearance", d, objectId, clearance));
            } else {
                rep.error("R-RESTR", v, s.f.id, String.format("%.2f m from %s, clearance %.2f m required", d,
                        objectId, clearance));
            }
        }

        final class Special {
            final String id;
            final String type;
            final RestrictionRule rule;
            final Geometry raw;
            final Geometry boundary;
            final double objHalf;
            final boolean areal;

            Special(String id, String type, RestrictionRule rule, Geometry raw, double objHalf) {
                this.id = id;
                this.type = type;
                this.rule = rule;
                this.raw = raw;
                this.objHalf = objHalf;
                this.areal = raw instanceof Polygonal;
                this.boundary = areal ? raw.getBoundary() : raw;
            }

            ru.lct.heatnet.plan.PolygonBoundary boundaryOfPolygon;

            ru.lct.heatnet.plan.PolygonBoundary boundary() {
                if (boundaryOfPolygon == null) {
                    boundaryOfPolygon = new ru.lct.heatnet.plan.PolygonBoundary(raw);
                }
                return boundaryOfPolygon;
            }
        }

        private double lineAngleDeg(Coordinate a, Coordinate b, Coordinate c, Coordinate d) {
            double ux = b.x - a.x;
            double uy = b.y - a.y;
            double vx = d.x - c.x;
            double vy = d.y - c.y;
            double cos = Math.abs(ux * vx + uy * vy) / (Math.hypot(ux, uy) * Math.hypot(vx, vy));
            return Math.toDegrees(Math.acos(Math.min(1, cos)));
        }

        /** Tie-in points that lie on this existing object: touching it there is not a crossing. */
        List<Coordinate> tiePointsOn(Special sp) {
            List<Coordinate> out = new ArrayList<>();
            if (!"heat_network".equals(sp.type)) {
                return out;
            }
            for (String n : attachTo.keySet()) {
                Coordinate c = nodePos.get(n);
                if (c != null && sp.raw.distance(Crs.UTM_FACTORY.createPoint(c)) <= POS_TOL + 0.5) {
                    out.add(c);
                }
            }
            return out;
        }

        void special(Special sp, Set<Coordinate> endpoints) {
            List<Coordinate> tiePts = tiePointsOn(sp);
            List<LineString> specialLines = new ArrayList<>();
            List<Coordinate> crossingPts = new ArrayList<>();
            for (Seg s : segs) {
                if (s.route.isEmpty()) {
                    continue;
                }
                Geometry x = s.route.intersection(sp.raw);
                if (x.isEmpty()) {
                    continue;
                }
                boolean onlyTie = true;
                for (Coordinate c : x.getCoordinates()) {
                    boolean atTie = false;
                    for (Coordinate t : tiePts) {
                        atTie |= c.distance(t) <= POS_TOL + 0.01;
                    }
                    onlyTie &= atTie;
                }
                if (onlyTie && !tiePts.isEmpty()) {
                    continue;
                }
                crossingPts.addAll(Arrays.asList(x.getCoordinates()));
                if (!s.special) {
                    rep.error("R-SPEC", v, s.f.id, "Crosses " + sp.type + " " + sp.id + " without a special section");
                    continue;
                }
                s.crossedTypes.add(sp.type);
                specialLines.add(s.route);
                if (sp.rule.getBounds() == RestrictionRule.Bounds.AREA_PLUS) {
                    s.allowedSpecialArea.add(sp.raw.buffer(sp.rule.getBoundsM() + LEN_TOL));
                } else {
                    for (Coordinate c : x.getCoordinates()) {
                        s.allowedSpecialArea.add(Crs.UTM_FACTORY.createPoint(c).buffer(sp.rule.getBoundsM() + LEN_TOL));
                    }
                }
                if (sp.rule.getMinAngleDeg() > 0) {
                    // against the line of a linear restriction; for a polygon — against the boundary the route
                    // passes through (appendix 4, clarification 6)
                    Coordinate[] cs = s.route.getCoordinates();
                    for (int k = 0; k + 1 < cs.length; k++) {
                        List<Double> angles = new ArrayList<>();
                        if (sp.areal) {
                            angles.addAll(sp.boundary().crossingAngles(cs[k], cs[k + 1]));
                        } else {
                            LineString piece = Crs.UTM_FACTORY.createLineString(new Coordinate[]{cs[k], cs[k + 1]});
                            for (Coordinate c : piece.intersection(sp.raw).getCoordinates()) {
                                Coordinate[] ab = segmentAt((LineString) sp.raw.getGeometryN(0), c);
                                angles.add(lineAngleDeg(cs[k], cs[k + 1], ab[0], ab[1]));
                            }
                        }
                        for (double ang : angles) {
                            if (ang + 0.5 < sp.rule.getMinAngleDeg()) {
                                rep.error("R-ANGLE", v, s.f.id, String.format("Crosses %s %s at %.1f° < %.0f°",
                                        sp.type, sp.id, ang, sp.rule.getMinAngleDeg()));
                            }
                        }
                    }
                }
            }
            // the whole bounded stretch around each crossing must be special
            if (!crossingPts.isEmpty()) {
                Geometry covered = specialLines.isEmpty() ? null
                        : UnaryUnionOp.union(new ArrayList<Geometry>(specialLines)).buffer(0.02);
                List<Geometry> bandParts = new ArrayList<>();
                if (sp.rule.getBounds() == RestrictionRule.Bounds.AREA_PLUS) {
                    bandParts.add(sp.raw.buffer(Math.max(0, sp.rule.getBoundsM() - LEN_TOL)));
                } else {
                    for (Coordinate c : crossingPts) {
                        bandParts.add(Crs.UTM_FACTORY.createPoint(c).buffer(Math.max(0, sp.rule.getBoundsM() - LEN_TOL)));
                    }
                }
                Geometry band = UnaryUnionOp.union(bandParts);
                for (Seg s : segs) {
                    if (s.special) {
                        continue;
                    }
                    Geometry in = s.route.intersection(band);
                    for (int i = 0; i < in.getNumGeometries(); i++) {
                        Geometry piece = in.getGeometryN(i);
                        if (piece.getLength() > LEN_TOL && piece.buffer(0.05).intersects(sp.raw)
                                && (covered == null || piece.difference(covered).getLength() > LEN_TOL)) {
                            boolean atTie = false;
                            for (Coordinate t : tiePts) {
                                atTie |= piece.distance(Crs.UTM_FACTORY.createPoint(t)) <= POS_TOL + 0.01;
                            }
                            if (!atTie) {
                                rep.error("R-SPEC", v, s.f.id, "Special section around " + sp.type + " " + sp.id
                                        + " is shorter than required by table 2");
                            }
                        }
                    }
                }
            }
            // no running alongside closer than the minimal distance except at crossings / route ends
            List<Geometry> pieces = new ArrayList<>();
            for (Seg s : segs) {
                PipeSpec pipe = ref.isKnownDn(s.dn) ? ref.pipe(s.dn) : ref.pipeAtLeast(s.dn);
                double corridor = sp.rule.getMinDistanceM() + pipe.getHalfWidthM() + sp.objHalf - DIST_TOL;
                if (!s.route.isWithinDistance(sp.raw, corridor)) {
                    continue;
                }
                Geometry in = s.route.intersection(sp.raw.buffer(corridor, 8));
                for (int i = 0; i < in.getNumGeometries(); i++) {
                    if (in.getGeometryN(i).getLength() > 1e-6) {
                        pieces.add(in.getGeometryN(i));
                    }
                }
            }
            if (pieces.isEmpty()) {
                return;
            }
            LineMerger lm = new LineMerger();
            lm.add(pieces);
            @SuppressWarnings("unchecked")
            Collection<LineString> merged = lm.getMergedLineStrings();
            for (LineString piece : merged) {
                boolean crosses = piece.buffer(0.01).intersects(sp.raw) && !onlyTouchesAtTies(piece, sp, tiePts);
                boolean end = false;
                for (Coordinate c : endpoints) {
                    end |= piece.isWithinDistance(Crs.UTM_FACTORY.createPoint(c), POS_TOL);
                }
                if (!crosses && !end) {
                    rep.error("R-RESTR", v, null, String.format("Route runs %.1f m along %s %s closer than %.1f m",
                            piece.getLength(), sp.type, sp.id, sp.rule.getMinDistanceM()));
                }
            }
        }

        boolean onlyTouchesAtTies(LineString piece, Special sp, List<Coordinate> tiePts) {
            if (tiePts.isEmpty()) {
                return false;
            }
            Geometry x = piece.buffer(0.01).intersection(sp.raw);
            for (Coordinate c : x.getCoordinates()) {
                boolean near = false;
                for (Coordinate t : tiePts) {
                    near |= c.distance(t) <= POS_TOL + 0.05;
                }
                if (!near) {
                    return false;
                }
            }
            return true;
        }

        void crossingsBetweenNewSegments() {
            for (int i = 0; i < segs.size(); i++) {
                for (int j = i + 1; j < segs.size(); j++) {
                    Seg a = segs.get(i);
                    Seg b = segs.get(j);
                    if (a.route.isEmpty() || b.route.isEmpty()
                            || !a.route.getEnvelopeInternal().intersects(b.route.getEnvelopeInternal())) {
                        continue;
                    }
                    Geometry x = a.route.intersection(b.route);
                    for (Coordinate c : x.getCoordinates()) {
                        // meeting at a common node location (e.g. two tie-ins into one existing chamber)
                        boolean sharedNode = isEndpoint(a.route, c) && isEndpoint(b.route, c);
                        if (!sharedNode) {
                            rep.error("R-NET-7", v, a.f.id, "Intersects " + b.f.id + " outside a common node");
                            break;
                        }
                    }
                }
            }
            rep.checked("R-NET-7", segs.size());
        }

        // ---------------------------------------------------------- attachments and chambers

        final Map<String, Integer> chamberBranches = new HashMap<>();
        int tieInCount;

        /**
         * How the new network joins the existing one (appendix 2.4, clarifications 11–13): either an existing
         * chamber with room for the new segments, or a new chamber standing right on an existing segment and
         * farther than 10 m from every existing chamber that still has a free branch.
         */
        void attachments() {
            for (Map.Entry<String, String> e : attachTo.entrySet()) {
                String node = e.getKey();
                if (!attachRoots.contains(node)) {
                    continue;
                }
                Coordinate p = nodePos.get(node);
                int nNew = 0;
                for (Seg s : segs) {
                    if (node.equals(s.start) || node.equals(s.end)) {
                        nNew++;
                    }
                }
                if (nNew == 0) {
                    continue;
                }
                if (attachChamber.contains(node)) {
                    InputModel.Chamber c = model.getChambers().get(node);
                    chamberBranches.merge(c.id, nNew, Integer::sum);
                    tieInCount += nNew;
                    continue;
                }
                InputModel.Segment s = model.getSegments().get(e.getValue());
                boolean interior = s.line.getStartPoint().distance(Crs.UTM_FACTORY.createPoint(p))
                        > InputModel.TOPOLOGY_TOLERANCE_M
                        && s.line.getEndPoint().distance(Crs.UTM_FACTORY.createPoint(p))
                        > InputModel.TOPOLOGY_TOLERANCE_M;
                int degree = (interior ? 2 : 1) + nNew;
                if (degree > ref.getMaxChamberDegree()) {
                    rep.error("R-NET-5", v, node, "New chamber with " + degree + " segments");
                }
                for (InputModel.Chamber c : model.getChambers().values()) {
                    double d = c.point.getCoordinate().distance(p);
                    if (d <= ref.getTieInChamberRadiusM() - POS_TOL) {
                        int used = c.existingDegree + chamberBranches.getOrDefault(c.id, 0);
                        if (used + nNew <= ref.getMaxChamberDegree()) {
                            rep.error("R-TIE-1", v, node, String.format("New chamber %.1f m from chamber %s with a "
                                    + "free branch: the network must be joined in that chamber", d, c.id));
                        }
                    }
                }
            }
            for (Map.Entry<String, Integer> e : chamberBranches.entrySet()) {
                InputModel.Chamber c = model.getChambers().get(e.getKey());
                int degree = c.existingDegree + e.getValue();
                if (degree > ref.getMaxChamberDegree()) {
                    rep.error("R-NET-5", v, c.id, "Existing chamber with " + degree + " segments after the tie-ins");
                }
            }
            rep.checked("R-TIE", attachRoots.size());
        }

        void chambers() {
            for (Out c : newChambers.values()) {
                int dn = 0;
                for (Seg s : segs) {
                    if (c.id.equals(s.start) || c.id.equals(s.end)) {
                        dn = Math.max(dn, s.dn);
                    }
                }
                // every existing line the chamber stands on adjoins it with both halves (appendix 3.2)
                for (InputModel.Segment s : model.getSegments().values()) {
                    if (s.line.distance(c.utm) <= POS_TOL) {
                        dn = Math.max(dn, s.dn);
                    }
                }
                if (c.i("diameter") != dn) {
                    rep.error("R-TIE-3", v, c.id, "Chamber diameter " + c.i("diameter") + ", expected " + dn);
                }
                if (Math.abs(c.n("cost") - ref.chamberCost(dn)) > 1) {
                    rep.error("R-TIE-3", v, c.id, "Chamber cost " + c.n("cost") + ", expected " + ref.chamberCost(dn));
                }
            }
            rep.checked("R-TIE-3", newChambers.size());
        }

        // ---------------------------------------------------------- summary

        void summary(Map<Integer, List<Seg>> comps) {
            double segmentCost = 0;
            double newLen = 0;
            for (Seg s : segs) {
                segmentCost += s.cost;
                newLen += s.length;
            }
            double chambers = 0;
            for (Out c : newChambers.values()) {
                chambers += c.n("cost");
            }
            double tieCost = tieInCount * ref.getTieInCost();
            double penalty = 0;
            for (JsonNode x : summary.props.path("unconnected_oks_ids")) {
                String id = x.asText();
                Double flow = oksById.containsKey(id) ? Double.valueOf(oksById.get(id).flowTph)
                        : model.getUnroutableOks().get(id);
                if (flow == null) {
                    rep.error("R-SUM", v, summary.id, "Unknown OKS id in unconnected_oks_ids: " + id);
                    continue;
                }
                penalty += ref.unconnectedPenalty(flow);
            }
            double construction = segmentCost + chambers + tieCost;
            double total = construction + penalty;
            cmp("construction_cost", construction);
            cmp("chamber_construction_cost", chambers);
            cmp("existing_chamber_tie_in_count", tieInCount);
            cmp("existing_chamber_tie_in_cost", tieCost);
            cmp("unconnected_penalty", penalty);
            cmp("calculated_cost", total);
            cmp("new_network_length", newLen);
            double score = ref.score(summary.n("calculated_cost"), summary.n("new_network_length"));
            if (Math.abs(score - summary.n("score")) > 1e-5) {
                rep.error("R-SCORE", v, summary.id, String.format("score %.6f, expected %.6f", summary.n("score"),
                        score));
            }
            rep.checked("R-SUM", 1);
        }

        void cmp(String field, double expected) {
            double got = summary.n(field);
            if (Math.abs(got - expected) > 0.05 * Math.max(1, features.size()) + 1e-7 * Math.abs(expected)) {
                rep.error("R-SUM", v, summary.id, String.format("%s %.3f, expected %.3f", field, got, expected));
            }
        }
    }

    static boolean isEndpoint(LineString l, Coordinate c) {
        return l.getCoordinateN(0).distance(c) <= POS_TOL
                || l.getCoordinateN(l.getNumPoints() - 1).distance(c) <= POS_TOL;
    }

    static boolean containsCoord(Set<Coordinate> set, Coordinate c) {
        for (Coordinate x : set) {
            if (x.distance(c) <= POS_TOL) {
                return true;
            }
        }
        return false;
    }

    /** Deflection of the route at vertex b, degrees (0 — straight on). */
    static double deflectionDeg(Coordinate a, Coordinate b, Coordinate c) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double vx = c.x - b.x;
        double vy = c.y - b.y;
        double den = Math.hypot(ux, uy) * Math.hypot(vx, vy);
        if (den < 1e-12) {
            return 0;
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ux * vx + uy * vy) / den))));
    }

    /** The straight piece of the line that contains the point. */
    static Coordinate[] segmentAt(LineString line, Coordinate c) {
        Coordinate[] cs = line.getCoordinates();
        Coordinate[] best = new Coordinate[]{cs[0], cs[1]};
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < cs.length; i++) {
            double d = org.locationtech.jts.algorithm.Distance.pointToSegment(c, cs[i], cs[i + 1]);
            if (d < bestD) {
                bestD = d;
                best = new Coordinate[]{cs[i], cs[i + 1]};
            }
        }
        return best;
    }
}
