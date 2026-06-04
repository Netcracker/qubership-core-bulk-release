package org.qubership.cloud.actions.go.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GoGAVTest {

    @Test
    void moduleWithoutVersionSuffix() {
        GoGAV gav = new GoGAV("github.com/org/repo", "v1.0.0");
        assertEquals("github.com/org/repo", gav.getArtifactId());
        assertEquals("github.com/org/repo", gav.getArtifactIdWithoutVersion());
        assertEquals(1, gav.getMajorVersionFromArtifactId());
        assertEquals("v1.0.0", gav.getVersion());
    }

    @Test
    void moduleWithV2Suffix() {
        GoGAV gav = new GoGAV("github.com/org/repo/v2", "v2.1.0");
        assertEquals("github.com/org/repo", gav.getArtifactIdWithoutVersion());
        assertEquals(2, gav.getMajorVersionFromArtifactId());
    }

    @Test
    void moduleWithV10Suffix() {
        GoGAV gav = new GoGAV("github.com/org/repo/v10", "v10.0.5");
        assertEquals("github.com/org/repo", gav.getArtifactIdWithoutVersion());
        assertEquals(10, gav.getMajorVersionFromArtifactId());
    }

    @Test
    void isSameArtifactMatchesAcrossMajorVersions() {
        GoGAV v1 = new GoGAV("github.com/org/repo", "v1.0.0");
        GoGAV v2 = new GoGAV("github.com/org/repo/v2", "v2.0.0");
        assertTrue(v1.isSameArtifact(v2));
        assertTrue(v2.isSameArtifact(v1));
    }

    @Test
    void isSameArtifactFalseForDifferentRepos() {
        GoGAV a = new GoGAV("github.com/org/repo-a", "v1.0.0");
        GoGAV b = new GoGAV("github.com/org/repo-b", "v1.0.0");
        assertFalse(a.isSameArtifact(b));
    }

    @Test
    void isSameArtifactTrueForSameModuleIdenticalVersion() {
        GoGAV a = new GoGAV("github.com/org/repo", "v1.2.3");
        GoGAV b = new GoGAV("github.com/org/repo", "v1.9.0");
        assertTrue(a.isSameArtifact(b));
    }

    @Test
    void toStringFormat() {
        GoGAV gav = new GoGAV("github.com/org/repo", "v1.0.0");
        assertEquals("github.com/org/repo:v1.0.0", gav.toString());
    }

    @Test
    void updatedVersionStrFormat() {
        GoGAV gav = new GoGAV("github.com/org/repo", "v1.0.0", "v1.1.0");
        assertEquals("github.com/org/repo:v1.0.0 -> v1.1.0", gav.getUpdatedVersionStr());
    }

    @Test
    void isSameArtifactFalseForNonGoGavWithDifferentArtifactId() {
        GoGAV goGav = new GoGAV("github.com/org/repo", "v1.0.0");
        GAV gav = new GAV("GO", "github.com/org/other", "v1.0.0");
        assertFalse(goGav.isSameArtifact(gav));
    }

    @Test
    void isSameArtifactTrueForNonGoGavWithSameGroupAndArtifactId() {
        GoGAV goGav = new GoGAV("github.com/org/repo", "v1.0.0");
        GAV gav = new GAV("GO", "github.com/org/repo", "v1.0.0");
        assertTrue(goGav.isSameArtifact(gav));
    }
}
