package org.qubership.cloud.actions.go;

import org.qubership.cloud.actions.go.doc.ReleaseSummary;
import org.qubership.cloud.actions.go.model.Config;
import org.qubership.cloud.actions.go.model.RepositoryConfig;
import org.qubership.cloud.actions.go.model.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ReleaseSummaryService {
    private final GitService gitService = new GitService();

    public void publishReleaseSummary(Config config, Result result) {
        try {
            //todo vlla change URL

            RepositoryConfig repositoryConfig = RepositoryConfig.builder("https://github.com/TaurMorchant/infra")
                    .build();
            gitService.gitCheckout(config.getBaseDir(), config.getGitConfig(), repositoryConfig);

            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM.dd.yyyy HH:mm"));
            String summaryFileName = "Release " + time + ".md";

            Path summaryFile = Paths.get(config.getBaseDir(), repositoryConfig.getDir(), "/releases/" + summaryFileName);

            String md = ReleaseSummary.md(result);

            Path parent = summaryFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(
                    summaryFile,
                    md,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            gitService.commitAdded(Paths.get(config.getBaseDir(), repositoryConfig.getDir()).toFile(), "Added " + summaryFileName);

            gitService.pushChanges(config.getGitConfig(), Paths.get(config.getBaseDir(), repositoryConfig.getDir()).toFile(), null);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
