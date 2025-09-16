package org.qubership.cloud.actions.go.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@EqualsAndHashCode(callSuper = true)
@Data
public class GAV extends GA implements Comparable<GAV> {

    String version;

    public GAV(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    @Override
    public String toString() {
        return String.format("%s:%s:%s", groupId, artifactId, version);
    }

    @Override
    public int compareTo(GAV o) {
        return Comparator.comparing(GA::getGroupId).thenComparing(GA::getArtifactId).compare(this, o);
    }

    public boolean isSameArtifact(GAV another) {
        return Objects.equals(artifactId, another.artifactId) && Objects.equals(groupId, another.groupId);
    }
}