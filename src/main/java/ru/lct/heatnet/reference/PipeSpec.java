package ru.lct.heatnet.reference;

/** One row of table 1 of the technical appendix (edition 18.09.2026). */
public final class PipeSpec {

    private final int dn;
    private final double capacityTph;
    private final double maxLengthM;
    private final double newCostPerM;
    private final double pairWidthM;
    private final double heightM;

    public PipeSpec(int dn, double capacityTph, double maxLengthM, double newCostPerM,
                    double pairWidthM, double heightM) {
        this.dn = dn;
        this.capacityTph = capacityTph;
        this.maxLengthM = maxLengthM;
        this.newCostPerM = newCostPerM;
        this.pairWidthM = pairWidthM;
        this.heightM = heightM;
    }

    public int getDn() {
        return dn;
    }

    public double getCapacityTph() {
        return capacityTph;
    }

    public double getMaxLengthM() {
        return maxLengthM;
    }

    public double getNewCostPerM() {
        return newCostPerM;
    }

    public double getPairWidthM() {
        return pairWidthM;
    }

    public double getHalfWidthM() {
        return pairWidthM / 2.0;
    }

    public double getHeightM() {
        return heightM;
    }
}
