package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.List;

@Data
public class RenovatePackageRule {
    List<String> matchPackageNames;
    String allowedVersions;
}
