package org.qubership.cloud.actions.go.model;

import lombok.Getter;
import lombok.ToString;

@ToString
@Getter
public class ReleaseVersion {
    private final Semver currentVersion;
    private final Semver newVersion;

    public ReleaseVersion(String currentVersion, String newVersion) {
        this.currentVersion = new Semver(currentVersion);
        this.newVersion = new Semver(newVersion);
    }

    public boolean isMajorUpdate() {
        return currentVersion.getMajor() != newVersion.getMajor();
    }

    public int getNewMajorVersion() {
        return newVersion.getMajor();
    }
}
