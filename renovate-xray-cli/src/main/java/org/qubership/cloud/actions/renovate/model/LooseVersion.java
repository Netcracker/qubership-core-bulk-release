package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import org.qubership.cloud.actions.maven.model.VersionIncrementType;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
public class LooseVersion implements Comparable<LooseVersion> {

    static Pattern versionPattern = Pattern.compile("^(?<perfix>[^\\d]+)?(?<major>\\d+)(\\.(?<minor>\\d+))?(\\.(?<patch>\\d+))?(?<suffix>.+)?$");

    private String version;
    private int major;
    private int minor;
    private int patch;
    private String suffix;

    public LooseVersion(String version) {
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

    public LooseVersion(int major, int minor, int patch) {
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
    public int compareTo(LooseVersion o) {
        return Comparator.comparing(LooseVersion::getMajor).thenComparing(LooseVersion::getMinor).thenComparing(LooseVersion::getPatch).compare(this, o);
    }
}
