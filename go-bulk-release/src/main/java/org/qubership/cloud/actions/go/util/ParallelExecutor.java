package org.qubership.cloud.actions.go.util;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;

@Slf4j
public class ParallelExecutor {

    private ParallelExecutor() {}

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

        public <R> List<R> execute(Function<T, R> function) {
            Objects.requireNonNull(function, "function must not be null");
            if (threadsCount <= 0) {
                throw new IllegalArgumentException("threadsCount must be > 0");
            }

            final List<T> items = collection.stream()
                    .filter(filter != null ? filter : e -> true)
                    .toList();

            try (ExecutorService pool = Executors.newFixedThreadPool(threadsCount)) {
                final List<CompletableFuture<R>> futures = items.stream()
                        .map(element -> {
                            final UUID id = UUID.randomUUID();
                            log.info("Executing command for element {}. Run id = {}", element, id);
                            return CompletableFuture.supplyAsync(() -> {
                                log.debug("Start function for run with id = {}", id);
                                try {
                                    return function.apply(element);
                                } finally {
                                    log.debug("Function for run with id = {} completed", id);
                                }
                            }, pool);
                        }).toList();

                final List<R> results = new ArrayList<>(futures.size());
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        results.add(futures.get(i).join());
                    } catch (CompletionException ce) {
                        Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                        T failedElement = items.get(i);
                        throw new TaskExecutionException("Task failed for element " + failedElement, cause);
                    }
                }
                return results;
            }
        }
    }
}
