package org.qubership.cloud.actions.go.publish;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.Config;
import org.qubership.cloud.actions.go.model.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Slf4j
public class ResultPublisher {
    private final Config config;

    public ResultPublisher(Config config) {
        this.config = config;
    }

    public void publish(final Result result) {
        publishResultFiles(result);
    }

    private void publishResultFiles(Result result) {
        String summaryFile = config.getSummaryFile();
        String resultOutputFile = config.getResultOutputFile();
        String gavsResultFile = config.getGavsResultFile();
        String dependencyGraphFile = config.getDependencyGraphFile();

        if (isNotBlank(summaryFile)) {
            try {
                Path summaryPath = Paths.get(summaryFile);
                String md = ReleaseSummary.md(result);
                Files.writeString(summaryPath, md, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                log.error("Failed to write summary to file {}", summaryFile, e);
            }
        }
        if (isNotBlank(resultOutputFile)) {
            try {
                Path resultPath = Paths.get(resultOutputFile);
                String gavsResult = ReleaseSummary.gavs(result);
                Files.writeString(resultPath, String.format("result=%s", gavsResult), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                log.error("Failed to write result to file {}", resultOutputFile, e);
            }
        }
        if (isNotBlank(gavsResultFile)) {
            try {
                Path resultPath = Paths.get(gavsResultFile);
                String gavsResult = ReleaseSummary.gavs(result).replace(",", "\n");
                Files.writeString(resultPath, gavsResult, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                log.error("Failed to write GAVs to file {}", gavsResultFile, e);
            }
        }
        if (isNotBlank(dependencyGraphFile)) {
            try {
                Path resultPath = Paths.get(dependencyGraphFile);
                String graph = ReleaseSummary.dependencyGraphDOT(result);
                Files.writeString(resultPath, graph, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                log.error("Failed to write dependency graph to file {}", dependencyGraphFile, e);
            }
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
