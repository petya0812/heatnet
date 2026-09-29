package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.plan.NetworkState;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.plan.RouteFinder;
import ru.lct.heatnet.plan.SearchTrace;
import ru.lct.heatnet.plan.Variant;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The construction journal of the variants (replay in the UI) and the trace of one route search: recording them
 * does not change the result, and replaying the journal gives the network the variant was assembled from.
 */
class JournalTest {

    @ParameterizedTest
    @ValueSource(strings = {"small.geojson", "chain.geojson", "star.geojson", "dirty.geojson", "dense.geojson",
            "random-42.geojson", "random-7.geojson", "random-100.geojson", "dense-big.geojson",
            "contest-lct.geojson", "contest-osm.geojson"})
    @SuppressWarnings("unchecked")
    void journalDoesNotChangeTheResultAndReplaysToTheVariant(String dataset) throws Exception {
        TestSupport.Run plain = TestSupport.run(dataset);
        PlanParams params = new PlanParams();
        params.journal = true;
        TestSupport.Run recorded = TestSupport.run(dataset, params, "-journal");

        assertArrayEquals(Files.readAllBytes(plain.output), Files.readAllBytes(recorded.output),
                "the output file is the same with and without the journal");
        for (Variant v : plain.variants) {
            assertNull(v.journal, "no journal unless requested");
        }

        for (Variant v : recorded.variants) {
            List<Map<String, Object>> journal = v.journal;
            assertNotNull(journal);
            assertEquals("start", journal.get(0).get("type"));
            assertEquals("finish", journal.get(journal.size() - 1).get("type"));
            assertEquals(v.summary.score, (Double) journal.get(journal.size() - 1).get("score"), 1e-9);
            // replay: apply the changes of every step
            Map<Integer, List<double[]>> edges = new HashMap<>();
            Map<String, Object> last = null;
            Set<String> connectedOks = new HashSet<>();
            for (int i = 0; i < journal.size(); i++) {
                Map<String, Object> e = journal.get(i);
                assertEquals(i, ((Number) e.get("seq")).intValue());
                String type = (String) e.get("type");
                if ("connect".equals(type) || "rebuild".equals(type)) {
                    for (Integer id : (List<Integer>) e.get("edges_removed")) {
                        assertNotNull(edges.remove(id), "removed edge " + id + " existed");
                    }
                    for (Map<String, Object> x : (List<Map<String, Object>>) e.get("edges")) {
                        edges.put((Integer) x.get("id"), (List<double[]>) x.get("coords"));
                    }
                    last = e;
                    if (e.containsKey("route")) {
                        connectedOks.add((String) e.get("oks"));
                        List<Map<String, Object>> alts = (List<Map<String, Object>>) e.get("alternatives");
                        assertFalse(alts.isEmpty(), "the chosen goal is among the alternatives");
                        assertTrue(alts.stream().anyMatch(a -> Boolean.TRUE.equals(a.get("chosen"))));
                    }
                }
            }
            int routes = v.metrics.routes;
            if (routes == 0) {
                continue;
            }
            assertNotNull(last);
            assertEquals(routes, ((Number) last.get("connected")).intValue(), dataset + " v" + v.variantId);
            double len = 0;
            for (List<double[]> c : edges.values()) {
                for (int i = 0; i + 1 < c.size(); i++) {
                    len += new Coordinate(c.get(i)[0], c.get(i)[1]).distance(new Coordinate(c.get(i + 1)[0],
                            c.get(i + 1)[1]));
                }
            }
            assertEquals(v.summary.newNetworkLength, len, 0.05 * Math.max(1, v.segments.size()),
                    dataset + " v" + v.variantId + ": the replayed network is the network of the variant");
            assertEquals(v.summary.newNetworkLength, ((Number) last.get("network_length")).doubleValue(),
                    0.05 * Math.max(1, v.segments.size()));
        }
    }

    @Test
    void traceOfOneSearch() throws Exception {
        LoadedInput in = LoadedInput.read(TestSupport.testData("contest-lct.geojson"), TestSupport.REF);
        InputModel m = in.getModel();
        PlanParams params = new PlanParams();
        RouteFinder finder = new RouteFinder(TestSupport.REF, params, m, in.obstacleSource());
        for (InputModel.Oks oks : m.getOks()) {
            SearchTrace t = finder.trace(oks, new NetworkState(m, TestSupport.REF));
            assertNotNull(t.route, "route of " + oks.id);
            assertTrue(t.starts >= 1 && t.vertices.size() >= t.starts);
            assertFalse(t.expansions.isEmpty());
            assertTrue(t.expansions.size() <= params.maxExpansions + 1);
            int[] lastStep = t.expansions.get(t.expansions.size() - 1);
            assertEquals(-1, lastStep[0], "the trace ends with the goal");
            for (int[] x : t.expansions) {
                assertTrue(x[1] < t.vertices.size());
            }
            assertFalse(t.zones.isEmpty(), "buildings around are zones of the search");
            assertEquals(t.zones.size(), t.zoneKinds.size());
            assertEquals(t.rejected.size(), t.rejectedReasons.size());
        }
    }
}
