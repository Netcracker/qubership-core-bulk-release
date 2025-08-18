package org.qubership.cloud.actions.go;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.qubership.cloud.actions.go.model.*;
import org.qubership.cloud.actions.go.proxy.GoProxy;
import org.qubership.cloud.actions.go.proxy.GoProxyPublisher;
import org.qubership.cloud.actions.go.util.CommandRunner;
import org.qubership.cloud.actions.go.util.LoggerWriter;
import org.qubership.cloud.actions.go.util.ParallelExecutor;

import java.io.PrintWriter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
public class ReleaseRunner {
    static YAMLMapper yamlMapper = new YAMLMapper();

    @SneakyThrows
    public Result release(Config config) {
        GoProxy.enableGoProxy();

        log.info("Config: {}", yamlMapper.writeValueAsString(config));

        GitService gitService = new GitService();
        gitService.setupGit(config.getGitConfig());

        RepositoryService repositoryService = new RepositoryService();
        DependencyGraph dependencyGraph = repositoryService.buildDependencyGraph(config.getBaseDir(), config.getGitConfig(),
                config.getRepositories(), config.getRepositoriesToReleaseFrom());

        List<RepositoryRelease> allReleases = prepareReleases(config, dependencyGraph);

        if (!config.isDryRun()) {
            performRelease(config, dependencyGraph, allReleases);
        }

        return getResult(config, dependencyGraph, allReleases);
    }

    private List<RepositoryRelease> prepareReleases(Config config, DependencyGraph dependencyGraph) {
        Map<GA, String> dependenciesGavs = new HashMap<>();
        return dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey() + 1;
            List<RepositoryInfo> reposInfoList = entry.getValue();
            log.info("\n\nRunning 'PREPARE RELEASE' - processing level {}/{}, {} repositories:\n{}\n\n", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream().map(RepositoryConfig::getUrl).toList()));
//            TODO VLLA extract to configuration file?
            int threads = config.isRunSequentially() ? 1 : 4;
            Set<GAV> gavList = dependenciesGavs.entrySet().stream()
                    .map(e -> new GAV(e.getKey().getGroupId(), e.getKey().getArtifactId(), e.getValue()))
                    .collect(Collectors.toSet());

            List<RepositoryRelease> releases = ParallelExecutor.forEachIn(reposInfoList)
                    .inParallelOn(threads)
                    .execute((repo) -> prepareRelease(config, repo, gavList));

            releases.stream().flatMap(r -> r.getGavs().stream())
                    .forEach(gav -> dependenciesGavs.put(new GA(gav.getGroupId(), gav.getArtifactId()), gav.getVersion()));
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

        buildGoProxy(repository, releaseVersion);

        RepositoryRelease release = buildRepositoryReleaseDTO(repository, releaseVersion);
        log.info("=== PRE-RELEASE DONE FOR {} ===", repository.getUrl());
        return release;
    }

    private void updateDependencies(RepositoryInfo repositoryInfo, Collection<GAV> dependencies) {
        log.info("updateDependencies. {}", repositoryInfo.getUrl());
        repositoryInfo.updateDepVersions(dependencies);

        checkIsAllDependenciesUpdated(repositoryInfo, dependencies);

        commitUpdatedDependenciesIfAny(repositoryInfo);
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
        CommandRunner.runCommand(repository.getRepositoryDirFile(), "git", "tag", "-a", releaseVersion, "-m", "Release " + releaseVersion);
    }

    private void buildGoProxy(RepositoryInfo repository, String releaseVersion) {
        log.info("=== BUILD GO PROXY {} ===", repository.getUrl());
        String goProxy;

        String osName = System.getProperty("os.name").toLowerCase();
        log.debug("os.name = {}", osName);
        if (osName.contains("win")) {
            goProxy = "\\\\wsl.localhost\\Ubuntu-24.04\\home\\user\\bulk_release\\GOPROXY";
        } else {
            goProxy = "/tmp/GOPROXY";
        }

        GoProxyPublisher.publishToLocalGoProxy(repository, releaseVersion, goProxy);
    }

    private RepositoryRelease buildRepositoryReleaseDTO(RepositoryInfo repository, String releaseVersion) {
        RepositoryRelease release = new RepositoryRelease();
        release.setRepository(repository);
        release.setReleaseVersion(releaseVersion);
        release.setTag(releaseVersion);
        List<GAV> gavs = new ArrayList<>();
        repository.getModules().forEach(gav -> gavs.add(new GAV("TMP", gav.getArtifactId(), releaseVersion)));
        release.setGavs(gavs);
        return release;
    }

    void commitUpdatedDependenciesIfAny(RepositoryInfo repository) {
        log.info("commitUpdatedDependenciesIfAny. {}", repository.getUrl());
        try (Git git = Git.open(repository.getRepositoryDirFile())) {
            List<DiffEntry> diff = git.diff().call();
            List<String> modifiedFiles = diff.stream().filter(d -> d.getChangeType() == DiffEntry.ChangeType.MODIFY).map(DiffEntry::getNewPath).toList();
            if (!modifiedFiles.isEmpty()) {
                git.add().setUpdate(true).call();
                String msg = "updating dependencies before release";
                git.commit().setMessage(msg).call();
                log.info("Commited '{}', changed files:\n{}", msg, String.join("\n", modifiedFiles));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    RepositoryRelease performRelease(Config config, RepositoryRelease release) {
        log.info("\n\n=== PERFORM RELEASE {} ===\n\n", release.getRepository().getUrl());
        RepositoryInfo repository = release.getRepository();
        pushChanges(config, repository, release);
        //releaseDeploy(repository, config, release);
        return release;
    }

    void pushChanges(Config config, RepositoryInfo repositoryInfo, RepositoryRelease release) {
        log.info("=== GIT PUSH {} ===", repositoryInfo.getUrl());
        String releaseVersion = release.getReleaseVersion();
        PrintWriter printWriter = new PrintWriter(new LoggerWriter(), true);
        try (Git git = Git.open(repositoryInfo.getRepositoryDirFile())) {
            Optional<Ref> tagOpt = git.tagList().call().stream()
                    .filter(t -> t.getName().equals(String.format("refs/tags/%s", releaseVersion)))
                    .findFirst();
            if (tagOpt.isEmpty()) {
                throw new IllegalStateException(String.format("git tag: %s not found", releaseVersion));
            }
            git.push()
                    .setProgressMonitor(new TextProgressMonitor(printWriter))
                    .setCredentialsProvider(config.getGitConfig().getCredentialsProvider())
                    .setPushAll()
                    .setPushTags()
                    .call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // do not close because we want
            printWriter.flush();
        }
        log.info("Pushed to git: tag: {}", releaseVersion);
        release.setPushedToGit(true);
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
