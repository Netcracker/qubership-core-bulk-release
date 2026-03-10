package org.qubership.cloud.actions.renovate.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Severity {
    Critical("Critical"),
    High("High"),
    Medium("Medium"),
    Low("Low"),
    Unknown("Unknown"),
    NoIssues("Scanned - No Issues");

    private final String value;

    Severity(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Severity fromValue(String value) {
        for (Severity s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown severity: " + value);
    }

}
