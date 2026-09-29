package ru.lct.heatnet.input;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;

/** One input feature after streaming parse and per-feature checks. */
public final class ParsedFeature {

    private final long seq;
    private final String id;
    private final ObjectType objectType;
    private final String restrictionType;
    private final ObjectNode properties;
    private final Geometry wgs84;
    private final Geometry utm;

    public ParsedFeature(long seq, String id, ObjectType objectType, String restrictionType, ObjectNode properties,
                         Geometry wgs84, Geometry utm) {
        this.seq = seq;
        this.id = id;
        this.objectType = objectType;
        this.restrictionType = restrictionType;
        this.properties = properties;
        this.wgs84 = wgs84;
        this.utm = utm;
    }

    public long getSeq() {
        return seq;
    }

    public String getId() {
        return id;
    }

    public ObjectType getObjectType() {
        return objectType;
    }

    public String getRestrictionType() {
        return restrictionType;
    }

    public ObjectNode getProperties() {
        return properties;
    }

    public Geometry getWgs84() {
        return wgs84;
    }

    public Geometry getUtm() {
        return utm;
    }

    /** Numeric attribute, or {@code fallback} when it is absent or not a number. */
    public double getDouble(String field, double fallback) {
        com.fasterxml.jackson.databind.JsonNode v = properties.get(field);
        return v == null || !v.isNumber() ? fallback : v.asDouble();
    }

    public double getDouble(String field) {
        return getDouble(field, 0);
    }

    public int getInt(String field, int fallback) {
        com.fasterxml.jackson.databind.JsonNode v = properties.get(field);
        return v == null || !v.isNumber() ? fallback : v.asInt();
    }

    public int getInt(String field) {
        return getInt(field, 0);
    }

    /** Whether the attribute is present and not null. */
    public boolean has(String field) {
        com.fasterxml.jackson.databind.JsonNode v = properties.get(field);
        return v != null && !v.isNull();
    }

    public String getText(String field) {
        com.fasterxml.jackson.databind.JsonNode v = properties.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
