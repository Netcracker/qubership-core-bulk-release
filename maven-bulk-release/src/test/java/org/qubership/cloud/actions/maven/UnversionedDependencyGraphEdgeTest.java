package org.qubership.cloud.actions.maven;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.cloud.actions.maven.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class UnversionedDependencyGraphEdgeTest {

    static final String PRODUCER_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example.test</groupId>
                <artifactId>producer-lib</artifactId>
                <version>1.0.1-SNAPSHOT</version>
            </project>""";

    // the version of producer-lib comes from a BOM, so the consumer pom itself carries none
    static final String CONSUMER_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example.consumer</groupId>
                <artifactId>consumer-lib</artifactId>
                <version>2.0.1-SNAPSHOT</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example.bom</groupId>
                            <artifactId>example-bom</artifactId>
                            <version>3.0.1-SNAPSHOT</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>com.example.test</groupId>
                        <artifactId>producer-lib</artifactId>
                    </dependency>
                </dependencies>
            </project>""";

    static RepositoryInfo repoInfo(Path baseDir, String pomFolder) {
        RepositoryConfig cfg = RepositoryConfig.builder("https://github.com/test/mono")
                .branch("main").pomFolder(pomFolder).build();
        return new RepositoryInfo(cfg, baseDir.toString());
    }

    @Test
    void keepsGraphEdgeForDependencyWithoutVersion(@TempDir Path baseDir) throws Exception {
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

            GA producerGA = new GA("com.example.test", "producer-lib");
            Assertions.assertTrue(consumer.getModuleDependencyGAs().contains(producerGA),
                    "a dependency without a version must still form a graph edge");
            Assertions.assertTrue(consumer.getModuleDependencies().stream().noneMatch(gav -> gav.toGA().equals(producerGA)),
                    "no version is known for that dependency, so it must not appear among versioned ones");

            GA bomGA = new GA("com.example.bom", "example-bom");
            Assertions.assertTrue(consumer.getModuleDependencyGAs().contains(bomGA),
                    "an imported BOM must form a graph edge as well");

            RepositoryInfoLinker linker = new RepositoryInfoLinker(List.of(producer, consumer));
            Assertions.assertEquals(List.of(producer), linker.getRepositoriesUsedByThis(consumer),
                    "consumer must be linked to producer through the versionless dependency");
            Assertions.assertEquals(List.of(consumer), linker.getRepositoriesUsingThis(producer),
                    "the same link must be visible from the producer side");
        }
    }
}
