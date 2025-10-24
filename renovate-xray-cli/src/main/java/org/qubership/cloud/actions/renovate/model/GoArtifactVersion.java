package org.qubership.cloud.actions.renovate.model;

import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;

public class GoArtifactVersion implements ArtifactVersionData<RenovateReportGomodDep> {
    String packageName;
    String version;
    String artifactPath;
    RenovateReportGomodDep renovateData;

    public GoArtifactVersion(String packageName, String version, RenovateReportGomodDep renovateData) {
        this.packageName = packageName;
        this.version = version;
        this.renovateData = renovateData;
        //        "defaul/proxy_golang_org.proxy_techical-cache/golang.org/toolchain/@v/v0.0.1-go1.23.3.linux-amd64.zip",
        //        "defaul/proxy_golang_org.proxy_techical-cache/github.com/miekg/dns/@v/v1.1.59.zip",
        //        "defaul/proxy_golang_org.proxy_techical-cache/github.com/bradleyjkemp/cupaloy/v2/@v/v2.7.0.zip"
        // todo check if below is valid for go artifact
        this.artifactPath = getArtifactPath(version);
    }

    @Override
    public ArtifactType getType() {
        return ArtifactType.go;
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
                .map(RenovateReportGomodDepUpdate::getNewVersion)
                .toList();
    }

    @Override
    public String getArtifactPath() {
        return artifactPath;
    }

    @Override
    public String getArtifactPath(String version) {
        return this.artifactPath = MessageFormat.format("{0}/@v/{1}.zip", this.packageName, version);
    }

    @Override
    public RenovateReportGomodDep getRenovateData() {
        return renovateData;
    }
}
