package ru.lct.heatnet.output;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnet.geo.Crs;
import ru.lct.heatnet.geo.GeoJsonGeometry;
import ru.lct.heatnet.plan.Variant;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Streams the result FeatureCollection (technical appendix section 7) in EPSG:4326. Every object type
 * carries exactly its own attribute set, without null fields of other types.
 *
 * <p>Identifiers of input objects may be strings or numbers and keep their type in the output (appendix 7.2).
 * An id written back is a number when its text is a canonical integer, and a string otherwise.
 */
public final class ResultWriter {

    /** ~1 mm at Moscow latitude. */
    private static final int COORD_DECIMALS = 8;

    private final ObjectMapper mapper = new ObjectMapper();

    public void write(OutputStream out, List<Variant> variants) throws IOException {
        try (JsonGenerator g = mapper.getFactory().createGenerator(out, JsonEncoding.UTF8)) {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeArrayFieldStart("features");
            for (Variant v : variants) {
                writeVariant(g, v);
            }
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    private void writeVariant(JsonGenerator g, Variant v) throws IOException {
        for (Variant.NewSegment s : v.segments) {
            start(g, s.line, s.id, "heat_network", v.variantId);
            writeId(g, "start_node_id", s.startNodeId);
            writeId(g, "end_node_id", s.endNodeId);
            g.writeNumberField("flow_tph", r(s.flowTph, 6));
            g.writeNumberField("diameter", s.dn);
            g.writeNumberField("length", r(s.length, 3));
            g.writeStringField("laying_method", s.layingMethod);
            g.writeNullField("depth_start");
            g.writeNullField("depth_end");
            g.writeNumberField("cost", r(s.cost, 2));
            end(g);
        }
        for (Variant.NewChamber c : v.chambers) {
            start(g, c.point, c.id, "heat_chamber", v.variantId);
            g.writeNumberField("diameter", c.dn);
            g.writeNumberField("cost", r(c.cost, 2));
            end(g);
        }
        for (Variant.TechnicalNode n : v.technicalNodes) {
            start(g, n.point, n.id, "technical_node", v.variantId);
            end(g);
        }
        Variant.Summary s = v.summary;
        start(g, null, s.id, "variant_summary", v.variantId);
        g.writeNumberField("rank", s.rank);
        g.writeNumberField("construction_cost", r(s.constructionCost, 2));
        g.writeNumberField("chamber_construction_cost", r(s.chamberConstructionCost, 2));
        g.writeNumberField("existing_chamber_tie_in_count", s.existingChamberTieInCount);
        g.writeNumberField("existing_chamber_tie_in_cost", r(s.existingChamberTieInCost, 2));
        g.writeNumberField("unconnected_penalty", r(s.unconnectedPenalty, 2));
        g.writeNumberField("calculated_cost", r(s.calculatedCost, 2));
        g.writeNumberField("new_network_length", r(s.newNetworkLength, 3));
        g.writeNumberField("score", r(s.score, 6));
        g.writeArrayFieldStart("unconnected_oks_ids");
        for (String id : s.unconnectedOksIds) {
            writeId(g, null, id);
        }
        g.writeEndArray();
        end(g);
    }

    /** Canonical integer: written back as a number, so the type of an input id is preserved. */
    private static final java.util.regex.Pattern INTEGER_ID =
            java.util.regex.Pattern.compile("0|-?[1-9][0-9]{0,17}");

    private static void writeId(JsonGenerator g, String field, String id) throws IOException {
        boolean number = id != null && INTEGER_ID.matcher(id).matches();
        if (field != null) {
            g.writeFieldName(field);
        }
        if (number) {
            g.writeNumber(Long.parseLong(id));
        } else {
            g.writeString(id);
        }
    }

    private static void start(JsonGenerator g, Geometry utm, String id, String type, String variantId)
            throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeFieldName("geometry");
        GeoJsonGeometry.write(g, utm == null ? null : Crs.toWgs84(utm), COORD_DECIMALS);
        g.writeObjectFieldStart("properties");
        g.writeStringField("id", id);
        g.writeStringField("object_type", type);
        g.writeStringField("variant_id", variantId);
    }

    private static void end(JsonGenerator g) throws IOException {
        g.writeEndObject();
        g.writeEndObject();
    }

    private static double r(double v, int decimals) {
        return GeoJsonGeometry.round(v, decimals);
    }
}
