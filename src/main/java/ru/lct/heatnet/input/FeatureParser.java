package ru.lct.heatnet.input;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.geo.GeoJsonGeometry;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Streaming reader of the input FeatureCollection: features are read one by one, so memory use does
 * not depend on the file size (R-ENV-7). Each feature is checked against section 1.1 of the technical
 * appendix; broken features are reported to {@link Diagnostics} and skipped.
 */
public final class FeatureParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReferenceData ref;

    public FeatureParser(ReferenceData ref) {
        this.ref = ref;
    }

    /** @return number of features read (including skipped ones) */
    public long parse(InputStream in, Diagnostics diag, Consumer<ParsedFeature> sink) throws IOException {
        JsonFactory factory = MAPPER.getFactory();
        long seq = 0;
        boolean sawFeatures = false;
        try (JsonParser p = factory.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                diag.error("file.not_object", null, "Input is not a JSON object");
                return 0;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.getCurrentName();
                JsonToken t = p.nextToken();
                if ("type".equals(field)) {
                    if (!"FeatureCollection".equals(p.getValueAsString())) {
                        diag.error("file.not_feature_collection", null,
                                "type is '" + p.getValueAsString() + "', expected FeatureCollection");
                    }
                } else if ("features".equals(field) && t == JsonToken.START_ARRAY) {
                    sawFeatures = true;
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode node = MAPPER.readTree(p);
                        seq++;
                        ParsedFeature f = check(seq, node, diag);
                        if (f != null) {
                            sink.accept(f);
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
        if (!sawFeatures) {
            diag.error("file.no_features", null, "No 'features' array");
        }
        return seq;
    }

    ParsedFeature check(long seq, JsonNode node, Diagnostics diag) {
        String ref0 = "#" + seq;
        if (node == null || !node.isObject()) {
            diag.error("feature.not_object", ref0, "Feature is not an object");
            return null;
        }
        JsonNode propsNode = node.get("properties");
        if (propsNode == null || !propsNode.isObject()) {
            diag.error("feature.no_properties", ref0, "Feature without properties");
            return null;
        }
        ObjectNode props = (ObjectNode) propsNode;
        JsonNode idNode = props.get("id");
        if (idNode == null || idNode.isNull() || idNode.asText().isEmpty()) {
            idNode = node.get("id");
        }
        if (idNode == null || idNode.isNull() || idNode.asText().isEmpty()) {
            diag.error("feature.no_id", ref0, "Feature without id");
            return null;
        }
        String id = idNode.asText();
        String typeCode = props.path("object_type").asText(null);
        ObjectType type = ObjectType.fromCode(typeCode);
        if (type == null) {
            diag.error("feature.unknown_object_type", id, "Unknown object_type '" + typeCode + "', feature skipped");
            return null;
        }
        Geometry wgs;
        try {
            wgs = GeoJsonGeometry.read(node.get("geometry"), Crs.WGS84_FACTORY);
        } catch (RuntimeException e) {
            diag.error("feature.bad_geometry", id, "Cannot read geometry: " + e.getMessage());
            return null;
        }
        if (wgs == null || wgs.isEmpty()) {
            diag.error("feature.no_geometry", id, "Empty geometry");
            return null;
        }
        if (!type.getGeometryTypes().contains(wgs.getGeometryType())) {
            diag.error("feature.geometry_type", id, type.getCode() + " has geometry " + wgs.getGeometryType()
                    + ", expected " + type.getGeometryTypes());
            return null;
        }
        if (!inWgs84Range(wgs)) {
            diag.error("feature.coordinates_out_of_range", id, "Coordinates are not WGS 84 lon/lat");
            return null;
        }
        for (String req : type.getRequiredAttributes()) {
            String[] nt = req.split(":");
            JsonNode v = props.get(nt[0]);
            if (v == null || v.isNull()) {
                diag.error("feature.missing_attribute", id, type.getCode() + " without '" + nt[0] + "'");
                return null;
            }
            boolean ok;
            switch (nt[1]) {
                case "int":
                    ok = v.isIntegralNumber() || (v.isNumber() && v.asDouble() == Math.rint(v.asDouble()));
                    break;
                case "number":
                    ok = v.isNumber();
                    break;
                default:
                    ok = v.isTextual() || v.isNumber();
            }
            if (!ok) {
                diag.error("feature.attribute_type", id, "'" + nt[0] + "' is not " + nt[1]);
                return null;
            }
        }
        String restrictionType = null;
        if (type == ObjectType.RESTRICTION) {
            restrictionType = props.get("restriction_type").asText();
            if (ref.isBuildingRestriction(restrictionType)) {
                // OKS outlines come as restrictions of this type (appendix 1.2)
                diag.info("restriction.building", id, "restriction_type '" + restrictionType
                        + "' is an OKS outline: minimal distance 5/7/9 m by DN");
                type = ObjectType.OKS_EXISTING;
                restrictionType = null;
            } else if (!ref.isKnownRestriction(restrictionType)) {
                diag.warning("restriction.unknown_type", id, "restriction_type '" + restrictionType
                        + "' is not in table 2, treated as forbidden with 1 m clearance");
            }
        }
        if ((type == ObjectType.HEAT_NETWORK || type == ObjectType.HEAT_CHAMBER) && props.hasNonNull("diameter")) {
            int dn = props.get("diameter").asInt();
            if (!ref.isKnownDn(dn)) {
                diag.warning("network.nonstandard_dn", id, "diameter " + dn + " is not in table 1");
            }
        }
        for (String flowField : new String[]{"flow_tph"}) {
            if (props.hasNonNull(flowField) && props.get(flowField).isNumber()
                    && props.get(flowField).asDouble() < 0) {
                diag.error("feature.negative_flow", id, flowField + " < 0");
                return null;
            }
        }
        Geometry utm = Crs.toUtm(wgs);
        // invalid geometry is a data error, not something to repair silently: the object is reported and left out
        // of the calculation (docs/parameters.md, invalid_geometry)
        IsValidOp valid = new IsValidOp(wgs);
        if (!valid.isValid()) {
            TopologyValidationError err = valid.getValidationError();
            diag.error("feature.invalid_geometry", id, "Invalid geometry: " + (err == null ? "not valid"
                    : err.getMessage() + " at " + err.getCoordinate().x + " " + err.getCoordinate().y));
            return null;
        }
        if (utm.getDimension() == 1 && utm.getLength() == 0) {
            diag.error("feature.invalid_geometry", id, "Invalid geometry: degenerate line");
            return null;
        }
        return new ParsedFeature(seq, id, type, restrictionType, props, wgs, utm);
    }

    private static boolean inWgs84Range(Geometry g) {
        org.locationtech.jts.geom.Envelope e = g.getEnvelopeInternal();
        return e.getMinX() >= -180 && e.getMaxX() <= 180 && e.getMinY() >= -90 && e.getMaxY() <= 90;
    }
}
