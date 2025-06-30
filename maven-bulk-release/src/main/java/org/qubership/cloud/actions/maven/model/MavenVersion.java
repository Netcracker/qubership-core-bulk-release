package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class MavenVersion {

    static Pattern versionPattern = Pattern.compile("^(?<major>\\d+)(\\.(?<minor>\\d+))?(\\.(?<patch>\\d+))?(?<sufix>.+)?$");

    private int major;
    private int minor;
    private int patch;
    private String suffix;

    public MavenVersion(String version) {
        Matcher matcher = versionPattern.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid maven version: " + version);
        }
        major = Integer.parseInt(matcher.group("major"));
        minor = Optional.ofNullable(matcher.group("minor")).map(Integer::parseInt).orElse(0);
        patch = Optional.ofNullable(matcher.group("patch")).map(Integer::parseInt).orElse(0);
        suffix = matcher.group("sufix");
    }

    public static boolean isValid(String version) {
        return versionPattern.matcher(version).matches();
    }
}
