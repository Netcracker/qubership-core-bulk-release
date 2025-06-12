package org.qubership.cloud.actions.maven;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.TagOpt;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.nio.dot.DOTExporter;
import org.qubership.cloud.actions.maven.model.*;
import org.qubership.cloud.actions.maven.model.Repository;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class ReleaseRunner {
    static Pattern propertyPattern = Pattern.compile("\\$\\{(.*?)}");
    static YAMLMapper yamlMapper = new YAMLMapper();

    @SneakyThrows
    public Result release(Config config) {
        Result result = new Result();
        log.info("Config: {}", yamlMapper.writeValueAsString(config));
        // set up git creds if necessary
        setupGit(config);

        Map<GA, String> dependenciesGavs = config.getGavs().stream().map(GAV::new)
                .collect(Collectors.toMap(gav -> new GA(gav.getGroupId(), gav.getArtifactId()), GAV::getVersion));
        // build dependency graph
        Map<Integer, List<RepositoryInfo>> dependencyGraph = buildDependencyGraph(config);
        result.setDependencyGraph(dependencyGraph);
        String dot = generateDotFile(dependencyGraph);
        result.setDependenciesDot(dot);
        result.setDryRun(config.isDryRun());

        List<RepositoryRelease> allReleases = dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey() + 1;
            List<RepositoryInfo> reposInfoList = entry.getValue();
            log.info("Running 'prepare' - processing level {}/{}, {} repositories:\n{}", level, dependencyGraph.size(), reposInfoList.size(),
                    String.join("\n", reposInfoList.stream().map(Repository::getUrl).toList()));
            int threads = config.isRunSequentially() ? 1 : reposInfoList.size();
            AtomicInteger activeProcessCount = new AtomicInteger(0);
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
                        .peek(f -> activeProcessCount.incrementAndGet())
                        .toList()
                        .stream()
                        .map(future -> {
                            try (PipedInputStream pipedInputStream = future.getPipedInputStream()) {
                                pipedInputStream.transferTo(System.out);
                                return future.getFuture().get();
                            } catch (Exception e) {
                                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                                log.error("'prepare' process for repository '{}' has failed. Error: {}", future.getObject().getUrl(), e.getMessage());
                                throw new RuntimeException(e);
                            } finally {
                                log.info("Remaining 'prepare' processes: {}/{} at level {}/{}",
                                        activeProcessCount.decrementAndGet(), threads, level, dependencyGraph.size());
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
                int threads = config.isRunSequentially() ? 1 : repos.size();
                log.info("Running 'perform' - processing level {}/{}, {} repositories:\n{}", level + 1, dependencyGraph.size(), threads,
                        String.join("\n", repos.stream().map(Repository::getUrl).toList()));
                AtomicInteger activeProcessCount = new AtomicInteger(0);
                try (ExecutorService executorService = Executors.newFixedThreadPool(threads)) {
                    allReleases.stream()
                            .filter(release -> repos.stream().map(Repository::getUrl)
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
                            .peek(f -> activeProcessCount.incrementAndGet())
                            .toList()
                            .forEach(future -> {
                                try (PipedInputStream pipedInputStream = future.getPipedInputStream()) {
                                    pipedInputStream.transferTo(System.out);
                                    future.getFuture().get();
                                } catch (Exception e) {
                                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                                    log.error("'perform' process for repository '{}' has failed. Error: {}",
                                            future.getObject().getRepository().getUrl(), e.getMessage());
                                    throw new RuntimeException(e);
                                } finally {
                                    log.info("Remaining 'perform' processes: {}/{} at level {}/{}",
                                            activeProcessCount.decrementAndGet(), threads, level + 1, dependencyGraph.size());
                                }
                            });
                }
            });
        }
        result.setReleases(allReleases);
        return result;
    }

    Map<Integer, List<RepositoryInfo>> buildDependencyGraph(Config config) {
        log.info("Building dependency graph");
        String baseDir = config.getBaseDir();
        Set<RepositoryConfig> repositories = config.getRepositories();
        int threads = config.isRunSequentially() ? 1 : 4;
        config.getRepositoriesToReleaseFrom().forEach(repositoryToReleaseFrom -> repositories.stream()
                .filter(repository -> Objects.equals(repository.getUrl(), repositoryToReleaseFrom.getUrl()))
                .findFirst()
                .ifPresent(repository -> {
                    String repositoryBranch = repository.getBranch();
                    String repositoryToReleaseFromBranch = repositoryToReleaseFrom.getBranch();
                    if (!Objects.equals(repositoryBranch, repositoryToReleaseFromBranch)) {
                        if (!Objects.equals(repositoryToReleaseFromBranch, RepositoryConfig.HEAD)) {
                            repository.setBranch(repositoryToReleaseFromBranch);
                        } else {
                            repositoryToReleaseFrom.setBranch(repositoryBranch);
                        }
                    }
                }));
        try (ExecutorService executorService = Executors.newFixedThreadPool(threads)) {
            List<RepositoryInfo> repositoryInfoList = repositories.stream().map(RepositoryInfo::new)
                    .map(repositoryInfo -> executorService.submit(() -> {
                        gitCheckout(config, repositoryInfo);
                        List<PomHolder> poms = getPoms(baseDir, repositoryInfo);
                        resolveDependencies(repositoryInfo, poms);
                        return repositoryInfo;
                    })).toList()
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        } catch (ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                    }).toList();
            // create a set of repositories modules GAs
            Set<GA> repositoriesModulesGAs = repositoryInfoList.stream().flatMap(r -> r.getModules().stream()).collect(Collectors.toSet());

            // set repository dependencies
            for (RepositoryInfo repositoryInfo : repositoryInfoList.stream().filter(ri -> !ri.getModuleDependencies().isEmpty()).toList()) {
                Set<GA> moduleDependencies = repositoryInfo.getModuleDependencies().stream()
                        // allow only GA which is an actual module of some repositories
                        .filter(dependency -> repositoriesModulesGAs.stream().anyMatch(ga ->
                                Objects.equals(ga.getGroupId(), dependency.getGroupId()) &&
                                Objects.equals(ga.getArtifactId(), dependency.getArtifactId())))
                        .map(gav -> new GA(gav.getGroupId(), gav.getArtifactId()))
                        .collect(Collectors.toSet());
                repositoryInfo.getRepoDependencies().addAll(moduleDependencies.stream()
                        .flatMap(ga -> repositoryInfoList.stream().filter(ri -> ri.getModules().contains(ga)))
                        .filter(repo -> !Objects.equals(repo.getUrl(), repositoryInfo.getUrl()))
                        .collect(Collectors.toSet()));
            }

            Set<RepositoryConfig> repositoriesToReleaseFrom = config.getRepositoriesToReleaseFrom();
            // filter repositories which are not affected by 'released from' repositories
            List<RepositoryInfo> repositoryInfos = repositoriesToReleaseFrom.isEmpty() ? repositoryInfoList : repositoryInfoList.stream()
                    .filter(ri ->
                            repositoriesToReleaseFrom.stream().map(RepositoryConfig::getUrl).collect(Collectors.toSet()).contains(ri.getUrl()) ||
                            repositoriesToReleaseFrom.stream().anyMatch(riFrom -> ri.getRepoDependenciesFlatSet().stream()
                                    .map(Repository::getUrl).collect(Collectors.toSet()).contains(riFrom.getUrl())))
                    .toList();

            Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);

            for (RepositoryInfo repositoryInfo : repositoryInfos) {
                graph.addVertex(repositoryInfo.getUrl());
            }
            for (RepositoryInfo repositoryInfo : repositoryInfos) {
                repositoryInfo.getRepoDependenciesFlatSet()
                        .stream()
                        .filter(ri -> repositoryInfos.stream().anyMatch(riFrom -> Objects.equals(riFrom.getUrl(), ri.getUrl())))
                        .forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
            }
            List<RepositoryInfo> independentRepos = repositoryInfos.stream()
                    .filter(ri -> graph.incomingEdgesOf(ri.getUrl()).isEmpty()).toList();
            List<RepositoryInfo> dependentRepos = repositoryInfos.stream()
                    .filter(ri -> !graph.incomingEdgesOf(ri.getUrl()).isEmpty()).collect(Collectors.toList());
            Map<Integer, List<RepositoryInfo>> groupedReposMap = new TreeMap<>();
            groupedReposMap.put(0, independentRepos);
            int level = 1;
            while (!dependentRepos.isEmpty()) {
                List<RepositoryInfo> prevLevelRepos = IntStream.range(0, level).boxed().flatMap(lvl -> groupedReposMap.get(lvl).stream()).toList();
                List<RepositoryInfo> thisLevelRepos = dependentRepos.stream()
                        .filter(ri -> graph.incomingEdgesOf(ri.getUrl()).stream().map(StringEdge::getSource)
                                .allMatch(dependentRepoUrl -> prevLevelRepos.stream().map(RepositoryInfo::getUrl)
                                        .collect(Collectors.toSet()).contains(dependentRepoUrl))).toList();
                groupedReposMap.put(level, thisLevelRepos);
                dependentRepos.removeAll(thisLevelRepos);
                level++;
            }
            return groupedReposMap;
        }
    }

    void setupGit(Config config) {
        Pattern urlPattern = Pattern.compile("^https://(?<host>.+)(:\\d+)?$");
        Pattern gitHostCredsPattern = Pattern.compile("^https://(?<username>.+):(?<password>.+)@(?<host>.+)$");

        Path credentialsFilePath = Paths.get(System.getProperty("user.home"), "/.git-credentials");
        GitConfig gitConfig = config.getGitConfig();
        String url = gitConfig.getUrl();
        Matcher urlMatcher = urlPattern.matcher(url);
        if (!urlMatcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid git url: %s, must match pattern: %s", url, urlPattern.pattern()));
        }
        String host = urlMatcher.group("host");
        try {
            if (!Files.exists(credentialsFilePath)) {
                Files.createFile(credentialsFilePath);
            }
            List<String> lines = Files.readAllLines(credentialsFilePath);
            boolean updated = false;
            Optional<Matcher> existingHostMatcher = lines.stream()
                    .map(gitHostCredsPattern::matcher)
                    .filter(Matcher::matches)
                    .filter(matcher -> Objects.equals(matcher.group("host"), host))
                    .findFirst();
            if (existingHostMatcher.isPresent()) {
                Matcher existingGitHostMatcher = existingHostMatcher.get();
                String username = existingGitHostMatcher.group("username");
                String password = existingGitHostMatcher.group("password");
                gitConfig.setUsername(username);
                gitConfig.setPassword(password);
            } else {
                String username = gitConfig.getUsername();
                String password = gitConfig.getPassword();
                String newEntry = String.format("https://%s:%s@%s", username, password, host);
                lines.add(newEntry);
                updated = true;
            }
            if (updated) {
                Files.writeString(credentialsFilePath, String.join("\n", lines));
                log.info("Updated ~/.git-credentials.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void gitCheckout(Config config, RepositoryInfo repository) {
        Path repositoryDirPath = Paths.get(config.getBaseDir(), repository.getDir());
        boolean repositoryDirExists = Files.exists(repositoryDirPath);
        try {
            Git git;
            if (repositoryDirExists) {
                git = Git.open(repositoryDirPath.toFile());
            } else {
                log.info("Checking out {} from {}", repository.getUrl(), repository.getBranch());
                Files.createDirectories(repositoryDirPath);
                git = Git.cloneRepository()
                        .setCredentialsProvider(config.getCredentialsProvider())
                        .setURI(repository.getUrl())
                        .setDirectory(repositoryDirPath.toFile())
                        .setDepth(1)
                        .setBranch(repository.getBranch())
                        .setCloneAllBranches(false)
                        .setTagOption(TagOpt.FETCH_TAGS)
//                    .setProgressMonitor(new TextProgressMonitor(new PrintWriter(new OutputStreamWriter(System.out, UTF_8))))
                        .call();
            }
//            try (Stream<Path> pathStream = Files.walk(repositoryDirPath)) {
//                pathStream.sorted(Comparator.comparingInt(p -> p.toString()
//                                .replaceAll("[^/.]", "")
//                                .replace(".", "/").length()).reversed())
//                        .forEach(file -> {
//                            try {
//                                Files.delete(file);
//                            } catch (Exception e) {
//                                throw new RuntimeException(e);
//                            }
//                        });
//            }
            try (git; org.eclipse.jgit.lib.Repository rep = git.getRepository()) {
                StoredConfig gitConfig = rep.getConfig();
                gitConfig.setString("user", null, "name", config.getGitConfig().getUsername());
                gitConfig.setString("user", null, "email", config.getGitConfig().getEmail());
                gitConfig.setString("credential", null, "helper", "store");
                gitConfig.save();
                log.debug("Saved git config:\n{}", gitConfig.toText());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<PomHolder> getPoms(String baseDir, RepositoryInfo repositoryInfo) {
        Path repositoryDirPath = Paths.get(baseDir, repositoryInfo.getDir());
        List<PomHolder> poms = new ArrayList<>();
        try {
            Files.walkFileTree(repositoryDirPath, new FileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    List<String> pathList = Arrays.asList(file.toString().split("/"));
                    if (pathList.contains("pom.xml") && !pathList.contains("target")) {
                        String content = Files.readString(file);
                        PomHolder pomHolder = new PomHolder(content, file);
                        poms.add(pomHolder);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return poms.stream().peek(pom -> {
                    Parent parent = pom.getModel().getParent();
                    if (parent != null) {
                        String groupId = parent.getGroupId();
                        String artifactId = parent.getArtifactId();
                        poms.stream().filter(ph -> Objects.equals(ph.getGroupId(), groupId) && Objects.equals(ph.getArtifactId(), artifactId))
                                .findFirst().ifPresent(pom::setParent);
                    }
                })
                // start with leaf poms
                .sorted(Comparator.<PomHolder>comparingInt(p -> p.getParentsFlatList().size()).reversed())
                .toList();
    }

    void resolveDependencies(RepositoryInfo repositoryInfo, List<PomHolder> poms) {
        repositoryInfo.getModules().clear();
        repositoryInfo.getModuleDependencies().clear();
        try {
            for (PomHolder pomHolder : poms) {
                Model project = pomHolder.getModel();
                GA projectGA = pomGAFunction.apply(project);
                String projectGroupId = pomHolder.autoResolvePropReference(projectGA.getGroupId());
                String projectArtifactId = pomHolder.autoResolvePropReference(projectGA.getArtifactId());
                GA moduleGA = new GA(projectGroupId, projectArtifactId);
                repositoryInfo.getModules().add(moduleGA);
            }
            for (PomHolder pomHolder : poms) {
                Model project = pomHolder.getModel();
                List<Dependency> dependencyManagementNodes = Optional.ofNullable(project.getDependencyManagement())
                        .map(DependencyManagement::getDependencies)
                        .orElse(List.of());
                List<Dependency> dependenciesNodes = Optional.ofNullable(project.getDependencies()).orElse(List.of());
                List<Dependency> allDependenciesNodes = Stream.concat(dependencyManagementNodes.stream(), dependenciesNodes.stream()).toList();
                for (Dependency dependency : allDependenciesNodes) {
                    String groupId = pomHolder.autoResolvePropReference(dependency.getGroupId());
                    String artifactId = pomHolder.autoResolvePropReference(dependency.getArtifactId());
                    String version = pomHolder.autoResolvePropReference(dependency.getVersion());
                    if (Stream.of(groupId, artifactId, version).allMatch(Objects::nonNull)) {
                        GAV dependencyGAV = new GAV(groupId, artifactId, version);
                        repositoryInfo.getModuleDependencies().add(dependencyGAV);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    RepositoryRelease releasePrepare(Config config, RepositoryInfo repository, Collection<GAV> dependencies, OutputStream outputStream) throws Exception {
        try (outputStream) {
            String baseDir = config.getBaseDir();
            List<PomHolder> poms = getPoms(baseDir, repository);
            updateDependencies(baseDir, repository, poms, dependencies);
            String releaseVersion = calculateReleaseVersion(repository, poms, config);
            String javaVersion = calculateJavaVersion(poms);
            return releasePrepare(repository, config, releaseVersion, javaVersion, outputStream);
        }
    }

    String calculateReleaseVersion(RepositoryInfo repository, List<PomHolder> poms, Config config) throws Exception {
        Path releasePropsPath = Path.of(config.getBaseDir(), repository.getDir(), "release.properties");
        if (Files.exists(releasePropsPath)) {
            String content = Files.readString(releasePropsPath);
            if (content.contains("completedPhase=end-release")) {
                Pattern pattern = Pattern.compile("^project.rel[^:]+:[^=]+=(?<version>.+)$");
                // preparation was already performed, get a version from the file
                Set<String> versions = Arrays.stream(content.split("\n"))
                        .map(String::trim)
                        .filter(line -> !line.isBlank() && line.startsWith("project.rel."))
                        .map(pattern::matcher)
                        .filter(Matcher::matches)
                        .map(m -> m.group("version"))
                        .collect(Collectors.toSet());
                if (versions.size() != 1) {
                    throw new IllegalStateException("Multiple/no release versions found for maven project: " + versions);
                }
                return versions.iterator().next();
            }
        }
        Set<String> pomVersions = poms.stream().map(PomHolder::getVersion).collect(Collectors.toSet());
        if (pomVersions.size() != 1) {
            throw new IllegalArgumentException(String.format("pom.xml files from repository: %s have different versions: %s",
                    repository.getUrl(), String.join(",", pomVersions)));
        }
        String pomVersion = pomVersions.iterator().next();
        Pattern semverPattern = Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)(?<snapshot>-SNAPSHOT)?");
        Matcher matcher = semverPattern.matcher(pomVersion);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Non-semver version: %s. Must match pattern: '%s'", pomVersion, semverPattern.pattern()));
        }
        int major = Integer.parseInt(matcher.group("major"));
        int minor = Integer.parseInt(matcher.group("minor"));
        int patch = Integer.parseInt(matcher.group("patch"));
        switch (config.getVersionIncrementType()) {
            case MAJOR -> {
                major++;
                minor = 0;
                patch = 0;
            }
            case MINOR -> {
                minor++;
                patch = 0;
            }
            case PATCH -> {
                String snapshot = matcher.group("snapshot");
                if (snapshot == null) patch++;
            }
        }
        return String.format("%d.%d.%d", major, minor, patch);
    }

    String calculateJavaVersion(List<PomHolder> poms) {
        Set<String> propsToSearch = Set.of("maven.compiler.source", "maven.compiler.target", "maven.compiler.release", "java.version");
        // first search among plugins in poms
        Optional<String> versionFromPlugin = poms.stream().map(ph -> {
                    Map<String, String> props = Optional.ofNullable(ph.getModel().getBuild()).map(PluginContainer::getPlugins).orElse(List.of()).stream()
                            .filter(plugin -> plugin.getArtifactId().equals("maven-compiler-plugin") && plugin.getConfiguration() instanceof Xpp3Dom)
                            .flatMap(plugin -> {
                                Map<String, String> result = new HashMap<>();
                                Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
                                Optional.ofNullable(config.getChild("release"))
                                        .map(Xpp3Dom::getValue)
                                        .map(ph::autoResolvePropReference)
                                        .ifPresent(r -> result.put("release", r));
                                Optional.ofNullable(config.getChild("target"))
                                        .map(Xpp3Dom::getValue)
                                        .map(ph::autoResolvePropReference)
                                        .ifPresent(r -> result.put("target", r));
                                Optional.ofNullable(config.getChild("source"))
                                        .map(Xpp3Dom::getValue)
                                        .map(ph::autoResolvePropReference)
                                        .ifPresent(r -> result.put("source", r));
                                return result.entrySet().stream();
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (s1, s2) -> {
                                if (!Objects.equals(s1, s2)) {
                                    throw new IllegalStateException(String.format("Different java versions %s and %s specified for maven-compiler-plugin in pom: %s",
                                            s1, s2, String.format("%s:%s", ph.getGroupId(), ph.getArtifactId())));
                                } else {
                                    return s1;
                                }
                            }));
                    return props.getOrDefault("release", props.getOrDefault("target", props.get("source")));
                })
                .filter(Objects::nonNull)
                .findFirst();
        if (versionFromPlugin.isPresent()) {
            return versionFromPlugin.get();
        }
        Map<String, String> props = poms.stream()
                .flatMap(ph -> ph.getProperties().entrySet().stream())
                .filter(entry -> propsToSearch.contains(entry.getKey()))
                .collect(Collectors.toMap(entry -> entry.getKey()
                                .replace("maven.compiler.", "")
                                .replace("java.version", "release"), Map.Entry::getValue,
                        (s1, s2) -> {
                            if (!Objects.equals(s1, s2)) {
                                throw new IllegalStateException(String.format("Different java versions %s and %s specified in properties in poms: %s",
                                        s1, s2, String.join("\n", poms.stream()
                                                .map(ph -> String.format("%s:%s", ph.getGroupId(), ph.getArtifactId()))
                                                .toList())));
                            }
                            return s1;
                        }));
        return props.getOrDefault("release", props.getOrDefault("target", props.get("source")));
    }

    void updateDependencies(String baseDir, RepositoryInfo repositoryInfo, List<PomHolder> poms, Collection<GAV> dependencies) {
        updateDepVersions(repositoryInfo, poms, dependencies);
        // check all versions were updated
        List<PomHolder> updatedPoms = getPoms(baseDir, repositoryInfo);
        resolveDependencies(repositoryInfo, updatedPoms);
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
        commitUpdatedDependenciesIfAny(baseDir, repositoryInfo);
    }

    Function<Model, GA> pomGAFunction = pom -> {
        String groupId = pom.getArtifactId();
        String artifactId = Optional.ofNullable(pom.getGroupId()).orElseGet(() -> {
            // get groupIg from parent tag
            return Optional.ofNullable(pom.getParent()).map(Parent::getGroupId)
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("Invalid pom with attributeId: '%s' - no groupId or no parent",
                                    groupId)));
        });
        return new GA(artifactId, groupId);
    };

    void updateDepVersions(RepositoryInfo repositoryInfo, List<PomHolder> poms, Collection<GAV> dependencies) {
        Map<String, List<GAV>> propertiesToDependencies = new HashMap<>();
        Map<String, Set<PomHolder>> propertiesToPoms = new HashMap<>();
        BiConsumer<PomHolder, GAV> gavFunction = (holder, gav) -> {
            String groupId = gav.getGroupId();
            if (groupId == null) {
                return;
            }
            String artifactId = gav.getArtifactId();
            String version = gav.getVersion();
            GA dependencyGA = new GA(groupId, artifactId);
            GAV newGav = dependencies.stream()
                    // exclude our's own modules
                    .filter(dep -> repositoryInfo.getModules().stream()
                            .noneMatch(ga -> Objects.equals(ga.getGroupId(), dep.getGroupId()) &&
                                             Objects.equals(ga.getArtifactId(), dep.getArtifactId())))
                    .filter(dep -> dependencyGA.getGroupId().matches(dep.getGroupId()) &&
                                   dependencyGA.getArtifactId().matches(dep.getArtifactId()))
                    .findFirst().orElse(null);
            if (version != null && newGav != null) {
                newGav = new GAV(dependencyGA.getGroupId(), dependencyGA.getArtifactId(), newGav.getVersion());
                Matcher matcher = propertyPattern.matcher(version);
                if (matcher.matches()) {
                    String propertyName = matcher.group(1);
                    List<GAV> dependenciesList = propertiesToDependencies.computeIfAbsent(propertyName, k -> new ArrayList<>());
                    if (!dependenciesList.contains(newGav)) dependenciesList.add(newGav);
                } else {
                    // update a hard-coded version right away
                    holder.updateVersionInGAV(newGav);
                }
            }
        };
        Consumer<PomHolder> propFunction = (holder) -> holder.getProperties().forEach((propertyName, propertyValue) -> {
            if (propertiesToDependencies.containsKey(propertyName)) {
                propertiesToPoms.computeIfAbsent(propertyName, k -> new HashSet<>()).add(holder);
            }
        });
        poms.forEach(ph -> {
            ph.getGavs().forEach(gav -> gavFunction.accept(ph, gav));
            propFunction.accept(ph);
        });
        if (!propertiesToPoms.isEmpty()) {
            propertiesToPoms.forEach((propertyName, propertyNodes) -> {
                // make sure that property is referencing the same version for all found dependencies
                List<GAV> propGavs = propertiesToDependencies.get(propertyName);
                Map<String, Set<GAV>> versionToGavs = propGavs.stream().collect(Collectors.toMap(GAV::getVersion, Set::of,
                        (s1, s2) -> Stream.concat(s1.stream(), s2.stream()).collect(Collectors.toSet())));
                if (versionToGavs.size() != 1) {
                    throw new IllegalStateException(String.format("Invalid property '%s' - references by GAVs with different 'update to' versions: %s",
                            propertyName, versionToGavs));
                }
                String version = versionToGavs.keySet().iterator().next();
                // update property value
                propertyNodes.forEach(pom -> pom.updateProperty(propertyName, version));
            });
        }
        poms.forEach(pom -> {
            try {
                Files.writeString(pom.getPath(), pom.getPom(), StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    void commitUpdatedDependenciesIfAny(String baseDir, RepositoryInfo repository) {
        Path repositoryDirPath = Paths.get(baseDir, repository.getDir());
        try (Git git = Git.open(repositoryDirPath.toFile())) {
            List<DiffEntry> diff = git.diff().call();
            if (diff.stream().anyMatch(d -> d.getChangeType() == DiffEntry.ChangeType.MODIFY)) {
                git.add().setUpdate(true).call();
                git.commit().setMessage("updating dependencies before release").call();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    RepositoryRelease releasePrepare(RepositoryInfo repositoryInfo, Config config, String releaseVersion,
                                     String javaVersion, OutputStream outputStream) throws Exception {
        Path repositoryDirPath = Paths.get(config.getBaseDir(), repositoryInfo.getDir());
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
        if (log.isDebugEnabled()) {
            log.debug("Repository: {}\nCmd: '{}' started with env:\n{}", repositoryInfo.getUrl(), String.join(" ", cmd),
                    String.join("\n", processBuilder.environment().entrySet().stream()
                            .map(entry -> String.format("%s=%s", entry.getKey(), entry.getValue()))
                            .toList()));
        } else {
            log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));
        }
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        process.getInputStream().transferTo(outputStream);
        process.waitFor();
        log.info("Repository: {}\nCmd: '{}' ended with code: {}",
                repositoryInfo.getUrl(), String.join(" ", cmd), process.exitValue());
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
        try (Git git = Git.open(repositoryDirPath.toFile())) {
            Optional<Ref> tagOpt = git.tagList().call().stream()
                    .filter(t -> t.getName().equals(String.format("refs/tags/%s", releaseVersion)))
                    .findFirst();
            if (tagOpt.isEmpty()) {
                throw new IllegalStateException(String.format("git tag: %s not found", releaseVersion));
            }
            git.push()
                    .setProgressMonitor(new TextProgressMonitor(new PrintWriter(new OutputStreamWriter(outputStream, UTF_8))))
                    .setCredentialsProvider(config.getCredentialsProvider())
                    .setPushAll()
                    .setPushTags()
                    .call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        log.info("Pushed to git: tag: {}", releaseVersion);
        release.setPushedToGit(true);
    }

    void releaseDeploy(RepositoryInfo repositoryInfo, Config config,
                       RepositoryRelease release, OutputStream outputStream) throws Exception {
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
        log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));

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
        log.info("Repository: {}\nCmd: '{}' ended with code: {}",
                repositoryInfo.getUrl(), String.join(" ", cmd), process.exitValue());
        if (process.exitValue() != 0) {
            throw new RuntimeException("Failed to execute cmd");
        }
        release.setDeployed(true);
    }

    String generateDotFile(Map<Integer, List<RepositoryInfo>> dependencyGraph) {
        Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);
        List<RepositoryInfo> repositoryInfoList = dependencyGraph.values().stream().flatMap(Collection::stream).toList();
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            graph.addVertex(repositoryInfo.getUrl());
        }
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            repositoryInfo.getRepoDependencies()
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
