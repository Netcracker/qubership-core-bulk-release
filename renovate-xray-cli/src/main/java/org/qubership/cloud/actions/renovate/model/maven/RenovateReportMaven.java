package org.qubership.cloud.actions.renovate.model.maven;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportMaven {
    String datasource;
    String packageFile;
    String packageFileVersion;
    List<RenovateReportMavenDep> deps;
}
