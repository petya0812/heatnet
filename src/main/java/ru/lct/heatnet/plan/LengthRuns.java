package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.LineString;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.reference.RuleOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Continuous parts of one DN of the new network (R-NET-10) for display: segments of the same DN joined through
 * chambers and technical nodes. The rule itself is enforced by {@link Draft} and checked by the output validator.
 */
final class LengthRuns {

    private LengthRuns() {
    }

    static void compute(Variant v, ReferenceData ref, RuleOptions rules) {
        Map<String, List<Variant.NewSegment>> down = new HashMap<>();
        Map<String, Variant.NewSegment> up = new HashMap<>();
        for (Variant.NewSegment s : v.segments) {
            down.computeIfAbsent(s.startNodeId, k -> new ArrayList<>()).add(s);
            up.put(s.endNodeId, s);
        }
        for (Variant.NewSegment top : v.segments) {
            Variant.NewSegment parent = up.get(top.startNodeId);
            if (parent != null && parent.dn == top.dn) {
                continue; // not the upstream end of a run
            }
            Variant.LengthRun run = new Variant.LengthRun();
            run.dn = top.dn;
            List<LineString> lines = new ArrayList<>();
            run.pathLength = walk(top, down, run, lines);
            run.measured = run.totalLength;
            run.limit = ref.pipe(top.dn).getMaxLengthM();
            run.line = Crs.UTM_FACTORY.createMultiLineString(lines.toArray(new LineString[0]));
            v.lengthRuns.add(run);
        }
    }

    /** Adds the segment and its same-DN descendants to the run; returns the longest path length from its start. */
    private static double walk(Variant.NewSegment s, Map<String, List<Variant.NewSegment>> down, Variant.LengthRun run,
                               List<LineString> lines) {
        run.totalLength += s.length;
        run.segmentIds.add(s.id);
        lines.add(s.line);
        double longest = 0;
        for (Variant.NewSegment c : down.getOrDefault(s.endNodeId, new ArrayList<>())) {
            if (c.dn == s.dn) {
                longest = Math.max(longest, walk(c, down, run, lines));
            }
        }
        return s.length + longest;
    }
}
