package org.qubership.cloud.actions.go.model.gomod;

import org.qubership.cloud.actions.go.model.GoGAV;
import org.qubership.cloud.actions.go.model.ReleaseTerminationException;
import org.qubership.cloud.actions.go.util.CommandExecutionException;
import org.qubership.cloud.actions.go.util.CommandRunner;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

public record GoModule(String moduleName, Set<GoGAV> dependencies, Path file) {
    public void get(GoGAV gav) {
        String lib = gav.getArtifactId() + "@" + gav.getVersion();
        get(lib);
    }

    public File getModuleDir() {
        return file.getParent().toFile();
    }

    public void get(String lib) {
        try {
            CommandRunner.exec(getModuleDir(), "gomajor", "get", lib);
        }
        catch (CommandExecutionException e) {
            String msg = "Cannot perform 'go get' for lib '%s' in module '%s'".formatted(lib, moduleName);
            throw new ReleaseTerminationException(msg, e);
        }
    }

    public void tidy() {
        try {
            CommandRunner.exec(getModuleDir(), "go", "mod", "tidy");
        }
        catch (CommandExecutionException e) {
            String msg = "Cannot perform 'go mod tidy' in module '%s'".formatted(moduleName);
            throw new ReleaseTerminationException(msg, e);
        }
    }

    public void modDownload() {
        try {
            CommandRunner.exec(getModuleDir(), "go", "mod", "download", "all");
        }
        catch (CommandExecutionException e) {
            String msg = "Cannot perform 'go mod download all' in module '%s'".formatted(moduleName);
            throw new ReleaseTerminationException(msg, e);
        }
    }
}
