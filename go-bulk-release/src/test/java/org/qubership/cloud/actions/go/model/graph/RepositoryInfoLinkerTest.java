package org.qubership.cloud.actions.go.model.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class RepositoryInfoLinkerTest {

    private static RepositoryInfo createRepo(Path baseDir, String orgAndName, String module, String... requires) throws IOException {
        Path repoDir = baseDir.resolve(orgAndName);
        Files.createDirectories(repoDir);
        StringBuilder goMod = new StringBuilder("module ").append(module).append("\n");
        if (requires.length > 0) {
            goMod.append("\nrequire (\n");
            for (String req : requires) {
                goMod.append("    ").append(req).append("\n");
            }
            goMod.append(")\n");
        }
        Files.writeString(repoDir.resolve("go.mod"), goMod.toString());
        RepositoryConfig config = RepositoryConfig.fromConfig("https://host/" + orgAndName);
        return new RepositoryInfo(config, baseDir.toString());
    }

    @Test
    void reposThatDependOnEachOther(@TempDir Path tempDir) throws IOException {
        RepositoryInfo repoA = createRepo(tempDir, "org/repo-a", "github.com/org/repo-a");
        RepositoryInfo repoB = createRepo(tempDir, "org/repo-b", "github.com/org/repo-b",
                "github.com/org/repo-a v1.0.0");

        RepositoryInfoLinker linker = new RepositoryInfoLinker(List.of(repoA, repoB));

        List<RepositoryInfo> usedByB = linker.getRepositoriesUsedByThis(repoB);
        assertEquals(1, usedByB.size());
        assertSame(repoA, usedByB.get(0));
    }

    @Test
    void repoWithNoDepsReturnsEmptyList(@TempDir Path tempDir) throws IOException {
        RepositoryInfo repoA = createRepo(tempDir, "org/repo-a", "github.com/org/repo-a");
        RepositoryInfo repoB = createRepo(tempDir, "org/repo-b", "github.com/org/repo-b",
                "github.com/org/repo-a v1.0.0");

        RepositoryInfoLinker linker = new RepositoryInfoLinker(List.of(repoA, repoB));

        List<RepositoryInfo> usedByA = linker.getRepositoriesUsedByThis(repoA);
        assertTrue(usedByA.isEmpty());
    }

    @Test
    void getRepositoriesUsingThis(@TempDir Path tempDir) throws IOException {
        RepositoryInfo repoA = createRepo(tempDir, "org/repo-a", "github.com/org/repo-a");
        RepositoryInfo repoB = createRepo(tempDir, "org/repo-b", "github.com/org/repo-b",
                "github.com/org/repo-a v1.0.0");

        RepositoryInfoLinker linker = new RepositoryInfoLinker(List.of(repoA, repoB));

        List<RepositoryInfo> usingA = linker.getRepositoriesUsingThis(repoA);
        assertEquals(1, usingA.size());
        assertSame(repoB, usingA.get(0));
    }

    @Test
    void matchesMajorVersionSuffixInDependency(@TempDir Path tempDir) throws IOException {
        RepositoryInfo repoA = createRepo(tempDir, "org/repo-a", "github.com/org/repo-a");
        RepositoryInfo repoC = createRepo(tempDir, "org/repo-c", "github.com/org/repo-c",
                "github.com/org/repo-a/v2 v2.0.0");

        RepositoryInfoLinker linker = new RepositoryInfoLinker(List.of(repoA, repoC));

        List<RepositoryInfo> usedByC = linker.getRepositoriesUsedByThis(repoC);
        assertEquals(1, usedByC.size());
        assertSame(repoA, usedByC.get(0));
    }

    @Test
    void transitiveDependenciesIncludedInFlatSet(@TempDir Path tempDir) throws IOException {
        RepositoryInfo repoA = createRepo(tempDir, "org/repo-a", "github.com/org/repo-a");
        RepositoryInfo repoB = createRepo(tempDir, "org/repo-b", "github.com/org/repo-b",
                "github.com/org/repo-a v1.0.0");
        RepositoryInfo repoC = createRepo(tempDir, "org/repo-c", "github.com/org/repo-c",
                "github.com/org/repo-b v1.0.0");

        RepositoryInfoLinker linker = new RepositoryInfoLinker(List.of(repoA, repoB, repoC));

        Set<RepositoryInfo> flatSet = linker.getRepositoriesUsedByThisFlatSet(repoC);
        assertEquals(2, flatSet.size());
        assertTrue(flatSet.stream().anyMatch(r -> r.getUrl().equals(repoA.getUrl())));
        assertTrue(flatSet.stream().anyMatch(r -> r.getUrl().equals(repoB.getUrl())));
    }

    @Test
    void noSelfReferenceReturned(@TempDir Path tempDir) throws IOException {
        RepositoryInfo repoA = createRepo(tempDir, "org/repo-a", "github.com/org/repo-a");

        RepositoryInfoLinker linker = new RepositoryInfoLinker(List.of(repoA));

        assertTrue(linker.getRepositoriesUsedByThis(repoA).isEmpty());
        assertTrue(linker.getRepositoriesUsingThis(repoA).isEmpty());
    }
}
