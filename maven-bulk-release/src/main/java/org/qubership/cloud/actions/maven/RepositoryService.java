package org.qubership.cloud.actions.maven;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
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

@Slf4j
public class RepositoryService {

    GitService gitService;

    public RepositoryService(GitService gitService) {
        this.gitService = gitService;
    }

    enum DependencyType {
        REFERENCES, DECLARED_IN
    }

    static Pattern versionPattern = Pattern.compile(".*?(?<major>\\d+)\\.(?<minor>(\\d+|x))\\.(?<patch>(\\d+|x))(-SNAPSHOT)?$");
    static Pattern supportBranchPattern = Pattern.compile(".*(?<branch>support/(?<major>\\d+)\\.(?<minor>\\d+|x)\\.(?<patch>\\d+|x))");

    public Map<Integer, List<RepositoryInfo>> buildDependencyGraph(String baseDir,
                                                                   GitConfig gitConfig,
                                                                   Set<RepositoryConfig> repositories,
                                                                   Set<RepositoryConfig> repositoriesToReleaseFrom) {
        log.info("Building dependency graph");
        BiFunction<Collection<RepositoryConfig>, Collection<RepositoryConfig>, List<RepositoryConfig>> mergeFunction =
                (repos1, repos2) -> repos1.stream()
                        .map(repositoryToReleaseFrom -> repos2.stream()
                                .filter(repository -> Objects.equals(repository.getUrl(), repositoryToReleaseFrom.getUrl()))
                                .findFirst()
                                .map(repository ->
                                        RepositoryConfig.builder(repository.getUrl())
                                                .branch(Optional.ofNullable(repository.getBranch()).orElse(repositoryToReleaseFrom.getBranch()))
                                                .version(Optional.ofNullable(repository.getVersion()).orElse(repositoryToReleaseFrom.getVersion()))
                                                .versionIncrementType(Optional.ofNullable(repository.getVersionIncrementType()).orElse(repositoryToReleaseFrom.getVersionIncrementType()))
                                                .skipTests(repository.isSkipTests() || repositoryToReleaseFrom.isSkipTests())
                                                .build())
                                .orElse(repositoryToReleaseFrom))
                        .collect(Collectors.toList());
        List<RepositoryConfig> mergedRepositories = mergeFunction.apply(repositories, repositoriesToReleaseFrom);
        List<RepositoryConfig> mergedRepositoriesToReleaseFrom = mergeFunction.apply(repositoriesToReleaseFrom, repositories);
        try (ExecutorService executorService = Executors.newFixedThreadPool(gitConfig.getCheckoutParallelism())) {
            List<RepositoryInfo> repositoryInfoList = mergedRepositories.stream()
                    .map(rc -> {
                        try {
                            PipedOutputStream out = new PipedOutputStream();
                            PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
                            Future<RepositoryInfo> future = executorService.submit(() ->
                                    createRepositoryInfo(baseDir, rc, out));
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
                                                                            Collection<RepositoryConfig> repositories,
                                                                            boolean createMissingBranches,
                                                                            VersionIncrementType validateSameVersionUpToLevel,
                                                                            List<Pattern> skipValidationForGAPatterns,
                                                                            OutputStream out) {
        log.info("Building versioned dependency graph");

        try (ExecutorService executorService = Executors.newFixedThreadPool(4)) {
            List<RepositoryInfo> repositoryInfoList = repositories.stream()
                    .map(rc -> {
                        try {
                            PipedOutputStream o = new PipedOutputStream();
                            PipedInputStream pipedInputStream = new PipedInputStream(o, 16384);
                            Future<RepositoryInfo> future = executorService.submit(() ->
                                    createRepositoryInfo(baseDir, rc, o));
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
            List<RepositoryInfo> resolvedSupportRepositories = resolveSupportBranches(repositoryInfoList,
                    baseDir, gitConfig, mavenConfig, createMissingBranches, validateSameVersionUpToLevel,
                    skipValidationForGAPatterns, out);

            Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);

            for (RepositoryInfo repositoryInfo : resolvedSupportRepositories) {
                graph.addVertex(repositoryInfo.getUrl());
            }
            RepositoryInfoLinker repositoryInfoLinker = new RepositoryInfoLinker(resolvedSupportRepositories);
            for (RepositoryInfo repositoryInfo : resolvedSupportRepositories) {
                repositoryInfoLinker.getRepositoriesUsedByThisFlatSet(repositoryInfo)
                        .stream()
                        .filter(ri -> resolvedSupportRepositories.stream().anyMatch(riFrom -> Objects.equals(riFrom.getUrl(), ri.getUrl())))
                        .forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
            }
            List<RepositoryInfo> independentRepos = resolvedSupportRepositories.stream()
                    .filter(ri -> graph.incomingEdgesOf(ri.getUrl()).isEmpty()).toList();
            List<RepositoryInfo> dependentRepos = resolvedSupportRepositories.stream()
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

    List<RepositoryInfo> resolveSupportBranches(Collection<RepositoryInfo> repositories,
                                                String baseDir, GitConfig gitConfig,
                                                MavenConfig mavenConfig, boolean createMissingBranches,
                                                VersionIncrementType validateSameVersionUpToLevel,
                                                List<Pattern> skipValidationForGAPatterns,
                                                OutputStream out) {
        Map<String, RepositoryInfo> processedVersionedRepositoryTree = new LinkedHashMap<>();
        // 1. find repositories with a non-empty version param
        List<RepositoryInfo> versionedRepositories = repositories.stream().filter(rc -> rc.getVersion() != null).toList();
        if (versionedRepositories.isEmpty()) {
            throw new IllegalArgumentException("At least 1 repository must contain a version param when building versioned dependency graph");
        }
        // first process repositories with requested versions without resolving their dependent repositories
        versionedRepositories.forEach(versionedRepository ->
                resolveSupportBranches(processedVersionedRepositoryTree, new LinkedHashMap<>(), versionedRepository,
                        repositories, baseDir, gitConfig, mavenConfig, createMissingBranches, validateSameVersionUpToLevel,
                        skipValidationForGAPatterns, false, out));
        // then process dependent repositories

        List<RepositoryInfo> updatedRepositories = repositories.stream()
                .map(ri -> processedVersionedRepositoryTree.getOrDefault(ri.getUrl(), ri)).toList();
        RepositoryInfoLinker linker = new RepositoryInfoLinker(updatedRepositories);

        versionedRepositories.stream()
                .map(ri -> processedVersionedRepositoryTree.getOrDefault(ri.getUrl(), ri))
                .filter(ri -> {
                    List<RepositoryInfo> parents = linker.getRepositoriesUsingThis(ri);
                    List<RepositoryInfo> children = linker.getRepositoriesUsedByThis(ri);
                    List<RepositoryInfo> resolve = Stream.concat(parents.stream(), children.stream())
                            .filter(d -> processedVersionedRepositoryTree.get(d.getUrl()) == null)
                            .toList();
                    return !resolve.isEmpty();
                })
                .forEach(versionedRepository ->
                        resolveDependent(processedVersionedRepositoryTree, new LinkedHashMap<>(), versionedRepository, updatedRepositories,
                                baseDir, gitConfig, mavenConfig, createMissingBranches, validateSameVersionUpToLevel,
                                skipValidationForGAPatterns, out));
        return processedVersionedRepositoryTree.values().stream().toList();
    }

    void resolveSupportBranches(Map<String, RepositoryInfo> processedVersionedRepositoryTree,
                                Map<RepositoryInfo, DependencyType> parentsStack, RepositoryConfig fromRepository,
                                Collection<RepositoryInfo> repositories,
                                String baseDir, GitConfig gitConfig, MavenConfig mavenConfig, boolean createMissingBranches,
                                VersionIncrementType validateSameVersionUpToLevel,
                                List<Pattern> skipValidationForGAPatterns,
                                boolean resolveDependentRepositories,
                                OutputStream out) {
        String branch = resolveSupportBranch(baseDir, gitConfig, mavenConfig, fromRepository, createMissingBranches, out);
        // check if we already processed the repository for this branch
        RepositoryConfig repositoryConfig = RepositoryConfig.builder(fromRepository).branch(branch).build();
        RepositoryInfo repositoryInfo = this.createRepositoryInfo(baseDir, repositoryConfig, out);
        if (processedVersionedRepositoryTree.get(fromRepository.getUrl()) instanceof RepositoryInfo processedRepositoryInfo) {
            if (!Objects.equals(processedRepositoryInfo.getBranch(), branch)) {
                throw new IllegalStateException(String.format("""
                                Version conflict detected. Repository stack:
                                %s
                                requested branch: %s,
                                existing branch: %s""",
                        Stream.concat(parentsStack.entrySet().stream(), Stream.<Map.Entry<RepositoryConfig, DependencyType>>
                                        of(new AbstractMap.SimpleEntry<>(repositoryConfig, null)))
                                .map(r -> String.format("%s [version:%s, branch:%s]%s",
                                        r.getKey().getUrl(), r.getKey().getVersion(), r.getKey().getBranch(),
                                        r.getValue() != null ? String.format("-[%s]->", r.getValue().name().toLowerCase()) : ""))
                                .collect(Collectors.joining("\n")),
                        branch, processedRepositoryInfo.getBranch()));
            } else {
                return;
            }
        }
        // validate repository's gavs do not conflict with dependencies from other processed repositories
        Map<GAV, RepositoryInfo> conflicts = repositoryInfo.getModuleDependencies().stream()
                .collect(Collectors.toMap(gav -> gav, gav -> processedVersionedRepositoryTree.values().stream()
                        .map(ri -> ri.getModuleDependencies().stream()
                                .filter(gavFrom -> skipValidationForGAPatterns.stream().noneMatch(p -> p.matcher(gavFrom.toString()).matches()))
                                .map(gavFrom -> {
                                    if (!MavenVersion.isValid(gav.getVersion()) ||
                                        !MavenVersion.isValid(gavFrom.getVersion())) {
                                        return Optional.<RepositoryInfo>empty();
                                    }
                                    MavenVersion gavVersion = new MavenVersion(gav.getVersion());
                                    MavenVersion gavFromVersion = new MavenVersion(gavFrom.getVersion());
                                    boolean valid = switch (validateSameVersionUpToLevel) {
                                        case MAJOR -> Objects.equals(gavVersion.getMajor(), gavFromVersion.getMajor());
                                        case MINOR ->
                                                Objects.equals(gavVersion.getMajor(), gavFromVersion.getMajor()) &&
                                                Objects.equals(gavVersion.getMinor(), gavFromVersion.getMinor());
                                        case PATCH ->
                                                Objects.equals(gavVersion.getMajor(), gavFromVersion.getMajor()) &&
                                                Objects.equals(gavVersion.getMinor(), gavFromVersion.getMinor()) &&
                                                Objects.equals(gavVersion.getPatch(), gavFromVersion.getPatch());
                                    };
                                    if (gav.toGA().equals(gavFrom.toGA()) && !valid) {
                                        return Optional.of(ri);
                                    } else {
                                        return Optional.<RepositoryInfo>empty();
                                    }
                                })
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .findFirst())
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .findFirst()))
                .entrySet()
                .stream()
                .filter(e -> e.getValue().isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(String.format("""
                            Version conflict detected. Repository stack:
                            %s
                            but processed repository tree already contains conflicting gavs:
                            
                            %s""",
                    Stream.concat(parentsStack.entrySet().stream(), Stream.<Map.Entry<RepositoryConfig, DependencyType>>of(new AbstractMap.SimpleEntry<>(repositoryConfig, null)))
                            .map(r -> String.format("%s [version:%s, branch:%s]%s",
                                    r.getKey().getUrl(), r.getKey().getVersion(), r.getKey().getBranch(),
                                    r.getValue() != null ? String.format("-[%s]->", r.getValue().name().toLowerCase()) : ""))
                            .collect(Collectors.joining("\n")),
                    conflicts.entrySet().stream()
                            .sorted(Comparator.<Map.Entry<GAV, RepositoryInfo>, String>comparing(e -> e.getKey().getVersion())
                                    .thenComparing(e -> e.getKey().toGA().toString()))
                            .map(e -> String.format("""
                                            GA '%s': new version: %s but existing: %s - %s [version:%s, branch:%s]""",
                                    e.getKey().toGA().toString(),
                                    e.getKey().getVersion(),
                                    e.getValue().getModuleDependencies().stream()
                                            .filter(gav -> gav.toGA().equals(e.getKey().toGA()))
                                            .map(GAV::getVersion)
                                            .findFirst().orElse("not found"),
                                    e.getValue().getUrl(), e.getValue().getVersion(), e.getValue().getBranch()))
                            .collect(Collectors.joining("\n"))));
        }

        processedVersionedRepositoryTree.put(repositoryInfo.getUrl(), repositoryInfo);
        log.info("Resolved {}[branch={}]", repositoryInfo.getUrl(), repositoryInfo.getBranch());

        if (resolveDependentRepositories) {
            resolveDependent(processedVersionedRepositoryTree, parentsStack, repositoryInfo, repositories,
                    baseDir, gitConfig, mavenConfig, createMissingBranches, validateSameVersionUpToLevel,
                    skipValidationForGAPatterns, out);
        }
    }

    void resolveDependent(Map<String, RepositoryInfo> processedVersionedRepositoryTree,
                          Map<RepositoryInfo, DependencyType> parentsStack,
                          RepositoryInfo repositoryInfo,
                          Collection<RepositoryInfo> repositories,
                          String baseDir, GitConfig gitConfig, MavenConfig mavenConfig, boolean createMissingBranches,
                          VersionIncrementType validateSameVersionUpToLevel,
                          List<Pattern> skipValidationForGAPatterns,
                          OutputStream out) {
        List<RepositoryInfo> updatedRepositories = repositories.stream().map(ri -> {
            if (Objects.equals(ri.getUrl(), repositoryInfo.getUrl())) {
                return repositoryInfo;
            } else {
                return ri;
            }
        }).toList();
        RepositoryInfoLinker linker = new RepositoryInfoLinker(updatedRepositories);
        List<RepositoryInfo> children = linker.getRepositoriesUsedByThis(repositoryInfo);
        children.forEach(child -> {
            String version = repositoryInfo.getModuleDependencies().stream()
                    .filter(module -> child.getModules().stream().map(GAV::toGA).anyMatch(module.toGA()::equals))
                    .findFirst()
                    .map(GAV::getVersion)
                    .orElse(null);
            if (version == null) {
                throw new IllegalStateException("No version found for repository: " + child.getUrl());
            }
            RepositoryConfig childRepositoryConfig = RepositoryConfig.builder(child).version(version).branch(null).build();

            Map<RepositoryInfo, DependencyType> stack = new LinkedHashMap<>(parentsStack);
            stack.put(repositoryInfo, DependencyType.REFERENCES);

            resolveSupportBranches(processedVersionedRepositoryTree, stack, childRepositoryConfig,
                    updatedRepositories, baseDir, gitConfig, mavenConfig, createMissingBranches, validateSameVersionUpToLevel,
                    skipValidationForGAPatterns, true, out);
        });
        List<RepositoryInfo> parents = linker.getRepositoriesUsingThis(repositoryInfo);
        parents.forEach(parent -> {
            String version = parent.getVersion();
            if (version == null) {
                // need to take into account all processedVersionedRepositoryTree to build the required GAV list
                Set<GAV> gavs = Stream.concat(processedVersionedRepositoryTree.values().stream(), Stream.of(repositoryInfo))
                        .flatMap(r -> {
                            // todo filter only dependencies which <scm> tag contains url from our repositories list
                            Set<GAV> moduleDependencies = r.getModuleDependencies();
                            return Stream.concat(r.getModules().stream(), moduleDependencies.stream());
                        })
                        // filter out buggy versions like unresolvable references ${lombok.version} etc
                        .filter(gav -> MavenVersion.isValid(gav.getVersion()))
                        .map(gav -> {
                            String v = gav.getVersion();
                            MavenVersion mavenVersion = new MavenVersion(v);
                            if (Optional.ofNullable(mavenVersion.getSuffix()).map(s -> s.equals("-SNAPSHOT")).orElse(false)) {
                                normalizeSnapshotVersion(mavenVersion);
                                return new GAV(gav.getGroupId(), gav.getArtifactId(), mavenVersion.toString());
                            }
                            return gav;
                        })
                        .collect(Collectors.toSet());
                version = findParentRepositoryVersion(baseDir, parent, gavs, validateSameVersionUpToLevel, skipValidationForGAPatterns, out);
            }
            RepositoryConfig parentRepositoryConfig = RepositoryConfig.builder(parent).version(version).branch(null).build();

            Map<RepositoryInfo, DependencyType> stack = new LinkedHashMap<>(parentsStack);
            stack.put(repositoryInfo, DependencyType.DECLARED_IN);

            resolveSupportBranches(processedVersionedRepositoryTree, stack, parentRepositoryConfig,
                    updatedRepositories, baseDir, gitConfig, mavenConfig, createMissingBranches, validateSameVersionUpToLevel,
                    skipValidationForGAPatterns, true, out);
        });
    }

    void normalizeSnapshotVersion(MavenVersion version) {
        if (version.getSuffix() != null && version.getSuffix().equals("-SNAPSHOT")) {
            version.setSuffix("");
            version.update(VersionIncrementType.PATCH, version.getPatch() - 1);
        }
    }

    String findParentRepositoryVersion(String baseDir, RepositoryConfig versionedRepository,
                                       Collection<GAV> mustHaveDependenciesGAVs, VersionIncrementType validateSameVersionUpToLevel,
                                       List<Pattern> skipValidationForGAPatterns, OutputStream out) {
        Path repositoryDirPath = Paths.get(baseDir, versionedRepository.getDir());
        if (!Files.exists(repositoryDirPath)) {
            throw new IllegalArgumentException(String.format("Repository directory '%s' does not exist", repositoryDirPath));
        }
        try {
            try (Git git = Git.open(repositoryDirPath.toFile())) {
                Map<String, Set<String>> supportBranchVersions = new TreeMap<>();
                List<String> branches = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call().stream()
                        .map(branch -> supportBranchPattern.matcher(branch.getName()))
                        .filter(Matcher::matches)
                        .peek(m -> {
                            String minor = m.group("minor");
                            if (!"x".equals(minor)) {
                                supportBranchVersions.computeIfAbsent(m.group("major"), k -> new TreeSet<>()).add(minor);
                            }
                        })
                        .map(Matcher::group)
                        .toList();
                List<String> refs = Stream.concat(git.tagList().call().stream().map(Ref::getName)
                                        // remove tags which are covered by corresponding support branches
                                        .map(ref -> versionPattern.matcher(ref))
                                        .filter(Matcher::matches)
                                        .filter(m -> {
                                            String major = m.group("major");
                                            String minor = m.group("minor");
                                            return !supportBranchVersions.getOrDefault(major, Set.of()).contains(minor);
                                        })
                                        .map(Matcher::group),
                                branches.stream())
                        .map(ref -> versionPattern.matcher(ref))
                        .filter(Matcher::matches)
                        .sorted((tagMatcher1, tagMatcher2) ->
                                Comparator.<Matcher, MavenVersion>comparing(m -> {
                                            String minor = m.group("minor");
                                            String patch = m.group("patch");
                                            return new MavenVersion(Integer.parseInt(m.group("major")),
                                                    "x".equals(minor) ? Integer.MAX_VALUE : Integer.parseInt(minor),
                                                    "x".equals(patch) ? Integer.MAX_VALUE : Integer.parseInt(patch));
                                        })
                                        .reversed()
                                        .compare(tagMatcher1, tagMatcher2))
                        .map(Matcher::group)
                        .toList();
                long maxValidGavs = 0;
                Map<GA, VersionInfo> lastResult = Map.of();
                for (String ref : refs) {
                    RepositoryConfig repositoryConfig = RepositoryConfig.builder(versionedRepository).branch(ref).version(null).build();
                    RepositoryInfo repositoryInfo = createRepositoryInfo(baseDir, repositoryConfig, out);

                    Set<GAV> targetGAVs = repositoryInfo.getModuleDependencies().stream()
                            .filter(d -> skipValidationForGAPatterns.stream().noneMatch(p -> p.matcher(d.toString()).matches()))
                            .filter(d -> mustHaveDependenciesGAVs.stream().anyMatch(gav -> gav.toGA().equals(d.toGA())))
                            .collect(Collectors.toSet());

                    Map<GA, VersionInfo> result = targetGAVs.stream()
                            .collect(Collectors.toMap(GAV::toGA, d -> {
                                GAV r = mustHaveDependenciesGAVs.stream()
                                        .filter(g -> g.toGA().equals(d.toGA())).findFirst()
                                        .orElseThrow(() -> new IllegalArgumentException("Missing GA: " + d.toGA()));
                                MavenVersion dependencyVersion = new MavenVersion(d.getVersion());
                                MavenVersion requiredVersion = new MavenVersion(r.getVersion());
                                boolean valid;
                                if (validateSameVersionUpToLevel == VersionIncrementType.MAJOR) {
                                    valid = dependencyVersion.getMajor() == requiredVersion.getMajor();
                                } else if (validateSameVersionUpToLevel == VersionIncrementType.MINOR) {
                                    valid = dependencyVersion.getMajor() == requiredVersion.getMajor() &&
                                            dependencyVersion.getMinor() == requiredVersion.getMinor();
                                } else {
                                    valid = dependencyVersion.getMajor() == requiredVersion.getMajor() &&
                                            dependencyVersion.getMinor() == requiredVersion.getMinor() &&
                                            dependencyVersion.getPatch() == requiredVersion.getPatch();
                                }
                                return new VersionInfo(requiredVersion, dependencyVersion, valid);
                            }));
                    long validCount = result.values().stream().filter(VersionInfo::isValid).count();
                    if (maxValidGavs < validCount) maxValidGavs = validCount;
                    if (validCount < maxValidGavs) {
                        throw new IllegalStateException(String.format("""
                                        Failed to resolve version for parent repository  %s
                                        ref=[%s].
                                        Valid matches started to decline. Max match result was:
                                        %s""",
                                repositoryInfo.getUrl(),
                                ref,
                                lastResult.entrySet().stream()
                                        .sorted(Comparator.<Map.Entry<GA, VersionInfo>, Boolean>comparing(entry -> entry.getValue().isValid())
                                                .thenComparing(entry -> entry.getKey().getGroupId())
                                                .thenComparing(entry -> entry.getValue().getRequired().getVersion()))
                                        .map(entry -> String.format("%s:%s", entry.getKey().toString(), entry.getValue().toString()))
                                        .collect(Collectors.joining("\n"))));
                    }
                    if (result.values().stream().allMatch(VersionInfo::isValid)) {
                        Optional<String> optionalVersion = repositoryInfo.getModules().stream().findFirst().map(GAV::getVersion);
                        if (optionalVersion.isEmpty()) {
                            throw new IllegalStateException("No version found for repository: " + repositoryInfo.getUrl());
                        }
                        MavenVersion mavenVersion = new MavenVersion(optionalVersion.get());
                        normalizeSnapshotVersion(mavenVersion);
                        return mavenVersion.toString();
                    }
                    lastResult = result;
                }
                throw new IllegalStateException(String.format("No version found for repository: %s which dependencies satisfy gavs: %s",
                        versionedRepository.getUrl(), mustHaveDependenciesGAVs.stream().map(GAV::toString).collect(Collectors.joining(","))));
            }
        } catch (Exception ioe) {
            if (ioe instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(ioe);
        }
    }

    @Data
    @AllArgsConstructor
    static class VersionInfo {
        MavenVersion required;
        MavenVersion actual;
        boolean valid;

        @Override
        public String toString() {
            return String.format("%s -> %s [%s]", required, actual, valid ? "valid" : "invalid");
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
                    if (optionalBranch.isEmpty() || !checkVersions(branchNameMinor, major, minor, baseDir, versionedRepository, out)) {
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
                                RepositoryInfo repositoryInfo = createRepositoryInfo(baseDir, repositoryConfig, out);
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

    boolean checkVersions(String branchName, String major, String minor, String baseDir, RepositoryConfig versionedRepository, OutputStream out) {
        RepositoryConfig repositoryConfig = RepositoryConfig.builder(versionedRepository).branch(branchName).version(null).build();
        RepositoryInfo repositoryInfo = this.createRepositoryInfo(baseDir, repositoryConfig, out);
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

    RepositoryInfo createRepositoryInfo(String baseDir, RepositoryConfig repositoryConfig, OutputStream out) {
        gitService.gitCheckout(baseDir, repositoryConfig, out);
        return new RepositoryInfo(repositoryConfig, baseDir);
    }
}
