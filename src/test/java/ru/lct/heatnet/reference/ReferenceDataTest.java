package ru.lct.heatnet.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReferenceDataTest {

    private final ReferenceData ref = ReferenceData.load();

    @Test
    void minimalDiameterByCapacity() {
        assertEquals(50, ref.minPipeForFlow(3.5).getDn());
        assertEquals(65, ref.minPipeForFlow(3.51).getDn());
        assertEquals(200, ref.minPipeForFlow(80).getDn());
        assertEquals(1400, ref.minPipeForFlow(22501.9).getDn());
        assertNull(ref.minPipeForFlow(22502));
    }

    @Test
    void chamberCostScale() {
        assertEquals(3_000_000, ref.chamberCost(50));
        assertEquals(3_000_000, ref.chamberCost(200));
        assertEquals(5_000_000, ref.chamberCost(250));
        assertEquals(5_000_000, ref.chamberCost(500));
        assertEquals(8_000_000, ref.chamberCost(600));
        assertEquals(12_000_000, ref.chamberCost(1400));
    }

    @Test
    void buildingClearanceDependsOnDiameter() {
        assertEquals(5.0, ref.buildingDistance(400));
        assertEquals(7.0, ref.buildingDistance(500));
        assertEquals(7.0, ref.buildingDistance(800));
        assertEquals(9.0, ref.buildingDistance(900));
    }

    @Test
    void scoreMatchesAppendixExample() {
        // formula of appendix 6: calculated_cost 51 094 590, length 220.2 -> score 2.091
        assertEquals(2.091, ref.score(51_094_590, 220.2), 5e-4);
        // output example of appendix 7.3: calculated_cost 13 974 800, new_network_length 100.0 -> score 0.6913
        assertEquals(0.6913, ref.score(13_974_800, 100.0), 5e-5);
    }

    /** Table 1 of the appendix, copied into docs/requirements.md, and the reference data agree. */
    @Test
    void tablesMatchTheRegistry() throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(
                java.nio.file.Paths.get("docs", "requirements.md"), java.nio.charset.StandardCharsets.UTF_8);
        int rows = 0;
        String section = "";
        for (String line : lines) {
            if (line.startsWith("### ")) {
                section = line;
                continue;
            }
            if (!line.matches("^\\| \\d+ \\|.*") || !section.contains("Таблица 1")) {
                continue;
            }
            String[] c = line.substring(1, line.length() - 1).split("\\|");
            int dn = Integer.parseInt(c[0].trim());
            PipeSpec p = ref.pipe(dn);
            assertEquals(num(c[1]), p.getCapacityTph(), 1e-9, "capacity " + dn);
            assertEquals(num(c[2]), p.getMaxLengthM(), 1e-9, "length limit " + dn);
            assertEquals(num(c[3]), p.getNewCostPerM(), 1e-9, "new cost " + dn);
            assertEquals(num(c[4]), p.getPairWidthM(), 1e-9, "pair width " + dn);
            assertEquals(num(c[5]), p.getHeightM(), 1e-9, "height " + dn);
            rows++;
        }
        assertEquals(ref.getPipes().size(), rows);
    }

    private static double num(String s) {
        return Double.parseDouble(s.trim().replace(",", "."));
    }

    @Test
    void penaltyAndRestrictionRules() {
        assertEquals(100_000_000 + 500_000 * 12.0, ref.unconnectedPenalty(12.0));
        assertEquals(1.60, ref.restriction("road").getK());
        assertEquals(RestrictionRule.Bounds.AROUND_CROSSING, ref.restriction("gas_pipeline").getBounds());
        assertEquals(RestrictionRule.Kind.FORBIDDEN, ref.restriction("metro").getKind());
        assertEquals(1.0, ref.restriction("metro").getMinDistanceM());
        // table 2 of the appendix (edition 18.09.2026) includes the railway
        assertEquals(1.0, ref.restriction("railway").getMinDistanceM());
        assertEquals(RestrictionRule.Kind.FORBIDDEN, ref.restriction("railway").getKind());
        assertEquals(90.0, ref.getMaxTurnDeg());
        org.junit.jupiter.api.Assertions.assertTrue(ref.isAllowedTurn(0.5) && ref.isAllowedTurn(45.8)
                && ref.isAllowedTurn(89.1) && ref.isAllowedTurn(30) && !ref.isAllowedTurn(135));
    }
}
