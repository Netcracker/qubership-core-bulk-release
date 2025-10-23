package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class XrayArtifactSummary {
    List<XrayArtifactSummaryElement> artifacts = new ArrayList<>();
    List<Map<String, Object>> errors;
}
