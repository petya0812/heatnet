package ru.lct.heatnet.service;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.reference.ReferenceData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks of the edits of the input (the operations of a version of a dataset) without a database: the objects of the
 * parent version are answered by a lookup, so every rule — the operation itself, the object it refers to, the
 * attribute and its value — is checked here.
 */
class DatasetEditsTest {

    private static final UUID VERSION = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** The objects of the parent version: the network with a chamber, a source, two OKS and a restriction. */
    private static final Map<String, String> OBJECTS = new LinkedHashMap<>();

    static {
        OBJECTS.put("src-1", "source");
        OBJECTS.put("seg-1", "heat_network");
        OBJECTS.put("seg-2", "heat_network");
        OBJECTS.put("cam-1", "heat_chamber");
        OBJECTS.put("oks-1", "oks_future");
        OBJECTS.put("oks-1-cp", "oks_connection_point");
        OBJECTS.put("oks-2-cp", "oks_connection_point");
        OBJECTS.put("res-1", "restriction");
    }

    private final DatasetService service = new DatasetService(null, null, null, ReferenceData.load(), null, null, null,
            null);

    private static final DatasetRepository.Targets TARGETS = new DatasetRepository.Targets() {
        @Override
        public String objectType(String fid) {
            return OBJECTS.get(fid);
        }

        @Override
        public long count(String objectType) {
            return OBJECTS.values().stream().filter(objectType::equals).count();
        }
    };

    private static Map<String, Object> edit(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @SafeVarargs
    private final List<Map<String, Object>> normalize(Map<String, Object>... edits) {
        return service.normalizeEdits(new ArrayList<>(Arrays.asList(edits)), VERSION, TARGETS);
    }

    @SafeVarargs
    private final String rejected(Map<String, Object>... edits) {
        return assertThrows(IllegalArgumentException.class, () -> normalize(edits)).getMessage();
    }

    @Test
    void unknownOperationIsRejected() {
        assertTrue(rejected(edit("op", "drop_table", "target_id", "seg-1")).contains("drop_table"));
        assertTrue(rejected().contains("No edits"), "an empty list of edits is not a version");
    }

    @Test
    void removeObjectChecksTheObject() {
        Map<String, Object> ok = normalize(edit("op", "remove_object", "target_id", " seg-2 ",
                "comment", " лишний участок ")).get(0);
        assertEquals("remove_object", ok.get("op"));
        assertEquals("seg-2", ok.get("target_id"), "the id is trimmed");
        assertEquals("heat_network", ok.get("object_type"));
        assertEquals("лишний участок", ok.get("comment"));
        assertEquals(VERSION.toString(), ok.get("version_id"));

        assertTrue(rejected(edit("op", "remove_object")).contains("target_id"));
        assertTrue(rejected(edit("op", "remove_object", "target_id", "seg-404")).contains("not in the dataset"));
        assertTrue(rejected(edit("op", "remove_object", "target_id", "src-1")).contains("source"),
                "the source is not removed");
    }

    @Test
    void removingEveryConnectionPointIsRejected() {
        // two points in the parent version: the first removal is fine, the second would leave no OKS to connect
        assertEquals(1, normalize(edit("op", "remove_object", "target_id", "oks-1-cp")).size());
        String message = rejected(edit("op", "remove_object", "target_id", "oks-1-cp"),
                edit("op", "remove_object", "target_id", "oks-2-cp"));
        assertTrue(message.contains("last"), message);
    }

    @Test
    void onlyKnownAttributesOfTheRightTypeAreChanged() {
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-1", "attribute", "cost", "value", 1))
                .contains("cannot be edited"));
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "oks-1-cp", "attribute", "diameter",
                "value", 200)).contains("does not belong"), "a connection point has no DN");
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "cam-1", "attribute", "upstream_object_id",
                "value", "seg-1")).contains("does not belong"), "the direction is an attribute of a segment");
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-404", "attribute", "diameter",
                "value", 200)).contains("not in the dataset"));
    }

    @Test
    void valuesAreCheckedAgainstTheAttribute() {
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-1", "attribute", "diameter",
                "value", 123)).contains("standard DN"), "123 is not a nomenclature of the reference data");
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-1", "attribute", "diameter",
                "value", "200")).contains("must be a number"));
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-1", "attribute", "flow_tph",
                "value", -1)).contains("0 … 10000"));
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-1", "attribute", "flow_tph",
                "value", 10001)).contains("0 … 10000"));
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-1", "attribute", "upstream_object_id",
                "value", "seg-404")).contains("no such object"));
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-1", "attribute", "upstream_object_id",
                "value", "res-1")).contains("restriction"), "the direction points at the network only");
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "seg-1", "attribute", "upstream_object_id",
                "value", "seg-1")).contains("itself"));
        assertTrue(rejected(edit("op", "set_attribute", "target_id", "res-1", "attribute", "restriction_type",
                "value", "lava")).contains("table 2"));
    }

    @Test
    void acceptedValuesAreStoredReadyToApply() {
        List<Map<String, Object>> out = normalize(
                edit("op", "set_attribute", "target_id", "seg-1", "attribute", "diameter", "value", 200),
                edit("op", "set_attribute", "target_id", "oks-1-cp", "attribute", "flow_tph", "value", 12.5),
                edit("op", "set_attribute", "target_id", "seg-2", "attribute", "upstream_object_id", "value", null),
                edit("op", "set_attribute", "target_id", "res-1", "attribute", "restriction_type", "value", "park"));
        assertEquals(4, out.size());
        assertEquals(200, out.get(0).get("value"));
        assertEquals("heat_network", out.get(0).get("object_type"));
        assertEquals(12.5, out.get(1).get("value"));
        assertNull(out.get(2).get("value"), "no direction to the source");
        assertEquals("park", out.get(3).get("value"));
        for (int i = 0; i < out.size(); i++) {
            assertEquals("edit-" + VERSION.toString().substring(0, 8) + "-" + (i + 1), out.get(i).get("id"));
            assertEquals("set_attribute", out.get(i).get("op"));
        }
    }

    @Test
    void addRestrictionKeepsWorkingAsBefore() {
        Map<String, Object> polygon = new LinkedHashMap<>();
        polygon.put("type", "Polygon");
        polygon.put("coordinates", Arrays.asList(Arrays.asList(Arrays.asList(37.64, 55.698),
                Arrays.asList(37.6403, 55.698), Arrays.asList(37.6403, 55.6982), Arrays.asList(37.64, 55.6982),
                Arrays.asList(37.64, 55.698))));
        // without "op" an edit is add_restriction (the default operation)
        Map<String, Object> m = normalize(edit("restriction_type", "prohibited_site", "geometry", polygon,
                "comment", "забор")).get(0);
        assertEquals("add_restriction", m.get("op"));
        assertEquals("prohibited_site", m.get("restriction_type"));
        assertEquals("забор", m.get("comment"));
        assertTrue(((Number) m.get("area_m2")).longValue() > 0);
        assertTrue(rejected(edit("restriction_type", "lava", "geometry", polygon)).contains("table 2"));
    }
}
