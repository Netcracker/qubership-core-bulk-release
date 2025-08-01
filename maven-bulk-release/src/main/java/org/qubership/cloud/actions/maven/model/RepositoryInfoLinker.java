package org.qubership.cloud.actions.maven.model;

import java.util.*;

public class RepositoryInfoLinker {

    final Collection<RepositoryInfo> repositories;

    public RepositoryInfoLinker(Collection<RepositoryInfo> repositories) {
        this.repositories = repositories;
    }

    public List<RepositoryInfo> getRepositoriesUsedByThis(RepositoryInfo thisRepository) {
        return this.repositories.stream()
                .filter(r ->
                        r.getModules().stream().map(GAV::toGA).anyMatch(module ->
                                thisRepository.getModuleDependencies().stream().map(GAV::toGA).anyMatch(module::equals)))
                .filter(r -> !Objects.equals(r.getUrl(), thisRepository.getUrl()))
                .toList();
    }

    public List<RepositoryInfo> getRepositoriesUsingThis(RepositoryInfo thisRepository) {
        return this.repositories.stream()
                .filter(r ->
                        r.getModuleDependencies().stream().map(GAV::toGA).anyMatch(module ->
                                thisRepository.getModules().stream().map(GAV::toGA).anyMatch(module::equals)))
                .filter(r -> !Objects.equals(r.getUrl(), thisRepository.getUrl()))
                .toList();
    }

    public Set<RepositoryInfo> getRepositoriesUsedByThisFlatSet(RepositoryInfo thisRepository) {
        List<RepositoryInfo> repositoryList = getRepositoriesUsedByThis(thisRepository);
        Set<RepositoryInfo> result = new HashSet<>(repositoryList);
        repositoryList.forEach(ri -> result.addAll(this.getRepositoriesUsedByThisFlatSet(ri)));
        return result;
    }
}
