package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class RenovatePackageRule {

    public static Pattern pattern = Pattern.compile("^\\[(matchManagers=(?<matchManagers>.+?);?)?" +
                                                    "(matchDatasources=(?<matchDatasources>.+?);?)?" +
                                                    "(matchPackageNames=(?<matchPackageNames>.+?);?)?" +
                                                    "(matchUpdateTypes=(?<matchUpdateTypes>.+?);?)?" +
                                                    "(registryUrls=(?<registryUrls>.+?);?)?" +
                                                    "(allowedVersions=(?<allowedVersions>.+?);?)?" +
                                                    "(groupName=(?<groupName>.+?);?)?" +
                                                    "(automerge=(?<automerge>.+?);?)?" +
                                                    "]$");
    List<String> matchManagers;
    List<String> matchDatasources;
    List<String> matchPackageNames;
    List<String> matchUpdateTypes;
    List<String> registryUrls;
    String allowedVersions;
    String groupName;
    Boolean automerge;

    public RenovatePackageRule() {
    }

    public RenovatePackageRule(String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid package rule: '%s'. Must match: '%s'", value, pattern));
        }
        this.matchManagers = toList(matcher, "matchManagers");
        this.matchDatasources = toList(matcher, "matchDatasources");
        this.matchPackageNames = toList(matcher, "matchPackageNames");
        this.matchUpdateTypes = toList(matcher, "matchUpdateTypes");
        this.registryUrls = toList(matcher, "registryUrls");
        this.allowedVersions = matcher.group("allowedVersions");
        this.groupName = matcher.group("groupName");
        this.automerge = Optional.ofNullable(matcher.group("automerge")).map(Boolean::parseBoolean).orElse(null);
    }

    static List<String> toList(Matcher matcher, String group) {
        String v = matcher.group(group);
        if (v == null) return null;
        return Arrays.stream(v.split("&")).toList();
    }
}
