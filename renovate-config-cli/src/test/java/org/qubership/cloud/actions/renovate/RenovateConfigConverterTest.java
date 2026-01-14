package org.qubership.cloud.actions.renovate;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.actions.renovate.model.RenovateConfigOutputFormat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RenovateConfigConverterTest {

    @Test
    public void testCli() throws Exception {
        Path repositoriesFile = Files.createTempFile("repositories", ".txt");
        repositoriesFile.toFile().deleteOnExit();
        Files.writeString(repositoriesFile, """
                https://github.com/Netcracker/qubership-core-release-test-maven-lib-1[branch=release/support-1.x.x,branchPrefix=renovate-1/,branchPrefixOld=renovate-1-old/]
                https://github.com/Netcracker/qubership-core-release-test-maven-lib-2[branch=release/support-2.x.x,branchPrefixSuffix=maven-lib-2/,addLabels=label1;label-2]
                https://github.com/Netcracker/qubership-core-release-test-maven-lib-3[branch=release/support-3.x.x,branchPrefix=renovate-3/,branchPrefixSuffix=maven-lib-3/]
                """);

        Path patchGavsFile = Files.createTempFile("patchGavs", ".txt");
        patchGavsFile.toFile().deleteOnExit();
        Files.writeString(patchGavsFile, """
                org.springframework.boot:spring-boot-dependencies:3.4.8
                org.springframework.boot:spring-boot-maven-plugin:3.4.8
                org.springframework.boot:spring-boot-starter-parent:3.4.8
                org.springframework.cloud:spring-cloud-dependencies:2024.0.2
                org.springframework.boot:spring-boot-parent:3.4.8
                io.quarkus.platform:quarkus-bom:3.15.6.1
                io.quarkus.platform:quarkus-maven-plugin:3.15.6
                io.quarkus:quarkus-extension-processor:3.15.6
                io.quarkus:quarkus-universe-bom:3.15.5
                io.quarkus.platform:quarkus-operator-sdk-bom:3.15.6
                io.quarkus:quarkus-extension-maven-plugin:3.15.6
                io.smallrye:jandex-maven-plugin:3.2.6
                io.quarkus:quarkus-bom:3.15.6
                io.smallrye:jandex:3.2.6
                io.quarkus:quarkus-maven-plugin:3.15.6
                test:version-with-suffix:1.0.0-Latest""");

        Path strictGavsFile = Files.createTempFile("strictGavs", ".txt");
        strictGavsFile.toFile().deleteOnExit();
        Files.writeString(strictGavsFile, """
                com.fasterxml.jackson:jackson-bom:2.18.4.1
                com.fasterxml.jackson.core:jackson-annotations:2.18.4
                com.fasterxml.jackson.core:jackson-core:2.18.4.1
                com.fasterxml.jackson.core:jackson-databind:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-avro:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-csv:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-ion:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-properties:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-protobuf:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-smile:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.18.4
                com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-eclipse-collections:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-guava:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-hibernate4:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-hibernate5:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-hibernate5-jakarta:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-hibernate6:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-hppc:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-jakarta-jsonp:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-jaxrs:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-joda:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-joda-money:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-json-org:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-jsr353:2.18.4
                com.fasterxml.jackson.datatype:jackson-datatype-pcollections:2.18.4
                com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-base:2.18.4
                com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-cbor-provider:2.18.4
                com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-json-provider:2.18.4
                com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-smile-provider:2.18.4
                com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-xml-provider:2.18.4
                com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-yaml-provider:2.18.4
                com.fasterxml.jackson.jaxrs:jackson-jaxrs-base:2.18.4
                com.fasterxml.jackson.jaxrs:jackson-jaxrs-cbor-provider:2.18.4
                com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider:2.18.4
                com.fasterxml.jackson.jaxrs:jackson-jaxrs-smile-provider:2.18.4
                com.fasterxml.jackson.jaxrs:jackson-jaxrs-xml-provider:2.18.4
                com.fasterxml.jackson.jaxrs:jackson-jaxrs-yaml-provider:2.18.4
                com.fasterxml.jackson.jr:jackson-jr-all:2.18.4
                com.fasterxml.jackson.jr:jackson-jr-annotation-support:2.18.4
                com.fasterxml.jackson.jr:jackson-jr-extension-javatime:2.18.4
                com.fasterxml.jackson.jr:jackson-jr-objects:2.18.4
                com.fasterxml.jackson.jr:jackson-jr-retrofit2:2.18.4
                com.fasterxml.jackson.jr:jackson-jr-stree:2.18.4
                com.fasterxml.jackson.module:jackson-module-afterburner:2.18.4
                com.fasterxml.jackson.module:jackson-module-android-record:2.18.4
                com.fasterxml.jackson.module:jackson-module-blackbird:2.18.4
                com.fasterxml.jackson.module:jackson-module-guice:2.18.4
                com.fasterxml.jackson.module:jackson-module-guice7:2.18.4
                com.fasterxml.jackson.module:jackson-module-jakarta-xmlbind-annotations:2.18.4
                com.fasterxml.jackson.module:jackson-module-jaxb-annotations:2.18.4
                com.fasterxml.jackson.module:jackson-module-jsonSchema:2.18.4
                com.fasterxml.jackson.module:jackson-module-jsonSchema-jakarta:2.18.4
                com.fasterxml.jackson.module:jackson-module-kotlin:2.18.4
                com.fasterxml.jackson.module:jackson-module-mrbean:2.18.4
                com.fasterxml.jackson.module:jackson-module-no-ctor-deser:2.18.4
                com.fasterxml.jackson.module:jackson-module-osgi:2.18.4
                com.fasterxml.jackson.module:jackson-module-parameter-names:2.18.4
                com.fasterxml.jackson.module:jackson-module-paranamer:2.18.4
                com.fasterxml.jackson.module:jackson-module-scala_2.11:2.18.4
                com.fasterxml.jackson.module:jackson-module-scala_2.12:2.18.4
                com.fasterxml.jackson.module:jackson-module-scala_2.13:2.18.4
                com.fasterxml.jackson.module:jackson-module-scala_3:2.18.4""");

        Path jsonFile = Files.createTempFile("json-config", ".yaml");
        jsonFile.toFile().deleteOnExit();
        // language=yaml
        Files.writeString(jsonFile, """
                ---
                platform: github
                username: renovate
                gitAuthor: renovate@test.com
                commitMessagePrefix: RENOVATE-0000
                dryRun: full
                prCreation: not-pending
                printConfig: true
                rebaseWhen: conflicted
                recreateWhen: always
                platformAutomerge: true
                labels:
                  - "renovate"
                  - "test"
                branchPrefix: renovate-support/
                branchPrefixOld: renovate/
                globalExtends:
                  - ":ignoreModulesAndTests"
                  - ":dependencyDashboard"
                helmv3:
                  registryAliases:
                    "@custom.libraries.helm.staging": https://artifactory.com/artifactory/custom.libraries.helm.staging
                packageRules:
                  - matchManagers:
                      - maven
                    matchUpdateTypes:
                      - patch
                    groupName: Default Maven Patch
                    autoMerge: true
                  - matchManagers:
                      - maven
                    matchUpdateTypes:
                      - minor
                      - major
                    groupName: Default Maven Minor
                    autoMerge: false
                  - matchPackagePatterns:
                      - .*
                    allowedVersions: "!/redhat|composite|groovyless|jboss|atlassian|preview/"
                hostRules:
                  - hostType: maven
                    matchHost: https://repo1.maven.org/maven2/
                    username: $process.env.MAVEN_USERNAME
                    password: $process.env.MAVEN_PASSWORD
                """);

        Path renovateConfigFile = Files.createTempFile("renovate-config", ".js");
        renovateConfigFile.toFile().deleteOnExit();

        String[] args = new String[]{
                "--renovateConfigOutputFile=" + renovateConfigFile,
                "--repositoriesFile=" + repositoriesFile,
                "--repository=https://github.com/Netcracker/qubership-core-release-test-maven-lib-4[branch=main]",
                "--fromJson={packageRules:[{'matchDatasources':['maven'],'registryUrls':['https://artifactory.com/pd.saas-release.mvn.group','https://artifactory.com/maven_pkg_github_com_netcracker.mvn.proxy']}]}",
                "--fromJson={'packageRules':[{'matchManagers':['maven'],'matchPackageNames':['/^org.qubership.*/'],'groupName':'qubership','labels':['group:qubership']}]}}",
                "--groupNameMapping={'quarkus': 'io.quarkus.*'}",
                "--groupNameMapping={\"com.fasterxml.jackson\": \"com.fasterxml.jackson.*\"}",
                "--fromFile=" + jsonFile,
                "--patchGavsFile=" + patchGavsFile,
                "--strictGavsFile=" + strictGavsFile
        };

        for (RenovateConfigOutputFormat format: RenovateConfigOutputFormat.values()) {
            String[] argsExtended = Arrays.copyOf(args, args.length + 1);
            String formatName = format.name().toLowerCase();
            argsExtended[argsExtended.length - 1] = "--renovateConfigOutputFormat=" + format;
            int status = RenovateConfigCli.run(argsExtended);
            Assertions.assertEquals(0, status);
            String result = Files.readString(renovateConfigFile);
            String expectedFileName = "testCli/result.%s".formatted(formatName);
            String expectedResult = new String(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(expectedFileName)).readAllBytes(), UTF_8);
            Assertions.assertEquals(expectedResult, result);
        }
    }
}
