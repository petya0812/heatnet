package ru.lct.heatnet;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.validate.ValidationReport;

/**
 * Metrics of every variant on every test dataset. Skipped unless {@code -Dbench=<comma separated files>}:
 * {@code mvn test -Dtest=BenchRun -Dbench=small.geojson,random-42.geojson}
 */
class BenchRun {

    @Test
    void bench() throws Exception {
        String files = System.getProperty("bench");
        Assumptions.assumeTrue(files != null && !files.isEmpty(), "bench not requested");
        for (String name : files.split(",")) {
            long t0 = System.currentTimeMillis();
            TestSupport.Run r = TestSupport.run(name.trim());
            long ms = System.currentTimeMillis() - t0;
            ValidationReport rep = PipelineTest.validate(r);
            System.out.printf("%s: %d ms, validation errors %d, warnings %d%n", name, ms, rep.errorCount(),
                    rep.warningCount());
            rep.errors().stream().limit(10).forEach(e -> System.out.println("  " + e));
            if (System.getProperty("diag") != null) {
                System.out.println("  diagnostics: " + r.input.getDiagnostics().getCounts());
            }
            for (Variant v : r.variants) {
                long merged = v.chambers.size() - v.tieIns.stream().filter(t -> !t.existingChamber).count();
                System.out.printf("  v%s rank %d %-10s S=%.4f cost=%.0f new=%.0f ties=%d branch=%d uncon=%s "
                                + "turns=%d (%.2f/km, <10°: %d, sharp: %d) minStraight=%.1f detour=%.3f/%.3f ms=%d%n",
                        v.variantId, v.summary.rank, v.metrics.strategy, v.summary.score, v.summary.calculatedCost,
                        v.summary.newNetworkLength, v.tieIns.size(), merged, v.summary.unconnectedOksIds,
                        v.metrics.turns, v.metrics.turnsPerKm, v.metrics.smallTurns, v.metrics.sharpTurns, v.metrics.minStraightM,
                        v.metrics.meanDetourRatio, v.metrics.maxDetourRatio, v.metrics.computeMillis);
                if (System.getProperty("reasons") != null) {
                    for (ru.lct.heatnet.plan.Unconnected u : v.unconnected) {
                        System.out.printf("    %s: %s %s%n", u.oksId, u.reason, u.detail == null ? "" : u.detail);
                    }
                }
            }
        }
    }
}
