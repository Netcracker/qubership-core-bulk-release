package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.List;

@Data
public class RenovateConfig {
    String username;
    String gitAuthor;
    String platform;
    String commitMessage;
    String commitMessagePrefix;
    String dryRun;
    boolean onboarding;
    int prConcurrentLimit = 20;
    int prHourlyLimit = 5;
    int branchConcurrentLimit = 5;
    List<String> repositories;
    List<RenovateHostRule> hostRules;
    List<RenovatePackageRule> packageRules;
}
