package org.qubership.cloud.actions.renovate.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class XrayArtifactSummaryIssue {
    String issue_id;
    String summary;
    String description;
    String issue_type;
    Severity severity;
    String provider;
    List<XrayArtifactSummaryCVE> cves;
    String created;
    List<String> impact_path;
}
