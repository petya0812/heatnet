package ru.lct.heatnet.plan;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With «through own parts» the own building of an OKS is only the part holding its connection point. Two connection
 * points in two parts of one building: the zone of each OKS is its own part, whichever OKS was searched first (the
 * zones are cached for all searches and threads of a calculation).
 */
class OwnPartsZoneTest {

    private static final ReferenceData REF = ReferenceData.load();
    private static final Coordinate O = Crs.toUtm(Crs.WGS84_FACTORY.createPoint(new Coordinate(37.62, 55.75)))
            .getCoordinate();

    @Test
    void theMainPartZoneBelongsToItsOwnOks() throws Exception {
        PlanParams params = new PlanParams();
        params.rules.throughOwnParts = true;
        String data = "{\"type\": \"FeatureCollection\", \"features\": ["
                + feature("src", "source", "{\"type\": \"Point\", \"coordinates\": " + pos(-100, -60) + "}", "") + ", "
                + feature("s1", "heat_network", "{\"type\": \"LineString\", \"coordinates\": [" + pos(-100, -60) + ", "
                + pos(100, -60) + "]}", ", \"diameter\": 300, \"upstream_object_id\": \"src\"") + ", "
                + feature("b1", "restriction", "{\"type\": \"MultiPolygon\", \"coordinates\": [[" + ring(-40, -20)
                + "], [" + ring(20, 40) + "]]}", ", \"restriction_type\": \"oks\"") + ", "
                + feature("a", "oks_connection_point", "{\"type\": \"Point\", \"coordinates\": " + pos(-30, 5) + "}",
                ", \"flow_tph\": 10.0") + ", "
                + feature("b", "oks_connection_point", "{\"type\": \"Point\", \"coordinates\": " + pos(30, 5) + "}",
                ", \"flow_tph\": 10.0") + "]}";
        LoadedInput in = LoadedInput.read(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), REF,
                params.rules);
        InputModel model = in.getModel();
        RouteFinder finder = new RouteFinder(REF, params, model, in.obstacleSource());
        for (String first : new String[]{"a", "b"}) {
            for (String id : first.equals("a") ? new String[]{"a", "b"} : new String[]{"b", "a"}) {
                InputModel.Oks o = oks(model, id);
                RoutingContext ctx = finder.context(o, REF.minPipeForFlow(o.flowTph),
                        Collections.<RouteFinder.Built>emptyList(), o.routePoint.getCoordinate(), 200);
                int found = 0;
                for (RoutingContext.Zone z : ctx.getZones()) {
                    if (z.kind.equals("oks_existing/main")) {
                        found++;
                        assertTrue(z.raw.contains(o.connectionPoint), "the zone of " + id + " is its own part");
                    }
                }
                assertEquals(1, found);
            }
            finder = new RouteFinder(REF, params, model, in.obstacleSource());
        }
    }

    private static InputModel.Oks oks(InputModel model, String cp) {
        for (InputModel.Oks o : model.getOks()) {
            if (o.connectionPointId.equals(cp)) {
                return o;
            }
        }
        throw new IllegalArgumentException(cp);
    }

    /** A square part from x0 to x1, y from 0 to 20. */
    private static String ring(double x0, double x1) {
        return "[" + pos(x0, 0) + ", " + pos(x1, 0) + ", " + pos(x1, 20) + ", " + pos(x0, 20) + ", " + pos(x0, 0) + "]";
    }

    private static String feature(String id, String type, String geometry, String props) {
        return "{\"type\": \"Feature\", \"geometry\": " + geometry + ", \"properties\": {\"id\": \"" + id
                + "\", \"object_type\": \"" + type + "\"" + props + "}}";
    }

    private static String pos(double x, double y) {
        Coordinate c = Crs.toWgs84(Crs.UTM_FACTORY.createPoint(new Coordinate(O.x + x, O.y + y))).getCoordinate();
        return String.format(Locale.ROOT, "[%.9f, %.9f]", c.x, c.y);
    }
}
