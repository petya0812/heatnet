package ru.lct.heatnet.plan;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.TestSupport;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The choice of a scheme: the shape (turns and joints that are not a T) in the score the schemes are compared by, the
 * group moves of the local search and the straightening of pieces with turns. Coordinates are metres east and north of
 * an origin; the existing pipe runs along y = −60.
 */
class SchemeChoiceTest {

    private static final ReferenceData REF = ReferenceData.load();
    private static final Coordinate O = Crs.toUtm(Crs.WGS84_FACTORY.createPoint(new Coordinate(37.62, 55.75)))
            .getCoordinate();
    private static final double TOL = REF.getStraightDeg();

    /** A pipe straight through a chamber with a branch at 90° is a T; a bend of the pipe is a turn. */
    @Test
    void junctionShapes() {
        double[] w = {-1, 0};
        double[] e = {1, 0};
        double[] n = {0, 1};
        double[] s = {0, -1};
        double[] ne = {1, 1};
        double[] nw = {-1, 1};
        assertArrayEquals(new int[]{0, 0}, Planner.junctionShape(new double[][]{w, e, n}, TOL, 2));
        // the pipe bends 90° in the chamber, the branch goes straight on from one piece: a T at a bend
        assertArrayEquals(new int[]{0, 0}, Planner.junctionShape(new double[][]{s, n, w}, TOL, 2));
        assertArrayEquals(new int[]{0, 1}, Planner.junctionShape(new double[][]{w, e, ne}, TOL, 2));
        // a Y: whatever pair is the pipe, it bends and the third piece is at an odd angle
        assertArrayEquals(new int[]{1, 1}, Planner.junctionShape(new double[][]{s, ne, nw}, TOL, 2));
        // a new pipe at the end of an existing one: straight on, a turn, sharper — an odd joint
        assertArrayEquals(new int[]{0, 0}, Planner.jointShape(new double[][]{w}, e, TOL));
        assertArrayEquals(new int[]{1, 0}, Planner.jointShape(new double[][]{w}, n, TOL));
        assertArrayEquals(new int[]{0, 1}, Planner.jointShape(new double[][]{w}, nw, TOL));
        assertArrayEquals(new int[]{0, 0}, Planner.jointShape(new double[][]{w, e}, n, TOL));
        assertArrayEquals(new int[]{0, 1}, Planner.jointShape(new double[][]{w, e}, ne, TOL));
    }

    /**
     * The score of the choice is S plus the shape at the price of the search; with weight 0 it is S exactly. The
     * reported S does not depend on the weight.
     */
    @Test
    void selectionScoreIsScorePlusShape() throws Exception {
        InputModel model = input().getModel();
        Tree t = tree(model);
        int[] shape = Planner.shape(t.d, REF, 2);
        // tie → (−40, −20) → J: one turn; J → (40, −20) → a: one turn (the last piece goes into the point);
        // J → b straight; in J the pipe goes on east, b leaves it at 90° (a T); the route leaves the existing pipe at
        // 90° (a T)
        assertArrayEquals(new int[]{2, 0}, shape);
        double s0 = -1;
        for (double w : new double[]{0, 1, 2.5}) {
            PlanParams p = new PlanParams();
            p.shapeWeight = w;
            Variant v = new Planner(REF, p).assemble(model, t.d.copy(), Collections.<String, Unconnected>emptyMap(),
                    "1", new Diagnostics());
            if (s0 < 0) {
                s0 = v.summary.score;
                assertEquals(v.summary.score, v.metrics.selectionScore, 0.0, "weight 0: S itself");
            }
            assertEquals(s0, v.summary.score, 0.0, "S does not depend on the weight");
            assertEquals(2, v.metrics.shapeTurns);
            assertEquals(0, v.metrics.oddJoints);
            assertEquals(s0 + w * p.turnPenaltyScore * (2 + p.oddJointTurns * 0), v.metrics.selectionScore, 1e-12);
        }
    }

