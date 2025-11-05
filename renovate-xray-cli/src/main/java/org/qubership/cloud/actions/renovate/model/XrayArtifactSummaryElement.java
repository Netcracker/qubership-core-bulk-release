package org.qubership.cloud.actions.renovate.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class XrayArtifactSummaryElement {
    XrayArtifactSummaryGeneral general;
    List<XrayArtifactSummaryIssue> issues = new ArrayList<>();
}
