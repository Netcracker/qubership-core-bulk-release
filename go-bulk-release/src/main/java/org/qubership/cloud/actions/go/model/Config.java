package org.qubership.cloud.actions.go.model;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@ToString(exclude = "mavenPassword")
public class Config {
    final String baseDir;
    final GitConfig gitConfig;
    // all repositories
    final Set<RepositoryConfig> repositories;
    // particular repository(ies) to start release from (the rest of the tree will be calculated automatically)
    Set<RepositoryConfig> repositoriesToReleaseFrom = new LinkedHashSet<>();
    Collection<String> gavs;
    VersionIncrementType versionIncrementType;
    boolean skipTests;
    boolean dryRun;
    boolean runSequentially;

    @Builder(builderMethodName = "")
    private Config(String baseDir,
                   GitConfig gitConfig,
                   Set<RepositoryConfig> repositories,
                   Set<RepositoryConfig> repositoriesToReleaseFrom,
                   Collection<String> gavs,
                   VersionIncrementType versionIncrementType,
                   boolean skipTests,
                   boolean dryRun,
                   boolean runSequentially) {
        this.baseDir = baseDir;
        this.gitConfig = gitConfig;
        this.gavs = gavs;
        this.repositories = repositories;
        this.repositoriesToReleaseFrom = repositoriesToReleaseFrom;
        this.skipTests = skipTests;
        this.dryRun = dryRun;
        this.runSequentially = runSequentially;
        this.versionIncrementType = versionIncrementType;
    }

    public static ConfigBuilder builder(String baseDir,
                                        GitConfig gitConfig,
                                        Set<RepositoryConfig> repositories) {
        return new ConfigBuilder()
                .baseDir(baseDir)
                .gitConfig(gitConfig)
                .repositories(repositories);
    }

    public Collection<String> getGavs() {
        return gavs == null ? Collections.emptyList() : gavs;
    }

    public Set<RepositoryConfig> getRepositoriesToReleaseFrom() {
        return repositoriesToReleaseFrom == null ? Collections.emptySet() : repositoriesToReleaseFrom;
    }

}
