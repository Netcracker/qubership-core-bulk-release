package org.qubership.cloud.actions.renovate.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class XrayArtifactSummaryCVE {
    String cve;
    List<String> cwe;
    String cvss_v3;
}
