package org.qubership.cloud.actions.maven.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.maven.model.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@EqualsAndHashCode(callSuper = true)
public class RepositoryInfo extends RepositoryConfig {
    public static Pattern propertyPattern = Pattern.compile("\\$\\{(.+?)}");

    String baseDir;
    Set<GAV> modules = new HashSet<>();
    Set<GAV> moduleDependencies = new HashSet<>();
    Map<GA, Set<GAV>> perModuleDependencies = new HashMap<>();

    public RepositoryInfo(RepositoryConfig repositoryConfig, String baseDir) {
        super(repositoryConfig.getUrl(), repositoryConfig.getBranch(), repositoryConfig.isSkipTests(),
                repositoryConfig.getVersion(), repositoryConfig.getVersionIncrementType(), repositoryConfig.getParams());
        this.baseDir = baseDir;
        try {
            Path repositoryDirPath = Paths.get(baseDir, this.getDir());
            boolean repositoryDirExists = Files.exists(repositoryDirPath);
            if (!repositoryDirExists || Files.list(repositoryDirPath).findAny().isEmpty()) {
                throw new IllegalStateException(String.format("Repository directory '%s' does not exist or is empty", repositoryDirPath));
            }
            try (Git git = Git.open(repositoryDirPath.toFile())) {
                String branch = repositoryConfig.getBranch();
                try {
                    git.checkout().setName(branch).call();
                } catch (RefNotFoundException e)  {
                    git.checkout().setName("origin/" +branch).call();
                }
            }
            this.resolveDependencies();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String calculateReleaseVersion(VersionIncrementType versionIncrementType) throws Exception {
        Path releasePropsPath = Path.of(this.getBaseDir(), this.getDir(), "release.properties");
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
        List<PomHolder> poms = PomHolder.parsePoms(Path.of(this.getBaseDir(), dir));
        Set<String> pomVersions = poms.stream().map(PomHolder::getVersion).collect(Collectors.toSet());
        if (pomVersions.size() != 1) {
            throw new IllegalArgumentException(String.format("pom.xml files from repository: %s have different versions: %s",
                    this.getUrl(), String.join(",", pomVersions)));
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
        switch (versionIncrementType) {
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

    public String calculateJavaVersion() {
        List<PomHolder> poms = PomHolder.parsePoms(Path.of(this.getBaseDir(), dir));
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

    void resolveDependencies() {
        List<PomHolder> poms = PomHolder.parsePoms(Path.of(this.getBaseDir(), dir));
        this.modules.clear();
        this.moduleDependencies.clear();
        try {
            for (PomHolder pomHolder : poms) {
                GAV moduleGAV = new GAV(pomHolder.getGroupId(), pomHolder.getArtifactId(), pomHolder.getVersion());
                this.modules.add(moduleGAV);
                this.perModuleDependencies.put(moduleGAV.toGA(), new HashSet<>());
            }
            for (PomHolder pomHolder : poms) {
                Model project = pomHolder.getModel();
                GA projectGA = new GA(pomHolder.getGroupId(), pomHolder.getArtifactId());
                Parent parent = project.getParent();
                if (parent != null && !Objects.equals(parent.getGroupId(), pomHolder.getGroupId())) {
                    GAV parentGAV = new GAV(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
                    this.moduleDependencies.add(parentGAV);
                    this.perModuleDependencies.get(projectGA).add(parentGAV);
                }
                List<GAV> dependenciesNodes = Stream.concat(
                                Optional.ofNullable(project.getDependencies()).orElse(List.of()).stream(),
                                Optional.ofNullable(project.getDependencyManagement()).map(DependencyManagement::getDependencies).orElse(List.of()).stream())
                        .map(d -> new GAV(d.getGroupId(), d.getArtifactId(), d.getVersion()))
                        .toList();
                List<GAV> pluginsDependenciesNodes = Stream.concat(
                                Optional.ofNullable(project.getBuild()).map(Build::getPluginManagement).map(PluginContainer::getPlugins).orElse(List.of()).stream(),
                                Optional.ofNullable(project.getBuild()).map(Build::getPlugins).orElse(List.of()).stream())
                        .flatMap(p -> {
                            Stream.Builder<GAV> gavStreamBuilder = Stream.builder();
                            gavStreamBuilder.add(new GAV(p.getGroupId(), p.getArtifactId(), p.getVersion()));
                            Stream<GAV> pluginDepGAVs = p.getDependencies().stream().map(dep -> new GAV(dep.getGroupId(), dep.getArtifactId(), dep.getVersion()));
                            Object configuration = p.getConfiguration();
                            if (configuration instanceof Xpp3Dom configurationDom) {
                                Optional.ofNullable(configurationDom.getChild("annotationProcessorPaths"))
                                        .map(annPaths -> annPaths.getChild("path")).ifPresent(annPaths -> {
                                            String groupId = Optional.ofNullable(annPaths.getChild("groupId")).map(Xpp3Dom::getValue).orElse(null);
                                            String artifactId = Optional.ofNullable(annPaths.getChild("artifactId")).map(Xpp3Dom::getValue).orElse(null);
                                            String version = Optional.ofNullable(annPaths.getChild("version")).map(Xpp3Dom::getValue).orElse(null);
                                            if (groupId != null && artifactId != null && version != null) {
                                                gavStreamBuilder.add(new GAV(groupId, artifactId, version));
                                            }
                                        });
                            }
                            return Stream.concat(gavStreamBuilder.build(), pluginDepGAVs);
                        })
                        .toList();
                // todo need to get dependencies from management section from effective-pom.xml because those dependencies do not contain versions in plain pom.xml
                List<GAV> allDependenciesNodes = Stream.concat(dependenciesNodes.stream(), pluginsDependenciesNodes.stream()).toList();
                for (GAV dependency : allDependenciesNodes) {
                    String groupId = pomHolder.autoResolvePropReference(dependency.getGroupId());
                    String artifactId = pomHolder.autoResolvePropReference(dependency.getArtifactId());
                    String version = pomHolder.autoResolvePropReference(dependency.getVersion());
                    if (Stream.of(groupId, artifactId, version).allMatch(Objects::nonNull)) {
                        GAV dependencyGAV = new GAV(groupId, artifactId, version);
                        this.moduleDependencies.add(dependencyGAV);
                        this.perModuleDependencies.get(projectGA).add(dependencyGAV);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void updateDepVersions(Collection<GAV> dependencies) {
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
                    .filter(dep -> this.getModules().stream()
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
        List<PomHolder> poms = PomHolder.parsePoms(Path.of(this.getBaseDir(), dir));
        poms.forEach(ph -> {
            ph.getGAVs().forEach(gav -> gavFunction.accept(ph, gav));
            ph.getProperties().forEach((propertyName, propertyValue) -> {
                if (propertiesToDependencies.containsKey(propertyName)) {
                    propertiesToPoms.computeIfAbsent(propertyName, k -> new HashSet<>()).add(ph);
                }
            });
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
        this.resolveDependencies();
    }

    @Override
    public String toString() {
        return getUrl();
    }
}
