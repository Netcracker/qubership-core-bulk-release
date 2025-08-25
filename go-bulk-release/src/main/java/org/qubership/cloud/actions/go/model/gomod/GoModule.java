package org.qubership.cloud.actions.go.model.gomod;

import org.qubership.cloud.actions.go.model.GoGAV;
import org.qubership.cloud.actions.go.util.CommandRunner;

import java.nio.file.Path;
import java.util.Set;

public record GoModule(String moduleName, Set<GoGAV> dependencies, Path file) {
    public void get(GoGAV gav) {

        String lib = gav.getArtifactId() + "@" + gav.getVersion();
        get(lib);
    }


    //todo vlla JUST FOR TEST gomajor
    public void get(String lib) {
        CommandRunner.exec(file.getParent().toFile(), "gomajor", "get", lib);
    }

    public void tidy() {
        CommandRunner.exec(file.getParent().toFile(), "go", "mod", "tidy");
    }
}
