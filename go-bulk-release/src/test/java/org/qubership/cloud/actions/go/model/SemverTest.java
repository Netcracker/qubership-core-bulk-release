package org.qubership.cloud.actions.go.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SemverTest {

    @Test
    void parsesValidVersion() {
        Semver s = new Semver("v1.2.3");
        assertEquals(1, s.getMajor());
        assertEquals(2, s.getMinor());
        assertEquals(3, s.getPatch());
        assertEquals("v1.2.3", s.getValue());
        assertEquals("v1.2.3", s.toString());
    }

    @Test
    void parsesZeroVersion() {
        Semver s = new Semver("v0.0.0");
        assertEquals(0, s.getMajor());
        assertEquals(0, s.getMinor());
        assertEquals(0, s.getPatch());
    }

    @Test
    void parsesLargeVersion() {
        Semver s = new Semver("v100.200.300");
        assertEquals(100, s.getMajor());
        assertEquals(200, s.getMinor());
        assertEquals(300, s.getPatch());
    }

    @Test
    void rejectsVersionWithoutVPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new Semver("1.2.3"));
    }

    @Test
    void rejectsTwoPartVersion() {
        assertThrows(IllegalArgumentException.class, () -> new Semver("v1.2"));
    }

    @Test
    void rejectsNonNumericVersion() {
        assertThrows(IllegalArgumentException.class, () -> new Semver("invalid"));
    }
}
