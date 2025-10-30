package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.RepositoryConfig;
import org.qubership.cloud.actions.renovate.converters.*;
import picocli.CommandLine;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@CommandLine.Command(description = "renovate xray config cli")
@Slf4j
public class RenovateXrayConfigCli implements Runnable {

    @CommandLine.Option(names = {"--artifactoryUrl"}, required = true, description = "artifactory url")
    private String artifactoryUrl;

    @CommandLine.Option(names = {"--artifactoryUsername"}, required = true, description = "artifactory username")
    private String artifactoryUsername;

    @CommandLine.Option(names = {"--artifactoryPassword"}, required = true, description = "artifactory password")
    private String artifactoryPassword;

    @CommandLine.Option(names = {"--fromJson"},
            description = "Merge config with provided json(s)",
            converter = JsonConverter.class)
    private List<Map<String, Object>> jsonList = new ArrayList<>();

    @CommandLine.Option(names = {"--fromFile"},
            description = "Merge config with provided json/yaml(s)",
            converter = YamlFromFileConverter.class)
    private List<Map<String, Object>> jsonListFromFiles = new ArrayList<>();

    @CommandLine.Option(names = {"--repository"}, description = """
            repository in format: '{url}[branch={branch}]' to be used for building 'repositories' and their
            'baseBranchPatterns' fields of the renovate config. Use multiple params to specify more than 1 repository""",
            converter = RepositoryConfigConverter.class)
    private List<RepositoryConfig> repositories = new ArrayList<>();

    @CommandLine.Option(names = {"--artifactoryMavenRepository"}, description = "artifactory maven repository name")
    private List<String> artifactoryMavenRepositories = new ArrayList<>();

    @CommandLine.Option(names = {"--artifactoryGoRepository"}, description = "artifactory go repository name")
    private List<String> artifactoryGoRepositories = new ArrayList<>();

    @CommandLine.Option(names = {"--artifactoryDockerRepository"}, description = "artifactory docker repository name")
    private List<String> artifactoryDockerRepositories = new ArrayList<>();

    @CommandLine.Option(names = {"--allowedVersionsPattern"}, defaultValue = "^(v\\d+|\\d+).*$", required = true,
            description = "pattern to filter packages versions")
    private Pattern allowedVersionsPattern;

    @CommandLine.Option(names = {"--repositoriesFile"}, split = ",", description = """
            File with new-line seperated repositories in format: '{url}[branch={branch}]' to be used for building 'repositories' and their
            'baseBranchPatterns' fields of the renovate config. Use multiple params to specify more than 1 repository""",
            converter = RepositoriesFileConfigConverter.class)
    private List<RepositoryConfig> repositoriesFromFile = new ArrayList<>();

    @CommandLine.Option(names = {"--renovateReportFilePath"}, required = true, description = "path to the renovate report dump file")
    private String renovateReportFilePath;

    @CommandLine.Option(names = {"--groupNameMapping"},
            description = "custom groupName to regex. Allows to combine multiple groups into one. I.e. org.spring=org.spring.+",
            converter = JsonConverter.class)
    private List<Map<String, Object>> groupNameMappings = new LinkedList<>();

    @CommandLine.Option(names = {"--label"}, defaultValue = "security", description = """
            label to add to MRs created to update versions with vulnerabilities""")
    private List<String> labels = new ArrayList<>();

    @CommandLine.Option(names = {"--renovateConfigOutputFile"}, required = true, description = "File path to save result to")
    private String renovateConfigOutputFile;

    public static void main(String... args) {
        System.exit(run(args));
    }

    protected static int run(String... args) {
        return new CommandLine(new RenovateXrayConfigCli()).execute(args);
    }

    @Override
    public void run() {
        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            ObjectMapper objectMapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(SerializationFeature.INDENT_OUTPUT, true);
            HttpService httpService = new HttpService(httpClient, objectMapper);
            RenovateRulesService renovateRulesService = new RenovateRulesService();

            Map<String, Object> config = new TreeMap<>();
            Stream.concat(jsonListFromFiles.stream(), jsonList.stream())
                    .forEach(json -> config.putAll(renovateRulesService.mergeMaps(json, config)));

            config.put("repositories", Stream.concat(repositories.stream(), repositoriesFromFile.stream())
                    .map(r -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("repository", r.getDir());
                        result.put("baseBranchPatterns", List.of(r.getBranch()));
                        return result;
                    }).toList());

            XrayService xrayService = new XrayService(httpService, objectMapper, artifactoryUrl, artifactoryUsername, artifactoryPassword);
            RenovateService service = new RenovateService(xrayService, objectMapper);
            Map<String, Collection<String>> dependencyRepositories = new TreeMap<>();
            dependencyRepositories.put("maven", artifactoryMavenRepositories);
            dependencyRepositories.put("go", artifactoryGoRepositories);
            dependencyRepositories.put("docker", artifactoryDockerRepositories);
            List<? extends Map<String, Object>> securityPackageRules = service.getRules(Path.of(renovateReportFilePath),
                    dependencyRepositories, allowedVersionsPattern, labels);

            List<Map> packageRules = (List<Map>) config.computeIfAbsent("packageRules", k -> new ArrayList<>());
            packageRules.addAll(securityPackageRules);

            String result = RenovateConfigToJsConverter.convert(config);
            if (renovateConfigOutputFile != null && !renovateConfigOutputFile.isBlank()) {
                // write the result
                try {
                    Path resultPath = Path.of(renovateConfigOutputFile);
                    log.info("Writing to {} result:\n{}", resultPath, result);
                    Files.writeString(resultPath, result, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", renovateConfigOutputFile, e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build renovate config", e);
        }
    }

}
