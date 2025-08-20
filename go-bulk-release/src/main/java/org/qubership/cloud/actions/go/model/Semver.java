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

    public Semver getNext(VersionIncrementType versionIncrementType) {
        int newMajor;
        int newMinor;
        int newPatch;
        switch (versionIncrementType) {
            case MAJOR -> {
                newMajor = major + 1;
                newMinor = 0;
                newPatch = 0;
            }
            case MINOR -> {
                newMajor = major;
                newMinor = minor + 1;
                newPatch = 0;
            }
            case PATCH -> {
                newMajor = major;
                newMinor = minor;
                newPatch = patch + 1;
            }
            default -> throw new IllegalArgumentException("Unsupported version increment type: " + versionIncrementType);
        }
        return new Semver(String.format("v%d.%d.%d", newMajor, newMinor, newPatch));
    }
}
