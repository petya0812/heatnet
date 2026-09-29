package ru.lct.heatnet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.reference.RuleOptions;
import ru.lct.heatnet.validate.OutputValidator;
import ru.lct.heatnet.validate.ValidationReport;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Assumptions about data missing in the input (docs/requirements.md, class of data assumptions) in both
 * positions: the planner and the validator with the same options agree.
 */
class RuleOptionsTest {

    private static ValidationReport validate(TestSupport.Run r, RuleOptions rules) throws Exception {
        try (InputStream in = Files.newInputStream(r.output)) {
            return new OutputValidator(TestSupport.REF, rules).validate(r.input, in);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"contest-lct.geojson"})
    void existingNetworkLoadedToAShareOfCapacity(String dataset) throws Exception {
        PlanParams p = new PlanParams();
        p.rules.existingFlow = RuleOptions.ExistingFlow.CAPACITY_SHARE;
        p.rules.existingFlowShare = 0.9;
        TestSupport.Run r = TestSupport.run(dataset, p, "-share");
        assertEquals(Collections.emptyList(), validate(r, p.rules).errors());
    }
}
