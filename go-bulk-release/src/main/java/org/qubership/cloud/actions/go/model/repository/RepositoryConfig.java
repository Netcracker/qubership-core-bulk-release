package org.qubership.cloud.actions.go.model.repository;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Data
public class RepositoryConfig {
    public static final String HEAD = "HEAD";
    public static final Pattern URL_PATTERN = Pattern.compile("^(?<url>https://[^/]+/(?<dir>[^\\[]+))(\\[(?<params>.*)])?$");

    final String url;
    final String dir;
    final String branch;
    final String version;
    final boolean skipTests;

    @Builder(builderMethodName = "")
    protected RepositoryConfig(String url, String branch, boolean skipTests, String version) {
        this.url = normalizeGitUrl(url);
        Matcher matcher = URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid repository url: %s. Must match pattern: '%s'", url, URL_PATTERN));
        }
        this.dir = matcher.group("dir");
        this.skipTests = skipTests;
        this.version = version;
        this.branch = Optional.ofNullable(branch).orElse(HEAD);
    }

    public static RepositoryConfigBuilder builder(String url) {
        return new RepositoryConfigBuilder().url(url);
    }

    public static RepositoryConfigBuilder builder(RepositoryConfig repositoryConfig) {
        return new RepositoryConfigBuilder()
                .url(repositoryConfig.getUrl())
                .branch(repositoryConfig.getBranch())
                .skipTests(repositoryConfig.isSkipTests())
                .version(repositoryConfig.getVersion());
    }

    @Override
    public String toString() {
        return String.format("%s [%s]", url, branch);
    }

    String normalizeGitUrl(String url) {
        return url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
    }

    public static RepositoryConfig fromConfig(String repositoryConfig) {
        Matcher matcher = URL_PATTERN.matcher(repositoryConfig);
        if (!matcher.matches())
            throw new IllegalArgumentException(String.format("Invalid repository config [%s], must match pattern: %s",
                    repositoryConfig, URL_PATTERN));
        String url = matcher.group("url");
        Map<String, String> params = Arrays.stream(Optional.ofNullable(matcher.group("params")).orElse("").split(","))
                .map(entry -> entry.split("="))
                .filter(entry -> entry.length == 2)
                .collect(Collectors.toMap(item -> item[0], item -> item[1]));
        RepositoryConfigBuilder builder = RepositoryConfig.builder(url);
        Optional.ofNullable(params.getOrDefault("branch", params.get("from"))).ifPresent(builder::branch);
        Optional.ofNullable(params.get("version")).ifPresent(builder::version);
        Optional.ofNullable(params.get("skipTests")).map(Boolean::parseBoolean).ifPresent(builder::skipTests);
        return builder.build();
    }
}
