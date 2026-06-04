package org.qubership.cloud.actions.go.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UrlUtilsTest {

    @Test
    void extractsDirFromSimpleUrl() {
        assertEquals("org/repo", UrlUtils.getDirName("https://github.com/org/repo"));
    }

    @Test
    void extractsDirFromUrlWithParams() {
        assertEquals("org/repo", UrlUtils.getDirName("https://github.com/org/repo[branch=main]"));
    }

    @Test
    void extractsDirFromUrlWithMultiplePathSegments() {
        assertEquals("org/sub/repo", UrlUtils.getDirName("https://github.com/org/sub/repo"));
    }

    @Test
    void throwsOnInvalidUrl() {
        assertThrows(IllegalArgumentException.class, () -> UrlUtils.getDirName("not-a-url"));
    }

    @Test
    void throwsOnRelativeUrl() {
        assertThrows(IllegalArgumentException.class, () -> UrlUtils.getDirName("org/repo"));
    }
}
