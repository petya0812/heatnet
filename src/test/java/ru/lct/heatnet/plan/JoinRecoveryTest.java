package ru.lct.heatnet.plan;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.TestSupport;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A join of the new network that fails after the route is found (a shared piece grows to a larger DN and no longer
 * keeps the clearances): only the joins through that piece are left out (step 1), the grown piece can be laid anew
 * for its DN (step 2). Coordinates are metres east and north of an origin; the existing pipe runs along y = −60.
 */
class JoinRecoveryTest {

    private static final ReferenceData REF = ReferenceData.load();
    private static final Coordinate O = Crs.toUtm(Crs.WGS84_FACTORY.createPoint(new Coordinate(37.62, 55.75)))
            .getCoordinate();

    /** Tree: tie → e1 → J → e2 → cp a, J → e3 → cp b. */
    private static final class Tree {
        final Draft d = new Draft();
        Draft.Edge e1;
        Draft.Edge e2;
        Draft.Edge e3;
        Draft.Node j;
    }

    private static Tree tree(InputModel model) {
        InputModel.Segment s = model.getSegments().get("s1");
        InputModel.Oks a = oks(model, "a");
        InputModel.Oks b = oks(model, "b");
        Tree t = new Tree();
        TieOption tie = new TieOption(at(-40, -60), null, s, 60, 0);
        t.d.insert(route(a, tie, at(-40, -60), at(-40, -20), at(40, -20), a.routePoint.getCoordinate()));
        Draft.Edge first = t.d.edges.values().iterator().next();
        TieOption join = new TieOption(at(0, -20), first.id, 40 + 40, 0, 0);
        t.d.insert(route(b, join, at(0, -20), b.routePoint.getCoordinate()));
        assertTrue(t.d.recompute(REF));
        for (Draft.Edge e : t.d.edges.values()) {
            if (e.up.kind == Draft.Kind.TIE) {
                t.e1 = e;
                t.j = e.down;
            }
        }
        for (Draft.Edge e : t.j.down) {
            if (e.down.oks == a) {
                t.e2 = e;
            } else {
                t.e3 = e;
            }
        }
        return t;
    }

    /** Step 1: only the joins on the failed piece and below it are left out. */
    @Test
    void onlyJoinsThroughTheFailedPieceAreLeftOut() throws Exception {
        InputModel model = input(null).getModel();
        Tree t = tree(model);
        Set<Integer> failed = new HashSet<>(Collections.singletonList(t.e2.id));
        assertEquals(failed, Planner.below(t.d, failed));
        assertTrue(Planner.leadsThrough(t.d, new TieOption(at(20, -20), t.e2.id, 10, 0, 0), failed));
        assertFalse(Planner.leadsThrough(t.d, new TieOption(at(0, -10), t.e3.id, 5, 0, 0), failed));
        assertFalse(Planner.leadsThrough(t.d, new TieOption(at(0, -20), 0, 0, t.j.id, 0), failed),
                "the junction above the failed piece is still a join");
        Set<Integer> trunk = new HashSet<>(Collections.singletonList(t.e1.id));
        assertEquals(new HashSet<>(Arrays.asList(t.e1.id, t.e2.id, t.e3.id)), Planner.below(t.d, trunk));
        assertTrue(Planner.leadsThrough(t.d, new TieOption(at(0, -20), 0, 0, t.j.id, 0), trunk),
                "a join at the junction below the failed trunk leads through it");
    }

    /**
     * Step 2: a shared piece laid 5,7 m from a building keeps the clearance for DN 150 (5 m + 0,325 + 0,3 of the search) but not for DN 200
     * (5 m + 0,44 + 0,3). It is laid anew for DN 200 between the same nodes, with turns up to 90°, and the draft stays
     * consistent: the same ids, nodes, flows and DN, intervals along the new polyline.
     */
    @Test
    void aGrownSharedPieceIsLaidAnewForItsDn() throws Exception {
        double[][] building = {{-32, -14.3}, {-18, -14.3}, {-18, 5}, {-32, 5}, {-32, -14.3}};
        LoadedInput in = input(building);
        PlanParams params = new PlanParams();
        RouteFinder finder = new RouteFinder(REF, params, in.getModel(), in.obstacleSource());
        Tree t = tree(in.getModel());
        PipeSpec dn150 = REF.pipe(150);
        PipeSpec dn200 = REF.pipe(200);
        assertTrue(finder.checkPolyline(t.e1.coords, dn150, null, true), "DN 150 keeps the clearance");
        assertFalse(finder.checkPolyline(t.e1.coords, dn200, null, true), "DN 200 does not");

        List<RouteFinder.Built> others = new ArrayList<>();
        List<Coordinate> way = finder.relay(t.e1.coords, dn200, true, others);
        assertNotNull(way, "a way for DN 200");
        assertEquals(0, way.get(0).distance(t.e1.coords.get(0)), 1e-9);
        assertEquals(0, way.get(way.size() - 1).distance(t.e1.coords.get(t.e1.coords.size() - 1)), 1e-9);
        assertTrue(finder.checkPolyline(way, dn200, null, true), "the new way keeps the clearance for DN 200");
        for (int i = 1; i + 1 < way.size(); i++) {
            assertTrue(REF.isAllowedTurn(Planner.deflectionDeg(way.get(i - 1), way.get(i), way.get(i + 1))));
        }

        Map<Integer, Double> flows = new HashMap<>();
        for (Draft.Edge e : t.d.edges.values()) {
            flows.put(e.id, e.flow);
        }
        Set<Integer> ids = new HashSet<>(t.d.edges.keySet());
        Draft.Node up = t.e1.up;
        Draft.Node down = t.e1.down;
        t.e1.dn = 200;
        t.d.relay(t.e1, way, finder.evaluatePolyline(way, dn200, null, true));
        assertEquals(200, t.e1.checkedDn);
        assertTrue(t.d.recompute(REF));
        assertEquals(ids, t.d.edges.keySet());
        assertTrue(t.e1.up == up && t.e1.down == down && up.down.contains(t.e1) && down.up == t.e1);
        for (Draft.Edge e : t.d.edges.values()) {
            assertEquals(flows.get(e.id), e.flow, 1e-9);
        }
        assertFalse(t.d.hasCrossings());
        assertFalse(t.d.hasSharpTurns(REF));
    }

