package org.qubership.cloud.actions.go.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ReleaseVersionTest {

    @Test
    void majorUpdateDetected() {
        ReleaseVersion rv = new ReleaseVersion("v1.5.0", "v2.0.0");
        assertTrue(rv.isMajorUpdate());
        assertFalse(rv.isPatchOnlyUpdate());
        assertEquals(2, rv.getNewMajorVersion());
    }

    @Test
    void minorUpdateIsNeitherMajorNorPatchOnly() {
        ReleaseVersion rv = new ReleaseVersion("v1.2.3", "v1.3.0");
        assertFalse(rv.isMajorUpdate());
        assertFalse(rv.isPatchOnlyUpdate());
        assertEquals(1, rv.getNewMajorVersion());
    }

    @Test
    void patchUpdateIsPatchOnly() {
        ReleaseVersion rv = new ReleaseVersion("v1.2.3", "v1.2.4");
        assertFalse(rv.isMajorUpdate());
        assertTrue(rv.isPatchOnlyUpdate());
        assertEquals(1, rv.getNewMajorVersion());
    }

    @Test
    void sameVersionIsPatchOnly() {
        ReleaseVersion rv = new ReleaseVersion("v2.0.0", "v2.0.0");
        assertFalse(rv.isMajorUpdate());
        assertTrue(rv.isPatchOnlyUpdate());
        assertEquals(2, rv.getNewMajorVersion());
    }

    @Test
    void highMajorVersionPreserved() {
        ReleaseVersion rv = new ReleaseVersion("v5.0.0", "v6.0.0");
        assertTrue(rv.isMajorUpdate());
        assertEquals(6, rv.getNewMajorVersion());
    }
}
