package org.qubership.cloud.actions.go.model.gomod;

import org.qubership.cloud.actions.go.model.GoGAV;
import org.qubership.cloud.actions.go.model.UnexpectedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class GoModuleFactory {
    //todo vlla parse dependencies with go lib?
    public static GoModule create(Path path) {
        try {
            boolean inRequireBlock = false;

            String moduleName = null;
            Set<GoGAV> dependencies = new HashSet<>();

            for (String line : Files.readAllLines(path)) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("//")) {
                    continue;
                }

                if (line.startsWith("module ")) {
                    moduleName = line.substring("module ".length()).trim();
                    continue;
                }

                if (line.startsWith("require (")) {
                    inRequireBlock = true;
                    continue;
                }

                if (inRequireBlock) {
                    if (line.equals(")")) {
                        inRequireBlock = false;
                        continue;
                    }
                    dependencies.add(parseDependencyLine(line));
                } else if (line.startsWith("require ")) {
                    String dep = line.substring("require ".length()).trim();
                    dependencies.add(parseDependencyLine(dep));
                }
            }

            if (moduleName != null) {
                return new GoModule(moduleName, dependencies, path);
            } else {
                throw new UnexpectedException("Module name could not be resolved for %s".formatted(path));
            }
        }
        catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    private static GoGAV parseDependencyLine(String line) {
        int commentIndex = line.indexOf("//");
        if (commentIndex != -1) {
            line = line.substring(0, commentIndex).trim();
        }

        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            return new GoGAV(parts[0], parts[1]);
        }

        throw new IllegalArgumentException("Invalid dependency: " + line);
    }

    private GoModuleFactory() {}
}
