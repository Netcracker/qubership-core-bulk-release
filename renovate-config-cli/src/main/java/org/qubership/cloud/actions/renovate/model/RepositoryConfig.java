package org.qubership.cloud.actions.renovate.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
@RequiredArgsConstructor
public class RepositoryConfig {
    public static Pattern pattern = Pattern.compile("^(https://[^/]+)/(?<name>[^\\[=]+)(\\[(?<params>.*)])?$");

    final String name;
    String branch;

    public static RepositoryConfig fromConfig(String repositoryConfig) {
        Matcher matcher = pattern.matcher(repositoryConfig);
        if (!matcher.matches())
            throw new IllegalArgumentException(String.format("Invalid repository config [%s], must match pattern: %s",
                    repositoryConfig, pattern));
        String name = matcher.group("name");
        Map<String, String> params = Arrays.stream(Optional.ofNullable(matcher.group("params")).orElse("").split(","))
                .map(entry -> entry.split("="))
                .filter(entry -> entry.length == 2)
                .collect(Collectors.toMap(item -> item[0], item -> item[1]));
        String branch = params.get("from");
        if (branch != null) {
            return new RepositoryConfig(name, branch);
        }
        return new RepositoryConfig(name);
    }
}
