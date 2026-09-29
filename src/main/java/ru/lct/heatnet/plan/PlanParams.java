package ru.lct.heatnet.plan;

/**
 * Tunable parameters of the routing heuristics. They are not dataset configuration: the same values
 * are used for any input of the case structure.
 */
public final class PlanParams {

    /** Search radius around the connection point: {@code factor * distance to network + margin}. */
    public double searchRadiusFactor = 1.6;
    public double searchRadiusMarginM = 200;
    public double searchRadiusMinM = 250;
    public double searchRadiusMaxM = 5000;
    /** Extra ring of loaded obstacles beyond the search radius, so edges near the border are checked. */
    public double obstacleMarginM = 150;
    /** Penalty for every turn of the route, in score units (≈ 25 m of a small pipe): fewer, cleaner turns. */
    public double turnPenaltyScore = 0.15;
    /** Obstacle simplification tolerance before buffering, metres (compensated in the buffer). */
    public double simplifyToleranceM = 0.3;
    /** Offset of routing vertices outside the blocking buffers, metres. */
    public double vertexOffsetM = 0.15;
    /** Clearance between a new route and previously built new routes, metres (between envelopes). */
    public double newRouteClearanceM = 0.5;
    /** Tie-in candidates are searched on network segments within {@code nearest + this} of a vertex. */
    public double tieSearchSlackM = 120;
    public int maxExpansions = 20000;
    public int maxVertices = 6000;
    /** Number of attempts with an increased search radius when no route is found. */
    public int radiusRetries = 2;
    /** Joining the new network: candidates on edges within this distance of a routing vertex, metres. */
    public double attachSearchM = 600;
    /** A branching chamber is not placed closer than this to the ends of an edge, metres. */
    public double attachEndMarginM = 2.0;
    /** Passes of the local search (take an OKS out, connect it again). */
    public int localSearchPasses = 2;
    /** Threads for building variants in parallel within one calculation. */
    public int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    /**
     * Corridor of the search: a turning point is kept when the way through it (to the point and on to the network)
     * is not longer than {@code (1 + corridorSlack)} of the straight distance plus {@code corridorMarginM}.
     * On a failed search the corridor is widened together with the radius.
     */
    public double corridorSlack = 2.0;
    public double corridorMarginM = 150;
    /**
     * Turning points further than this from the current one are not considered as the next step: the cost of a
     * search step is bounded by the number of neighbours, not by the size of the graph. When a point has fewer
     * than {@link #neighboursMin} neighbours in this radius, the nearest ones are taken instead.
     */
    public double neighbourRadiusM = 900;
    public int neighboursMin = 24;
    /**
     * The same radius for the search along the directions of {@link #turnStepDeg}: its states are turning points with the heading of
     * arrival, and each pair of points has two ways of two legs, so the neighbourhood is kept smaller.
     */
    public double frameNeighbourRadiusM = 300;
    /** Search areas whose obstacle zones are kept for reuse (memory against repeated work). */
    public int contextCache = 64;
    /** Points on the outline of the building where the route may leave it (the search picks one). */
    public int entryPoints = 8;
    /** Minimal distance between such points, metres. */
    public double entryPointSpacingM = 8.0;
    /**
     * Angles of the turns of a route. 0 — any angle up to 90°, the appendix (2.1) allows it and charges nothing for a
     * turn. 30, 45 or 90 — the search runs along the directions of this step around the connection point, so turns
     * are multiples of it up to 90° (30° — 30, 60 and 90°; 45° — 45 and 90°; 90° — right angles only).
     */
    public int turnStepDeg = 45;
    /**
     * With a step of turns: an OKS that has no route with such angles stays without a route (true) or gets one with
     * any angles up to 90° (false).
     */
    public boolean turnStepStrict = false;
    /**
     * Preferences of the engineer, stricter than the appendix (never looser: the output validator checks the
     * appendix minimums, so a stricter route always passes). Extra clearance is added to every clearance to
     * buildings and restrictions; the crossing angle replaces the minimum of the rule when it is larger.
     */
    public double extraClearanceM = 0;
    public double minCrossingAngleDeg = 0;
    /** Joint connection of several OKS through one tie-in; off — every OKS gets its own route and tie-in. */
    public boolean jointConnection = true;
    /** Shortest leg of a way of two legs between turning points, metres. */
    public double minLegM = 1.0;
    /**
     * Record the construction journal of every variant (steps, alternatives of each tie-in) for the replay in the UI.
     * Recording does not change the result.
     */
    public boolean journal = false;
    /** Assumptions about data missing in the input, shared with the output validator. */
    public ru.lct.heatnet.reference.RuleOptions rules = new ru.lct.heatnet.reference.RuleOptions();

    /** Allowed steps of {@link #turnStepDeg}; 0 — any angle. */
    public static final int[] TURN_STEPS = {0, 30, 45, 90};

    /** The angles of a step as UI text (Russian), for step 45: "under 45° and 90°". */
    public static String turnAnglesText(int stepDeg) {
        switch (stepDeg) {
            case 30:
                return "под 30°, 60° и 90°";
            case 45:
                return "под 45° и 90°";
            case 90:
                return "под 90°";
            default:
                return "под любым углом до 90°";
        }
    }

    /** Independent copy (per-calculation parameters of the service). */
    public PlanParams copy() {
        PlanParams p = new PlanParams();
        for (java.lang.reflect.Field f : PlanParams.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.set(p, f.get(this));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        p.rules = rules.copy();
        return p;
    }
}
