package org.qubership.cloud.actions.go.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.*;

@Slf4j
public class CommandRunner {

    public static void exec(String... command) {
        exec(null, new LoggingOutputProcessor(), command);
    }

    public static void exec(File directory, String... command) {
        exec(directory, new LoggingOutputProcessor(), command);
    }

    public static List<String> execWithResult(String... command) {
        CollectingOutputProcessor outputProcessor = new CollectingOutputProcessor();
        exec(null, outputProcessor, command);
        return outputProcessor.getResult();
    }

    public static List<String> execWithResult(File directory, String... command) {
        CollectingOutputProcessor outputProcessor = new CollectingOutputProcessor();
        exec(directory, outputProcessor, command);
        return outputProcessor.getResult();
    }

    static void exec(File directory, OutputProcessor processor, String... command) {
        try {
            String cmd = String.join(" ", command);
            log.info("Run command '{}'", cmd);
            ProcessBuilder processBuilder = new ProcessBuilder(Arrays.asList(command));
            if (directory != null) {
                processBuilder.directory(directory);
            }
            processBuilder.redirectErrorStream(true);
            Map<String, String> env = processBuilder.environment();
            log.debug("VLLA before env = {}", env);
            env.remove("GITHUB_ACTIONS");
            env.remove("GITHUB_SHA");
            env.remove("GITHUB_REF");
            env.remove("CI");
            log.debug("VLLA after env = {}", processBuilder.environment());
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
                throw new RuntimeException("Failed to execute cmd: " + Arrays.toString(command) + " in directory " + directory);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            processor.onFail();
            throw new RuntimeException(e);
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
