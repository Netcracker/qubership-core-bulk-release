package org.qubership.cloud.actions.go.model.gomod;

import lombok.Getter;

import java.nio.file.Path;
import java.util.Set;

@Getter
public class GoModFile {
    private final String moduleName;
    private final Set<GoDependency> dependencies;
    private final Path file;

    public GoModFile(String moduleName, Set<GoDependency> dependencies, Path file) {
        this.moduleName = moduleName;
        this.dependencies = dependencies;
        this.file = file;
    }
}
