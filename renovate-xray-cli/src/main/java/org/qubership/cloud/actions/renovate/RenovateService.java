package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.renovate.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class RenovateService {
    XrayService xrayService;
    RenovateRulesService renovateRulesService;
    ObjectMapper objectMapper;

    public RenovateService(XrayService xrayService, RenovateRulesService renovateRulesService, ObjectMapper objectMapper) {
        this.xrayService = xrayService;
        this.renovateRulesService = renovateRulesService;
        this.objectMapper = objectMapper;
    }

    public List<? extends Map> getRules(Path reportFilePath,
                                        Map<String, ? extends Collection<String>> repos,
                                        Collection<String> labels) {
        try {
            List<ArtifactVersionData<?>> artifactVersions = getArtifactVersionsWithRenovateData(reportFilePath);
            Map<ArtifactVersion, Set<String>> fixes = findFixedVersions(repos, artifactVersions, Severity.High);
            log.info("Versions with fixed CVEs:\n{}",
                    fixes.entrySet().stream()
                            .map(entry -> String.format("[%s] %s:%s [%s]",
                                    entry.getKey().getType(),
                                    entry.getKey().getPackageName(),
                                    entry.getKey().getVersion(),
                                    String.join(", ", entry.getValue())))
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
                .map(mavenDep -> {
                    GAV gav = new GAV(String.format("%s:%s", mavenDep.getDepName(), mavenDep.getCurrentVersion()));
                    return new MavenArtifactVersion(gav, mavenDep);
                })
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
                .map(goDep -> new GoArtifactVersion(goDep.getPackageName(), goDep.getCurrentVersion(), goDep))
                .toList());
        return result;
    }

    public Map<ArtifactVersion, Set<String>> findFixedVersions(Map<String, ? extends Collection<String>> repos,
                                                               List<ArtifactVersionData<?>> artifactVersions,
                                                               Severity severity) {
        Predicate<XrayArtifactSummaryIssue> issueBySeverity = issue ->
                /*Objects.equals(issue.getIssue_type(), "security") && */issue.getSeverity().ordinal() <= severity.ordinal();
        return artifactVersions.stream()
                .map(data -> {
                    try {
                        // 1. load xray artifact summary
                        Collection<String> repositories = switch (data.getType()) {
                            case maven -> repos.get("maven");
                            case go -> repos.get("go");
                            default -> throw new IllegalArgumentException("Unsupported type: " + data.getType());
                        };
                        XrayArtifactSummaryElement artifactSummary = xrayService.getArtifactSummary(repositories, data.getArtifactPath());
                        if (artifactSummary == null) {
                            log.warn("Artifact summary not found for: {}", data.getArtifactPath());
                            return Optional.<Map.Entry<ArtifactVersion, Set<String>>>empty();
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
                                    .map(LooseVersion::new)
                                    .filter(v -> v.getSuffix() == null || !v.getSuffix().contains("SNAPSHOT"))
                                    .filter(v -> v.compareTo(currentVersion) > 0)
                                    .sorted()
                                    .toList();
                            // 4. if there are versions >= gav, then load their summary to find versions with fixed issues
                            Map.Entry<ArtifactVersion, Set<String>> newVersiontoFixedCVEs = null;
                            // 5.a check if renovate has updates and the updates have versions with fixed issues
                            Optional<LooseVersion> minUpdate = data.getNewVersions().stream()
                                    .map(LooseVersion::new)
                                    .min(Comparator.naturalOrder());
                            if (minUpdate.isPresent()) {
                                newVersions = Stream.concat(Stream.of(minUpdate.get()), newVersions.stream()).distinct().toList();
                            }
                            // 5.b. find the lowest version with fixed issues
                            for (LooseVersion nVersion : newVersions) {
                                try {
                                    String newVersion = nVersion.getVersion();
                                    ArtifactVersion newArtifactVersion = new DefaultArtifactVersion(data.getType(), data.getPackageName(), newVersion);
                                    XrayArtifactSummaryElement summary = xrayService.getArtifactSummary(repositories, data.getArtifactPath(newVersion));
                                    Set<String> summaryCVEs = summary.getIssues().stream()
                                            .filter(issueBySeverity)
                                            .flatMap(issue -> issue.getCves().stream().map(XrayArtifactSummaryCVE::getCve))
                                            .collect(Collectors.toSet());
                                    if (summaryCVEs.isEmpty()) {
                                        // all vulnerabilities of >= severity fixed
                                        newVersiontoFixedCVEs = Map.entry(newArtifactVersion, currentCVEs);
                                        log.info("Found the candidate with all vulnerabilities >= {} fixed [{}] from {} fixed in: {}",
                                                severity, String.join(", ", currentCVEs), data.getArtifactPath(), newVersion);
                                        break;
                                    } else {
                                        if (summaryCVEs.size() < currentCVEs.size()) {
                                            Set<String> fixedCVEs = currentCVEs.stream().filter(cve -> !summaryCVEs.contains(cve)).collect(Collectors.toSet());
                                            if (newVersiontoFixedCVEs == null) {
                                                newVersiontoFixedCVEs = Map.entry(newArtifactVersion, fixedCVEs);
                                                log.info("Found new candidate with fixed vulnerabilities >= {} [{}] from {} fixed in: {}",
                                                        severity, String.join(", ", currentCVEs), data.getArtifactPath(), newVersion);
                                            } else {
                                                Set<String> existingSummaryCVEs = newVersiontoFixedCVEs.getValue();
                                                if (fixedCVEs.size() > existingSummaryCVEs.size()) {
                                                    newVersiontoFixedCVEs = Map.entry(newArtifactVersion, fixedCVEs);
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
                        return Optional.<Map.Entry<ArtifactVersion, Set<String>>>empty();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to process artifact", e);
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<? extends Map> gavsToRules(Map<ArtifactVersion, Set<String>> artifactVersions, Collection<String> labels) {
        return artifactVersions.entrySet().stream()
                .sorted(Comparator.<Map.Entry<ArtifactVersion, Set<String>>, String>comparing(entry -> entry.getKey().getPackageName())
                        .thenComparing(entry -> new LooseVersion(entry.getKey().getVersion())))
                .map(entry -> {
                    ArtifactVersion artifactVersion = entry.getKey();
                    Collection<String> fixedCVEs = entry.getValue();
                    RenovateMap rule = new RenovateMap();
                    rule.put("matchPackageNames", List.of(artifactVersion.getPackageName()));
                    rule.put("allowedVersions", String.format("/^%s$/", artifactVersion.getVersion()));
                    rule.put("enabled", true);
                    rule.put("addLabels", labels);
                    rule.put("prBodyNotes", List.of(
                            """
                                    ⚠️Vulnerability alert
                                    This MR fixes the following CVEs:
                                    """ + String.join("\n", fixedCVEs)));
                    return rule;
                })
                .toList();
    }

}
