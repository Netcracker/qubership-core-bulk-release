package org.qubership.cloud.actions.go;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.*;
import org.qubership.cloud.actions.go.model.graph.DependencyGraph;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;
import org.qubership.cloud.actions.go.model.repository.RepositoryRelease;
import org.qubership.cloud.actions.go.proxy.GoProxyService;
import org.qubership.cloud.actions.go.util.CommandExecutionException;
import org.qubership.cloud.actions.go.util.CommandRunner;
import org.qubership.cloud.actions.go.util.ParallelExecutor;

import java.util.*;
import java.util.function.Predicate;

@Slf4j
public class ReleaseRunner {
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    private final Config config;
    private final GitService gitService;
    private final GoProxyService goProxyService;
    private final RepositoryService repositoryService;
    private final SemanticReleaseService semanticReleaseService;

    public ReleaseRunner(Config config) {
        this.config = config;
        this.gitService = new GitService(config.getGitConfig());
        this.goProxyService = new GoProxyService(config);
        this.repositoryService = new RepositoryService(config);
        this.semanticReleaseService = new SemanticReleaseService();
    }

    @SneakyThrows
    public Result release() {
        log.info("Start release. Config: {}", YAML_MAPPER.writeValueAsString(config));

        goProxyService.enableGoProxy();

        gitService.setup();

        List<RepositoryInfo> repositories = repositoryService.checkout(config.getBaseDir(),
                config.getRepositories(), config.getRepositoriesToReleaseFrom());

        DependencyGraph dependencyGraph = repositoryService.buildDependencyGraph(repositories,
                config.getRepositories(), config.getRepositoriesToReleaseFrom());

        List<RepositoryRelease> preparedReleases = prepareReleases(config, dependencyGraph);

        if (!config.isDryRun()) {
            performRelease(config, dependencyGraph, preparedReleases);
        }

        return getResult(config, dependencyGraph, preparedReleases);
    }

    List<RepositoryRelease> prepareReleases(Config config, DependencyGraph dependencyGraph) {
        Set<GoGAV> gavList = new HashSet<>();
        return dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey() + 1;
            List<RepositoryInfo> reposInfoList = entry.getValue();
            log.info("=== Running 'PREPARE RELEASE' - processing level {}/{}, {} repositories: ===\n{}", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream().map(RepositoryConfig::getUrl).toList()));

            List<RepositoryRelease> releases = ParallelExecutor.forEachIn(reposInfoList)
                    .inParallelOn(getThreads())
                    .execute(repo -> prepareRelease(config, repo, gavList));

            log.info("=== 'PREPARE RELEASE' - for level {}/{} completed ===", level, dependencyGraph.size());

            saveReleaseGAV(releases, gavList);

