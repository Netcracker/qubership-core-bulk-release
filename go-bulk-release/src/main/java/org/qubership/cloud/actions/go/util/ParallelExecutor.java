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
import java.util.function.Predicate;

@Slf4j
public class ParallelExecutor {

//    public List<R> process(Collection<T> collection, BiFunction<T, OutputStream, R> function) {
//        return process(collection, null, function);
//    }
//
//    public List<R> process(Collection<T> collection, Predicate<T> filter, BiFunction<T, OutputStream, R> function) {
//        try (ExecutorService executorService = Executors.newFixedThreadPool(threadsCount)) {
//            return collection.stream()
//                    .filter(filter == null ? element -> true : filter)
//                    .map(element -> {
//                        try {
//                            PipedOutputStream out = new PipedOutputStream();
//                            PipedInputStream pipedInputStream = new PipedInputStream(out, 16384);
//                            Future<R> future = executorService.submit(() -> function.apply(element, out));
//                            return new TraceableFuture<>(future, pipedInputStream, element);
//                        } catch (IOException e) {
//                            throw new IllegalStateException(e);
//                        }
//                    }).toList()
//                    .stream()
//                    .map(future -> {
//                        try (PipedInputStream pipedInputStream = future.getPipedInputStream();
//                             BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream, StandardCharsets.UTF_8))) {
//                            String line;
//                            while ((line = reader.readLine()) != null) {
//                                log.info(line);
//                            }
//                            return future.getFuture().get();
//                        } catch (Exception e) {
//                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
//                            //todo vlla form correct exception
//                            throw new RuntimeException(e);
//                        }
//                    }).toList();
//        }
//    }

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
                                //todo vlla form correct exception
                                throw new RuntimeException(e);
                            }
                        }).toList();
            }
        }
    }
}
