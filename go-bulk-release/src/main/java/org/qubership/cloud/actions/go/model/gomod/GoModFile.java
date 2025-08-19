package org.qubership.cloud.actions.go.model.gomod;

import java.nio.file.Path;
import java.util.Set;

public record GoModFile(String moduleName, Set<GoDependency> dependencies, Path file) {
}
