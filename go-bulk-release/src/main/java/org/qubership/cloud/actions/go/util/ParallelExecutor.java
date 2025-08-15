package org.qubership.cloud.actions.go.util;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.TraceableFuture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@Slf4j
public class ParallelExecutor {

    public static <T> CommandBuilder<T> forEachIn(Collection<T> collection) {
        return new CommandBuilder<>(collection);
    }

    public static final class CommandBuilder<T> {
        private final Collection<T> collection;
        private Predicate<? super T> filter = t -> true;
        private int threadsCount = 1;

        private CommandBuilder(Collection<T> collection) {
            this.collection = collection;
        }

        public CommandBuilder<T> filter(Predicate<? super T> filter) {
            if (filter != null) {
                this.filter = filter;
            }
            return this;
        }

        public CommandBuilder<T> inParallelOn(int threadsCount) {
            this.threadsCount = threadsCount;
            return this;
        }

        public <R> List<R> execute(BiFunction<? super T, ? super OutputStream, ? extends R> function) {
            try (ExecutorService executorService = Executors.newFixedThreadPool(threadsCount)) {
                return collection.stream()
                        .filter(filter == null ? element -> true : filter)
                        .map(element -> {
                            try {
                                UUID id = UUID.randomUUID();
                                log.info("Executing command for element {}. Run id = {}", element, id);
                                PipedOutputStream out = new PipedOutputStream();
                                PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
                                Future<R> future = executorService.submit(() -> {
                                    try (out) {
                                        log.debug("Start function for run with id = {}", id);
                                        R result = function.apply(element, out);
                                        log.debug("Function for run with id = {} completed", id);
                                        return result;
                                    }
                                });
                                return new TraceableFuture<>(future, pipedInputStream, element, id);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        }).toList()
                        .stream()
                        .map(future -> {
                            log.debug("VLLA start map after function run");
                            try (PipedInputStream pipedInputStream = future.getPipedInputStream();
                                 BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
                                String line;
                                log.info("Reading logs from command {}... [START]", future.getId());
                                while ((line = reader.readLine()) != null) {
                                    log.debug(line);
                                }
                                log.info("Reading logs from command {}... [FINISH]", future.getId());
                                return future.getFuture().get();
                            } catch (Exception e) {
                                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                                //todo vlla form correct exception
                                throw new RuntimeException(e);
                            }
                        }).toList();
            }
        }
    }
}
