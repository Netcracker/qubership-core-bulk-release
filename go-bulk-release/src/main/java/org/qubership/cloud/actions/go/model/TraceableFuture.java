package org.qubership.cloud.actions.go.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.PipedInputStream;
import java.util.concurrent.Future;

@Data
@AllArgsConstructor
public class TraceableFuture<T, S> {
    Future<T> future;
    PipedInputStream pipedInputStream;
    S object;
}
