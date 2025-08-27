package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EffectiveDependenciesDiff {
    final Map<String, Conflict> majorConflictingGroups;
    final Map<String, Conflict> minorConflictingGroups;
    final List<GAV> gavs;

    public EffectiveDependenciesDiff(List<GAV> gavs,
                                     Map<String, Conflict> majorConflictingGroups,
                                     Map<String, Conflict> minorConflictingGroups) {
        this.gavs = gavs;
        this.majorConflictingGroups = majorConflictingGroups;
        this.minorConflictingGroups = minorConflictingGroups;
    }
}
