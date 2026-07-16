package org.qubership.cloud.actions.maven;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.actions.maven.model.Config;
import org.qubership.cloud.actions.maven.model.GitConfig;
import org.qubership.cloud.actions.maven.model.MavenConfig;
import org.qubership.cloud.actions.maven.model.RepositoryConfig;

import java.util.List;

public class ReleaseRunnerSnapshotTest {

    static Config config(String branch, boolean flag, boolean dryRun, boolean partial) {
        RepositoryConfig repo = RepositoryConfig.builder("https://github.com/test/mono").branch(branch).build();
        Config.ConfigBuilder builder = Config.builder("/tmp",
                        GitConfig.builder().url("https://github.com").username("u").email("e").password("p").build(),
                        MavenConfig.builder().build(),
                        List.of(repo))
                .dryRun(dryRun)
                .switchInterModuleDepsToSnapshot(flag);
        if (partial) {
            builder.repositoriesToReleaseFrom(java.util.Set.of(repo));
        }
        return builder.build();
    }

    @Test
    void gateOnlyWhenFlagMainAndNotDryRun() {
        ReleaseRunner runner = new ReleaseRunner();
        Assertions.assertTrue(runner.shouldSwitchInterModuleDepsToSnapshot(config("main", true, false, false), "main"));
        Assertions.assertFalse(runner.shouldSwitchInterModuleDepsToSnapshot(config("main", false, false, false), "main"));
        Assertions.assertFalse(runner.shouldSwitchInterModuleDepsToSnapshot(config("main", true, true, false), "main"));
        Assertions.assertFalse(runner.shouldSwitchInterModuleDepsToSnapshot(config("lts/1.2.3", true, false, false), "lts/1.2.3"));
    }

    @Test
    void guardBlocksPartialReleaseFromMainWithFlag() {
        ReleaseRunner runner = new ReleaseRunner();
        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> runner.assertPartialReleaseAllowed(config("main", true, false, true)));
        Assertions.assertTrue(ex.getMessage().contains("partial release is not allowed from main"));
    }

    @Test
    void guardAllowsPartialReleaseWhenServiceCaseOrBranch() {
        ReleaseRunner runner = new ReleaseRunner();
        // services / any run without the flag: partial from main stays allowed
        Assertions.assertDoesNotThrow(() -> runner.assertPartialReleaseAllowed(config("main", false, false, true)));
        // partial from a non-main branch is allowed even with the flag
        Assertions.assertDoesNotThrow(() -> runner.assertPartialReleaseAllowed(config("lts/1.2.3", true, false, true)));
        // full release from main with the flag is allowed
        Assertions.assertDoesNotThrow(() -> runner.assertPartialReleaseAllowed(config("main", true, false, false)));
    }
}
