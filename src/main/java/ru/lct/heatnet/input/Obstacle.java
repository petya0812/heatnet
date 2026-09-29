package ru.lct.heatnet.input;

import org.locationtech.jts.geom.Geometry;

/** Existing building or spatial restriction, UTM geometry. */
public final class Obstacle {

    private final String id;
    private final ObjectType objectType;
    private final String restrictionType;
    private final Geometry geometry;

    public Obstacle(String id, ObjectType objectType, String restrictionType, Geometry geometry) {
        this.id = id;
        this.objectType = objectType;
        this.restrictionType = restrictionType;
        this.geometry = geometry;
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

    public Geometry getGeometry() {
        return geometry;
    }
}
