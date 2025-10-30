package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportDockerfileDep {
    String datasource;
    String versioning;
    String depType;
    String depName;
    String currentValue;
    List<RenovateReportDockerfileDepUpdate> updates;
    String packageName;
    String currentVersion;
    String fixedVersion;
    String registryUrl;
    String lookupName;
    boolean isSingleVersion;
}
