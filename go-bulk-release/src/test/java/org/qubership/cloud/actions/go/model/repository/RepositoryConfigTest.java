package org.qubership.cloud.actions.go.model.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RepositoryConfigTest {

    @Test
    void parsesUrlWithoutParams() {
        RepositoryConfig config = RepositoryConfig.fromConfig("https://github.com/org/repo");
        assertEquals("https://github.com/org/repo", config.getUrl());
        assertEquals("org/repo", config.getDir());
        assertEquals("HEAD", config.getBranch());
        assertNull(config.getVersion());
        assertFalse(config.isSkipTests());
    }

    @Test
    void parsesUrlWithBranchParam() {
        RepositoryConfig config = RepositoryConfig.fromConfig("https://github.com/org/repo[branch=main]");
        assertEquals("main", config.getBranch());
    }

    @Test
    void parsesUrlWithFromAlias() {
        RepositoryConfig config = RepositoryConfig.fromConfig("https://github.com/org/repo[from=develop]");
        assertEquals("develop", config.getBranch());
    }

    @Test
    void parsesUrlWithVersionParam() {
        RepositoryConfig config = RepositoryConfig.fromConfig("https://github.com/org/repo[version=v2.0.0]");
        assertEquals("v2.0.0", config.getVersion());
    }

    @Test
    void parsesUrlWithSkipTestsParam() {
        RepositoryConfig config = RepositoryConfig.fromConfig("https://github.com/org/repo[skipTests=true]");
        assertTrue(config.isSkipTests());
    }

    @Test
    void parsesUrlWithAllParams() {
        RepositoryConfig config = RepositoryConfig.fromConfig(
                "https://github.com/org/repo[branch=feature,version=v1.5.0,skipTests=true]");
        assertEquals("feature", config.getBranch());
        assertEquals("v1.5.0", config.getVersion());
        assertTrue(config.isSkipTests());
    }

    @Test
    void normalizesGitSuffix() {
        RepositoryConfig config = RepositoryConfig.fromConfig("https://github.com/org/repo.git");
        assertEquals("https://github.com/org/repo", config.getUrl());
    }

    @Test
    void defaultBranchIsHeadWhenNotSpecified() {
        RepositoryConfig config = RepositoryConfig.fromConfig("https://github.com/org/repo[version=v1.0.0]");
        assertEquals("HEAD", config.getBranch());
    }

    @Test
    void throwsOnInvalidUrl() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryConfig.fromConfig("not-a-url"));
    }

    @Test
    void branchParamTakesPriorityOverFromAlias() {
        RepositoryConfig config = RepositoryConfig.fromConfig(
                "https://github.com/org/repo[branch=main,from=develop]");
        assertEquals("main", config.getBranch());
    }

    @Test
    void paramValueWithEmbeddedEqualSignIsDropped() {
        RepositoryConfig config = RepositoryConfig.fromConfig(
                "https://github.com/org/repo[version=v1.0.0=extra]");
        assertNull(config.getVersion());
    }
}
