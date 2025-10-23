package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.maven.model.MavenVersion;
import org.qubership.cloud.actions.renovate.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
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

    public List<? extends Map> getRules(Path reportFilePath, List<String> repos, Map<String, Pattern> groupNamePatternsMap, Collection<String> labels) {
        try {
            Map<GAV, RenovateReportMavenDep> gavs = getGAVs(reportFilePath);
            Map<GAV, Set<String>> fixes = findFixedGavs(repos, gavs, Severity.High);
            log.info("Versions with fixed CVEs:\n{}",
                    fixes.entrySet().stream()
                            .map(entry-> String.format("%s: [%s]", entry.getKey(), String.join(", ", entry.getValue())))
                            .collect(Collectors.joining("\n")));
            return fixes.keySet().stream().flatMap(gav ->
                    renovateRulesService.gavsToRules(List.of(gav), null, groupNamePatternsMap)
                            .stream()
                            .peek(rule -> {
                                rule.put("enabled", true);
                                rule.put("addLabels", labels);
                                rule.put("prBodyNotes", List.of(
                                        """
                                                <span style="color:red">Vulnerability alert</span>
                                                This MR fixes the following CVEs:
                                                """ + String.join("\n", fixes.get(gav))));
                            })).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build renovate package rules for report: " + reportFilePath, e);
        }
    }

    public Map<GAV, RenovateReportMavenDep> getGAVs(Path reportFilePath) throws IOException {
        RenovateReport renovateReport = objectMapper.readValue(Files.readString(reportFilePath), RenovateReport.class);
        return renovateReport.getRepositories().values().stream()
                .map(rep -> rep.getPackageFiles().getMaven())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(RenovateReportMaven::getDeps)
                .flatMap(List::stream)
                .filter(dep -> dep.getCurrentVersion() != null && !"import".equals(dep.getDepType()))
                .collect(Collectors.toMap(
                        dep -> new GAV(String.format("%s:%s", dep.getDepName(), dep.getCurrentVersion())),
                        dep -> dep,
                        (dep1, dep2) -> dep1,
                        TreeMap::new));
    }

    public Map<GAV, Set<String>> findFixedGavs(List<String> repositories, Map<GAV, RenovateReportMavenDep> gavs, Severity severity) {
        Predicate<XrayArtifactSummaryIssue> issueBySeverity = issue ->
                /*Objects.equals(issue.getIssue_type(), "security") && */issue.getSeverity().ordinal() <= severity.ordinal();
        return gavs.entrySet().stream()
                .map(entry -> {
                    GAV gav = entry.getKey();
                    RenovateReportMavenDep reportMavenDep = entry.getValue();
                    try {
                        // 1. load xray artifact summary
                        XrayArtifactSummaryElement artifactSummary = xrayService.getArtifactSummary(repositories, gav);
                        if (artifactSummary == null) {
                            log.warn("Artifact summary not found for: {}", gav);
                            return Optional.<Map.Entry<GAV, Set<String>>>empty();
                        }
                        // 2. filter artifact issues with severity >= requested
                        List<XrayArtifactSummaryIssue> issues = artifactSummary.getIssues().stream()
                                .filter(issueBySeverity)
                                .toList();
                        if (issues.isEmpty()) {
                            log.info("No vulnerabilities >= {} found for: {}", severity, gav);
                        } else {
                            Set<String> currentCVEs = issues.stream()
                                    .flatMap(issue -> issue.getCves().stream().map(XrayArtifactSummaryCVE::getCve))
                                    .collect(Collectors.toSet());
                            MavenVersion currentVersion = new MavenVersion(gav.getVersion());
                            // 3. load available versions for artifact
                            List<MavenVersion> newVersions = xrayService.getArtifactVersions(repositories, gav).stream()
                                    .filter(v -> !v.isIntegration())
                                    .map(v -> new MavenVersion(v.getVersion()))
                                    .filter(v -> v.getSuffix() == null || !v.getSuffix().contains("SNAPSHOT"))
                                    .filter(v -> v.compareTo(currentVersion) > 0)
                                    .sorted()
                                    .toList();
                            // 4. if there are versions >= gav, then load their summary to find versions with fixed issues
                            Map.Entry<GAV, Set<String>> newGAVtoFixedCVEs = null;
                            // 5.a check if renovate has updates and the updates have versions with fixed issues
                            Optional<MavenVersion> minUpdate = reportMavenDep.getUpdates().stream()
                                    .map(RenovateReportMavenDepUpdate::getNewVersion)
                                    .filter(Objects::nonNull)
                                    .map(MavenVersion::new)
                                    .min(Comparator.naturalOrder());
                            if (minUpdate.isPresent()) {
                                newVersions = Stream.concat(Stream.of(minUpdate.get()), newVersions.stream()).toList();
                            }
                            // 5.b. find the lowest version with fixed issues
                            for (MavenVersion newVersion : newVersions) {
                                GAV newGav = new GAV(gav.getGroupId(), gav.getArtifactId(), newVersion.toString());
                                try {
                                    XrayArtifactSummaryElement summary = xrayService.getArtifactSummary(repositories, newGav);
                                    Set<String> summaryCVEs = summary.getIssues().stream()
                                            .filter(issueBySeverity)
                                            .flatMap(issue -> issue.getCves().stream().map(XrayArtifactSummaryCVE::getCve))
                                            .collect(Collectors.toSet());
                                    if (summaryCVEs.isEmpty()) {
                                        // all vulnerabilities of >= severity fixed
                                        newGAVtoFixedCVEs = Map.entry(newGav, currentCVEs);
                                        log.info("Found the candidate with all vulnerabilities >= {} fixed [{}] from {} fixed in: {}",
                                                severity, String.join(", ", currentCVEs), gav, newGav.getVersion());
                                        break;
                                    } else {
                                        if (summaryCVEs.size() < currentCVEs.size()) {
                                            Set<String> fixedCVEs = currentCVEs.stream().filter(cve -> !summaryCVEs.contains(cve)).collect(Collectors.toSet());
                                            if (newGAVtoFixedCVEs == null) {
                                                newGAVtoFixedCVEs = Map.entry(newGav, fixedCVEs);
                                                log.info("Found new candidate with fixed vulnerabilities >= {} [{}] from {} fixed in: {}",
                                                        severity, String.join(", ", currentCVEs), gav, newGav.getVersion());
                                            } else {
                                                Set<String> existingSummaryCVEs = newGAVtoFixedCVEs.getValue();
                                                if (fixedCVEs.size() > existingSummaryCVEs.size()) {
                                                    newGAVtoFixedCVEs = Map.entry(newGav, fixedCVEs);
                                                    log.info("Found new candidate with more fixed vulnerabilities >= {} [{}] from {} fixed in: {}",
                                                            severity, String.join(", ", currentCVEs), gav, newGav.getVersion());
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    throw new IllegalStateException(String.format("Failed to load artifact summary for new version: %s", newGav), e);
                                }
                            }
                            return Optional.ofNullable(newGAVtoFixedCVEs);
                        }
                        return Optional.<Map.Entry<GAV, Set<String>>>empty();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to process artifact", e);
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

}
