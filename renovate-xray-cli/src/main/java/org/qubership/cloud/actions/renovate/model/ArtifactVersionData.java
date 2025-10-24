package org.qubership.cloud.actions.renovate.model;

import java.util.List;

public interface ArtifactVersionData<T> extends ArtifactVersion {

    List<String> getNewVersions();

    String getArtifactPath(String version);

    String getArtifactPath();

    T getRenovateData();

}
