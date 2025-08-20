package org.qubership.cloud.actions.go.model;

import lombok.Getter;

@Getter
public class ReleaseVersion {
    private final Semver currentVersion;
    private final Semver newVersion;
    private final VersionIncrementType versionIncrementType;

    public ReleaseVersion(String currentVersion, VersionIncrementType versionIncrementType) {
        this.currentVersion = new Semver(currentVersion);
        this.newVersion = this.currentVersion.getNext(versionIncrementType);
        this.versionIncrementType = versionIncrementType;
    }

    public boolean isMajorUpdate() {
        return versionIncrementType == VersionIncrementType.MAJOR;
    }

    public int getNewMajorVersion() {
        return newVersion.getMajor();
    }
}
