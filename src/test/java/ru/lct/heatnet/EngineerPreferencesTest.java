package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.plan.Planner;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.service.RunParams;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preferences of the engineer are stricter than the appendix, never looser: with any of them the result still
 * passes the independent check of the mandatory rules.
 */
class EngineerPreferencesTest {

    @Test
    void extraClearanceKeepsRulesAndDoesNotShortenRoutes() throws Exception {
        PlanParams p = new PlanParams();
        p.extraClearanceM = 2.0;
        TestSupport.Run r = TestSupport.run("small.geojson", p, "-clearance");
        assertEquals(Collections.emptyList(), PipelineTest.validate(r).errors());
        TestSupport.Run base = TestSupport.run("small.geojson");
        // the baseline leaves some OKS without a route (small.geojson has one); a stricter clearance never adds
        // connections, and the ones it keeps go around obstacles no closer than before
        assertTrue(r.variants.get(0).summary.unconnectedOksIds.containsAll(base.variants.get(0).summary.unconnectedOksIds));
        assertTrue(r.variants.get(0).summary.newNetworkLength
                >= base.variants.get(0).summary.newNetworkLength - 1e-6
                || r.variants.get(0).summary.unconnectedOksIds.size() > base.variants.get(0).summary.unconnectedOksIds.size(),
                "routes with a wider clearance are not shorter");
    }

    @Test
    void perpendicularCrossingsKeepRules() throws Exception {
        PlanParams p = new PlanParams();
        p.minCrossingAngleDeg = 90;
        TestSupport.Run r = TestSupport.run("dense.geojson", p, "-angle");
        assertEquals(Collections.emptyList(), PipelineTest.validate(r).errors());
    }

    @Test
    void separateOnlyGivesOneSeparateVariant() throws Exception {
        PlanParams p = new PlanParams();
        p.jointConnection = false;
        TestSupport.Run r = TestSupport.run("star.geojson", p, "-separate");
        assertEquals(Collections.emptyList(), PipelineTest.validate(r).errors());
        assertEquals(1, r.variants.size());
        for (Variant v : r.variants) {
            assertEquals(Planner.STRATEGY_SEPARATE, v.metrics.strategy);
        }
    }

    @Test
    void freeAnglesKeepRules() throws Exception {
        PlanParams p = new PlanParams();
        p.turnStepDeg = 0;
        TestSupport.Run r = TestSupport.run("small.geojson", p, "-free");
        assertEquals(Collections.emptyList(), PipelineTest.validate(r).errors());
    }

    @Test
    void strictRightAnglesGiveOnlyRightTurns() throws Exception {
        assertOnlyTurnsOf(90, "contest-lct.geojson");
    }

    @Test
    void strictThirtyDegreeStepGivesOnlyItsMultiples() throws Exception {
        assertOnlyTurnsOf(30, "dense.geojson");
    }

    /** With strict angles every turn of every new segment is a multiple of the step, and the rules still hold. */
    private static void assertOnlyTurnsOf(int step, String data) throws Exception {
        PlanParams p = new PlanParams();
        p.turnStepDeg = step;
        p.turnStepStrict = true;
        TestSupport.Run r = TestSupport.run(data, p, "-turns" + step);
        assertEquals(Collections.emptyList(), PipelineTest.validate(r).errors());
        int turns = 0;
        for (Variant v : r.variants) {
            for (Variant.NewSegment s : v.segments) {
                org.locationtech.jts.geom.Coordinate[] c = s.line.getCoordinates();
                for (int k = 1; k + 1 < c.length; k++) {
                    double deg = deflection(c[k - 1], c[k], c[k + 1]);
                    double off = Math.abs(deg - step * Math.round(deg / step));
                    assertTrue(off < 1.0, s.id + ": turn of " + deg + "° is not a multiple of " + step + "°");
                    turns += deg > 1 ? 1 : 0;
                }
            }
        }
        assertTrue(turns > 0, "the routes of " + data + " have turns to check");
    }

    private static double deflection(org.locationtech.jts.geom.Coordinate a, org.locationtech.jts.geom.Coordinate b,
                                     org.locationtech.jts.geom.Coordinate c) {
        double d = Math.toDegrees(Math.atan2(c.y - b.y, c.x - b.x) - Math.atan2(b.y - a.y, b.x - a.x));
        d = Math.abs(((d % 360) + 540) % 360 - 180);
        return d;
    }

    @Test
    void runParamsAreBoundedAndRoundTrip() {
        RunParams rp = new RunParams();
        rp.extraClearanceM = 1.5;
        rp.minCrossingAngleDeg = 60.0;
        rp.turnAngles = "90";
        rp.turnAnglesStrict = true;
        rp.jointConnection = false;
        PlanParams p = rp.apply(new PlanParams());
        assertEquals(1.5, p.extraClearanceM);
        assertEquals(60.0, p.minCrossingAngleDeg);
        assertEquals(90, p.turnStepDeg);
        assertEquals(true, p.turnStepStrict);
        assertEquals(false, p.jointConnection);
        RunParams back = RunParams.of(p);
        assertEquals(1.5, back.extraClearanceM);
        assertEquals(60.0, back.minCrossingAngleDeg);
        assertEquals("90", back.turnAngles);
        assertEquals("45_90", RunParams.of(new PlanParams()).turnAngles, "45° and 90° by default");
        assertEquals(45.0, RunParams.of(new PlanParams()).minCrossingAngleDeg, "the appendix minimum by default");

        RunParams bad = new RunParams();
        bad.extraClearanceM = 11.0;
        assertThrows(IllegalArgumentException.class, () -> bad.apply(new PlanParams()));
        RunParams bad2 = new RunParams();
        bad2.minCrossingAngleDeg = 30.0;
        assertThrows(IllegalArgumentException.class, () -> bad2.apply(new PlanParams()));
        RunParams bad3 = new RunParams();
        bad3.turnAngles = "60";
        assertThrows(IllegalArgumentException.class, () -> bad3.apply(new PlanParams()));
    }
}
