package org.qubership.cloud.actions.maven.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Data
public class PomHolder {

    static Pattern referencePattern = Pattern.compile("\\$\\{(.+?)}");

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
            Model model = new MavenXpp3Reader().read(new StringReader(pom));
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

    // todo re-write with Xpp3Dom approach
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
        try (Reader reader = new StringReader(pom)) {
            Xpp3Dom dom = Xpp3DomBuilder.build(reader, false);
            Arrays.stream(dom.getChildren()).forEach(child -> updateGAV(child, gav));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void updateGAV(Xpp3Dom container, GAV gav) {
        Set<String> gavNames = Set.of("groupId", "artifactId", "version");
        List<Xpp3Dom> children = Arrays.stream(container.getChildren()).toList();
        if (gavNames.stream().allMatch(n -> children.stream().anyMatch(cn -> Objects.equals(cn.getName(), n)))) {
            String groupId = container.getChild("groupId").getValue();
            String artifactId = container.getChild("artifactId").getValue();
            Xpp3Dom versionDom = container.getChild("version");
            if (Objects.equals(gav.getGroupId(), groupId) && Objects.equals(gav.getArtifactId(), artifactId) && versionDom != null) {
                StringBuilder sb = new StringBuilder("<").append(container.getName()).append(">");
                children.forEach(child -> {
                    sb.append("(\\s+|<!--.*?-->)*");
                    sb.append("<").append(child.getName()).append(">");
                    if (child.getValue() != null) {
                        if (Objects.equals(child.getName(), "version")) {
                            sb.append("(?<before>\\s+|<!--.*?-->)*").append("(?<version>").append(child.getValue()).append(")").append("(?<after>\\s+|<!--.*?-->)*");
                        } else {
                            sb.append("(\\s+|<!--.*?-->)*").append(child.getValue()).append("(\\s+|<!--.*?-->)*");
                        }
                    } else {
                        sb.append(".*?");
                    }
                    sb.append("</").append(child.getName()).append(">");
                });
                sb.append("(\\s+|<!--.*?-->)*</").append(container.getName()).append(">");
                Pattern pattern = Pattern.compile(sb.toString(), Pattern.DOTALL);
                Matcher matcher = pattern.matcher(pom);
                if (!matcher.find()) {
                    throw new IllegalStateException(String.format("Failed to find GAV [%s] with pattern: '%s' in pom:\n%s", gav, pattern, pom));
                } else {
                    String originalVersion = versionDom.getValue();
                    String newVersion = gav.getVersion();
                    int start = matcher.start("version");
                    int end = matcher.end("version");
                    String updatedPom = pom.substring(0, start) + newVersion + pom.substring(end);
                    setPom(updatedPom);
                    log.info("Updated GAV: {}:{} {} -> {}", gav.getGroupId(), gav.getArtifactId(), originalVersion, newVersion);
                }
            }
        }
        children.stream()
                .filter(child -> !Set.of("groupId", "artifactId", "version").contains(child.getName()))
                .filter(child -> child.getChildren().length > 0)
                .forEach(child -> updateGAV(child, gav));
    }

    public Set<GAV> getGAVs() {
        try (Reader reader = new StringReader(pom)) {
            Xpp3Dom dom = Xpp3DomBuilder.build(reader);
            return Arrays.stream(dom.getChildren()).map(this::getGAVs).flatMap(Collection::stream).collect(Collectors.toSet());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Set<GAV> getGAVs(Xpp3Dom container) {
        Set<GAV> gavs = new HashSet<>();
        if (container.getChild("groupId") != null && container.getChild("artifactId") != null && container.getChild("version") != null) {
            String groupId = container.getChild("groupId").getValue();
            String artifactId = container.getChild("artifactId").getValue();
            String version = container.getChild("version").getValue();
            if (groupId != null && artifactId != null && version != null) {
                groupId = groupId.trim();
                artifactId = artifactId.trim();
                version = version.trim();
                gavs.add(new GAV(groupId, artifactId, version));
            }
        }
        gavs.addAll(Arrays.stream(container.getChildren())
                .filter(child -> !Set.of("groupId", "artifactId", "version").contains(child.getName()))
                .filter(child -> child.getChildren().length > 0)
                .map(this::getGAVs)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet()));
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
