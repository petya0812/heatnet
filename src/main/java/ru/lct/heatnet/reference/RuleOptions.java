package ru.lct.heatnet.reference;

/**
 * Assumptions about data the input does not carry (docs/requirements.md, class of data assumptions) and the way into
 * the building of an OKS. The planner and the output validator use the same options, so switching one changes both.
 *
 * Settled rules are not options: the length limit (the minimal DN that meets both the flow and the limit, checked
 * along every path), routes along roads and the geometry of prospective OKS follow the technical appendix and the
 * clarifications to it.
 */
public final class RuleOptions {

    /** What the flow of an existing segment is when the input does not carry it (the contest dataset). */
    public enum ExistingFlow {
        /** The network is empty: its whole capacity is free for the new connections. */
        ZERO,
        /** The network is already loaded to {@link RuleOptions#existingFlowShare} of the capacity of its DN. */
        CAPACITY_SHARE
    }

    /**
     * Where the route may leave the building of an OKS whose connection point lies inside it. The appendix (2.2,
     * clarification 3) allows only the nearest wall; the other modes depart from it and every such entry is reported
     * by the output validator as a warning.
     */
    public enum Entry {
        /** Only from the nearest wall: the foot of the perpendicular from the connection point (appendix 2.2). */
        NEAREST("только ближайшая стена", true),
        /** When the nearest wall is walled in: another wall whose piece inside is at most {@link #entrySlackM} longer. */
        NEAR("другая стена, если ненамного дальше", true),
        /** When the nearest wall is walled in: any wall, the nearest ones first. */
        ANY("любая стена", true),
        /**
         * From any point of the outline at any angle to the wall, the search picks it; the route keeps no clearance to
         * its own building and has no straight piece out of it.
         */
        OUTLINE("любая точка контура", false);

        private final String label;
        private final boolean alongNormal;

        Entry(String label, boolean alongNormal) {
            this.label = label;
            this.alongNormal = alongNormal;
        }

        /**
         * The route leaves the building from a wall along its normal, straight through the clearance to the building,
         * and keeps that clearance on the rest of the way; otherwise from any point of the outline without it.
         */
        public boolean alongNormal() {
            return alongNormal;
        }

        /** Name of the mode in the UI (Russian). */
        public String label() {
            return label;
        }
    }

    /**
     * Existing buildings other than the own one the route may go through (СП 124, 9.3 allows crossing residential and
     * public buildings; the appendix says nothing, so by default none). Such a building is crossed like a road: a
     * straight special section priced with {@link #buildingCrossingK}, no clearance to it.
     */
    public enum ThroughBuildings {
        /** None: every building keeps its clearance. */
        NONE("нельзя"),
        /** Existing buildings, except the buildings of the OKS being connected. */
        EXISTING("через существующие здания"),
        /** Existing buildings and the buildings of the other OKS being connected. */
        ANY("через существующие здания и здания других ОКС");

        private final String label;

        ThroughBuildings(String label) {
            this.label = label;
        }

        /** Name of the option in the UI (Russian). */
        public String label() {
            return label;
        }
    }

    /** Type of the special section across a building, see {@link ThroughBuildings}. */
    public static final String BUILDING_CROSSING = "building";

    public ExistingFlow existingFlow = ExistingFlow.ZERO;
    /** Share of the capacity of the DN assumed loaded with {@link ExistingFlow#CAPACITY_SHARE}. */
    public double existingFlowShare = 0.5;
    public Entry entry = Entry.NEAREST;
    /** With {@link Entry#NEAR}: how much longer than the shortest one the piece inside the building may be, metres. */
    public double entrySlackM = 5.0;
    public ThroughBuildings throughBuildings = ThroughBuildings.NONE;
    /** Cost coefficient K of the pipe across a building ({@link #throughBuildings}); 1 — the base price. */
    public double buildingCrossingK = 1.0;
    /**
     * The route may go through the separate parts of the own building (polygons of its multipolygon without the
     * connection point), they keep no clearance; by default they are the building like its main part.
     */
    public boolean throughOwnParts = false;

    public static RuleOptions defaults() {
        return new RuleOptions();
    }

    public RuleOptions copy() {
        RuleOptions r = new RuleOptions();
        r.existingFlow = existingFlow;
        r.existingFlowShare = existingFlowShare;
        r.entry = entry;
        r.entrySlackM = entrySlackM;
        r.throughBuildings = throughBuildings;
        r.buildingCrossingK = buildingCrossingK;
        r.throughOwnParts = throughOwnParts;
        return r;
    }

    @Override
    public String toString() {
        return "existingFlow=" + existingFlow
                + (existingFlow == ExistingFlow.CAPACITY_SHARE ? " " + existingFlowShare : "")
                + " entry=" + entry + (entry == Entry.NEAR ? " " + entrySlackM : "")
                + " throughBuildings=" + throughBuildings
                + (throughBuildings != ThroughBuildings.NONE ? " k=" + buildingCrossingK : "")
                + " throughOwnParts=" + throughOwnParts;
    }
}
