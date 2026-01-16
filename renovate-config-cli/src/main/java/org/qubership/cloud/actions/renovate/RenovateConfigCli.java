package org.qubership.cloud.actions.renovate;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.maven.model.RepositoryConfig;
import org.qubership.cloud.actions.maven.model.VersionIncrementType;
import org.qubership.cloud.actions.renovate.converters.*;
import org.qubership.cloud.actions.renovate.model.RenovateConfigOutputFormat;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(description = "renovate config cli")
@Slf4j
public class RenovateConfigCli implements Runnable {

    @CommandLine.Option(names = {"--fromJson"},
            description = "Merge config with provided json(s)",
            converter = JsonConverter.class)
    private List<Map<String, Object>> jsonList = new ArrayList<>();

    @CommandLine.Option(names = {"--fromFile"},
            description = "Merge config with provided json/yaml(s)",
            converter = YamlFromFileConverter.class)
    private List<Map<String, Object>> jsonListFromFiles = new ArrayList<>();

    @CommandLine.Option(names = {"--gavs", "--strictGavs"}, split = ",",
            description = "comma seperated list of GAVs to be used for building renovate config", converter = GAVConverter.class)
    private Set<GAV> strictGavs = new HashSet<>();

    @CommandLine.Option(names = {"--gavsFile", "--strictGavsFile"}, description = "file of GAVs seperated by new-line to be used for building renovate config")
    private String strictGavsFile;

    @CommandLine.Option(names = {"--patchGavs"}, split = ",",
            description = "comma seperated list of GAVs to be used for building renovate config with packageRule with allowedVersions='/^{major}\\.{minor}\\.\\d+$/'",
            converter = GAVConverter.class)
    private Set<GAV> patchGavs = new HashSet<>();

    @CommandLine.Option(names = {"--patchGavsFile"},
            description = "file of GAVs seperated by new-line to be used for building renovate config with packageRule with allowedVersions='/^{major}\\.{minor}\\.\\d+$/'")
    private String patchGavsFile;

    @CommandLine.Option(names = {"--repository"}, description = """
            repository in format: '{url}[branch={branch}]' to be used for building 'repositories' and their
            'baseBranchPatterns' fields of the renovate config. Use multiple params to specify more than 1 repository""",
            converter = RepositoryConfigConverter.class)
    private List<RepositoryConfig> repositories = new ArrayList<>();

    @CommandLine.Option(names = {"--repositoriesFile"}, split = ",", description = """
            File with new-line seperated repositories in format: '{url}[branch={branch}]' to be used for building 'repositories' and their
            'baseBranchPatterns' fields of the renovate config. Use multiple params to specify more than 1 repository""",
            converter = RepositoriesFileConfigConverter.class)
    private List<RepositoryConfig> repositoriesFromFile = new ArrayList<>();

    @CommandLine.Option(names = {"--groupNameMapping"},
            description = "custom groupName to regex. Allows to combine multiple groups into one. I.e. org.spring=org.spring.+",
            converter = JsonConverter.class)
    private List<Map<String, Object>> groupNameMappings = new LinkedList<>();

    @CommandLine.Option(names = {"--renovateConfigOutputFile"}, required = true, description = "File path to save result to")
    private String renovateConfigOutputFile;

    @CommandLine.Option(names = {"--renovateConfigOutputFormat"}, description = "supported formats: JS/JSON", defaultValue = "JS")
    private RenovateConfigOutputFormat renovateConfigOutputFormat;

    @CommandLine.Option(names = {"--addGroupName"}, description = "add groupName to each package rule", defaultValue = "true")
    private boolean addGroupName;

    public static void main(String... args) {
        System.exit(run(args));
    }

    protected static int run(String... args) {
        return new CommandLine(new RenovateConfigCli()).execute(args);
    }

    @Override
    public void run() {
        RenovateRulesService renovateRulesService = new RenovateRulesService();
        try {
            Map<String, Object> config = new TreeMap<>();
            Stream.concat(jsonListFromFiles.stream(), jsonList.stream())
                    .forEach(json -> config.putAll(renovateRulesService.mergeMaps(json, config)));

            config.put("repositories", Stream.concat(repositories.stream(), repositoriesFromFile.stream())
                    .map(r -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("repository", r.getDir());
                        result.put("baseBranchPatterns", List.of(r.getBranch()));
                        Optional.ofNullable(r.getParams().get("branchPrefix"))
                                .ifPresent(bp -> result.put("branchPrefix", bp));
                        Optional.ofNullable(r.getParams().get("branchPrefixSuffix"))
                                .ifPresent(bp -> {
                                    String branchPrefix = r.getParams().getOrDefault("branchPrefix",
                                            (String) config.getOrDefault("branchPrefix", "renovate/"));
                                    result.put("branchPrefix", branchPrefix + bp);
                                });
                        Optional.ofNullable(r.getParams().get("branchPrefixOld"))
                                .ifPresent(bp -> result.put("branchPrefixOld", bp));
                        Optional.ofNullable(r.getParams().get("addLabels"))
                                .ifPresent(v -> result.put("addLabels", Arrays.stream(v.split(";"))
                                        .filter(s -> !s.isBlank()).toList()));
                        return result;
                    }).toList());

            // group by the same groupId and version
            if (strictGavsFile != null) {
                Files.readAllLines(Path.of(strictGavsFile)).stream().filter(l -> !l.isBlank()).map(GAV::new).forEach(strictGavs::add);
            }
            if (patchGavsFile != null) {
                Files.readAllLines(Path.of(patchGavsFile)).stream().filter(l -> !l.isBlank()).map(GAV::new).forEach(patchGavs::add);
            }
            Map<String, Pattern> groupNamePatternsMap = groupNameMappings.stream().flatMap(m -> m.entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, p -> Pattern.compile(p.getValue().toString())));

            List<Map> packageRules = (List<Map>) config.computeIfAbsent("packageRules", k -> new ArrayList<>());
            packageRules.addAll(renovateRulesService.gavsToRules(strictGavs, null, groupNamePatternsMap, addGroupName));
            packageRules.addAll(renovateRulesService.gavsToRules(patchGavs, VersionIncrementType.PATCH, groupNamePatternsMap, addGroupName));

            String result = switch (renovateConfigOutputFormat) {
                case JS -> RenovateConfigConverter.toJs(config);
                case JSON -> RenovateConfigConverter.toJson(config);
            };
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
