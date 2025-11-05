package org.qubership.cloud.actions.renovate.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
public class FixedVersionData {
    String vulnerableVersion;
    Set<String> fixedCVEs;
}
