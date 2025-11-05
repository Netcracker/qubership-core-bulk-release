package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.qubership.cloud.actions.renovate.model.docker.RenovateReportDockerfile;
import org.qubership.cloud.actions.renovate.model.go.RenovateReportGomod;
import org.qubership.cloud.actions.renovate.model.maven.RenovateReportMaven;
import org.qubership.cloud.actions.renovate.model.regex.RenovateReportRegex;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportPackageFiles {
    List<RenovateReportMaven> maven;
    List<RenovateReportGomod> gomod;
    List<RenovateReportDockerfile> dockerfile;
    List<RenovateReportRegex> regex;
}
