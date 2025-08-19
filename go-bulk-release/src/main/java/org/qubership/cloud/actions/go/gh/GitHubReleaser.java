package org.qubership.cloud.actions.go.gh;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;

import java.io.File;
import java.io.IOException;

@Slf4j
public class GitHubReleaser {

    private final GitHub github;

    public GitHubReleaser(String token) throws IOException {
        github = new GitHubBuilder().withOAuthToken(token).build();
    }

    public GHRelease createOrUpdate(File repoDir, String repo, String tag) throws Exception {
        log.debug("VLLA createOrUpdate repo = {}, tag = {}", repo, tag);
        GHRepository r = github.getRepository(repo);

        GHRelease release = null;
        try {
            release = r.getReleaseByTagName(tag); // 404, если нет
        } catch (GHFileNotFoundException e) {
            // ignore
        }

        if (release != null) {
            GHReleaseUpdater upd = release.update();
            upd.name("VLLA test title");
            upd.body("VLLA test body");
            return upd.update();
        } else {
            String prevTag = PrevReleaseTagFinder.findFromTagViaReleases(r, tag, false);
            GHReleaseBuilder b = r.createRelease(tag)
                    .name("VLLA test title")
                    .body("VLLA test body\n" + ConventionalNotes.generate(repoDir, prevTag, tag))
                    .draft(false);
            b.generateReleaseNotes(true);
            return b.create();
        }
    }

    public void uploadAsset(GHRelease release, java.io.File file, String contentType) throws Exception {
        release.uploadAsset(file, contentType);
    }
}
