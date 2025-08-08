package org.qubership.cloud.actions.renovate;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GA;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.maven.model.MavenVersion;
import org.qubership.cloud.actions.maven.model.RepositoryConfig;
import org.qubership.cloud.actions.renovate.model.RenovateConfig;
import org.qubership.cloud.actions.renovate.model.RenovateMap;
import org.qubership.cloud.actions.renovate.model.RenovateParam;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(description = "maven effective dependencies cli")
@Slf4j
public class RenovateConfigCli implements Runnable {

    @CommandLine.Option(names = {"--platform"}, required = true, description = "platform")
    private String platform;

    @CommandLine.Option(names = {"--gavs"}, split = ",",
            description = "comma seperated list of GAVs to be used for building renovate config", converter = GAVConverter.class)
    private Set<GAV> gavs = new HashSet<>();

    @CommandLine.Option(names = {"--gavsFile"}, description = "file of GAVs seperated by new-line to be used for building renovate config")
    private String gavsFile;

    @CommandLine.Option(names = {"--param"},
            description = "base renovate config param like 'platform', 'dryRun' etc",
            converter = RenovateParamConverter.class)
    private List<RenovateParam> params = new LinkedList<>();

    @CommandLine.Option(names = {"--repository"}, required = true,
            description = "comma seperated list of git urls to all repositories to be used for building renovate config",
            converter = RepositoryConfigConverter.class)
    private Set<RepositoryConfig> repositories;

    @CommandLine.Option(names = {"--packageRule"},
            description = "Default packageRule to be included for building renovate config",
            converter = RenovateMapConverter.class)
    private List<RenovateMap> defaultPackageRules = new ArrayList<>();

    @CommandLine.Option(names = {"--hostRule"},
            description = "hostRule to be used for building renovate config",
            converter = RenovateMapConverter.class)
    private List<RenovateMap> hostRules;

    @CommandLine.Option(names = {"--labels"}, split = ",", description = "comma seperated list of labels to be used for building renovate config")
    private List<String> labels;

    @CommandLine.Option(names = {"--renovateConfigOutputFile"}, required = true, description = "File path to save result to")
    private String renovateConfigOutputFile;

    public static void main(String... args) {
        System.exit(run(args));
    }

    protected static int run(String... args) {
        return new CommandLine(new RenovateConfigCli()).execute(args);
    }

    @Override
    public void run() {
        try {
            RenovateConfig config = new RenovateConfig();
            config.put("platform", platform);
            config.putAll(params.stream().collect(Collectors.toMap(RenovateParam::getKey, RenovateParam::getValue,
                    (v1, v2) -> v2, LinkedHashMap::new)));
            config.put("repositories", repositories.stream().map(RepositoryConfig::getDir).toList());
            config.put("baseBranchPatterns", repositories.stream()
                    .map(RepositoryConfig::getBranch)
                    .filter(Objects::nonNull)
                    .filter(b -> !"HEAD".equals(b))
                    .toList());
            // group by the same groupId and version
            if (gavsFile != null) {
                Files.readAllLines(Path.of(gavsFile)).stream().filter(l -> !l.isBlank()).map(GAV::new).forEach(gavs::add);
            }
            List<RenovateMap> packageRules = new ArrayList<>(defaultPackageRules);
            packageRules.addAll(gavs.stream()
                    .sorted(Comparator.comparing(GAV::getGroupId).thenComparing(GAV::getArtifactId))
                    .collect(Collectors.toMap(GA::getGroupId, gav -> {
                                LinkedHashMap<String, Set<String>> versionToArtifactIds = new LinkedHashMap<>();
                                HashSet<String> set = new HashSet<>();
                                set.add(gav.getArtifactId());
                                versionToArtifactIds.put(gav.getVersion(), set);
                                return versionToArtifactIds;
                            },
                            (m1, m2) -> {
                                m2.forEach((k, v) -> m1.computeIfAbsent(k, k1 -> new HashSet<>()).addAll(v));
                                return m1;
                            }))
                    .entrySet().stream()
                    .flatMap(group -> {
                        String groupId = group.getKey();
                        return group.getValue().entrySet().stream()
                                .map(versionToArtifactIds -> {
                                    String version = versionToArtifactIds.getKey();
                                    Set<String> artifactIds = versionToArtifactIds.getValue();
                                    RenovateMap rule = new RenovateMap();
                                    rule.put("matchPackageNames", artifactIds.stream()
                                            .map(artifactId -> groupId + ":" + artifactId)
                                            .sorted()
                                            .toList());
                                    MavenVersion mavenVersion = new MavenVersion(version);
//                                    mavenVersion.update(VersionIncrementType.PATCH, 0);
//                                    rule.put("allowedVersions", "~" +mavenVersion);
                                    rule.put("allowedVersions", mavenVersion.toString());
                                    rule.put("groupName", groupId);
                                    return rule;
                                });
                    })
                    .toList());

            if (hostRules != null && !hostRules.isEmpty()) {
                List<String> mavenHosts = hostRules.stream()
                        .filter(h -> "maven".equals(h.get("hostType")))
                        .map(m -> (String) m.get("matchHost")).toList();
                RenovateMap mavenPackageRule = new RenovateMap();
                mavenPackageRule.put("matchDatasources", List.of("maven"));
                mavenPackageRule.put("registryUrls", mavenHosts);
                List<RenovateMap> mavenPackageRules = List.of(mavenPackageRule);

                config.put("hostRules", hostRules);
                packageRules = Stream.concat(mavenPackageRules.stream(), packageRules.stream()).toList();
            }
            config.put("packageRules", packageRules);
            String result = RenovateConfigToJsConverter.convert(config);
            if (renovateConfigOutputFile != null && !renovateConfigOutputFile.isBlank()) {
                // write the result
                try {
                    Path resultPath = Path.of(renovateConfigOutputFile);
                    log.info("Writing to {} result:\n{}", resultPath, result);
                    Files.writeString(resultPath, result, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", renovateConfigOutputFile, e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build renovate config", e);
        }
    }
}
