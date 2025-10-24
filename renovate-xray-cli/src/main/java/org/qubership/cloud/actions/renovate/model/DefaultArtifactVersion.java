package org.qubership.cloud.actions.renovate.model;

public class DefaultArtifactVersion implements ArtifactVersion {
    ArtifactType type;
    String packageName;
    String version;

    public DefaultArtifactVersion(ArtifactType type, String packageName, String version) {
        this.type = type;
        this.packageName = packageName;
        this.version = version;
    }

    @Override
    public ArtifactType getType() {
        return type;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getVersion() {
        return version;
    }

}