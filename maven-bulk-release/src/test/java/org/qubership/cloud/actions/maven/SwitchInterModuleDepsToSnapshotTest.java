package org.qubership.cloud.actions.maven;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.cloud.actions.maven.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SwitchInterModuleDepsToSnapshotTest {

    static final String PRODUCER_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example.test</groupId>
                <artifactId>producer-lib</artifactId>
                <version>1.0.1-SNAPSHOT</version>
            </project>""";

    static final String CONSUMER_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example.test</groupId>
                <artifactId>consumer-lib</artifactId>
                <version>2.0.1-SNAPSHOT</version>
                <properties>
                    <test.producer-lib.version>1.0.0</test.producer-lib.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.example.test</groupId>
                        <artifactId>producer-lib</artifactId>
                        <version>${test.producer-lib.version}</version>
                    </dependency>
                </dependencies>
            </project>""";

    static RepositoryInfo repoInfo(Path baseDir, String pomFolder) {
        RepositoryConfig cfg = RepositoryConfig.builder("https://github.com/test/mono")
                .branch("main").pomFolder(pomFolder).build();
        return new RepositoryInfo(cfg, baseDir.toString());
    }

    static RepositoryRelease release(RepositoryInfo ri, String releasedVersion, String devVersion) {
        RepositoryRelease r = new RepositoryRelease();
        r.setRepository(ri);
        r.setVersionTag(new VersionTag(releasedVersion, releasedVersion));
        String ga = ri.getBaseModule().getGroupId() + ":" + ri.getBaseModule().getArtifactId();
        r.setGavs(List.of(new GAV(ga + ":" + releasedVersion)));
        r.setDevGavs(List.of(new GAV(ga + ":" + devVersion)));
        return r;
    }

    @Test
    void rewritesInterModulePropertyToSnapshotAndCommits(@TempDir Path baseDir) throws Exception {
        Path repoRoot = baseDir.resolve("test/mono");
        Files.createDirectories(repoRoot.resolve("producer"));
        Files.createDirectories(repoRoot.resolve("consumer"));
        Files.writeString(repoRoot.resolve("producer/pom.xml"), PRODUCER_POM);
        Files.writeString(repoRoot.resolve("consumer/pom.xml"), CONSUMER_POM);

        try (Git git = Git.init().setInitialBranch("main").setDirectory(repoRoot.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").call();

            RepositoryInfo producer = repoInfo(baseDir, "producer");
            RepositoryInfo consumer = repoInfo(baseDir, "consumer");

            Config config = ReleaseRunnerSnapshotTest.config("main", true, false, false);
            List<RepositoryRelease> allReleases = List.of(
                    release(producer, "1.0.0", "1.0.1-SNAPSHOT"),
                    release(consumer, "2.0.0", "2.0.1-SNAPSHOT"));

            new ReleaseRunner().switchInterModuleDepsToSnapshot(config, allReleases);

            String consumerPom = Files.readString(repoRoot.resolve("consumer/pom.xml"));
            Assertions.assertTrue(consumerPom.contains("<test.producer-lib.version>1.0.1-SNAPSHOT</test.producer-lib.version>"),
                    "consumer property must point to producer's SNAPSHOT dev version");

            String producerPom = Files.readString(repoRoot.resolve("producer/pom.xml"));
            Assertions.assertTrue(producerPom.contains("<version>1.0.1-SNAPSHOT</version>"),
                    "producer's own module version must stay untouched");

            String lastMessage = git.log().setMaxCount(1).call().iterator().next().getShortMessage();
            Assertions.assertEquals("switch inter-module deps to SNAPSHOT after release", lastMessage);
        }
    }
}
