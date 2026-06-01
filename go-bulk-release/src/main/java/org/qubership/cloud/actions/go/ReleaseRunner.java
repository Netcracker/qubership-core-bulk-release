package org.qubership.cloud.actions.go;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.*;
import org.qubership.cloud.actions.go.model.gomod.GoModule;
import org.qubership.cloud.actions.go.model.graph.DependencyGraph;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;
import org.qubership.cloud.actions.go.model.repository.RepositoryRelease;
import org.qubership.cloud.actions.go.proxy.GoProxyService;
import org.qubership.cloud.actions.go.util.CommandExecutionException;
import org.qubership.cloud.actions.go.util.CommandRunner;
import org.qubership.cloud.actions.go.util.ParallelExecutor;

import java.io.File;
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

            if (config.isLtsRelease()) {
                performLtsSteps(preparedReleases);
            }
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

        if (releaseVersion.isMajorUpdate() && config.getBranch() == null) {
            updateMajorVersion(repository, releaseVersion);
        }

        runGoBuild(repository);
        if (!config.isSkipTests()) {
            runGoTest(repository);
        }

        createTagsForSubmodules(repository, releaseVersion);

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

        ReleaseVersion releaseVersion = semanticReleaseService.resolveReleaseVersion(repository);

        if (config.getBranch() != null && !releaseVersion.isPatchOnlyUpdate()) {
            throw new ReleaseTerminationException(
                    "Branch release requires a patch-only version bump, but semantic-release calculated %s → %s for repository %s. The branch contains feat: or BREAKING CHANGE commits."
                            .formatted(releaseVersion.getCurrentVersion(), releaseVersion.getNewVersion(), repository.getUrl()));
        }

        return releaseVersion;
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
            for (GoModule goModule : repository.getGoModFiles()) {
                log.info("Run go build for module {}", goModule.getModuleDir());
                CommandRunner.exec(goModule.getModuleDir(), "go", "build", "-mod=mod", "./...");
            }
        } catch (CommandExecutionException e) {
            String msg = "Build failed for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }

        gitService.commitModified(repository.getRepositoryDirFile(), "chore(deps): synchronize go.sum dependencies before release");
    }

    void runGoTest(RepositoryInfo repository) {
        log.info("--- GO TEST {} ---", repository.getUrl());
        try {
            for (GoModule goModule: repository.getGoModFiles()) {
                log.info("Run go test for module {}", goModule.getModuleDir());
                CommandRunner.exec(goModule.getModuleDir(), "go", "test", "./...", "-v");
            }
        } catch (CommandExecutionException e) {
            String msg = "Tests failed for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    //It is necessary for submodules (modules with `go.mod` not located in the root directory of the repository)
    private void createTagsForSubmodules(RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("--- GO CREATE TAGS {} ---", repository.getUrl());
        try {
            for (GoModule goModule: repository.getGoModFiles()) {
                if (goModule.isSubModule()) {
                    log.info("Create tag for module {}", goModule.getModuleDir());
                    String tagName = goModule.relativePath() + "/" + releaseVersion.getNewVersion();

                    CommandRunner.exec(goModule.getModuleDir(), "git", "tag", "-a", tagName, "-m", "\"%s\"".formatted(tagName));
                }
            }
        } catch (CommandExecutionException e) {
            String msg = "Cannot create tag for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    void publishToGoProxy(Config config, RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("--- PUBLISH TO GO PROXY {} ---", repository.getUrl());

        for (GoModule goModule: repository.getGoModFiles()) {
            log.debug("Publish to GOPROXY module {}", goModule.getModuleDir());
            goProxyService.publishToLocalGoProxy(goModule.getModuleDir().getAbsolutePath(), releaseVersion.getNewVersion().getValue(), config.getGoProxyDir());
        }
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

    void performLtsSteps(List<RepositoryRelease> allReleases) {
        log.info("=== Running 'LTS STEPS' for {} repositories ===", allReleases.size());
        ParallelExecutor.forEachIn(allReleases)
                .inParallelOn(allReleases.size())
                .execute(release -> {
                    performLtsStepsForRepository(release);
                    return release;
                });
        log.info("=== 'LTS STEPS' completed ===");
    }

    void performLtsStepsForRepository(RepositoryRelease release) {
        File repoDir = release.getRepository().getRepositoryDirFile();
        String ltsBranchName = config.getLtsBranchName();
        log.info("--- LTS STEPS {} ---", release.getRepository().getUrl());

        String originalBranch = getCurrentBranch(repoDir);

        createLtsBranch(release, ltsBranchName, originalBranch);
        makeTechnicalCommit(release, originalBranch);

        log.info("--- LTS STEPS DONE FOR {} ---", release.getRepository().getUrl());
    }

    void createLtsBranch(RepositoryRelease release, String ltsBranchName, String originalBranch) {
        File repoDir = release.getRepository().getRepositoryDirFile();
        log.info("--- CREATE LTS BRANCH '{}' for {} ---", ltsBranchName, release.getRepository().getUrl());
        try {
            CommandRunner.exec(repoDir, "git", "checkout", "-b", ltsBranchName);
        } catch (CommandExecutionException e) {
            throw new ReleaseTerminationException(
                    "Failed to create LTS branch '%s' in repository %s. The branch may already exist."
                            .formatted(ltsBranchName, release.getRepository().getUrl()), e);
        }
        gitService.pushBranch(repoDir, ltsBranchName);
        try {
            CommandRunner.exec(repoDir, "git", "checkout", originalBranch);
        } catch (CommandExecutionException e) {
            throw new ReleaseTerminationException(
                    "Failed to switch back to branch '%s' in repository %s."
                            .formatted(originalBranch, release.getRepository().getUrl()), e);
        }
    }

    void makeTechnicalCommit(RepositoryRelease release, String originalBranch) {
        File repoDir = release.getRepository().getRepositoryDirFile();
        log.info("--- TECHNICAL COMMIT IN '{}' for {} ---", originalBranch, release.getRepository().getUrl());
        try {
            CommandRunner.exec(repoDir, "git", "commit", "--allow-empty", "-m", "feat: technical commit for bump minor version");
        } catch (CommandExecutionException e) {
            throw new ReleaseTerminationException(
                    "Failed to create technical commit in repository %s.".formatted(release.getRepository().getUrl()), e);
        }
        gitService.pushBranch(repoDir, originalBranch);
    }

    String getCurrentBranch(File repoDir) {
        try {
            return CommandRunner.execWithResult(repoDir, "git", "symbolic-ref", "--short", "HEAD")
                    .stream().findFirst().map(String::strip).orElse("main");
        } catch (CommandExecutionException e) {
            return "main";
        }
    }

    Result getResult(Config config, DependencyGraph dependencyGraph, List<RepositoryRelease> allReleases) {
        Result result = new Result();
        result.setDependencyGraph(dependencyGraph);
        result.setDependenciesDot(dependencyGraph.generateDotFile());
        result.setDryRun(config.isDryRun());
        result.setReleases(allReleases);
        result.setLtsRelease(config.isLtsRelease() && !config.isDryRun());
        result.setLtsBranchName(config.getLtsBranchName());
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
