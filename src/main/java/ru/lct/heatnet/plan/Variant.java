package ru.lct.heatnet.plan;

import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.List;

/** One connection variant: all output objects of technical appendix section 7, in UTM. */
public final class Variant {

    public static final class NewSegment {
        public String id;
        public LineString line;
        public String startNodeId;
        public String endNodeId;
        public double flowTph;
        public int dn;
        public double length;
        public String layingMethod;
        public double k;
        public double cost;
    }

    /**
     * Where a part of the new network joins the existing one. Not an object of the output file
     * (appendix 2.4, clarification 13): it is kept for the API and the map.
     */
    public static final class TieIn {
        public String id;
        public Point point;
        public String existingObjectId;
        public String existingObjectType;
        public int existingDn;
        public int requiredDn;
        /** The join goes into an existing chamber: then it is paid as a tie-in. */
        public boolean existingChamber;
        public double cost;
        public double flowTph;
        public double positionM;
    }

    public static final class NewChamber {
        public String id;
        public Point point;
        public int dn;
        public double cost;
    }

    public static final class TechnicalNode {
        public String id;
        public Point point;
    }

    /** Summary of a variant, exactly the attributes of section 7.2 of the appendix. */
    public static final class Summary {
        public String id;
        public int rank;
        public double constructionCost;
        public double chamberConstructionCost;
        public int existingChamberTieInCount;
        public double existingChamberTieInCost;
        public double unconnectedPenalty;
        public double calculatedCost;
        public double newNetworkLength;
        public double score;
        public List<String> unconnectedOksIds = new ArrayList<>();
    }

    /** Route quality figures, not part of the output file. */
    public static final class Metrics {
        public int routes;
        public int turns;
        public double meanDetourRatio;
        public double maxDetourRatio;
        public long computeMillis;
        public String strategy;
        public String explanation;
        /** How the variant was built, without the counts of {@link #explanation}. */
        public String approach;
        public int trees;
        public int junctions;
        /** Turns sharper than 2°, of them below 10°, per km of new network; shortest straight piece. */
        public int smallTurns;
        /** Turns sharper than the allowed 90° (must be zero). */
        public int sharpTurns;
        public double turnsPerKm;
        public double minStraightM;
    }

    /** Additional flow on a piece of an existing segment (whether or not its DN suffices) — for the map. */
    public static final class FlowChange {
        public LineString line;
        public String existingObjectId;
        public double existingFlowTph;
        public double addedFlowTph;
        public int existingDn;
        public int requiredDn;
    }

    /** Continuous part of the new network of one DN (R-NET-10) — for the map. */
    public static final class LengthRun {
        public org.locationtech.jts.geom.MultiLineString line;
        public int dn;
        /** Longest path inside the run from its upstream end. */
        public double pathLength;
        /** Total length of the run. */
        public double totalLength;
        /** The measure used by the rule options (path or total). */
        public double measured;
        public double limit;
        public final List<String> segmentIds = new ArrayList<>();
    }

    /** One prospective OKS in the variant: how it is connected (for the list and the card of an OKS in the UI). */
    public static final class OksInfo {
        public String oksId;
        public double flowTph;
        public boolean connected;
        /** {@code tie_in} — its own branch starts at a tie-in; {@code junction} — joins the new network of others. */
        public String joins;
        public String tieInId;
        public String tieObjectId;
        public String tieObjectType;
        /** DN, length, cost and turns of the own branch (from the connection point to the first shared node). */
        public int dn;
        public double ownLength;
        public double ownCost;
        public int turns;
        /** Types of special sections crossed by the own branch. */
        public final List<String> specials = new ArrayList<>();
        /** Length of the whole path from the tie-in; new segments along it from the tie-in to the OKS. */
        public double pathLength;
        public final List<String> pathSegmentIds = new ArrayList<>();
        /** Other OKS fed through the same tie-in. */
        public final List<String> sharedWith = new ArrayList<>();
        /** Existing objects from the tie-in to the source (the added flow goes there). */
        public final List<String> existingChain = new ArrayList<>();
    }

    public final List<OksInfo> oks = new ArrayList<>();

    public String variantId;
    public final List<NewSegment> segments = new ArrayList<>();
    public final List<TieIn> tieIns = new ArrayList<>();
    public final List<NewChamber> chambers = new ArrayList<>();
    public final List<TechnicalNode> technicalNodes = new ArrayList<>();
    public final List<Unconnected> unconnected = new ArrayList<>();
    public final List<FlowChange> flowChanges = new ArrayList<>();
    public final List<LengthRun> lengthRuns = new ArrayList<>();
    public final Summary summary = new Summary();
    /** Construction journal (see {@code JournalRecorder}); null unless {@link PlanParams#journal}. */
    public List<java.util.Map<String, Object>> journal;
    public final Metrics metrics = new Metrics();
}
