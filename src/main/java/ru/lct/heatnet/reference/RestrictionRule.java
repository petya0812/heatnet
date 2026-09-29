package ru.lct.heatnet.reference;

import com.fasterxml.jackson.databind.JsonNode;

/** Rule of table 2 of the appendix for one restriction type. */
public final class RestrictionRule {

    public enum Kind { FORBIDDEN, SPECIAL }

    /** How the special section is bounded (table 2, last-but-one column). */
    public enum Bounds { NONE, AREA_PLUS, AROUND_CROSSING }

    private final String type;
    private final Kind kind;
    private final double minDistanceM;
    private final double minAngleDeg;
    private final Bounds bounds;
    private final double boundsM;
    private final double k;
    private final double objectWidthM;

    private RestrictionRule(String type, Kind kind, double minDistanceM, double minAngleDeg, Bounds bounds,
                            double boundsM, double k, double objectWidthM) {
        this.type = type;
        this.kind = kind;
        this.minDistanceM = minDistanceM;
        this.minAngleDeg = minAngleDeg;
        this.bounds = bounds;
        this.boundsM = boundsM;
        this.k = k;
        this.objectWidthM = objectWidthM;
    }

    static RestrictionRule fromJson(String type, JsonNode n) {
        return new RestrictionRule(type,
                Kind.valueOf(n.get("rule").asText()),
                n.get("minDistanceM").asDouble(),
                n.path("minAngleDeg").asDouble(0),
                n.has("bounds") ? Bounds.valueOf(n.get("bounds").asText()) : Bounds.NONE,
                n.path("boundsM").asDouble(0),
                n.path("k").asDouble(1.0),
                n.path("objectWidthM").asDouble(0));
    }

    public String getType() {
        return type;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isForbidden() {
        return kind == Kind.FORBIDDEN;
    }

    public double getMinDistanceM() {
        return minDistanceM;
    }

    public double getMinAngleDeg() {
        return minAngleDeg;
    }

    public Bounds getBounds() {
        return bounds;
    }

    public double getBoundsM() {
        return boundsM;
    }

    public double getK() {
        return k;
    }

    /** Width of the object's own envelope (linear objects), 0 when not given. */
    public double getObjectWidthM() {
        return objectWidthM;
    }
}
