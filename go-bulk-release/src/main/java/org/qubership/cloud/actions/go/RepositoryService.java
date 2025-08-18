package org.qubership.cloud.actions.go;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.TagOpt;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.qubership.cloud.actions.go.model.*;
import org.qubership.cloud.actions.go.util.LoggerWriter;
import org.qubership.cloud.actions.go.util.ParallelExecutor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class RepositoryService {
    public DependencyGraph buildDependencyGraph(String baseDir,
                                                                   GitConfig gitConfig,
                                                                   Set<RepositoryConfig> repositories,
                                                                   Set<RepositoryConfig> repositoriesToReleaseFrom) {
        log.info("Building dependency graph");
        Set<RepositoryConfig> mergedRepositories = merge(repositories, repositoriesToReleaseFrom);
        Set<RepositoryConfig> mergedRepositoriesToReleaseFrom = merge(repositoriesToReleaseFrom, repositories);

        List<RepositoryInfo> repositoryInfoList = ParallelExecutor.forEachIn(mergedRepositories)
                .inParallelOn(4)
                .execute((rc) -> {
                    gitCheckout(baseDir, gitConfig, rc);
                    return new RepositoryInfo(rc, baseDir);
                });

        // set repository dependencies
        RepositoryInfoLinker repositoryInfoLinker = new RepositoryInfoLinker(repositoryInfoList);

        // filter repositories which are not affected by 'released from' repositories
        List<RepositoryInfo> repositoryInfos = mergedRepositoriesToReleaseFrom.isEmpty() ? repositoryInfoList : repositoryInfoList.stream()
                .filter(ri ->
                        mergedRepositoriesToReleaseFrom.stream().map(RepositoryConfig::getUrl).collect(Collectors.toSet()).contains(ri.getUrl()) ||
                        mergedRepositoriesToReleaseFrom.stream().anyMatch(riFrom -> repositoryInfoLinker.getRepositoriesUsedByThisFlatSet(ri).stream()
                                .map(RepositoryInfo::getUrl).collect(Collectors.toSet()).contains(riFrom.getUrl())))
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
        DependencyGraph groupedReposMap = new DependencyGraph();
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

    private Set<RepositoryConfig> merge(Collection<RepositoryConfig> repos1, Collection<RepositoryConfig> repos2) {
        return repos1.stream()
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
    }

    void gitCheckout(String baseDir, GitConfig gitConf, RepositoryConfig repository) {
        try {
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
                PrintWriter printWriter = new PrintWriter(new LoggerWriter(), true);
                try {
                    printWriter.println(String.format("Checking out %s from: [%s]", repository.getUrl(), branch));
                    Files.createDirectories(repositoryDirPath);

                    TextProgressMonitor monitor = new TextProgressMonitor(printWriter);

                    git = Git.cloneRepository()
                            .setCredentialsProvider(gitConf.getCredentialsProvider())
                            .setURI(repository.getUrl())
                            .setDirectory(repositoryDirPath.toFile())
                            .setDepth(1)
                            .setBranch(branch)
                            .setCloneAllBranches(false)
                            .setTagOption(TagOpt.FETCH_TAGS)
                            .setProgressMonitor(monitor)
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
