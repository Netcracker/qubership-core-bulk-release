package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportPackageFiles {
    List<RenovateReportMaven> maven;
    List<RenovateReportGomod> gomod;
    List<RenovateReportDockerfile> dockerfile;
}
