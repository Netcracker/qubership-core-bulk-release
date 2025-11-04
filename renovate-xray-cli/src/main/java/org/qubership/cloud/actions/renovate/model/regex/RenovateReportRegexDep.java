package org.qubership.cloud.actions.renovate.model.regex;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.qubership.cloud.actions.renovate.model.docker.RenovateReportDockerfileDepUpdate;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportRegexDep {
    String datasource;
    String versioning;
    String depName;
    String currentValue;
    List<RenovateReportRegexDepUpdate> updates;
    String packageName;
    String currentVersion;
    String fixedVersion;
}
