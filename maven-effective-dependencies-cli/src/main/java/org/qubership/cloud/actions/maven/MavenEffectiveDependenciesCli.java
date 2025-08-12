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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(description = "maven effective dependencies cli")
@Slf4j
public class MavenEffectiveDependenciesCli implements Runnable {

    @CommandLine.Option(names = {"--baseDir"}, required = true, description = "directory to pom.xml")
    private String baseDir;

    @CommandLine.Option(names = {"--type1PomRelativeDir"}, required = true, description = "directory to pom.xml")
    private String type1PomRelativeDir;

    @CommandLine.Option(names = {"--type2PomRelativeDir"}, required = true, description = "directory to pom.xml")
    private String type2PomRelativeDir;

    @CommandLine.Option(names = {"--gitURL"}, required = true, description = "git host")
    private String gitURL;

    @CommandLine.Option(names = {"--gitUsername"}, required = true, description = "git username")
    private String gitUsername;

    @CommandLine.Option(names = {"--gitEmail"}, required = true, description = "git email")
    private String gitEmail;

    @CommandLine.Option(names = {"--gitPassword"}, required = true, description = "git password")
    private String gitPassword;

    @CommandLine.Option(names = {"--repositories"}, split = "\\s*,\\s*",
            description = "comma seperated list of git urls to all repositories which depend on each other and can be bulk released",
            converter = RepositoryConfigConverter.class)
    private Set<RepositoryConfig> repositories = new LinkedHashSet<>();

    @CommandLine.Option(names = {"--checkoutParallelism"}, required = true, description = "checkout parallelism")
    private int checkoutParallelism = 1;

    @CommandLine.Option(names = {"--mavenLocalRepoPath"}, description = "custom path to maven local repository")
    private String mavenLocalRepoPath = "${user.home}/.m2/repository";

    @CommandLine.Option(names = {"--resultOutputFile"}, description = "File path to save result to")
    private String resultOutputFile;

    @CommandLine.Option(names = {"--gavsOutputFile", "--effectiveGavsOutputFile"}, description = "File path to save GAVs to")
    private String gavsOutputFile;

    @CommandLine.Option(names = {"--implicitGavsOutputFile"}, description = "File path to save implicit (declared in poms) GAVs to")
    private String implicitGavsOutputFile;

    public static void main(String... args) {
        System.exit(new CommandLine(new MavenEffectiveDependenciesCli()).execute(args));
    }

    @Override
    public void run() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()
                .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                .disable(YAMLGenerator.Feature.SPLIT_LINES)
                .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE))
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        try {
            GitConfig gitConfig = GitConfig.builder()
                    .url(gitURL)
                    .username(gitUsername)
                    .email(gitEmail)
                    .password(gitPassword)
                    .checkoutParallelism(checkoutParallelism)
                    .build();

            MavenConfig mavenConfig = MavenConfig.builder()
                    .localRepositoryPath(mavenLocalRepoPath)
                    .build();

            MavenEffectiveDependenciesService service = new MavenEffectiveDependenciesService(new GitService(gitConfig));

            Path type1PomPath = Path.of(baseDir, type1PomRelativeDir);
            Path type2PomPath = Path.of(baseDir, type2PomRelativeDir);

            String type1 = type1PomPath.getName(type1PomPath.getNameCount() - 1).toString();
            String type2 = type2PomPath.getName(type2PomPath.getNameCount() - 1).toString();

            Set<GAV> gavs1 = service.resolvePomsEffectiveDependencies(type1PomPath, mavenConfig);
            Set<GAV> gavs2 = service.resolvePomsEffectiveDependencies(type2PomPath, mavenConfig);

            Config config = Config.builder(Path.of(baseDir, "repositories").toString(), gitConfig, mavenConfig, repositories).build();
            Map<Integer, List<RepositoryInfo>> graph = service.resolveRepositories(config);

            EffectiveDependenciesDiff diff = service.compare(type1, gavs1, type2, gavs2, graph, mavenConfig);

            if (resultOutputFile != null && !resultOutputFile.isBlank()) {
                // write the result
                Path resultPath = resultOutputFile.startsWith("/") ? Path.of(resultOutputFile) : Paths.get(baseDir, resultOutputFile);
                try {
                    String result = yaml.writeValueAsString(diff);
                    log.info("Writing to {} result:\n{}", resultPath, result);
                    Files.writeString(resultPath, result, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", resultPath, e);
                }
            }
            if (gavsOutputFile != null && !gavsOutputFile.isBlank()) {
                // write the result
                Path resultPath = gavsOutputFile.startsWith("/") ? Path.of(gavsOutputFile) : Paths.get(baseDir, gavsOutputFile);
                try {
                    String gavs = diff.getGavs().stream().map(GAV::toString).collect(Collectors.joining("\n"));
                    log.info("Writing to {} gavs:\n{}", resultPath, gavs);
                    Files.writeString(resultPath, gavs, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", resultPath, e);
                }
            }
            if (implicitGavsOutputFile != null && !implicitGavsOutputFile.isBlank()) {
                Set<GAV> implicitGavs1 = service.resolvePomsImplicitDependencies(type1PomPath);
                Set<GAV> implicitGavs2 = service.resolvePomsImplicitDependencies(type2PomPath);
                // write the result
                Path resultPath = implicitGavsOutputFile.startsWith("/") ? Path.of(implicitGavsOutputFile) : Paths.get(baseDir, implicitGavsOutputFile);
                try {
                    String gavs = Stream.concat(implicitGavs1.stream(), implicitGavs2.stream())
                            .map(GAV::toString).collect(Collectors.joining("\n"));
                    log.info("Writing to {} gavs:\n{}", resultPath, gavs);
                    Files.writeString(resultPath, gavs, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", resultPath, e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve effective dependencies", e);
        }
    }
}
