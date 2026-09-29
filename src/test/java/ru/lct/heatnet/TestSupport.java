package ru.lct.heatnet;

import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.output.ResultWriter;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.plan.Planner;
import ru.lct.heatnet.plan.Variant;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Runs the whole pipeline on a file of {@code test-data/} and keeps the result under {@code target/}. */
public final class TestSupport {

    public static final ReferenceData REF = ReferenceData.load();

    public static final class Run {
        public final LoadedInput input;
        public final List<Variant> variants;
        public final Path output;

        Run(LoadedInput input, List<Variant> variants, Path output) {
            this.input = input;
            this.variants = variants;
            this.output = output;
        }
    }

    private TestSupport() {
    }

    public static Path testData(String name) {
        Path p = Paths.get("test-data", name);
        if (!Files.exists(p)) {
            throw new IllegalStateException("Missing " + p.toAbsolutePath());
        }
        return p;
    }

    private static final java.util.Map<String, Run> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Planning result of a dataset; computed once per test JVM (the planner is deterministic). */
    public static synchronized Run run(String name) throws IOException {
        Run cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        Run r = compute(name);
        CACHE.put(name, r);
        return r;
    }

    private static Run compute(String name) throws IOException {
        PlanParams params = new PlanParams();
        if (System.getProperty("turnPenalty") != null) {
            params.turnPenaltyScore = Double.parseDouble(System.getProperty("turnPenalty"));
        }
        params.journal = Boolean.getBoolean("journal");
        if (System.getProperty("turnStep") != null) {
            params.turnStepDeg = Integer.getInteger("turnStep");
        }
        if (System.getProperty("neighbourRadius") != null) {
            params.frameNeighbourRadiusM = Double.parseDouble(System.getProperty("neighbourRadius"));
        }
        if (System.getProperty("minLeg") != null) {
            params.minLegM = Double.parseDouble(System.getProperty("minLeg"));
        }
        return run(name, params, "");
    }

    /** Uncached run with explicit parameters; the result file gets {@code suffix} in its name. */
    public static Run run(String name, PlanParams params, String suffix) throws IOException {
        LoadedInput in = LoadedInput.read(testData(name), REF, params.rules);
        List<Variant> variants = new Planner(REF, params)
                .plan(in.getModel(), in.obstacleSource(), in.getDiagnostics());
        Path out = Paths.get("target", "results", name.replace(".geojson", "") + suffix + "-result.geojson");
        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out)) {
            new ResultWriter().write(os, variants);
        }
        return new Run(in, variants, out);
    }
}