            return releases.stream();
        }).toList();
    }

    void performRelease(Config config, DependencyGraph dependencyGraph, List<RepositoryRelease> allReleases) {
        dependencyGraph.forEach((level, repos) -> {
            int threads = config.isRunSequentially() ? 1 : repos.size();
            log.info("=== Running 'PERFORM RELEASE' - processing level {}/{}, repositories: ===\n{}", level + 1, dependencyGraph.size(),
                    String.join("\n", repos.stream().map(RepositoryConfig::getUrl).toList()));

            ParallelExecutor.forEachIn(allReleases)
                    .filter(new CurrentLevelRepoFilter(repos))
                    .inParallelOn(threads)
                    .execute(this::performRelease);

            log.info("=== 'PERFORM RELEASE' - for level {}/{} completed ===", level, dependencyGraph.size());
        });
    }

    void saveReleaseGAV(List<RepositoryRelease> releases, Set<GoGAV> gavList) {
        releases.forEach(release -> gavList.addAll(release.getGavs()));
    }

    RepositoryRelease prepareRelease(Config config, RepositoryInfo repository, Collection<GoGAV> dependencies) {
        log.info("--- PREPARE RELEASE {} ---", repository.getUrl());

        updateDependencies(repository, dependencies);

        ReleaseVersion releaseVersion = resolveReleaseVersion(repository);
        log.info("Release version: {}", releaseVersion);

        if (releaseVersion.isMajorUpdate()) {
            updateMajorVersion(repository, releaseVersion);
        }

        runGoBuild(repository);
        if (!config.isSkipTests()) {
            runGoTest(repository);
        }

        cleanupLocalCopy(repository);
        publishToGoProxy(config, repository, releaseVersion);

        RepositoryRelease release = RepositoryRelease.from(repository, releaseVersion);
        log.info("--- PRE-RELEASE DONE FOR {} ---", repository.getUrl());
        return release;
    }

    void updateDependencies(RepositoryInfo repositoryInfo, Collection<GoGAV> dependencies) {
        log.info("--- UPDATE DEPENDENCIES FOR {} ---", repositoryInfo.getUrl());

        repositoryInfo.updateDepVersions(dependencies);

        gitService.commitModified(repositoryInfo.getRepositoryDirFile(), "chore(deps): updating dependencies before release");
    }

    ReleaseVersion resolveReleaseVersion(RepositoryInfo repository) {
        log.info("--- CALCULATE RELEASE VERSION {} ---", repository.getUrl());

        return semanticReleaseService.resolveReleaseVersion(repository);
    }

    void updateMajorVersion(RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("--- UPDATE MAJOR VERSION FOR {} ---", repository.getUrl());

        String newMajorVersion = "v" + releaseVersion.getNewMajorVersion();
        try {
            CommandRunner.exec(repository.getRepositoryDirFile(), "gomajor", "path", "-version", newMajorVersion);
        } catch (CommandExecutionException e) {
            String msg = "Cannot update major version for repository %s".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }

        gitService.commitModified(repository.getRepositoryDirFile(), "chore(deps): update major version to " + newMajorVersion);
    }

    void runGoBuild(RepositoryInfo repository) {
        log.info("--- GO BUILD {} ---", repository.getUrl());

        try {
            CommandRunner.exec(repository.getRepositoryDirFile(), "go", "build", "-mod=mod", "./...");

            gitService.commitModified(repository.getRepositoryDirFile(), "chore(deps): synchronize go.sum dependencies before release");
        } catch (CommandExecutionException e) {
            String msg = "Build failed for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    void runGoTest(RepositoryInfo repository) {
        log.info("--- GO TEST {} ---", repository.getUrl());
        try {
            CommandRunner.exec(repository.getRepositoryDirFile(), "go", "test", "./...", "-v");
        } catch (CommandExecutionException e) {
            String msg = "Tests failed for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    void publishToGoProxy(Config config, RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("--- PUBLISH TO GO PROXY {} ---", repository.getUrl());

        goProxyService.publishToLocalGoProxy(repository, releaseVersion.getNewVersion().getValue(), config.getGoProxyDir());
    }

    void cleanupLocalCopy(RepositoryInfo repository) {
        try {
            CommandRunner.exec(repository.getRepositoryDirFile(), "git", "clean", "-fdx");
        } catch (CommandExecutionException e) {
            String msg = "Cannot cleanup directory '%s'".formatted(repository.getRepositoryDirFile());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    RepositoryRelease performRelease(RepositoryRelease release) {
        log.info("--- PERFORM RELEASE {} ---", release.getRepository().getUrl());
        RepositoryInfo repository = release.getRepository();

        pushChanges(repository, release);

        release(repository);
        return release;
    }

    void pushChanges(RepositoryInfo repository, RepositoryRelease release) {
        log.info("--- GIT PUSH {} ---", repository.getUrl());

        gitService.pushChanges(repository.getRepositoryDirFile());
        release.setPushedToGit(true);
    }

    void release(RepositoryInfo repository) {
        log.info("--- GITHUB RELEASE {} ---", repository.getUrl());

        semanticReleaseService.release(repository);
    }

    Result getResult(Config config, DependencyGraph dependencyGraph, List<RepositoryRelease> allReleases) {
        Result result = new Result();
        result.setDependencyGraph(dependencyGraph);
        result.setDependenciesDot(dependencyGraph.generateDotFile());
        result.setDryRun(config.isDryRun());
        result.setReleases(allReleases);
        return result;
    }

    private int getThreads() {
//      it fails if we try to 'go test' in parallel -> sequential build for now
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
