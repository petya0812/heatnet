package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.reference.RuleOptions;
import ru.lct.heatnet.validate.OutputValidator;
import ru.lct.heatnet.validate.ValidationReport;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contest dataset (ZIL district): the input has no flow and no {@code upstream_object_id} on the network,
 * no diameter on chambers, no {@code oks_future} polygons — the prospective OKS is the connection point itself
 * and buildings arrive as restrictions of type {@code oks}. Everything missing is completed from geometry.
 */
class ContestDataTest {

    private static ValidationReport validate(TestSupport.Run r, RuleOptions rules) throws Exception {
        try (InputStream in = Files.newInputStream(r.output)) {
            return new OutputValidator(TestSupport.REF, rules).validate(r.input, in);
        }
    }

    @Test
    void inputIsCompletedFromGeometry() throws Exception {
        LoadedInput in = LoadedInput.read(TestSupport.testData("contest-lct.geojson"), TestSupport.REF);
        InputModel m = in.getModel();
        assertEquals(29, m.getSegments().size());
        assertEquals(9, m.getChambers().size());
        assertEquals(17, m.getOks().size());
        assertNotNull(m.getSource());

        // orientation towards the source derived from geometry: every object reaches the source
        for (String id : m.getSegments().keySet()) {
            assertTrue(m.isAttached(id), "segment " + id + " must reach the source");
        }
        for (String id : m.getChambers().keySet()) {
            assertTrue(m.isAttached(id), "chamber " + id + " must reach the source");
        }
        assertTrue(in.getDiagnostics().hasCode("network.topology_derived"));
        assertTrue(in.getDiagnostics().hasCode("network.flow_assumed"));
        assertTrue(in.getDiagnostics().hasCode("network.chamber_dn_assumed"));

        // the DN of a chamber is the largest DN of its segments
        for (InputModel.Chamber c : m.getChambers().values()) {
            int max = 0;
            for (String sid : c.adjacentSegments) {
                max = Math.max(max, m.getSegments().get(sid).dn);
            }
            assertEquals(max, c.dn, "chamber " + c.id);
        }
        // existing flow is unknown: ZERO by default
        for (InputModel.Segment s : m.getSegments().values()) {
            assertEquals(0.0, s.flowTph, 1e-9);
        }
        // every OKS is a connection point inside an existing building that came as a restriction
        for (InputModel.Oks o : m.getOks()) {
            assertEquals(o.id, o.connectionPointId);
            assertTrue(o.flowTph > 0, o.id);
            assertNotNull(o.ownBuildingId, "OKS " + o.id + " must know its building");
            assertTrue(o.polygon.covers(o.connectionPoint), "connection point of " + o.id + " is inside the building");
            assertTrue(o.connectionInside(), "the route comes to the outline of " + o.id);
        }
        // buildings arrive as restrictions and are treated as existing buildings
        assertTrue(in.getObstacles().stream().anyMatch(o -> o.getObjectType() == ru.lct.heatnet.input.ObjectType.OKS_EXISTING));
        assertTrue(in.getDiagnostics().hasCode("restriction.building"));
        assertEquals(0, in.getDiagnostics().getIssues().stream()
                .filter(i -> i.getSeverity() == ru.lct.heatnet.input.Diagnostics.Severity.ERROR).count(),
                in.getDiagnostics().getIssues().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"contest-lct.geojson", "contest-osm.geojson"})
    void rulesHoldOnContestData(String dataset) throws Exception {
        TestSupport.Run r = TestSupport.run(dataset);
        assertEquals(Collections.emptyList(), validate(r, new RuleOptions()).errors());
        assertFalse(r.variants.isEmpty());
        for (Variant v : r.variants) {
            assertFalse(v.segments.isEmpty());
            assertTrue(v.summary.score > 0);
        }
    }

    /**
     * The assumption about the load of the existing network is informational only: it changes the capacity
     * report, never the mandatory result (appendix 2.4, clarification 14).
     */
    @Test
    void loadOfTheExistingNetworkDoesNotChangeTheResult() throws Exception {
        PlanParams empty = new PlanParams();
        PlanParams loaded = new PlanParams();
        loaded.rules.existingFlow = RuleOptions.ExistingFlow.CAPACITY_SHARE;
        loaded.rules.existingFlowShare = 0.9;
        TestSupport.Run zero = TestSupport.run("contest-lct.geojson", empty, "-flow-zero");
        TestSupport.Run share = TestSupport.run("contest-lct.geojson", loaded, "-flow-share");
        assertEquals(Collections.emptyList(), validate(zero, empty.rules).errors());
        assertEquals(Collections.emptyList(), validate(share, loaded.rules).errors());
        assertEquals(zero.variants.get(0).summary.score, share.variants.get(0).summary.score, 1e-9,
                "the score does not depend on the assumed load of the existing network");
        long shortZero = zero.variants.get(0).flowChanges.stream().filter(f -> f.requiredDn > f.existingDn).count();
        long shortShare = share.variants.get(0).flowChanges.stream().filter(f -> f.requiredDn > f.existingDn).count();
        assertTrue(shortShare >= shortZero, String.format(
                "a loaded network runs out of capacity at least as often: %d against %d", shortShare, shortZero));
    }
}