    /** A shared piece with a turn is laid anew between the same nodes; the nodes, ids and flows stay. */
    @Test
    void aSharedPieceWithATurnIsStraightened() throws Exception {
        LoadedInput in = input();
        InputModel model = in.getModel();
        PlanParams params = new PlanParams();
        RouteFinder finder = new RouteFinder(REF, params, model, in.obstacleSource());
        Planner planner = new Planner(REF, params);
        Tree t = tree(model);
        assertEquals(1, Planner.headTurns(t.e1, TOL));
        Set<Integer> ids = new HashSet<>(t.d.edges.keySet());
        Draft.Node up = t.e1.up;
        Draft.Node down = t.e1.down;
        double flow = t.e1.flow;
        assertTrue(planner.relayHead(model, finder, t.d, t.e1), "a way with fewer turns");
        assertEquals(0, Planner.headTurns(t.e1, TOL));
        assertEquals(ids, t.d.edges.keySet());
        assertTrue(t.e1.up == up && t.e1.down == down && down.up == t.e1);
        assertEquals(flow, t.e1.flow, 1e-9);
        assertFalse(t.d.hasCrossings());
        assertFalse(t.d.hasSharpTurns(REF));
        assertTrue(t.e1.coords.get(0).distance(up.c) < 1e-6
                && t.e1.coords.get(t.e1.coords.size() - 1).distance(down.c) < 1e-6);
        // the edge to a connection point keeps its last straight piece (the entry): with one turn before it, nothing
        // is left to lay anew
        assertEquals(0, Planner.headTurns(t.e2, TOL));
        assertFalse(planner.relayHead(model, finder, t.d, t.e2));
    }

    /** Groups are the OKS below a chamber or of a small tree; every order of 4 takes 64 searches with shared beginnings. */
    @Test
    void groupsAndTheirOrders() throws Exception {
        InputModel model = input().getModel();
        Tree t = tree(model);
        List<Planner.Group> groups = new Planner(REF, new PlanParams()).groups(t.d);
        assertEquals(2, groups.size(), "below the chamber and the whole tree");
        for (Planner.Group g : groups) {
            assertEquals("a,b", g.key());
        }
        assertEquals(4, Planner.groupConnects(2));
        assertEquals(15, Planner.groupConnects(3));
        assertEquals(64, Planner.groupConnects(4));
        assertEquals(15, Planner.groupConnects(5));
    }

    /**
     * Contest data, defaults: the branch at the buildings 74–90 (OKS 3, 6, 8, 11) used to be a web of turns from the
     * route of the first OKS; the group moves and the straightening leave fewer turns and a lower score of the choice
     * than before (S 16,45, 36 turns, one odd joint — 22,15).
     */
    @Test
    void contestDataHasASimplerScheme() throws Exception {
        Variant v = TestSupport.run("contest-lct.geojson").variants.get(0);
        assertTrue(v.metrics.selectionScore < 21.5, String.format(Locale.ROOT, "selection %.3f, S %.3f, turns %d",
                v.metrics.selectionScore, v.summary.score, v.metrics.shapeTurns));
        assertTrue(v.metrics.shapeTurns <= 33, "turns " + v.metrics.shapeTurns);
        assertTrue(v.summary.score < 16.45, "S " + v.summary.score);
    }

    // ------------------------------------------------------------------ data

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
        tie.pipeDirs = new double[][]{{1, 0}, {-1, 0}};
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

    /** The pipe along y = −60, connection points a (40, −10) and b (0, 30), no buildings. */
    private static LoadedInput input() throws Exception {
        List<String> fs = new ArrayList<>();
        fs.add(feature("src", "source", point(-100, -60), ""));
        fs.add(feature("s1", "heat_network", line(new double[][]{{-100, -60}, {100, -60}}),
                ", \"diameter\": 300, \"upstream_object_id\": \"src\""));
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
