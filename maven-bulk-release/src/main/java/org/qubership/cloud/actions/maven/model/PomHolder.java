package org.qubership.cloud.actions.maven.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Data
public class PomHolder {

    static Pattern commentPattern = Pattern.compile("<!--.*?-->");
    static Pattern dependencyPattern = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
    static Pattern groupIdPattern = Pattern.compile("<groupId>(<!--.*?-->)*(?<groupId>[^<>]+?)(<!--.*?-->)*</groupId>");
    static Pattern artifactIdPattern = Pattern.compile("<artifactId>(<!--.*?-->)*(?<artifactId>[^<>]+?)(<!--.*?-->)*</artifactId>");
    static Pattern versionPattern = Pattern.compile("<version>(<!--.*?-->)*(?<version>[^<>]+?)(<!--.*?-->)*</version>");
    static Pattern referencePattern = Pattern.compile("\\$\\{(.+?)}");

    static Function<List<Pattern>, Pattern> gavPatternFunction = patterns ->
            Pattern.compile("(?s)" + patterns.stream().map(Pattern::pattern)
                    // whitespaces and XML comments
                    .collect(Collectors.joining("(\\s+|\\s*<!--.*?-->\\s*|\\s*<(?!(?:groupId|artifactId|version)\\b)[^>]+>.+?</(?!(?:groupId|artifactId|version)\\b)[^>]+>\\s*)")));

    static List<Pattern> gavCombinations = List.of(
            gavPatternFunction.apply(List.of(groupIdPattern, artifactIdPattern, versionPattern)),
            gavPatternFunction.apply(List.of(groupIdPattern, versionPattern, artifactIdPattern)),
            gavPatternFunction.apply(List.of(artifactIdPattern, groupIdPattern, versionPattern)),
            gavPatternFunction.apply(List.of(artifactIdPattern, versionPattern, groupIdPattern)),
            gavPatternFunction.apply(List.of(versionPattern, groupIdPattern, artifactIdPattern)),
            gavPatternFunction.apply(List.of(versionPattern, artifactIdPattern, groupIdPattern)));

    Path path;
    PomHolder parent;
    Model model;
    String pom;

    public PomHolder(String pom, Path path) {
        this.path = path;
        this.setPom(pom);
    }

