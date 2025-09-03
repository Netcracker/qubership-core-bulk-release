package org.qubership.cloud.actions.go.model;

import lombok.Builder;
import lombok.Data;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

@Data
public class Config {
    final String baseDir;
    final String goProxyDir;
    final GitConfig gitConfig;
    // all repositories
    final Set<RepositoryConfig> repositories;
    // particular repository(ies) to start release from (the rest of the tree will be calculated automatically)
    Set<RepositoryConfig> repositoriesToReleaseFrom;
    Collection<String> gavs;
    String summaryFile;
    String resultOutputFile;
    String dependencyGraphFile;
    String gavsResultFile;
    boolean skipTests;
    boolean dryRun;
    boolean runSequentially;

    @Builder(builderMethodName = "")
    private Config(String baseDir,
                   String goProxyDir,
                   GitConfig gitConfig,
                   Set<RepositoryConfig> repositories,
                   Set<RepositoryConfig> repositoriesToReleaseFrom,
                   Collection<String> gavs,
                   String summaryFile,
                   String resultOutputFile,
                   String dependencyGraphFile,
                   String gavsResultFile,
                   boolean skipTests,
                   boolean dryRun,
                   boolean runSequentially) {
        this.baseDir = baseDir;
        this.goProxyDir = goProxyDir;
        this.gitConfig = gitConfig;
        this.gavs = gavs;
        this.repositories = repositories;
        this.repositoriesToReleaseFrom = repositoriesToReleaseFrom;
        this.summaryFile = summaryFile;
        this.resultOutputFile = resultOutputFile;
        this.dependencyGraphFile = dependencyGraphFile;
        this.gavsResultFile = gavsResultFile;
        this.skipTests = skipTests;
        this.dryRun = dryRun;
        this.runSequentially = runSequentially;
    }

    public static ConfigBuilder builder(String baseDir,
                                        String goProxyDir,
                                        GitConfig gitConfig,
                                        Set<RepositoryConfig> repositories) {
        return new ConfigBuilder()
                .baseDir(baseDir)
                .goProxyDir(goProxyDir)
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
