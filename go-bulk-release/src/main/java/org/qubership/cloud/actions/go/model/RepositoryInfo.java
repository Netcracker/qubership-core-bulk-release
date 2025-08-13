package org.qubership.cloud.actions.go.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.qubership.cloud.actions.go.model.gomod.GoModFile;
import org.qubership.cloud.actions.go.model.gomod.GoModFileFactory;
import org.qubership.cloud.actions.go.proxy.GoModule;
import org.qubership.cloud.actions.go.util.FilesUtils;
import org.qubership.cloud.actions.go.util.UrlUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Getter
@EqualsAndHashCode(callSuper = true)
//TODO VLLA why inheritance, why not composition?
public class RepositoryInfo extends RepositoryConfig {
    String baseDir;

    Set<GAV> modules = new HashSet<>();
    Set<GAV> moduleDependencies = new HashSet<>();
    Map<GA, Set<GAV>> perModuleDependencies = new HashMap<>();

    List<GoModFile> goModFiles;

    public RepositoryInfo(RepositoryConfig repositoryConfig, String baseDir) {
        super(repositoryConfig.getUrl(), repositoryConfig.getBranch(), repositoryConfig.isSkipTests(),
                repositoryConfig.getVersion(), repositoryConfig.getVersionIncrementType());
        this.baseDir = baseDir;

        resolveDependencies();
    }

//    //TODO VLLA complex logic in the constructor that not only constructs the object, but also changes the file system (git.checkout)
//    // => hard to test, hard to override => move to the service level
//    public GoRepositoryInfo(RepositoryConfig repositoryConfig, String baseDir) {
//        super(repositoryConfig.getUrl(), repositoryConfig.getBranch(), repositoryConfig.isSkipTests(),
//                repositoryConfig.getVersion(), repositoryConfig.getVersionIncrementType());
//        this.baseDir = baseDir;
//        try {
//            Path repositoryDirPath = Paths.get(baseDir, this.getDir());
//            boolean repositoryDirExists = Files.exists(repositoryDirPath);
//            if (!repositoryDirExists || Files.list(repositoryDirPath).findAny().isEmpty()) {
//                throw new IllegalStateException(String.format("Repository directory '%s' does not exist or is empty", repositoryDirPath));
//            }
//            try (Git git = Git.open(repositoryDirPath.toFile())) {
////                TODO VLLA why do checkout again if it's already done at buildDependencyGraph level?
//                String branch = repositoryConfig.getBranch();
//                try {
//                    git.checkout().setName(branch).call();
//                } catch (RefNotFoundException e)  {
//                    git.checkout().setName("origin/" +branch).call();
//                }
//            }
//            this.resolveDependencies();
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }

    private static final Pattern SEMVER_PATTERN = Pattern.compile("v(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)");

    public String calculateReleaseVersion(VersionIncrementType versionIncrementType) {
        String currentVersion;

        try {
            Path repoPath = Path.of(this.getBaseDir(), this.getDir());
            currentVersion = getLastGitTag(repoPath.toFile());
        } catch (NoTagsFoundException e) {
            log.debug("No tags found -> calculate current version from go.mod");
            //todo vlla getFirst is HACK
            String moduleName = getGoModFiles().getFirst().getModuleName();
            String moduleVersion = extractGoModuleVersion(moduleName);
            currentVersion = moduleVersion + ".0.0";
        }

        Matcher matcher = SEMVER_PATTERN.matcher(currentVersion);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Non-semver version: %s. Must match pattern: '%s'", currentVersion, SEMVER_PATTERN.pattern()));
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
                patch++;
            }
        }
        return String.format("v%d.%d.%d", major, minor, patch);

