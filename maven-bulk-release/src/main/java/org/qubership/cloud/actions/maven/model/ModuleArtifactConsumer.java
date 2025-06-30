package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ModuleArtifactConsumer {
    final String groupId;
    final String artifactId;
    List<RepositoryArtifactConsumer> consumers = new ArrayList<>();
}