    public void setPom(String pom) {
        try {
            this.pom = pom;
            Model model = new MavenXpp3Reader().read(new ByteArrayInputStream(pom.getBytes()));
            this.setModel(model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<PomHolder> getParentsFlatList() {
        List<PomHolder> parents = new ArrayList<>();
        if (this.parent != null) {
            parents.add(this.parent);
            parents.addAll(this.parent.getParentsFlatList());
        }
        return parents;
    }

    public String getGroupId() {
        return autoResolvePropReference(Optional.ofNullable(model.getGroupId()).orElseGet(() -> model.getParent().getGroupId()));
    }

    public String getArtifactId() {
        return autoResolvePropReference(model.getArtifactId());
    }

    public String getVersion() {
        return Optional.ofNullable(model.getVersion()).orElseGet(() -> model.getParent().getVersion());
    }

    public String autoResolvePropReference(String value) {
        if (value == null) return null;
        Matcher referenceMatcher = referencePattern.matcher(value);
        if (referenceMatcher.find()) {
            // find reference among properties
            String prop = referenceMatcher.group(1);
            Map<String, String> properties = getProperties();
            properties.put("project.groupId", this.getGroupId());
            properties.put("project.version", this.getVersion());
            String valueFromProperty = properties.get(prop);
            if (valueFromProperty != null) {
                return autoResolvePropReference(valueFromProperty, properties);
            } else {
                return value;
            }
        }
        return value;
    }

    public String autoResolvePropReference(String value, Map<String, String> properties) {
        if (value == null) return null;
        Matcher referenceMatcher = referencePattern.matcher(value);
        if (referenceMatcher.find()) {
            // find reference among properties
            String prop = referenceMatcher.group(1);
            String valueFromProperty = properties.get(prop);
            if (valueFromProperty != null) {
                return autoResolvePropReference(valueFromProperty, properties);
            } else {
                return value;
            }
        }
        return value;
    }

    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        PomHolder ph = this;
        while (ph != null) {
            Model model = ph.getModel();
            properties.putAll(model.getProperties().entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String)
                    .collect(Collectors.toMap(entry -> (String) entry.getKey(), entry -> (String) entry.getValue())));
            ph = ph.getParent();
        }
        return properties.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    String value = entry.getValue();
                    return autoResolvePropReference(value, properties);
                }));
    }

    public void updateProperty(String name, String version) {
        Pattern propertiesPattern = Pattern.compile("(?s)<properties>(.+?)</properties>");
        Pattern propertyPattern = Pattern.compile(MessageFormat.format("<{0}>(.+?)</{0}>", name));
        String pomContent = this.getPom();
        Matcher matcher = propertiesPattern.matcher(pomContent);
        while (matcher.find()) {
            String properties = matcher.group();
            String propertiesContent = matcher.group(1);
            Matcher propMatcher = propertyPattern.matcher(propertiesContent);
            String newVersionTag = MessageFormat.format("<{0}>{1}</{0}>", name, version);
            while (propMatcher.find()) {
                String oldVersion = propMatcher.group(1);
                String oldVersionTag = propMatcher.group();
                pomContent = pomContent.replace(properties, properties.replace(oldVersionTag, newVersionTag));
                if (!Objects.equals(oldVersion, version))
                    log.info("Updated property: {} [{} -> {}] in {}:{}", name, oldVersion, version, this.getGroupId(), this.getArtifactId());
            }
        }
        setPom(pomContent);
    }

    public void updateVersionInGAV(GAV gav) {
        String pomContent = pom;
        // find GAV entries in all possible combinations
        for (Pattern pattern : gavCombinations) {
            Matcher matcher = pattern.matcher(pomContent);
            while (matcher.find()) {
                String dependency = matcher.group();
                String groupId = autoResolvePropReference(matcher.group("groupId"));
                String artifactId = autoResolvePropReference(matcher.group("artifactId"));
                String version = matcher.group("version");
                if (groupId != null && artifactId != null && version != null) {
                    groupId = groupId.trim();
                    artifactId = artifactId.trim();
                    version = version.trim();
                    if (groupId.equals(gav.getGroupId()) && artifactId.equals(gav.getArtifactId())) {
                        pomContent = pomContent.replace(dependency, dependency.replace(version, gav.getVersion()));
                        if (!Objects.equals(gav.getVersion(), version))
                            log.info("Updated gav: {}:{} [{} -> {}] in {}:{}", groupId, artifactId, version, gav.getVersion(), this.getGroupId(), this.getArtifactId());
                    }
                }
            }
        }
        setPom(pomContent);
    }

    public Set<GAV> getGavs() {
        Set<GAV> gavs = new HashSet<>();
        // find GAV entries in all possible combinations
        for (Pattern pattern : gavCombinations) {

            Matcher matcher = pattern.matcher(pom);
            while (matcher.find()) {
                String groupId = autoResolvePropReference(matcher.group("groupId").replaceAll(commentPattern.pattern(), ""));
                String artifactId = autoResolvePropReference(matcher.group("artifactId").replaceAll(commentPattern.pattern(), ""));
                String version = matcher.group("version");
                if (groupId != null && artifactId != null && version != null) {
                    groupId = groupId.trim();
                    artifactId = artifactId.trim();
                    version = version.trim().replaceAll(commentPattern.pattern(), "");
                    gavs.add(new GAV(groupId, artifactId, version));
                }
            }
        }
        return gavs;
    }

    public static PomHolder parsePom(Path pomPath) throws IOException {
        String content = Files.readString(pomPath);
        return new PomHolder(content, pomPath);
    }

    public static List<PomHolder> parsePoms(Path repositoryDir) {
        List<PomHolder> poms = new ArrayList<>();
        try {
            Files.walkFileTree(repositoryDir, new FileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    List<String> pathList = Arrays.asList(file.toString().split("/"));
                    if (pathList.contains("pom.xml") && !pathList.contains("target")) {
                        poms.add(parsePom(file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return poms.stream().peek(pom -> {
                    Parent parent = pom.getModel().getParent();
                    if (parent != null) {
                        String groupId = parent.getGroupId();
                        String artifactId = parent.getArtifactId();
                        poms.stream().filter(ph -> Objects.equals(ph.getGroupId(), groupId) && Objects.equals(ph.getArtifactId(), artifactId))
                                .findFirst().ifPresent(pom::setParent);
                    }
                })
                // start with leaf poms
                .sorted(Comparator.<PomHolder>comparingInt(p -> p.getParentsFlatList().size()).reversed())
                .toList();
    }

    @Override
    public String toString() {
        return String.format("%s", model.getArtifactId());
    }

}
