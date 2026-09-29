package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatnet.plan.Planner;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.validate.OutputValidator;
import ru.lct.heatnet.validate.ValidationReport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole pipeline on the test datasets: parse → plan → write → independent validation. */
class PipelineTest {

    static ValidationReport validate(TestSupport.Run r) throws IOException {
        try (InputStream in = Files.newInputStream(r.output)) {
            return new OutputValidator(TestSupport.REF).validate(r.input, in);
        }
    }

    static Variant byStrategy(TestSupport.Run r, String strategy) {
        for (Variant v : r.variants) {
            if (strategy.equals(v.metrics.strategy)) {
                return v;
            }
        }
        throw new AssertionError("no variant " + strategy);
    }

    /** Mandatory rules hold in every variant; 2–3 distinct variants, ranked by score, separate baseline among them. */
    @ParameterizedTest
    @ValueSource(strings = {"small.geojson", "chain.geojson", "star.geojson", "dirty.geojson", "dense.geojson",
            "random-42.geojson", "random-7.geojson", "random-100.geojson", "dense-big.geojson"})
    void rulesHoldInEveryVariant(String name) throws Exception {
        TestSupport.Run r = TestSupport.run(name);
        ValidationReport rep = validate(r);
        assertEquals(Collections.emptyList(), rep.errors(), "mandatory rules");
        assertTrue(r.variants.size() >= 2 && r.variants.size() <= 3, "2–3 variants");
        for (int i = 0; i < r.variants.size(); i++) {
            assertEquals(i + 1, r.variants.get(i).summary.rank);
        }
        Variant best = r.variants.get(0);
        Variant sep = byStrategy(r, Planner.STRATEGY_SEPARATE);
        assertTrue(best.summary.score <= sep.summary.score, "best is not worse than separate");
        assertTrue(rep.getChecked().getOrDefault("R-VAR", 0) >= 2);
    }

    @Test
    void smallScenario() throws Exception {
        TestSupport.Run r = TestSupport.run("small.geojson");
        for (Variant v : r.variants) {
            // oks-5 is walled in; any other point may stay unconnected only because no route fits the rules
            assertTrue(v.summary.unconnectedOksIds.contains("oks-5"));
            for (ru.lct.heatnet.plan.Unconnected u : v.unconnected) {
                assertTrue("oks-5".equals(u.oksId)
                        || u.reason == ru.lct.heatnet.plan.Unconnected.Reason.LENGTH_LIMIT
                        || u.reason == ru.lct.heatnet.plan.Unconnected.Reason.NO_ROUTE, u.oksId + " " + u.reason);
            }
        }
        Variant sep = byStrategy(r, Planner.STRATEGY_SEPARATE);
        Set<Double> ks = new HashSet<>();
        for (Variant.NewSegment s : sep.segments) {
            if ("special".equals(s.layingMethod)) {
                ks.add(s.k);
            }
        }
        assertTrue(ks.contains(1.60) && ks.contains(1.25) && ks.contains(1.75), "road, gas, tram crossed: " + ks);
        assertTrue(sep.flowChanges.stream().anyMatch(f -> f.requiredDn > f.existingDn),
                "m1 runs out of capacity with any connection (informational layer)");
        assertTrue(sep.tieIns.stream().anyMatch(t -> t.existingChamber));
        assertTrue(r.input.getDiagnostics().hasCode("restriction.unknown_type"));
    }

    @Test
    void dirtyDataIsReportedAndHandled() throws Exception {
        TestSupport.Run r = TestSupport.run("dirty.geojson");
        for (String code : new String[]{"feature.geometry_type", "feature.invalid_geometry", "feature.duplicate_id",
                "network.chain_cycle", "network.chamber_gap", "network.chamber_inside_segment", "network.nonstandard_dn",
                "network.upstream_not_adjacent", "oks.connection_point_inside", "oks.connection_point_off_boundary",
                "oks.connection_point_orphan", "oks.flow_exceeds_max_dn", "oks.no_connection_point",
                "restriction.unknown_type"}) {
            assertTrue(r.input.getDiagnostics().hasCode(code), "diagnostics must report " + code);
        }
        for (Variant v : r.variants) {
            assertEquals(java.util.Arrays.asList("oks-big", "oks-nocp"), v.summary.unconnectedOksIds);
            assertTrue(v.tieIns.stream().noneMatch(t -> t.existingObjectId.startsWith("x")), "no tie-in into a detached part");
        }
        assertTrue(r.input.getModel().getChambers().get("k2").existingDegree >= 3, "line through k2 counts twice");
    }

    @Test
    void chainIsConnectedJointly() throws Exception {
        TestSupport.Run r = TestSupport.run("chain.geojson");
        Variant best = r.variants.get(0);
        Variant sep = byStrategy(r, Planner.STRATEGY_SEPARATE);
        assertTrue(best.metrics.strategy.startsWith(Planner.STRATEGY_JOINT), best.metrics.strategy);
        assertTrue(best.summary.score < sep.summary.score);
        assertTrue(best.metrics.junctions > 0 && best.tieIns.size() < sep.tieIns.size());
    }

    @Test
    void randomCityJointBeatsSeparate() throws Exception {
        TestSupport.Run r = TestSupport.run("random-42.geojson");
        Variant best = r.variants.get(0);
        // a point is left out only when the rules leave no admissible route for it
        for (ru.lct.heatnet.plan.Unconnected u : best.unconnected) {
            assertTrue(u.reason == ru.lct.heatnet.plan.Unconnected.Reason.LENGTH_LIMIT
                    || u.reason == ru.lct.heatnet.plan.Unconnected.Reason.NO_ROUTE, u.oksId + " " + u.reason);
        }
        assertTrue(best.summary.score <= 71.953, "not worse than the baseline of 15.09.2026 (docs/metrics.md)");
        assertTrue(best.tieIns.size() < 30);
        // the baseline of 15.09.2026 had 9.11 turns per km of new network (turns sharper than 2°); at least 30 % fewer
        assertTrue(best.metrics.turnsPerKm <= 9.11 * 0.7, "turns per km: " + best.metrics.turnsPerKm);
        assertTrue(best.metrics.minStraightM >= 1.0, "no tiny straight pieces: " + best.metrics.minStraightM);
    }
}
