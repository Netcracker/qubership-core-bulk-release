package org.qubership.cloud.actions.go.publish;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.cloud.actions.go.model.GoGAV;
import org.qubership.cloud.actions.go.model.Result;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;
import org.qubership.cloud.actions.go.model.repository.RepositoryRelease;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ReleaseSummaryTest {

    @TempDir
    Path tempDir;

    private RepositoryRelease createRelease(String orgAndName, String branch, String tag, boolean pushedToGit) throws IOException {
        Path repoDir = tempDir.resolve(orgAndName);
        Files.createDirectories(repoDir);
        Files.writeString(repoDir.resolve("go.mod"), "module github.com/" + orgAndName + "\n");
        String configUrl = branch.equals("HEAD")
                ? "https://host/" + orgAndName
                : "https://host/" + orgAndName + "[branch=" + branch + "]";
        RepositoryConfig config = RepositoryConfig.fromConfig(configUrl);
        RepositoryInfo repoInfo = new RepositoryInfo(config, tempDir.toString());

        RepositoryRelease release = new RepositoryRelease();
        release.setRepository(repoInfo);
        release.setTag(tag);
        release.setPushedToGit(pushedToGit);
        release.setGavs(List.of(new GoGAV("github.com/" + orgAndName, "v0.9.0", "v1.0.0")));
        return release;
    }

    // --- gavs() ---

    @Test
    void gavsJoinsAllGavsWithComma() {
        RepositoryRelease r1 = new RepositoryRelease();
        r1.setGavs(List.of(new GoGAV("github.com/org/repo-a", "v1.0.0")));
        RepositoryRelease r2 = new RepositoryRelease();
        r2.setGavs(List.of(new GoGAV("github.com/org/repo-b", "v2.1.3")));

        Result result = new Result();
        result.setReleases(List.of(r1, r2));

        assertEquals("github.com/org/repo-a:v1.0.0,github.com/org/repo-b:v2.1.3", ReleaseSummary.gavs(result));
    }

    @Test
    void gavsHandlesMultipleGavsPerRelease() {
        RepositoryRelease r = new RepositoryRelease();
        r.setGavs(List.of(
                new GoGAV("github.com/org/mod-a", "v3.0.0"),
                new GoGAV("github.com/org/mod-b/v2", "v2.5.0")
        ));

        Result result = new Result();
        result.setReleases(List.of(r));

        String gavs = ReleaseSummary.gavs(result);
        assertTrue(gavs.contains("github.com/org/mod-a:v3.0.0"));
        assertTrue(gavs.contains("github.com/org/mod-b/v2:v2.5.0"));
    }

    @Test
    void gavsReturnsEmptyStringForNoReleases() {
        Result result = new Result();
        result.setReleases(List.of());

        assertEquals("", ReleaseSummary.gavs(result));
    }

    // --- md() ---

    @Test
    void mdContainsDryRunAnnotation() throws IOException {
        RepositoryRelease release = createRelease("org/repo", "HEAD", "v1.0.0", false);
        Result result = new Result();
        result.setReleases(List.of(release));
        result.setDryRun(true);

        assertTrue(ReleaseSummary.md(result).contains("[DRY RUN]"));
    }

    @Test
    void mdOmitsDryRunAnnotationWhenNotDryRun() throws IOException {
        RepositoryRelease release = createRelease("org/repo", "HEAD", "v1.0.0", false);
        Result result = new Result();
        result.setReleases(List.of(release));
        result.setDryRun(false);

        assertFalse(ReleaseSummary.md(result).contains("[DRY RUN]"));
    }

    @Test
    void mdUsesTagUrlWhenPushedToGit() throws IOException {
        RepositoryRelease release = createRelease("org/repo", "HEAD", "v1.2.3", true);
        Result result = new Result();
        result.setReleases(List.of(release));

        String md = ReleaseSummary.md(result);
        assertTrue(md.contains("https://host/org/repo/releases/tag/v1.2.3"));
    }

    @Test
    void mdUsesRepoUrlWhenNotPushedToGit() throws IOException {
        RepositoryRelease release = createRelease("org/repo", "HEAD", "v1.2.3", false);
        Result result = new Result();
        result.setReleases(List.of(release));

        String md = ReleaseSummary.md(result);
        assertFalse(md.contains("/releases/tag/"));
        assertTrue(md.contains("https://host/org/repo"));
    }

    @Test
    void mdIncludesBranchNameWhenNotHead() throws IOException {
        RepositoryRelease release = createRelease("org/repo", "lts/25.1", "v1.0.0", false);
        Result result = new Result();
        result.setReleases(List.of(release));

        assertTrue(ReleaseSummary.md(result).contains("[lts/25.1]"));
    }

    @Test
    void mdOmitsBranchNameForHeadBranch() throws IOException {
        RepositoryRelease release = createRelease("org/repo", "HEAD", "v1.0.0", false);
        Result result = new Result();
        result.setReleases(List.of(release));

        assertFalse(ReleaseSummary.md(result).contains("[HEAD]"));
    }

    @Test
    void mdIncludesLtsSummaryWhenLtsRelease() throws IOException {
        RepositoryRelease release = createRelease("org/repo", "HEAD", "v1.0.0", true);
        Result result = new Result();
        result.setReleases(List.of(release));
        result.setLtsRelease(true);
        result.setLtsBranchName("lts/25.2");

        String md = ReleaseSummary.md(result);
        assertTrue(md.contains("### LTS Steps"));
        assertTrue(md.contains("lts/25.2"));
    }

    @Test
    void mdOmitsLtsSummaryWhenNotLtsRelease() throws IOException {
        RepositoryRelease release = createRelease("org/repo", "HEAD", "v1.0.0", true);
        Result result = new Result();
        result.setReleases(List.of(release));
        result.setLtsRelease(false);

        assertFalse(ReleaseSummary.md(result).contains("### LTS Steps"));
    }
}
