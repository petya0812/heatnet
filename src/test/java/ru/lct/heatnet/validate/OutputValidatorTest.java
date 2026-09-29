package ru.lct.heatnet.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.TestSupport;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The validator must catch deliberately broken results (otherwise "0 errors" means nothing). */
class OutputValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static TestSupport.Run run;
    private static JsonNode good;

    @BeforeAll
    static void plan() throws Exception {
        run = TestSupport.run("small.geojson");
        good = MAPPER.readTree(Files.readAllBytes(run.output));
    }

    private static ValidationReport check(Consumer<ArrayNode> mutation) throws Exception {
        ObjectNode copy = good.deepCopy();
        mutation.accept((ArrayNode) copy.get("features"));
        byte[] bytes = MAPPER.writeValueAsBytes(copy);
        return new OutputValidator(TestSupport.REF).validate(run.input, new ByteArrayInputStream(bytes));
    }

    private static ObjectNode first(ArrayNode fs, String type, String field, String value) {
        for (JsonNode f : fs) {
            JsonNode p = f.get("properties");
            if (type.equals(p.get("object_type").asText()) && (field == null || value.equals(p.path(field).asText()))) {
                return (ObjectNode) f;
            }
        }
        throw new AssertionError("no " + type);
    }

    private static void assertRule(ValidationReport rep, String rule) {
        assertTrue(rep.errors().stream().anyMatch(v -> v.getRule().equals(rule)), rule + " expected, got "
                + rep.errors());
    }

    @Test
    void goodResultPasses() throws Exception {
        assertEquals(0, check(fs -> { }).errorCount());
    }

    @Test
    void extraNullAttribute() throws Exception {
        assertRule(check(fs -> ((ObjectNode) first(fs, "technical_node", null, null).get("properties"))
                .putNull("diameter")), "OUT-SCHEMA");
    }

    @Test
    void wrongSegmentCost() throws Exception {
        assertRule(check(fs -> {
            ObjectNode p = (ObjectNode) first(fs, "heat_network", "laying_method", "base").get("properties");
            p.put("cost", p.get("cost").asDouble() * 1.1);
        }), "R-COST");
    }

    @Test
    void specialSectionMarkedAsBase() throws Exception {
        assertRule(check(fs -> {
            ObjectNode p = (ObjectNode) first(fs, "heat_network", "laying_method", "special").get("properties");
            p.put("laying_method", "base");
        }), "R-SPEC");
    }

    @Test
    void wrongFlow() throws Exception {
        assertRule(check(fs -> ((ObjectNode) first(fs, "heat_network", null, null).get("properties"))
                .put("flow_tph", 1234.5)), "R-NET-8");
    }

    @Test
    void wrongScore() throws Exception {
        assertRule(check(fs -> ((ObjectNode) first(fs, "variant_summary", null, null).get("properties"))
                .put("score", 1.0)), "R-SCORE");
    }

    /** A chamber standing on an existing line is as wide as every segment adjoining it (appendix 3.2). */
    @Test
    void chamberNarrowerThanTheNetworkItJoins() throws Exception {
        assertRule(check(fs -> {
            ObjectNode p = (ObjectNode) first(fs, "heat_chamber", null, null).get("properties");
            p.put("diameter", TestSupport.REF.getPipes().get(0).getDn());
        }), "R-TIE-3");
    }

    @Test
    void segmentThroughBuilding() throws Exception {
        // put a vertex of the route to oks-1 in the middle of existing building bld-2
        org.locationtech.jts.geom.Coordinate c = null;
        for (ru.lct.heatnet.input.Obstacle o : run.input.getObstacles()) {
            if ("bld-2".equals(o.getId())) {
                c = ru.lct.heatnet.geo.Crs.toWgs84(o.getGeometry().getCentroid()).getCoordinate();
            }
        }
        final org.locationtech.jts.geom.Coordinate inside = c;
        assertRule(check(fs -> {
            ObjectNode seg = first(fs, "heat_network", "end_node_id", "oks-1-cp");
            ArrayNode coords = (ArrayNode) seg.get("geometry").get("coordinates");
            ArrayNode mid = MAPPER.createArrayNode();
            mid.add(inside.x);
            mid.add(inside.y);
            coords.insert(1, mid);
        }), "R-RESTR");
    }

    @Test
    void duplicateVariant() throws Exception {
        assertRule(check(fs -> {
            List<JsonNode> copies = new java.util.ArrayList<>();
            for (JsonNode f : fs) {
                if ("1".equals(f.get("properties").get("variant_id").asText())) {
                    ObjectNode c = f.deepCopy();
                    ObjectNode p = (ObjectNode) c.get("properties");
                    p.put("variant_id", "9");
                    p.put("id", "dup-" + p.get("id").asText());
                    for (String ref : new String[]{"start_node_id", "end_node_id"}) {
                        if (p.has(ref) && !p.get(ref).asText().endsWith("-cp")) {
                            p.put(ref, "dup-" + p.get(ref).asText());
                        }
                    }
                    if ("variant_summary".equals(p.get("object_type").asText())) {
                        p.put("rank", 4);
                    }
                    copies.add(c);
                }
            }
            copies.forEach(fs::add);
        }), "R-VAR");
    }

    @Test
    void branchingChamberWithTooManySegments() throws Exception {
        assertRule(check(fs -> {
            String junction = null;
            for (JsonNode f : fs) {
                JsonNode p = f.get("properties");
                if ("heat_network".equals(p.get("object_type").asText()) && p.get("start_node_id").asText().contains("-ch-")
                        && "1".equals(p.get("variant_id").asText())) {
                    junction = p.get("start_node_id").asText();
                    break;
                }
            }
            if (junction == null) {
                throw new AssertionError("the small scenario is expected to have a branching chamber in variant 1");
            }
            ObjectNode ch = first(fs, "heat_chamber", "id", junction);
            JsonNode at = ch.get("geometry").get("coordinates");
            for (int k = 0; k < 2; k++) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("type", "Feature");
                ObjectNode g = node.putObject("geometry");
                g.put("type", "Point");
                ArrayNode c = g.putArray("coordinates");
                c.add(at.get(0).asDouble() + 0.00002 * (k + 1));
                c.add(at.get(1).asDouble());
                ObjectNode np = node.putObject("properties");
                np.put("id", "fake-node-" + k);
                np.put("object_type", "technical_node");
                np.put("variant_id", "1");
                fs.add(node);
                ObjectNode seg = first(fs, "heat_network", "start_node_id", junction).deepCopy();
                ObjectNode sp = (ObjectNode) seg.get("properties");
                sp.put("id", "fake-seg-" + k);
                sp.put("end_node_id", "fake-node-" + k);
                ArrayNode sc = ((ObjectNode) seg.get("geometry")).putArray("coordinates");
                sc.add(at.deepCopy());
                sc.add(c.deepCopy());
                fs.add(seg);
            }
        }), "R-NET-5");
    }

    /** A turn sharper than 90° (appendix 2.1): the route doubles back on itself. */
    @Test
    void turnSharperThanNinetyDegrees() throws Exception {
        assertRule(check(fs -> {
            for (JsonNode f : fs) {
                JsonNode p = f.get("properties");
                if (!"heat_network".equals(p.get("object_type").asText()) || !"base".equals(p.get("laying_method")
                        .asText())) {
                    continue;
                }
                ArrayNode coords = (ArrayNode) f.get("geometry").get("coordinates");
                org.locationtech.jts.geom.Coordinate a = utm(coords.get(0));
                org.locationtech.jts.geom.Coordinate b = utm(coords.get(1));
                double len = a.distance(b);
                if (len < 20) {
                    continue;
                }
                double ux = (b.x - a.x) / len;
                double uy = (b.y - a.y) / len;
                // a vertex far to the side of the piece: the route comes back almost the way it came
                double off = Math.tan(Math.toRadians(80)) * len / 2;
                org.locationtech.jts.geom.Coordinate m = new org.locationtech.jts.geom.Coordinate(
                        (a.x + b.x) / 2 - uy * off, (a.y + b.y) / 2 + ux * off);
                org.locationtech.jts.geom.Coordinate w = ru.lct.heatnet.geo.Crs.toWgs84(m);
                ArrayNode mid = MAPPER.createArrayNode();
                mid.add(w.x);
                mid.add(w.y);
                coords.insert(1, mid);
                return;
            }
            throw new AssertionError("no straight base piece of 20 m");
        }), "R-TURN");
    }

    /** A DN below the minimal one for the flow of the segment (appendix 2.3). */
    @Test
    void diameterBelowTheFlow() throws Exception {
        assertRule(check(fs -> {
            ObjectNode p = (ObjectNode) first(fs, "heat_network", null, null).get("properties");
            ru.lct.heatnet.reference.PipeSpec min = TestSupport.REF.minPipeForFlow(p.get("flow_tph").asDouble());
            p.put("flow_tph", min.getCapacityTph() * 10);
        }), "R-NET-9");
    }

    /** The summary must agree with the objects of the variant (appendix 7.2). */
    @Test
    void summaryWithoutTheTieInCost() throws Exception {
        assertRule(check(fs -> {
            ObjectNode p = (ObjectNode) first(fs, "variant_summary", null, null).get("properties");
            p.put("existing_chamber_tie_in_count", 0);
            p.put("existing_chamber_tie_in_cost", 0);
        }), "R-SUM");
    }

    private static org.locationtech.jts.geom.Coordinate utm(JsonNode lonLat) {
        return ru.lct.heatnet.geo.Crs.toUtm(ru.lct.heatnet.geo.Crs.WGS84_FACTORY.createPoint(
                new org.locationtech.jts.geom.Coordinate(lonLat.get(0).asDouble(), lonLat.get(1).asDouble())))
                .getCoordinate();
    }

}
