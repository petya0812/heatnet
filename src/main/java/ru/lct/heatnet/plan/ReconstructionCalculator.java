package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Load of the existing network after the new connections — an informational layer only: the appendix (2.4) does
 * not rebuild the existing network, so nothing here enters the cost, the score or the output file. The additional
 * flow of every attachment goes up the chain to the source; on the attachment segment only the part from the
 * attachment point towards the source is loaded. Pieces whose required DN exceeds the existing one are reported
 * as short of capacity.
 */
public final class ReconstructionCalculator {

    /** Additional flow entering the existing network. */
    public static final class Load {
        public final String objectId;
        public final boolean chamber;
        /** Position along the segment from its upstream end (segments only). */
        public final double t;
        public final double flowTph;

        public Load(String objectId, boolean chamber, double t, double flowTph) {
            this.objectId = objectId;
            this.chamber = chamber;
            this.t = t;
            this.flowTph = flowTph;
        }
    }

    /** A piece of an existing segment with a constant additional flow. */
    public static final class Piece {
        public final InputModel.Segment segment;
        public final double from;
        public final double to;
        public final double addedFlow;
        public final int requiredDn;

        Piece(InputModel.Segment segment, double from, double to, double addedFlow, int requiredDn) {
            this.segment = segment;
            this.from = from;
            this.to = to;
            this.addedFlow = addedFlow;
            this.requiredDn = requiredDn;
        }

        public boolean needsReconstruction() {
            return requiredDn > segment.dn;
        }

        public LineString line() {
            return (LineString) new LengthIndexedLine(segment.line).extractLine(from, to);
        }
    }

    /** Pieces shorter than this (an attachment practically at the upstream end of a segment) are not reported. */
    public static final double MIN_RECON_PIECE_M = 0.1;

    private final InputModel model;
    private final ReferenceData ref;

    public ReconstructionCalculator(InputModel model, ReferenceData ref) {
        this.model = model;
        this.ref = ref;
    }

    /** All loaded pieces of existing segments (whether or not the capacity suffices), ordered by segment. */
    public List<Piece> compute(List<Load> loads, Diagnostics diag) {
        Map<String, Double> full = new HashMap<>();
        Map<String, TreeMap<Double, Double>> partial = new LinkedHashMap<>();
        for (Load l : loads) {
            String start;
            InputModel.Chamber lc = l.chamber ? model.getChambers().get(l.objectId) : null;
            if (lc != null && lc.insideSegment != null) {
                // the chamber sits inside its upstream segment: only the part towards the source is loaded
                partial.computeIfAbsent(lc.insideSegment.id, k -> new TreeMap<>()).merge(lc.insideT, l.flowTph, Double::sum);
                start = lc.insideSegment.upstreamId;
            } else if (l.chamber) {
                start = lc.upstreamId;
            } else {
                partial.computeIfAbsent(l.objectId, k -> new TreeMap<>()).merge(l.t, l.flowTph, Double::sum);
                start = model.getSegments().get(l.objectId).upstreamId;
            }
            Set<String> seen = new HashSet<>();
            String cur = start;
            while (cur != null && !model.isSource(cur) && seen.add(cur)) {
                full.merge(cur, l.flowTph, Double::sum);
                cur = model.upstreamOf(cur);
            }
        }
        List<Piece> out = new ArrayList<>();
        for (InputModel.Segment s : model.getSegments().values()) {
            double f = full.getOrDefault(s.id, 0.0);
            TreeMap<Double, Double> ties = partial.get(s.id);
            if (f == 0 && ties == null) {
                continue;
            }
            double len = s.length();
            List<double[]> cuts = new ArrayList<>(); // [from, to, added]
            if (ties == null) {
                cuts.add(new double[]{0, len, f});
            } else {
                // Flow of a tie at t acts on [0, t]; pieces between consecutive tie positions.
                double prev = 0;
                List<Double> ts = new ArrayList<>(ties.keySet());
                double suffix = 0;
                double[] suffixFrom = new double[ts.size() + 1];
                for (int i = ts.size() - 1; i >= 0; i--) {
                    suffix += ties.get(ts.get(i));
                    suffixFrom[i] = suffix;
                }
                for (int i = 0; i < ts.size(); i++) {
                    double t = Math.min(len, Math.max(0, ts.get(i)));
                    cuts.add(new double[]{prev, t, f + suffixFrom[i]});
                    prev = t;
                }
                if (f > 0) {
                    cuts.add(new double[]{prev, len, f});
                }
            }
            for (double[] c : cuts) {
                if (c[1] - c[0] < 1e-6 || c[2] <= 0) {
                    continue;
                }
                PipeSpec req = ref.minPipeForFlow(s.flowTph + c[2]);
                int reqDn;
                if (req == null) {
                    reqDn = ref.getPipes().get(ref.getPipes().size() - 1).getDn();
                    diag.warning("recon.flow_exceeds_max_dn", s.id, "Flow exceeds the capacity of the largest DN");
                } else {
                    reqDn = req.getDn();
                }
                out.add(new Piece(s, c[0], c[1], c[2], reqDn));
            }
        }
        return out;
    }
}
