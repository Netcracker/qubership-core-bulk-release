package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
public class RepositoryConfig {
    public static final String HEAD = "HEAD";
    public static Pattern pattern = Pattern.compile("^(?<url>https://[^\\[]+)(\\[(?<params>.*)])?$");
    public static Pattern repositoryUrlPattern = Pattern.compile("https://[^/]+/(.+)");

    String url;
    String dir;
    String from = HEAD;
    String to = HEAD;
    boolean skipTests = false;
    VersionIncrementType versionIncrementType;

    public RepositoryConfig(String url) {
        this(url, Map.of());
    }

    public RepositoryConfig(String url, Map<String, String> params) {
        this.url = normalizeGitUrl.apply(url);
        Matcher matcher = repositoryUrlPattern.matcher(url);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid repository url: " + url);
        this.dir = matcher.group(1);
        params = new LinkedHashMap<>(params);
        Optional.ofNullable(params.remove("from")).ifPresent(from -> this.from = from);
        Optional.ofNullable(params.remove("to")).ifPresent(to -> this.to = to);
        Optional.ofNullable(params.remove("skipTests")).ifPresent(skipTests -> this.skipTests = Boolean.parseBoolean(skipTests));
        Optional.ofNullable(params.remove("versionIncrementType")).ifPresent(versionIncrementType ->
                this.versionIncrementType = VersionIncrementType.valueOf(versionIncrementType.toUpperCase()));
        if (!params.isEmpty()) {
            throw new IllegalArgumentException(String.format("Unknown repository [%s] params: %s", url, params));
        }
    }

    @Override
    public String toString() {
        return String.format("%s [%s]", url, from);
    }

    public Map<String, String> params() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("from", from);
        params.put("to", to);
        params.put("skipTests", String.valueOf(skipTests));
        if (versionIncrementType != null) {
            params.put("versionIncrementType", versionIncrementType.toString());
        }
        return params;
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
        return new RepositoryConfig(url, params);
    }
}
