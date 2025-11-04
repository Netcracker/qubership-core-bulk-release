package org.qubership.cloud.actions.renovate.model.go;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportGomod {
    String packageFile;
    List<RenovateReportGomodDep> deps;
}
