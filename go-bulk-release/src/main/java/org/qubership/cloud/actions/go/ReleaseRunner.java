package org.qubership.cloud.actions.go;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.nio.dot.DOTExporter;
import org.qubership.cloud.actions.go.model.*;
import org.qubership.cloud.actions.go.proxy.GoProxy;
import org.qubership.cloud.actions.go.proxy.GoProxyPublisher;
import org.qubership.cloud.actions.go.util.CommandRunner;
import org.qubership.cloud.actions.go.util.LoggerWriter;
import org.qubership.cloud.actions.go.util.ParallelExecutor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class ReleaseRunner {
    static YAMLMapper yamlMapper = new YAMLMapper();

    @SneakyThrows
    public Result release(Config config) {
        GoProxy.enableGoProxy();

        Result result = new Result();
        log.info("Config: {}", yamlMapper.writeValueAsString(config));
        // set up git creds if necessary
        GitService gitService = new GitService();
        gitService.setupGit(config.getGitConfig());

        Map<GA, String> dependenciesGavs = config.getGavs().stream().map(GAV::new)
                .collect(Collectors.toMap(gav -> new GA(gav.getGroupId(), gav.getArtifactId()), GAV::getVersion));
        log.debug("VLLA initial dependenciesGavs = {}", dependenciesGavs);
        // build dependency graph
        RepositoryService repositoryService = new RepositoryService();
        Map<Integer, List<RepositoryInfo>> dependencyGraph = repositoryService.buildDependencyGraph(config.getBaseDir(), config.getGitConfig(),
                config.getRepositories(), config.getRepositoriesToReleaseFrom());
        result.setDependencyGraph(dependencyGraph);
        String dot = generateDotFile(dependencyGraph);
        result.setDependenciesDot(dot);
        result.setDryRun(config.isDryRun());

        List<RepositoryRelease> allReleases = dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey() + 1;
            List<RepositoryInfo> reposInfoList = entry.getValue();
            log.info("\n\nRunning 'PREPARE RELEASE' - processing level {}/{}, {} repositories:\n{}\n\n", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream().map(RepositoryConfig::getUrl).toList()));
//            TODO VLLA extract to configuration file?
            int threads = config.isRunSequentially() ? 1 : 4;
            Set<GAV> gavList = dependenciesGavs.entrySet().stream()
                    .map(e -> new GAV(e.getKey().getGroupId(), e.getKey().getArtifactId(), e.getValue()))
                    .collect(Collectors.toSet());
            log.debug("VLLA gavList = {}", gavList);

            List<RepositoryRelease> releases = ParallelExecutor.forEachIn(reposInfoList)
                    .inParallelOn(threads)
                    .execute((repo) -> prepareRelease(config, repo, gavList));


            releases.stream().flatMap(r -> r.getGavs().stream())
                    .forEach(gav -> dependenciesGavs.put(new GA(gav.getGroupId(), gav.getArtifactId()), gav.getVersion()));
            return releases.stream();
        }).toList();

        if (!config.isDryRun()) {
            dependencyGraph.forEach((level, repos) -> {
                int threads = config.isRunSequentially() ? 1 : repos.size();
                log.info("\n\nRunning 'PERFORM RELEASE' - processing level {}/{}, {} repositories:\n{}\n\n", level + 1, dependencyGraph.size(), threads,
                        String.join("\n", repos.stream().map(RepositoryConfig::getUrl).toList()));

                ParallelExecutor.forEachIn(allReleases)
                        .filter(new CurrentLevelRepoFilter(repos))
                        .inParallelOn(threads)
                        .execute((release) -> performRelease(config, release));

//                try (ExecutorService executorService = Executors.newFixedThreadPool(threads)) {
//
//
//                    //todo vlla выяснить, нужен ли функционал со сбором логов из стрима. В любом случае -вынести в служебный метод.
//                    allReleases.stream()
//                            .filter(release -> repos.stream().map(RepositoryConfig::getUrl)
//                                    .anyMatch(repo -> Objects.equals(repo, release.getRepository().getUrl())))
//                            .map(release -> {
//                                    Future<RepositoryRelease> future = executorService.submit(() -> performRelease(config, release));
//                                    return new TraceableFuture<>(future, null, release);
//                            })
//                            .toList()
//                            .forEach(future -> {
//                                try {
//                                    future.getFuture().get();
//                                } catch (Exception e) {
//                                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
//                                    log.error("'perform' process for repository '{}' has failed. Error: {}",
//                                            future.getObject().getRepository().getUrl(), e.getMessage());
//                                    throw new RuntimeException(e);
//                                }
//                            });
//                }
            });
        }
        result.setReleases(allReleases);
        return result;
    }

    public RepositoryRelease prepareRelease(Config config, RepositoryInfo repository, Collection<GAV> dependencies) {
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
        //todo vlla move to separate service
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

//    void updatePatchVersions(RepositoryInfo repositoryInfo, Config config, String javaVersion, OutputStream outputStream) throws Exception {
//        Path repositoryDirPath = Paths.get(repositoryInfo.getBaseDir(), repositoryInfo.getDir());
//        List<String> arguments = new ArrayList<>();
//        arguments.add("-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath());
//        List<String> cmd = List.of("mvn", "-B", "versions:use-latest-releases",
//                "-DallowMajorUpdates=false",
//                "-DallowMinorUpdates=false",
//                warpPropertyInQuotes("-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath()),
//                warpPropertyInQuotes(String.format("-Darguments=%s", String.join(" ", arguments)))
//        );
//
//        ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
//        Optional.ofNullable(javaVersion).map(v -> config.getJavaVersionToJavaHomeEnv().get(v))
//                .ifPresent(javaHome -> processBuilder.environment().put("JAVA_HOME", javaHome));
//
//        log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));
//
//        processBuilder.redirectErrorStream(true);
//        Process process = processBuilder.start();
//        process.getInputStream().transferTo(outputStream);
//        process.waitFor();
//        log.info("Repository: {}\nCmd: '{}' ended with code: {}",
//                repositoryInfo.getUrl(), String.join(" ", cmd), process.exitValue());
//        if (process.exitValue() != 0) {
//            throw new RuntimeException("Failed to execute cmd");
//        }
//    }
//

    //
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

    //
//    void releaseDeploy(RepositoryInfo repositoryInfo, Config config,
//                       RepositoryRelease release, OutputStream outputStream) throws Exception {
//        Path repositoryDirPath = Paths.get(config.getBaseDir(), repositoryInfo.getDir());
//        List<String> arguments = new ArrayList<>();
//        arguments.add("-DskipTests");
//        arguments.add("-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath());
//        if (config.getMavenConfig().getAltDeploymentRepository() != null) {
//            arguments.add("-DaltDeploymentRepository=" + config.getMavenConfig().getAltDeploymentRepository());
//        }
//        String argsString = String.join(" ", arguments);
//        List<String> cmd = Stream.of("mvn", "-B", "release:perform",
//                        "-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath(),
//                        "-DlocalCheckout=true",
//                        "-DautoVersionSubmodules=true",
//                        warpPropertyInQuotes(String.format("-Darguments=%s", argsString)))
//                .collect(Collectors.toList());
//        log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));
//
//        ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
//        Optional.ofNullable(release.getJavaVersion()).map(v -> config.getJavaVersionToJavaHomeEnv().get(v))
//                .ifPresent(javaHome -> processBuilder.environment().put("JAVA_HOME", javaHome));
//        // maven envs
//        if (config.getMavenConfig().getUser() != null && config.getMavenConfig().getPassword() != null) {
//            processBuilder.environment().put("MAVEN_USER", config.getMavenConfig().getUser());
//            processBuilder.environment().put("MAVEN_TOKEN", config.getMavenConfig().getPassword());
//        }
//        Process process = processBuilder.start();
//        process.getInputStream().transferTo(outputStream);
//        process.getErrorStream().transferTo(outputStream);
//        process.waitFor();
//        log.info("Repository: {}\nCmd: '{}' ended with code: {}",
//                repositoryInfo.getUrl(), String.join(" ", cmd), process.exitValue());
//        if (process.exitValue() != 0) {
//            throw new RuntimeException("Failed to execute cmd");
//        }
//        release.setDeployed(true);
//    }
//
    String generateDotFile(Map<Integer, List<RepositoryInfo>> dependencyGraph) {
        Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);
        List<RepositoryInfo> repositoryInfoList = dependencyGraph.values().stream().flatMap(Collection::stream).toList();
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            graph.addVertex(repositoryInfo.getUrl());
        }
        RepositoryInfoLinker linker = new RepositoryInfoLinker(repositoryInfoList);
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            linker.getRepositoriesUsedByThis(repositoryInfo)
                    .stream()
                    .filter(ri -> dependencyGraph.values().stream()
                            .flatMap(Collection::stream).anyMatch(ri2 -> Objects.equals(ri2.getUrl(), ri.getUrl())))
                    .forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
        }
        Function<String, String> vertexIdProvider = vertex -> {
            int level = dependencyGraph.entrySet().stream()
                    .filter(e -> e.getValue().stream().anyMatch(ri -> Objects.equals(ri.getUrl(), vertex)))
                    .mapToInt(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new IllegalStateException(String.format("Failed to find level for vertex: %s", vertex)));
            List<RepositoryInfo> repositoryInfos = dependencyGraph.get(level);
            int index = IntStream.range(0, repositoryInfos.size())
                    .boxed()
                    .filter(i -> repositoryInfos.get(i).getUrl().equals(vertex))
                    .mapToInt(i -> i)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(String.format("Failed to find index for vertex: %s", vertex)));
            String id = vertex.contains("/") ? Optional.of(vertex.split("/")).map(v -> v[v.length - 1]).get() : vertex;
            return String.format("\"%d.%d %s\"", level + 1, index + 1, id);
        };
        DOTExporter<String, StringEdge> exporter = new DOTExporter<>(vertexIdProvider);
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            exporter.exportGraph(graph, stream);
            return stream.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class DifferentVersionDependencyPredicate implements Predicate<GAV> {
        private final Collection<GAV> dependencies;

        private DifferentVersionDependencyPredicate(Collection<GAV> dependencies) {
            this.dependencies = dependencies;
        }

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
