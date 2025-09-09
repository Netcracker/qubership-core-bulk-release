package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ArtifactInfo {
    List<RepositoryArtifactConsumer> consumers = new ArrayList<>();
}
