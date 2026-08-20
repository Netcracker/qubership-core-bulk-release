package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.List;

@Data
public class RepositoryRelease {
    RepositoryInfo repository;
    VersionTag versionTag;
    List<GAV> gavs;
    List<GAV> devGavs;
    String javaVersion;
    boolean pushedToGit;
    boolean deployed;
    Exception exception;
}
