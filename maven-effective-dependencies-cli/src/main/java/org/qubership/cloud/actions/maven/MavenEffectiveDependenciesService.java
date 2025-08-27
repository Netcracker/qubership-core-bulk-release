package org.qubership.cloud.actions.maven;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.PluginConfiguration;
import org.apache.maven.model.PluginManagement;
import org.qubership.cloud.actions.maven.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class MavenEffectiveDependenciesService {

    GitService gitService;
    RepositoryService repositoryService;

    public MavenEffectiveDependenciesService(GitService gitService) {
        this.gitService = gitService;
        this.repositoryService = new RepositoryService(gitService);
    }

    GAV empty = new GAV("", "", "");

    public EffectiveDependenciesDiff compare(String type1, Set<GAV> gavs1,
                                             String type2, Set<GAV> gavs2,
                                             Map<Integer, List<RepositoryInfo>> graph,
                                             MavenConfig mavenConfig) throws Exception {
        Function<Set<GAV>, Map<GA, String>> func = gavs ->
                gavs.stream().collect(Collectors.toMap(gav -> new GA(gav.getGroupId(), gav.getArtifactId()), gav -> {
                    String version = gav.getVersion();
                    // validate that version is a valid semver
                    if (!MavenVersion.isValid(version)) {
                        throw new RuntimeException(String.format("gav: '%s' version is non-semver", gav));
                    }
                    return version;
                }, (v1, v2) -> {
                    MavenVersion mv1 = new MavenVersion(v1);
                    MavenVersion mv2 = new MavenVersion(v2);
                    return Comparator.comparing(MavenVersion::getMajor)
                                   .thenComparing(MavenVersion::getMinor)
                                   .thenComparing(MavenVersion::getPatch)
                                   .compare(mv1, mv2) >= 0 ? v1 : v2;
                }));
        Map<GA, String> type1GAVs = func.apply(gavs1);
        Map<GA, String> type2GAVs = func.apply(gavs2);

        Set<GA> bothFrameworksGAs = Stream.of(type1GAVs.keySet(), type2GAVs.keySet())
                .flatMap(Set::stream).collect(Collectors.toSet());

        Set<GA> netcrackerGAs = graph.values().stream().flatMap(List::stream)
                .flatMap(r -> r.getModules().stream())
                .collect(Collectors.toSet());

        // collect all dependencies GAVs for all repositories
        Map<GA, Set<String>> repositoriesGAVs = graph.values().stream()
                .flatMap(List::stream)
                .flatMap(repositoryInfo -> repositoryInfo.getModuleDependencies().stream())
                .collect(Collectors.toMap(
                        gav -> new GA(gav.getGroupId(), gav.getArtifactId()),
                        gav -> Set.of(gav.getVersion()),
                        (l1, l2) -> Stream.concat(l1.stream(), l2.stream()).collect(Collectors.toSet())));

        Map<String, Conflict> majorConflictingGroups = new LinkedHashMap<>();
        Map<String, Conflict> minorConflictingGroups = new LinkedHashMap<>();

        List<GAV> gavs = bothFrameworksGAs.stream()
                .filter(repositoriesGAVs::containsKey)
                .collect(Collectors.toMap(ga -> ga, ga -> {
                    String version1 = type1GAVs.get(ga);
                    String version2 = type2GAVs.get(ga);
                    if (version1 == null) {
                        return new GAV(ga.getGroupId(), ga.getArtifactId(), version2);
                    } else if (version2 == null) {
                        return new GAV(ga.getGroupId(), ga.getArtifactId(), version1);
                    } else {
                        MavenVersion type1Version = new MavenVersion(version1);
                        MavenVersion type2Version = new MavenVersion(version2);
                        int type1Major = type1Version.getMajor();
                        int type2Major = type2Version.getMajor();
                        int type1Minor = type1Version.getMinor();
                        int type2Minor = type2Version.getMinor();
                        if ((type1Major != type2Major || type1Minor != type2Minor)) {
                            boolean majorConflict = type1Major != type2Major;
                            if (majorConflict) {
                                // todo - for now if majors are conflicting skip them (manual changes are required)
                                Conflict groupIdConflict = majorConflictingGroups.computeIfAbsent(ga.getGroupId(), key ->
                                        new Conflict(new ConflictSide(type1, version1), new ConflictSide(type2, version2)));

                                Set<String> versions = Optional.ofNullable(repositoriesGAVs.get(ga)).orElse(Collections.emptySet());
                                groupIdConflict.setRepositories(versions.stream().collect(Collectors.toMap(v -> v, v -> {
                                    LinkedHashMap<String, ArtifactInfo> artifactInfoMap = new LinkedHashMap<>();
                                    ArtifactInfo artifactInfo = artifactInfoMap.computeIfAbsent(ga.getArtifactId(), key -> new ArtifactInfo());
                                    artifactInfo.setConsumers(consumers(new GAV(ga.getGroupId(), ga.getArtifactId(), v), 0, graph));
                                    return artifactInfoMap;
                                })));
                                return empty;
                            }
                            Conflict groupIdConflict = minorConflictingGroups.computeIfAbsent(ga.getGroupId(), key ->
                                    new Conflict(new ConflictSide(type1, version1), new ConflictSide(type2, version2)));

                            Set<String> versions = Optional.ofNullable(repositoriesGAVs.get(ga)).orElse(Collections.emptySet());
                            groupIdConflict.setRepositories(versions.stream().collect(Collectors.toMap(v -> v, v -> {
                                LinkedHashMap<String, ArtifactInfo> artifactInfoMap = new LinkedHashMap<>();
                                ArtifactInfo artifactInfo = artifactInfoMap.computeIfAbsent(ga.getArtifactId(), key -> new ArtifactInfo());
                                artifactInfo.setConsumers(consumers(new GAV(ga.getGroupId(), ga.getArtifactId(), v), 0, graph));
                                return artifactInfoMap;
                            })));
                        }
                        String version = type1Version.compareTo(type2Version) > 0 ? version1 : version2;
                        return new GAV(ga.getGroupId(), ga.getArtifactId(), version);
                    }
                })).values().stream().filter(gav -> gav != empty).distinct().toList();
        return new EffectiveDependenciesDiff(gavs.stream().sorted().toList(), majorConflictingGroups, minorConflictingGroups);
    }

    HttpClient httpClient = HttpClient.newBuilder().build();
    ObjectMapper xmlMapper = new ObjectMapper(new XmlFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    MavenMetadata getGAMetadata(GA ga) {
        String uri = String.format("https://repo1.maven.org/maven2/%s/%s/maven-metadata.xml",
                ga.getGroupId().replace(".", "/"), ga.getArtifactId());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Received non OK status code [{}] from {}, body: {}", response.statusCode(), uri, response.body());
                return null;
            }
            return xmlMapper.readValue(response.body(), MavenMetadata.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    List<RepositoryArtifactConsumer> consumers(GAV gav, int level, Map<Integer, List<RepositoryInfo>> graph) {
        List<RepositoryInfo> repos = graph.get(level);
        if (repos == null) return Collections.emptyList();
        String version = gav.getVersion();
        if (!MavenVersion.isValid(version)) {
            throw new IllegalArgumentException("non-semver GAV version: " + gav.getVersion());
        }
        List<RepositoryArtifactConsumer> artifactConsumers = repos.stream().flatMap(repositoryInfo -> {
            List<RepositoryArtifactConsumer> consumers = new LinkedList<>();
            RepositoryArtifactConsumer artifactConsumer = new RepositoryArtifactConsumer(repositoryInfo.getUrl());
            repositoryInfo.getPerModuleDependencies().forEach((moduleGA, moduleGAVs) -> {
                if (moduleGAVs.stream().anyMatch(_gav -> {
                    if (!MavenVersion.isValid(_gav.getVersion())) {
                        throw new IllegalArgumentException(String.format("Module '%s' contains non-semver GAV version: '%s'", moduleGA, _gav.getVersion()));
                    }
                    return Objects.equals(_gav, gav);
                })) {
                    artifactConsumer.getModules().add(moduleArtifactConsumer(moduleGA, graph));
                }

            });
            if (!artifactConsumer.getModules().isEmpty()) {
                consumers.add(artifactConsumer);
            }
            return consumers.stream();
        }).toList();
        if (artifactConsumers.isEmpty()) {
            return consumers(gav, level + 1, graph);
        }
        return artifactConsumers;
    }

    List<RepositoryArtifactConsumer> consumers(GA ga, Map<Integer, List<RepositoryInfo>> graph) {
        return graph.values().stream().flatMap(List::stream)
                .filter(repositoryInfo -> repositoryInfo.getModuleDependencies().stream()
                        .map(gav -> new GA(gav.getGroupId(), gav.getArtifactId()))
                        .distinct()
                        .anyMatch(_ga -> Objects.equals(ga, _ga)))
                .flatMap(repositoryInfo -> {
                    RepositoryArtifactConsumer artifactConsumer = new RepositoryArtifactConsumer(repositoryInfo.getUrl());
                    List<RepositoryArtifactConsumer> consumers = new LinkedList<>();
                    repositoryInfo.getPerModuleDependencies().forEach((moduleGA, moduleGAVs) -> {
                        if (moduleGAVs.stream().anyMatch(gav -> Objects.equals(new GA(gav.getGroupId(), gav.getArtifactId()), ga))) {
                            artifactConsumer.getModules().add(moduleArtifactConsumer(moduleGA, graph));
                        }
                    });
                    if (!artifactConsumer.getModules().isEmpty()) consumers.add(artifactConsumer);
                    return consumers.stream();
                }).toList();
    }

    ModuleArtifactConsumer moduleArtifactConsumer(GA ga, Map<Integer, List<RepositoryInfo>> graph) {
        ModuleArtifactConsumer moduleArtifactConsumer = new ModuleArtifactConsumer(ga.getGroupId(), ga.getArtifactId());
        moduleArtifactConsumer.setConsumers(consumers(ga, graph));
        return moduleArtifactConsumer;
    }

    public Map<Integer, List<RepositoryInfo>> resolveRepositories(Config config) {
        return repositoryService.buildDependencyGraph(config.getBaseDir(), config.getGitConfig(),
                config.getRepositories(), config.getRepositoriesToReleaseFrom());
    }

    public Set<GAV> resolvePomsImplicitDependencies(Path pomDir) {
        List<PomHolder> poms = PomHolder.parsePoms(pomDir);
        Set<GA> selfGavs = poms.stream().map(pom-> new GA(pom.getGroupId(), pom.getArtifactId())).collect(Collectors.toSet());
        return poms.stream()
                .flatMap(pom -> pom.getGAVs().stream()
                        .filter(gav -> !selfGavs.contains(gav.toGA()))
                        .map(gav -> new GAV(pom.autoResolvePropReference(gav.getGroupId()),
                                pom.autoResolvePropReference(gav.getArtifactId()),
                                pom.autoResolvePropReference(gav.getVersion()))))
                .collect(Collectors.toSet());
    }

    public Set<GAV> resolvePomsEffectiveDependencies(Path pomDir, MavenConfig config) {
        List<PomHolder> poms = PomHolder.parsePoms(pomDir);
        List<PomHolder> effectivePoms = poms.stream().map(pom -> {
            try {
                return effectivePom(pom, config);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
        Set<GAV> effectiveGAVs = effectivePoms.stream()
                .flatMap(pom -> resolveDependenciesFromEffectivePom(pom).stream())
                .collect(Collectors.toSet());
        // additionally, resolve 'pom imports' dependencies
        String mavenLocalRepoPath = resolveMavenLocalRepoPath(poms.getFirst(), config);
        Map<GA, GAV> importDependencies = poms.stream().flatMap(pom -> resolveImportDependencies(pom, mavenLocalRepoPath).stream())
                .collect(Collectors.toSet())
                .stream()
                .collect(Collectors.toMap(gav -> new GA(gav.getGroupId(), gav.getArtifactId()), gav -> gav,
                        (gav1, gav2) -> {
                            MavenVersion v1 = gav1.getVersion().transform(MavenVersion::new);
                            MavenVersion v2 = gav2.getVersion().transform(MavenVersion::new);
                            if (v1.getMajor() != v2.getMajor() || v1.getMinor() != v2.getMinor()) {
                                throw new RuntimeException("Major/minor versions do not match for: " + Stream.of(gav1, gav2).map(GAV::toString).collect(Collectors.joining(", ")));
                            }
                            return Comparator.comparing(MavenVersion::getMajor)
                                           .thenComparing(MavenVersion::getMinor)
                                           .thenComparing(MavenVersion::getPatch)
                                           .compare(v1, v2) >= 0 ? gav1 : gav2;
                        }));

        Collection<GAV> importGAVs = importDependencies.values();
        effectiveGAVs.addAll(importGAVs);
        return effectiveGAVs;
    }

    private static Set<GAV> resolveDependenciesFromEffectivePom(PomHolder pom) {
        Stream<GAV> depManagmentStream = Optional.ofNullable(pom.getModel().getDependencyManagement())
                .map(DependencyManagement::getDependencies).stream()
                .flatMap(dependencies -> dependencies.stream()
                        .map(d -> new GAV(d.getGroupId(), d.getArtifactId(), d.getVersion())));
        Stream<GAV> dependenciesStream = Optional.ofNullable(pom.getModel().getDependencies())
                .stream().flatMap(dependencies -> dependencies.stream()
                        .map(d -> new GAV(d.getGroupId(), d.getArtifactId(), d.getVersion())));
        Stream<GAV> pluginsManagementStream = Optional.ofNullable(pom.getModel().getBuild())
                .map(PluginConfiguration::getPluginManagement)
                .map(PluginManagement::getPlugins)
                .stream().flatMap(plugins -> {
                    Stream<GAV> pluginsGavs = plugins.stream()
                            .map(p -> new GAV(p.getGroupId(), p.getArtifactId(), p.getVersion()));
                    Stream<GAV> pluginsDepsGavs = plugins.stream().flatMap(p -> p.getDependencies().stream()
                            .map(d -> new GAV(d.getGroupId(), d.getArtifactId(), d.getVersion())));
                    return Stream.concat(pluginsGavs, pluginsDepsGavs);
                });
        Stream<GAV> pluginsStream = Optional.ofNullable(pom.getModel().getBuild())
                .map(PluginConfiguration::getPlugins)
                .stream().flatMap(plugins -> {
                    Stream<GAV> pluginsGavs = plugins.stream().map(p -> new GAV(p.getGroupId(), p.getArtifactId(), p.getVersion()));
                    Stream<GAV> pluginsDepsGavs = plugins.stream().flatMap(p -> p.getDependencies().stream()
                            .map(d -> new GAV(d.getGroupId(), d.getArtifactId(), d.getVersion())));
                    return Stream.concat(pluginsGavs, pluginsDepsGavs);
                });
        return Stream.of(dependenciesStream, depManagmentStream, pluginsManagementStream, pluginsStream).flatMap(s -> s).collect(Collectors.toSet());
    }

    Set<GAV> resolveImportDependencies(PomHolder pom, String mavenLocalRepoPath) {
        Set<GAV> result = Optional.ofNullable(pom.getModel().getDependencyManagement())
                .map(DependencyManagement::getDependencies).stream()
                .flatMap(dependencies -> dependencies.stream().flatMap(d -> {
                    String groupId = pom.autoResolvePropReference(d.getGroupId());
                    String artifactId = pom.autoResolvePropReference(d.getArtifactId());
                    String version = pom.autoResolvePropReference(d.getVersion());
                    if ("pom".equals(d.getType()) && "import".equals(d.getScope())) {
                        GAV gav = new GAV(groupId, artifactId, version);
                        try {
                            // get pom from local repository
                            Path path = builArtifactLocalMavenRepoPath(mavenLocalRepoPath, groupId, artifactId, version);
                            PomHolder pomHolder = PomHolder.parsePom(path);
                            Set<GAV> gavs = resolveImportDependencies(pomHolder, mavenLocalRepoPath);
                            gavs.add(gav);
                            return gavs.stream();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        return Stream.empty();
                    }
                }))
                .collect(Collectors.toSet());
        // check if parent's group differs from ours
        PomHolder parent = pom.getParent();
        if (parent != null && !Objects.equals(parent.getGroupId(), pom.getGroupId())) {
            return Stream.concat(result.stream(), Stream.of(new GAV(parent.getGroupId(), parent.getArtifactId(), parent.getVersion()))).collect(Collectors.toSet());
        }
        return result;
    }

    private static Path builArtifactLocalMavenRepoPath(String mavenLocalRepoPath, String groupId, String artifactId, String version) {
        List<String> artifactPathList = new ArrayList<>();
        artifactPathList.addAll(Arrays.stream(groupId.split("\\.")).toList());
        artifactPathList.add(artifactId);
        artifactPathList.add(version);
        artifactPathList.add(String.format("%s-%s.pom", artifactId, version));
        String[] artifactPath = artifactPathList.toArray(new String[0]);
        Path path = Path.of(mavenLocalRepoPath, artifactPath);
        return path;
    }

    PomHolder effectivePom(PomHolder pom, MavenConfig mavenConfig) throws Exception {
        Path parentPath = pom.getPath().getParent();
        Path effectivePomPath = Path.of(parentPath.toString(), "effective-pom.xml");
        List<String> cmd = List.of("mvn", "-B", "-f", pom.getPath().getFileName().toString(), "help:effective-pom",
                "-Doutput=" + effectivePomPath,
                wrapPropertyInQuotes("-Dmaven.repo.local=" + mavenConfig.getLocalRepositoryPath())
        );
        ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(parentPath.toFile());
        log.info("Dir: {}\nCmd: '{}' started", pom.getPath(), String.join(" ", cmd));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        process.getInputStream().transferTo(System.out);
        process.waitFor();
        log.info("Dir: {}\nCmd: '{}' ended with code: {}", pom.getPath(), String.join(" ", cmd), process.exitValue());
        if (process.exitValue() != 0) {
            throw new RuntimeException("Failed to execute cmd");
        }
        return PomHolder.parsePom(effectivePomPath);
    }

    interface LoadDependency {
        void load(String packaging) throws Exception;
    }

    void getDependency(PomHolder pom, GAV gav, MavenConfig mavenConfig) {
        Path path = pom.getPath();
        LoadDependency loadDependecy = packaging -> {
            Path parentPath = path.getParent();
            List<String> cmd = List.of("mvn", "-B", "dependency:get", "-Dartifact=" + gav.toString() + ":" + packaging,
                    wrapPropertyInQuotes("-Dmaven.repo.local=" + mavenConfig.getLocalRepositoryPath())
            );
            ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(parentPath.toFile());
            log.info("Dir: {}\nCmd: '{}' started", path, String.join(" ", cmd));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            process.getInputStream().transferTo(System.out);
            process.waitFor();
            log.info("Dir: {}\nCmd: '{}' ended with code: {}", path, String.join(" ", cmd), process.exitValue());
            if (process.exitValue() != 0) {
                throw new RuntimeException("Failed to execute cmd");
            }
        };
        try {
            loadDependecy.load("jar");
        } catch (Exception e) {
            try {
                loadDependecy.load("pom");
            } catch (Exception ex) {
                RuntimeException runtimeException = new RuntimeException("Failed to get artifact: " + gav, ex);
                runtimeException.addSuppressed(e);
                throw runtimeException;
            }
        }

    }

    String resolveMavenLocalRepoPath(PomHolder pom, MavenConfig mavenConfig) {
        try {
            Path parentPath = pom.getPath().getParent();
            List<String> cmd = List.of("mvn", "-B", "help:evaluate", "-q",
                    "-DforceStdout",
                    "-Dexpression=settings.localRepository",
                    wrapPropertyInQuotes("-Dmaven.repo.local=" + mavenConfig.getLocalRepositoryPath())
            );
            ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(parentPath.toFile());
            log.info("Dir: {}\nCmd: '{}' started", pom.getPath(), String.join(" ", cmd));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            process.waitFor();
            log.info("Dir: {}\nCmd: '{}' ended with code: {}", pom.getPath(), String.join(" ", cmd), process.exitValue());
            if (process.exitValue() != 0) {
                throw new RuntimeException("Failed to execute cmd:\n" + new String(process.getInputStream().readAllBytes()));
            }
            return new String(process.getInputStream().readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve local maven repository", e);
        }
    }

    String wrapPropertyInQuotes(String prop) {
        return String.format("\"%s\"", prop);
    }
}
