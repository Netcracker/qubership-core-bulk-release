package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.renovate.model.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RenovateXrayTest {

    ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testCli() throws Exception {
        String artifactoryUrl = "https://artifactory.com";
        String artifactoryUsername = "test";
        String artifactoryPassword = "test";

        String renovateReportFilePath = RenovateXrayTest.class.getResource("/renovate-report.json").getPath();
        Path repositoriesFile = Files.createTempFile("repositories", ".txt");
        repositoriesFile.toFile().deleteOnExit();
        Files.writeString(repositoriesFile, """
                https://github.com/Netcracker/qubership-core-release-test-maven-lib-1[branch=release/support-1.x.x]
                https://github.com/Netcracker/qubership-core-release-test-maven-lib-2[branch=release/support-2.x.x]
                """);

        Path jsonFile = Files.createTempFile("json-config", ".yaml");
        jsonFile.toFile().deleteOnExit();
        // language=yaml
        Files.writeString(jsonFile, """
                ---
                platform: github
                username: renovate
                gitAuthor: renovate@test.com
                commitMessagePrefix: RENOVATE-0000
                dryRun: full
                prCreation: not-pending
                printConfig: true
                rebaseWhen: conflicted
                recreateWhen: always
                platformAutomerge: true
                labels:
                  - "renovate"
                  - "test"
                branchPrefix: renovate-support/
                branchPrefixOld: renovate/
                globalExtends:
                  - ":ignoreModulesAndTests"
                  - ":dependencyDashboard"
                helmv3:
                  registryAliases:
                    "'@custom.libraries.helm.staging'": https://artifactory.com/artifactory/custom.libraries.helm.staging
                packageRules:
                  - matchManagers:
                      - maven
                    matchUpdateTypes:
                      - patch
                    groupName: Default Maven Patch
                    autoMerge: true
                  - matchManagers:
                      - maven
                    matchUpdateTypes:
                      - minor
                      - major
                    groupName: Default Maven Minor
                    autoMerge: false
                  - matchPackagePatterns:
                      - .*
                    allowedVersions: "!/redhat|composite|groovyless|jboss|atlassian|preview/"
                hostRules:
                  - hostType: maven
                    matchHost: https://repo1.maven.org/maven2/
                    username: $process.env.MAVEN_USERNAME
                    password: $process.env.MAVEN_PASSWORD
                """);

        Path renovateConfigFile = Files.createTempFile("renovate-config", ".js");
        renovateConfigFile.toFile().deleteOnExit();

        String[] args = new String[]{
                "--renovateReportFilePath=" + renovateReportFilePath,
                "--artifactoryUrl=" + artifactoryUrl,
                "--artifactoryUsername=" + artifactoryUsername,
                "--artifactoryPassword=" + artifactoryPassword,
                "--artifactoryMavenRepository=maven-repository",
                "--renovateConfigOutputFile=" + renovateConfigFile,
                "--repositoriesFile=" + repositoriesFile,
                "--fromFile=" + jsonFile
        };

        try (MockedStatic<HttpClient> httpClientMockedStatic = Mockito.mockStatic(HttpClient.class)) {
            HttpClient httpClient = Mockito.mock(HttpClient.class);
            httpClientMockedStatic.when(() -> HttpClient.newHttpClient()).thenReturn(httpClient);

            HttpResponse defaultSummaryResponse = Mockito.mock(HttpResponse.class);
            Mockito.when(defaultSummaryResponse.statusCode()).thenReturn(200);
            Mockito.when(defaultSummaryResponse.body()).thenReturn(mapper.writeValueAsString(new XrayArtifactSummary()));

            BiFunction<GAV, List<XrayArtifactSummaryIssue>, XrayArtifactSummary> buildSummary = (GAV gav, List<XrayArtifactSummaryIssue> issues) -> {
                XrayArtifactSummary summary = new XrayArtifactSummary();
                XrayArtifactSummaryGeneral xrayArtifactSummaryGeneral = new XrayArtifactSummaryGeneral();
                xrayArtifactSummaryGeneral.setPath(MessageFormat.format("default/maven-repository/{0}/{1}/{2}/{1}-{2}.jar", gav.getGroupId(), gav.getArtifactId(), gav.getVersion()));

                XrayArtifactSummaryElement xrayArtifactSummaryElement = new XrayArtifactSummaryElement(xrayArtifactSummaryGeneral, issues);

                summary.setArtifacts(List.of(xrayArtifactSummaryElement));
                return summary;
            };

            XrayArtifactSummaryIssue xrayArtifactSummaryIssue = new XrayArtifactSummaryIssue();
            xrayArtifactSummaryIssue.setSeverity(Severity.High);
            XrayArtifactSummaryCVE xrayArtifactSummaryCVE = new XrayArtifactSummaryCVE();
            xrayArtifactSummaryCVE.setCve("CVE-2023-4444");
            xrayArtifactSummaryIssue.setCves(List.of(xrayArtifactSummaryCVE));
            List<XrayArtifactSummaryIssue> antJunitIssues = List.of(xrayArtifactSummaryIssue);

            HttpResponse antJunitVersionsResponse = Mockito.mock(HttpResponse.class);
            Mockito.when(antJunitVersionsResponse.statusCode()).thenReturn(200);
            Mockito.when(antJunitVersionsResponse.body()).thenReturn(mapper.writeValueAsString(new ArtifactoryVersions(List.of(
                    new ArtifactoryVersion("1.6.5", false),
                    new ArtifactoryVersion("1.6.6", false),
                    new ArtifactoryVersion("1.6.7", false)
            ))));

            Mockito.when(httpClient.send(Mockito.any(), Mockito.any())).then(i -> {
                try {
                    HttpRequest request = i.getArgument(0, HttpRequest.class);
                    String method = request.method();
                    String string = request.uri().toString();

                    if (method.equals("POST") && string.equals("https://artifactory.com/xray/api/v1/summary/artifact")) {
                        String content = HttpService.getBodyAsString(request.bodyPublisher().get());
                        XrayArtifactSummaryRequest summaryRequest = mapper.readValue(content, XrayArtifactSummaryRequest.class);
                        HttpResponse response = Mockito.mock(HttpResponse.class);
                        Mockito.when(response.statusCode()).thenReturn(200);

                        Pattern pathPattern = Pattern.compile("^default/maven-repository/(?<group>.+?)/(?<artifact>[^/]+)/(?<version>\\d+.+?)/[^/]+\\.jar$");
                        Matcher matcher = pathPattern.matcher(summaryRequest.getPaths().getFirst());
                        if (matcher.matches()) {
                            String group = matcher.group("group").replace("/", ".");
                            String artifact = matcher.group("artifact");
                            String version = matcher.group("version");
                            GAV gav = new GAV(group, artifact, version);
                            if (gav.equals(new GAV("ant:ant-junit:1.6.5")) || gav.equals(new GAV("ant:ant-junit:1.6.6"))) {
                                Mockito.when(response.body()).thenReturn(mapper.writeValueAsString(buildSummary.apply(gav, antJunitIssues)));
                            } else {
                                Mockito.when(response.body()).thenReturn(mapper.writeValueAsString(buildSummary.apply(gav, List.of())));
                            }
                            return response;
                        } else {
                            throw new IllegalStateException("Unexpected request: " + request);
                        }
                    } else if (method.equals("GET") && string.equals("https://artifactory.com/artifactory/api/search/versions?g=ant&a=ant-junit&repos=maven-repository")) {
                        return antJunitVersionsResponse;
                    } else {
                        throw new IllegalStateException("Unexpected request: " + request);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to check request", e);
                }
            });
            RenovateXrayConfigCli.run(args);
            String result = Files.readString(renovateConfigFile);

            Assertions.assertEquals("""
                    module.exports = {
                      branchPrefix : "renovate-support/",
                      branchPrefixOld : "renovate/",
                      commitMessagePrefix : "RENOVATE-0000",
                      dryRun : "full",
                      gitAuthor : "renovate@test.com",
                      globalExtends : [ ":ignoreModulesAndTests", ":dependencyDashboard" ],
                      helmv3 : {
                        registryAliases : {
                          "@custom.libraries.helm.staging" : "https://artifactory.com/artifactory/custom.libraries.helm.staging"
                        }
                      },
                      hostRules : [ {
                        hostType : "maven",
                        matchHost : "https://repo1.maven.org/maven2/",
                        username : process.env.MAVEN_USERNAME,
                        password : process.env.MAVEN_PASSWORD
                      } ],
                      labels : [ "renovate", "test" ],
                      packageRules : [ {
                        matchManagers : [ "maven" ],
                        matchUpdateTypes : [ "patch" ],
                        groupName : "Default Maven Patch",
                        autoMerge : true
                      }, {
                        matchManagers : [ "maven" ],
                        matchUpdateTypes : [ "minor", "major" ],
                        groupName : "Default Maven Minor",
                        autoMerge : false
                      }, {
                        matchPackagePatterns : [ ".*" ],
                        allowedVersions : "!/redhat|composite|groovyless|jboss|atlassian|preview/"
                      }, {
                        matchPackageNames : [ "ant:ant-junit" ],
                        groupName : "ant",
                        allowedVersions : "/^1.6.7$/",
                        enabled : true,
                        addLabels : [ "security" ],
                        prBodyNotes : [ "Fixed CVEs: CVE-2023-4444" ]
                      } ],
                      platform : "github",
                      platformAutomerge : true,
                      prCreation : "not-pending",
                      printConfig : true,
                      rebaseWhen : "conflicted",
                      recreateWhen : "always",
                      repositories : [ {
                        repository : "Netcracker/qubership-core-release-test-maven-lib-1",
                        baseBranchPatterns : [ "release/support-1.x.x" ]
                      }, {
                        repository : "Netcracker/qubership-core-release-test-maven-lib-2",
                        baseBranchPatterns : [ "release/support-2.x.x" ]
                      } ],
                      username : "renovate"
                    };""", result);
        }
    }
}
