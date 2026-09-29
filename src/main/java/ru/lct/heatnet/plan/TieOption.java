package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.input.InputModel;

/** A candidate place to connect to the existing network: an existing chamber or a point on a segment. */
public final class TieOption {

    public final Coordinate point;
    /** Non-null for a tie-in into an existing chamber. */
    public final InputModel.Chamber chamber;
    /** Non-null for a tie-in into a segment (a new chamber is built there). */
    public final InputModel.Segment segment;
    /** Position along the segment from its upstream end, metres. */
    public final double t;
    /** Estimated score of the attachment itself: the tie-in cost or the new chamber. */
    public final double terminalScore;
    /** Joining the new network of the variant: edge id and position on it, or an existing junction id. */
    final int attachEdgeId;
    final double attachPos;
    final int attachNodeId;
    /** Joining the new network: the largest DN at the joining point after the join (its clearances apply there). */
    int attachDn;

    TieOption(Coordinate point, InputModel.Chamber chamber, InputModel.Segment segment, double t,
              double terminalScore) {
        this.point = point;
        this.chamber = chamber;
        this.segment = segment;
        this.t = t;
        this.terminalScore = terminalScore;
        this.attachEdgeId = 0;
        this.attachPos = 0;
        this.attachNodeId = 0;
    }

    /** Joining the new network at a point of a draft edge ({@code edgeId > 0}) or at a junction ({@code nodeId > 0}). */
    TieOption(Coordinate point, int edgeId, double pos, int nodeId, double terminalScore) {
        this.point = point;
        this.chamber = null;
        this.segment = null;
        this.t = 0;
        this.terminalScore = terminalScore;
        this.attachEdgeId = edgeId;
        this.attachPos = pos;
        this.attachNodeId = nodeId;
    }

    public boolean isAttach() {
        return attachEdgeId > 0 || attachNodeId > 0;
    }

    public String existingObjectId() {
        return chamber != null ? chamber.id : segment != null ? segment.id : null;
    }

    public String key() {
        if (isAttach()) {
            return attachNodeId > 0 ? "j:" + attachNodeId : String.format("e:%d:%.2f", attachEdgeId, attachPos);
        }
        return chamber != null ? "c:" + chamber.id : String.format("s:%s:%.2f", segment.id, t);
    }
}
