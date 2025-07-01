package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
public class MavenVersion {

    static Pattern versionPattern = Pattern.compile("^(?<major>\\d+)(\\.(?<minor>\\d+))?(\\.(?<patch>\\d+))?(?<suffix>.+)?$");

    private String version;
    private int major;
    private int minor;
    private int patch;
    private String suffix;

    public MavenVersion(String version) {
        Matcher matcher = versionPattern.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid maven version: " + version);
        }
        this.version = version;
        major = Integer.parseInt(matcher.group("major"));
        minor = Optional.ofNullable(matcher.group("minor")).map(Integer::parseInt).orElse(0);
        patch = Optional.ofNullable(matcher.group("patch")).map(Integer::parseInt).orElse(0);
        suffix = matcher.group("suffix");
    }

    public void update(VersionIncrementType type, int value) {
        Matcher matcher = versionPattern.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid maven version: " + version);
        }
        this.version = matcher.replaceAll(mr -> {
            String part = mr.group(type.name().toLowerCase());
            if (part == null) {
                return mr.group();
            } else {
                String major = mr.group(VersionIncrementType.MAJOR.name().toLowerCase());
                String minor = mr.group(VersionIncrementType.MINOR.name().toLowerCase());
                String patch = mr.group(VersionIncrementType.PATCH.name().toLowerCase());
                String suffix = mr.group("suffix");
                switch (type) {
                    case MAJOR -> {
                        this.major = value;
                        major = String.valueOf(value);
                    }
                    case MINOR -> {
                        this.minor = value;
                        minor = String.valueOf(value);
                    }
                    case PATCH -> {
                        this.patch = value;
                        patch = String.valueOf(value);
                        if (suffix != null && suffix.matches("^\\.\\d+$")) {
                            // remove number suffix
                            this.suffix = null;
                            suffix = null;
                        }
                    }
                }
                return Stream.of(major, minor, patch).filter(Objects::nonNull).collect(Collectors.joining(".")) + (suffix == null ? "" : suffix);
            }
        });
    }

    public String toString() {
        return version;
    }

    public static boolean isValid(String version) {
        return versionPattern.matcher(version).matches();
    }
}
