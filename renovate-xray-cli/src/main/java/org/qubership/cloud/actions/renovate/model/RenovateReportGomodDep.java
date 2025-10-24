package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportGomodDep {
    String datasource;
    String versioning;
    String depType;
    String depName;
    String currentValue;
    List<RenovateReportGomodDepUpdate> updates;
    String packageName;
    String skipReason;
    String currentVersion;
    String currentVersionTimestamp;
    int currentVersionAgeInDays;
    String fixedVersion;
}
