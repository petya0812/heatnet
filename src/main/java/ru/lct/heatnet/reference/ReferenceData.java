package ru.lct.heatnet.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference tables of the technical appendix (edition 18.09.2026, sections 3, 4, 6), loaded from
 * {@code reference/tech-appendix.json}. Rule values are read from here; UI texts quote some of them.
 */
public final class ReferenceData {

    public static final String RESOURCE = "reference/tech-appendix.json";

    private final List<PipeSpec> pipes;
    private final List<long[]> chamberCosts; // minDn, maxDn, cost
    private final double tieInCost;
    private final double tieInChamberRadiusM;
    private final int maxChamberDegree;
    private final double penaltyFixed;
    private final double penaltyPerTph;
    private final double costWeight;
    private final double costBase;
    private final double lengthWeight;
    private final double lengthBase;
    private final List<double[]> buildingDistances; // maxDnExclusive, distance
    private final Map<String, RestrictionRule> restrictions;
    private final RestrictionRule unknownRestriction;
    private final java.util.Set<String> buildingRestrictionTypes;
    private final double maxTurnDeg;
    private final double turnToleranceDeg;
    private final double straightDeg;

    private ReferenceData(JsonNode root) {
        List<PipeSpec> p = new ArrayList<>();
        for (JsonNode n : root.get("pipes")) {
            p.add(new PipeSpec(n.get("dn").asInt(), n.get("capacityTph").asDouble(), n.get("maxLengthM").asDouble(),
                    n.get("newCostPerM").asDouble(),
                    n.get("pairWidthM").asDouble(), n.get("heightM").asDouble()));
        }
        p.sort((a, b) -> Integer.compare(a.getDn(), b.getDn()));
        this.pipes = Collections.unmodifiableList(p);
        List<long[]> cc = new ArrayList<>();
        for (JsonNode n : root.get("chamberCosts")) {
            cc.add(new long[]{n.get("minDn").asLong(), n.get("maxDn").asLong(), n.get("cost").asLong()});
        }
        this.chamberCosts = cc;
        this.tieInCost = root.get("tieInCost").asDouble();
        this.tieInChamberRadiusM = root.get("tieInChamberRadiusM").asDouble();
        this.maxChamberDegree = root.get("maxChamberDegree").asInt();
        this.penaltyFixed = root.get("unconnectedPenaltyFixed").asDouble();
        this.penaltyPerTph = root.get("unconnectedPenaltyPerTph").asDouble();
        JsonNode s = root.get("score");
        this.costWeight = s.get("costWeight").asDouble();
        this.costBase = s.get("costBase").asDouble();
        this.lengthWeight = s.get("lengthWeight").asDouble();
        this.lengthBase = s.get("lengthBase").asDouble();
        List<double[]> bd = new ArrayList<>();
        for (JsonNode n : root.get("existingBuilding").get("distances")) {
            bd.add(new double[]{n.get("maxDnExclusive").asDouble(), n.get("distanceM").asDouble()});
        }
        this.buildingDistances = bd;
        Map<String, RestrictionRule> r = new HashMap<>();
        for (JsonNode n : root.get("restrictions")) {
            RestrictionRule rule = RestrictionRule.fromJson(n.get("type").asText(), n);
            r.put(rule.getType(), rule);
        }
        this.restrictions = Collections.unmodifiableMap(r);
        this.unknownRestriction = RestrictionRule.fromJson("unknown", root.get("unknownRestriction"));
        java.util.Set<String> brt = new java.util.LinkedHashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode n : root.path("buildingRestrictionTypes")) {
            brt.add(n.asText().toLowerCase(java.util.Locale.ROOT));
        }
        this.buildingRestrictionTypes = java.util.Collections.unmodifiableSet(brt);
        JsonNode b = root.get("turn");
        this.maxTurnDeg = b.get("maxDeg").asDouble();
        this.turnToleranceDeg = b.get("toleranceDeg").asDouble();
        this.straightDeg = b.get("straightDeg").asDouble();
    }

    /**
     * Whether a turn of the route is allowed: an arbitrary angle up to 90° inclusive, with a tolerance for
     * rounding (appendix 2.1, clarification 5). There is no surcharge for a turn.
     */
    public boolean isAllowedTurn(double deg) {
        return deg <= maxTurnDeg + turnToleranceDeg;
    }

    /** Below this deflection the route is considered straight. */
    public double getStraightDeg() {
        return straightDeg;
    }

    public double getMaxTurnDeg() {
        return maxTurnDeg;
    }

    public static ReferenceData load() {
        try (InputStream in = ReferenceData.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return new ReferenceData(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<PipeSpec> getPipes() {
        return pipes;
    }

    public PipeSpec pipe(int dn) {
        for (PipeSpec p : pipes) {
            if (p.getDn() == dn) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown DN " + dn);
    }

    public boolean isKnownDn(int dn) {
        for (PipeSpec p : pipes) {
            if (p.getDn() == dn) {
                return true;
            }
        }
        return false;
    }

    /** Minimal DN whose capacity is not less than the flow; null if the flow exceeds the largest DN. */
    public PipeSpec minPipeForFlow(double flowTph) {
        for (PipeSpec p : pipes) {
            if (p.getCapacityTph() + 1e-9 >= flowTph) {
                return p;
            }
        }
        return null;
    }

    /** Next larger DN after the given one, or null. */
    public PipeSpec nextPipe(int dn) {
        for (PipeSpec p : pipes) {
            if (p.getDn() > dn) {
                return p;
            }
        }
        return null;
    }

    /** Pipe spec for an arbitrary existing DN: exact match or the closest larger known DN. */
    public PipeSpec pipeAtLeast(int dn) {
        for (PipeSpec p : pipes) {
            if (p.getDn() >= dn) {
                return p;
            }
        }
        return pipes.get(pipes.size() - 1);
    }

    public double chamberCost(int maxDn) {
        for (long[] c : chamberCosts) {
            if (maxDn >= c[0] && maxDn <= c[1]) {
                return c[2];
            }
        }
        // DN outside the scale (e.g. unusual existing DN): take the bracket of the closest larger bound.
        for (long[] c : chamberCosts) {
            if (maxDn <= c[1]) {
                return c[2];
            }
        }
        return chamberCosts.get(chamberCosts.size() - 1)[2];
    }

    public double buildingDistance(int dn) {
        for (double[] d : buildingDistances) {
            if (dn < d[0]) {
                return d[1];
            }
        }
        return buildingDistances.get(buildingDistances.size() - 1)[1];
    }

    public RestrictionRule restriction(String type) {
        RestrictionRule r = restrictions.get(type);
        return r != null ? r : unknownRestriction;
    }

    public boolean isKnownRestriction(String type) {
        return restrictions.containsKey(type);
    }

    /**
     * Restriction types that are OKS outlines: they get the minimal distance of table 2 for OKS
     * (5/7/9 m by DN), not the unknown-type rule.
     */
    public boolean isBuildingRestriction(String type) {
        return type != null && buildingRestrictionTypes.contains(type.toLowerCase(java.util.Locale.ROOT));
    }

    public double unconnectedPenalty(double flowTph) {
        return penaltyFixed + penaltyPerTph * flowTph;
    }

    public double score(double cost, double lengthM) {
        return costWeight * cost / costBase + lengthWeight * lengthM / lengthBase;
    }

    /** Score contribution of a cost in roubles. */
    public double scoreOfCost(double cost) {
        return costWeight * cost / costBase;
    }

    /** Score contribution of a length in metres. */
    public double scoreOfLength(double lengthM) {
        return lengthWeight * lengthM / lengthBase;
    }

    public double getTieInCost() {
        return tieInCost;
    }

    public double getTieInChamberRadiusM() {
        return tieInChamberRadiusM;
    }

    public int getMaxChamberDegree() {
        return maxChamberDegree;
    }

    /** Rules of table 2 (known restriction types), in the order of the reference file. */
    public List<RestrictionRule> getRestrictionRules() {
        List<RestrictionRule> out = new ArrayList<>(restrictions.values());
        out.sort(java.util.Comparator.comparing(RestrictionRule::getType));
        return out;
    }

    public RestrictionRule getUnknownRestriction() {
        return unknownRestriction;
    }

    /** Minimal distance to existing buildings: pairs {DN below which it applies, distance, m}. */
    public List<double[]> getBuildingDistances() {
        return Collections.unmodifiableList(buildingDistances);
    }

    /** Chamber costs: {min DN, max DN, cost}. */
    public List<long[]> getChamberCosts() {
        return Collections.unmodifiableList(chamberCosts);
    }

    public double getPenaltyFixed() {
        return penaltyFixed;
    }

    public double getPenaltyPerTph() {
        return penaltyPerTph;
    }

    public double getCostWeight() {
        return costWeight;
    }

    public double getCostBase() {
        return costBase;
    }

    public double getLengthWeight() {
        return lengthWeight;
    }

    public double getLengthBase() {
        return lengthBase;
    }
}
