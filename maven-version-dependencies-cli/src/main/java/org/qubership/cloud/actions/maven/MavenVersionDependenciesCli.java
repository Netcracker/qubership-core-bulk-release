package org.qubership.cloud.actions.maven;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.*;
import picocli.CommandLine;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@CommandLine.Command(description = "maven version dependencies cli")
@Slf4j
public class MavenVersionDependenciesCli implements Runnable {

    @CommandLine.Option(names = {"--baseDir"}, required = true, description = "directory to pom.xml")
    private String baseDir;

    @CommandLine.Option(names = {"--gitURL"}, required = true, description = "git host")
    private String gitURL;

    @CommandLine.Option(names = {"--gitUsername"}, required = true, description = "git username")
    private String gitUsername;

    @CommandLine.Option(names = {"--gitEmail"}, required = true, description = "git email")
    private String gitEmail;

    @CommandLine.Option(names = {"--gitPassword"}, required = true, description = "git password")
    private String gitPassword;

    @CommandLine.Option(names = {"--createMissingBranches"}, required = true,
            description = "create and push to git required support branch if it's not found")
    private boolean createMissingBranches;

    @CommandLine.Option(names = {"--validateSameVersionUpToLevel"}, description = "")
    private VersionIncrementType validateSameVersionUpToLevel = VersionIncrementType.MINOR;

    @CommandLine.Option(names = {"--repositories"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of git urls to all repositories which depend on each other and can be bulk released",
            converter = RepositoryConfigConverter.class)
    private Set<RepositoryConfig> repositories;

    @CommandLine.Option(names = {"--skipValidationForGAPatterns"}, split = ",", converter = PatternConverter.class)
    private List<Pattern> skipValidationForGAPatterns;

    @CommandLine.Option(names = {"--mavenLocalRepoPath"}, description = "custom path to maven local repository")
    private String mavenLocalRepoPath = "${user.home}/.m2/repository";

    @CommandLine.Option(names = {"--resultOutputFile"}, required = true, description = "File path to save result to")
    private String resultOutputFile;

    public static void main(String... args) {
        System.exit(new CommandLine(new MavenVersionDependenciesCli()).execute(args));
    }

    @Override
    public void run() {

        try {
            GitConfig gitConfig = GitConfig.builder()
                    .url(gitURL)
                    .username(gitUsername)
                    .email(gitEmail)
                    .password(gitPassword)
                    .build();

            MavenConfig mavenConfig = MavenConfig.builder()
                    .localRepositoryPath(mavenLocalRepoPath)
                    .build();

            // todo refactor logging
            OutputStream out = new OutputStream() {
                @Override
                public void write(int b) {
                    System.out.print((char) b);
                }
            };

            RepositoryService repositoryService = new RepositoryService();
            Map<Integer, List<RepositoryInfo>> repositoriesMap = repositoryService.buildVersionedDependencyGraph(baseDir,
                    gitConfig, mavenConfig, repositories, createMissingBranches, validateSameVersionUpToLevel,
                    skipValidationForGAPatterns, out);
            String result = repositoriesMap.values().stream()
                    .flatMap(Collection::stream)
                    .map(r -> String.format("%s[branch=%s]", r.getUrl(), r.getBranch()))
                    .collect(Collectors.joining("\n"));

            if (resultOutputFile != null && !resultOutputFile.isBlank()) {
                // write the result
                try {
                    Path resultPath = resultOutputFile.startsWith("/") ? Path.of(resultOutputFile) : Paths.get(baseDir, resultOutputFile);
                    log.info("Writing to {} result:\n{}", resultPath, result);
                    Files.writeString(resultPath, result, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", resultOutputFile, e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve effective dependencies", e);
        }
    }
}
