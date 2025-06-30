package org.qubership.cloud.actions.maven;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.TagOpt;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.qubership.cloud.actions.maven.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class RepositoryService {

    Map<Integer, List<RepositoryInfo>> buildDependencyGraph(Config config) {
        log.info("Building dependency graph");
        String baseDir = config.getBaseDir();
        Set<RepositoryConfig> repositories = config.getRepositories();
        config.getRepositoriesToReleaseFrom().forEach(repositoryToReleaseFrom -> repositories.stream()
                .filter(repository -> Objects.equals(repository.getUrl(), repositoryToReleaseFrom.getUrl()))
                .findFirst()
                .ifPresent(repository -> {
                    String repositoryBranch = repository.getFrom();
                    String repositoryToReleaseFromBranch = repositoryToReleaseFrom.getFrom();
                    if (!Objects.equals(repositoryBranch, repositoryToReleaseFromBranch)) {
                        if (!Objects.equals(repositoryToReleaseFromBranch, RepositoryConfig.HEAD)) {
                            repository.setFrom(repositoryToReleaseFromBranch);
                        } else {
                            repositoryToReleaseFrom.setFrom(repositoryBranch);
                        }
                    }
                }));
        try (ExecutorService executorService = Executors.newFixedThreadPool(4)) {
            List<RepositoryInfo> repositoryInfoList = repositories.stream()
                    .map(rc -> {
                        try {
                            PipedOutputStream out = new PipedOutputStream();
                            PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
                            Future<RepositoryInfo> future = executorService.submit(() -> {
                                gitCheckout(config, rc, out);
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

    public void gitCheckout(Config config, RepositoryConfig repository, OutputStream out) {
        try (out) {
            Path repositoryDirPath = Paths.get(config.getBaseDir(), repository.getDir());
            boolean repositoryDirExists = Files.exists(repositoryDirPath);
            Git git;
            if (repositoryDirExists && Files.list(repositoryDirPath).findAny().isPresent()) {
                git = Git.open(repositoryDirPath.toFile());
            } else {
                try (PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(out, UTF_8))) {
                    printWriter.println(String.format("Checking out %s from: [%s]", repository.getUrl(), repository.getFrom()));
                    Files.createDirectories(repositoryDirPath);

                    git = Git.cloneRepository()
                            .setCredentialsProvider(config.getCredentialsProvider())
                            .setURI(repository.getUrl())
                            .setDirectory(repositoryDirPath.toFile())
                            .setDepth(1)
                            .setBranch(repository.getFrom())
                            .setCloneAllBranches(false)
                            .setTagOption(TagOpt.FETCH_TAGS)
                            .setProgressMonitor(new TextProgressMonitor(printWriter))
                            .call();
                }
            }
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
}
