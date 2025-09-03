package org.qubership.cloud.actions.go;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.*;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;
import org.qubership.cloud.actions.go.publish.ResultPublisher;
import picocli.CommandLine;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings({"UnusedDeclaration"})
@CommandLine.Command(description = "go bulk release cli")
@Slf4j
public class GoBulkReleaseCli implements Runnable {

    @CommandLine.Option(names = {"--gitURL"}, required = true, description = "git host")
    private String gitURL;

    @CommandLine.Option(names = {"--gitUsername"}, required = true, description = "git username")
    private String gitUsername;

    @CommandLine.Option(names = {"--gitEmail"}, required = true, description = "git email")
    private String gitEmail;

    @CommandLine.Option(names = {"--gitPassword"}, required = true, description = "git password")
    private String gitPassword;

    @CommandLine.Option(names = {"--baseDir"}, required = true, description = "base directory to write result to")
    private String baseDir;

    @CommandLine.Option(names = {"--goProxyDir"}, description = "GOPROXY directory", defaultValue = "/tmp/GOPROXY")
    private String goProxyDir;

    @CommandLine.Option(names = {"--repositories"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of git urls to all repositories which depend on each other and can be bulk released",
            converter = RepositoryConfigConverter.class)
    private Set<RepositoryConfig> repositories;

    @CommandLine.Option(names = {"--repositoriesToReleaseFrom"}, split = "\\s*,\\s*",
            description = "comma seperated list of git urls which were changed and need to be released. Repositories which use them directly or indirectly will be released as well",
            converter = RepositoryConfigConverter.class)
    private Set<RepositoryConfig> repositoriesToReleaseFrom = Set.of();

    @CommandLine.Option(names = {"--skipTests"}, arity = "0", defaultValue = "false", description = "skip tests run by release:prepare mvn command")
    private boolean skipTests;

    @CommandLine.Option(names = {"--dryRun"}, arity = "0", defaultValue = "false", description = """
            if specified:
            1. only run release:prepare mvn command in each repository updating dependencies with versions from artifacts in dependent repositories
            if not specified:
            1. push git updates to origin
            2. deploy artifacts to distribution repository by release:perform mvn command
            """)
    private boolean dryRun;

    @CommandLine.Option(names = {"--summaryFile"}, description = "File path to save summary to")
    private String summaryFile;

    @CommandLine.Option(names = {"--resultOutputFile"}, description = "File path to save result GAVs to")
    private String resultOutputFile;

    @CommandLine.Option(names = {"--dependencyGraphFile"}, description = "File path to save dependencies graph in DOT format")
    private String dependencyGraphFile;

    @CommandLine.Option(names = {"--gavsResultFile"}, description = "File path to save dependencies graph in DOT format")
    private String gavsResultFile;

    public static void main(String... args) {
        CommandLine commandLine = new CommandLine(new GoBulkReleaseCli());
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            Config config = prepareConfig();

            Result result = new ReleaseRunner(config).release();

            new ResultPublisher(config).publish(result);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to perform go bulk release", e);
        }
    }

    private Config prepareConfig() {
        if (repositories.stream()
                .filter(Objects::nonNull)
                .toList().isEmpty()) {
            throw new IllegalArgumentException("--repositories property cannot be empty");
        }

        GitConfig gitConfig = GitConfig.builder()
                .url(gitURL)
                .username(gitUsername)
                .email(gitEmail)
                .password(gitPassword)
                .build();

        repositoriesToReleaseFrom = repositoriesToReleaseFrom.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());


        return Config.builder(baseDir, goProxyDir, gitConfig, repositories)
                .repositoriesToReleaseFrom(repositoriesToReleaseFrom)
                .skipTests(skipTests)
                .dryRun(dryRun)
                .summaryFile(summaryFile)
                .resultOutputFile(resultOutputFile)
                .dependencyGraphFile(dependencyGraphFile)
                .gavsResultFile(gavsResultFile)
                .build();
    }
}
