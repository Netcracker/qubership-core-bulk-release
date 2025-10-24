package org.qubership.cloud.actions.go;

import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.qubership.cloud.actions.go.model.Config;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;
import org.qubership.cloud.actions.go.model.graph.DependencyGraph;
import org.qubership.cloud.actions.go.model.graph.RepositoryInfoLinker;
import org.qubership.cloud.actions.go.model.graph.StringEdge;
import org.qubership.cloud.actions.go.util.ParallelExecutor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class RepositoryService {
    private final GitService gitService;

    public RepositoryService(Config config) {
        this.gitService = new GitService(config.getGitConfig());
    }

    public List<RepositoryInfo> checkout(String baseDir,
                                         Set<RepositoryConfig> repositories,
                                         Set<RepositoryConfig> repositoriesToReleaseFrom) {
        Set<RepositoryConfig> mergedRepositories = merge(repositories, repositoriesToReleaseFrom);
        return ParallelExecutor.forEachIn(mergedRepositories)
                .inParallelOn(4)
                .execute(rc -> {
                    Path repository = Paths.get(baseDir, rc.getDir());
                    gitService.clone(repository, rc);
                    return new RepositoryInfo(rc, baseDir);
                });
    }

    public DependencyGraph buildDependencyGraph(List<RepositoryInfo> repositoryInfoList,
                                                Set<RepositoryConfig> repositories,
                                                Set<RepositoryConfig> repositoriesToReleaseFrom) {
        log.info("Building dependency graph");
        Set<RepositoryConfig> mergedRepositoriesToReleaseFrom = merge(repositoriesToReleaseFrom, repositories);
        // set repository dependencies
        RepositoryInfoLinker repositoryInfoLinker = new RepositoryInfoLinker(repositoryInfoList);

        // filter repositories which are not affected by 'released from' repositories
        List<RepositoryInfo> repositoryInfos = mergedRepositoriesToReleaseFrom.isEmpty() ? repositoryInfoList : repositoryInfoList.stream()
                .filter(ri ->
                        isContains(mergedRepositoriesToReleaseFrom, ri) ||
                        isAffectedByMergedReleaseRepos(mergedRepositoriesToReleaseFrom, repositoryInfoLinker, ri))
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

    private boolean isContains(Set<RepositoryConfig> repositoryConfigs, RepositoryInfo repositoryInfo) {
        return repositoryConfigs.stream()
                .map(repositoryConfig -> repositoryConfig.getUrl().toLowerCase())
                .collect(Collectors.toSet())
                .contains(repositoryInfo.getUrl().toLowerCase());
    }

    private boolean isAffectedByMergedReleaseRepos(Set<RepositoryConfig> merged,
                                                   RepositoryInfoLinker linker,
                                                   RepositoryInfo repositoryInfo) {
        Set<String> usedUrls = linker.getRepositoriesUsedByThisFlatSet(repositoryInfo).stream()
                .map(r -> r.getUrl().toLowerCase())
                .collect(Collectors.toSet());
        return merged.stream()
                .map(rc -> rc.getUrl().toLowerCase())
                .anyMatch(usedUrls::contains);
    }
}
