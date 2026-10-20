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
    /**
     * Directions of the pipe joined at {@link #point}, away from it: two opposite ones inside a straight piece, the
     * two pieces at a bend, every edge at a junction. Null — the angle of joining does not matter (an existing chamber).
     */
    double[][] pipeDirs;

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

    /**
     * The place of attachment for telling variants apart: the existing chamber, or the existing segment as
     * {@link InputModel#placeOf} names the point (a point where two segments meet is one place).
     */
    String place;

    String place() {
        return place != null ? place : existingObjectId();
    }

    /**
     * Directions away from the point {@code pos} metres along a polyline: both ways inside a piece, the two pieces at a
     * turning point (one at an end of the polyline).
     */
    static double[][] dirsAt(java.util.List<Coordinate> cs, double pos) {
        double acc = 0;
        for (int i = 0; i + 1 < cs.size(); i++) {
            Coordinate a = cs.get(i);
            Coordinate b = cs.get(i + 1);
            double l = a.distance(b);
            if (l < 1e-9) {
                continue;
            }
            if (pos <= acc + l + 1e-6 || i + 2 == cs.size()) {
                if (Math.abs(pos - acc) <= 1e-6) {
                    return i == 0 ? new double[][]{{b.x - a.x, b.y - a.y}} : vertexDirs(cs, i);
                }
                if (Math.abs(pos - acc - l) <= 1e-6) {
                    return i + 2 == cs.size() ? new double[][]{{a.x - b.x, a.y - b.y}} : vertexDirs(cs, i + 1);
                }
                return new double[][]{{b.x - a.x, b.y - a.y}, {a.x - b.x, a.y - b.y}};
            }
            acc += l;
        }
        return null;
    }

    private static double[][] vertexDirs(java.util.List<Coordinate> cs, int k) {
        Coordinate c = cs.get(k);
        Coordinate p = cs.get(k - 1);
        Coordinate n = cs.get(k + 1);
        return new double[][]{{p.x - c.x, p.y - c.y}, {n.x - c.x, n.y - c.y}};
    }

    /** The key follows from final fields only: made once (the search asks for it again and again). */
    private String key;

    public String key() {
        String k = key;
        if (k == null) {
            if (isAttach()) {
                k = attachNodeId > 0 ? "j:" + attachNodeId : String.format("e:%d:%.2f", attachEdgeId, attachPos);
            } else {
                k = chamber != null ? "c:" + chamber.id : String.format("s:%s:%.2f", segment.id, t);
            }
            key = k;
        }
        return k;
    }
}
