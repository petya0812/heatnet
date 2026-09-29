package ru.lct.heatnet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.plan.Planner;
import ru.lct.heatnet.plan.Unconnected;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.service.RunParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the service shows besides the output file: reasons of unconnected OKS, continuous parts of one DN,
 * additional flow on the existing network, progress and cancellation of a calculation, per-run parameters.
 */
class ServiceLayersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Unconnected reason(Variant v, String oks) {
        return v.unconnected.stream().filter(u -> u.oksId.equals(oks)).findFirst().orElse(null);
    }

    @Test
    void unconnectedReasonsOfDirtyData() throws Exception {
        TestSupport.Run r = TestSupport.run("dirty.geojson");
        for (Variant v : r.variants) {
            assertEquals(2, v.unconnected.size());
            assertEquals(Unconnected.Reason.FLOW_EXCEEDS_MAX_DN, reason(v, "oks-big").reason);
            assertEquals(Unconnected.Reason.NO_CONNECTION_POINT, reason(v, "oks-nocp").reason);
            assertEquals(v.summary.unconnectedOksIds.size(), v.unconnected.size());
        }
    }

    @Test
    void enclosedOksHasNoRoute() throws Exception {
        // oks-1 of the small set inside a closed ring of water: no admissible route, the reason says so
        ObjectNode fc = (ObjectNode) MAPPER.readTree(TestSupport.testData("small.geojson").toFile());
        double cx = 37.615847;
        double cy = 55.750150;
        double[][] outer = square(cx, cy, 0.0016, 0.0010);
        double[][] inner = square(cx, cy, 0.0011, 0.0007);
        ObjectNode ring = MAPPER.createObjectNode();
        ring.put("type", "Feature");
        ObjectNode props = ring.putObject("properties");
        props.put("id", "water-ring");
        props.put("object_type", "restriction");
        props.put("restriction_type", "water");
        ObjectNode geom = ring.putObject("geometry");
        geom.put("type", "Polygon");
        ArrayNode rings = geom.putArray("coordinates");
        rings.add(MAPPER.valueToTree(outer));
        rings.add(MAPPER.valueToTree(inner));
        ((ArrayNode) fc.get("features")).add(ring);
        Path file = Paths.get("target", "tmp", "small-enclosed.geojson");
        Files.createDirectories(file.getParent());
        MAPPER.writeValue(file.toFile(), fc);

        LoadedInput in = LoadedInput.read(file, TestSupport.REF);
        List<Variant> variants = new Planner(TestSupport.REF, new PlanParams())
                .plan(in.getModel(), in.obstacleSource(), in.getDiagnostics());
        for (Variant v : variants) {
            Unconnected u = reason(v, "oks-1");
            assertNotNull(u, "oks-1 is enclosed and cannot be connected");
            assertEquals(Unconnected.Reason.NO_ROUTE, u.reason);
            assertNotNull(u.detail);
        }
    }

    private static double[][] square(double cx, double cy, double dx, double dy) {
        return new double[][]{{cx - dx, cy - dy}, {cx + dx, cy - dy}, {cx + dx, cy + dy}, {cx - dx, cy + dy},
                {cx - dx, cy - dy}};
    }

    @ParameterizedTest
    @ValueSource(strings = {"small.geojson", "chain.geojson", "star.geojson", "random-42.geojson"})
    void lengthRunsCoverEverySegmentOnce(String dataset) throws Exception {
        TestSupport.Run r = TestSupport.run(dataset);
        for (Variant v : r.variants) {
            Map<String, Variant.NewSegment> byId = new HashMap<>();
            v.segments.forEach(s -> byId.put(s.id, s));
            Set<String> seen = new HashSet<>();
            for (Variant.LengthRun run : v.lengthRuns) {
                double total = 0;
                for (String id : run.segmentIds) {
                    assertTrue(seen.add(id), "segment " + id + " in two runs");
                    assertEquals(run.dn, byId.get(id).dn, "one DN inside a run");
                    total += byId.get(id).length;
                }
                assertEquals(total, run.totalLength, 1e-6);
                assertTrue(run.pathLength <= run.totalLength + 1e-6);
                assertTrue(run.measured <= run.limit + 1e-6, "length limit of DN " + run.dn + " holds: "
                        + run.measured + " > " + run.limit);
            }
            assertEquals(byId.keySet(), seen, "every segment belongs to a run");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small.geojson", "random-42.geojson"})
    void flowChangesCoverTheExistingNetwork(String dataset) throws Exception {
        TestSupport.Run r = TestSupport.run(dataset);
        for (Variant v : r.variants) {
            assertFalse(v.flowChanges.isEmpty(), "every attachment adds flow to the existing network");
            for (Variant.FlowChange f : v.flowChanges) {
                assertTrue(f.addedFlowTph > 0);
                assertTrue(f.requiredDn >= f.existingDn);
            }
        }
    }

    @Test
    void progressIsReportedAndCancellationStopsThePlanner() throws Exception {
        LoadedInput in = LoadedInput.read(TestSupport.testData("dense-big.geojson"), TestSupport.REF);
        AtomicInteger updates = new AtomicInteger();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<List<Variant>> f = ex.submit(() -> new Planner(TestSupport.REF, new PlanParams())
                    .withProgress((stage, done, total) -> updates.incrementAndGet())
                    .plan(in.getModel(), in.obstacleSource(), in.getDiagnostics()));
            long until = System.currentTimeMillis() + 20_000;
            while (updates.get() < 5 && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            assertTrue(updates.get() >= 5, "progress is reported while planning");
            f.cancel(true);
            long t0 = System.currentTimeMillis();
            ex.shutdown();
            assertTrue(ex.awaitTermination(15, TimeUnit.SECONDS), "the planner stops after cancellation");
            assertTrue(System.currentTimeMillis() - t0 < 15_000);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void interruptedPlannerThrowsCancellation() throws Exception {
        LoadedInput in = LoadedInput.read(TestSupport.testData("random-42.geojson"), TestSupport.REF);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> new Planner(TestSupport.REF, new PlanParams())
                    .plan(in.getModel(), in.obstacleSource(), in.getDiagnostics()));
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small.geojson", "contest-lct.geojson"})
    void resultDoesNotDependOnTheNumberOfThreads(String dataset) throws Exception {
        // the planning threads share obstacle zones; the result must be the same as with one thread
        PlanParams one = new PlanParams();
        one.threads = 1;
        PlanParams four = new PlanParams();
        four.threads = 4;
        TestSupport.Run a = TestSupport.run(dataset, one, "-t1");
        TestSupport.Run b = TestSupport.run(dataset, four, "-t4");
        assertEquals(a.variants.size(), b.variants.size());
        for (int i = 0; i < a.variants.size(); i++) {
            assertEquals(a.variants.get(i).summary.score, b.variants.get(i).summary.score, 1e-9,
                    "variant " + i + " of " + dataset);
            assertEquals(a.variants.get(i).summary.unconnectedOksIds, b.variants.get(i).summary.unconnectedOksIds);
            assertEquals(a.variants.get(i).segments.size(), b.variants.get(i).segments.size());
        }
    }

    @Test
    void runParametersOverrideDefaults() {
        PlanParams defaults = new PlanParams();
        RunParams req = new RunParams();
        req.turnPenalty = 0.4;
        req.existingFlow = "capacity_share";
        req.existingFlowShare = 0.8;
        PlanParams p = req.apply(defaults);
        assertEquals("CAPACITY_SHARE", p.rules.existingFlow.name());
        assertEquals(0.8, p.rules.existingFlowShare, 1e-12);
        assertEquals("ZERO", defaults.rules.existingFlow.name());
        assertEquals(0.4, p.turnPenaltyScore, 1e-12);
        // defaults untouched, other fields copied
        assertEquals(defaults.maxExpansions, p.maxExpansions);
        assertEquals(defaults.threads, p.threads);

        PlanParams same = new RunParams().apply(defaults);
        assertEquals(RunParams.of(defaults).existingFlow, RunParams.of(same).existingFlow);
        assertEquals(defaults.turnPenaltyScore, same.turnPenaltyScore, 1e-12);

        RunParams bad = new RunParams();
        bad.existingFlow = "SOMETIMES";
        assertThrows(IllegalArgumentException.class, () -> bad.apply(defaults));
        RunParams badPenalty = new RunParams();
        badPenalty.turnPenalty = -1.0;
        assertThrows(IllegalArgumentException.class, () -> badPenalty.apply(defaults));
        RunParams badShare = new RunParams();
        badShare.existingFlowShare = 1.5;
        assertThrows(IllegalArgumentException.class, () -> badShare.apply(defaults));
    }
}
