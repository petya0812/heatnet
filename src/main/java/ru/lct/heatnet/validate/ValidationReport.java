package ru.lct.heatnet.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Result of the output check: violations of mandatory rules (ERROR) and remarks (WARNING). */
public final class ValidationReport {

    public enum Severity { ERROR, WARNING }

    public static final class Violation {
        private final Severity severity;
        private final String rule;
        private final String variantId;
        private final String featureId;
        private final String message;

        Violation(Severity severity, String rule, String variantId, String featureId, String message) {
            this.severity = severity;
            this.rule = rule;
            this.variantId = variantId;
            this.featureId = featureId;
            this.message = message;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getRule() {
            return rule;
        }

        public String getVariantId() {
            return variantId;
        }

        public String getFeatureId() {
            return featureId;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return severity + " " + rule + " v" + variantId + (featureId != null ? " [" + featureId + "]" : "")
                    + ": " + message;
        }
    }

    private final List<Violation> violations = new ArrayList<>();
    private final Map<String, Integer> checked = new TreeMap<>();

    void error(String rule, String variantId, String featureId, String message) {
        violations.add(new Violation(Severity.ERROR, rule, variantId, featureId, message));
    }

    void warning(String rule, String variantId, String featureId, String message) {
        violations.add(new Violation(Severity.WARNING, rule, variantId, featureId, message));
    }

    /** Counts how many objects a rule was checked on (so that "0 errors" is not "0 checks"). */
    void checked(String rule, int n) {
        checked.merge(rule, n, Integer::sum);
    }

    public List<Violation> getViolations() {
        return Collections.unmodifiableList(violations);
    }

    public Map<String, Integer> getChecked() {
        return Collections.unmodifiableMap(checked);
    }

    public long errorCount() {
        return violations.stream().filter(v -> v.severity == Severity.ERROR).count();
    }

    public long warningCount() {
        return violations.stream().filter(v -> v.severity == Severity.WARNING).count();
    }

    public List<Violation> errors() {
        List<Violation> out = new ArrayList<>();
        for (Violation v : violations) {
            if (v.severity == Severity.ERROR) {
                out.add(v);
            }
        }
        return out;
    }
}
