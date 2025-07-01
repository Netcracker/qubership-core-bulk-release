package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.List;

@Data
public class RenovateConfig {
    String username;
    String gitAuthor;
    String platform;
    String dryRun;
    boolean onboarding;
    List<String> repositories;
    List<RenovateHostRule> hostRules;
    List<RenovatePackageRule> packageRules;
}
