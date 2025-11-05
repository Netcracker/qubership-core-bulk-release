package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.renovate.model.*;
import org.qubership.cloud.actions.renovate.model.docker.DockerArtifactVersion;
import org.qubership.cloud.actions.renovate.model.docker.RenovateReportDockerfile;
import org.qubership.cloud.actions.renovate.model.go.GoArtifactVersion;
import org.qubership.cloud.actions.renovate.model.go.RenovateReportGomod;
import org.qubership.cloud.actions.renovate.model.maven.MavenArtifactVersion;
import org.qubership.cloud.actions.renovate.model.maven.RenovateReportMaven;
import org.qubership.cloud.actions.renovate.model.regex.RegexArtifactVersion;
import org.qubership.cloud.actions.renovate.model.regex.RenovateReportRegex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class RenovateService {
    XrayService xrayService;
    ObjectMapper objectMapper;

    public RenovateService(XrayService xrayService, ObjectMapper objectMapper) {
        this.xrayService = xrayService;
        this.objectMapper = objectMapper;
    }

    public List<? extends Map<String, Object>> getRules(Path reportFilePath,
                                                        Map<String, ? extends Collection<String>> repos,
                                                        Pattern allowedVersionsPattern,
                                                        Collection<String> labels) {
        try {
            List<ArtifactVersionData<?>> artifactVersions = getArtifactVersionsWithRenovateData(reportFilePath);
            log.info("Found {} dependencies with renovate data:\n{}", artifactVersions.size(),
                    artifactVersions.stream().collect(Collectors.groupingBy(ArtifactVersion::getType))
                            .entrySet().stream().map(entry ->
                                    String.format("%s: %d", entry.getKey(), entry.getValue().size()))
                            .collect(Collectors.joining("\n")));
            Map<ArtifactVersion, Map<String, Set<String>>> fixes = findFixedVersions(repos, artifactVersions, allowedVersionsPattern, Severity.High);
            log.info("Dependencies with fixed CVEs:\n{}",
                    fixes.entrySet().stream()
                            .map(entry -> String.format("[%s] %s:%s [%s]",
                                    entry.getKey().getType(),
                                    entry.getKey().getPackageName(),
                                    entry.getKey().getVersion(),
                                    entry.getValue().entrySet().stream()
                                            .map(vulnVerEntry -> String.format("%s -> [%s]", vulnVerEntry.getKey(), String.join(", ", vulnVerEntry.getValue())))
                                            .collect(Collectors.joining("\n"))))
                            .collect(Collectors.joining("\n")));
            return gavsToRules(fixes, labels);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build renovate package rules for report: " + reportFilePath, e);
        }
    }

    public List<ArtifactVersionData<?>> getArtifactVersionsWithRenovateData(Path reportFilePath) throws IOException {
        RenovateReport renovateReport = objectMapper.readValue(Files.readString(reportFilePath), RenovateReport.class);
        List<ArtifactVersionData<?>> result = new ArrayList<>();
        // maven
        result.addAll(renovateReport.getRepositories().values().stream()
                .map(RenovateReportRepository::getPackageFiles)
                .filter(Objects::nonNull)
                .map(RenovateReportPackageFiles::getMaven)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(RenovateReportMaven::getDeps)
                .flatMap(List::stream)
                .filter(dep -> dep.getCurrentVersion() != null && !"import".equals(dep.getDepType()))
                .map(dep -> {
                    GAV gav = new GAV(String.format("%s:%s", dep.getDepName(), dep.getCurrentVersion()));
                    return new MavenArtifactVersion(gav, dep);
                })
                .distinct()
                .toList());
        // go
        result.addAll(renovateReport.getRepositories().values().stream()
                .map(RenovateReportRepository::getPackageFiles)
                .filter(Objects::nonNull)
                .map(RenovateReportPackageFiles::getGomod)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(RenovateReportGomod::getDeps)
                .flatMap(List::stream)
                .filter(dep -> dep.getCurrentVersion() != null)
                .map(dep -> new GoArtifactVersion(dep.getPackageName(), dep.getCurrentVersion(), dep))
                .distinct()
                .toList());
        // docker
        result.addAll(renovateReport.getRepositories().values().stream()
                .map(RenovateReportRepository::getPackageFiles)
                .filter(Objects::nonNull)
                .map(RenovateReportPackageFiles::getDockerfile)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(RenovateReportDockerfile::getDeps)
                .flatMap(List::stream)
                .filter(dep -> dep.getCurrentVersion() != null)
                .map(dep -> new DockerArtifactVersion(dep.getLookupName(), dep.getCurrentVersion(), dep))
                .distinct()
                .toList());
        // regex (alpine)
        result.addAll(renovateReport.getRepositories().values().stream()
                .map(RenovateReportRepository::getPackageFiles)
                .filter(Objects::nonNull)
                .map(RenovateReportPackageFiles::getRegex)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(RenovateReportRegex::getDeps)
                .flatMap(List::stream)
                .filter(dep -> dep.getCurrentVersion() != null)
                .map(dep -> new RegexArtifactVersion(dep.getPackageName(), dep.getCurrentVersion(), dep))
                .distinct()
                .toList());
        return result;
    }

    public Map<ArtifactVersion, Map<String, Set<String>>> findFixedVersions(Map<String, ? extends Collection<String>> repos,
                                                                            List<ArtifactVersionData<?>> artifactVersions,
                                                                            Pattern allowedVersionsPattern,
                                                                            Severity severity) {
        Predicate<XrayArtifactSummaryIssue> issueBySeverity = issue ->
                /*Objects.equals(issue.getIssue_type(), "security") && */issue.getSeverity().ordinal() <= severity.ordinal();
        return artifactVersions.stream()
                .map(data -> {
                    try {
                        // 1. load xray artifact summary
                        Collection<String> repositories = repos.get(data.getType().name().toLowerCase());
                        if (repositories == null || repositories.isEmpty()) {
                            throw new IllegalArgumentException("Repositories not configured for type: " + data.getType());
                        }
                        XrayArtifactSummaryElement artifactSummary = xrayService.getArtifactSummary(repositories, data.getArtifactPath());
                        if (artifactSummary == null) {
                            log.warn("Artifact summary not found for: {}", data.getArtifactPath());
                            return Optional.<Map.Entry<ArtifactVersion, FixedVersionData>>empty();
                        } else if (!LooseVersion.isValid(data.getVersion())) {
                            log.warn("Artifact version in not a valid version: {}. Must match pattern: '{}'",
                                    data.getVersion(), LooseVersion.versionPattern.pattern());
                            return Optional.<Map.Entry<ArtifactVersion, FixedVersionData>>empty();
                        }
                        // 2. filter artifact issues with severity >= requested
                        List<XrayArtifactSummaryIssue> issues = artifactSummary.getIssues().stream()
                                .filter(issueBySeverity)
                                .toList();
                        if (issues.isEmpty()) {
                            log.info("No vulnerabilities >= {} found for: {}", severity, data.getArtifactPath());
                        } else {
                            Set<String> currentCVEs = issues.stream()
                                    .flatMap(issue -> issue.getCves().stream().map(XrayArtifactSummaryCVE::getCve))
                                    .collect(Collectors.toSet());
                            LooseVersion currentVersion = new LooseVersion(data.getVersion());
                            // 3. load available versions for artifact
                            List<LooseVersion> newVersions = xrayService.getArtifactVersions(repositories, data).stream()
                                    .filter(v -> {
                                        boolean allowed = allowedVersionsPattern.matcher(v).matches();
                                        if (!allowed) {
                                            log.warn("Package with version '{}' not allowed and filtered out. Version '{}' must match pattern: {}",
                                                    data.getArtifactPath(v), v, allowedVersionsPattern.pattern());
                                        }
                                        return allowed;
                                    })
                                    .filter(LooseVersion::isValid)
                                    .map(LooseVersion::new)
                                    .filter(v -> v.compareTo(currentVersion) > 0)
                                    .sorted(LooseVersion::compareTo)
                                    .toList();
                            // 4. if there are versions >= gav, then load their summary to find versions with fixed issues
                            Map.Entry<ArtifactVersion, FixedVersionData> newVersiontoFixedCVEs = null;
                            // 5.a check if renovate has updates and the updates have versions with fixed issues
                            Optional<LooseVersion> minUpdate = data.getNewVersions().stream()
                                    .map(LooseVersion::new)
                                    .min(LooseVersion::compareTo);
                            if (minUpdate.isPresent()) {
                                newVersions = Stream.concat(Stream.of(minUpdate.get()), newVersions.stream()).distinct().toList();
                            }
                            // 5.b. find the lowest version with fixed issues
                            for (LooseVersion nVersion : newVersions) {
                                try {
                                    String newVersion = nVersion.getVersion();
                                    ArtifactVersion newArtifactVersion = data instanceof DockerArtifactVersion dockerData ?
                                            new DefaultArtifactVersion(data.getType(), dockerData.getRenovateData().getPackageName(), newVersion) :
                                            new DefaultArtifactVersion(data.getType(), data.getPackageName(), newVersion);
                                    XrayArtifactSummaryElement summary = xrayService.getArtifactSummary(repositories, data.getArtifactPath(newVersion));
                                    if (summary == null) {
                                        log.warn("Artifact summary not found for: {}", data.getArtifactPath(newVersion));
                                        continue;
                                    }
                                    Set<String> summaryCVEs = summary.getIssues().stream()
                                            .filter(issueBySeverity)
                                            .flatMap(issue -> issue.getCves().stream().map(XrayArtifactSummaryCVE::getCve))
                                            .collect(Collectors.toSet());
                                    if (summaryCVEs.isEmpty()) {
                                        // all vulnerabilities of >= severity fixed
                                        newVersiontoFixedCVEs = Map.entry(newArtifactVersion, new FixedVersionData(currentVersion.getVersion(), currentCVEs));
                                        log.info("Found the candidate with all vulnerabilities >= {} fixed [{}] from {} fixed in: {}",
                                                severity, String.join(", ", currentCVEs), data.getArtifactPath(), newVersion);
                                        break;
                                    } else {
                                        if (summaryCVEs.size() < currentCVEs.size()) {
                                            Set<String> fixedCVEs = currentCVEs.stream().filter(cve -> !summaryCVEs.contains(cve)).collect(Collectors.toSet());
                                            if (newVersiontoFixedCVEs == null) {
                                                newVersiontoFixedCVEs = Map.entry(newArtifactVersion, new FixedVersionData(currentVersion.getVersion(), fixedCVEs));
                                                log.info("Found new candidate with fixed vulnerabilities >= {} [{}] from {} fixed in: {}",
                                                        severity, String.join(", ", currentCVEs), data.getArtifactPath(), newVersion);
                                            } else {
                                                Set<String> existingSummaryCVEs = newVersiontoFixedCVEs.getValue().getFixedCVEs();
                                                if (fixedCVEs.size() > existingSummaryCVEs.size()) {
                                                    newVersiontoFixedCVEs = Map.entry(newArtifactVersion, new FixedVersionData(currentVersion.getVersion(), fixedCVEs));
                                                    log.info("Found new candidate with more fixed vulnerabilities >= {} [{}] from {} fixed in: {}",
                                                            severity, String.join(", ", currentCVEs), data.getArtifactPath(), newVersion);
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    throw new IllegalStateException(String.format("Failed to load artifact summary for new version: %s", nVersion), e);
                                }
                            }
                            return Optional.ofNullable(newVersiontoFixedCVEs);
                        }
                        return Optional.<Map.Entry<ArtifactVersion, FixedVersionData>>empty();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to process artifact", e);
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    FixedVersionData fixedVersionData = entry.getValue();
                    return Map.of(fixedVersionData.getVulnerableVersion(), fixedVersionData.getFixedCVEs());
                }, (m1, m2) -> Stream.concat(m1.entrySet().stream(), m2.entrySet().stream())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, TreeMap::new))));
    }

    public List<? extends Map<String, Object>> gavsToRules(Map<ArtifactVersion, Map<String, Set<String>>> artifactVersions, Collection<String> labels) {
        return artifactVersions.entrySet().stream()
                .sorted(Comparator.<Map.Entry<ArtifactVersion, Map<String, Set<String>>>, String>comparing(entry -> entry.getKey().getPackageName())
                        .thenComparing(entry -> new LooseVersion(entry.getKey().getVersion())))
                .flatMap(entry -> {
                    ArtifactVersion artifactVersion = entry.getKey();
                    return entry.getValue().entrySet().stream()
                            .map(vulnerableVerEntry -> {
                                String vulnerableVersion = vulnerableVerEntry.getKey();
                                Collection<String> fixedCVEs = vulnerableVerEntry.getValue();
                                RenovateMap rule = new RenovateMap();
                                rule.put("matchPackageNames", List.of(artifactVersion.getPackageName()));
                                rule.put("allowedVersions", String.format("/^%s$/", artifactVersion.getVersion()));
                                rule.put("matchCurrentVersion", vulnerableVersion);
                                rule.put("enabled", true);
                                rule.put("addLabels", labels);
                                rule.put("prBodyNotes", List.of(
                                        """
                                                ⚠️Vulnerability alert
                                                This MR fixes the following CVEs:
                                                """.replace("\n", "<br>") + fixedCVEs.stream()
                                                .map(cve -> new MessageFormat("([{0}](https://nvd.nist.gov/vuln/detail/{0})").format(new Object[]{cve}))
                                                .collect(Collectors.joining("<br>"))));
                                return rule;
                            });

                })
                .toList();
    }

}
