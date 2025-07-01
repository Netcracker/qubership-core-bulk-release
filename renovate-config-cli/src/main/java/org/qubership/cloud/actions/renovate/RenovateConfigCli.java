package org.qubership.cloud.actions.renovate;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GA;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.maven.model.MavenVersion;
import org.qubership.cloud.actions.maven.model.VersionIncrementType;
import org.qubership.cloud.actions.renovate.model.*;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command(description = "maven effective dependencies cli")
@Slf4j
public class RenovateConfigCli implements Runnable {
    @CommandLine.Option(names = {"--username"}, description = "username")
    private String username = "renovate";

    @CommandLine.Option(names = {"--gitAuthor"}, description = "gitAuthor")
    private String gitAuthor = "Renovate Bot <bot@renovate.com>";

    @CommandLine.Option(names = {"--platform"}, required = true, description = "platform")
    private String platform;

    @CommandLine.Option(names = {"--dryRun"}, description = "dry run", converter = DryRunConverter.class)
    private RenovateDryRun dryRun;

    @CommandLine.Option(names = {"--onboarding"}, description = "onboarding")
    private boolean onboarding = false;

    @CommandLine.Option(names = {"--tabSize"}, description = "tab length")
    private int tabSize = 2;

    @CommandLine.Option(names = {"--gavs"}, required = true, split = ",",
            description = "comma seperated list of GAVs to be used for building renovate config",
            converter = GAVConverter.class)
    private Set<GAV> gavs;

    @CommandLine.Option(names = {"--repositories"}, required = true, split = ",",
            description = "comma seperated list of repositories to be used for building renovate config")
    private List<String> repositories;

    @CommandLine.Option(names = {"--mavenRepositories"}, required = true,
            description = "comma seperated list of maven repositories to be used for building renovate config",
            converter = RenovateMavenRepositoryConverter.class)
    private List<RenovateMavenRepository> mavenRepositories;

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
            config.setUsername(username);
            config.setGitAuthor(gitAuthor);
            config.setPlatform(platform);
            config.setOnboarding(onboarding);
            config.setRepositories(repositories);
            // group by the same groupId and version
            config.setPackageRules(gavs.stream()
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
                                    RenovatePackageRule rule = new RenovatePackageRule();
                                    rule.setMatchPackageNames(artifactIds.stream()
                                            .map(artifactId -> groupId + ":" + artifactId)
                                            .sorted()
                                            .toList());
                                    MavenVersion mavenVersion = new MavenVersion(version);
                                    mavenVersion.update(VersionIncrementType.PATCH, 0);
                                    rule.setAllowedVersions("~" + mavenVersion);
                                    return rule;
                                });
                    })
                    .toList());
            if (dryRun != null) config.setDryRun(dryRun.name());
            if (mavenRepositories != null && !mavenRepositories.isEmpty()) {
                RenovateMaven maven = new RenovateMaven();
                maven.setRepositories(mavenRepositories);
                config.setMaven(maven);
            }
            String result = RenovateConfigToJsConverter.convert(config, tabSize);

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
