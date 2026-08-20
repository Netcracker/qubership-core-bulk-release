package org.qubership.cloud.actions.maven;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.cloud.actions.maven.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * End-to-end check that runs a real {@code mvn release:prepare} on a throwaway mini-monorepo and then
 * exercises {@link ReleaseRunner#switchInterModuleDepsToSnapshot}. It verifies that the inter-module
 * dependency is rewritten to the producer's real next development (SNAPSHOT) version taken from the
 * genuine {@code release.properties} (project.dev.*) that maven-release-plugin produced.
 * <p>
 * Heavy (spawns real mvn subprocesses); gated behind {@code -De2e=true} so it does not run in the
 * regular {@code mvn test}.
 */
@EnabledIfSystemProperty(named = "e2e", matches = "true")
public class SwitchInterModuleDepsToSnapshotE2ETest {

    static String producerPom(String scmUrl) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.test</groupId>
                    <artifactId>producer-lib</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <scm>
                        <connection>%s</connection>
                        <developerConnection>%s</developerConnection>
                        <tag>HEAD</tag>
                    </scm>
                </project>""".formatted(scmUrl, scmUrl);
    }

    static String consumerPom(String scmUrl) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.test</groupId>
                    <artifactId>consumer-lib</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <properties>
                        <test.producer-lib.version>1.0.0-SNAPSHOT</test.producer-lib.version>
                    </properties>
                    <scm>
                        <connection>%s</connection>
                        <developerConnection>%s</developerConnection>
                        <tag>HEAD</tag>
                    </scm>
                    <dependencies>
                        <dependency>
                            <groupId>com.example.test</groupId>
                            <artifactId>producer-lib</artifactId>
                            <version>${test.producer-lib.version}</version>
                            <type>pom</type>
                        </dependency>
                    </dependencies>
                </project>""".formatted(scmUrl, scmUrl);
    }

    static RepositoryInfo repoInfo(Path baseDir, String pomFolder) {
        RepositoryConfig cfg = RepositoryConfig.builder("https://localhost/test-mono")
                .branch("main").pomFolder(pomFolder).build();
        return new RepositoryInfo(cfg, baseDir.toString());
    }

    RepositoryRelease prepare(ReleaseRunner runner, Config config, RepositoryInfo repo, Set<GAV> deps) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            return runner.releasePrepare(config, repo, deps, out);
        } catch (Exception e) {
            System.out.println("=== release:prepare output for " + repo.getPomFolder() + " ===");
            System.out.println(out.toString(StandardCharsets.UTF_8));
            throw e;
        }
    }

    @Test
    void realReleasePrepareThenSwitchInterModuleDepsToSnapshot(@TempDir Path tmp) throws Exception {
        Path baseDir = tmp.resolve("base");
        Path repoRoot = baseDir.resolve("test-mono");
        Files.createDirectories(repoRoot.resolve("producer"));
        Files.createDirectories(repoRoot.resolve("consumer"));

        Path bare = tmp.resolve("origin.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).call().close();
        String scmUrl = "scm:git:file:///" + bare.toString().replace('\\', '/');

        Files.writeString(repoRoot.resolve("producer/pom.xml"), producerPom(scmUrl));
        Files.writeString(repoRoot.resolve("consumer/pom.xml"), consumerPom(scmUrl));

        try (Git git = Git.init().setInitialBranch("main").setDirectory(repoRoot.toFile()).call()) {
            StoredConfig gitCfg = git.getRepository().getConfig();
            gitCfg.setString("remote", "origin", "url", bare.toString().replace('\\', '/'));
            gitCfg.setString("user", null, "name", "t");
            gitCfg.setString("user", null, "email", "t@t");
            gitCfg.save();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").call();

            RepositoryInfo producer = repoInfo(baseDir, "producer");
            RepositoryInfo consumer = repoInfo(baseDir, "consumer");

            String localRepo = System.getProperty("user.home") + "/.m2/repository";
            Config config = Config.builder(baseDir.toString(),
                            GitConfig.builder().url("https://localhost").username("t").email("t@t").password("p").build(),
                            MavenConfig.builder().localRepositoryPath(localRepo).deployArtifacts(false).build(),
                            List.of(RepositoryConfig.builder("https://localhost/test-mono").branch("main").build()))
                    .skipTests(true)
                    .dryRun(false)
                    .switchInterModuleDepsToSnapshot(true)
                    .build();

            ReleaseRunner runner = new ReleaseRunner();

            RepositoryRelease producerRelease = prepare(runner, config, producer, Set.of());
            GAV producerReleasedGav = producerRelease.getGavs().stream()
                    .filter(g -> "producer-lib".equals(g.getArtifactId())).findFirst().orElseThrow();
            RepositoryRelease consumerRelease = prepare(runner, config, consumer, Set.of(producerReleasedGav));

            String producerDevVersion = producerRelease.getDevGavs().stream()
                    .filter(g -> "producer-lib".equals(g.getArtifactId())).findFirst().orElseThrow().getVersion();
            Assertions.assertTrue(producerDevVersion.endsWith("-SNAPSHOT"),
                    "producer dev version from release.properties must be a SNAPSHOT, was: " + producerDevVersion);

            runner.switchInterModuleDepsToSnapshot(config, List.of(producerRelease, consumerRelease));

            String consumerPom = Files.readString(repoRoot.resolve("consumer/pom.xml"));
            Assertions.assertTrue(
                    consumerPom.contains("<test.producer-lib.version>" + producerDevVersion + "</test.producer-lib.version>"),
                    "consumer property must point to producer's real dev SNAPSHOT (" + producerDevVersion + "), pom was:\n" + consumerPom);

            String lastMessage = git.log().setMaxCount(1).call().iterator().next().getShortMessage();
            Assertions.assertEquals("switch inter-module deps to SNAPSHOT after release", lastMessage);
        }
    }
}
