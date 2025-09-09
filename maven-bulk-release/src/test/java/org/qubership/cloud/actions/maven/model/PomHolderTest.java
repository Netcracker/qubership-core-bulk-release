package org.qubership.cloud.actions.maven.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class PomHolderTest {

    @Test
    void testGAVPatterns() {
        // language=xml
        PomHolder pomHolder = new PomHolder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.1-SNAPSHOT</version>
                
                    <properties>
                        <quarkus.version>3.15.6</quarkus.version>
                    </properties>
                
                    <dependencies>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-1</artifactId>
                            <version>1.0.1<!--'comment'--></version>
                        </dependency>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-2</artifactId>
                            <version><!--'comment'-->1.0.2</version>
                        </dependency>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-3</artifactId>
                            <!--'comment'-->
                            <version>1.0.3</version>
                        </dependency>
                        <dependency>
                
                            <groupId>group-1</groupId>
                            <!--'comment'-->
                            <!--'comment'-->
                            <artifactId><!--'comment'-->artifact-4<!--'comment' $1--></artifactId>
                            <!--'comment'-->
                            <version>1.0.4</version>
                
                        </dependency>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-5</artifactId>
                            <classifier>classifier</classifier>
                            <version>1.0.5</version>
                        </dependency>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-6</artifactId>
                            <scope>test</scope>
                            <version>1.0.6</version>
                        </dependency>
                        <dependency>
                
                            <artifactId>artifact-7</artifactId>
                
                            <groupId>group-1</groupId>
                
                            <scope>test</scope>
                
                            <version>1.0.7</version>
                
                        </dependency>
                    </dependencies>
                
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>io.quarkus</groupId>
                                <artifactId>quarkus-extension-maven-plugin</artifactId>
                                <version>${quarkus.version}</version>
                                <executions>
                                    <execution>
                                        <goals>
                                            <goal>extension-descriptor</goal>
                                        </goals>
                                        <phase>compile</phase>
                                        <configuration>
                                            <deployment>${project.groupId}:${project.artifactId}-deployment:${project.version}
                                            </deployment>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <annotationProcessorPaths>
                                        <path>
                                            <groupId>io.quarkus</groupId>
                                            <artifactId>quarkus-extension-processor</artifactId>
                                            <version>${quarkus.version}</version>
                                        </path>
                                    </annotationProcessorPaths>
                                    <compilerArgs>
                                        <arg>-AlegacyConfigRoot=true</arg>
                                    </compilerArgs>
                                </configuration>
                                <version>3.12.1</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>""", Path.of("test"));
        Set<GAV> gavs = pomHolder.getGAVs();
        Set<GAV> expectedGavs = Set.of(
                new GAV("group-1:artifact-1:1.0.1"),
                new GAV("group-1:artifact-2:1.0.2"),
                new GAV("group-1:artifact-3:1.0.3"),
                new GAV("group-1:artifact-4:1.0.4"),
                new GAV("group-1:artifact-5:1.0.5"),
                new GAV("group-1:artifact-6:1.0.6"),
                new GAV("group-1:artifact-7:1.0.7"),
                new GAV("io.quarkus:quarkus-extension-processor:${quarkus.version}"),
                new GAV("io.quarkus:quarkus-extension-maven-plugin:${quarkus.version}"),
                new GAV("org.apache.maven.plugins:maven-compiler-plugin:3.12.1")
        );
        Assertions.assertTrue(gavs.containsAll(expectedGavs));

        Set<GAV> expectedUpdatedGavs = new HashSet<>();
        expectedGavs.forEach(gav -> {
            String version = gav.getVersion();
            if (!version.matches("\\d+\\.\\d+\\.\\d+.*")) {
                return;
            }
            MavenVersion mavenVersion = new MavenVersion(version);
            mavenVersion.update(VersionIncrementType.MINOR, mavenVersion.getMinor() + 1);
            GAV updatedGav = new GAV(gav.getGroupId(), gav.getArtifactId(), mavenVersion.toString());
            expectedUpdatedGavs.add(updatedGav);
            pomHolder.updateVersionInGAV(updatedGav);
        });
        gavs = pomHolder.getGAVs();
        Assertions.assertTrue(gavs.containsAll(expectedUpdatedGavs));
        // language=xml
        Assertions.assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.1-SNAPSHOT</version>
                
                    <properties>
                        <quarkus.version>3.15.6</quarkus.version>
                    </properties>
                
                    <dependencies>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-1</artifactId>
                            <version>1.1.1<!--'comment'--></version>
                        </dependency>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-2</artifactId>
                            <version><!--'comment'-->1.1.2</version>
                        </dependency>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-3</artifactId>
                            <!--'comment'-->
                            <version>1.1.3</version>
                        </dependency>
                        <dependency>
                
                            <groupId>group-1</groupId>
                            <!--'comment'-->
                            <!--'comment'-->
                            <artifactId><!--'comment'-->artifact-4<!--'comment' $1--></artifactId>
                            <!--'comment'-->
                            <version>1.1.4</version>
                
                        </dependency>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-5</artifactId>
                            <classifier>classifier</classifier>
                            <version>1.1.5</version>
                        </dependency>
                        <dependency>
                            <groupId>group-1</groupId>
                            <artifactId>artifact-6</artifactId>
                            <scope>test</scope>
                            <version>1.1.6</version>
                        </dependency>
                        <dependency>
                
                            <artifactId>artifact-7</artifactId>
                
                            <groupId>group-1</groupId>
                
                            <scope>test</scope>
                
                            <version>1.1.7</version>
                
                        </dependency>
                    </dependencies>
                
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>io.quarkus</groupId>
                                <artifactId>quarkus-extension-maven-plugin</artifactId>
                                <version>${quarkus.version}</version>
                                <executions>
                                    <execution>
                                        <goals>
                                            <goal>extension-descriptor</goal>
                                        </goals>
                                        <phase>compile</phase>
                                        <configuration>
                                            <deployment>${project.groupId}:${project.artifactId}-deployment:${project.version}
                                            </deployment>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <annotationProcessorPaths>
                                        <path>
                                            <groupId>io.quarkus</groupId>
                                            <artifactId>quarkus-extension-processor</artifactId>
                                            <version>${quarkus.version}</version>
                                        </path>
                                    </annotationProcessorPaths>
                                    <compilerArgs>
                                        <arg>-AlegacyConfigRoot=true</arg>
                                    </compilerArgs>
                                </configuration>
                                <version>3.13.1</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>""", pomHolder.getPom());
    }
}
