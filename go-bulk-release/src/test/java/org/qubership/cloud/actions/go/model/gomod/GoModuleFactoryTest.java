package org.qubership.cloud.actions.go.model.gomod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.cloud.actions.go.model.GoGAV;
import org.qubership.cloud.actions.go.model.UnexpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class GoModuleFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesModuleNameAndRequireBlock() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, """
                module github.com/org/repo

                go 1.21

                require (
                    github.com/dep/one v1.2.3
                    github.com/dep/two v0.5.0
                )
                """);

        GoModule module = GoModuleFactory.create(tempDir, goMod);

        assertEquals("github.com/org/repo", module.moduleName());
        assertEquals("", module.relativePath());
        assertFalse(module.isSubModule());
        assertEquals(2, module.dependencies().size());
        assertTrue(module.dependencies().stream()
                .anyMatch(d -> d.getArtifactId().equals("github.com/dep/one") && d.getVersion().equals("v1.2.3")));
        assertTrue(module.dependencies().stream()
                .anyMatch(d -> d.getArtifactId().equals("github.com/dep/two") && d.getVersion().equals("v0.5.0")));
    }

    @Test
    void parsesSingleLineRequire() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, """
                module github.com/org/repo

                require github.com/dep/single v3.1.0
                """);

        GoModule module = GoModuleFactory.create(tempDir, goMod);

        assertEquals(1, module.dependencies().size());
        GoGAV dep = module.dependencies().iterator().next();
        assertEquals("github.com/dep/single", dep.getArtifactId());
        assertEquals("v3.1.0", dep.getVersion());
    }

    @Test
    void stripsInlineCommentFromDependency() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, """
                module github.com/org/repo

                require (
                    github.com/dep/lib v2.0.0 // indirect
                )
                """);

        GoModule module = GoModuleFactory.create(tempDir, goMod);

        assertEquals(1, module.dependencies().size());
        GoGAV dep = module.dependencies().iterator().next();
        assertEquals("github.com/dep/lib", dep.getArtifactId());
        assertEquals("v2.0.0", dep.getVersion());
    }

    @Test
    void skipsCommentLines() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, """
                module github.com/org/repo

                // this is a top-level comment
                require (
                    // another comment inside block
                    github.com/dep/lib v1.0.0
                )
                """);

        GoModule module = GoModuleFactory.create(tempDir, goMod);

        assertEquals(1, module.dependencies().size());
    }

    @Test
    void parsesSubmoduleRelativePath() throws IOException {
        Path subDir = tempDir.resolve("submodule");
        Files.createDirectories(subDir);
        Path goMod = subDir.resolve("go.mod");
        Files.writeString(goMod, "module github.com/org/repo/submodule\n");

        GoModule module = GoModuleFactory.create(tempDir, goMod);

        assertEquals("submodule", module.relativePath());
        assertTrue(module.isSubModule());
    }

    @Test
    void throwsWhenModuleNameMissing() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, """
                go 1.21

                require (
                    github.com/dep/lib v1.0.0
                )
                """);

        assertThrows(UnexpectedException.class, () -> GoModuleFactory.create(tempDir, goMod));
    }

    @Test
    void parsesModuleWithNoDependencies() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, "module github.com/org/repo\n");

        GoModule module = GoModuleFactory.create(tempDir, goMod);

        assertEquals("github.com/org/repo", module.moduleName());
        assertTrue(module.dependencies().isEmpty());
    }

    @Test
    void parsesMultipleSingleLineRequires() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, """
                module github.com/org/repo

                require github.com/dep/a v1.0.0
                require github.com/dep/b v2.0.0
                """);

        GoModule module = GoModuleFactory.create(tempDir, goMod);

        assertEquals(2, module.dependencies().size());
    }

    @Test
    void throwsOnDependencyLineWithNoVersion() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, """
                module github.com/org/repo

                require (
                    github.com/dep/no-version
                )
                """);

        assertThrows(UnexpectedException.class, () -> GoModuleFactory.create(tempDir, goMod));
    }

    @Test
    void unclosedRequireBlockParsesSubsequentLinesAsGhostDependencies() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, "module github.com/org/repo\n\nrequire (\n    github.com/dep/lib v1.0.0\n\ngo 1.21\n");

        GoModule module = GoModuleFactory.create(tempDir, goMod);

        assertTrue(module.dependencies().stream().anyMatch(d -> d.getArtifactId().equals("go")),
                "unclosed require block leaks subsequent lines as ghost dependencies");
    }
}
