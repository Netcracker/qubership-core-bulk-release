package org.qubership.cloud.actions.renovate;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GA;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.maven.model.MavenVersion;
import org.qubership.cloud.actions.renovate.model.*;
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
    @CommandLine.Option(names = {"--username"}, description = "username")
    private String username = "renovate";

    @CommandLine.Option(names = {"--gitAuthor"}, description = "gitAuthor")
    private String gitAuthor = "Renovate Bot <bot@renovate.com>";

    @CommandLine.Option(names = {"--platform"}, required = true, description = "platform")
    private String platform;

    @CommandLine.Option(names = {"--dryRun"}, description = "dry run", converter = DryRunConverter.class)
    private RenovateDryRun dryRun;

    @CommandLine.Option(names = {"--globalExtends"}, description = "globalExtends", split = ",")
    private List<String> globalExtends;

    @CommandLine.Option(names = {"--onboarding"}, description = "onboarding")
    private boolean onboarding = false;

    @CommandLine.Option(names = {"--branchConcurrentLimit"}, description = "branchConcurrentLimit")
    private int branchConcurrentLimit = 20;

    @CommandLine.Option(names = {"--prConcurrentLimit"}, description = "prConcurrentLimit")
    private int prConcurrentLimit = 20;

    @CommandLine.Option(names = {"--prHourlyLimit"}, description = "prConcurrentLimit")
    private int prHourlyLimit = 5;

    @CommandLine.Option(names = {"--commitMessage"}, description = "commit message")
    private String commitMessage;

    @CommandLine.Option(names = {"--branchPrefix"}, description = "branchPrefix")
    private String branchPrefix;

    @CommandLine.Option(names = {"--branchPrefixOld"}, description = "branchPrefixOld")
    private String branchPrefixOld;

    @CommandLine.Option(names = {"--commitMessagePrefix"}, description = "commit message prefix")
    private String commitMessagePrefix;

    @CommandLine.Option(names = {"--gavs"}, split = ",",
            description = "comma seperated list of GAVs to be used for building renovate config", converter = GAVConverter.class)
    private Set<GAV> gavs = new HashSet<>();

    @CommandLine.Option(names = {"--gavsFile"}, description = "file of GAVs seperated by new-line to be used for building renovate config")
    private String gavsFile;

    @CommandLine.Option(names = {"--repositories"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of git urls to all repositories to be used for building renovate config",
            converter = RepositoryConfigConverter.class)
    private Set<RepositoryConfig> repositories;

    @CommandLine.Option(names = {"--packageRules"}, split = ",",
            description = "comma seperated list of default packageRules to be included for building renovate config",
            converter = RenovatePackageRuleConverter.class)
    private List<RenovatePackageRule> defaultPackageRules = new ArrayList<>();

    @CommandLine.Option(names = {"--hostRules"}, split = ",",
            description = "comma seperated list of hostRules to be used for building renovate config",
            converter = RenovateHostRuleConverter.class)
    private List<RenovateHostRule> hostRules;

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
            config.setUsername(username);
            config.setGitAuthor(gitAuthor);
            config.setPlatform(platform);
            config.setCommitMessage(commitMessage);
            config.setCommitMessagePrefix(commitMessagePrefix);
            config.setPrHourlyLimit(prHourlyLimit);
            config.setPrConcurrentLimit(prConcurrentLimit);
            config.setBranchConcurrentLimit(branchConcurrentLimit);
            config.setOnboarding(onboarding);
            config.setGlobalExtends(globalExtends);
            config.setBranchPrefix(branchPrefix);
            config.setBranchPrefixOld(branchPrefixOld);
            config.setRepositories(repositories.stream().map(RepositoryConfig::getName).toList());
            config.setBaseBranchPatterns(repositories.stream().map(RepositoryConfig::getBranch).filter(Objects::nonNull).toList());

            // group by the same groupId and version
            if (gavsFile != null) {
                Files.readAllLines(Path.of(gavsFile)).stream().filter(l -> !l.isBlank()).map(GAV::new).forEach(gavs::add);
            }
            List<RenovatePackageRule> packageRules = new ArrayList<>(defaultPackageRules);
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
                                    RenovatePackageRule rule = new RenovatePackageRule();
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
            config.setPackageRules(packageRules);
            config.setLabels(labels);

            if (dryRun != null) config.setDryRun(dryRun.name());
            if (hostRules != null && !hostRules.isEmpty()) {
                List<String> mavenHosts = hostRules.stream()
                        .filter(h -> "maven".equals(h.getHostType()))
                        .map(RenovateHostRule::getMatchHost).toList();
                RenovatePackageRule mavenPackageRule = new RenovatePackageRule();
                mavenPackageRule.put("matchDatasources", List.of("maven"));
                mavenPackageRule.put("registryUrls", mavenHosts);
                List<RenovatePackageRule> mavenPackageRules = List.of(mavenPackageRule);

                config.setHostRules(hostRules);
                config.setPackageRules(Stream.concat(mavenPackageRules.stream(), packageRules.stream()).toList());
            }
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
