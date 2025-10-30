package org.qubership.cloud.actions.renovate.model;

import lombok.Getter;
import org.qubership.cloud.actions.maven.model.GAV;

import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;

public class MavenArtifactVersion implements ArtifactVersionData<RenovateReportMavenDep> {
    @Getter
    GAV gav;
    String packageName;
    String version;
    String artifactPath;
    RenovateReportMavenDep renovateData;

    public MavenArtifactVersion(GAV gav, RenovateReportMavenDep renovateData) {
        this.gav = gav;
        this.packageName = String.format("%s:%s", gav.getGroupId(), gav.getArtifactId());
        this.version = gav.getVersion();
        this.renovateData = renovateData;
        this.artifactPath = getArtifactPath(gav.getVersion());
    }

    @Override
    public ArtifactType getType() {
        return ArtifactType.maven;
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
                .map(RenovateReportMavenDepUpdate::getNewVersion)
                .toList();
    }

    @Override
    public String getArtifactPath() {
        return artifactPath;
    }

    @Override
    public String getArtifactPath(String version) {
        return MessageFormat.format("{0}/{1}/{2}/{1}-{2}.jar",
                gav.getGroupId().replace(".", "/"), gav.getArtifactId(), version);
    }

    @Override
    public RenovateReportMavenDep getRenovateData() {
        return renovateData;
    }
}
