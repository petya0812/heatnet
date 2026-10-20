package ru.lct.heatnet.plan;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Variants differ by places of attachment and the OKS connected through them (ТЗ 2.8), as the check of the result
 * sees them: two new pipes in one existing chamber are one place; the point where two existing segments meet is one
 * place whichever of them the tie-in is made on.
 */
class VariantPlaceTest {

    private static final ReferenceData REF = ReferenceData.load();
    private static final Coordinate O = Crs.toUtm(Crs.WGS84_FACTORY.createPoint(new Coordinate(37.62, 55.75)))
            .getCoordinate();

    @Test
    void aPointWhereTwoSegmentsMeetIsOnePlace() throws Exception {
        InputModel model = input();
        InputModel.Segment s1 = model.getSegments().get("s1");
        InputModel.Segment s2 = model.getSegments().get("s2");
        assertEquals("s1", model.placeOf(s2, at(0, -60), 0.05), "the start of s2 is the end of s1");
        assertEquals("s1", model.placeOf(s1, at(0, -60), 0.05));
        assertEquals("s2", model.placeOf(s2, at(30, -60), 0.05));
    }

    @Test
    void treesInOneChamberAreOnePlace() throws Exception {
        InputModel model = input();
        InputModel.Chamber ch = model.getChambers().get("c1");
        InputModel.Oks a = oks(model, "a");
        InputModel.Oks b = oks(model, "b");
        Coordinate c = ch.point.getCoordinate();
        Draft two = new Draft();
        two.insert(route(a, new TieOption(c, ch, null, 0, 0), c, a.routePoint.getCoordinate()));
        two.insert(route(b, new TieOption(c, ch, null, 0, 0), c, b.routePoint.getCoordinate()));
        Draft one = new Draft();
        one.insert(route(a, new TieOption(c, ch, null, 0, 0), c, a.routePoint.getCoordinate()));
        Draft.Edge e = one.edges.values().iterator().next();
        one.insert(route(b, new TieOption(e.coords.get(1), e.id, e.length(), 0, 0), e.coords.get(1),
                b.routePoint.getCoordinate()));
        assertEquals(one.signature(), two.signature(), "one chamber, the same OKS: not a different variant");

        InputModel.Segment s2 = model.getSegments().get("s2");
        TieOption atStart = new TieOption(at(0, -60), null, s2, 0, 0);
        atStart.place = model.placeOf(s2, at(0, -60), 0.05);
        Draft other = new Draft();
        other.insert(route(a, atStart, at(0, -60), a.routePoint.getCoordinate()));
        other.insert(route(b, new TieOption(c, ch, null, 0, 0), c, b.routePoint.getCoordinate()));
        assertNotEquals(two.signature(), other.signature());
        assertEquals("c1:b;s1:a", other.signature());
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

    /** Pipe s1 (−100 → 0) and its continuation s2 (0 → 100) along y = −60, chamber c1 at the end of s2. */
    private static InputModel input() throws Exception {
        String data = "{\"type\": \"FeatureCollection\", \"features\": ["
                + feature("src", "source", point(-100, -60), "") + ", "
                + feature("s1", "heat_network", line(-100, 0), ", \"diameter\": 300, \"upstream_object_id\": \"src\"")
                + ", " + feature("s2", "heat_network", line(0, 100), ", \"diameter\": 300, \"upstream_object_id\": \"s1\"")
                + ", " + feature("c1", "heat_chamber", point(100, -60), "") + ", "
                + feature("a", "oks_connection_point", point(0, -20), ", \"flow_tph\": 10.0") + ", "
                + feature("b", "oks_connection_point", point(100, -20), ", \"flow_tph\": 10.0") + "]}";
        return LoadedInput.read(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), REF,
                new PlanParams().rules).getModel();
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

    private static String line(double x0, double x1) {
        return "{\"type\": \"LineString\", \"coordinates\": [" + pos(x0, -60) + ", " + pos(x1, -60) + "]}";
    }

    private static String pos(double x, double y) {
        Coordinate c = Crs.toWgs84(Crs.UTM_FACTORY.createPoint(new Coordinate(O.x + x, O.y + y))).getCoordinate();
        return String.format(Locale.ROOT, "[%.9f, %.9f]", c.x, c.y);
    }
}
