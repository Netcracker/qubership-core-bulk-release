package org.qubership.cloud.actions.go.proxy;

import org.qubership.cloud.actions.go.model.GAV;
import org.qubership.cloud.actions.go.util.CommandRunner;

import java.nio.file.Path;

//todo vlla merge with GoModFile
public class GoModule {
    private final Path repo;

    public GoModule(Path repo) {
        this.repo = repo;
    }

    public void get(GAV gav) {
        String lib = gav.getArtifactId() + "@" + gav.getVersion();
        get(lib);
    }

    public void get(String lib) {
        CommandRunner.runCommand(repo.toFile(), "go", "get", lib);
    }

    public void tidy() {
        CommandRunner.runCommand(repo.toFile(), "go", "mod", "tidy");
    }
}
