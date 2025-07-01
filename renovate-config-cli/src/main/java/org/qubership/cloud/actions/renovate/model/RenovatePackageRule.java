package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.List;

@Data
public class RenovatePackageRule {
    List<String> matchDatasources;
    List<String> matchPackageNames;
    List<String> registryUrls;
    String allowedVersions;
}
