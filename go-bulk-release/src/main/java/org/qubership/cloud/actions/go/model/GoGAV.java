package org.qubership.cloud.actions.go.model;

import lombok.Getter;

import java.util.regex.Pattern;

public class GoGAV extends GAV {
    private static final Pattern VERSION_SUFFIX = Pattern.compile("/v\\d+$");

    @Getter
    String artifactIdWithoutVersion;

    public GoGAV(String artifactId, String version) {
        super("GO", artifactId, version);
        artifactIdWithoutVersion = stripVersion(artifactId);
    }

    public String stripVersion(String s) {
        if (s == null) {
            return null;
        }
        return VERSION_SUFFIX.matcher(s).replaceFirst("");
    }

    @Override
    public String toString() {
        return String.format("%s:%s", artifactId, version);
    }
}
