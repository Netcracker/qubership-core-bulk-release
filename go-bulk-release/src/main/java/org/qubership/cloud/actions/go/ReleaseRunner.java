package org.qubership.cloud.actions.go;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.*;
import org.qubership.cloud.actions.go.model.graph.DependencyGraph;

import org.qubership.cloud.actions.go.proxy.GoProxyService;
import org.qubership.cloud.actions.go.util.CommandRunner;
import org.qubership.cloud.actions.go.util.ParallelExecutor;

import java.util.*;
import java.util.function.Predicate;

@Slf4j
public class ReleaseRunner {
    static YAMLMapper yamlMapper = new YAMLMapper();

    private final Config config;
    private final GitService gitService;
    private final GoProxyService goProxyService;
    private final RepositoryService repositoryService;

    public ReleaseRunner(Config config) {
        this.config = config;
        this.gitService = new GitService(config.getGitConfig());
        this.goProxyService = new GoProxyService(config);
        this.repositoryService = new RepositoryService(config);
    }

    @SneakyThrows
    public Result release() {
        log.info("Start release. Config: {}", yamlMapper.writeValueAsString(config));

        goProxyService.enableGoProxy();

        gitService.setupGit();

        DependencyGraph dependencyGraph = repositoryService.buildDependencyGraph(config.getBaseDir(),
                config.getRepositories(), config.getRepositoriesToReleaseFrom());

        List<RepositoryRelease> preparedReleases = prepareReleases(config, dependencyGraph);

        if (!config.isDryRun()) {
            performRelease(config, dependencyGraph, preparedReleases);
        }

        return getResult(config, dependencyGraph, preparedReleases);
    }

    private List<RepositoryRelease> prepareReleases(Config config, DependencyGraph dependencyGraph) {
        Set<GAV> gavList = new HashSet<>();
        return dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey() + 1;
            List<RepositoryInfo> reposInfoList = entry.getValue();
            log.info("\n\nRunning 'PREPARE RELEASE' - processing level {}/{}, {} repositories:\n{}\n\n", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream().map(RepositoryConfig::getUrl).toList()));

            int threads = getThreads();

            List<RepositoryRelease> releases = ParallelExecutor.forEachIn(reposInfoList)
                    .inParallelOn(threads)
                    .execute((repo) -> prepareRelease(config, repo, gavList));

            saveReleaseGAV(releases, gavList);

