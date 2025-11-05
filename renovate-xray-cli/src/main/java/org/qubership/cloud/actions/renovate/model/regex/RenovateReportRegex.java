package org.qubership.cloud.actions.renovate.model.regex;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.qubership.cloud.actions.renovate.model.docker.RenovateReportDockerfileDep;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportRegex {
    String packageFile;
    List<RenovateReportRegexDep> deps;
}
