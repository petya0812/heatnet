package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnet.plan.PolygonBoundary;
import ru.lct.heatnet.plan.Unconnected;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.reference.PipeSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules of the technical appendix (edition 18.09.2026) and of the clarifications of the organisers as the planner
 * applies them. The output validator checks the same rules on every result (PipelineTest), and OutputValidatorTest
 * makes sure it catches their violations.
 */
class AppendixRulesTest {

    /**
     * R-NET-9, R-NET-10 (appendix 2.3, clarifications 1 and 2): the DN is never below the minimal one for the
     * flow, and towards the place of attachment it never decreases.
     */
    @ParameterizedTest
    @ValueSource(strings = {"small.geojson", "chain.geojson", "star.geojson", "random-42.geojson",
            "contest-lct.geojson"})
    void diameterFollowsFlowAndNeverDecreasesTowardsTheAttachment(String dataset) throws Exception {
        TestSupport.Run r = TestSupport.run(dataset);
        for (Variant v : r.variants) {
            java.util.Map<String, Integer> dnOfNode = new java.util.HashMap<>();
            for (Variant.NewSegment s : v.segments) {
                PipeSpec min = TestSupport.REF.minPipeForFlow(s.flowTph);
                assertTrue(s.dn >= min.getDn(),
                        dataset + " " + s.id + ": DN " + s.dn + " for " + s.flowTph + " t/h");
                dnOfNode.merge(s.startNodeId, s.dn, Math::max);
            }
            for (Variant.NewSegment s : v.segments) {
                Integer up = dnOfNode.get(s.startNodeId);
                assertTrue(up == null || up >= s.dn, dataset + " " + s.id + ": DN decreases towards the attachment");
            }
        }
    }

    /**
     * R-UNC-2 (appendix 2.5, clarification 15): an expensive connection is still built — a point may be left out
     * only when no admissible route exists.
     */
    @Test
    void expensiveOksIsStillConnected() throws Exception {
        TestSupport.Run r = TestSupport.run("expensive.geojson");
        for (Variant v : r.variants) {
            for (Unconnected u : v.unconnected) {
                assertTrue(u.reason != Unconnected.Reason.NO_CONNECTION_POINT,
                        "no point may be dropped because it is expensive: " + u.reason);
            }
        }
        assertTrue(r.variants.get(0).summary.unconnectedOksIds.isEmpty(),
                "oks-far is expensive but reachable: " + r.variants.get(0).summary.unconnectedOksIds);
    }

    /**
     * R-TIE-1 (appendix 2.4, clarification 11): an existing chamber within 10 m with a free branch is used
     * instead of a new chamber on the segment.
     */
    @Test
    void existingChamberWithinTenMetresIsUsed() throws Exception {
        TestSupport.Run r = TestSupport.run("chambers.geojson");
        Variant best = r.variants.get(0);
        assertEquals(1, best.tieIns.size());
        assertTrue(best.tieIns.get(0).existingChamber, "the network must be joined in an existing chamber");
        assertEquals(TestSupport.REF.getTieInCost(), best.summary.existingChamberTieInCost, 1);
        assertEquals(1, best.summary.existingChamberTieInCount);
    }

    /**
     * R-NET-6, R-NET-15 (appendix 2.1, clarification 5): a turn may be of any angle up to 90°, sharper turns do
     * not occur, and a turn never makes a segment cost more.
     */
    @ParameterizedTest
    @ValueSource(strings = {"small.geojson", "random-42.geojson", "contest-lct.geojson", "contest-osm.geojson"})
    void turnsAreWithinNinetyDegreesAndCostNothing(String dataset) throws Exception {
        TestSupport.Run r = TestSupport.run(dataset);
        for (Variant v : r.variants) {
            assertEquals(0, v.metrics.sharpTurns, dataset + " variant " + v.variantId + ": turns sharper than 90°");
            for (Variant.NewSegment s : v.segments) {
                if (!"special".equals(s.layingMethod)) {
                    assertEquals(1.0, s.k, 1e-9, dataset + " " + s.id + ": a plain segment costs K=1");
                }
            }
        }
    }

    /**
     * R-RST-4 (appendix 4, clarification 6): the angle is measured against the boundary the route crosses, and
     * a section that runs along inside the polygon instead of crossing it is not allowed.
     */
    @Test
    void crossingAngleAgainstTheBoundaryOfARoadPolygon() throws Exception {
        WKTReader wkt = new WKTReader();
        // a straight road 10 m wide along x, crossed at 60°
        PolygonBoundary straight = new PolygonBoundary(wkt.read("POLYGON((0 0, 200 0, 200 10, 0 10, 0 0))"));
        List<Double> a = straight.crossingAngles(new Coordinate(100, -20), new Coordinate(100 + 50 / Math.tan(
                Math.toRadians(60)), 30));
        assertEquals(60, min(a), 0.01);
        // the same road with a 1 m bay in the kerb right at the crossing: the side of the bay does not decide
        Geometry bay = wkt.read("POLYGON((0 0, 99 0, 99 -1, 103 -1, 103 0, 200 0, 200 10, 0 10, 0 0))");
        List<Double> b = new PolygonBoundary(bay).crossingAngles(new Coordinate(101, -20), new Coordinate(101, 30));
        assertEquals(90, min(b), 3);
        // a road whose kerbs are not parallel: the strictest of the kerbs around the crossing decides — here the
        // slanted one, 5,7° off the horizontal
        Geometry wedge = wkt.read("POLYGON((0 0, 200 0, 200 30, 0 10, 0 0))");
        List<Double> c = new PolygonBoundary(wedge).crossingAngles(new Coordinate(100, -20), new Coordinate(100, 60));
        assertEquals(84.3, min(c), 0.5);
        // a route that runs along inside the road from one end to the other is not a crossing
        List<Double> along = straight.crossingAngles(new Coordinate(-20, 5), new Coordinate(220, 5));
        assertTrue(min(along) < 45, "running along the road must not pass the angle rule: " + along);
    }

    private static double min(List<Double> angles) {
        double m = 90;
        for (double a : angles) {
            m = Math.min(m, a);
        }
        return m;
    }

    /** R-IN-6: invalid input geometry is a diagnostic error and the object is not used. */
    @Test
    void invalidGeometryIsReportedAsAnError() throws Exception {
        TestSupport.Run r = TestSupport.run("dirty.geojson");
        assertTrue(r.input.getDiagnostics().getIssues().stream().anyMatch(i ->
                "feature.invalid_geometry".equals(i.getCode()) && "ERROR".equals(i.getSeverity().name())));
    }
}
