package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class RenovateMavenRepository {
    public static Pattern pattern = Pattern.compile("^(?<id>.+?)\\[url=(?<url>.+?);username=(?<username>.+?);password=(?<password>.+?)]$");
    String id;
    String url;
    String username;
    String password;

    public RenovateMavenRepository(String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid maven repository: '%s'. Must match: '%s'", value, pattern));
        }
        this.id = matcher.group("id");
        this.url = matcher.group("url");
        this.username = matcher.group("username");
        this.password = matcher.group("password");
    }
}
