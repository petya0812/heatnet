package ru.lct.heatnet.input;

import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.reference.RuleOptions;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Whole input held in memory: for tests, the validator and datasets that fit into memory. */
public final class LoadedInput {

    private final InputModel model;
    private final List<Obstacle> obstacles;
    private final Diagnostics diagnostics;
    private final long featureCount;

    private LoadedInput(InputModel model, List<Obstacle> obstacles, Diagnostics diagnostics, long featureCount) {
        this.model = model;
        this.obstacles = obstacles;
        this.diagnostics = diagnostics;
        this.featureCount = featureCount;
    }

    public static LoadedInput read(Path file, ReferenceData ref) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return read(in, ref);
        }
    }

    public static LoadedInput read(Path file, ReferenceData ref, RuleOptions rules) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return read(in, ref, rules);
        }
    }

    public static LoadedInput read(InputStream in, ReferenceData ref) throws IOException {
        return read(in, ref, RuleOptions.defaults());
    }

    public static LoadedInput read(InputStream in, ReferenceData ref, RuleOptions rules) throws IOException {
        Diagnostics diag = new Diagnostics();
        List<ParsedFeature> core = new ArrayList<>();
        List<Obstacle> obstacles = new ArrayList<>();
        java.util.Set<String> obstacleIds = new java.util.HashSet<>();
        long n = new FeatureParser(ref).parse(in, diag, f -> {
            if (!f.getObjectType().isCore() && !obstacleIds.add(f.getId())) {
                diag.error("feature.duplicate_id", f.getId(), "Duplicate id");
            }
            if (f.getObjectType().isCore()) {
                core.add(f);
            } else {
                obstacles.add(new Obstacle(f.getId(), f.getObjectType(), f.getRestrictionType(), f.getUtm()));
            }
        });
        ObstacleSource source = new ObstacleSource.InMemory(obstacles);
        InputModel model = InputModel.build(core, diag, ref, rules, source);
        return new LoadedInput(model, obstacles, diag, n);
    }

    public InputModel getModel() {
        return model;
    }

    public List<Obstacle> getObstacles() {
        return obstacles;
    }

    public ObstacleSource obstacleSource() {
        return new ObstacleSource.InMemory(obstacles);
    }

    public Diagnostics getDiagnostics() {
        return diagnostics;
    }

    public long getFeatureCount() {
        return featureCount;
    }
}
