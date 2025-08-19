package org.qubership.cloud.actions.go.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RepositoryRelease {
    RepositoryInfo repository;
    String releaseVersion;
    String tag;
    List<GAV> gavs;
    String javaVersion;
    boolean pushedToGit;
    boolean deployed;
    Exception exception;

    public static RepositoryRelease from(RepositoryInfo repository, String releaseVersion) {
        RepositoryRelease release = new RepositoryRelease();
        release.setRepository(repository);
        release.setReleaseVersion(releaseVersion);
        release.setTag(releaseVersion);
        List<GAV> gavs = new ArrayList<>();
        repository.getModules().forEach(gav -> gavs.add(new GoGAV(gav.getArtifactId(), releaseVersion)));
        release.setGavs(gavs);
        return release;
    }
}
