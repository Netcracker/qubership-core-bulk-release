package org.qubership.cloud.actions.go.model;

import lombok.Getter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
public class Semver {
    private static final Pattern SEMVER_PATTERN = Pattern.compile("v(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)");

    private final String value;
    private final int major;
    private final int minor;
    private final int patch;

    public Semver(String value) {
        this.value = value;

        Matcher matcher = SEMVER_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Non-semver version: %s. Must match pattern: '%s'", value, SEMVER_PATTERN.pattern()));
        }
        major = Integer.parseInt(matcher.group("major"));
        minor = Integer.parseInt(matcher.group("minor"));
        patch = Integer.parseInt(matcher.group("patch"));
    }

    @Override
    public String toString() {
        return value;
    }
}
