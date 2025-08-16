package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.maven.model.GA;
import org.qubership.cloud.actions.maven.model.GAV;
import org.qubership.cloud.actions.maven.model.MavenVersion;
import org.qubership.cloud.actions.maven.model.VersionIncrementType;
import org.qubership.cloud.actions.renovate.model.RenovateMap;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RenovateRulesService {

    public List<? extends Map> gavsToRules(Collection<GAV> gavs, VersionIncrementType type, Map<String, Pattern> groupNamePatternsMap) {
        return gavs.stream()
                .sorted(Comparator.comparing(GAV::getGroupId).thenComparing(GAV::getArtifactId))
                .collect(Collectors.toMap(GA::getGroupId, gav -> {
                            LinkedHashMap<String, Set<String>> versionToArtifactIds = new LinkedHashMap<>();
                            HashSet<String> set = new HashSet<>();
                            set.add(gav.getArtifactId());
                            versionToArtifactIds.put(gav.getVersion(), set);
                            return versionToArtifactIds;
                        },
                        (m1, m2) -> {
                            m2.forEach((k, v) -> m1.computeIfAbsent(k, k1 -> new HashSet<>()).addAll(v));
                            return m1;
                        }))
                .entrySet().stream()
                .flatMap(group -> {
                    String groupId = group.getKey();
                    String groupName = groupNamePatternsMap.entrySet().stream()
                            .filter(e -> e.getValue().matcher(groupId).matches())
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(groupId);
                    return group.getValue().entrySet().stream()
                            .map(versionToArtifactIds -> {
                                String version = versionToArtifactIds.getKey();
                                Set<String> artifactIds = versionToArtifactIds.getValue();
                                RenovateMap rule = new RenovateMap();
                                rule.put("matchPackageNames", artifactIds.stream()
                                        .map(artifactId -> groupId + ":" + artifactId)
                                        .sorted()
                                        .toList());
                                rule.put("groupName", groupName);
                                MavenVersion mavenVersion = new MavenVersion(version);
                                if (type == null) {
                                    rule.put("allowedVersions", String.format("/^%s$/", mavenVersion));
                                } else if (type == VersionIncrementType.PATCH) {
                                    rule.put("allowedVersions", String.format("/^%d\\.%d(\\.\\d+)+%s$/",
                                            mavenVersion.getMajor(), mavenVersion.getMinor(), Optional.ofNullable(mavenVersion.getSuffix()).orElse("")));
                                } else {
                                    throw new IllegalArgumentException(String.format("Unsupported version increment type '%s' to build a packageRule", type));
                                }
                                return rule;
                            });
                })
                .toList();
    }

    public Map<String, Object> mergeMaps(Map<String, Object> map, Map<String, Object> defaultMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (map == null) map = new LinkedHashMap<>();
        if (defaultMap == null) defaultMap = new LinkedHashMap<>();
        keys.addAll(defaultMap.keySet());
        keys.addAll(map.keySet());
        for (String key : keys) {
            Object mapValue = map.get(key);
            Object defaultMapValue = defaultMap.get(key);
            if (mapValue instanceof Map mVal && defaultMapValue instanceof Map dVal) {
                result.put(key, mergeMaps(mVal, dVal));
            } else if (mapValue instanceof Collection mVal && defaultMapValue instanceof Collection dVal) {
                result.put(key, concatCollections(mVal, dVal));
            } else {
                result.put(key, map.getOrDefault(key, defaultMap.get(key)));
            }
        }
        return result;
    }

    public Collection<Object> concatCollections(Collection<Object> collection, Collection<Object> defaultCollection) {
        boolean isSet = collection instanceof Set || defaultCollection instanceof Set;
        if (collection == null) {
            collection = isSet ? new LinkedHashSet<>() : new ArrayList<>();
        }
        if (defaultCollection == null) {
            defaultCollection = isSet ? new LinkedHashSet<>() : new ArrayList<>();
        } else {
            defaultCollection = isSet ? new LinkedHashSet<>(defaultCollection) : new ArrayList<>(defaultCollection);
        }
        for (Object o : collection) {
            if (!defaultCollection.contains(o)) {
                defaultCollection.add(o);
            }
        }
        return defaultCollection;
    }
}
