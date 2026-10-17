package ru.lct.heatnet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.output.ResultWriter;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.plan.Planner;
import ru.lct.heatnet.plan.Unconnected;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.reference.RuleOptions;
import ru.lct.heatnet.validate.OutputValidator;
import ru.lct.heatnet.validate.ValidationReport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-RST-10 (appendix 2.2, clarification 3): the route goes into the building by one straight piece from the wall
 * nearest to the connection point, perpendicular to it, and leaves it straight along the normal of the wall.
 * The building is 40 × 40 m, the connection point 3 m from its south wall, the network 60 m north of it.
 */
class BuildingEntryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double LON0 = 37.62;
    private static final double LAT0 = 55.75;

    /** The building of the OKS: x −20…20 m, y 0…40 m; the connection point at (0, 3). */
    private static final double[][] BUILDING = {{-20, 0}, {20, 0}, {20, 40}, {-20, 40}, {-20, 0}};

    @Test
    void entryFromTheNearestWallAndStraightOut() throws Exception {
        Result r = plan(dataset(false), new PlanParams());
        assertEquals(Collections.emptyList(), r.report.errors());
        assertTrue(r.report.getChecked().getOrDefault("R-ENTRY", 0) > 0, "the entry is checked");
        assertTrue(r.variants.get(0).unconnected.isEmpty(), "the best variant connects the OKS");
        for (Variant v : r.variants) {
            if (!v.unconnected.isEmpty()) {
                continue; // an alternative variant without the only segment of the network
            }
            List<Coordinate> c = routeOf(v);
            int n = c.size();
            Coordinate cp = c.get(n - 1);
            Coordinate exit = c.get(n - 2);
            Coordinate leg = c.get(n - 3);
            // from the south wall, 3 m inside, perpendicular to it
            assertEquals(3.0, exit.distance(cp), 0.05, "piece inside the building");
            assertEquals(cp.x, exit.x, 0.05, "perpendicular to the wall");
            // the route leaves the building straight on along the normal through the whole clearance to it (5 m
            // and the half width of the pipe) and turns only beyond it
            double clearance = TestSupport.REF.buildingDistance(v.segments.get(0).dn)
                    + TestSupport.REF.pipe(v.segments.get(0).dn).getHalfWidthM();
            assertTrue(leg.distance(exit) >= clearance, "leg out of the building: " + leg.distance(exit));
            assertTrue(leg.distance(exit) <= clearance + 1.0, "no longer than needed: " + leg.distance(exit));
            assertEquals(exit.x, leg.x, 0.05, "the leg goes on along the normal");
            assertTrue(leg.y < exit.y, "the leg goes out of the building");
            // the rest of the route keeps the clearance to its own building
            assertTrue(minDistanceToBuilding(c.subList(0, n - 2)) >= clearance - 0.02,
                    "clearance to the own building: " + minDistanceToBuilding(c.subList(0, n - 2)));
            assertEquals(3.0, v.metrics.insideLengthM, 0.05);
            assertEquals(0, v.metrics.entriesNotNearest);
        }
    }

    /** The validator catches an entry from another wall and one that is not perpendicular to its wall. */
    @Test
    void validatorCatchesAnEntryNotFromTheNearestWall() throws Exception {
        Result r = plan(dataset(false), new PlanParams());
        // the exit moved to the north wall: 37 m inside the building instead of 3 m
        String north = moveExit(r.output, 0, 40);
        assertTrue(entryErrors(r.input, north, RuleOptions.defaults()) > 0, "another wall is an error");
        RuleOptions any = RuleOptions.defaults();
        any.entry = RuleOptions.Entry.ANY;
        ValidationReport rep = check(r.input, north, any);
        assertEquals(0, rep.errors().stream().filter(v -> v.getRule().equals("R-ENTRY")).count(),
                "with any wall allowed it is not an error");
        assertTrue(rep.getViolations().stream().anyMatch(v -> v.getRule().equals("R-ENTRY")
                && v.getSeverity() == ValidationReport.Severity.WARNING), "but it is reported");
        // the exit moved 2 m along the south wall: still on the outline, no longer perpendicular
        assertTrue(entryErrors(r.input, moveExit(r.output, 2, 0), any) > 0, "a slanting entry is an error");
        // the end of the leg moved 3 m aside: the route turns within the clearance to its own building
        ValidationReport turned = check(r.input, moveLegEnd(r.output, 3), RuleOptions.defaults());
        assertTrue(turned.errors().stream().anyMatch(v -> v.getRule().equals("R-RESTR")
                && v.getMessage().contains("of the OKS it feeds")), turned.errors().toString());
        // entry from any point of the outline: a slanting entry and a turn at the wall are reported, not errors
        RuleOptions outline = RuleOptions.defaults();
        outline.entry = RuleOptions.Entry.OUTLINE;
        assertEquals(0, entryErrors(r.input, moveExit(r.output, 2, 0), outline), "a slanting entry is no error");
        assertTrue(check(r.input, moveExit(r.output, 2, 0), outline).getViolations().stream().anyMatch(v ->
                v.getRule().equals("R-ENTRY") && v.getSeverity() == ValidationReport.Severity.WARNING),
                "but it is reported");
        assertFalse(check(r.input, moveLegEnd(r.output, 3), outline).errors().stream().anyMatch(v ->
                v.getRule().equals("R-RESTR")), "no clearance to the own building");
    }

    /**
     * A neighbour 1 m south of the building, 160 m long, walls in the south wall and the side walls too (their
     * straight pieces stay in its clearance). Strictly by the appendix the OKS is left without a route, and the reason
     * says that the north wall would do; the other modes leave by it.
     */
    @Test
    void walledInNearestWall() throws Exception {
        Result strict = plan(dataset(true), new PlanParams());
        Variant v = strict.variants.get(0);
        assertEquals(1, v.unconnected.size(), v.unconnected.toString());
        Unconnected u = v.unconnected.get(0);
        assertEquals(Unconnected.Reason.ENTRY_BLOCKED, u.reason);
        assertTrue(u.detail.contains("со стены в 37,0 м внутри здания трасса есть"), u.detail);

        PlanParams near = new PlanParams();
        near.rules.entry = RuleOptions.Entry.NEAR;
        assertFalse(plan(dataset(true), near).variants.get(0).unconnected.isEmpty(),
                "another wall 34 m farther is beyond the default 5 m");
        near.rules.entrySlackM = 40;
        Result nearer = plan(dataset(true), near);
        PlanParams any = new PlanParams();
        any.rules.entry = RuleOptions.Entry.ANY;
        Result anyWall = plan(dataset(true), any);
        for (Result r : new Result[]{nearer, anyWall}) {
            Variant w = r.variants.get(0);
            assertTrue(w.unconnected.isEmpty(), w.unconnected.toString());
            List<Coordinate> c = routeOf(w);
            // the north wall: 37 m inside
            assertEquals(37.0, c.get(c.size() - 2).distance(c.get(c.size() - 1)), 0.05);
            assertEquals(1, w.metrics.entriesNotNearest);
            assertEquals(Collections.emptyList(), r.report.errors());
            assertTrue(r.report.warningCount() > 0, "an entry not from the nearest wall is reported");
        }
        // entry from any point of the outline: no straight piece through the clearance, the search picks the point
        PlanParams outline = new PlanParams();
        outline.rules.entry = RuleOptions.Entry.OUTLINE;
        Result fromOutline = plan(dataset(true), outline);
        Variant o = fromOutline.variants.get(0);
        assertTrue(o.unconnected.isEmpty(), o.unconnected.toString());
        assertEquals(Collections.emptyList(), fromOutline.report.errors());
    }

    /**
     * The building has one more small part 2 × 2 m off the south wall, 3 m aside from the way out (a multipolygon,
     * as many outlines of the input are). Appendix 2.2: the clearance to the own building does not hold on the
     * straight piece out of it, so the piece passes the part; it may not cross it.
     */
    @Test
    void straightPiecePassesAPartOfTheOwnBuilding() throws Exception {
        Result r = plan(dataset(false, new double[][]{{3, -5}, {5, -5}, {5, -3}, {3, -3}, {3, -5}}), new PlanParams());
        Variant v = r.variants.get(0);
        assertTrue(v.unconnected.isEmpty(), v.unconnected.toString());
        assertEquals(Collections.emptyList(), r.report.errors());
        List<Coordinate> c = routeOf(v);
        assertEquals(3.0, c.get(c.size() - 2).distance(c.get(c.size() - 1)), 0.05, "from the nearest wall");
        assertEquals(0, v.metrics.entriesNotNearest);

        // the part right on the way out: the straight piece would cross the building itself
        Result blocked = plan(dataset(false, new double[][]{{-1, -8}, {1, -8}, {1, -6}, {-1, -6}, {-1, -8}}),
                new PlanParams());
        Unconnected u = blocked.variants.get(0).unconnected.get(0);
        assertEquals(Unconnected.Reason.ENTRY_BLOCKED, u.reason);
    }

    // ------------------------------------------------------------------ helpers

    private static final class Result {
        LoadedInput input;
        List<Variant> variants;
        String output;
        ValidationReport report;
    }

    private static Result plan(String geojson, PlanParams params) throws Exception {
        Result r = new Result();
        r.input = LoadedInput.read(new ByteArrayInputStream(geojson.getBytes(StandardCharsets.UTF_8)),
                TestSupport.REF, params.rules);
        r.variants = new Planner(TestSupport.REF, params).plan(r.input.getModel(), r.input.obstacleSource(),
                r.input.getDiagnostics());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ResultWriter().write(out, r.variants);
        r.output = out.toString(StandardCharsets.UTF_8.name());
        r.report = check(r.input, r.output, params.rules);
        return r;
    }

    private static ValidationReport check(LoadedInput in, String output, RuleOptions rules) throws Exception {
        return new OutputValidator(TestSupport.REF, rules).validate(in,
                new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
    }

    private static long entryErrors(LoadedInput in, String output, RuleOptions rules) throws Exception {
        return check(in, output, rules).errors().stream().filter(v -> v.getRule().equals("R-ENTRY")).count();
    }

    /** The route of the only OKS, from the network to the connection point (one segment in this layout). */
    private static List<Coordinate> routeOf(Variant v) {
        List<Coordinate> out = new ArrayList<>();
        for (Variant.NewSegment s : v.segments) {
            if ("cp".equals(s.endNodeId)) {
                Collections.addAll(out, s.line.getCoordinates());
            }
        }
        assertFalse(out.isEmpty(), "no segment ends at the connection point");
        return out;
    }

    /** The result with the exit of the segment into the building moved to the point (x, y), metres. */
    private static String moveExit(String output, double x, double y) throws Exception {
        JsonNode root = MAPPER.readTree(output);
        for (JsonNode f : root.get("features")) {
            if ("cp".equals(f.get("properties").path("end_node_id").asText())) {
                ArrayNode cs = (ArrayNode) f.get("geometry").get("coordinates");
                ArrayNode exit = (ArrayNode) cs.get(cs.size() - 2);
                double[] ll = lonLat(x, y);
                exit.set(0, MAPPER.getNodeFactory().numberNode(ll[0]));
                exit.set(1, MAPPER.getNodeFactory().numberNode(ll[1]));
            }
        }
        return MAPPER.writeValueAsString(root);
    }

    /** The result with the end of the leg out of the building (the point before the exit) moved east by dx metres. */
    private static String moveLegEnd(String output, double dx) throws Exception {
        JsonNode root = MAPPER.readTree(output);
        for (JsonNode f : root.get("features")) {
            if ("cp".equals(f.get("properties").path("end_node_id").asText())) {
                ArrayNode cs = (ArrayNode) f.get("geometry").get("coordinates");
                ArrayNode q = (ArrayNode) cs.get(cs.size() - 3);
                Coordinate u = Crs.toUtm(Crs.WGS84_FACTORY.createPoint(new Coordinate(q.get(0).asDouble(),
                        q.get(1).asDouble()))).getCoordinate();
                Coordinate w = Crs.toWgs84(Crs.UTM_FACTORY.createPoint(new Coordinate(u.x + dx, u.y))).getCoordinate();
                q.set(0, MAPPER.getNodeFactory().numberNode(w.x));
                q.set(1, MAPPER.getNodeFactory().numberNode(w.y));
            }
        }
        return MAPPER.writeValueAsString(root);
    }

    /** Distance from a polyline (UTM) to the building of the OKS. */
    private static double minDistanceToBuilding(List<Coordinate> pts) {
        Coordinate o = Crs.toUtm(Crs.WGS84_FACTORY.createPoint(new Coordinate(LON0, LAT0))).getCoordinate();
        Coordinate[] ring = new Coordinate[BUILDING.length];
        for (int i = 0; i < BUILDING.length; i++) {
            ring[i] = new Coordinate(o.x + BUILDING[i][0], o.y + BUILDING[i][1]);
        }
        return Crs.UTM_FACTORY.createLineString(pts.toArray(new Coordinate[0]))
                .distance(Crs.UTM_FACTORY.createPolygon(ring));
    }

    /** The building, the connection point, the network 60 m north of the building; optionally a neighbour south. */
    private static String dataset(boolean neighbour) {
        return dataset(neighbour, null);
    }

    /** The dataset; {@code part} — one more polygon of the building of the OKS (a multipolygon then), or null. */
    private static String dataset(boolean neighbour, double[][] part) {
        List<String> fs = new ArrayList<>();
        fs.add(feature("src", "source", point(-100, 100), ""));
        fs.add(feature("s1", "heat_network", line(new double[][]{{-100, 100}, {100, 100}}),
                ", \"diameter\": 300, \"upstream_object_id\": \"src\""));
        fs.add(feature("b1", "restriction", part == null ? polygon(BUILDING) : multiPolygon(BUILDING, part),
                ", \"restriction_type\": \"oks\""));
        if (neighbour) {
            fs.add(feature("b2", "restriction", polygon(new double[][]{{-80, -12}, {80, -12}, {80, -1}, {-80, -1},
                    {-80, -12}}), ", \"restriction_type\": \"oks\""));
        }
        fs.add(feature("cp", "oks_connection_point", point(0, 3), ", \"flow_tph\": 5.0"));
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

    private static String multiPolygon(double[][]... rings) {
        List<String> out = new ArrayList<>();
        for (double[][] r : rings) {
            out.add("[" + positions(r) + "]");
        }
        return "{\"type\": \"MultiPolygon\", \"coordinates\": [" + String.join(", ", out) + "]}";
    }

    private static String positions(double[][] pts) {
        List<String> out = new ArrayList<>();
        for (double[] p : pts) {
            out.add(pos(p[0], p[1]));
        }
        return "[" + String.join(", ", out) + "]";
    }

    private static String pos(double x, double y) {
        double[] ll = lonLat(x, y);
        return String.format(Locale.ROOT, "[%.9f, %.9f]", ll[0], ll[1]);
    }

    /** Longitude and latitude of a point given in metres east and north of the origin, through UTM. */
    private static double[] lonLat(double x, double y) {
        Coordinate o = Crs.toUtm(Crs.WGS84_FACTORY.createPoint(new Coordinate(LON0, LAT0))).getCoordinate();
        Coordinate c = Crs.toWgs84(Crs.UTM_FACTORY.createPoint(new Coordinate(o.x + x, o.y + y))).getCoordinate();
        return new double[]{c.x, c.y};
    }
}
