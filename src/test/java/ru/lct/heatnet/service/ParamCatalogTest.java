package ru.lct.heatnet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.lct.heatnet.TestSupport;
import ru.lct.heatnet.plan.PlanParams;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog of parameters: every parameter of a calculation has a class, a description and a source;
 * docs/parameters.md is built from the same list ({@code -DupdateDocs=true} rewrites it).
 */
class ParamCatalogTest {

    private final ParamCatalog catalog = new ParamCatalog(new PlanParams(), TestSupport.REF);

    @Test
    void everyRunParameterIsInTheCatalogWithClassAndDescription() {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> e : catalog.entries()) {
            String key = (String) e.get("key");
            assertTrue(keys.add(key), "unique key " + key);
            assertTrue(ParamCatalog.CLASS_TITLES.containsKey((String) e.get("class")), key);
            for (String f : new String[]{"title", "description", "type", "class_title"}) {
                assertNotNull(e.get(f), key + "." + f);
                assertFalse(String.valueOf(e.get(f)).trim().isEmpty(), key + "." + f);
            }
            // the source is a document; a decision of the service has none
            assertFalse(e.containsKey("source") && String.valueOf(e.get("source")).trim().isEmpty(), key + ".source");
            // rules are read-only and name their document
            if (ParamCatalog.NORM.equals(e.get("class"))) {
                assertNotNull(e.get("source"), key + ".source");
            }
            if (Boolean.TRUE.equals(e.get("editable"))) {
                assertNotNull(e.get("default"), key);
            } else {
                assertNotNull(e.get("value"), key);
            }
        }
        // every field of the request of a calculation (except its name and note) is described
        for (Field f : RunParams.class.getFields()) {
            com.fasterxml.jackson.annotation.JsonProperty p = f.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            if (p == null || "name".equals(p.value()) || "note".equals(p.value())) {
                continue;
            }
            assertTrue(keys.contains(p.value()), "catalog has " + p.value());
        }
        // the diagnostic codes of the assumptions are known
        for (Map<String, Object> e : catalog.entries()) {
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) e.get("diagnostic_codes");
            if (codes != null) {
                for (String c : codes) {
                    assertTrue(IssueCatalog.isKnown(c), c);
                }
            }
        }
        for (String code : IssueCatalog.codes()) {
            Object param = IssueCatalog.describe(code).get("param");
            assertTrue(param == null || keys.contains((String) param), code + " → " + param);
        }
    }

    @Test
    void catalogSerializes() throws Exception {
        String json = new ObjectMapper().writeValueAsString(catalog.catalog());
        assertTrue(json.contains("\"classes\"") && json.contains("\"restriction_types\""));
    }

    @Test
    void parametersDocumentIsUpToDate() throws Exception {
        Path doc = Paths.get("docs", "parameters.md");
        String expected = catalog.toMarkdown();
        if (Boolean.getBoolean("updateDocs")) {
            Files.write(doc, expected.getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(Files.exists(doc), "run with -DupdateDocs=true to create " + doc);
        assertEquals(expected, new String(Files.readAllBytes(doc), StandardCharsets.UTF_8),
                "docs/parameters.md is out of date: run mvn test -Dtest=ParamCatalogTest -DupdateDocs=true");
    }
}
