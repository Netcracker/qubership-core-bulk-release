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
                            String.join("\n", reposInfoList.stream().map(RepositoryConfig::getUrl).toList()));
                }).toList()));

        List<RepositoryRelease> allReleases = dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey() + 1;
            List<RepositoryInfo> reposInfoList = entry.getValue();
            log.info("Running 'prepare' - processing level {}/{}, {} repositories:\n{}", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream().map(RepositoryConfig::getUrl).toList()));
            int threads = Math.min(config.getRunParallelism(), reposInfoList.size());
            try (ExecutorService executorService = Executors.newFixedThreadPool(threads)) {
                Set<GAV> gavList = dependenciesGavs.entrySet().stream()
                        .map(e -> new GAV(e.getKey().getGroupId(), e.getKey().getArtifactId(), e.getValue()))
                        .collect(Collectors.toSet());
                List<RepositoryRelease> releases = reposInfoList.stream()
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
                        .toList()
                        .stream()
                        .map(future -> {
                            RepositoryInfo repositoryInfo = future.getObject();
                            Path repoLogDirPath = logsFolderPath.resolve(repositoryInfo.getDir());
                            Path repoLogFilePath = repoLogDirPath.resolve("prepare.log");
                            try (PipedInputStream pipedInputStream = future.getPipedInputStream();
                                 BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
                                if (!Files.exists(repoLogDirPath)) {
                                    Files.createDirectories(repoLogDirPath);
                                }
                                Files.writeString(repoLogFilePath, "", StandardCharsets.UTF_8,
                                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                log.info("Started 'prepare' process for repository '{}'.\nFor details see log file: {}",
                                        repositoryInfo.getUrl(), repoLogFilePath);
                                String line;
                                int iterations = 0;
                                while ((line = reader.readLine()) != null) {
                                    Files.writeString(repoLogFilePath, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                                    if (++iterations % 100 == 0) {
                                        System.out.printf("%d x 100 log lines forwarded%n", iterations / 100);
                                    }
                                }
                                RepositoryRelease repositoryRelease = future.getFuture().get();
                                log.info("Finished 'prepare' process for repository '{}'.\nFor details see log file: {}",
                                        repositoryInfo.getUrl(), repoLogFilePath);
                                return repositoryRelease;
                            } catch (Exception e) {
                                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                                try {
                                    Files.readAllLines(repoLogFilePath).forEach(log::error);
                                } catch (IOException ioe) {
                                    log.error("Failed to read log file: {}", repoLogFilePath, ioe);
                                }
                                log.error("'prepare' process for repository '{}' has failed. Error: {}.\nFor details see log content above",
                                        repositoryInfo.getUrl(), e.getMessage());
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
            dependencyGraph.forEach((level, repos) -> {
                int threads = Math.min(config.getRunParallelism(), repos.size());
                log.info("Running 'perform' - processing level {}/{}, {} repositories:\n{}", level + 1, dependencyGraph.size(), threads,
                        String.join("\n", repos.stream().map(RepositoryConfig::getUrl).toList()));
                try (ExecutorService executorService = Executors.newFixedThreadPool(threads)) {
                    allReleases.stream()
                            .filter(release -> repos.stream().map(RepositoryConfig::getUrl)
                                    .anyMatch(repo -> Objects.equals(repo, release.getRepository().getUrl())))
                            .map(release -> {
                                try {
                                    PipedOutputStream out = new PipedOutputStream();
                                    PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
                                    Future<RepositoryRelease> future = executorService.submit(() -> performRelease(config, release, out));
                                    return new TraceableFuture<>(future, pipedInputStream, release);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .toList()
                            .forEach(future -> {
                                RepositoryRelease repositoryRelease = future.getObject();
                                Path repoLogDirPath = logsFolderPath.resolve(repositoryRelease.getRepository().getDir());
                                Path repoLogFilePath = repoLogDirPath.resolve("perform.log");
                                try (PipedInputStream pipedInputStream = future.getPipedInputStream();
                                     BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
                                    if (!Files.exists(repoLogDirPath)) {
                                        Files.createDirectories(repoLogDirPath);
                                    }
                                    Files.writeString(repoLogFilePath, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                    log.info("Started 'perform' process for repository '{}'.\nFor details see log file: {}",
                                            repositoryRelease.getRepository().getUrl(), repoLogFilePath);
                                    String line;
                                    int iterations = 0;
                                    while ((line = reader.readLine()) != null) {
                                        Files.writeString(repoLogFilePath, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                                        if (++iterations % 100 == 0) {
                                            System.out.printf("%d x 100 log lines forwarded%n", iterations / 100);
                                        }
                                    }
                                    future.getFuture().get();
                                    log.info("Finished 'perform' process for repository '{}'.\nFor details see log file: {}",
                                            repositoryRelease.getRepository().getUrl(), repoLogFilePath);
                                } catch (Exception e) {
                                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                                    try {
                                        Files.readAllLines(repoLogFilePath).forEach(log::error);
                                    } catch (IOException ioe) {
                                        log.error("Failed to read log file: {}", repoLogFilePath, ioe);
                                    }
                                    log.error("'perform' process for repository '{}' has failed. Error: {}.\nFor details see log content above",
                                            repositoryRelease.getRepository().getUrl(), e.getMessage());
                                    throw new RuntimeException(e);
                                }
                            });
                }
            });
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
            String releaseVersion = repository.calculateReleaseVersion(versionIncrementType);
            return releasePrepare(repository, config, releaseVersion, javaVersion, outputStream);
        }
    }

    void updateDependencies(RepositoryInfo repositoryInfo, Collection<GAV> dependencies) {
        repositoryInfo.updateDepVersions(dependencies);
        // check all versions were updated
        Set<GAV> updatedModuleDependencies = repositoryInfo.getModuleDependencies();
        Set<GAV> missedDependencies = updatedModuleDependencies.stream()
                .filter(gav -> {
                    Optional<GAV> foundGav = dependencies.stream()
                            .filter(dGav -> Objects.equals(gav.getGroupId(), dGav.getGroupId()) &&
                                            Objects.equals(gav.getArtifactId(), dGav.getArtifactId()))
                            .findFirst();
                    if (foundGav.isEmpty()) return false;
                    GAV g = foundGav.get();
                    return !Objects.equals(gav.getVersion(), g.getVersion());
                })
                .collect(Collectors.toSet());
        if (!missedDependencies.isEmpty()) {
            throw new RuntimeException("Failed to update dependencies: " + missedDependencies.stream().map(GAV::toString).collect(Collectors.joining("\n")));
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

    RepositoryRelease releasePrepare(RepositoryInfo repositoryInfo, Config config, String releaseVersion,
                                     String javaVersion, OutputStream outputStream) throws Exception {
        Path repositoryDirPath = Paths.get(repositoryInfo.getBaseDir(), repositoryInfo.getDir());
        List<String> arguments = new ArrayList<>();
        arguments.add("-Dmaven.repo.local=" + config.getMavenConfig().getLocalRepositoryPath());
        if (repositoryInfo.isSkipTests() || config.isSkipTests()) {
            arguments.add("-DskipTests=true");
        } else {
            arguments.add("-Dsurefire.rerunFailingTestsCount=1");
        }
        String tag = releaseVersion;
        List<String> cmd = List.of("mvn", "-B", "release:prepare",
                "-Dresume=true",
                "-DautoVersionSubmodules=true",
                "-DreleaseVersion=" + releaseVersion,
                "-DpushChanges=false",
                "-Dtag=" + tag,
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
            release.setReleaseVersion(releaseVersion);
            release.setTag(tag);
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

    RepositoryRelease performRelease(Config config, RepositoryRelease release, OutputStream outputStream) throws Exception {
        try (outputStream) {
            RepositoryInfo repository = release.getRepository();
            pushChanges(config, repository, release, outputStream);
            releaseDeploy(repository, config, release, outputStream);
            return release;
        }
    }

    void pushChanges(Config config, RepositoryInfo repositoryInfo, RepositoryRelease release, OutputStream outputStream) {
        Path repositoryDirPath = Paths.get(config.getBaseDir(), repositoryInfo.getDir());
        String releaseVersion = release.getReleaseVersion();
        PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(outputStream, UTF_8));
        try (Git git = Git.open(repositoryDirPath.toFile())) {
            Optional<Ref> tagOpt = git.tagList().call().stream()
                    .filter(t -> t.getName().equals(String.format("refs/tags/%s", releaseVersion)))
                    .findFirst();
            if (tagOpt.isEmpty()) {
                throw new IllegalStateException(String.format("git tag: %s not found", releaseVersion));
            }
            String branch = git.getRepository().getFullBranch();
            RefSpec branchRef = Optional.of(branch).map(b -> new RefSpec(b + ":" + b)).get();
            RefSpec tagRefSpec = Optional.of(tagOpt.get().getName()).map(t -> new RefSpec(t + ":" + t)).get();

            Iterable<PushResult> pushResults = git.push()
                    .setProgressMonitor(new TextProgressMonitor(printWriter))
                    .setCredentialsProvider(config.getGitConfig().getCredentialsProvider())
                    .setRefSpecs(branchRef, tagRefSpec)
                    .call();

            List<PushResult> results = StreamSupport.stream(pushResults.spliterator(), false).toList();
            List<RemoteRefUpdate> failedUpdates = results.stream().flatMap(r -> r.getRemoteUpdates().stream()).filter(r -> r.getStatus() != RemoteRefUpdate.Status.OK).toList();
            if (!failedUpdates.isEmpty()) {
                throw new IllegalStateException("Failed to push: " + failedUpdates.stream().map(RemoteRefUpdate::toString).collect(Collectors.joining("\n")));
            }
            printWriter.println(String.format("Pushed to git: tag: %s", releaseVersion));
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(e);
        } finally {
            // do not close because we want
            printWriter.flush();
        }
        release.setPushedToGit(true);
    }

    void releaseDeploy(RepositoryInfo repositoryInfo, Config config,
                       RepositoryRelease release, OutputStream outputStream) throws Exception {
        PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(outputStream, UTF_8));
        try {
            Path repositoryDirPath = Paths.get(config.getBaseDir(), repositoryInfo.getDir());
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
}
