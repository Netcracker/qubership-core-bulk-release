package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportDockerfile {
    String packageFile;
    List<RenovateReportDockerfileDep> deps;
}
