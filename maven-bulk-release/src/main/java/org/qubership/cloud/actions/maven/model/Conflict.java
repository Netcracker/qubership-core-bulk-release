package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.Map;
import java.util.TreeMap;

@Data
public class Conflict {
    final ConflictSide left;
    final ConflictSide right;
    Map<String, Map<String, ArtifactInfo>> repositories = new TreeMap<>(); // version to artifact to info

}
