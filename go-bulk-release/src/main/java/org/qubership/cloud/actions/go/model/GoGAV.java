package org.qubership.cloud.actions.go.model;

import lombok.Getter;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GoGAV extends GAV {
    private static final Pattern VERSION_SUFFIX = Pattern.compile("(/v(\\d+))$");

    String oldVersion;
    @Getter
    String artifactIdWithoutVersion;
    @Getter
    int majorVersionFromArtifactId;

    public GoGAV(String artifactId, String version) {
        this(artifactId, null, version);
    }

    public GoGAV(String artifactId, String oldVersion, String version) {
        super("GO", artifactId, version);
        this.oldVersion = oldVersion;

        Matcher m = VERSION_SUFFIX.matcher(artifactId);
        if (m.find()) {
            majorVersionFromArtifactId = Integer.parseInt(m.group(2));
            artifactIdWithoutVersion = artifactId.substring(0, m.start(1));
        } else {
            majorVersionFromArtifactId = 1;
            artifactIdWithoutVersion = artifactId;
        }
    }

    @Override
    public boolean isSameArtifact(GAV another) {
        if (another instanceof GoGAV goGAV) {
            return Objects.equals(artifactIdWithoutVersion, goGAV.artifactIdWithoutVersion);
        }
        else {
            return super.isSameArtifact(another);
        }
    }

    @Override
    public String toString() {
        return String.format("%s:%s", artifactId, version);
    }

    public String getUpdatedVersionStr() {
        return String.format("%s:%s -> %s", artifactId, oldVersion, version);
    }
}
