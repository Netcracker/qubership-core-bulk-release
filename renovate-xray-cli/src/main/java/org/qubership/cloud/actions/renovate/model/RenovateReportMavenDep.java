package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportMavenDep {
    String datasource;
    String depName;
    String currentValue;
    List<String> registryUrls;
    String depType;
    List<RenovateReportMavenDepUpdate> updates;
    String packageName;
    String versioning;
    boolean respectLatest;
    String currentVersion;
    String currentVersionTimestamp;
    int currentVersionAgeInDays;
    String fixedVersion;
}
