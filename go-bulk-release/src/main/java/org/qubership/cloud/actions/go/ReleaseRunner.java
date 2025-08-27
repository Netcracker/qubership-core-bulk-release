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
    public static final String GO_SEMANTIC_RELEASE_CURRENT_VERSION = "found version: ";
    public static final String GO_SEMANTIC_RELEASE_NEW_VERSION = "new version: ";
    public static final String CHANGELOG_FORMAT_TEMPLATE = "* {{with .Scope -}} **{{.}}:** {{end}} {{- .Message}} ({{trimSHA .SHA}}) {{- with index .Annotations \"author_login\" }} - by @{{.}} {{- end}}";
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

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
        log.info("Start release. Config: {}", YAML_MAPPER.writeValueAsString(config));

        goProxyService.enableGoProxy();

        gitService.setupGit();

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
            log.info("Running 'PREPARE RELEASE' - processing level {}/{}, {} repositories:\n{}", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream().map(RepositoryConfig::getUrl).toList()));

            List<RepositoryRelease> releases = ParallelExecutor.forEachIn(reposInfoList)
                    .inParallelOn(getThreads())
                    .execute(repo -> prepareRelease(config, repo, gavList));

            log.info("'PREPARE RELEASE' - for level {}/{} completed", level, dependencyGraph.size());

            saveReleaseGAV(releases, gavList);

            return releases.stream();
        }).toList();
    }

    void saveReleaseGAV(List<RepositoryRelease> releases, Set<GoGAV> gavList) {
        releases.forEach(release -> gavList.addAll(release.getGavs()));
    }

    void performRelease(Config config, DependencyGraph dependencyGraph, List<RepositoryRelease> allReleases) {
        dependencyGraph.forEach((level, repos) -> {
            int threads = config.isRunSequentially() ? 1 : repos.size();
            log.info("Running 'PERFORM RELEASE' - processing level {}/{}, repositories:\n{}", level + 1, dependencyGraph.size(),
                    String.join("\n", repos.stream().map(RepositoryConfig::getUrl).toList()));

            ParallelExecutor.forEachIn(allReleases)
                    .filter(new CurrentLevelRepoFilter(repos))
                    .inParallelOn(threads)
                    .execute(this::performRelease);
        });
    }

    RepositoryRelease prepareRelease(Config config, RepositoryInfo repository, Collection<GoGAV> dependencies) {
        log.info("=== PREPARE RELEASE {} ===", repository.getUrl());

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
        log.info("=== PRE-RELEASE DONE FOR {} ===", repository.getUrl());
        return release;
    }

    void updateDependencies(RepositoryInfo repositoryInfo, Collection<GoGAV> dependencies) {
        log.info("=== UPDATE DEPENDENCIES FOR {} ===", repositoryInfo.getUrl());

        repositoryInfo.updateDepVersions(dependencies);

        gitService.commitModified(repositoryInfo.getRepositoryDirFile(), "chore(deps): updating dependencies before release");
    }

    void updateMajorVersion(RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("=== UPDATE MAJOR VERSION FOR {} ===", repository.getUrl());

        String newMajorVersion = "v" + releaseVersion.getNewMajorVersion();
        try {
            CommandRunner.exec(repository.getRepositoryDirFile(), "gomajor", "path", "-version", newMajorVersion);
        } catch (CommandExecutionException e) {
            String msg = "Cannot update major version for repository %s".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }

        gitService.commitModified(repository.getRepositoryDirFile(), "chore(deps): update major version to " + newMajorVersion);
    }

    ReleaseVersion resolveReleaseVersion(RepositoryInfo repository) {
        log.info("=== CALCULATE RELEASE VERSION {} ===", repository.getUrl());

        try {
            String[] command = new String[]{"semantic-release", "--no-ci", "--dry", "--allow-no-changes",
                    "--provider", "git",
                    "--provider-opt", "default_branch=main",
                    "--ci-condition", "default",
                    "--commit-analyzer-opt", "patch_release_rules=*"};
            List<String> result = CommandRunner.execWithResult(repository.getRepositoryDirFile(), command);

            String currentVersion = null;
            String newVersion = null;

            for (String line : result) {
                if (line.contains(GO_SEMANTIC_RELEASE_CURRENT_VERSION)) {
                    currentVersion = "v" + getSubstringAfter(line, GO_SEMANTIC_RELEASE_CURRENT_VERSION);
                    continue;
                }
                if (line.contains(GO_SEMANTIC_RELEASE_NEW_VERSION)) {
                    newVersion = "v" + getSubstringAfter(line, GO_SEMANTIC_RELEASE_NEW_VERSION);
                }
            }
            if (currentVersion == null || currentVersion.equals("v0.0.0")) {
                String msg = "Cannot find any valid tag for repository %s. Please, create at least one tag".formatted(repository.getUrl());
                throw new UnsupportedOperationException(msg);
            } else if (newVersion == null) {
                return new ReleaseVersion(currentVersion, currentVersion);
            } else {
                return new ReleaseVersion(currentVersion, newVersion);
            }
        } catch (CommandExecutionException e) {
            String msg = "Cannot resolve next release version for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    void runGoBuild(RepositoryInfo repository) {
        log.info("=== GO BUILD {} ===", repository.getUrl());

        try {
            CommandRunner.exec(repository.getRepositoryDirFile(), "go", "build", "./...");
        } catch (CommandExecutionException e) {
            String msg = "Build failed for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    void runGoTest(RepositoryInfo repository) {
        log.info("=== GO TEST {} ===", repository.getUrl());
        try {
            CommandRunner.exec(repository.getRepositoryDirFile(), "go", "test", "./...", "-v");
        } catch (CommandExecutionException e) {
            String msg = "Tests failed for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    void publishToGoProxy(Config config, RepositoryInfo repository, ReleaseVersion releaseVersion) {
        log.info("=== PUBLISH TO GO PROXY {} ===", repository.getUrl());

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
        log.info("\n\n=== PERFORM RELEASE {} ===\n\n", release.getRepository().getUrl());
        RepositoryInfo repository = release.getRepository();

        pushChanges(repository, release);

        deployRelease(repository);
        return release;
    }

    void pushChanges(RepositoryInfo repository, RepositoryRelease release) {
        log.info("=== GIT PUSH {} ===", repository.getDir());

        gitService.pushChanges(repository.getRepositoryDirFile());
        release.setPushedToGit(true);
    }

    void deployRelease(RepositoryInfo repository) {
        log.info("=== DEPLOY RELEASE {} ===", repository.getDir());

        try {
            String[] command = {"semantic-release", "--allow-no-changes", "--no-ci",
                    "--provider", "github",
                    "--provider-opt", "slug=" + repository.getDir(),
                    "--ci-condition", "default",
                    "--commit-analyzer-opt", "patch_release_rules=*",
                    "--changelog-generator-opt", "format_commit_template=" + CHANGELOG_FORMAT_TEMPLATE};
            CommandRunner.exec(repository.getRepositoryDirFile(), command);
        } catch (CommandExecutionException e) {
            String msg = "Cannot deploy release for repository %s. Se logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    Result getResult(Config config, DependencyGraph dependencyGraph, List<RepositoryRelease> allReleases) {
        Result result = new Result();
        result.setDependencyGraph(dependencyGraph);
        String dot = dependencyGraph.generateDotFile();
        result.setDependenciesDot(dot);
        result.setDryRun(config.isDryRun());
        result.setReleases(allReleases);
        return result;
    }

    private int getThreads() {
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

    static String getSubstringAfter(String line, String substring) {
        return line.substring(line.indexOf(substring) + substring.length());
    }
}
