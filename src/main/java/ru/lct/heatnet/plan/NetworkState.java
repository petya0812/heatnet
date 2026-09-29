package ru.lct.heatnet.plan;

import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.reference.PipeSpec;
import ru.lct.heatnet.reference.ReferenceData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * State of the existing network while routes are added one by one: new branches per existing chamber and the
 * attachment points already used. Keeps the chamber rules (free branches within 10 m, spacing of attachments)
 * during the search.
 */
public final class NetworkState {

    private final InputModel model;
    private final ReferenceData ref;
    private final Map<String, Integer> usedChamberBranches = new HashMap<>();
    private final Map<String, Double> addedFlow = new HashMap<>();
    private final java.util.List<org.locationtech.jts.geom.Coordinate> tiePoints = new java.util.ArrayList<>();
    /** Two tie-ins closer than this would need one chamber, and one chamber means one part of the network. */
    public static final double TIE_SPACING_M = 1.0;

    public NetworkState(InputModel model, ReferenceData ref) {
        this.model = model;
        this.ref = ref;
    }

    /** State after all trees of a draft are connected (flows must be recomputed). */
    static NetworkState fromDraft(InputModel model, ReferenceData ref, Draft d) {
        NetworkState st = new NetworkState(model, ref);
        for (Draft.Node r : d.roots) {
            st.commit(r.tie, Draft.treeFlow(r));
        }
        return st;
    }

    /**
     * A tie-in into a segment this close to another segment tie-in of the same variant is not allowed: both would
     * need a new chamber in the same place. Two parts may tie into one existing chamber — that is a different case,
     * limited by the number of its free branches.
     */
    public boolean tooCloseToSegmentTie(org.locationtech.jts.geom.Coordinate c) {
        for (org.locationtech.jts.geom.Coordinate t : tiePoints) {
            if (t.distance(c) < TIE_SPACING_M) {
                return true;
            }
        }
        return false;
    }

    public int freeBranches(InputModel.Chamber c) {
        return ref.getMaxChamberDegree() - c.existingDegree - usedChamberBranches.getOrDefault(c.id, 0);
    }

    public double addedFlow(String objectId) {
        return addedFlow.getOrDefault(objectId, 0.0);
    }

    /** Registers a committed tie-in: one more branch in the chamber (if any) and flow up to the source. */
    public void commit(TieOption tie, double flowTph) {
        if (tie.chamber == null) {
            tiePoints.add(tie.point);
        }
        if (tie.chamber != null) {
            usedChamberBranches.merge(tie.chamber.id, 1, Integer::sum);
            if (tie.chamber.insideSegment != null) {
                addedFlow.merge(tie.chamber.insideSegment.id, flowTph, Double::sum);
                addChain(tie.chamber.insideSegment.upstreamId, flowTph);
            } else {
                addChain(tie.chamber.upstreamId, flowTph);
            }
        } else {
            addedFlow.merge(tie.segment.id, flowTph, Double::sum);
            addChain(tie.segment.upstreamId, flowTph);
        }
    }

    private void addChain(String startId, double flowTph) {
        Set<String> seen = new HashSet<>();
        String cur = startId;
        while (cur != null && !model.isSource(cur) && seen.add(cur)) {
            addedFlow.merge(cur, flowTph, Double::sum);
            cur = model.upstreamOf(cur);
        }
    }

    /** DN of an existing segment after adding flow (existing DN if it is enough). */
    public int dnAfter(InputModel.Segment s, double extraFlow) {
        PipeSpec req = ref.minPipeForFlow(s.flowTph + addedFlow(s.id) + extraFlow);
        int reqDn = req == null ? ref.getPipes().get(ref.getPipes().size() - 1).getDn() : req.getDn();
        return Math.max(s.dn, reqDn);
    }

}
