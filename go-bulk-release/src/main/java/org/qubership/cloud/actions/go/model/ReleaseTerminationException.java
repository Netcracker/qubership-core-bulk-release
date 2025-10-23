package org.qubership.cloud.actions.go.model;

public class ReleaseTerminationException extends RuntimeException {
    public ReleaseTerminationException(String message) {
        super(message);
    }

    public ReleaseTerminationException(String message, Throwable cause) {
        super(message, cause);
    }
}
