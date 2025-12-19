package org.qubership.cloud.actions.maven.model;

import lombok.Builder;
import lombok.Data;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
public class RepositoryConfig {
    public static Pattern pattern = Pattern.compile("^(?<url>https://[^/]+/(?<dir>[^\\[]+))(\\[(?<params>.*)])?$");

    final String url;
    final String dir;
    final String branch;
    final String version;
    final boolean skipTests;
    final VersionIncrementType versionIncrementType;
    final Map<String, String> params;

    @Builder(builderMethodName = "")
    protected RepositoryConfig(String url, String branch, boolean skipTests, String version,
                               VersionIncrementType versionIncrementType, Map<String, String> params) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must be specified");
        }
        if ((branch == null || branch.isBlank()) && (version == null || version.isBlank())) {
            throw new IllegalArgumentException("Branch or version must be specified for repository: " + url);
        }
        this.url = normalizeGitUrl.apply(url);
        Matcher matcher = pattern.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid repository url: %s. Must match pattern: '%s'", url, pattern));
        }
        this.dir = matcher.group("dir");
        this.skipTests = skipTests;
        this.version = version;
        this.branch = branch;
        this.versionIncrementType = versionIncrementType;
        this.params = params;
    }

    public static RepositoryConfigBuilder builder(String url) {
        return new RepositoryConfigBuilder().url(url);
    }

    public static RepositoryConfigBuilder builder(RepositoryConfig repositoryConfig) {
        return new RepositoryConfigBuilder()
                .url(repositoryConfig.getUrl())
                .branch(repositoryConfig.getBranch())
                .skipTests(repositoryConfig.isSkipTests())
                .version(repositoryConfig.getVersion())
                .versionIncrementType(repositoryConfig.getVersionIncrementType())
                .params(repositoryConfig.getParams());
    }

    @Override
    public String toString() {
        return String.format("%s [%s]", url, branch);
    }

    public static Function<String, String> normalizeGitUrl = url -> url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;

    public static RepositoryConfig fromConfig(String repositoryConfig) {
        Matcher matcher = pattern.matcher(repositoryConfig);
        if (!matcher.matches())
            throw new IllegalArgumentException(String.format("Invalid repository config [%s], must match pattern: %s",
                    repositoryConfig, pattern));
        String url = matcher.group("url");
        Map<String, String> params = Arrays.stream(Optional.ofNullable(matcher.group("params")).orElse("").split(","))
                .map(entry -> entry.split("="))
                .filter(entry -> entry.length == 2)
                .collect(Collectors.toMap(item -> item[0], item -> item[1]));
        RepositoryConfigBuilder builder = RepositoryConfig.builder(url);
        builder.params(params);
        Optional.ofNullable(params.getOrDefault("branch", params.get("from"))).ifPresent(builder::branch);
        Optional.ofNullable(params.get("version")).ifPresent(builder::version);
        Optional.ofNullable(params.get("skipTests")).map(Boolean::parseBoolean).ifPresent(builder::skipTests);
        Optional.ofNullable(params.get("versionIncrementType")).map(String::toUpperCase).map(VersionIncrementType::valueOf).ifPresent(builder::versionIncrementType);
        return builder.build();
    }
}
