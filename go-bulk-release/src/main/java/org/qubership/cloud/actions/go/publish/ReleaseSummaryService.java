package org.qubership.cloud.actions.go.publish;

import org.qubership.cloud.actions.go.GitService;
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
    public static final String RELEASE_SUMMARY_FILE_NAME_PATTERN = "Release %s.md";
    public static final String DATE_PATTERN = "MM.dd.yyyy HH.mm";

    private final GitService gitService;
    private final Config config;

    public ReleaseSummaryService(Config config) {
        this.config = config;
        this.gitService = new GitService(config.getGitConfig());
    }

    public void publishReleaseSummary(Result result) {
        try {
            //todo vlla change URL
            RepositoryConfig repositoryConfig = RepositoryConfig.builder("https://github.com/TaurMorchant/infra")
                    .build();

            Path repository = Paths.get(config.getBaseDir(), repositoryConfig.getDir());

            gitService.gitCheckout(repository, repositoryConfig);

            String summaryFileName = getSummaryFileName();
            writeSummaryFile(repository, summaryFileName, result);

            gitService.commitAdded(repository.toFile(), "Added " + summaryFileName);
            gitService.pushChanges(repository.toFile(), null);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeSummaryFile(Path repository, String summaryFileName, Result result) throws Exception {
        Path summaryFilePath = getSummaryFilePath(repository, summaryFileName);
        String summaryFileContent = ReleaseSummary.md(result);

        Path parent = summaryFilePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(
                summaryFilePath,
                summaryFileContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private String getSummaryFileName() {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_PATTERN));
        return RELEASE_SUMMARY_FILE_NAME_PATTERN.formatted(time);
    }

    private Path getSummaryFilePath(Path repository, String summaryFileName) {
        return repository.resolve("/releases/" + summaryFileName);
    }
}
