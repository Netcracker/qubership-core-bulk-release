package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.OutputStream;
import java.util.*;

@Data
@ToString(exclude = "mavenPassword")
public class Config {
    final String baseDir;
    final GitConfig gitConfig;
    MavenConfig mavenConfig;
    @JsonIgnore
    final CredentialsProvider credentialsProvider;
    // all repositories
    final Set<RepositoryConfig> repositories;
    // particular repository(ies) to start release from (the rest of the tree will be calculated automatically)
    Set<RepositoryConfig> repositoriesToReleaseFrom = new LinkedHashSet<>();
    Collection<String> gavs;
    VersionIncrementType versionIncrementType = VersionIncrementType.PATCH;
    Map<String, String> javaVersionToJavaHomeEnv;
    boolean skipTests;
    boolean dryRun;
    boolean runSequentially;
    @JsonIgnore
    OutputStream summaryOutputStream;

    @Builder(builderMethodName = "")
    private Config(String baseDir,
                   GitConfig gitConfig,
                   CredentialsProvider credentialsProvider,
                   Set<RepositoryConfig> repositories,
                   Set<RepositoryConfig> repositoriesToReleaseFrom,
                   Collection<String> gavs,
                   VersionIncrementType versionIncrementType,
                   Map<String, String> javaVersionToJavaHomeEnv,
                   MavenConfig mavenConfig,
                   boolean skipTests,
                   boolean dryRun,
                   boolean runSequentially,
                   OutputStream summaryOutputStream) {
        this.baseDir = baseDir;
        this.gitConfig = gitConfig;
        this.credentialsProvider = credentialsProvider;
        this.gavs = gavs;
        this.javaVersionToJavaHomeEnv = javaVersionToJavaHomeEnv;
        this.mavenConfig = mavenConfig;
        this.repositories = repositories;
        this.repositoriesToReleaseFrom = repositoriesToReleaseFrom;
        this.skipTests = skipTests;
        this.dryRun = dryRun;
        this.runSequentially = runSequentially;
        this.versionIncrementType = versionIncrementType;
        this.summaryOutputStream = summaryOutputStream;
    }

    public static ConfigBuilder builder(String baseDir,
                                        GitConfig gitConfig,
                                        MavenConfig mavenConfig,
                                        Set<RepositoryConfig> repositories) {
        CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(gitConfig.getUsername(), gitConfig.getPassword());
        CredentialsProvider.setDefault(credentialsProvider);
        return new ConfigBuilder()
                .baseDir(baseDir)
                .gitConfig(gitConfig)
                .credentialsProvider(credentialsProvider)
                .mavenConfig(mavenConfig)
                .repositories(repositories)
                // set by default NOP OutputStream
                .summaryOutputStream(new OutputStream() {
                    @Override
                    public void write(int b) {
                    }
                });
    }

    public Collection<String> getGavs() {
        return gavs == null ? Collections.emptyList() : gavs;
    }

    public Map<String, String> getJavaVersionToJavaHomeEnv() {
        return javaVersionToJavaHomeEnv == null ? Collections.emptyMap() : javaVersionToJavaHomeEnv;
    }

    public Set<RepositoryConfig> getRepositoriesToReleaseFrom() {
        return repositoriesToReleaseFrom == null ? Collections.emptySet() : repositoriesToReleaseFrom;
    }

}
