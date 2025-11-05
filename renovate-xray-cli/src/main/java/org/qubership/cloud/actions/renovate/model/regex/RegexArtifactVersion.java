package org.qubership.cloud.actions.renovate.model.regex;

import lombok.Data;
import lombok.Getter;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.renovate.model.ArtifactType;
import org.qubership.cloud.actions.renovate.model.ArtifactVersionData;
import org.qubership.cloud.actions.renovate.model.maven.RenovateReportMavenDep;
import org.qubership.cloud.actions.renovate.model.maven.RenovateReportMavenDepUpdate;

import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;

@Data
public class RegexArtifactVersion implements ArtifactVersionData<RenovateReportRegexDep> {
    @Getter
    String packageName;
    String version;
    String artifactPath;
    RenovateReportRegexDep renovateData;

    public RegexArtifactVersion(String packageName, String version, RenovateReportRegexDep renovateData) {
        this.packageName = packageName;
        this.version = version;
        this.renovateData = renovateData;
        this.artifactPath = getArtifactPath(version);
    }

    @Override
    public ArtifactType getType() {
        return ArtifactType.regex;
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
                .map(RenovateReportRegexDepUpdate::getNewVersion)
                .toList();
    }

    @Override
    public String getArtifactPath() {
        return artifactPath;
    }

    @Override
    public String getArtifactPath(String version) {
        //                "packageName": "nss_wrapper",
        //        "default/dl_cdn_alpinelinux_org_alpine.apk.proxy-cache/edge/main/aarch64/zstd-libs-1.5.7-r2.apk"
        //        "default/dl_cdn_alpinelinux_org_alpine.apk.proxy-cache/v3.22/community/aarch64/nss_wrapper-1.1.12-r1.apk"
        return MessageFormat.format("{0}-{1}.apk", this.packageName, version);
    }

    @Override
    public RenovateReportRegexDep getRenovateData() {
        return renovateData;
    }
}
