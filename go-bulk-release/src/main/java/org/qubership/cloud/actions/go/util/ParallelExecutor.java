package org.qubership.cloud.actions.go.util;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.TraceableFuture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

@Slf4j
public class ParallelExecutor<T, R> {

    private final int threadsCount;

    public ParallelExecutor(int threadsCount) {
        this.threadsCount = threadsCount;
    }

    public List<R> process(Collection<T> collection, BiFunction<T, OutputStream, R> function) {
        try (ExecutorService executorService = Executors.newFixedThreadPool(threadsCount)) {
            return collection.stream()
                    .map(element -> {
                        try {
                            PipedOutputStream out = new PipedOutputStream();
                            PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
                            Future<R> future = executorService.submit(() -> function.apply(element, out));
                            return new TraceableFuture<>(future, pipedInputStream, element);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList()
                    .stream()
                    .map(future -> {
                        try (PipedInputStream pipedInputStream = future.getPipedInputStream();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                log.info(line);
                            }
                            return future.getFuture().get();
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }).toList();
        }
    }
}
