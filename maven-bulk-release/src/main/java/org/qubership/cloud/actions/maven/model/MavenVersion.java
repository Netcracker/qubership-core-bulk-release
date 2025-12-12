package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
public class MavenVersion implements Comparable<MavenVersion> {

    static Pattern versionPattern = Pattern.compile("^v?(?<major>\\d+)(\\.(?<minor>\\d+))?(\\.(?<patch>\\d+))?(?<suffix>.+)?$");

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
        this.major = Integer.parseInt(matcher.group("major"));
        this.minor = Optional.ofNullable(matcher.group("minor")).map(Integer::parseInt).orElse(0);
        this.patch = Optional.ofNullable(matcher.group("patch")).map(Integer::parseInt).orElse(0);
        this.suffix = matcher.group("suffix");
    }

    public MavenVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.version = String.format("%d.%d.%d", major, minor, patch);
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
//                        if (suffix != null && suffix.matches("^\\.\\d+$")) {
                        if (suffix != null) {
                            // remove suffix
                            this.suffix = null;
                            suffix = null;
                        }
                    }
                }
                return Stream.of(major, minor, patch).filter(Objects::nonNull).collect(Collectors.joining(".")) + (suffix == null ? "" : suffix);
            }
        });
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
        this.version = String.format("%d.%d.%d%s", major, minor, patch, suffix == null ? "" : suffix);
    }

    public String toString() {
        return version;
    }

    public static boolean isValid(String version) {
        return versionPattern.matcher(version).matches();
    }

    @Override
    public int compareTo(MavenVersion o) {
        return Comparator.comparing(MavenVersion::getMajor).thenComparing(MavenVersion::getMinor).thenComparing(MavenVersion::getPatch).compare(this, o);
    }
}
