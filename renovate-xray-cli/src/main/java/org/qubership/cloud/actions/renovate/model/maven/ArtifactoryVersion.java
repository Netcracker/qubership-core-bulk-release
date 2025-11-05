package org.qubership.cloud.actions.renovate.model.maven;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactoryVersion {
    String version;
    boolean integration;
}
