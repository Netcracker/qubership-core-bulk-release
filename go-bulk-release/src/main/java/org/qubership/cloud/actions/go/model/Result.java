package org.qubership.cloud.actions.go.model;

import lombok.Data;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;
import org.qubership.cloud.actions.go.model.repository.RepositoryRelease;

import java.util.List;
import java.util.Map;

@Data
public class Result {
    String dependenciesDot;
    List<RepositoryRelease> releases;
    Map<Integer, List<RepositoryInfo>> dependencyGraph;
    boolean dryRun;
}
