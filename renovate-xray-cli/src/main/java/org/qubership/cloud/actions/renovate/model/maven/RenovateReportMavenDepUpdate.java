package org.qubership.cloud.actions.renovate.model.maven;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RenovateReportMavenDepUpdate {
    String bucket;
    String newVersion;
    String newValue;
    String releaseTimestamp;
    int newVersionAgeInDays;
    String registryUrl;
    int newMajor;
    int newMinor;
    int newPatch;
    String updateType;
    boolean isBreaking;
    String branchName;
}
