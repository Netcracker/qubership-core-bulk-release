package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qubership.cloud.actions.renovate.model.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
                "--artifactoryGoRepository=go-repository",
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

            BiFunction<ArtifactVersion, List<XrayArtifactSummaryIssue>, XrayArtifactSummary> buildSummary = (ArtifactVersion artifactVersion, List<XrayArtifactSummaryIssue> issues) -> {
                XrayArtifactSummary summary = new XrayArtifactSummary();
                XrayArtifactSummaryGeneral xrayArtifactSummaryGeneral = new XrayArtifactSummaryGeneral();
                final String packageName = artifactVersion.getPackageName();
                final String version = artifactVersion.getVersion();
                String path = switch (artifactVersion.getType()) {
                    //        "default/pd.saas.golang/git.netcracker.com/prod.platform.cloud_core/libs/go/core/v2/@v/v2.2.9.zip"
                    case go -> MessageFormat.format("default/go-repository/{0}/@v/{1}.zip", packageName, version);
                    //        "default/central_maven_org.mvn.proxy-cache/io/undertow/undertow-core/2.3.11.Final/undertow-core-2.3.11.Final.jar"
                    case maven -> {
                        String[] packageParts = packageName.split("/");
                        String groupId = Arrays.stream(Arrays.copyOfRange(packageParts, 0, packageParts.length - 1)).collect(Collectors.joining("/"));
                        String artifactId = packageParts[packageParts.length - 1];
                        yield MessageFormat.format("default/maven-repository/{0}/{1}/{2}/{1}-{2}.jar", groupId, artifactId, version);
                    }
                    default ->
                            throw new IllegalArgumentException("Unsupported artifact type: " + artifactVersion.getType());
                };
                xrayArtifactSummaryGeneral.setPath(path);

                XrayArtifactSummaryElement xrayArtifactSummaryElement = new XrayArtifactSummaryElement(xrayArtifactSummaryGeneral, issues);

                summary.setArtifacts(List.of(xrayArtifactSummaryElement));
                return summary;
            };

            XrayArtifactSummaryIssue xrayArtifactSummaryIssue1 = new XrayArtifactSummaryIssue();
            xrayArtifactSummaryIssue1.setSeverity(Severity.High);
            XrayArtifactSummaryCVE xrayArtifactSummaryCVE1 = new XrayArtifactSummaryCVE();
            xrayArtifactSummaryCVE1.setCve("CVE-2023-4444");
            xrayArtifactSummaryIssue1.setCves(List.of(xrayArtifactSummaryCVE1));
            List<XrayArtifactSummaryIssue> issues1 = List.of(xrayArtifactSummaryIssue1);

            XrayArtifactSummaryIssue xrayArtifactSummaryIssue2 = new XrayArtifactSummaryIssue();
            xrayArtifactSummaryIssue2.setSeverity(Severity.High);
            XrayArtifactSummaryCVE xrayArtifactSummaryCVE2 = new XrayArtifactSummaryCVE();
            xrayArtifactSummaryCVE2.setCve("CVE-2023-8888");
            xrayArtifactSummaryIssue2.setCves(List.of(xrayArtifactSummaryCVE2));
            List<XrayArtifactSummaryIssue> issues2 = List.of(xrayArtifactSummaryIssue2);

            HttpResponse antJunitVersionsResponse = Mockito.mock(HttpResponse.class);
            Mockito.when(antJunitVersionsResponse.statusCode()).thenReturn(200);
            Mockito.when(antJunitVersionsResponse.body()).thenReturn(mapper.writeValueAsString(new ArtifactoryVersions(List.of(
                    new ArtifactoryVersion("1.6.5", false),
                    new ArtifactoryVersion("1.6.6", false),
                    new ArtifactoryVersion("1.6.7", false)
            ))));
            HttpResponse testifyVersionsResponse = Mockito.mock(HttpResponse.class);
            Mockito.when(testifyVersionsResponse.statusCode()).thenReturn(200);
            Mockito.when(testifyVersionsResponse.body()).thenReturn("""
                    v1.10.0
                    v1.11.1
                    v1.11.2
                    """);

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

                        Pattern pathPattern = Pattern.compile("^default/(?<repository>[^/]+)/(?<path>.+)$");
                        Matcher matcher = pathPattern.matcher(summaryRequest.getPaths().getFirst());
                        if (matcher.matches()) {
                            String repository = matcher.group("repository");
                            String path = matcher.group("path");
                            if (Objects.equals(repository, "maven-repository")) {
                                Matcher mavenPathMatcher = Pattern.compile("^(?<package>.+?)/(?<version>[\\d.]+.+?)/(?<jar>.+)\\.jar$").matcher(path);
                                if (!mavenPathMatcher.matches()) {
                                    throw new IllegalStateException("Unexpected path: " + matcher.pattern());
                                }
                                String packageName = mavenPathMatcher.group("package");
                                String version = mavenPathMatcher.group("version");
                                List<XrayArtifactSummaryIssue> issues;
                                if (packageName.equals("ant/ant-junit") && (version.equals("1.6.5") || version.equals("1.6.6"))) {
                                    issues = issues1;
                                } else {
                                    issues = List.of();
                                }
                                String body = mapper.writeValueAsString(buildSummary.apply(new DefaultArtifactVersion(ArtifactType.maven, packageName, version), issues));
                                Mockito.when(response.body()).thenReturn(body);
                                return response;
                            } else if (Objects.equals(repository, "go-repository")) {
                                //        "github.com/stretchr/testify/@v/v1.10.0.zip"
                                Matcher goPathMatcher = Pattern.compile("^(?<package>.+?)/@v/(?<version>v[\\d.]+.+?)\\.zip$").matcher(path);
                                if (!goPathMatcher.matches()) {
                                    throw new IllegalStateException("Unexpected path: " + matcher.pattern());
                                }
                                String packageName = goPathMatcher.group("package");
                                String version = goPathMatcher.group("version");
                                List<XrayArtifactSummaryIssue> issues;
                                if (packageName.equals("github.com/stretchr/testify") && (version.equals("v1.10.0") || version.equals("v1.11.1"))) {
                                    issues = issues2;
                                } else {
                                    issues = List.of();
                                }
                                String body = mapper.writeValueAsString(buildSummary.apply(new DefaultArtifactVersion(ArtifactType.go, packageName, version), issues));
                                Mockito.when(response.body()).thenReturn(body);
                                return response;
                            } else {
                                throw new IllegalStateException("Unexpected repository: " + repository);
                            }
                        } else {
                            throw new IllegalStateException("Unexpected request: " + request);
                        }
                    } else if (method.equals("GET") && string.equals("https://artifactory.com/artifactory/api/search/versions?g=ant&a=ant-junit&repos=maven-repository")) {
                        return antJunitVersionsResponse;
                    } else if (method.equals("GET") && string.equals("https://artifactory.com/api/go/go-repository/github.com/stretchr/testify/@v/list")) {
                        return testifyVersionsResponse;
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
                        allowedVersions : "/^1.6.7$/",
                        enabled : true,
                        addLabels : [ "security" ],
                        prBodyNotes : [ "⚠️Vulnerability alert\\nThis MR fixes the following CVEs:\\nCVE-2023-4444" ]
                      }, {
                        matchPackageNames : [ "github.com/stretchr/testify" ],
                        allowedVersions : "/^v1.11.2$/",
                        enabled : true,
                        addLabels : [ "security" ],
                        prBodyNotes : [ "⚠️Vulnerability alert\\nThis MR fixes the following CVEs:\\nCVE-2023-8888" ]
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
