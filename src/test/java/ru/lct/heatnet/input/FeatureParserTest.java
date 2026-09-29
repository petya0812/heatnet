package ru.lct.heatnet.input;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.reference.ReferenceData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureParserTest {

    private static final ReferenceData REF = ReferenceData.load();

    private static InputStream json(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reportsBrokenFeaturesAndKeepsGoodOnes() throws IOException {
        String fc = "{\"type\":\"FeatureCollection\",\"name\":\"x\",\"features\":["
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]},"
                + "\"properties\":{\"id\":\"src\",\"object_type\":\"source\"}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]},"
                + "\"properties\":{\"id\":\"a\",\"object_type\":\"spaceship\"}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]},"
                + "\"properties\":{\"id\":\"b\",\"object_type\":\"heat_network\",\"diameter\":100,\"flow_tph\":1,"
                + "\"upstream_object_id\":\"src\"}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6,55.7],[37.61,55.7]]},"
                + "\"properties\":{\"id\":\"c\",\"object_type\":\"heat_network\",\"flow_tph\":1,"
                + "\"upstream_object_id\":\"src\"}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37.6,55.7],[37.61,55.7],"
                + "[37.61,55.71],[37.6,55.7]]]},\"properties\":{\"id\":\"d\",\"object_type\":\"restriction\","
                + "\"restriction_type\":\"metro\"}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[412345,6179000]},"
                + "\"properties\":{\"id\":\"e\",\"object_type\":\"source\"}}"
                + "]}";
        Diagnostics diag = new Diagnostics();
        List<ParsedFeature> ok = new ArrayList<>();
        long n = new FeatureParser(REF).parse(json(fc), diag, ok::add);
        assertEquals(6, n);
        assertEquals(2, ok.size());
        assertTrue(diag.hasCode("feature.unknown_object_type"));
        assertTrue(diag.hasCode("feature.geometry_type"));
        assertTrue(diag.hasCode("feature.missing_attribute"));
        assertTrue(diag.hasCode("restriction.unknown_type"));
        assertTrue(diag.hasCode("feature.coordinates_out_of_range"));
    }

    @Test
    void modelChecksReferencesAndOrientation() throws IOException {
        LoadedInput in = LoadedInput.read(java.nio.file.Paths.get("test-data", "small.geojson"), REF);
        InputModel m = in.getModel();
        assertEquals(5, m.getSegments().size());
        // m4 is drawn downstream-to-upstream: oriented geometry must start at chamber k3
        InputModel.Segment m4 = m.getSegments().get("m4");
        assertTrue(m4.inputReversed);
        assertTrue(m4.line.getStartPoint().distance(m.getChambers().get("k3").point) < 0.01);
        assertEquals(3, m.getChambers().get("k2").adjacentSegments.size());
        assertEquals(0, in.getDiagnostics().count(Diagnostics.Severity.ERROR));
    }

    @Test
    void streamsManyFeaturesWithoutKeepingThem() throws IOException {
        int count = 200_000;
        String head = "{\"type\":\"FeatureCollection\",\"features\":[";
        String item = "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[37.6,55.7],"
                + "[37.6001,55.7],[37.6001,55.7001],[37.6,55.7]]]},\"properties\":{\"id\":\"%d\","
                + "\"object_type\":\"oks_existing\"}}";
        Enumeration<InputStream> parts = new Enumeration<InputStream>() {
            int i = -1;

            @Override
            public boolean hasMoreElements() {
                return i <= count;
            }

            @Override
            public InputStream nextElement() {
                i++;
                if (i == 0) {
                    return json(head);
                }
                if (i > count) {
                    return json("]}");
                }
                return json((i > 1 ? "," : "") + String.format(item, i));
            }
        };
        AtomicLong seen = new AtomicLong();
        Diagnostics diag = new Diagnostics();
        long n = new FeatureParser(REF).parse(new SequenceInputStream(parts), diag, f -> seen.incrementAndGet());
        assertEquals(count, n);
        assertEquals(count, seen.get());
        assertEquals(Collections.emptyMap(), diag.getCounts());
    }
}
