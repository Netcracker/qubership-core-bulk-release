package org.qubership.cloud.actions.go.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.Arrays;

@Slf4j
public class CommandRunner {

    public static void runCommand(String... command) {
        runCommand(null, command);
    }

    public static void runCommand(File directory, String... command) {
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
                    log.info("cmd out: {}", line);
                }
            }

            process.waitFor();
            log.info("Command: '{}' ended with code: {}", cmd, process.exitValue());
            if (process.exitValue() != 0) {
                throw new RuntimeException("Failed to execute cmd");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
