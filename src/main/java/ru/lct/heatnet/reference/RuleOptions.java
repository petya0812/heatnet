package ru.lct.heatnet.reference;

/**
 * Assumptions about data the input does not carry (docs/requirements.md, class of data assumptions). The planner
 * and the output validator use the same options, so switching one changes both.
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

    public ExistingFlow existingFlow = ExistingFlow.ZERO;
    /** Share of the capacity of the DN assumed loaded with {@link ExistingFlow#CAPACITY_SHARE}. */
    public double existingFlowShare = 0.5;

    public static RuleOptions defaults() {
        return new RuleOptions();
    }

    public RuleOptions copy() {
        RuleOptions r = new RuleOptions();
        r.existingFlow = existingFlow;
        r.existingFlowShare = existingFlowShare;
        return r;
    }

    @Override
    public String toString() {
        return "existingFlow=" + existingFlow
                + (existingFlow == ExistingFlow.CAPACITY_SHARE ? " " + existingFlowShare : "");
    }
}
