package org.qubership.cloud.actions.maven;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.nio.dot.DOTExporter;
import org.qubership.cloud.actions.maven.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class ReleaseRunner {
    static YAMLMapper yamlMapper = new YAMLMapper();

    public Result release(Config config) throws Exception {
        Result result = new Result();
        log.info("Config: {}", yamlMapper.writeValueAsString(config));
        // set up git creds if necessary
        GitService gitService = new GitService(config.getGitConfig());

        Map<GA, String> dependenciesGavs = config.getGavs().stream().map(GAV::new)
                .collect(Collectors.toMap(gav -> new GA(gav.getGroupId(), gav.getArtifactId()), GAV::getVersion));
        // build dependency graph
        RepositoryService repositoryService = new RepositoryService(gitService);
        Map<Integer, List<RepositoryInfo>> dependencyGraph = repositoryService.buildDependencyGraph(config.getBaseDir(), config.getGitConfig(),
                config.getRepositories(), config.getRepositoriesToReleaseFrom());
        result.setDependencyGraph(dependencyGraph);
        String dot = generateDotFile(dependencyGraph);
        result.setDependenciesDot(dot);
        result.setDryRun(config.isDryRun());

        Path logsFolderPath = Path.of(config.getBaseDir()).resolve("logs");
        if (!Files.exists(logsFolderPath)) {
            Files.createDirectories(logsFolderPath);
        }
        log.info("Dependency graph:\n{}", String.join("\n", dependencyGraph.entrySet().stream()
                .map(entry -> {
                    int level = entry.getKey();
                    List<RepositoryInfo> reposInfoList = entry.getValue();
                    return String.format("Level %d/%d, repos:\n%s", level + 1, dependencyGraph.size(),
                            String.join("\n", reposInfoList.stream()
                                    .map(r -> "%s [pom:%s]".formatted(r.getUrl(), r.getPomFolder()))
                                    .toList()));
                }).toList()));

        List<RepositoryRelease> allReleases = dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey() + 1;
            List<RepositoryInfo> reposInfoList = entry.getValue();
            log.info("Running 'prepare' - processing level {}/{}, {} repositories:\n{}", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream()
                            .map(rc -> "%s [pomFolder: '%s']".formatted(rc.getUrl(), rc.getPomFolder())).toList()));

            int threads = Math.min(config.getRunParallelism(), reposInfoList.size());
            try (ExecutorService executorService = Executors.newFixedThreadPool(threads)) {
                Set<GAV> gavList = dependenciesGavs.entrySet().stream()
                        .map(e -> new GAV(e.getKey().getGroupId(), e.getKey().getArtifactId(), e.getValue()))
                        .collect(Collectors.toSet());
                List<TraceableFuture<RepositoryRelease, RepositoryInfo>> traceableFutures = reposInfoList.stream()
                        .map(repo -> {
                            try {
                                PipedOutputStream out = new PipedOutputStream();
                                PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
                                Future<RepositoryRelease> future = executorService.submit(() -> releasePrepare(config, repo, gavList, out));
                                return new TraceableFuture<>(future, pipedInputStream, repo);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .toList();
                List<RepositoryRelease> releases = traceableFutures.stream()
                        .map(future -> {
                            RepositoryInfo repositoryInfo = future.getObject();
                            Path repoLogDirPath = logsFolderPath.resolve(repositoryInfo.getDir());
                            if (!repositoryInfo.getPomFolder().isBlank()) {
                                repoLogDirPath = repoLogDirPath.resolve(repositoryInfo.getPomFolder());
                            }
                            Path repoLogFilePath = repoLogDirPath.resolve("prepare.log");
                            String pomFolder = repositoryInfo.getPomFolder().isBlank() ? "" : "/" + repositoryInfo.getPomFolder();
                            try (PipedInputStream pipedInputStream = future.getPipedInputStream();
                                 BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
                                if (!Files.exists(repoLogDirPath)) {
                                    Files.createDirectories(repoLogDirPath);
                                }
                                Files.writeString(repoLogFilePath, "", StandardCharsets.UTF_8,
                                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                log.info("Started 'prepare' process for repository '{}'.\nFor details see log file: {}",
                                        repositoryInfo.getUrl() + pomFolder, repoLogFilePath);
                                String line;
                                int iterations = 0;
                                while ((line = reader.readLine()) != null) {
                                    Files.writeString(repoLogFilePath, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                                    if (config.isLogsToConsole()) {
                                        System.out.println(line);
                                    } else {
                                        if (++iterations % 100 == 0) {
                                            System.out.printf("%d x 100 log lines forwarded%n", iterations / 100);
                                        }
                                    }
                                }
                                RepositoryRelease repositoryRelease = future.getFuture().get();
                                log.info("Finished 'prepare' process for repository '{}'.\nFor details see log file: {}",
                                        repositoryInfo.getUrl() + pomFolder, repoLogFilePath);
                                return repositoryRelease;
                            } catch (Exception e) {
                                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                                if (!config.isLogsToConsole()) {
                                    try {
                                        Files.readAllLines(repoLogFilePath).forEach(log::error);
                                    } catch (IOException ioe) {
                                        log.error("Failed to read log file: {}", repoLogFilePath, ioe);
                                    }
                                }
                                log.error("'prepare' process for repository '{}' has failed. Error: {}. \nFor details see log content above",
                                        repositoryInfo.getUrl() + pomFolder, e.getMessage());
                                log.info("Shutting down now executor service");
                                executorService.shutdownNow();
                                for (TraceableFuture<RepositoryRelease, RepositoryInfo> traceableFuture : new ArrayList<>(traceableFutures)) {
                                    try {
                                        traceableFuture.getPipedInputStream().close();
                                    } catch (IOException ioe) {
                                        log.warn("Failed to close piped stream: {}", ioe.getMessage());
                                    }
                                }
                                throw new RuntimeException(e);
                            }
                        })
                        .toList();
                releases.stream().flatMap(r -> r.getGavs().stream())
                        .forEach(gav -> dependenciesGavs.put(new GA(gav.getGroupId(), gav.getArtifactId()), gav.getVersion()));
                return releases.stream();
            }
        }).toList();

        if (!config.isDryRun()) {
            Map<Integer, Map<String, Map<Integer, RepositoryInfo>>> publishDependencyGraph = new TreeMap<>();
            AtomicInteger newLevel = new AtomicInteger(-1);
            AtomicReference<String> lastUrlRef = new AtomicReference<>();
            dependencyGraph.forEach((level, repos) -> repos.forEach(ri -> {
                String url = ri.getUrl();
                String lastUrl = lastUrlRef.get();
                if (!Objects.equals(lastUrl, url)) {
                    newLevel.incrementAndGet();
                    lastUrlRef.set(url);
                }
                Map<String, Map<Integer, RepositoryInfo>> urlToInfosMap = publishDependencyGraph.computeIfAbsent(newLevel.get(), k -> new TreeMap<>());
                Map<Integer, RepositoryInfo> urlToReposMap = urlToInfosMap.computeIfAbsent(url, k -> new TreeMap<>());
                urlToReposMap.put(level, ri); // todo - maybe re-calculate this level
            }));

            publishDependencyGraph.forEach((level, reposByUrlMap) ->
                    reposByUrlMap.forEach((url, reposMap) -> {
                        List<RepositoryInfo> repos = reposMap.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(Map.Entry::getValue)
                                .toList();
                        log.info("Running 'perform' - processing level {}/{}, {} repositories:\n{}", level + 1, publishDependencyGraph.size(), repos.size(),
                                String.join("\n", repos.stream()
                                        .map(rc -> "%s [pomFolder: '%s']".formatted(rc.getUrl(), rc.getPomFolder())).toList()));
                        List<RepositoryRelease> releases = allReleases.stream()
                                .filter(release -> repos.stream()
                                        .anyMatch(repo -> Objects.equals(repo.getUrl(), release.getRepository().getUrl()) &&
                                                          Objects.equals(repo.getPomFolder(), release.getRepository().getPomFolder())))
                                .toList();
                        RepositoryInfo repositoryInfo = releases.getFirst().getRepository();
                        Path repoLogDirPath = logsFolderPath.resolve(repositoryInfo.getDir());
                        Path repoLogFilePath = repoLogDirPath.resolve("perform.log");

                        try (PipedOutputStream out = new PipedOutputStream();
                             PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
                             BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
                            try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
                                Future<?> future = executorService.submit(() -> {
                                    try {
                                        performRelease(config, releases, out);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                                if (!Files.exists(repoLogDirPath)) {
                                    Files.createDirectories(repoLogDirPath);
                                }
                                Files.writeString(repoLogFilePath, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                log.info("Started 'perform' process for repository '{}'.\nFor details see log file: {}",
                                        repositoryInfo.getUrl(), repoLogFilePath);
                                String line;
                                int iterations = 0;
                                while ((line = reader.readLine()) != null) {
                                    Files.writeString(repoLogFilePath, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                                    if (config.isLogsToConsole()) {
                                        System.out.println(line);
                                    } else {
                                        if (++iterations % 100 == 0) {
                                            System.out.printf("%d x 100 log lines forwarded%n", iterations / 100);
                                        }
                                    }
                                }
                                future.get();
                                log.info("Finished 'perform' process for repository '{}'.\nFor details see log file: {}",
                                        repositoryInfo.getUrl(), repoLogFilePath);
                            }
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                            if (!config.isLogsToConsole()) {
                                try {
                                    Files.readAllLines(repoLogFilePath).forEach(log::error);
                                } catch (IOException ioe) {
                                    log.error("Failed to read log file: {}", repoLogFilePath, ioe);
                                }
                            }
                            log.error("'perform' process for repository '{}' has failed. Error: {}.\nFor details see log content above",
                                    repositoryInfo.getUrl(), e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }));
        }
        result.setReleases(allReleases);
        return result;
    }

    RepositoryRelease releasePrepare(Config config, RepositoryInfo repository, Collection<GAV> dependencies, OutputStream outputStream) throws Exception {
        try (outputStream) {
            updateDependencies(repository, dependencies);

            String javaVersion = repository.calculateJavaVersion();
            // todo - disable, because this plugin brings versions from redhat server i.e. 2.0.17.redhat-00001 which is not acceptable
            // updatePatchVersions(repository, config, javaVersion, outputStream);

            VersionIncrementType versionIncrementType = Optional.ofNullable(repository.getVersionIncrementType())
                    .orElse(Optional.ofNullable(config.getVersionIncrementType()).orElse(VersionIncrementType.PATCH));
            VersionTag versionTag = repository.calculateReleaseVersion(versionIncrementType);
            return releasePrepare(repository, config, versionTag, javaVersion, outputStream);
        }
    }

    void updateDependencies(RepositoryInfo repositoryInfo, Collection<GAV> dependencies) {
        repositoryInfo.updateDepVersions(dependencies);
        // check all versions were updated
        Set<GAV> updatedModuleDependencies = repositoryInfo.getModuleDependencies();
        List<String> missedDependencies = updatedModuleDependencies.stream()
                .map(gav -> {
                    Optional<GAV> foundGav = dependencies.stream()
                            .filter(dGav -> Objects.equals(gav.getGroupId(), dGav.getGroupId()) &&
                                            Objects.equals(gav.getArtifactId(), dGav.getArtifactId()))
                            .findFirst();
                    if (foundGav.isEmpty()) {
                        return Optional.<String>empty();
                    }
                    GAV g = foundGav.get();
                    if (!Objects.equals(gav.getVersion(), g.getVersion())) {
                        return Optional.of(String.format("GA [%s] expected version = %s, actual = %s",
                                gav.toGA().toString(), g.getVersion(), gav.getVersion()));
                    } else {
                        return Optional.<String>empty();
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        if (!missedDependencies.isEmpty()) {
            throw new RuntimeException(String.format("Failed to update dependencies. Check poms of repository: %s have valid versions assignment for: \n%s",
                    repositoryInfo.getUrl(), String.join("\n", missedDependencies)));
        }
        commitUpdatedDependenciesIfAny(repositoryInfo);
    }

    void commitUpdatedDependenciesIfAny(RepositoryInfo repository) {
        Path repositoryDirPath = Paths.get(repository.getBaseDir(), repository.getDir());
        try (Git git = Git.open(repositoryDirPath.toFile())) {
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

    RepositoryRelease releasePrepare(RepositoryInfo repositoryInfo, Config config, VersionTag versionTag,
                                     String javaVersion, OutputStream outputStream) throws Exception {
        Path repositoryDirPath = Paths.get(repositoryInfo.getBaseDir(), repositoryInfo.getDir(), repositoryInfo.getPomFolder());
        List<String> arguments = new ArrayList<>();
        arguments.add("-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath());
        if (repositoryInfo.isSkipTests() || config.isSkipTests()) {
            arguments.add("-DskipTests=true");
        } else {
            arguments.add("-Dsurefire.rerunFailingTestsCount=1");
        }
        List<String> cmd = List.of("mvn", "-B", "release:prepare",
                "-Dresume=true",
                "-DautoVersionSubmodules=true",
                "-DreleaseVersion=" + versionTag.version(),
                "-DpushChanges=false",
                "-Dtag=" + versionTag.tag(),
                warpPropertyInQuotes("-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath()),
                warpPropertyInQuotes("-DtagNameFormat=@{project.version}"),
                warpPropertyInQuotes(String.format("-Darguments=%s", String.join(" ", arguments))),
                warpPropertyInQuotes("-DpreparationGoals=clean install"));

        ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
        Optional.ofNullable(javaVersion).map(v -> config.getJavaVersionToJavaHomeEnv().get(v))
                .ifPresent(javaHome -> processBuilder.environment().put("JAVA_HOME", javaHome));

        PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(outputStream, UTF_8));
        try {
            printWriter.println(String.format("Repository: %s\nCmd: '%s' started", repositoryInfo.getUrl(), String.join(" ", cmd)));

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            process.getInputStream().transferTo(outputStream);
            process.waitFor();
            printWriter.println(String.format("Repository: %s\nCmd: '%s' ended with code: %d",
                    repositoryInfo.getUrl(), String.join(" ", cmd), process.exitValue()));

            if (process.exitValue() != 0) {
                throw new RuntimeException("Failed to execute cmd");
            }
            List<GAV> gavs = Files.readString(Paths.get(repositoryDirPath.toString(), "release.properties")).lines()
                    .filter(l -> l.startsWith("project.rel."))
                    .map(l -> l.replace("project.rel.", "")
                            .replace("\\", "")
                            .replace("=", ":"))
                    .map(GAV::new).toList();
            RepositoryRelease release = new RepositoryRelease();
            release.setRepository(repositoryInfo);
            release.setVersionTag(versionTag);
            release.setJavaVersion(javaVersion);
            release.setGavs(gavs);
            return release;
        } finally {
            printWriter.flush();
        }
    }

    String warpPropertyInQuotes(String prop) {
        return String.format("\"%s\"", prop);
    }

    void performRelease(Config config, List<RepositoryRelease> releases, OutputStream outputStream) throws Exception {
        try (outputStream) {
            pushChanges(config, releases, outputStream);
            if (config.getMavenConfig().isDeployArtifacts()) {
                for (RepositoryRelease release : releases) {
                    releaseDeploy(config, release, outputStream);
                }
            } else {
                log.info("Skipping release-deploy due to maven config: deployArtifacts = false");
                // todo - wait for artifact to get deployed by github/gitlab workflows?
            }
        }
    }

    void pushChanges(Config config, List<RepositoryRelease> releases, OutputStream outputStream) {
        RepositoryInfo repositoryInfo = releases.getFirst().getRepository();
        Path repositoryDirPath = Paths.get(config.getBaseDir(), repositoryInfo.getDir());
        Set<String> tags = releases.stream().map(RepositoryRelease::getVersionTag).map(VersionTag::tag).collect(Collectors.toSet());
        PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(outputStream, UTF_8));
        try (Git git = Git.open(repositoryDirPath.toFile())) {
            List<Ref> tagRefs = git.tagList().call().stream()
                    .filter(t -> tags.stream().anyMatch(tag -> t.getName().equals(String.format("refs/tags/%s", tag))))
                    .toList();
            if (tagRefs.isEmpty()) {
                throw new IllegalStateException(String.format("git tags: %s not found", tags));
            }
            String branch = git.getRepository().getFullBranch();
            RefSpec branchRef = Optional.of(branch).map(b -> new RefSpec(b + ":" + b)).get();
            List<RefSpec> tagRefSpecs = tagRefs.stream().map(Ref::getName).map(t -> new RefSpec(t + ":" + t)).toList();

            Iterable<PushResult> pushResults = git.push()
                    .setProgressMonitor(new TextProgressMonitor(printWriter))
                    .setCredentialsProvider(config.getGitConfig().getCredentialsProvider())
                    .setRefSpecs(Stream.concat(Stream.of(branchRef), tagRefSpecs.stream()).toList())
                    .call();

            List<PushResult> results = StreamSupport.stream(pushResults.spliterator(), false).toList();
            List<RemoteRefUpdate> failedUpdates = results.stream().flatMap(r -> r.getRemoteUpdates().stream()).filter(r -> r.getStatus() != RemoteRefUpdate.Status.OK).toList();
            if (!failedUpdates.isEmpty()) {
                throw new IllegalStateException("Failed to push: " + failedUpdates.stream().map(RemoteRefUpdate::toString).collect(Collectors.joining("\n")));
            }
            printWriter.println("""
                    Pushed to git: branch: %s
                    tags:
                    %s""".formatted(branch, String.join("\n", tags)));
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(e);
        } finally {
            // do not close because we want
            printWriter.flush();
        }
        releases.forEach(release -> release.setPushedToGit(true));
    }

    void releaseDeploy(Config config, RepositoryRelease release, OutputStream outputStream) throws Exception {
        RepositoryInfo repositoryInfo = release.getRepository();
        PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(outputStream, UTF_8));
        try {
            Path repositoryDirPath = Paths.get(config.getBaseDir(), repositoryInfo.getDir(), repositoryInfo.getPomFolder());
            List<String> arguments = new ArrayList<>();
            arguments.add("-DskipTests");
            arguments.add("-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath());
            if (config.getMavenConfig().getAltDeploymentRepository() != null) {
                arguments.add("-DaltDeploymentRepository=" + config.getMavenConfig().getAltDeploymentRepository());
            }
            String argsString = String.join(" ", arguments);
            List<String> cmd = Stream.of("mvn", "-B", "release:perform",
                            "-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath(),
                            "-DlocalCheckout=true",
                            "-DautoVersionSubmodules=true",
                            warpPropertyInQuotes(String.format("-Darguments=%s", argsString)))
                    .collect(Collectors.toList());
            printWriter.println(String.format("Repository: %s\nCmd: '%s' started", repositoryInfo.getUrl(), String.join(" ", cmd)));

            ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
            Optional.ofNullable(release.getJavaVersion()).map(v -> config.getJavaVersionToJavaHomeEnv().get(v))
                    .ifPresent(javaHome -> processBuilder.environment().put("JAVA_HOME", javaHome));
            // maven envs
            if (config.getMavenConfig().getUser() != null && config.getMavenConfig().getPassword() != null) {
                processBuilder.environment().put("MAVEN_USER", config.getMavenConfig().getUser());
                processBuilder.environment().put("MAVEN_TOKEN", config.getMavenConfig().getPassword());
            }
            Process process = processBuilder.start();
            process.getInputStream().transferTo(outputStream);
            process.getErrorStream().transferTo(outputStream);
            process.waitFor();
            printWriter.println(String.format("Repository: %s\nCmd: '%s' ended with code: %d",
                    repositoryInfo.getUrl(), String.join(" ", cmd), process.exitValue()));
            if (process.exitValue() != 0) {
                throw new RuntimeException("Failed to execute cmd");
            }
            release.setDeployed(true);
        } finally {
            printWriter.flush();
        }
    }

    String generateDotFile(Map<Integer, List<RepositoryInfo>> dependencyGraph) {
        Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);
        List<RepositoryInfo> repositoryInfoList = dependencyGraph.values().stream().flatMap(Collection::stream).toList();
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            graph.addVertex(repositoryInfo.graphEdge());
        }
        RepositoryInfoLinker linker = new RepositoryInfoLinker(repositoryInfoList);
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            linker.getRepositoriesUsedByThis(repositoryInfo)
                    .stream()
                    .filter(ri -> dependencyGraph.values().stream()
                            .flatMap(Collection::stream).anyMatch(ri2 -> Objects.equals(ri2.getUrl(), ri.getUrl())))
                    .forEach(ri -> graph.addEdge(ri.graphEdge(), repositoryInfo.graphEdge()));
        }
        Function<String, String> vertexIdProvider = vertex -> {
            int level = dependencyGraph.entrySet().stream()
                    .filter(e -> e.getValue().stream().anyMatch(ri -> Objects.equals(ri.graphEdge(), vertex)))
                    .mapToInt(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new IllegalStateException(String.format("Failed to find level for vertex: %s", vertex)));
            List<RepositoryInfo> repositoryInfos = dependencyGraph.get(level);
            int index = IntStream.range(0, repositoryInfos.size())
                    .boxed()
                    .filter(i -> repositoryInfos.get(i).graphEdge().equals(vertex))
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
}
