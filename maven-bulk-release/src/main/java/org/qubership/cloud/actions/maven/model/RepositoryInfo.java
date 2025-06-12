package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Getter
@EqualsAndHashCode(callSuper = true)
public class RepositoryInfo extends Repository {
    public static Pattern repositoryUrlPattern = Pattern.compile("https://[^/]+/(.+)");

    Set<GA> modules = new HashSet<>();
    Set<GAV> moduleDependencies = new HashSet<>();
    Set<RepositoryInfo> repoDependencies = new HashSet<>();

    public RepositoryInfo(String url, Map<String, String> params) {
        super(url, params);
        Matcher matcher = repositoryUrlPattern.matcher(url);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid repository url: " + url);
        this.dir = matcher.group(1);
    }
    public RepositoryInfo(RepositoryConfig repositoryConfig) {
        this(repositoryConfig.getUrl(), repositoryConfig.params());
    }

    public RepositoryInfo(String url, Map<String, String> params, Set<RepositoryInfo> repoDependencies) {
        this(url, params);
        this.repoDependencies = repoDependencies.stream().map(ri -> new RepositoryInfo(ri.getUrl(), ri.params(),
                ri.getRepoDependencies())).collect(Collectors.toSet());
    }

    @JsonIgnore
    public Set<RepositoryInfo> getRepoDependenciesFlatSet() {
        Set<RepositoryInfo> result = new HashSet<>(repoDependencies);
        repoDependencies.forEach(ri -> result.addAll(ri.getRepoDependenciesFlatSet()));
        return result;
    }

    @Override
    public String toString() {
        return getUrl();
    }
}