    /**
     * On the contest data (defaults) the OKS 15 in building 92 and the OKS 14 used to get tie-ins of their own (417 and
     * 564 m) after a join to the new network had failed: now every OKS of the best variant hangs on a network with a
     * short own pipe, and no pipe from a tie-in is longer than 300 m.
     */
    @Test
    void contestDataHasNoLongPipesFromTieInsAfterFailedJoins() throws Exception {
        Variant v = TestSupport.run("contest-lct.geojson").variants.get(0);
        Set<String> chambers = new HashSet<>();
        Set<String> ends = new HashSet<>();
        for (Variant.NewSegment s : v.segments) {
            ends.add(s.endNodeId);
        }
        for (Variant.NewSegment s : v.segments) {
            boolean fromTie = !ends.contains(s.startNodeId);
            if (fromTie) {
                chambers.add(s.startNodeId);
                assertTrue(s.line.getLength() < 300, String.format(Locale.ROOT, "%s from %s: %.0f m", s.id,
                        s.startNodeId, s.line.getLength()));
            }
        }
        assertTrue(v.summary.score < 18, "score " + v.summary.score);
    }

    // ------------------------------------------------------------------ data

    private static InputModel.Oks oks(InputModel model, String cp) {
        for (InputModel.Oks o : model.getOks()) {
            if (o.connectionPointId.equals(cp)) {
                return o;
            }
        }
        throw new IllegalArgumentException(cp);
    }

    private static Route route(InputModel.Oks oks, TieOption tie, Coordinate... pts) {
        List<Coordinate> c = new ArrayList<>(Arrays.asList(pts));
        List<RoutingContext.EdgeEval> e = new ArrayList<>();
        for (int i = 0; i + 1 < c.size(); i++) {
            e.add(new RoutingContext.EdgeEval(true, c.get(i).distance(c.get(i + 1)), 0,
                    Collections.<RoutingContext.Interval>emptyList(), null));
        }
        return new Route(oks, REF.minPipeForFlow(oks.flowTph), tie, c, e, 0);
    }

    /** The pipe along y = −60, connection points a (40, −10) and b (0, 10) without buildings, optional building. */
    private static LoadedInput input(double[][] building) throws Exception {
        List<String> fs = new ArrayList<>();
        fs.add(feature("src", "source", point(-100, -60), ""));
        fs.add(feature("s1", "heat_network", line(new double[][]{{-100, -60}, {100, -60}}),
                ", \"diameter\": 300, \"upstream_object_id\": \"src\""));
        if (building != null) {
            fs.add(feature("b9", "restriction", polygon(building), ", \"restriction_type\": \"oks\""));
        }
        fs.add(feature("a", "oks_connection_point", point(40, -10), ", \"flow_tph\": 40.0"));
        fs.add(feature("b", "oks_connection_point", point(0, 30), ", \"flow_tph\": 30.0"));
        String data = "{\"type\": \"FeatureCollection\", \"features\": [" + String.join(", ", fs) + "]}";
        return LoadedInput.read(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), REF,
                new PlanParams().rules);
    }

    private static Coordinate at(double x, double y) {
        return new Coordinate(O.x + x, O.y + y);
    }

    private static String feature(String id, String type, String geometry, String props) {
        return "{\"type\": \"Feature\", \"geometry\": " + geometry + ", \"properties\": {\"id\": \"" + id
                + "\", \"object_type\": \"" + type + "\"" + props + "}}";
    }

    private static String point(double x, double y) {
        return "{\"type\": \"Point\", \"coordinates\": " + pos(x, y) + "}";
    }

    private static String line(double[][] pts) {
        return "{\"type\": \"LineString\", \"coordinates\": " + positions(pts) + "}";
    }

    private static String polygon(double[][] ring) {
        return "{\"type\": \"Polygon\", \"coordinates\": [" + positions(ring) + "]}";
    }

    private static String positions(double[][] pts) {
        List<String> out = new ArrayList<>();
        for (double[] p : pts) {
            out.add(pos(p[0], p[1]));
        }
        return "[" + String.join(", ", out) + "]";
    }

    private static String pos(double x, double y) {
        Coordinate c = Crs.toWgs84(Crs.UTM_FACTORY.createPoint(new Coordinate(O.x + x, O.y + y))).getCoordinate();
        return String.format(Locale.ROOT, "[%.9f, %.9f]", c.x, c.y);
    }
}
