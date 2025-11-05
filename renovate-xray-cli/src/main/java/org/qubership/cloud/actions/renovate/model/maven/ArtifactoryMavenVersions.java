package org.qubership.cloud.actions.renovate.model.maven;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactoryMavenVersions {
    List<ArtifactoryVersion> results;
}
