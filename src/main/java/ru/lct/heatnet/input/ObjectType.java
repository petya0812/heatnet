package ru.lct.heatnet.input;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Input object types, technical appendix section 1.1 (oks_future and oks_existing: earlier input format; OKS
 * outlines of the current format, restrictions of type oks, are read as oks_existing). Only the attributes a feature cannot be read without are
 * required here; what can be derived from geometry ({@code upstream_object_id}, the DN of a chamber, the flow of
 * an existing segment, the building of a connection point) is completed by {@link InputModel} with a diagnostic.
 */
public enum ObjectType {
    SOURCE("source", Collections.singletonList("Point"), Collections.emptyList()),
    HEAT_NETWORK("heat_network", Collections.singletonList("LineString"),
            Collections.singletonList("diameter:int")),
    HEAT_CHAMBER("heat_chamber", Collections.singletonList("Point"), Collections.emptyList()),
    OKS_FUTURE("oks_future", Arrays.asList("Polygon", "MultiPolygon"),
            Collections.singletonList("flow_tph:number")),
    OKS_CONNECTION_POINT("oks_connection_point", Collections.singletonList("Point"), Collections.emptyList()),
    OKS_EXISTING("oks_existing", Arrays.asList("Polygon", "MultiPolygon"), Collections.emptyList()),
    RESTRICTION("restriction", Arrays.asList("Point", "LineString", "Polygon", "MultiPoint", "MultiLineString",
            "MultiPolygon"), Collections.singletonList("restriction_type:string"));

    private final String code;
    private final List<String> geometryTypes;
    private final List<String> requiredAttributes;

    ObjectType(String code, List<String> geometryTypes, List<String> requiredAttributes) {
        this.code = code;
        this.geometryTypes = geometryTypes;
        this.requiredAttributes = requiredAttributes;
    }

    public String getCode() {
        return code;
    }

    public List<String> getGeometryTypes() {
        return geometryTypes;
    }

    /** Entries of the form {@code name:type}, type is one of int, number, string. */
    public List<String> getRequiredAttributes() {
        return requiredAttributes;
    }

    public static ObjectType fromCode(String code) {
        for (ObjectType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }

    /** Types that are small enough to be loaded fully into memory for a run. */
    public boolean isCore() {
        return this != OKS_EXISTING && this != RESTRICTION;
    }
}
