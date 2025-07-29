package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.List;

@Data
public class RenovateConfig {
    String username;
    String gitAuthor;
    String platform;
    String commitMessage;
    List<String> baseBranchPatterns;
    List<String> globalExtends;
    String commitMessagePrefix;
    String dryRun;
    String branchPrefix;
    String branchPrefixOld;
    boolean onboarding;
    int prConcurrentLimit = 20;
    int prHourlyLimit = 5;
    int branchConcurrentLimit = 5;
    List<String> repositories;
    List<RenovateHostRule> hostRules;
    List<RenovatePackageRule> packageRules;
    List<String> labels;
}
