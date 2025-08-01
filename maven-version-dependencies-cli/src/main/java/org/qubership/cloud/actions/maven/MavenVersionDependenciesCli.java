package org.qubership.cloud.actions.maven;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.*;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @CommandLine.Option(names = {"--repositories"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of git urls to all repositories which depend on each other and can be bulk released",
            converter = RepositoryConfigConverter.class)
    private Set<RepositoryConfig> repositories;

    @CommandLine.Option(names = {"--fromRepository"}, required = true,
            description = "repository to start to resolve version tree from",
            converter = RepositoryConfigConverter.class)
    private RepositoryConfig fromRepository;

    @CommandLine.Option(names = {"--fromVersion"}, required = true,
            description = "fromVersion in formats: {major-version}.x.x or {major-version}.{minor-version}.x")
    private String fromVersion;

    @CommandLine.Option(names = {"--mavenLocalRepoPath"}, description = "custom path to maven local repository")
    private String mavenLocalRepoPath = "${user.home}/.m2/repository";

    @CommandLine.Option(names = {"--resultOutputFile"}, required = true, description = "File path to save result to")
    private String resultOutputFile;

    @CommandLine.Option(names = {"--gavsOutputFile"}, required = true, description = "File path to save GAVs to")
    private String gavsOutputFile;

    public static void main(String... args) {
        System.exit(new CommandLine(new MavenVersionDependenciesCli()).execute(args));
    }

    @Override
    public void run() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()
                .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                .disable(YAMLGenerator.Feature.SPLIT_LINES)
                .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE))
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        try {
            MavenEffectiveDependenciesService service = new MavenEffectiveDependenciesService();
            GitConfig gitConfig = GitConfig.builder()
                    .url(gitURL)
                    .username(gitUsername)
                    .email(gitEmail)
                    .password(gitPassword)
                    .build();

            MavenConfig mavenConfig = MavenConfig.builder()
                    .localRepositoryPath(mavenLocalRepoPath)
                    .build();

            Config config = Config.builder(Path.of(baseDir, "repositories").toString(), gitConfig, mavenConfig, repositories).build();
            Set<GAV> gavs1 = service.resolvePomsEffectiveDependencies(Path.of(baseDir, type1PomRelativeDir), mavenConfig);
            Set<GAV> gavs2 = service.resolvePomsEffectiveDependencies(Path.of(baseDir, type2PomRelativeDir), mavenConfig);
            Map<Integer, List<RepositoryInfo>> graph = service.resolveRepositories(config);

            EffectiveDependenciesDiff diff = service.compare(type1, gavs1, type2, gavs2, graph, mavenConfig);

            if (resultOutputFile != null && !resultOutputFile.isBlank()) {
                // write the result
                try {
                    Path resultPath = resultOutputFile.startsWith("/") ? Path.of(resultOutputFile) : Paths.get(baseDir, resultOutputFile);
                    String result = yaml.writeValueAsString(diff);
                    log.info("Writing to {} result:\n{}", resultPath, result);
                    Files.writeString(resultPath, result, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", resultOutputFile, e);
                }
            }
            if (gavsOutputFile != null && !gavsOutputFile.isBlank()) {
                // write the result
                try {
                    Path resultPath = gavsOutputFile.startsWith("/") ? Path.of(gavsOutputFile) : Paths.get(baseDir, gavsOutputFile);
                    String gavs = diff.getGavs().stream().map(GAV::toString).collect(Collectors.joining("\n"));
                    log.info("Writing to {} gavs:\n{}", resultPath, gavs);
                    Files.writeString(resultPath, gavs, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", gavsOutputFile, e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve effective dependencies", e);
        }
    }
}
