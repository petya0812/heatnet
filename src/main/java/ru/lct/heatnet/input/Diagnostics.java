package ru.lct.heatnet.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collected data issues. Keeps counts per code and a bounded number of examples per code,
 * so that a 3 GB input with a systematic defect does not blow up memory.
 */
public final class Diagnostics {

    public enum Severity { ERROR, WARNING, INFO }

    public static final class Issue {
        private final Severity severity;
        private final String code;
        private final String featureId;
        private final String message;

        public Issue(Severity severity, String code, String featureId, String message) {
            this.severity = severity;
            this.code = code;
            this.featureId = featureId;
            this.message = message;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getCode() {
            return code;
        }

        public String getFeatureId() {
            return featureId;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return severity + " " + code + (featureId != null ? " [" + featureId + "]" : "") + ": " + message;
        }
    }

    private final int examplesPerCode;
    private final Map<String, Long> counts = new TreeMap<>();
    private final Map<String, Severity> severities = new TreeMap<>();
    private final Map<String, List<Issue>> examples = new LinkedHashMap<>();

    public Diagnostics() {
        this(50);
    }

    public Diagnostics(int examplesPerCode) {
        this.examplesPerCode = examplesPerCode;
    }

    public synchronized void add(Severity severity, String code, String featureId, String message) {
        counts.merge(code, 1L, Long::sum);
        severities.put(code, severity);
        List<Issue> list = examples.computeIfAbsent(code, k -> new ArrayList<>());
        if (list.size() < examplesPerCode) {
            list.add(new Issue(severity, code, featureId, message));
        }
    }

    public void error(String code, String featureId, String message) {
        add(Severity.ERROR, code, featureId, message);
    }

    public void warning(String code, String featureId, String message) {
        add(Severity.WARNING, code, featureId, message);
    }

    public void info(String code, String featureId, String message) {
        add(Severity.INFO, code, featureId, message);
    }

    public synchronized Map<String, Long> getCounts() {
        return Collections.unmodifiableMap(new TreeMap<>(counts));
    }

    public synchronized Severity severityOf(String code) {
        return severities.get(code);
    }

    public synchronized List<Issue> getIssues() {
        List<Issue> all = new ArrayList<>();
        for (List<Issue> l : examples.values()) {
            all.addAll(l);
        }
        return all;
    }

    public synchronized long count(Severity severity) {
        long n = 0;
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (severities.get(e.getKey()) == severity) {
                n += e.getValue();
            }
        }
        return n;
    }

    public synchronized boolean hasCode(String code) {
        return counts.containsKey(code);
    }
}
