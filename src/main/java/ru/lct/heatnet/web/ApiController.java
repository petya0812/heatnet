package ru.lct.heatnet.web;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.lct.heatnet.service.DatasetRepository;
import ru.lct.heatnet.service.DatasetService;
import ru.lct.heatnet.service.ExplainService;
import ru.lct.heatnet.service.IssueCatalog;
import ru.lct.heatnet.service.ParamCatalog;
import ru.lct.heatnet.service.MapRepository;
import ru.lct.heatnet.service.NotFoundException;
import ru.lct.heatnet.service.RunParams;
import ru.lct.heatnet.service.RunService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
public class ApiController {

    private static final MediaType GEOJSON = MediaType.parseMediaType("application/geo+json");
    private static final MediaType MVT = MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    private final DatasetService datasetService;
    private final DatasetRepository datasets;
    private final MapRepository map;
    private final RunService runs;
    private final ExplainService explain;
    private final ParamCatalog catalog;

    public ApiController(DatasetService datasetService, DatasetRepository datasets, MapRepository map,
                         RunService runs, ExplainService explain, ParamCatalog catalog) {
        this.datasetService = datasetService;
        this.datasets = datasets;
        this.map = map;
        this.runs = runs;
        this.explain = explain;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------------ datasets

    @PostMapping(value = "/api/datasets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestPart("file") MultipartFile file) throws IOException {
        UUID id = datasetService.upload(file);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", "UPLOADED");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/api/datasets")
    public List<Map<String, Object>> list() {
        return datasets.list();
    }

    @GetMapping("/api/datasets/{id}")
    public Map<String, Object> dataset(@PathVariable UUID id) {
        return datasetService.get(id);
    }

    @DeleteMapping("/api/datasets/{id}")
    public ResponseEntity<Void> deleteDataset(@PathVariable UUID id) {
        datasetService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/datasets/{id}/cancel")
    public Map<String, Object> cancelImport(@PathVariable UUID id) {
        datasetService.cancel(id);
        return datasetService.get(id);
    }

    @GetMapping("/api/datasets/{id}/issues")
    public List<Map<String, Object>> issues(@PathVariable UUID id,
                                            @RequestParam(required = false) String severity,
                                            @RequestParam(defaultValue = "1000") int limit) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> i : datasets.issues(id, severity, limit)) {
            Map<String, Object> m = new LinkedHashMap<>(i);
            Map<String, Object> d = IssueCatalog.describe((String) i.get("code"));
            m.put("group", d.get("group"));
            m.put("title", d.get("title"));
            m.put("meaning", d.get("meaning"));
            m.put("param", d.get("param"));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/api/datasets/{id}/overview")
    public Map<String, Object> overview(@PathVariable UUID id) {
        return datasetService.overview(id);
    }

    @GetMapping("/api/datasets/{id}/issue-features")
    public ResponseEntity<StreamingResponseBody> issueFeatures(@PathVariable UUID id, @RequestParam String code) {
        requireDataset(id);
        return ResponseEntity.ok().contentType(GEOJSON).body(out -> datasets.writeIssueFeatures(id, code, out));
    }

    @PostMapping("/api/datasets/{id}/versions")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> createVersion(@PathVariable UUID id,
                                                             @RequestBody Map<String, Object> body) {
        UUID version = datasetService.createVersion(id, (List<Map<String, Object>>) body.get("edits"),
                (String) body.get("note"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", version);
        m.put("status", "IMPORTING");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(m);
    }

    @PutMapping("/api/datasets/{id}/edits")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> updateEdits(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        datasetService.updateEdits(id, (List<Map<String, Object>>) body.get("edits"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(datasetService.get(id));
    }

    @GetMapping("/api/datasets/{id}/tiles/{z}/{x}/{y}.mvt")
    public ResponseEntity<byte[]> tile(@PathVariable UUID id, @PathVariable int z, @PathVariable int x,
                                       @PathVariable int y) {
        requireDataset(id);
        return ResponseEntity.ok().contentType(MVT).cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .body(map.tile(id, z, x, y));
    }

    @GetMapping("/api/datasets/{id}/bbox")
    public double[] bbox(@PathVariable UUID id) {
        return map.bbox(id);
    }

    @GetMapping("/api/datasets/{id}/features/{fid}")
    public ResponseEntity<StreamingResponseBody> feature(@PathVariable UUID id, @PathVariable String fid) {
        requireDataset(id);
        return ResponseEntity.ok().contentType(GEOJSON).body(out -> map.writeFeatures(id, fid, out));
    }

    // ------------------------------------------------------------------ runs

    @GetMapping("/api/params")
    public Map<String, Object> params() {
        return catalog.catalog();
    }

    @GetMapping("/api/issue-catalog")
    public Map<String, Object> issueCatalog() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String code : IssueCatalog.codes()) {
            out.put(code, IssueCatalog.describe(code));
        }
        return out;
    }

    @GetMapping("/api/run-params")
    public RunParams runDefaults() {
        return runs.defaults();
    }

    @PostMapping("/api/datasets/{id}/runs")
    public ResponseEntity<Map<String, Object>> startRun(@PathVariable UUID id,
                                                        @RequestBody(required = false) RunParams params) {
        UUID runId = runs.start(id, params);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", runId);
        body.put("status", "QUEUED");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/api/datasets/{id}/runs")
    public List<Map<String, Object>> runsOf(@PathVariable UUID id) {
        return runs.list(id);
    }

    @GetMapping("/api/runs/{id}")
    public Map<String, Object> run(@PathVariable UUID id) {
        return runs.get(id);
    }

    @PostMapping("/api/runs/{id}/cancel")
    public Map<String, Object> cancelRun(@PathVariable UUID id) {
        runs.cancel(id);
        return runs.get(id);
    }

    @PatchMapping("/api/runs/{id}")
    public Map<String, Object> updateRun(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return runs.update(id, body);
    }

    @GetMapping("/api/runs/{id}/journal")
    public Map<String, Object> journal(@PathVariable UUID id, @RequestParam(defaultValue = "1") String variant,
                                       @RequestParam(defaultValue = "0") int after) throws IOException {
        return runs.journal(id, variant, after);
    }

    @GetMapping("/api/runs/{id}/oks/{oksId}/search")
    public Map<String, Object> search(@PathVariable UUID id, @PathVariable String oksId) {
        return explain.searchTrace(id, oksId);
    }

    @GetMapping("/api/compare")
    public Map<String, Object> compare(@RequestParam String a, @RequestParam String b) {
        String[] x = ref(a);
        String[] y = ref(b);
        return explain.compare(UUID.fromString(x[0]), x[1], UUID.fromString(y[0]), y[1]);
    }

    @GetMapping("/api/compare/features")
    public ResponseEntity<StreamingResponseBody> compareFeatures(@RequestParam String a, @RequestParam String b) {
        String[] x = ref(a);
        String[] y = ref(b);
        return ResponseEntity.ok().contentType(GEOJSON).body(out -> explain.writeCompareGeoJson(UUID.fromString(x[0]),
                x[1], UUID.fromString(y[0]), y[1], out));
    }

    private static String[] ref(String s) {
        int i = s.lastIndexOf(':');
        if (i <= 0 || i == s.length() - 1) {
            throw new IllegalArgumentException("Expected <run id>:<variant>, got " + s);
        }
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    @GetMapping(value = "/api/runs/{id}/variants/{variant}/report.html", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable UUID id, @PathVariable String variant,
                                         @RequestParam(defaultValue = "false") boolean download) {
        HttpHeaders h = new HttpHeaders();
        if (download) {
            h.setContentDisposition(ContentDisposition.attachment()
                    .filename("report-" + id.toString().substring(0, 8) + "-v" + variant + ".html").build());
        }
        return ResponseEntity.ok().headers(h).contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(explain.report(id, variant));
    }

    @DeleteMapping("/api/runs/{id}")
    public ResponseEntity<Void> deleteRun(@PathVariable UUID id) {
        runs.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/runs/{id}/validation")
    public Object validation(@PathVariable UUID id) {
        Object v = runs.get(id).get("validation");
        return v != null ? v : Collections.emptyMap();
    }

    @GetMapping(value = "/api/runs/{id}/result.geojson", produces = "application/geo+json")
    public ResponseEntity<Resource> result(@PathVariable UUID id) {
        Path file = runs.resultFile(id);
        HttpHeaders h = new HttpHeaders();
        h.setContentDisposition(ContentDisposition.attachment().filename("result-" + id + ".geojson").build());
        return ResponseEntity.ok().headers(h).contentType(GEOJSON).body(new FileSystemResource(file));
    }

    @GetMapping("/api/runs/{id}/variants/{variant}.geojson")
    public ResponseEntity<StreamingResponseBody> variant(@PathVariable UUID id, @PathVariable String variant,
                                                         @RequestParam(defaultValue = "true") boolean derived,
                                                         @RequestParam(defaultValue = "false") boolean download)
            throws Exception {
        runs.requireVariant(id, variant); // 400 / 404 / 409 before streaming starts
        HttpHeaders h = new HttpHeaders();
        if (download) {
            h.setContentDisposition(ContentDisposition.attachment()
                    .filename("variant-" + id.toString().substring(0, 8) + "-v" + variant + ".geojson").build());
        }
        return ResponseEntity.ok().headers(h).contentType(GEOJSON).body(out -> {
            try {
                runs.writeVariant(id, variant, derived, out);
            } catch (IOException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        });
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return runs.load();
    }

    private void requireDataset(UUID id) {
        Map<String, Object> ds = datasets.get(id);
        if (ds == null) {
            throw new NotFoundException("Dataset " + id + " not found");
        }
        if (!"READY".equals(ds.get("status"))) {
            throw new IllegalStateException("Dataset is " + ds.get("status") + ", expected READY");
        }
    }

    // ------------------------------------------------------------------ errors

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** A body that does not parse or a field of the wrong type: the same error format as everywhere else. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, String>> unreadable(Exception e) {
        Throwable cause = e.getCause();
        String message;
        if (cause instanceof com.fasterxml.jackson.databind.JsonMappingException
                && !(cause instanceof com.fasterxml.jackson.core.JsonParseException)) {
            com.fasterxml.jackson.databind.JsonMappingException jme = (com.fasterxml.jackson.databind.JsonMappingException) cause;
            String field = jme.getPath().isEmpty() ? null : jme.getPath().get(jme.getPath().size() - 1).getFieldName();
            message = (field != null ? "Field '" + field + "': " : "") + firstLine(jme.getOriginalMessage());
        } else if (e instanceof HttpMessageNotReadableException) {
            message = "Request body is not valid JSON: " + firstLine(mostSpecific(e));
        } else if (e instanceof MethodArgumentTypeMismatchException) {
            message = "Invalid value of '" + ((MethodArgumentTypeMismatchException) e).getName() + "': " + firstLine(mostSpecific(e));
        } else {
            message = firstLine(e.getMessage());
        }
        return error(HttpStatus.BAD_REQUEST, message);
    }

    private static String mostSpecific(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "Bad request";
        }
        int i = s.indexOf('\n');
        String line = i < 0 ? s : s.substring(0, i);
        int at = line.indexOf(" at [Source");
        return (at < 0 ? line : line.substring(0, at)).trim();
    }

    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<Map<String, String>> busy(TaskRejectedException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "The queue is full, try again later");
    }

    @ExceptionHandler(DatasetService.InsufficientStorageException.class)
    public ResponseEntity<Map<String, String>> storage(DatasetService.InsufficientStorageException e) {
        return error(HttpStatus.INSUFFICIENT_STORAGE, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Collections.singletonMap("error", message));
    }
}
