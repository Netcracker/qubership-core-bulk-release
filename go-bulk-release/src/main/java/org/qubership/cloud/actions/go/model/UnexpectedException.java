package org.qubership.cloud.actions.go.model;

public class UnexpectedException extends RuntimeException {
    public UnexpectedException(String message) {
        super(message);
    }

    public UnexpectedException(Throwable cause) {
        super(cause);
    }

    public UnexpectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
