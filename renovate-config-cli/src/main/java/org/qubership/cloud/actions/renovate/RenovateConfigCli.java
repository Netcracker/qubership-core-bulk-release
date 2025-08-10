package org.qubership.cloud.actions.renovate;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.*;
import org.qubership.cloud.actions.renovate.model.RenovateConfig;
import org.qubership.cloud.actions.renovate.model.RenovateMap;
import org.qubership.cloud.actions.renovate.model.RenovateParam;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(description = "maven effective dependencies cli")
@Slf4j
public class RenovateConfigCli implements Runnable {

    @CommandLine.Option(names = {"--platform"}, required = true, description = "platform")
    private String platform;

    @CommandLine.Option(names = {"--gavs", "--strictGavs"}, split = ",",
            description = "comma seperated list of GAVs to be used for building renovate config", converter = GAVConverter.class)
    private Set<GAV> gavs = new HashSet<>();

    @CommandLine.Option(names = {"--gavsFile", "--strictGavsFile"}, description = "file of GAVs seperated by new-line to be used for building renovate config")
    private String gavsFile;

    @CommandLine.Option(names = {"--patchGavs"}, split = ",",
            description = "comma seperated list of GAVs to be used for building renovate config with packageRule with allowedVersions='<{major}.{minor+1}'",
            converter = GAVConverter.class)
    private Set<GAV> patchGavs = new HashSet<>();

    @CommandLine.Option(names = {"--patchGavsFile"},
            description = "file of GAVs seperated by new-line to be used for building renovate config with packageRule with allowedVersions='<{major}.{minor+1}'")
    private String patchGavsFile;

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
            if (patchGavsFile != null) {
                Files.readAllLines(Path.of(patchGavsFile)).stream().filter(l -> !l.isBlank()).map(GAV::new).forEach(patchGavs::add);
            }
            List<RenovateMap> packageRules = new ArrayList<>(defaultPackageRules);
            BiFunction<Collection<GAV>, VersionIncrementType, Collection<RenovateMap>> gavsToRulesFunc =
                    (gavs, type) -> gavs.stream()
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
                                            rule.put("groupName", groupId);
                                            MavenVersion mavenVersion = new MavenVersion(version);
                                            if (type == null) {
                                                rule.put("allowedVersions", mavenVersion.toString());
                                            } else if (type == VersionIncrementType.PATCH) {
                                                rule.put("allowedVersions", String.format("<%d.%d.0",
                                                        mavenVersion.getMajor(), mavenVersion.getMinor() + 1));
                                            } else {
                                                throw new IllegalArgumentException(String.format("Unsupported version increment type '%s' to build a packageRule", type));
                                            }
                                            return rule;
                                        });
                            })
                            .toList();
            packageRules.addAll(gavsToRulesFunc.apply(gavs, null));
            packageRules.addAll(gavsToRulesFunc.apply(patchGavs, VersionIncrementType.PATCH));

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
