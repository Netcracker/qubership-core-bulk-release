package org.qubership.cloud.actions.renovate.model;

public interface ArtifactVersion {

    ArtifactType getType();

    String getPackageName();

    String getVersion();

}
