package org.qubership.cloud.actions.go.model.graph;

import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.nio.dot.DOTExporter;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;
import org.qubership.cloud.actions.go.model.UnexpectedException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

public class DependencyGraph extends TreeMap<Integer, List<RepositoryInfo>> {
    public String generateDotFile() {
        Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);
        List<RepositoryInfo> repositoryInfoList = values().stream().flatMap(Collection::stream).toList();
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            graph.addVertex(repositoryInfo.getUrl());
        }
        RepositoryInfoLinker linker = new RepositoryInfoLinker(repositoryInfoList);
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            linker.getRepositoriesUsedByThis(repositoryInfo)
                    .stream()
                    .filter(ri -> values().stream()
                            .flatMap(Collection::stream).anyMatch(ri2 -> Objects.equals(ri2.getUrl(), ri.getUrl())))
                    .forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
        }
        Function<String, String> vertexIdProvider = vertex -> {
            int level = entrySet().stream()
                    .filter(e -> e.getValue().stream().anyMatch(ri -> Objects.equals(ri.getUrl(), vertex)))
                    .mapToInt(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new IllegalStateException(String.format("Failed to find level for vertex: %s", vertex)));
            List<RepositoryInfo> repositoryInfos = get(level);
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
            throw new UnexpectedException(e);
        }
    }
}
