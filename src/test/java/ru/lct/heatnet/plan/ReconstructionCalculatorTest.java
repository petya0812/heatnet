package ru.lct.heatnet.plan;

import org.junit.jupiter.api.Test;
import ru.lct.heatnet.input.Diagnostics;
import ru.lct.heatnet.input.InputModel;
import ru.lct.heatnet.input.LoadedInput;
import ru.lct.heatnet.reference.ReferenceData;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconstructionCalculatorTest {

    private static final ReferenceData REF = ReferenceData.load();

    private static InputModel small() throws Exception {
        return LoadedInput.read(Paths.get("test-data", "small.geojson"), REF).getModel();
    }

    private static ReconstructionCalculator.Piece piece(List<ReconstructionCalculator.Piece> ps, String seg,
                                                        double added) {
        for (ReconstructionCalculator.Piece p : ps) {
            if (p.segment.id.equals(seg) && Math.abs(p.addedFlow - added) < 1e-9) {
                return p;
            }
        }
        throw new AssertionError("no piece " + seg + " +" + added);
    }

    @Test
    void tieInsideSegmentLoadsOnlyTheUpstreamPart() throws Exception {
        InputModel m = small();
        // m1: 900 t/h, DN 400 (943.1). +50 at 120 m from the source -> [0,120] needs DN 500.
        List<ReconstructionCalculator.Piece> ps = new ReconstructionCalculator(m, REF).compute(
                Arrays.asList(new ReconstructionCalculator.Load("m1", false, 120, 50)), new Diagnostics());
        assertEquals(1, ps.size());
        ReconstructionCalculator.Piece p = ps.get(0);
        assertEquals(0, p.from, 1e-9);
        assertEquals(120, p.to, 1e-9);
        assertEquals(500, p.requiredDn);
        assertEquals(120, p.line().getLength(), 1e-3);
    }

    @Test
    void flowsOfSeveralTieInsAddUpOnTheCommonPart() throws Exception {
        InputModel m = small();
        List<ReconstructionCalculator.Piece> ps = new ReconstructionCalculator(m, REF).compute(Arrays.asList(
                new ReconstructionCalculator.Load("m1", false, 50, 20),
                new ReconstructionCalculator.Load("m1", false, 150, 30),
                new ReconstructionCalculator.Load("k3", true, 0, 5)), new Diagnostics());
        // k3 -> m3 -> k2 -> m2 -> k1 -> m1: +5 on all of m3, m2, m1
        assertEquals(200, piece(ps, "m3", 5).to - piece(ps, "m3", 5).from, 1e-3);
        assertEquals(200, piece(ps, "m2", 5).to - piece(ps, "m2", 5).from, 1e-3);
        // m1: [0,50] +55, [50,150] +35, [150,200] +5
        assertEquals(50, piece(ps, "m1", 55).to, 1e-3);
        assertEquals(100, piece(ps, "m1", 35).to - piece(ps, "m1", 35).from, 1e-3);
        assertEquals(50, piece(ps, "m1", 5).to - piece(ps, "m1", 5).from, 1e-3);
        assertTrue(piece(ps, "m1", 55).needsReconstruction());   // 955 > 943.1
        assertFalse(piece(ps, "m1", 35).needsReconstruction());  // 935
        assertFalse(piece(ps, "m3", 5).needsReconstruction());   // 145 < 152.3
    }

    @Test
    void reversedSegmentIsMeasuredFromItsUpstreamEnd() throws Exception {
        InputModel m = small();
        // m4 (drawn 800 -> 600, upstream k3 at 600): tie 50 m from k3, +110 t/h -> 160 > 65.1 (DN 150)
        List<ReconstructionCalculator.Piece> ps = new ReconstructionCalculator(m, REF).compute(
                Arrays.asList(new ReconstructionCalculator.Load("m4", false, 50, 110)), new Diagnostics());
        ReconstructionCalculator.Piece p = piece(ps, "m4", 110);
        assertEquals(50, p.to - p.from, 1e-3);
        assertTrue(p.line().getStartPoint().distance(m.getChambers().get("k3").point) < 1e-6);
    }
}
