package org.qubership.cloud.actions.go.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RepositoryArtifactConsumer {
    final String repository;
    List<ModuleArtifactConsumer> modules = new ArrayList<>();
}
