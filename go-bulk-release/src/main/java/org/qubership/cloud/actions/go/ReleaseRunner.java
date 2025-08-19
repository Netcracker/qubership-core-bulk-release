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
import java.util.stream.Collectors;

@Slf4j
public class ReleaseRunner {
    static YAMLMapper yamlMapper = new YAMLMapper();

    private final GitService gitService = new GitService();
    private final GoProxyService goProxyService = new GoProxyService();
    private final RepositoryService repositoryService = new RepositoryService();

    @SneakyThrows
    public Result release(Config config) {
        log.info("Start release. Config: {}", yamlMapper.writeValueAsString(config));

        goProxyService.enableGoProxy(config);

        gitService.setupGit(config.getGitConfig());

        DependencyGraph dependencyGraph = repositoryService.buildDependencyGraph(config.getBaseDir(), config.getGitConfig(),
                config.getRepositories(), config.getRepositoriesToReleaseFrom());

        List<RepositoryRelease> allReleases = prepareReleases(config, dependencyGraph);

        if (!config.isDryRun()) {
            performRelease(config, dependencyGraph, allReleases);
        }

        return getResult(config, dependencyGraph, allReleases);
    }

    private List<RepositoryRelease> prepareReleases(Config config, DependencyGraph dependencyGraph) {
        Set<GAV> gavList = new HashSet<>();
        return dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey() + 1;
            List<RepositoryInfo> reposInfoList = entry.getValue();
            log.info("\n\nRunning 'PREPARE RELEASE' - processing level {}/{}, {} repositories:\n{}\n\n", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream().map(RepositoryConfig::getUrl).toList()));

//            TODO VLLA extract thread number to configuration file?
//            TODO VLLA it fails if we try to 'go test' in parallel -> sequential build for now
//            int threads = config.isRunSequentially() ? 1 : 4;
            int threads = 1;

            List<RepositoryRelease> releases = ParallelExecutor.forEachIn(reposInfoList)
                    .inParallelOn(threads)
                    .execute((repo) -> prepareRelease(config, repo, gavList));

            releases.forEach(release -> gavList.addAll(release.getGavs()));
            return releases.stream();
        }).toList();
    }

    private void performRelease(Config config, DependencyGraph dependencyGraph, List<RepositoryRelease> allReleases) {
        dependencyGraph.forEach((level, repos) -> {
            int threads = config.isRunSequentially() ? 1 : repos.size();
            log.info("\n\nRunning 'PERFORM RELEASE' - processing level {}/{}, {} repositories:\n{}\n\n", level + 1, dependencyGraph.size(), threads,
                    String.join("\n", repos.stream().map(RepositoryConfig::getUrl).toList()));

            ParallelExecutor.forEachIn(allReleases)
                    .filter(new CurrentLevelRepoFilter(repos))
                    .inParallelOn(threads)
                    .execute((release) -> performRelease(config, release));
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

        String releaseVersion = resolveReleaseVersion(config, repository);
        log.info("Release version: {}", releaseVersion);

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

        checkIsAllDependenciesUpdated(repositoryInfo, dependencies);

        gitService.commitChanges(repositoryInfo, "updating dependencies before release");
    }

    private void checkIsAllDependenciesUpdated(RepositoryInfo repositoryInfo, Collection<GAV> dependencies) {
        Set<GAV> updatedModuleDependencies = repositoryInfo.getModuleDependencies();
        Set<GAV> missedDependencies = updatedModuleDependencies.stream()
                .filter(new DifferentVersionDependencyPredicate(dependencies))
                .collect(Collectors.toSet());
        if (!missedDependencies.isEmpty()) {
            throw new RuntimeException("Failed to update dependencies: " + missedDependencies.stream().map(GAV::toString).collect(Collectors.joining("\n")));
        }
    }

    private String resolveReleaseVersion(Config config, RepositoryInfo repository) {
        log.debug("=== CALCULATE RELEASE VERSION {} ===", repository.getUrl());
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

    private void createTag(RepositoryInfo repository, String releaseVersion) {
        log.info("=== CREATE TAG {} ===", repository.getUrl());
        gitService.createTag(repository, releaseVersion);
    }

    private void publishToGoProxy(Config config, RepositoryInfo repository, String releaseVersion) {
        log.info("=== PUBLISH TO GO PROXY {} ===", repository.getUrl());

        goProxyService.publishToLocalGoProxy(repository, releaseVersion, config.getGoProxyDir());
    }

    RepositoryRelease performRelease(Config config, RepositoryRelease release) {
        log.info("\n\n=== PERFORM RELEASE {} ===\n\n", release.getRepository().getUrl());
        RepositoryInfo repository = release.getRepository();

        pushChanges(config, repository, release);

        deployRelease(repository, config, release);
        return release;
    }

    void pushChanges(Config config, RepositoryInfo repository, RepositoryRelease release) {
        log.info("=== GIT PUSH {} ===", repository.getUrl());

        gitService.pushChanges(config.getGitConfig(), repository, release.getReleaseVersion());
        release.setPushedToGit(true);
    }

    void deployRelease(RepositoryInfo repository, Config config, RepositoryRelease release) {
        log.info("=== DEPLOY RELEASE {} ===", repository.getUrl());
        try {
            CommandRunner.runCommand(repository.getRepositoryDirFile(), "goreleaser", "-f", "/tmp/goreleaser.yaml", "release");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record DifferentVersionDependencyPredicate(Collection<GAV> dependencies) implements Predicate<GAV> {
        @Override
        public boolean test(GAV gav) {
            Optional<GAV> foundGav = dependencies.stream()
                    .filter(dGav -> dGav.isSameArtifact(gav))
                    .findFirst();
            if (foundGav.isEmpty()) return false;
            GAV g = foundGav.get();
            return !Objects.equals(gav.getVersion(), g.getVersion());
        }
    }

    private record CurrentLevelRepoFilter(List<RepositoryInfo> repos) implements Predicate<RepositoryRelease> {
        @Override
        public boolean test(RepositoryRelease release) {
            return repos.stream().map(RepositoryConfig::getUrl)
                    .anyMatch(repo -> Objects.equals(repo, release.getRepository().getUrl()));
        }
    }
}
