package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GA;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.renovate.model.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class XrayService {

    HttpClient httpClient;
    String artifactoryUrl;
    String basicAuth;
    ObjectMapper objectMapper;

    public XrayService(HttpClient httpClient, ObjectMapper objectMapper, String artifactoryUrl, String user, String password) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.artifactoryUrl = artifactoryUrl;
        this.basicAuth = String.format("Basic %s", Base64.getEncoder().encodeToString(String.format("%s:%s", user, password).getBytes()));
    }

    public XrayArtifactSummaryElement getArtifactSummary(List<String> repositories, GAV gav) throws Exception {
        for (String repo : repositories) {
            String groupPath = gav.getGroupId().replace(".", "/");
            String artifactPath = gav.getArtifactId().replace(".", "/");
            String artifactJar = String.format("%s-%s.jar", gav.getArtifactId(), gav.getVersion());
            String artifactFullPath = String.format("default/%s/%s/%s/%s/%s", repo, groupPath, artifactPath, gav.getVersion(), artifactJar);
            String body = objectMapper.writeValueAsString(new XrayArtifactSummaryRequest(List.of(artifactFullPath)));

            HttpRequest request = HttpRequest.newBuilder(URI.create(String.format("%s%s", artifactoryUrl, "/xray/api/v1/summary/artifact")))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = sendRequest(request, 5,  200);
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

    public List<ArtifactoryVersion> getArtifactVersions(List<String> repositories, GA ga) throws Exception {
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
            HttpResponse<String> response = sendRequest(request, 5, 404, 200);
            if (response.statusCode() == 404) {
                continue;
            }
            return objectMapper.readValue(response.body(), ArtifactoryVersions.class).getResults();
        }
        throw new IllegalStateException("No versions found for GA: " + ga);
    }

    private HttpResponse<String> sendRequest(HttpRequest request, int retries, int... codes) throws IOException, InterruptedException {
        HttpResponse<String> response;
        Set<Integer> allowedResponses = Arrays.stream(codes).boxed().collect(Collectors.toSet());
        do {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (allowedResponses.contains(response.statusCode())) {
                return response;
            }
            // do not retry 4xx responses
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                break;
            }
            retries--;
            if (retries >= 0) Thread.sleep(3000);
        } while (retries >= 0);
        throw new IllegalStateException(String.format("Invalid response, status: %d, body: '%s'", response.statusCode(), response.body()));
    }

}
