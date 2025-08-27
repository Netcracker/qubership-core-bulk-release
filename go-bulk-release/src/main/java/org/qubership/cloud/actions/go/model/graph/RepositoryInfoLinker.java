package org.qubership.cloud.actions.go.model.graph;

import org.qubership.cloud.actions.go.model.GAV;
import org.qubership.cloud.actions.go.model.GoGAV;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;

import java.util.*;

public class RepositoryInfoLinker {

    final Collection<RepositoryInfo> repositories;

    public RepositoryInfoLinker(Collection<RepositoryInfo> repositories) {
        this.repositories = repositories;
    }

    public List<RepositoryInfo> getRepositoriesUsedByThis(RepositoryInfo thisRepository) {
        return this.repositories.stream()
                .filter(r ->
                        r.getModules().stream().anyMatch(first ->
                                thisRepository.getModuleDependencies().stream().anyMatch(first::isSameArtifact)))
                .filter(r -> !Objects.equals(r.getUrl(), thisRepository.getUrl()))
                .toList();
    }

    public List<RepositoryInfo> getRepositoriesUsingThis(RepositoryInfo thisRepository) {
        return this.repositories.stream()
                .filter(r ->
                        r.getModuleDependencies().stream().anyMatch(first ->
                                thisRepository.getModules().stream().anyMatch(first::isSameArtifact)))
                .filter(r -> !Objects.equals(r.getUrl(), thisRepository.getUrl()))
                .toList();
    }

    public Set<RepositoryInfo> getRepositoriesUsedByThisFlatSet(RepositoryInfo thisRepository) {
        List<RepositoryInfo> repositoryList = getRepositoriesUsedByThis(thisRepository);
        Set<RepositoryInfo> result = new HashSet<>(repositoryList);
        repositoryList.forEach(ri -> result.addAll(this.getRepositoriesUsedByThisFlatSet(ri)));
        return result;
    }

    private boolean isSameArtifact(GAV first, GAV second) {
        if (first instanceof GoGAV goFirst && second instanceof GoGAV goSecond) {
            return Objects.equals(goFirst.getArtifactIdWithoutVersion(), goSecond.getArtifactIdWithoutVersion());
        }
        else {
            return Objects.equals(first.getGroupId(), second.getGroupId()) && Objects.equals(first.getArtifactId(), second.getArtifactId());
        }
    }
}
