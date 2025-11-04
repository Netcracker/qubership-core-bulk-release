package org.qubership.cloud.actions.renovate.model.docker;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RenovateReportDockerfileDepUpdate {
    String bucket;
    String newVersion;
    String newValue;
    int newMajor;
    int newMinor;
    int newPatch;
    String updateType;
    boolean isBreaking;
    String branchName;

}
