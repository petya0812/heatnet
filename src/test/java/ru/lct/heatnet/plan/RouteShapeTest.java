package ru.lct.heatnet.plan;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.output.ResultWriter;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.validate.OutputValidator;
import ru.lct.heatnet.validate.ValidationReport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape of a route: directions of the network it joins, the joint with the pipe, shapes cut short after the search.
 * Coordinates are metres east and north of an origin; the existing pipe runs along y = −60.
 */
class RouteShapeTest {

    private static final ReferenceData REF = ReferenceData.load();
    private static final double LON0 = 37.62;
    private static final double LAT0 = 55.75;
    private static final Coordinate O = Crs.toUtm(Crs.WGS84_FACTORY.createPoint(new Coordinate(LON0, LAT0)))
            .getCoordinate();

    /**
     * The building of the OKS is turned by 20° to the pipe. The route leaves it along the normal of its south wall
     * through the clearance, turns once by 20° to the directions of the pipe and meets the pipe at 90°.
     */
    @Test
    void routeOfATurnedBuildingMeetsThePipeAtARightAngle() throws Exception {
        double[][] building = turned(new double[][]{{-10, 0}, {10, 0}, {10, 20}, {-10, 20}, {-10, 0}}, 20);
        double[] cp = turned(new double[][]{{0, 3}}, 20)[0];
        String data = dataset(building, cp);
        PlanParams params = new PlanParams();
        LoadedInput in = LoadedInput.read(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), REF,
                params.rules);
        List<Variant> vs = new Planner(REF, params).plan(in.getModel(), in.obstacleSource(), in.getDiagnostics());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ResultWriter().write(out, vs);
        ValidationReport rep = new OutputValidator(REF, params.rules).validate(in,
                new ByteArrayInputStream(out.toByteArray()));
        assertEquals(Collections.emptyList(), rep.errors());
        Variant v = vs.get(0);
        assertTrue(v.unconnected.isEmpty(), v.unconnected.toString());
        List<Coordinate> c = new ArrayList<>();
        for (Variant.NewSegment s : v.segments) {
            if ("cp".equals(s.endNodeId)) {
                c.addAll(Arrays.asList(s.line.getCoordinates()));
            }
        }
        int n = c.size();
        // the tie-in: the first leg at 90° to the pipe along x
        assertEquals(90, angle(c.get(0), c.get(1), 1, 0), 1.0, "joint with the pipe: " + local(c));
        // the straight piece out of the building along the normal of its south wall (−70°)
        Coordinate exit = c.get(n - 2);
        Coordinate leg = c.get(n - 3);
        assertEquals(0, angle(exit, leg, Math.cos(Math.toRadians(-70)), Math.sin(Math.toRadians(-70))), 1.0,
                "the leg out of the building: " + local(c));
        // one turn of 20° links the two directions, no other turn
        assertEquals(1, turns(c.subList(0, n - 1)), "turns: " + local(c));
        assertEquals(20, Planner.deflectionDeg(c.get(n - 4), leg, exit), 1.0);
    }

    /**
     * A T is free, any other joint costs two turns and is not allowed with strict angles; at the end of a pipe the
     * joint is a bend.
     */
    @Test
    void jointWithAPipe() throws Exception {
        PlanParams params = new PlanParams();
        params.oddJointTurns = 2;
        RouteFinder f = finder(params);
        TieOption straight = tie(0, 0, new double[][]{{1, 0}, {-1, 0}});
        Frame frame = new Frame(0, 90);
        assertEquals(0, f.junctionScore(straight, at(0, 10), frame), 1e-12, "at 90° to a straight piece");
        assertEquals(2 * params.turnPenaltyScore, f.junctionScore(straight, at(3, 10), frame), 1e-12,
                "at 73° — two odd angles");
        PlanParams cheap = new PlanParams();
        cheap.oddJointTurns = 0.5;
        assertEquals(0.5 * cheap.turnPenaltyScore, finder(cheap).junctionScore(straight, at(3, 10), frame), 1e-12,
                "the price of an odd joint is a setting");
        // a bend of the pipe: from (−10, 0) to (0, 0), then north
        TieOption bend = tie(0, 0, new double[][]{{-1, 0}, {0, 1}});
        assertEquals(0, f.junctionScore(bend, at(10, 0), frame), 1e-12, "straight on from one piece, 90° to the other");
        assertEquals(0, f.junctionScore(bend, at(0, -10), frame), 1e-12, "straight on from the other piece");
        assertEquals(2 * params.turnPenaltyScore, f.junctionScore(bend, at(10, -10), frame), 1e-12,
                "at 135° to both pieces");
        TieOption bend45 = tie(0, 0, new double[][]{{-1, 0}, {1, 1}});
        assertEquals(2 * params.turnPenaltyScore, f.junctionScore(bend45, at(10, 0), frame), 1e-12,
                "a bend of 45° gives no T");
        // the end of a pipe that goes east: the route goes on along it, a bend like any other
        TieOption end = tie(0, 0, new double[][]{{1, 0}});
        assertEquals(0, f.junctionScore(end, at(-10, 0), frame), 1e-12, "straight on into the pipe");
        assertEquals(params.turnPenaltyScore, f.junctionScore(end, at(-10, -10), frame), 1e-12, "a bend of 45°");
        assertEquals(2 * params.turnPenaltyScore, f.junctionScore(end, at(10, -10), frame), 1e-12,
                "a bend of 135°");
        PlanParams strict = new PlanParams();
        strict.turnStepStrict = true;
        RouteFinder fs = finder(strict);
        assertEquals(-1, fs.junctionScore(straight, at(3, 10), frame), 1e-12, "strict angles: not allowed");
        assertEquals(0, fs.junctionScore(straight, at(0, 10), frame), 1e-12);
        TieOption chamber = tie(0, 0, null);
        assertEquals(0, f.junctionScore(chamber, at(3, 10), frame), 1e-12, "an existing chamber takes any angle");
    }

    /**
     * A route that goes north past its turn and comes back (a U): from the pipe at (0, −60) north to (0, 0), east
     * 10 m, south to (10, −20), east to its start (30, −20). One turn at (0, −20) does: 40 m shorter, two turns less.
     */
    @Test
    void backtrackIsCutShort() throws Exception {
        PlanParams params = new PlanParams();
        RouteFinder f = finder(params);
        PipeSpec pipe = REF.pipe(100);
        RoutingContext ctx = f.context(null, pipe, Collections.<RouteFinder.Built>emptyList(), at(0, -20), 150);
        List<Coordinate> fwd = new ArrayList<>(Arrays.asList(at(0, -60), at(0, 0), at(10, 0), at(10, -20),
                at(30, -20)));
        List<RoutingContext.EdgeEval> edges = new ArrayList<>();
        RoutingContext.Endpoint goalEp = ctx.endpoint(fwd.get(0), ctx.specialsAround(fwd.get(0), 0.05));
        for (int i = 0; i + 1 < fwd.size(); i++) {
            RoutingContext.EdgeEval e = ctx.evaluate(fwd.get(i), fwd.get(i + 1), i == 0 ? goalEp : null, null);
            assertTrue(e.valid, "piece " + i + ": " + e.reason());
            edges.add(e);
        }
        TieOption goal = tie(0, -60, new double[][]{{1, 0}, {-1, 0}});
        double change = f.cutShapes(fwd, edges, new Frame(0, 90), ctx, pipe, goal, goalEp, null, null);
        assertEquals(Arrays.asList(at(0, -60), at(0, -20), at(30, -20)), fwd, local(fwd));
        assertEquals(2, edges.size());
        assertTrue(change < -2 * params.turnPenaltyScore, "cheaper by two turns and 40 m: " + change);
        // nothing left to cut
        assertEquals(0, f.cutShapes(fwd, edges, new Frame(0, 90), ctx, pipe, goal, goalEp, null, null), 1e-12);
    }

    /** The point beyond the start (the wall): the turn there is kept within 90° and the start is not moved. */
    @Test
    void cutKeepsTheStartAndTheTurnToTheWall() throws Exception {
        PlanParams params = new PlanParams();
        RouteFinder f = finder(params);
        PipeSpec pipe = REF.pipe(100);
        RoutingContext ctx = f.context(null, pipe, Collections.<RouteFinder.Built>emptyList(), at(0, -20), 150);
        // the route comes to its start (30, −20) from the north; the wall is east of it: a turn of 90° there
        List<Coordinate> fwd = new ArrayList<>(Arrays.asList(at(0, -60), at(0, 0), at(30, 0), at(30, -20)));
        List<RoutingContext.EdgeEval> edges = new ArrayList<>();
        RoutingContext.Endpoint goalEp = ctx.endpoint(fwd.get(0), ctx.specialsAround(fwd.get(0), 0.05));
        for (int i = 0; i + 1 < fwd.size(); i++) {
            edges.add(ctx.evaluate(fwd.get(i), fwd.get(i + 1), i == 0 ? goalEp : null, null));
        }
        TieOption goal = tie(0, -60, new double[][]{{1, 0}, {-1, 0}});
        Coordinate wall = at(40, -20);
        f.cutShapes(fwd, edges, new Frame(0, 90), ctx, pipe, goal, goalEp, null, wall);
        int n = fwd.size();
        assertEquals(at(30, -20), fwd.get(n - 1), "the start stays");
        assertTrue(Planner.deflectionDeg(fwd.get(n - 2), fwd.get(n - 1), wall) <= 90 + 1e-6, local(fwd));
        assertEquals(edges.size(), n - 1);
    }

    private static RouteFinder finder(PlanParams params) throws Exception {
        // the shapes are drawn around a lone building: they leave the boundary of so small a dataset
        params.dataBoundaryM = 0;
        String data = dataset(null, new double[]{30, -10});
        LoadedInput in = LoadedInput.read(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), REF,
                params.rules);
        return new RouteFinder(REF, params, in.getModel(), in.obstacleSource());
    }

    private static TieOption tie(double x, double y, double[][] dirs) {
        TieOption t = new TieOption(at(x, y), null, null, 0, 0);
        t.pipeDirs = dirs;
        return t;
    }

    private static double angle(Coordinate a, Coordinate b, double ux, double uy) {
        double vx = b.x - a.x;
        double vy = b.y - a.y;
        double cos = (vx * ux + vy * uy) / (Math.hypot(vx, vy) * Math.hypot(ux, uy));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    private static int turns(List<Coordinate> c) {
        int n = 0;
        for (int k = 1; k + 1 < c.size(); k++) {
            if (Planner.deflectionDeg(c.get(k - 1), c.get(k), c.get(k + 1)) > REF.getStraightDeg()) {
                n++;
            }
        }
        return n;
    }

    private static double[][] turned(double[][] pts, double deg) {
        double a = Math.toRadians(deg);
        double[][] out = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            out[i] = new double[]{pts[i][0] * Math.cos(a) - pts[i][1] * Math.sin(a),
                    pts[i][0] * Math.sin(a) + pts[i][1] * Math.cos(a)};
        }
        return out;
    }

    private static Coordinate at(double x, double y) {
        return new Coordinate(O.x + x, O.y + y);
    }

    private static String local(List<Coordinate> c) {
        List<String> out = new ArrayList<>();
        for (Coordinate p : c) {
            out.add(String.format(Locale.ROOT, "(%.2f, %.2f)", p.x - O.x, p.y - O.y));
        }
        return String.join(" ", out);
    }

    /** The pipe along y = −60 fed from its west end, the connection point, optionally the building of the OKS. */
    private static String dataset(double[][] building, double[] cp) {
        List<String> fs = new ArrayList<>();
        fs.add(feature("src", "source", point(-100, -60), ""));
        fs.add(feature("s1", "heat_network", line(new double[][]{{-100, -60}, {100, -60}}),
                ", \"diameter\": 300, \"upstream_object_id\": \"src\""));
        if (building != null) {
            fs.add(feature("b1", "restriction", polygon(building), ", \"restriction_type\": \"oks\""));
        }
        fs.add(feature("cp", "oks_connection_point", point(cp[0], cp[1]), ", \"flow_tph\": 5.0"));
        return "{\"type\": \"FeatureCollection\", \"features\": [" + String.join(", ", fs) + "]}";
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
