package org.qubership.cloud.actions.go.util;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.UnexpectedException;

import java.io.*;
import java.util.*;

@Slf4j
public class CommandRunner {

    public static void exec(String... command) throws CommandExecutionException {
        exec(null, new LoggingOutputProcessor(), command);
    }

    public static void exec(File directory, String... command) throws CommandExecutionException {
        exec(directory, new LoggingOutputProcessor(), command);
    }

    public static List<String> execWithResult(String... command) throws CommandExecutionException {
        CollectingOutputProcessor outputProcessor = new CollectingOutputProcessor();
        exec(null, outputProcessor, command);
        return outputProcessor.getResult();
    }

    public static List<String> execWithResult(File directory, String... command) throws CommandExecutionException {
        CollectingOutputProcessor outputProcessor = new CollectingOutputProcessor();
        exec(directory, outputProcessor, command);
        return outputProcessor.getResult();
    }

    static void exec(File directory, OutputProcessor processor, String... command) throws CommandExecutionException {
        try {
            String cmd = String.join(" ", command);
            log.info("Run command '{}'", cmd);
            ProcessBuilder processBuilder = new ProcessBuilder(Arrays.asList(command));
            if (directory != null) {
                processBuilder.directory(directory);
            }
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processor.onLine(line);
                }
            }

            process.waitFor();
            log.info("Command: '{}' ended with code: {}", cmd, process.exitValue());
            if (process.exitValue() != 0) {
                processor.onFail();
                throw new CommandExecutionException("Failed to execute cmd: " + Arrays.toString(command) + " in directory " + directory + ". See logs for more info");
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            processor.onFail();
            throw new UnexpectedException(e);
        }
    }

    private interface OutputProcessor {
        void onLine(String line);

        void onFail();
    }

    private static class LoggingOutputProcessor implements OutputProcessor {
        @Override
        public void onLine(String line) {
            log.debug("cmd out: {}", line);
        }

        @Override
        public void onFail() {
            //do nothing
        }
    }

    private static class CollectingOutputProcessor extends LoggingOutputProcessor {
        private final List<String> result = new ArrayList<>();

        @Override
        public void onLine(String line) {
            result.add(line);
            super.onLine(line);
        }

        List<String> getResult() {
            return Collections.unmodifiableList(result);
        }

        @Override
        public void onFail() {
            result.forEach(log::warn);
        }
    }
}
