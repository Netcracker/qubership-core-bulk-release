package org.qubership.cloud.actions.go.model.repository;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.GoGAV;
import org.qubership.cloud.actions.go.model.ReleaseVersion;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Data
public class RepositoryRelease {
    RepositoryInfo repository;
    String releaseVersion;
    String tag;
    List<GoGAV> gavs;
    String javaVersion;
    boolean pushedToGit;
    boolean deployed;
    Exception exception;

    public static RepositoryRelease from(RepositoryInfo repository, ReleaseVersion releaseVersion) {
        String oldReleaseVersion = releaseVersion.getCurrentVersion().getValue();
        String newReleaseVersion = releaseVersion.getNewVersion().getValue();
        RepositoryRelease release = new RepositoryRelease();
        release.setRepository(repository);
        release.setReleaseVersion(newReleaseVersion);
        release.setTag(newReleaseVersion);
        List<GoGAV> gavs = new ArrayList<>();
        repository.getModules().forEach(gav -> {
            //todo vlla check is it major update - refactor
            GoGAV goGAV;
            if (gav.getMajorVersionFromArtifactId() == releaseVersion.getNewMajorVersion())
            {
                goGAV = new GoGAV(gav.getArtifactId(), oldReleaseVersion, newReleaseVersion);
            }
            else {
                goGAV = new GoGAV(gav.getArtifactIdWithoutVersion() + "/v" + releaseVersion.getNewMajorVersion(), oldReleaseVersion, newReleaseVersion);
            }
            gavs.add(goGAV);
        });
        release.setGavs(gavs);
        return release;
    }
}
