package org.qubership.cloud.actions.go.model;

public class GoGAV extends GAV {

    public GoGAV(String artifactId, String version) {
        super("GO", artifactId, version);
    }

    @Override
    public String toString() {
        return String.format("%s:%s", artifactId, version);
    }
}
