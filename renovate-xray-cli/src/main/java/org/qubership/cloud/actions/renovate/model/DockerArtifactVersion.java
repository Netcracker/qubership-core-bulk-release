package org.qubership.cloud.actions.renovate.model;

import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;

public class DockerArtifactVersion implements ArtifactVersionData<RenovateReportDockerfileDep> {
    String packageName;
    String version;
    String artifactPath;
    RenovateReportDockerfileDep renovateData;

    public DockerArtifactVersion(String packageName, String version, RenovateReportDockerfileDep renovateData) {
        this.packageName = packageName;
        this.version = version;
        this.renovateData = renovateData;
        //defaul/pd.saas.docker/alpine/openjdk21/21.0.8.9.05/manifest.json
        this.artifactPath = getArtifactPath(version);
    }

    @Override
    public ArtifactType getType() {
        return ArtifactType.docker;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public List<String> getNewVersions() {
        return Optional.ofNullable(renovateData.getUpdates()).orElse(List.of()).stream()
                .map(RenovateReportDockerfileDepUpdate::getNewVersion)
                .toList();
    }

    @Override
    public String getArtifactPath() {
        return artifactPath;
    }

    @Override
    public String getArtifactPath(String version) {
        return MessageFormat.format("{0}/{1}/", this.packageName, version);
    }

    @Override
    public RenovateReportDockerfileDep getRenovateData() {
        return renovateData;
    }
}
