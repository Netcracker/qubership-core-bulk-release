package org.qubership.cloud.actions.maven;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
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
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class RepositoryService {

    enum Direction {
        BOTH, UP, DOWN;
    }

    static Pattern versionPattern = Pattern.compile(".*?(?<major>\\d+)\\.(?<minor>(\\d+|x))\\.(?<patch>(\\d+|x))(-SNAPSHOT)?$");
    static Pattern tagPattern = Pattern.compile(".*?(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)");

    public Map<Integer, List<RepositoryInfo>> buildDependencyGraph(String baseDir,
                                                                   GitConfig gitConfig,
                                                                   Set<RepositoryConfig> repositories,
                                                                   Set<RepositoryConfig> repositoriesToReleaseFrom) {
        log.info("Building dependency graph");
        BiFunction<Collection<RepositoryConfig>, Collection<RepositoryConfig>, Set<RepositoryConfig>> mergeFunction =
                (repos1, repos2) -> repos1.stream()
                        .map(repositoryToReleaseFrom -> repos2.stream()
                                .filter(repository -> Objects.equals(repository.getUrl(), repositoryToReleaseFrom.getUrl()))
                                .findFirst()
                                .map(repository -> {
                                    String repositoryBranch1 = repository.getBranch();
                                    String repositoryBranch2 = repositoryToReleaseFrom.getBranch();
                                    if (!Objects.equals(repositoryBranch1, repositoryBranch2)) {
                                        if (!Objects.equals(repositoryBranch2, RepositoryConfig.HEAD)) {
                                            return RepositoryConfig.builder(repository).branch(repositoryBranch2).build();
                                        } else {
                                            return RepositoryConfig.builder(repository).branch(repositoryBranch1).build();
                                        }
                                    }
                                    return repository;
                                }).orElse(repositoryToReleaseFrom))
                        .collect(Collectors.toSet());
        Set<RepositoryConfig> mergedRepositories = mergeFunction.apply(repositories, repositoriesToReleaseFrom);
        Set<RepositoryConfig> mergedRepositoriesToReleaseFrom = mergeFunction.apply(repositoriesToReleaseFrom, repositories);
        try (ExecutorService executorService = Executors.newFixedThreadPool(4)) {
            List<RepositoryInfo> repositoryInfoList = mergedRepositories.stream()
                    .map(rc -> {
                        try {
                            PipedOutputStream out = new PipedOutputStream();
                            PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
                            Future<RepositoryInfo> future = executorService.submit(() -> {
                                gitCheckout(baseDir, gitConfig, rc, out);
                                return new RepositoryInfo(rc, baseDir);
                            });
                            return new TraceableFuture<>(future, pipedInputStream, rc);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList()
                    .stream()
                    .map(future -> {
                        try (PipedInputStream pipedInputStream = future.getPipedInputStream();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                log.info(line);
                            }
                            return future.getFuture().get();
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }).toList();
            // create a set of repositories modules GAs
            Set<GA> repositoriesModulesGAs = repositoryInfoList.stream().flatMap(r -> r.getModules().stream()).collect(Collectors.toSet());

            // set repository dependencies
            RepositoryInfoLinker repositoryInfoLinker = new RepositoryInfoLinker(repositoryInfoList);

            // filter repositories which are not affected by 'released from' repositories
            List<RepositoryInfo> repositoryInfos = mergedRepositoriesToReleaseFrom.isEmpty() ? repositoryInfoList : repositoryInfoList.stream()
                    .filter(ri ->
                            mergedRepositoriesToReleaseFrom.stream().map(RepositoryConfig::getUrl).collect(Collectors.toSet()).contains(ri.getUrl()) ||
                            mergedRepositoriesToReleaseFrom.stream().anyMatch(riFrom -> repositoryInfoLinker.getRepositoriesUsedByThisFlatSet(ri).stream()
                                    .map(RepositoryConfig::getUrl).collect(Collectors.toSet()).contains(riFrom.getUrl())))
                    .toList();

            Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);

            for (RepositoryInfo repositoryInfo : repositoryInfos) {
                graph.addVertex(repositoryInfo.getUrl());
            }
            for (RepositoryInfo repositoryInfo : repositoryInfos) {
                repositoryInfoLinker.getRepositoriesUsedByThisFlatSet(repositoryInfo)
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

    public Map<Integer, List<RepositoryInfo>> buildVersionedDependencyGraph(String baseDir, GitConfig gitConfig,
                                                                            MavenConfig mavenConfig,
                                                                            Set<RepositoryConfig> repositories,
                                                                            boolean createMissingBranches,
                                                                            OutputStream out) {
        log.info("Building versioned dependency graph");

        RepositoryConfig versionedRepository;
        List<RepositoryConfig> versionedRepositories = repositories.stream().filter(rc -> rc.getVersion() != null).toList();
        if (versionedRepositories.size() != 1) {
            throw new IllegalArgumentException("Only one versioned repository is allowed when building versioned dependency graph");
        } else if (!versionPattern.matcher(versionedRepositories.getFirst().getVersion()).matches()) {
            throw new IllegalArgumentException("Versioned repository version must match pattern: " + versionPattern.pattern());
        } else {
            versionedRepository = versionedRepositories.getFirst();
        }
        try (ExecutorService executorService = Executors.newFixedThreadPool(4)) {
            List<RepositoryInfo> repositoryInfoList = repositories.stream()
                    .map(rc -> {
                        try {
                            PipedOutputStream o = new PipedOutputStream();
                            PipedInputStream pipedInputStream = new PipedInputStream(o, 16384);
                            Future<RepositoryInfo> future = executorService.submit(() ->
                                    createRepositoryInfo(baseDir, gitConfig, rc, o));
                            return new TraceableFuture<>(future, pipedInputStream, rc);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList()
                    .stream()
                    .map(future -> {
                        try (PipedInputStream pipedInputStream = future.getPipedInputStream();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                log.info(line);
                            }
                            return future.getFuture().get();
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }).toList();
            // find support branches for all repositories starting from the versioned repository
            Map<String, String> processedVersionedRepositoryTree = new TreeMap<>();
            List<RepositoryInfo> resolvedSupportBranches = resolveSupportBranches(processedVersionedRepositoryTree,
                    versionedRepository, repositoryInfoList, baseDir, gitConfig, mavenConfig, createMissingBranches, out, Direction.BOTH);

            Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);

            for (RepositoryInfo repositoryInfo : resolvedSupportBranches) {
                graph.addVertex(repositoryInfo.getUrl());
            }
            RepositoryInfoLinker repositoryInfoLinker = new RepositoryInfoLinker(resolvedSupportBranches);
            for (RepositoryInfo repositoryInfo : resolvedSupportBranches) {
                repositoryInfoLinker.getRepositoriesUsedByThisFlatSet(repositoryInfo)
                        .stream()
                        .filter(ri -> resolvedSupportBranches.stream().anyMatch(riFrom -> Objects.equals(riFrom.getUrl(), ri.getUrl())))
                        .forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
            }
            List<RepositoryInfo> independentRepos = resolvedSupportBranches.stream()
                    .filter(ri -> graph.incomingEdgesOf(ri.getUrl()).isEmpty()).toList();
            List<RepositoryInfo> dependentRepos = resolvedSupportBranches.stream()
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

    List<RepositoryInfo> resolveSupportBranches(Map<String, String> processedVersionedRepositoryTree,
                                                RepositoryConfig fromRepository, Collection<RepositoryInfo> repositories,
                                                String baseDir, GitConfig gitConfig, MavenConfig mavenConfig, boolean createMissingBranches,
                                                OutputStream out, Direction direction) {
        String branch = resolveSupportBranch(baseDir, gitConfig, mavenConfig, fromRepository, createMissingBranches, out);
        // check if we already processed the repository for this branch
        if (processedVersionedRepositoryTree.putIfAbsent(fromRepository.getUrl(), branch) != null) {
            return List.of();
        }
        RepositoryConfig repositoryConfig = RepositoryConfig.builder(fromRepository).branch(branch).version(null).build();
        RepositoryInfo repositoryInfo = this.createRepositoryInfo(baseDir, gitConfig, repositoryConfig, out);

        List<RepositoryInfo> updatedRepositories = repositories.stream().map(ri -> {
            if (Objects.equals(ri.getUrl(), repositoryInfo.getUrl())) {
                return repositoryInfo;
            } else {
                return ri;
            }
        }).toList();
        RepositoryInfoLinker linker = new RepositoryInfoLinker(updatedRepositories);
        List<RepositoryInfo> result = new ArrayList<>();
        if (direction == Direction.BOTH || direction == Direction.UP) { // todo remove if
            List<RepositoryInfo> parents = linker.getRepositoriesUsingThis(repositoryInfo);
            result.addAll(parents.stream().flatMap(parent -> {
                Set<GAV> gavs = repositoryInfo.getModules().stream()
                        .filter(module -> parent.getModuleDependencies().stream().map(GAV::toGA).anyMatch(module.toGA()::equals))
                        .map(gav -> {
                            String version = gav.getVersion();
                            MavenVersion mavenVersion = new MavenVersion(version);
                            if (mavenVersion.getSuffix() != null && mavenVersion.getSuffix().equals("-SNAPSHOT")) {
                                mavenVersion.setSuffix("");
                                mavenVersion.update(VersionIncrementType.PATCH, mavenVersion.getPatch() - 1);
                                return new GAV(gav.getGroupId(), gav.getArtifactId(), mavenVersion.toString());
                            }
                            return gav;
                        })
                        .collect(Collectors.toSet());
                String version = findParentRepositoryVersion(baseDir, gitConfig, parent, gavs, out);
                RepositoryConfig parentRepositoryConfig = RepositoryConfig.builder(parent).version(version).branch(null).build();
                return resolveSupportBranches(processedVersionedRepositoryTree,
                        parentRepositoryConfig, updatedRepositories, baseDir, gitConfig, mavenConfig, createMissingBranches, out, Direction.BOTH).stream();
            }).toList());
        }
        result.add(repositoryInfo);
        if (direction == Direction.BOTH || direction == Direction.DOWN) { // todo remove if
            List<RepositoryInfo> children = linker.getRepositoriesUsedByThis(repositoryInfo);
            result.addAll(children.stream().flatMap(child -> {
                String version = repositoryInfo.getModuleDependencies().stream()
                        .filter(module -> child.getModules().stream().map(GAV::toGA).anyMatch(module.toGA()::equals))
                        .findFirst()
                        .map(GAV::getVersion)
                        .orElse(null);
                if (version == null) {
                    throw new IllegalStateException("No version found for repository: " + child.getUrl());
                }
                RepositoryConfig childRepositoryConfig = RepositoryConfig.builder(child).version(version).branch(null).build();
                return resolveSupportBranches(processedVersionedRepositoryTree,
                        childRepositoryConfig, updatedRepositories, baseDir, gitConfig, mavenConfig, createMissingBranches, out, Direction.BOTH).stream();
            }).toList());
        }
        return result.stream()
                .collect(Collectors.toMap(RepositoryConfig::getUrl, r -> r, (r1, r2) -> {
                    Matcher tagMatcher1 = versionPattern.matcher(r1.getBranch());
                    Matcher tagMatcher2 = versionPattern.matcher(r2.getBranch());
                    if (!tagMatcher1.matches() || !tagMatcher2.matches()) {
                        throw new IllegalArgumentException("Invalid branches: " + Stream.of(r1, r2).map(RepositoryInfo::getBranch).toList());
                    }
                    String major1 = tagMatcher1.group("major");
                    String minor1 = tagMatcher1.group("minor");
                    String patch1 = tagMatcher1.group("patch");
                    String major2 = tagMatcher2.group("major");
                    String minor2 = tagMatcher2.group("minor");
                    String patch2 = tagMatcher2.group("patch");
                    int majorComparison = Integer.compare(Integer.parseInt(major1), Integer.parseInt(major2));
                    if (majorComparison != 0) {
                        return majorComparison > 0 ? r1 : r2;
                    }
                    if (minor1.equals("x")) {
                        return r1;
                    } else if (minor2.equals("x")) {
                        return r2;
                    } else {
                        int minorComparison = Integer.compare(Integer.parseInt(minor1), Integer.parseInt(minor2));
                        if (minorComparison != 0) {
                            return minorComparison > 0 ? r1 : r2;
                        }
                        if (patch1.equals("x")) {
                            return r1;
                        } else if (patch2.equals("x")) {
                            return r2;
                        } else {
                            return Integer.parseInt(patch1) > Integer.parseInt(patch2) ? r1 : r2;
                        }
                    }
                }))
                .values().stream().toList();
    }

    String findParentRepositoryVersion(String baseDir, GitConfig gitConf, RepositoryConfig versionedRepository,
                                       Collection<GAV> mustHaveDependenciesGAVs, OutputStream out) {
        Path repositoryDirPath = Paths.get(baseDir, versionedRepository.getDir());
        if (!Files.exists(repositoryDirPath)) {
            throw new IllegalArgumentException(String.format("Repository directory '%s' does not exist", repositoryDirPath));
        }
        try {
            try (Git git = Git.open(repositoryDirPath.toFile())) {
                List<String> tags = git.tagList().call().stream()
                        .map(tag -> tagPattern.matcher(tag.getName()))
                        .filter(Matcher::matches)
                        .sorted((tagMatcher1, tagMatcher2) ->
                                Comparator.<Matcher, MavenVersion>comparing(m -> new MavenVersion(
                                                Integer.parseInt(m.group("major")),
                                                Integer.parseInt(m.group("minor")),
                                                Integer.parseInt(m.group("patch"))))
                                        .reversed()
                                        .compare(tagMatcher1, tagMatcher2))
                        .map(Matcher::group)
                        .toList();
                for (String tag : tags) {
                    RepositoryConfig repositoryConfig = RepositoryConfig.builder(versionedRepository).branch(tag).version(null).build();
                    RepositoryInfo repositoryInfo = createRepositoryInfo(baseDir, gitConf, repositoryConfig, out);
                    if (repositoryInfo.getModuleDependencies().stream()
                            .filter(d -> mustHaveDependenciesGAVs.stream().anyMatch(gav -> gav.toGA().equals(d.toGA())))
                            .anyMatch(dependency -> {
                                MavenVersion dependencyVersion = new MavenVersion(dependency.getVersion());
                                return mustHaveDependenciesGAVs.stream()
                                        .filter(gav -> gav.toGA().equals(dependency.toGA()))
                                        .map(gav -> new MavenVersion(gav.getVersion()))
                                        .allMatch(mv -> dependencyVersion.compareTo(mv) <= 0);
                            })) {
                        Optional<String> optionalVersion = repositoryInfo.getModules().stream().findFirst().map(GAV::getVersion);
                        if (optionalVersion.isEmpty()) {
                            throw new IllegalStateException("No version found for repository: " + repositoryInfo.getUrl());
                        }
                        return optionalVersion.get();
                    }
                }
                throw new IllegalStateException(String.format("No version found for repository: %s which dependencies satisfy gavs: %s",
                        versionedRepository.getUrl(), mustHaveDependenciesGAVs.stream().map(GAV::toString).collect(Collectors.joining(","))));
            }
        } catch (Exception ioe) {
            throw new RuntimeException(ioe);
        }
    }

    String resolveSupportBranch(String baseDir, GitConfig gitConf, MavenConfig mavenConfig, RepositoryConfig versionedRepository,
                                boolean createMissingBranches, OutputStream out) {
        String version = versionedRepository.getVersion();
        Matcher matcher;
        if (version == null || !(matcher = versionPattern.matcher(version)).matches()) {
            throw new IllegalArgumentException("Versioned repository must have version which matches pattern: " + versionPattern.pattern());
        }
        String major = matcher.group("major");
        String minor = matcher.group("minor");
        try (out) {
            Path repositoryDirPath = Paths.get(baseDir, versionedRepository.getDir());
            if (!Files.exists(repositoryDirPath)) {
                throw new IllegalArgumentException(String.format("Repository directory '%s' does not exist", repositoryDirPath));
            }
            try (Git git = Git.open(repositoryDirPath.toFile())) {
                List<Ref> branches = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
                String branch;
                String branchNamePatch = String.format("support/%s.%s.x", major, minor);
                Optional<Ref> optionalBranch = branches.stream()
                        .filter(ref -> ref.getName().endsWith(branchNamePatch))
                        .findFirst();
                if (optionalBranch.isEmpty()) {
                    String branchNameMinor = String.format("support/%s.x.x", major);
                    optionalBranch = branches.stream()
                            .filter(ref -> ref.getName().endsWith(branchNameMinor))
                            .findFirst();
                    if (optionalBranch.isEmpty() || !checkVersions(branchNameMinor, major, minor, baseDir, gitConf, versionedRepository, out)) {
                        Pattern tagPattern = Pattern.compile(String.format(".*(?<major>%s)\\.(?<minor>%s)\\.(?<patch>\\d+)", major, minor));
                        List<String> tags = git.tagList().call().stream()
                                .map(tag -> tagPattern.matcher(tag.getName()))
                                .filter(Matcher::matches)
                                .sorted((tagMatcher1, tagMatcher2) ->
                                        Comparator.<Matcher>comparingInt(m -> Integer.parseInt(m.group("major")))
                                                .thenComparingInt(m -> Integer.parseInt(m.group("minor")))
                                                .thenComparingInt(m -> Integer.parseInt(m.group("patch")))
                                                .compare(tagMatcher1, tagMatcher2))
                                .map(Matcher::group)
                                .toList();
                        if (tags.isEmpty()) {
                            throw new IllegalStateException(String.format("No tag found for versioned repository [%s] matching tagPattern: '%s'",
                                    versionedRepository.getUrl(), tagPattern));
                        } else {
                            branch = optionalBranch.isEmpty() ? branchNameMinor : branchNamePatch;
                            String lastTag = tags.getLast();
                            git.branchCreate()
                                    .setName(branch)
                                    .setStartPoint(lastTag)
                                    .call();
                            if (createMissingBranches) {
                                // need to increment 'patch' version and append '-SNAPSHOT' suffix to the module's version tags because in the tags versions are the released ones
                                RepositoryConfig repositoryConfig = RepositoryConfig.builder(versionedRepository).branch(branch).version(null).build();
                                RepositoryInfo repositoryInfo = createRepositoryInfo(baseDir, gitConf, repositoryConfig, out);
                                String v = repositoryInfo.getModules().stream().map(GAV::getVersion).toList().getFirst();
                                MavenVersion mavenVersion = new MavenVersion(v);
                                mavenVersion.update(VersionIncrementType.PATCH, mavenVersion.getPatch() + 1);
                                mavenVersion.setSuffix("-SNAPSHOT");
                                preparePOMsForDev(baseDir, repositoryInfo, mavenConfig, mavenVersion, out);
                                List<DiffEntry> diff = git.diff().call();
                                List<String> modifiedFiles = diff.stream()
                                        .filter(d -> d.getChangeType() == DiffEntry.ChangeType.MODIFY)
                                        .map(DiffEntry::getNewPath)
                                        .collect(Collectors.toList());
                                if (!modifiedFiles.isEmpty()) {
                                    // check if buildConfig.yaml is present
                                    String buildConfigFileName = "buildConfig.yaml";
                                    Path buildConfigPath = repositoryDirPath.resolve(buildConfigFileName);
                                    String buildConfigContent = "";
                                    AddCommand addCommand = git.add();
                                    if (!Files.exists(buildConfigPath) || !(buildConfigContent = Files.readString(buildConfigPath)).contains("type: library")) {
                                        YAMLMapper mapper = new YAMLMapper(new YAMLFactory().configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false));
                                        Map<String, Object> buildConfigMap;
                                        if (buildConfigContent.isBlank()) {
                                            buildConfigMap = Map.of("tools", Map.of("java", "J17", "maven", "M381"));
                                        } else {
                                            buildConfigMap = mapper.readValue(buildConfigContent, new TypeReference<>() {
                                            });
                                        }
                                        buildConfigMap = new LinkedHashMap<>(buildConfigMap) {{
                                            putFirst("type", "library");
                                        }};
                                        Files.write(buildConfigPath, mapper.writeValueAsBytes(buildConfigMap),
                                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                                        modifiedFiles.add(buildConfigFileName);
                                    }
                                    modifiedFiles.forEach(addCommand::addFilepattern);
                                    addCommand.call();
                                    String msg = "CPCAP-0000 updating poms versions for support branch created from the tag";
                                    git.commit().setMessage(msg).call();
                                    log.info("Commited '{}', changed files:\n{}", msg, String.join("\n", modifiedFiles));
                                    Iterable<PushResult> pushResults = git.push()
                                            .setRemote("origin")
                                            .add("refs/heads/" + branch)
                                            .call();
                                    List<PushResult> results = StreamSupport.stream(pushResults.spliterator(), false).toList();
                                    List<RemoteRefUpdate> failedUpdates = results.stream().flatMap(r -> r.getRemoteUpdates().stream()).filter(r -> r.getStatus() != RemoteRefUpdate.Status.OK).toList();
                                    if (!failedUpdates.isEmpty()) {
                                        throw new IllegalStateException("Failed to push: " + failedUpdates.stream().map(RemoteRefUpdate::toString).collect(Collectors.joining("\n")));
                                    }
                                    log.info("Pushed '{}'. Results: ", msg);
                                }
                            }
                        }
                    } else {
                        branch = branchNameMinor;
                    }
                } else {
                    branch = branchNamePatch;
                }
                log.info("Resolved support branch '{}' for repository: '{}'", branch, versionedRepository.getUrl());
                return branch;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void preparePOMsForDev(String baseDir, RepositoryInfo repositoryInfo, MavenConfig mavenConfig,
                           MavenVersion targetVersion, OutputStream outputStream) throws Exception {
        Path repositoryDirPath = Paths.get(baseDir, repositoryInfo.getDir());
        List<String> cmd = Stream.of("mvn", "-B", "release:update-versions",
                        "-Dmaven.repo.local=" + mavenConfig.getLocalRepositoryPath(),
                        "-DdevelopmentVersion=" + targetVersion.toString())
                .collect(Collectors.toList());

        log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));

        ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
        // maven envs
        if (mavenConfig.getUser() != null && mavenConfig.getPassword() != null) {
            processBuilder.environment().put("MAVEN_USER", mavenConfig.getUser());
            processBuilder.environment().put("MAVEN_TOKEN", mavenConfig.getPassword());
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
    }

    boolean checkVersions(String branchName, String major, String minor, String baseDir, GitConfig gitConf, RepositoryConfig versionedRepository, OutputStream out) {
        RepositoryConfig repositoryConfig = RepositoryConfig.builder(versionedRepository).branch(branchName).version(null).build();
        RepositoryInfo repositoryInfo = this.createRepositoryInfo(baseDir, gitConf, repositoryConfig, out);
        return repositoryInfo.getModules().stream().allMatch(gav -> {
            Matcher versionMatcher = versionPattern.matcher(gav.getVersion());
            if (!versionMatcher.matches()) {
                throw new IllegalStateException("Invalid version: " + gav.getVersion() + " for module: " + gav.toGA() + " in repository: " + versionedRepository.getUrl());
            }
            String majorFromModule = versionMatcher.group("major");
            String minorFromModule = versionMatcher.group("minor");
            return Objects.equals(majorFromModule, major) && Objects.equals(minorFromModule, minor);
        });
    }

    RepositoryInfo createRepositoryInfo(String baseDir, GitConfig gitConf, RepositoryConfig repositoryConfig, OutputStream out) {
        gitCheckout(baseDir, gitConf, repositoryConfig, out);
        return new RepositoryInfo(repositoryConfig, baseDir);
    }

    void gitCheckout(String baseDir, GitConfig gitConf, RepositoryConfig repository, OutputStream out) {
        try (out) {
            Path repositoryDirPath = Paths.get(baseDir, repository.getDir());
            boolean repositoryDirExists = Files.exists(repositoryDirPath);
            Git git;
            String branch = repository.getBranch();
            if (repositoryDirExists && Files.list(repositoryDirPath).findAny().isPresent()) {
                git = Git.open(repositoryDirPath.toFile());
                try {
                    git.checkout().setForced(true).setName(branch).call();
                } catch (RefNotFoundException e) {
                    git.checkout().setForced(true).setName("origin/" + branch).call();
                }
            } else {
                PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(out, UTF_8));
                try {
                    printWriter.println(String.format("Checking out %s from: [%s]", repository.getUrl(), branch));
                    Files.createDirectories(repositoryDirPath);

                    git = Git.cloneRepository()
                            .setCredentialsProvider(gitConf.getCredentialsProvider())
                            .setURI(repository.getUrl())
                            .setDirectory(repositoryDirPath.toFile())
                            .setDepth(1)
                            .setBranch(branch)
                            .setCloneAllBranches(false)
                            .setTagOption(TagOpt.FETCH_TAGS)
                            .setProgressMonitor(new TextProgressMonitor(printWriter))
                            .call();
                } finally {
                    printWriter.flush();
                }
            }
            try (git; org.eclipse.jgit.lib.Repository rep = git.getRepository()) {
                StoredConfig gitConfig = rep.getConfig();
                gitConfig.setString("user", null, "name", gitConf.getUsername());
                gitConfig.setString("user", null, "email", gitConf.getEmail());
                gitConfig.setString("credential", null, "helper", "store");
                gitConfig.save();
                log.debug("Saved git config:\n{}", gitConfig.toText());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
