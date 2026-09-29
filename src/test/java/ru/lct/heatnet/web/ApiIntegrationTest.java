package ru.lct.heatnet.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP API against a real PostGIS: upload and import, vector tiles, features, runs with parameters,
 * layers of a variant, reasons of unconnected OKS, cancellation, deletion.
 * <p>
 * Runs only with {@code -Dheatnet.it.db=jdbc:postgresql://host:5432/<db>} (user/password heatnet by default,
 * {@code -Dheatnet.it.user}, {@code -Dheatnet.it.password}); a separate database {@code heatnet_it} is created there,
 * the data of the service itself is not touched. In the compose setup:
 * {@code docker run --rm --network heatnet_default -v "$PWD":/w -v ~/.m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-11
 * mvn -B verify -Dheatnet.it.db=jdbc:postgresql://db:5432/heatnet}.
 */
@EnabledIfSystemProperty(named = "heatnet.it.db", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String IT_DB = "heatnet_it";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String url = System.getProperty("heatnet.it.db");
        String user = System.getProperty("heatnet.it.user", "heatnet");
        String password = System.getProperty("heatnet.it.password", "heatnet");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + IT_DB + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + IT_DB);
        }
        String itUrl = url.substring(0, url.lastIndexOf('/') + 1) + IT_DB;
        Path storage = Files.createTempDirectory("heatnet-it");
        r.add("spring.datasource.url", () -> itUrl);
        r.add("spring.datasource.username", () -> user);
        r.add("spring.datasource.password", () -> password);
        r.add("spring.servlet.multipart.location", () -> storage.resolve("tmp").toString());
        r.add("heatnet.storage-dir", storage::toString);
        r.add("heatnet.min-free-disk-mb", () -> "10");
        r.add("heatnet.run-threads", () -> "1");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> r = rest.getForEntity(path, String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode(), path + ": " + r.getBody());
        return MAPPER.readTree(r.getBody());
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.postForEntity(path, new HttpEntity<>(json, h), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), path + ": " + r.getStatusCode() + " " + r.getBody());
        return MAPPER.readTree(r.getBody());
    }

    private String upload(String dataset) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(Paths.get("test-data", dataset)));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> r = rest.postForEntity("/api/datasets", new HttpEntity<>(body, h), String.class);
        assertEquals(HttpStatus.ACCEPTED, r.getStatusCode(), r.getBody());
        String id = MAPPER.readTree(r.getBody()).get("id").asText();
        assertEquals("READY", await("/api/datasets/" + id, "READY", "FAILED", "CANCELLED").get("status").asText());
        return id;
    }

    private JsonNode await(String path, String... final_) throws Exception {
        long until = System.currentTimeMillis() + 180_000;
        while (true) {
            JsonNode n = get(path);
            for (String s : final_) {
                if (s.equals(n.get("status").asText())) {
                    return n;
                }
            }
            if (System.currentTimeMillis() > until) {
                throw new AssertionError(path + " still " + n.get("status"));
            }
            Thread.sleep(200);
        }
    }

    private JsonNode runAndWait(String ds, String params) throws Exception {
        String run = post("/api/datasets/" + ds + "/runs", params).get("id").asText();
        return await("/api/runs/" + run, "DONE", "FAILED", "CANCELLED");
    }

    private static int[] tileOf(double lon, double lat, int z) {
        int n = 1 << z;
        int x = (int) ((lon + 180) / 360 * n);
        double r = Math.toRadians(lat);
        int y = (int) ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n);
        return new int[]{x, y};
    }

    private String tileText(String ds, int z, int[] t) {
        ResponseEntity<byte[]> tile = rest.getForEntity("/api/datasets/" + ds + "/tiles/" + z + "/" + t[0] + "/" + t[1]
                + ".mvt", byte[].class);
        assertEquals(HttpStatus.OK, tile.getStatusCode());
        assertEquals("application/vnd.mapbox-vector-tile", tile.getHeaders().getContentType().toString());
        return new String(tile.getBody(), StandardCharsets.ISO_8859_1);
    }

    private static Set<String> types(JsonNode fc) {
        Set<String> t = new HashSet<>();
        fc.get("features").forEach(f -> t.add(f.get("properties").get("object_type").asText()));
        return t;
    }

    // ------------------------------------------------------------------ tests

    private static String small;

    @Test
    @Order(1)
    void uploadImportTilesFeatures() throws Exception {
        small = upload("small.geojson");
        JsonNode ds = get("/api/datasets/" + small);
        assertTrue(ds.get("type_counts").get("oks_future").asInt() > 0);

        JsonNode bbox = get("/api/datasets/" + small + "/bbox");
        assertEquals(4, bbox.size());
        double lon = (bbox.get(0).asDouble() + bbox.get(2).asDouble()) / 2;
        double lat = (bbox.get(1).asDouble() + bbox.get(3).asDouble()) / 2;
        int[] t13 = tileOf(lon, lat, 13);
        String raw13 = tileText(small, 13, t13);
        assertTrue(raw13.contains("network") && raw13.contains("oks"), "z13 tile has the network and OKS layers");
        assertFalse(raw13.contains("buildings"), "buildings only from z14");
        JsonNode cp = get("/api/datasets/" + small + "/features/oks-1-cp").get("features").get(0).get("geometry");
        int[] t15 = tileOf(cp.get("coordinates").get(0).asDouble(), cp.get("coordinates").get(1).asDouble(), 15);
        assertTrue(tileText(small, 15, t15).contains("connection_points"), "z15 tile has the connection point");
        byte[] low = rest.getForEntity("/api/datasets/" + small + "/tiles/5/1/1.mvt", byte[].class).getBody();
        assertEquals(0, low == null ? 0 : low.length, "below the minimal zoom tiles are empty");
        assertEquals(HttpStatus.BAD_REQUEST, rest.getForEntity("/api/datasets/" + small + "/tiles/3/9/9.mvt",
                String.class).getStatusCode());

        JsonNode f = get("/api/datasets/" + small + "/features/oks-1");
        assertEquals(1, f.get("features").size());
        assertEquals("oks_future", f.get("features").get(0).get("properties").get("object_type").asText());
    }

    @Test
    @Order(1)
    void swaggerIsServed() {
        ResponseEntity<String> docs = rest.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, docs.getStatusCode());
        assertTrue(docs.getBody().contains("/api/datasets"), "OpenAPI lists the API");
        assertEquals(HttpStatus.OK, rest.getForEntity("/swagger-ui/index.html", String.class).getStatusCode());
    }

    @Test
    @Order(2)
    void runWithParametersAndVariantLayers() throws Exception {
        JsonNode run = runAndWait(small, "{\"existing_flow\":\"CAPACITY_SHARE\",\"turn_penalty\":0.2}");
        assertEquals("DONE", run.get("status").asText(), String.valueOf(run.get("error")));
        assertEquals("CAPACITY_SHARE", run.get("params").get("existing_flow").asText());
        assertEquals(0.2, run.get("params").get("turn_penalty").asDouble(), 1e-12);
        assertEquals(0.5, run.get("params").get("existing_flow_share").asDouble(), 1e-12,
                "unset fields take the defaults");
        assertEquals(0, run.get("validation").get("errors").asInt(), run.get("validation").toString());
        String id = run.get("id").asText();

        JsonNode v1 = get("/api/runs/" + id + "/variants/1.geojson");
        Set<String> t = types(v1);
        assertTrue(t.containsAll(java.util.Arrays.asList("heat_network", "attachment", "variant_summary", "flow_change",
                "length_run")), t.toString());
        for (JsonNode f : v1.get("features")) {
            assertEquals("1", f.get("properties").has("variant_id") ? f.get("properties").get("variant_id").asText() : "1");
        }
        JsonNode plain = get("/api/runs/" + id + "/variants/1.geojson?derived=false");
        assertFalse(types(plain).contains("flow_change"));
        assertTrue(plain.get("features").size() < v1.get("features").size());

        ResponseEntity<String> file = rest.getForEntity("/api/runs/" + id + "/result.geojson", String.class);
        assertEquals(HttpStatus.OK, file.getStatusCode());
        assertFalse(file.getBody().contains("flow_change"), "derived layers never go to the output file");

        // an unset body is allowed
        JsonNode def = runAndWait(small, "");
        assertEquals("ZERO", def.get("params").get("existing_flow").asText());
    }

    @Test
    @Order(3)
    void badParametersAreRejected() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.postForEntity("/api/datasets/" + small + "/runs",
                new HttpEntity<>("{\"existing_flow\":\"SOMETIMES\"}", h), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity("/api/datasets/" + java.util.UUID.randomUUID()
                + "/tiles/15/1/1.mvt", String.class).getStatusCode());
    }

    @Test
    @Order(4)
    void unconnectedReasons() throws Exception {
        String dirty = upload("dirty.geojson");
        JsonNode run = runAndWait(dirty, "{}");
        assertEquals("DONE", run.get("status").asText());
        for (JsonNode v : run.get("summary")) {
            Set<String> reasons = new HashSet<>();
            v.get("unconnected").forEach(u -> reasons.add(u.get("oks_id").asText() + ":" + u.get("reason").asText()));
            assertEquals(new HashSet<>(java.util.Arrays.asList("oks-big:FLOW_EXCEEDS_MAX_DN",
                    "oks-nocp:NO_CONNECTION_POINT")), reasons);
        }
        JsonNode v1 = get("/api/runs/" + run.get("id").asText() + "/variants/1.geojson");
        int drawn = 0;
        for (JsonNode f : v1.get("features")) {
            if ("unconnected_oks".equals(f.get("properties").get("object_type").asText())) {
                assertFalse(f.get("geometry").isNull(), "unconnected OKS drawn with its footprint");
                assertFalse(f.get("properties").get("reason_text").asText().isEmpty());
                drawn++;
            }
        }
        assertEquals(2, drawn);
    }

    @Test
    @Order(5)
    void cancelRunningAndQueuedRuns() throws Exception {
        String big = upload("dense-big.geojson");
        // one calculation thread: the first run is running, the second waits in the queue
        String running = post("/api/datasets/" + big + "/runs", "{}").get("id").asText();
        String queued = post("/api/datasets/" + big + "/runs", "{}").get("id").asText();
        long until = System.currentTimeMillis() + 30_000;
        while (!"RUNNING".equals(get("/api/runs/" + running).get("status").asText())
                && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        JsonNode q = post("/api/runs/" + queued + "/cancel", "");
        assertEquals("CANCELLED", q.get("status").asText(), "a queued run is cancelled at once");

        Thread.sleep(500);
        JsonNode during = get("/api/runs/" + running);
        if ("RUNNING".equals(during.get("status").asText())) {
            assertTrue(during.has("progress"), "a running calculation reports its progress");
        }
        post("/api/runs/" + running + "/cancel", "");
        JsonNode r = await("/api/runs/" + running, "CANCELLED", "DONE", "FAILED");
        assertEquals("CANCELLED", r.get("status").asText());
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity("/api/runs/" + running + "/result.geojson", String.class)
                .getStatusCode());
        assertEquals(HttpStatus.CONFLICT, rest.postForEntity("/api/runs/" + running + "/cancel", null, String.class)
                .getStatusCode(), "a cancelled run cannot be cancelled again");
        JsonNode status = get("/api/status");
        assertEquals(0, status.get("running").asInt());
    }

    @Test
    @Order(6)
    void deleteDatasetRemovesTablesAndFiles() throws Exception {
        JsonNode run = runAndWait(small, "{}");
        String runId = run.get("id").asText();
        String runTable = "run_" + runId.replace("-", "");
        String dsTable = "ds_" + small.replace("-", "");
        assertTrue(tableExists(runTable) && tableExists(dsTable));

        ResponseEntity<Void> del = rest.exchange("/api/runs/" + runId, HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode());
        assertFalse(tableExists(runTable));
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity("/api/runs/" + runId, String.class).getStatusCode());

        del = rest.exchange("/api/datasets/" + small, HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode());
        assertFalse(tableExists(dsTable));
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity("/api/datasets/" + small, String.class).getStatusCode());
        Integer runs = jdbc.queryForObject("SELECT count(*) FROM run WHERE dataset_id = ?::uuid", Integer.class, small);
        assertEquals(0, runs);
    }

    // ------------------------------------------------------------------ the workflow of the UI

    private static String contest;
    private static String contestRun;

    private JsonNode send(HttpMethod method, String path, String json) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange(path, method, new HttpEntity<>(json, h), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), path + ": " + r.getStatusCode() + " " + r.getBody());
        return MAPPER.readTree(r.getBody());
    }

    private HttpStatus status(HttpMethod method, String path, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(json, h), String.class).getStatusCode();
    }

    @Test
    @Order(7)
    void parameterCatalogAndOverviewOfADataset() throws Exception {
        JsonNode cat = get("/api/params");
        assertEquals(4, cat.get("classes").size());
        Set<String> keys = new HashSet<>();
        for (JsonNode p : cat.get("params")) {
            keys.add(p.get("key").asText());
            assertTrue(p.hasNonNull("class") && p.hasNonNull("title") && p.hasNonNull("description"), p.toString());
        }
        assertTrue(keys.containsAll(java.util.Arrays.asList("length_limit", "existing_flow", "existing_flow_share",
                "turn_penalty")), keys.toString());
        assertTrue(cat.get("restriction_types").size() >= 8);

        contest = upload("contest-lct.geojson");
        JsonNode o = get("/api/datasets/" + contest + "/overview");
        assertEquals(1, o.get("version").asInt());
        assertEquals(1, o.get("lineage").size());
        assertEquals(29, o.get("contents").get("types").get("heat_network").asInt());
        assertEquals(17, o.get("contents").get("oks").get("count").asInt());
        boolean assumed = false;
        for (JsonNode i : o.get("issues")) {
            assertTrue(i.hasNonNull("title"), i.toString());
            if ("network.flow_assumed".equals(i.get("code").asText())) {
                assertEquals("assumption", i.get("group").asText());
                assertEquals("existing_flow", i.get("param").asText());
                assumed = true;
            }
        }
        assertTrue(assumed);
        for (JsonNode c : o.get("readiness")) {
            assertFalse(c.get("blocking").asBoolean(), c.toString());
        }
        JsonNode fs = get("/api/datasets/" + contest + "/issue-features?code=network.chamber_dn_assumed");
        assertEquals(9, fs.get("features").size());
    }

    @Test
    @Order(8)
    void runWithNameNotePinJournalAndSearchTrace() throws Exception {
        JsonNode run = runAndWait(contest, "{\"name\":\"базовый\",\"note\":\"для сравнения\"}");
        assertEquals("DONE", run.get("status").asText(), String.valueOf(run.get("error")));
        contestRun = run.get("id").asText();
        assertEquals("базовый", run.get("name").asText());
        assertEquals("для сравнения", run.get("note").asText());
        assertFalse(run.get("pinned").asBoolean());
        assertFalse(run.get("params").has("name"), "name is not a parameter of the calculation");
        JsonNode v1 = run.get("summary").get(0);
        assertTrue(v1.get("plain").asText().contains("ОКС"), v1.get("plain").asText());
        assertEquals(17, v1.get("oks").size());
        for (JsonNode x : v1.get("oks")) {
            if (x.get("connected").asBoolean()) {
                assertTrue(x.get("path_segment_ids").size() > 0 && x.get("existing_chain").size() > 0, x.toString());
            }
        }

        JsonNode patched = send(HttpMethod.PATCH, "/api/runs/" + contestRun, "{\"pinned\":true,\"note\":\"закреплён\"}");
        assertTrue(patched.get("pinned").asBoolean());
        assertEquals("закреплён", patched.get("note").asText());
        assertEquals(HttpStatus.BAD_REQUEST, status(HttpMethod.PATCH, "/api/runs/" + contestRun, "{\"name\":\"\"}"));

        // a run without a name gets one from its parameters
        JsonNode other = runAndWait(contest, "{\"existing_flow\":\"CAPACITY_SHARE\",\"existing_flow_share\":0.5}");
        assertTrue(other.get("name").asText().contains("занята"), other.get("name").asText());

        JsonNode journal = get("/api/runs/" + contestRun + "/journal?variant=1");
        assertFalse(journal.get("live").asBoolean());
        JsonNode events = journal.get("events");
        assertEquals("start", events.get(0).get("type").asText());
        assertEquals("finish", events.get(events.size() - 1).get("type").asText());
        boolean connect = false;
        for (JsonNode e : events) {
            if ("connect".equals(e.get("type").asText())) {
                double lon = e.get("route").get("coords").get(0).get(0).asDouble();
                assertTrue(lon > 30 && lon < 45, "coordinates in WGS 84: " + lon);
                assertTrue(e.get("alternatives").size() > 0);
                connect = true;
            }
        }
        assertTrue(connect);
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity("/api/runs/" + contestRun + "/journal?variant=9",
                String.class).getStatusCode());

        String oks = v1.get("oks").get(0).get("oks_id").asText();
        JsonNode trace = get("/api/runs/" + contestRun + "/oks/" + oks + "/search");
        assertTrue(trace.get("vertices").size() > 0 && trace.get("expansions").size() > 0);
        assertTrue(trace.has("route"), trace.toString().substring(0, 200));
        assertTrue(trace.get("zones").get("features").size() > 0);
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity("/api/runs/" + contestRun + "/oks/nope/search",
                String.class).getStatusCode());

        ResponseEntity<String> report = rest.getForEntity("/api/runs/" + contestRun + "/variants/1/report.html",
                String.class);
        assertEquals(HttpStatus.OK, report.getStatusCode());
        assertTrue(report.getBody().contains("<svg") && report.getBody().contains("Проверка обязательных правил"));
    }

    @Test
    @Order(9)
    void versionWithAnEditAndComparison() throws Exception {
        String polygon = "{\"type\":\"Polygon\",\"coordinates\":[[[37.6400,55.6980],[37.6403,55.6980],"
                + "[37.6403,55.6982],[37.6400,55.6982],[37.6400,55.6980]]]}";
        assertEquals(HttpStatus.BAD_REQUEST, status(HttpMethod.POST, "/api/datasets/" + contest + "/versions",
                "{\"edits\":[{\"op\":\"add_restriction\",\"restriction_type\":\"lava\",\"geometry\":" + polygon + "}]}"));
        JsonNode created = send(HttpMethod.POST, "/api/datasets/" + contest + "/versions",
                "{\"note\":\"стройплощадка\",\"edits\":[{\"op\":\"add_restriction\",\"restriction_type\":"
                        + "\"prohibited_site\",\"comment\":\"забор\",\"geometry\":" + polygon + "}]}");
        String version = created.get("id").asText();
        JsonNode v = await("/api/datasets/" + version, "READY", "FAILED");
        assertEquals("READY", v.get("status").asText(), String.valueOf(v.get("error")));
        assertEquals(2, v.get("version").asInt());
        assertEquals(contest, v.get("parent_id").asText());
        assertEquals(145, v.get("feature_count").asInt(), "one object more than the original");
        assertEquals(144, get("/api/datasets/" + contest).get("feature_count").asInt(), "the original is not changed");
        JsonNode o = get("/api/datasets/" + version + "/overview");
        assertEquals(2, o.get("lineage").size());
        boolean noted = false;
        for (JsonNode i : o.get("issues")) {
            noted |= "edit.restriction_added".equals(i.get("code").asText());
        }
        assertTrue(noted);

        // edits of a version may be replaced while it has no calculations
        JsonNode replaced = send(HttpMethod.PUT, "/api/datasets/" + version + "/edits",
                "{\"edits\":[{\"restriction_type\":\"park\",\"geometry\":" + polygon + "}]}");
        assertEquals("IMPORTING", replaced.get("status").asText());
        assertEquals("READY", await("/api/datasets/" + version, "READY", "FAILED").get("status").asText());
        assertEquals(HttpStatus.CONFLICT, status(HttpMethod.PUT, "/api/datasets/" + contest + "/edits",
                "{\"edits\":[{\"restriction_type\":\"park\",\"geometry\":" + polygon + "}]}"),
                "the uploaded dataset itself is not edited");

        JsonNode vr = runAndWait(version, "{}");
        assertEquals("DONE", vr.get("status").asText());
        assertEquals(2, vr.get("dataset_version").asInt());
        assertEquals(HttpStatus.CONFLICT, status(HttpMethod.PUT, "/api/datasets/" + version + "/edits",
                "{\"edits\":[{\"restriction_type\":\"park\",\"geometry\":" + polygon + "}]}"),
                "a version with calculations is not edited");

        JsonNode cmp = get("/api/compare?a=" + contestRun + ":1&b=" + vr.get("id").asText() + ":1");
        assertFalse(cmp.get("data").get("same_dataset").asBoolean());
        assertTrue(cmp.get("data").get("same_family").asBoolean());
        assertEquals(1, cmp.get("data").get("edits_only_b").size());
        assertEquals(0, cmp.get("data").get("edits_only_a").size());
        assertTrue(cmp.get("metrics").size() > 10);
        assertTrue(cmp.get("geometry").get("common_length").asDouble() > 0);
        assertTrue(cmp.hasNonNull("explanation"));

        JsonNode same = get("/api/compare?a=" + contestRun + ":1&b=" + contestRun + ":2");
        assertTrue(same.get("data").get("same_dataset").asBoolean());
        assertEquals(0, same.get("params").size());
        JsonNode features = get("/api/compare/features?a=" + contestRun + ":1&b=" + contestRun + ":2");
        Set<String> sides = new HashSet<>();
        features.get("features").forEach(f -> sides.add(f.get("properties").get("side").asText()));
        assertTrue(sides.contains("common"), sides.toString());
        assertEquals(HttpStatus.BAD_REQUEST, rest.getForEntity("/api/compare?a=x&b=y", String.class).getStatusCode());

        // deleting the uploaded dataset removes its versions
        ResponseEntity<Void> del = rest.exchange("/api/datasets/" + contest, HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity("/api/datasets/" + version, String.class).getStatusCode());
        assertFalse(tableExists("ds_" + version.replace("-", "")));
    }

    @Test
    @Order(10)
    void versionWithARemovedObjectAndAChangedAttribute() throws Exception {
        String parent = upload("small.geojson");
        String versions = "/api/datasets/" + parent + "/versions";
        // what the checks of the edits reject
        assertEquals(HttpStatus.BAD_REQUEST, status(HttpMethod.POST, versions,
                "{\"edits\":[{\"op\":\"remove_object\",\"target_id\":\"src\"}]}"), "the source is not removed");
        assertEquals(HttpStatus.BAD_REQUEST, status(HttpMethod.POST, versions,
                "{\"edits\":[{\"op\":\"remove_object\",\"target_id\":\"nope\"}]}"), "no such object");
        assertEquals(HttpStatus.BAD_REQUEST, status(HttpMethod.POST, versions,
                "{\"edits\":[{\"op\":\"set_attribute\",\"target_id\":\"m4\",\"attribute\":\"cost\",\"value\":1}]}"),
                "the attribute is not editable");
        assertEquals(HttpStatus.BAD_REQUEST, status(HttpMethod.POST, versions,
                "{\"edits\":[{\"op\":\"set_attribute\",\"target_id\":\"m4\",\"attribute\":\"diameter\","
                        + "\"value\":123}]}"), "123 is not a standard DN");
        assertEquals(HttpStatus.BAD_REQUEST, status(HttpMethod.POST, versions,
                "{\"edits\":[{\"op\":\"set_attribute\",\"target_id\":\"oks-1-cp\",\"attribute\":\"flow_tph\","
                        + "\"value\":-5}]}"), "the flow is not negative");

        long before = get("/api/datasets/" + parent).get("feature_count").asLong();
        JsonNode created = send(HttpMethod.POST, versions,
                "{\"note\":\"убрали парк, подняли ДУ\",\"edits\":["
                        + "{\"op\":\"remove_object\",\"target_id\":\"park-1\",\"comment\":\"снят с учёта\"},"
                        + "{\"op\":\"set_attribute\",\"target_id\":\"m4\",\"attribute\":\"diameter\",\"value\":250},"
                        + "{\"op\":\"set_attribute\",\"target_id\":\"oks-1-cp\",\"attribute\":\"flow_tph\","
                        + "\"value\":12.5}]}");
        String version = created.get("id").asText();
        JsonNode v = await("/api/datasets/" + version, "READY", "FAILED");
        assertEquals("READY", v.get("status").asText(), String.valueOf(v.get("error")));
        assertEquals(before - 1, v.get("feature_count").asLong(), "one object fewer than the parent");
        assertEquals(before, get("/api/datasets/" + parent).get("feature_count").asLong(), "the parent is not changed");

        assertEquals(0, get("/api/datasets/" + version + "/features/park-1").get("features").size(),
                "the removed object is gone from the version");
        assertEquals(1, get("/api/datasets/" + parent + "/features/park-1").get("features").size(),
                "and is still in the parent");
        JsonNode segment = get("/api/datasets/" + version + "/features/m4").get("features").get(0).get("properties");
        assertEquals(250, segment.get("diameter").asInt(), "the attribute is changed");
        assertEquals("k3", segment.get("upstream_object_id").asText(), "the other attributes are kept");
        assertEquals(150, get("/api/datasets/" + parent + "/features/m4").get("features").get(0)
                .get("properties").get("diameter").asInt(), "the parent keeps its value");
        assertEquals(12.5, get("/api/datasets/" + version + "/features/oks-1-cp").get("features").get(0)
                .get("properties").get("flow_tph").asDouble());

        JsonNode o = get("/api/datasets/" + version + "/overview");
        Set<String> codes = new HashSet<>();
        o.get("issues").forEach(i -> codes.add(i.get("code").asText()));
        assertTrue(codes.contains("edit.object_removed"), codes.toString());
        assertTrue(codes.contains("edit.attribute_changed"), codes.toString());
        JsonNode removedFeatures = get("/api/datasets/" + version + "/issue-features?code=edit.attribute_changed");
        assertTrue(removedFeatures.get("features").size() > 0, "the changed objects are shown on the map");

        JsonNode run = runAndWait(version, "{}");
        assertEquals("DONE", run.get("status").asText(), String.valueOf(run.get("error")));
        assertEquals(2, run.get("dataset_version").asInt());

        rest.exchange("/api/datasets/" + parent, HttpMethod.DELETE, null, Void.class);
    }

    private boolean tableExists(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = "
                + "'data' AND tablename = ?)", Boolean.class, name));
    }
}
