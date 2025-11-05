package org.qubership.cloud.actions.renovate.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class XrayArtifactSummaryGeneral {
    String name;
    String component_id;
    String pkg_type;
    String path;
    String sha256;
}
