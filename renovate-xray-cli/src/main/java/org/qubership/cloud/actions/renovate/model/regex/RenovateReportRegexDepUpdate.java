package org.qubership.cloud.actions.renovate.model.regex;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RenovateReportRegexDepUpdate {
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
