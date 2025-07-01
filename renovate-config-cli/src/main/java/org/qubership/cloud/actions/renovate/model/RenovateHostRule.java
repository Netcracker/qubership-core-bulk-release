package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class RenovateHostRule {
    public static Pattern pattern = Pattern.compile("^(?<hostType>.+?)\\[matchHost=(?<matchHost>.+?);username=(?<username>.+?);password=(?<password>.+?)]$");

    String hostType;
    String matchHost;
    String username;
    String password;

    public RenovateHostRule(String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid host rule: '%s'. Must match: '%s'", value, pattern));
        }
        this.hostType = matcher.group("hostType");
        this.matchHost = matcher.group("matchHost");
        this.username = matcher.group("username");
        this.password = matcher.group("password");
    }
}
