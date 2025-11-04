package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GA;
import org.qubership.cloud.actions.renovate.model.ArtifactVersionData;
import org.qubership.cloud.actions.renovate.model.XrayArtifactSummary;
import org.qubership.cloud.actions.renovate.model.XrayArtifactSummaryElement;
import org.qubership.cloud.actions.renovate.model.XrayArtifactSummaryRequest;
import org.qubership.cloud.actions.renovate.model.docker.ArtifactoryDockerVersions;
import org.qubership.cloud.actions.renovate.model.docker.DockerArtifactVersion;
import org.qubership.cloud.actions.renovate.model.go.GoArtifactVersion;
import org.qubership.cloud.actions.renovate.model.maven.ArtifactoryMavenVersions;
import org.qubership.cloud.actions.renovate.model.maven.ArtifactoryVersion;
import org.qubership.cloud.actions.renovate.model.maven.MavenArtifactVersion;
import org.qubership.cloud.actions.renovate.model.regex.AlpinePkgVersion;
import org.qubership.cloud.actions.renovate.model.regex.AlpinePkgsVersions;
import org.qubership.cloud.actions.renovate.model.regex.RegexArtifactVersion;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class XrayService {

    HttpService httpService;
    String artifactoryUrl;
    String basicAuth;
    ObjectMapper objectMapper;

    public XrayService(HttpService httpService, ObjectMapper objectMapper, String artifactoryUrl, String user, String password) {
        this.httpService = httpService;
        this.objectMapper = objectMapper;
        this.artifactoryUrl = artifactoryUrl;
        this.basicAuth = String.format("Basic %s", Base64.getEncoder().encodeToString(String.format("%s:%s", user, password).getBytes()));
    }

    public XrayArtifactSummaryElement getArtifactSummary(Collection<String> repositories, String artifactPath) throws Exception {
        for (String repo : repositories) {
            String artifactFullPath = String.format("default/%s/%s", repo, artifactPath);
            String body = objectMapper.writeValueAsString(new XrayArtifactSummaryRequest(List.of(artifactFullPath)));

            HttpRequest request = HttpRequest.newBuilder(URI.create(String.format("%s%s", artifactoryUrl, "/xray/api/v1/summary/artifact")))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpService.sendRequest(request, 5, 200);
            XrayArtifactSummary xrayArtifactSummary = objectMapper.readValue(response.body(), XrayArtifactSummary.class);
            if (xrayArtifactSummary == null || xrayArtifactSummary.getErrors() != null && !xrayArtifactSummary.getErrors().isEmpty()) {
                continue;
            }
            return xrayArtifactSummary.getArtifacts().stream()
                    .filter(element -> Objects.equals(element.getGeneral().getPath(), artifactFullPath))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    public List<String> getArtifactVersions(Collection<String> repos, ArtifactVersionData<?> artifactVersion) throws Exception {
        return switch (artifactVersion.getType()) {
            case maven -> getMavenVersions(repos, (MavenArtifactVersion) artifactVersion);
            case go -> getGoVersions(repos, (GoArtifactVersion) artifactVersion);
            case docker -> getDockerVersions(repos, (DockerArtifactVersion) artifactVersion);
            case regex -> getAlpinePkgVersions(repos, (RegexArtifactVersion) artifactVersion);
        };
    }

    public List<String> getMavenVersions(Collection<String> repositories, MavenArtifactVersion mavenArtifactVersion) throws Exception {
        GA ga = mavenArtifactVersion.getGav().toGA();
        for (String repo : repositories) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("g", ga.getGroupId());
            query.put("a", ga.getArtifactId());
            query.put("repos", repo);
            String queryStr = query.entrySet().stream().map(e -> String.format("%s=%s", e.getKey(), e.getValue())).collect(Collectors.joining("&"));

            HttpRequest request = HttpRequest.newBuilder(URI.create(String.format("%s%s?%s", artifactoryUrl, "/artifactory/api/search/versions", queryStr)))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpService.sendRequest(request, 5, 404, 200);
            if (response.statusCode() == 404) {
                continue;
            }
            return objectMapper.readValue(response.body(), ArtifactoryMavenVersions.class).getResults().stream()
                    .filter(v -> !v.isIntegration())
                    .map(ArtifactoryVersion::getVersion)
                    .toList();
        }
        throw new IllegalStateException(String.format("No versions found for GA: '%s' in repositories:\n%s",
                ga, String.join("\n", repositories)));
    }

    public List<String> getGoVersions(Collection<String> repositories, GoArtifactVersion goArtifactVersion) throws Exception {
        String packageName = goArtifactVersion.getPackageName();
        for (String repo : repositories) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(String.format("%s/api/go/%s/%s/@v/list", artifactoryUrl, repo, packageName)))
                    .header("Authorization", basicAuth)
                    .GET()
                    .build();
            HttpResponse<String> response = httpService.sendRequest(request, 5, 404, 200);
            if (response.statusCode() == 404) {
                continue;
            }
            return Arrays.stream(Optional.ofNullable(response.body()).orElse("").split("\n"))
                    .filter(v -> !v.isBlank())
                    .toList();
        }
        throw new IllegalStateException(String.format("No versions found for packageName: '%s' in repositories:\n%s",
                packageName, String.join("\n", repositories)));
    }

    public List<String> getDockerVersions(Collection<String> repositories, DockerArtifactVersion dockerArtifactVersion) throws Exception {
        String packageName = dockerArtifactVersion.getPackageName();
        for (String repo : repositories) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(String.format("%s/api/docker/%s/v2/%s/tags/list", artifactoryUrl, repo, packageName)))
                    .header("Authorization", basicAuth)
                    .GET()
                    .build();
            HttpResponse<String> response = httpService.sendRequest(request, 5, 404, 200);
            if (response.statusCode() == 404) {
                continue;
            }
            return objectMapper.readValue(response.body(), ArtifactoryDockerVersions.class).getTags();
        }
        throw new IllegalStateException(String.format("No versions found for packageName: '%s' in repositories:\n%s",
                packageName, String.join("\n", repositories)));
    }

    public List<String> getAlpinePkgVersions(Collection<String> repositories, RegexArtifactVersion regexArtifactVersion) throws Exception {
        String packageName = regexArtifactVersion.getPackageName();
        String packageNameSimplified = packageName.replace("[+]{2}", "++");
        //            "name": "clang18-libs-18.1.8-r6.apk"
        Pattern packageNamePattern = Pattern.compile("^%s-(?<version>\\d+.*)\\.apk".formatted(packageName));
        Pattern repoPattern = Pattern.compile("^(?<artifactoryRepo>.+?)/(?<alpineRepo>.+)");
        //        "dl_cdn_alpinelinux_org_alpine.apk.proxy-cache/v3.22/community/x86_64/",
        //        "dl_cdn_alpinelinux_org_alpine.apk.proxy-cache/edge/community/x86_64/"
        List<String> versions = repositories.stream().flatMap(repo -> {
                    Matcher matcher = repoPattern.matcher(repo);
                    if (!matcher.matches()) {
                        throw new IllegalStateException(String.format("Invalid repository: '%s'. Must match pattern: %s", repo, repoPattern.pattern()));
                    }
                    String artifactoryRepo = matcher.group("artifactoryRepo");
                    String alpineRepo = matcher.group("alpineRepo");

                    String body = """
                            items.find({
                              "$and": [
                                {"name": {"$match": "%s*.apk"}},
                                {"path": {"$match": "%s"}},
                                {"repo": {"$match": "%s"}}
                              ]
                            }).include("name","repo","path")
                            """.formatted(packageNameSimplified, alpineRepo, artifactoryRepo);
                    try {
                        HttpRequest request = HttpRequest.newBuilder(URI.create("%s/artifactory/api/search/aql".formatted(artifactoryUrl)))
                                .header("Authorization", basicAuth)
                                .header("Content-Type", "text/plain")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        HttpResponse<String> response = httpService.sendRequest(request, 5, 404, 200);
                        if (response.statusCode() == 404) {
                            return Stream.empty();
                        }
                        return objectMapper.readValue(response.body(), AlpinePkgsVersions.class).getResults().stream()
                                .map(AlpinePkgVersion::getName)
                                .map(packageNamePattern::matcher)
                                .filter(Matcher::matches)
                                .map(m -> m.group("version"))
                                .toList().stream();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .distinct()
                .toList();

        if (versions.isEmpty()) {
            throw new IllegalStateException(String.format("No versions found for packageName: '%s' in repositories:\n%s",
                    packageName, String.join("\n", repositories)));
        } else {
            return versions;
        }
    }

}