            return releases.stream();
        }).toList();
    }

    private void saveReleaseGAV(List<RepositoryRelease> releases, Set<GAV> gavList) {
        releases.forEach(release -> gavList.addAll(release.getGavs()));
    }

    private void performRelease(Config config, DependencyGraph dependencyGraph, List<RepositoryRelease> allReleases) {
        dependencyGraph.forEach((level, repos) -> {
            int threads = config.isRunSequentially() ? 1 : repos.size();
            log.info("\n\nRunning 'PERFORM RELEASE' - processing level {}/{}, {} repositories:\n{}\n\n", level + 1, dependencyGraph.size(), threads,
                    String.join("\n", repos.stream().map(RepositoryConfig::getUrl).toList()));

            ParallelExecutor.forEachIn(allReleases)
                    .filter(new CurrentLevelRepoFilter(repos))
                    .inParallelOn(threads)
                    .execute(this::performRelease);
        });
    }

    private Result getResult(Config config, DependencyGraph dependencyGraph, List<RepositoryRelease> allReleases) {
        Result result = new Result();
        result.setDependencyGraph(dependencyGraph);
        String dot = dependencyGraph.generateDotFile();
        result.setDependenciesDot(dot);
        result.setDryRun(config.isDryRun());
        result.setReleases(allReleases);
        return result;
    }

    private RepositoryRelease prepareRelease(Config config, RepositoryInfo repository, Collection<GAV> dependencies) {
        log.info("\n\n=== PREPARE RELEASE {} ===\n\n", repository.getUrl());

        updateDependencies(repository, dependencies);

        ReleaseVersion releaseVersion = resolveReleaseVersion(config, repository);
        log.info("Release version: {}", releaseVersion);

        if (releaseVersion.isMajorUpdate()){
            updateMajorVersion(repository, releaseVersion);
        }

        runGoBuild(repository);
        if (!config.isSkipTests()) {
            runGoTest(repository);
        }

        createTag(repository, releaseVersion);

        publishToGoProxy(config, repository, releaseVersion);

        RepositoryRelease release = RepositoryRelease.from(repository, releaseVersion);
        log.info("\n\n=== PRE-RELEASE DONE FOR {} ===\n\n", repository.getUrl());
        return release;
    }

    private void updateDependencies(RepositoryInfo repositoryInfo, Collection<GAV> dependencies) {
        log.info("=== UPDATE DEPENDENCIES FOR {} ===", repositoryInfo.getUrl());

        repositoryInfo.updateDepVersions(dependencies);

        gitService.commitModified(repositoryInfo.getRepositoryDirFile(), "chore: updating dependencies before release");
    }

    private void updateMajorVersion(RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("=== UPDATE MAJOR VERSION FOR {} ===", repository.getUrl());

        String newMajorVersion = "v" + releaseVersion.getNewMajorVersion();
        CommandRunner.runCommand(repository.getRepositoryDirFile(), "gomajor" , "path", "-version", newMajorVersion);
    }

    private ReleaseVersion resolveReleaseVersion(Config config, RepositoryInfo repository) {
        log.info("=== CALCULATE RELEASE VERSION {} ===", repository.getUrl());
        VersionIncrementType versionIncrementType = Optional.ofNullable(repository.getVersionIncrementType()).orElse(config.getVersionIncrementType());
        return repository.calculateReleaseVersion(versionIncrementType);
    }

    private void runGoBuild(RepositoryInfo repository) {
        log.info("=== GO BUILD {} ===", repository.getUrl());
        CommandRunner.runCommand(repository.getRepositoryDirFile(), "go", "build", "./...");
    }

    private void runGoTest(RepositoryInfo repository) {
        log.info("=== GO TEST {} ===", repository.getUrl());
        CommandRunner.runCommand(repository.getRepositoryDirFile(), "go", "test", "./...", "-v");
    }

    private void createTag(RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("=== CREATE TAG {} ===", repository.getUrl());
        gitService.createTag(repository, releaseVersion.getNewVersion().getValue());
    }

    private void publishToGoProxy(Config config, RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("=== PUBLISH TO GO PROXY {} ===", repository.getUrl());

        goProxyService.publishToLocalGoProxy(repository, releaseVersion.getNewVersion().getValue(), config.getGoProxyDir());
    }

    RepositoryRelease performRelease(RepositoryRelease release) {
        log.info("\n\n=== PERFORM RELEASE {} ===\n\n", release.getRepository().getUrl());
        RepositoryInfo repository = release.getRepository();

        pushChanges(repository, release);

        deployRelease(repository);
        return release;
    }

    void pushChanges(RepositoryInfo repository, RepositoryRelease release) {
        log.info("=== GIT PUSH {} ===", repository.getUrl());

        gitService.pushChanges(repository.getRepositoryDirFile(), release.getReleaseVersion());
        release.setPushedToGit(true);
    }

    void deployRelease(RepositoryInfo repository) {
        log.info("=== DEPLOY RELEASE {} ===", repository.getUrl());

        CommandRunner.runCommand(repository.getRepositoryDirFile(), "goreleaser", "-f", "/tmp/goreleaser.yaml", "release");
    }

    private int getThreads() {
//      TODO VLLA extract thread number to configuration file?
//      TODO VLLA it fails if we try to 'go test' in parallel -> sequential build for now
//      return config.isRunSequentially() ? 1 : 4;
        return 1;
    }

    private record CurrentLevelRepoFilter(List<RepositoryInfo> repos) implements Predicate<RepositoryRelease> {
        @Override
        public boolean test(RepositoryRelease release) {
            return repos.stream().map(RepositoryConfig::getUrl)
                    .anyMatch(repo -> Objects.equals(repo, release.getRepository().getUrl()));
        }
    }
}