//        Path releasePropsPath = Path.of(this.getBaseDir(), this.getDir(), "release.properties");
//        if (Files.exists(releasePropsPath)) {
//            String content = Files.readString(releasePropsPath);
//            if (content.contains("completedPhase=end-release")) {
//                Pattern pattern = Pattern.compile("^project.rel[^:]+:[^=]+=(?<version>.+)$");
//                // preparation was already performed, get a version from the file
//                Set<String> versions = Arrays.stream(content.split("\n"))
//                        .map(String::trim)
//                        .filter(line -> !line.isBlank() && line.startsWith("project.rel."))
//                        .map(pattern::matcher)
//                        .filter(Matcher::matches)
//                        .map(m -> m.group("version"))
//                        .collect(Collectors.toSet());
//                if (versions.size() != 1) {
//                    throw new IllegalStateException("Multiple/no release versions found for maven project: " + versions);
//                }
//                return versions.iterator().next();
//            }
//        }
//        List<PomHolder> poms = PomHolder.parsePoms(Path.of(this.getBaseDir(), dir));
//        Set<String> pomVersions = poms.stream().map(PomHolder::getVersion).collect(Collectors.toSet());
//        if (pomVersions.size() != 1) {
//            throw new IllegalArgumentException(String.format("pom.xml files from repository: %s have different versions: %s",
//                    this.getUrl(), String.join(",", pomVersions)));
//        }
//        String pomVersion = pomVersions.iterator().next();
//        Pattern semverPattern = Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)(?<snapshot>-SNAPSHOT)?");
//        Matcher matcher = semverPattern.matcher(pomVersion);
//        if (!matcher.matches()) {
//            throw new IllegalArgumentException(String.format("Non-semver version: %s. Must match pattern: '%s'", pomVersion, semverPattern.pattern()));
//        }
//        int major = Integer.parseInt(matcher.group("major"));
//        int minor = Integer.parseInt(matcher.group("minor"));
//        int patch = Integer.parseInt(matcher.group("patch"));
//        switch (versionIncrementType) {
//            case MAJOR -> {
//                major++;
//                minor = 0;
//                patch = 0;
//            }
//            case MINOR -> {
//                minor++;
//                patch = 0;
//            }
//            case PATCH -> {
//                String snapshot = matcher.group("snapshot");
//                if (snapshot == null) patch++;
//            }
//        }
//        return String.format("%d.%d.%d", major, minor, patch);
    }

    public static String getLastGitTag(File repoDir) throws NoTagsFoundException {
        try {

            if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
                throw new IllegalArgumentException("Directory is not a git repository: " + repoDir);
            }

            ProcessBuilder pb = new ProcessBuilder("git", "describe", "--tags", "--abbrev=0");
            pb.directory(repoDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isEmpty()) {
                throw new NoTagsFoundException("No tags found in the repository " + repoDir.getAbsolutePath());
            }

            return output;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static String extractGoModuleVersion(String moduleName) {
        if (moduleName == null || moduleName.isEmpty()) {
            return null;
        }

        int lastSlash = moduleName.lastIndexOf('/');
        if (lastSlash == -1) {
            return "v1";
        }

        String lastSegment = moduleName.substring(lastSlash + 1);

        if (lastSegment.matches("v\\d+")) {
            return lastSegment;
        }

        return "v1";
    }

//
//    public String calculateJavaVersion() {
//        List<PomHolder> poms = PomHolder.parsePoms(Path.of(this.getBaseDir(), dir));
//        Set<String> propsToSearch = Set.of("maven.compiler.source", "maven.compiler.target", "maven.compiler.release", "java.version");
//        // first search among plugins in poms
//        Optional<String> versionFromPlugin = poms.stream().map(ph -> {
//                    Map<String, String> props = Optional.ofNullable(ph.getModel().getBuild()).map(PluginContainer::getPlugins).orElse(List.of()).stream()
//                            .filter(plugin -> plugin.getArtifactId().equals("maven-compiler-plugin") && plugin.getConfiguration() instanceof Xpp3Dom)
//                            .flatMap(plugin -> {
//                                Map<String, String> result = new HashMap<>();
//                                Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
//                                Optional.ofNullable(config.getChild("release"))
//                                        .map(Xpp3Dom::getValue)
//                                        .map(ph::autoResolvePropReference)
//                                        .ifPresent(r -> result.put("release", r));
//                                Optional.ofNullable(config.getChild("target"))
//                                        .map(Xpp3Dom::getValue)
//                                        .map(ph::autoResolvePropReference)
//                                        .ifPresent(r -> result.put("target", r));
//                                Optional.ofNullable(config.getChild("source"))
//                                        .map(Xpp3Dom::getValue)
//                                        .map(ph::autoResolvePropReference)
//                                        .ifPresent(r -> result.put("source", r));
//                                return result.entrySet().stream();
//                            })
//                            .filter(Objects::nonNull)
//                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (s1, s2) -> {
//                                if (!Objects.equals(s1, s2)) {
//                                    throw new IllegalStateException(String.format("Different java versions %s and %s specified for maven-compiler-plugin in pom: %s",
//                                            s1, s2, String.format("%s:%s", ph.getGroupId(), ph.getArtifactId())));
//                                } else {
//                                    return s1;
//                                }
//                            }));
//                    return props.getOrDefault("release", props.getOrDefault("target", props.get("source")));
//                })
//                .filter(Objects::nonNull)
//                .findFirst();
//        if (versionFromPlugin.isPresent()) {
//            return versionFromPlugin.get();
//        }
//        Map<String, String> props = poms.stream()
//                .flatMap(ph -> ph.getProperties().entrySet().stream())
//                .filter(entry -> propsToSearch.contains(entry.getKey()))
//                .collect(Collectors.toMap(entry -> entry.getKey()
//                                .replace("maven.compiler.", "")
//                                .replace("java.version", "release"), Map.Entry::getValue,
//                        (s1, s2) -> {
//                            if (!Objects.equals(s1, s2)) {
//                                throw new IllegalStateException(String.format("Different java versions %s and %s specified in properties in poms: %s",
//                                        s1, s2, String.join("\n", poms.stream()
//                                                .map(ph -> String.format("%s:%s", ph.getGroupId(), ph.getArtifactId()))
//                                                .toList())));
//                            }
//                            return s1;
//                        }));
//        return props.getOrDefault("release", props.getOrDefault("target", props.get("source")));
//    }

    void resolveDependencies() {
        this.modules.clear();
        this.moduleDependencies.clear();
        this.perModuleDependencies.clear();

        try {
            String dir = UrlUtils.getDirName(getUrl());
            Path repositoryDirPath = Paths.get(baseDir, UrlUtils.getDirName(getUrl()));
            boolean repositoryDirExists = Files.exists(repositoryDirPath);
            if (!repositoryDirExists || Files.list(repositoryDirPath).findAny().isEmpty()) {
                throw new IllegalStateException(String.format("Repository directory '%s' does not exist or is empty", repositoryDirPath));
            }

            List<Path> goModFilePaths = FilesUtils.findAll(Path.of(baseDir, dir), "go.mod");

            //todo vlla collect all, not only first
            List<GoModFile> goModFiles = goModFilePaths.stream()
                    .map(path -> GoModFileFactory.create(goModFilePaths.getFirst()))
                    .toList();

            this.goModFiles = goModFiles;

            for (GoModFile goModFile : goModFiles) {
                String moduleName = goModFile.getModuleName();
                GAV moduleGAV = new GAV("TMP", moduleName, "");
                modules.add(moduleGAV);
                perModuleDependencies.put(moduleGAV, new HashSet<>());

                goModFile.getDependencies()
                        .forEach(goDependency -> {
                            GAV gav = new GAV("TMP", goDependency.getModule(), goDependency.getVersion());
                            moduleDependencies.add(gav);
                            perModuleDependencies.get(moduleGAV).add(gav);
                        });
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }

        log.info("VLLA resolveDependencies {}. module deps: {}", getUrl(), moduleDependencies);

//        List<PomHolder> poms = PomHolder.parsePoms(Path.of(this.getBaseDir(), dir));
//        this.modules.clear();
//        this.moduleDependencies.clear();
//        try {
//            for (PomHolder pomHolder : poms) {
//                GAV moduleGAV = new GAV(pomHolder.getGroupId(), pomHolder.getArtifactId(), pomHolder.getVersion());
//                this.modules.add(moduleGAV);
//                this.perModuleDependencies.put(moduleGAV.toGA(), new HashSet<>());
//            }
//            for (PomHolder pomHolder : poms) {
//                Model project = pomHolder.getModel();
//                GA projectGA = new GA(pomHolder.getGroupId(), pomHolder.getArtifactId());
//                Parent parent = project.getParent();
//                if (parent != null && !Objects.equals(parent.getGroupId(), pomHolder.getGroupId())) {
//                    GAV parentGAV = new GAV(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
//                    this.moduleDependencies.add(parentGAV);
//                    this.perModuleDependencies.get(projectGA).add(parentGAV);
//                }
//                List<GAV> dependenciesNodes = Stream.concat(
//                                Optional.ofNullable(project.getDependencies()).orElse(List.of()).stream(),
//                                Optional.ofNullable(project.getDependencyManagement()).map(DependencyManagement::getDependencies).orElse(List.of()).stream())
//                        .map(d -> new GAV(d.getGroupId(), d.getArtifactId(), d.getVersion()))
//                        .toList();
//                List<GAV> pluginsDependenciesNodes = Stream.concat(
//                                Optional.ofNullable(project.getBuild()).map(Build::getPluginManagement).map(PluginContainer::getPlugins).orElse(List.of()).stream(),
//                                Optional.ofNullable(project.getBuild()).map(Build::getPlugins).orElse(List.of()).stream())
//                        .flatMap(p -> {
//                            Stream.Builder<GAV> gavStreamBuilder = Stream.builder();
//                            gavStreamBuilder.add(new GAV(p.getGroupId(), p.getArtifactId(), p.getVersion()));
//                            Stream<GAV> pluginDepGAVs = p.getDependencies().stream().map(dep -> new GAV(dep.getGroupId(), dep.getArtifactId(), dep.getVersion()));
//                            Object configuration = p.getConfiguration();
//                            if (configuration instanceof Xpp3Dom configurationDom) {
//                                Optional.ofNullable(configurationDom.getChild("annotationProcessorPaths"))
//                                        .map(annPaths -> annPaths.getChild("path")).ifPresent(annPaths -> {
//                                            String groupId = Optional.ofNullable(annPaths.getChild("groupId")).map(Xpp3Dom::getValue).orElse(null);
//                                            String artifactId = Optional.ofNullable(annPaths.getChild("artifactId")).map(Xpp3Dom::getValue).orElse(null);
//                                            String version = Optional.ofNullable(annPaths.getChild("version")).map(Xpp3Dom::getValue).orElse(null);
//                                            if (groupId != null && artifactId != null && version != null) {
//                                                gavStreamBuilder.add(new GAV(groupId, artifactId, version));
//                                            }
//                                        });
//                            }
//                            return Stream.concat(gavStreamBuilder.build(), pluginDepGAVs);
//                        })
//                        .toList();
//                // todo need to get dependencies from management section from effective-pom.xml because those dependencies do not contain versions in plain pom.xml
//                List<GAV> allDependenciesNodes = Stream.concat(dependenciesNodes.stream(), pluginsDependenciesNodes.stream()).toList();
//                for (GAV dependency : allDependenciesNodes) {
//                    String groupId = pomHolder.autoResolvePropReference(dependency.getGroupId());
//                    String artifactId = pomHolder.autoResolvePropReference(dependency.getArtifactId());
//                    String version = pomHolder.autoResolvePropReference(dependency.getVersion());
//                    if (Stream.of(groupId, artifactId, version).allMatch(Objects::nonNull)) {
//                        GAV dependencyGAV = new GAV(groupId, artifactId, version);
//                        this.moduleDependencies.add(dependencyGAV);
//                        this.perModuleDependencies.get(projectGA).add(dependencyGAV);
//                    }
//                }
//            }
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
    }


    public void updateDepVersions(Collection<GAV> dependencies) {
        log.info("=== UPDATE DEPENDENCIES ===");
        for (GoModFile goModFile : goModFiles) {
            log.debug("process goModFile {}", goModFile.getFile());
            GoModule goModule = new GoModule(goModFile.getFile().getParent());

            log.info("VLLA dependencies: " + dependencies);
            log.info("VLLA getModuleDependencies: " + getModuleDependencies());

            dependencies.stream().filter(this::isModuleContainsDependency).forEach(goModule::get);

//            log.info("go mod tidy");
//            goModule.tidy();

//            for (GAV dependency : dependencies) {
//                if (isModuleContainsDependency(dependency)) {
//                    log.debug("VLLA module DO contains dependency");
//                    goModule.get(dependency);
//                }
//                else {
//                    log.debug("VLLA module DO NOT contains dependency");
//                }
//
//            }
        }
        this.resolveDependencies();
    }

    private boolean isModuleContainsDependency(GAV dependency) {
        return getModuleDependencies().stream().anyMatch(gav -> gav.isSameArtifact(dependency));
    }

    public void updateDepVersions_old(Collection<GAV> dependencies) {
        try {
            Map<String, String> depMap = new HashMap<>();
            for (GAV gav : dependencies) {
                depMap.put(gav.getArtifactId(), gav.getVersion());
            }

            //TODO VLLA IMPLEMENT
            log.info("updateDepVersions");
            for (GoModFile goModFile : goModFiles) {
                boolean inRequireBlock = false;
                List<String> lines = Files.readAllLines(goModFile.getFile(), StandardCharsets.UTF_8);
                //TODO VLLA remove duplicates
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);

                    line = line.trim();

                    if (line.isEmpty() || line.startsWith("//")) {
                        continue;
                    }

                    if (line.startsWith("module ")) {
                        continue;
                    }

                    // Обработка блока require (... )
                    if (line.startsWith("require (")) {
                        inRequireBlock = true;
                        continue;
                    }

                    if (inRequireBlock) {
                        if (line.equals(")")) {
                            inRequireBlock = false;
                            continue;
                        }

                        String[] parts = line.split("//", 2);
                        String mainPart = parts[0].trim();
                        String comment = parts.length > 1 ? " // " + parts[1].trim() : "";

                        String[] tokens  = mainPart.split("\\s+");
                        if (tokens .length >= 2) {
                            String moduleName = tokens[0];

                            if (depMap.containsKey(moduleName)) {
                                String newVersion = depMap.get(moduleName);
                                String newMain = moduleName + " " + newVersion;
                                lines.set(i, newMain + comment);
                            }
                        }
                    } else if (line.startsWith("require ")) {
                        String dep = line.substring("require ".length()).trim();

                        String[] parts = dep.split("//", 2);
                        String mainPart = parts[0].trim();
                        String comment = parts.length > 1 ? " // " + parts[1].trim() : "";

                        String[] tokens  = mainPart.split("\\s+");
                        if (tokens .length >= 2) {
                            String moduleName = tokens[0];

                            if (depMap.containsKey(moduleName)) {
                                String newVersion = depMap.get(moduleName);
                                String newMain = moduleName + " " + newVersion;
                                lines.set(i, "require " + newMain + comment);
                            }
                        }
                    }
                }

                Files.write(goModFile.getFile(), lines, StandardCharsets.UTF_8);
            }

        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }

//        Map<String, List<GAV>> propertiesToDependencies = new HashMap<>();
//        Map<String, Set<PomHolder>> propertiesToPoms = new HashMap<>();
//        BiConsumer<PomHolder, GAV> gavFunction = (holder, gav) -> {
//            String groupId = gav.getGroupId();
//            if (groupId == null) {
//                return;
//            }
//            String artifactId = gav.getArtifactId();
//            String version = gav.getVersion();
//            GA dependencyGA = new GA(groupId, artifactId);
//            GAV newGav = dependencies.stream()
//                    // exclude our's own modules
//                    .filter(dep -> this.getModules().stream()
//                            .noneMatch(ga -> Objects.equals(ga.getGroupId(), dep.getGroupId()) &&
//                                             Objects.equals(ga.getArtifactId(), dep.getArtifactId())))
//                    .filter(dep -> dependencyGA.getGroupId().matches(dep.getGroupId()) &&
//                                   dependencyGA.getArtifactId().matches(dep.getArtifactId()))
//                    .findFirst().orElse(null);
//            if (version != null && newGav != null) {
//                newGav = new GAV(dependencyGA.getGroupId(), dependencyGA.getArtifactId(), newGav.getVersion());
//                Matcher matcher = propertyPattern.matcher(version);
//                if (matcher.matches()) {
//                    String propertyName = matcher.group(1);
//                    List<GAV> dependenciesList = propertiesToDependencies.computeIfAbsent(propertyName, k -> new ArrayList<>());
//                    if (!dependenciesList.contains(newGav)) dependenciesList.add(newGav);
//                } else {
//                    // update a hard-coded version right away
//                    holder.updateVersionInGAV(newGav);
//                }
//            }
//        };
//        List<PomHolder> poms = PomHolder.parsePoms(Path.of(this.getBaseDir(), dir));
//        poms.forEach(ph -> {
//            ph.getGavs().forEach(gav -> gavFunction.accept(ph, gav));
//            ph.getProperties().forEach((propertyName, propertyValue) -> {
//                if (propertiesToDependencies.containsKey(propertyName)) {
//                    propertiesToPoms.computeIfAbsent(propertyName, k -> new HashSet<>()).add(ph);
//                }
//            });
//        });
//        if (!propertiesToPoms.isEmpty()) {
//            propertiesToPoms.forEach((propertyName, propertyNodes) -> {
//                // make sure that property is referencing the same version for all found dependencies
//                List<GAV> propGavs = propertiesToDependencies.get(propertyName);
//                Map<String, Set<GAV>> versionToGavs = propGavs.stream().collect(Collectors.toMap(GAV::getVersion, Set::of,
//                        (s1, s2) -> Stream.concat(s1.stream(), s2.stream()).collect(Collectors.toSet())));
//                if (versionToGavs.size() != 1) {
//                    throw new IllegalStateException(String.format("Invalid property '%s' - references by GAVs with different 'update to' versions: %s",
//                            propertyName, versionToGavs));
//                }
//                String version = versionToGavs.keySet().iterator().next();
//                // update property value
//                propertyNodes.forEach(pom -> pom.updateProperty(propertyName, version));
//            });
//        }
//        poms.forEach(pom -> {
//            try {
//                Files.writeString(pom.getPath(), pom.getPom(), StandardOpenOption.TRUNCATE_EXISTING);
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        });
        this.resolveDependencies();
    }

    @Override
    public String toString() {
        return getUrl();
    }
}
