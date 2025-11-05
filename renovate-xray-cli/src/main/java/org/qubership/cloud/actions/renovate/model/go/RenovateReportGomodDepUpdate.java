package org.qubership.cloud.actions.renovate.model.go;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RenovateReportGomodDepUpdate {
    String bucket;
    String newVersion;
    String newValue;
    String releaseTimestamp;
    int newVersionAgeInDays;
    int newMajor;
    int newMinor;
    int newPatch;
    String updateType;
    boolean isBreaking;
    String branchName;
}
