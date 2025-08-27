package org.qubership.cloud.actions.maven.model;

import lombok.Data;

@Data
public class ConflictSide {
    final String from;
    final String version;
}
