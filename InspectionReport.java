package com.linuxops.inspection;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Structured result for one server inspection. */
public final class InspectionReport {
    private final String hostId;
    private final String hostName;
    private final Instant inspectedAt;
    private final Map<String, String> metrics;
    private final Map<String, Finding> findings;
    private final String collectionError;

    public InspectionReport(String hostId, String hostName, Instant inspectedAt,
                            Map<String, String> metrics, Map<String, Finding> findings,
                            String collectionError) {
        this.hostId = Objects.requireNonNull(hostId);
        this.hostName = Objects.requireNonNullElse(hostName, hostId);
        this.inspectedAt = Objects.requireNonNull(inspectedAt);
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        this.findings = Collections.unmodifiableMap(new LinkedHashMap<>(findings));
        this.collectionError = collectionError;
    }

    public String hostId() { return hostId; }
    public String hostName() { return hostName; }
    public Instant inspectedAt() { return inspectedAt; }
    public Map<String, String> metrics() { return metrics; }
    public Map<String, Finding> findings() { return findings; }
    public String collectionError() { return collectionError; }
    public long count(Severity severity) { return findings.values().stream().filter(f -> f.severity() == severity).count(); }
    public Severity overallSeverity() {
        if (collectionError != null && !collectionError.isBlank()) return Severity.CRITICAL;
        if (count(Severity.CRITICAL) > 0) return Severity.CRITICAL;
        if (count(Severity.WARNING) > 0) return Severity.WARNING;
        return Severity.NORMAL;
    }

    public record Finding(Severity severity, String message, String observedValue) {
        public Finding {
            Objects.requireNonNull(severity);
            Objects.requireNonNull(message);
            observedValue = Objects.requireNonNullElse(observedValue, "");
        }
    }
}
