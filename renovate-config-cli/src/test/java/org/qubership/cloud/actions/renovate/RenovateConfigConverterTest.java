package org.qubership.cloud.actions.renovate;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.actions.renovate.model.RenovateConfig;
import org.qubership.cloud.actions.renovate.model.RenovatePackageRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class RenovateConfigConverterTest {

    @Test
    public void testConvert() {
        RenovateConfig config = new RenovateConfig();
        config.setUsername("renovate");
        config.setGitAuthor("renovate@test.xom");
        config.setPlatform("github");
        config.setDryRun("full");
        config.setOnboarding(true);
        config.setRepositories(List.of(
                "https://github.com/Netcracker/qubership-core-release-test-maven-lib-1",
                "https://github.com/Netcracker/qubership-core-release-test-maven-lib-2",
                "https://github.com/Netcracker/qubership-core-release-test-maven-lib-3"
        ));
        config.setPackageRules(List.of(
                new RenovatePackageRule() {{
                    put("matchPackageNames", List.of("org.qubership:qubership-core-release-test-maven-lib-1"));
                    put("allowedVersions", "~1.2.3");
                }},
                new RenovatePackageRule() {{
                    put("matchPackageNames", List.of("org.qubership:qubership-core-release-test-maven-lib-2"));
                    put("allowedVersions", "~2.3.4");
                }},
                new RenovatePackageRule() {{
                    put("matchPackageNames", List.of("org.qubership:qubership-core-release-test-maven-lib-3"));
                    put("allowedVersions", "~3.4.5");
                }}
        ));
        String result = RenovateConfigToJsConverter.convert(config);
        Assertions.assertEquals("""
                module.exports = {
                  username : "renovate",
                  gitAuthor : "renovate@test.xom",
                  platform : "github",
                  dryRun : "full",
                  onboarding : true,
                  prConcurrentLimit : 20,
                  prHourlyLimit : 5,
                  branchConcurrentLimit : 5,
                  repositories : [ "https://github.com/Netcracker/qubership-core-release-test-maven-lib-1", "https://github.com/Netcracker/qubership-core-release-test-maven-lib-2", "https://github.com/Netcracker/qubership-core-release-test-maven-lib-3" ],
                  packageRules : [ {
                    matchPackageNames : [ "org.qubership:qubership-core-release-test-maven-lib-1" ],
                    allowedVersions : "~1.2.3"
                  }, {
                    matchPackageNames : [ "org.qubership:qubership-core-release-test-maven-lib-2" ],
                    allowedVersions : "~2.3.4"
                  }, {
                    matchPackageNames : [ "org.qubership:qubership-core-release-test-maven-lib-3" ],
                    allowedVersions : "~3.4.5"
                  } ]
                };""", result);
    }

    @Test
    public void testCli() throws Exception {
        Path tempFile = Files.createTempFile("renovate-config", ".js");
        tempFile.toFile().deleteOnExit();

        String[] args = new String[]{
                "--username=renovate",
                "--gitAuthor=renovate@test.com",
                "--platform=github",
                "--commitMessage=RENOVATE-0000 update dependencies",
                "--commitMessagePrefix=RENOVATE-0000",
                "--dryRun=full",
                "--labels=renovate",
                "--branchPrefix=renovate-support/",
                "--branchPrefixOld=renovate/",
                "--globalExtends=:ignoreModulesAndTests",
                "--renovateConfigOutputFile=" + tempFile,
                "--repositories=" + """
                        https://github.com/Netcracker/qubership-core-release-test-maven-lib-1[from=release/support-1.x.x],
                        https://github.com/Netcracker/qubership-core-release-test-maven-lib-2[from=release/support-2.x.x],
                        https://github.com/Netcracker/qubership-core-release-test-maven-lib-3"""
                        .replaceAll("\n", ""),
                "--packageRules=" +
                "[matchManagers=[maven];matchDatasources=[maven];matchUpdateTypes=[patch];groupName=Default Maven Patch]," +
                "[matchManagers=[maven];matchDatasources=[maven];matchUpdateTypes=[minor];groupName=Default Maven Minor]," +
                "[matchManagers=[maven];matchDatasources=[maven];matchUpdateTypes=[major];groupName=Default Maven Major]," +
                "[matchPackagePatterns=[.*];allowedVersions=!/redhat|composite|groovyless|jboss|atlassian|preview/]," +
                "[matchManagers=[maven];versioning=regex:^(?<compatibility>.*)-v?(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)?-SNAPSHOT$;enabled=false]",
                "--hostRules=" + """
                        maven[matchHost=https://repo1.maven.org/maven2/;username=process.env.MAVEN_USERNAME;password=process.env.MAVEN_PASSWORD],
                        maven[matchHost=https://maven.pkg.github.com/Netcracker/**;username=process.env.MAVEN_USERNAME;password=process.env.MAVEN_PASSWORD],
                        maven[matchHost=https://artifactorycn.netcracker.com/pd.saas-release.mvn.group]"""
                        .replace("\n", ""),
                "--gavs=" + """
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
                        com.fasterxml.jackson.module:jackson-module-scala_3:2.18.4"""
                        .replaceAll("\n", ",")
        };
        RenovateConfigCli.run(args);
        String result = Files.readString(tempFile);

        Assertions.assertEquals("""
                module.exports = {
                  username : "renovate",
                  gitAuthor : "renovate@test.com",
                  platform : "github",
                  commitMessage : "RENOVATE-0000 update dependencies",
                  baseBranchPatterns : [ "release/support-1.x.x", "release/support-2.x.x" ],
                  globalExtends : [ ":ignoreModulesAndTests" ],
                  commitMessagePrefix : "RENOVATE-0000",
                  dryRun : "full",
                  branchPrefix : "renovate-support/",
                  branchPrefixOld : "renovate/",
                  onboarding : false,
                  prConcurrentLimit : 20,
                  prHourlyLimit : 5,
                  branchConcurrentLimit : 20,
                  repositories : [ "Netcracker/qubership-core-release-test-maven-lib-1", "Netcracker/qubership-core-release-test-maven-lib-2", "Netcracker/qubership-core-release-test-maven-lib-3" ],
                  hostRules : [ {
                    hostType : "maven",
                    matchHost : "https://repo1.maven.org/maven2/",
                    username : process.env.MAVEN_USERNAME,
                    password : process.env.MAVEN_PASSWORD
                  }, {
                    hostType : "maven",
                    matchHost : "https://maven.pkg.github.com/Netcracker/**",
                    username : process.env.MAVEN_USERNAME,
                    password : process.env.MAVEN_PASSWORD
                  }, {
                    hostType : "maven",
                    matchHost : "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group"
                  } ],
                  packageRules : [ {
                    matchDatasources : [ "maven" ],
                    registryUrls : [ "https://repo1.maven.org/maven2/", "https://maven.pkg.github.com/Netcracker/**", "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group" ]
                  }, {
                    matchDatasources : [ "maven" ],
                    groupName : "Default Maven Patch",
                    matchManagers : [ "maven" ],
                    matchUpdateTypes : [ "patch" ]
                  }, {
                    matchDatasources : [ "maven" ],
                    groupName : "Default Maven Minor",
                    matchManagers : [ "maven" ],
                    matchUpdateTypes : [ "minor" ]
                  }, {
                    matchDatasources : [ "maven" ],
                    groupName : "Default Maven Major",
                    matchManagers : [ "maven" ],
                    matchUpdateTypes : [ "major" ]
                  }, {
                    matchPackagePatterns : [ ".*" ],
                    allowedVersions : "!/redhat|composite|groovyless|jboss|atlassian|preview/"
                  }, {
                    versioning : "regex:^(?<compatibility>.*)-v?(?<major>\\\\d+)\\\\.(?<minor>\\\\d+)\\\\.(?<patch>\\\\d+)?-SNAPSHOT$",
                    matchManagers : [ "maven" ],
                    enabled : false
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson.jr:jackson-jr-all", "com.fasterxml.jackson.jr:jackson-jr-annotation-support", "com.fasterxml.jackson.jr:jackson-jr-extension-javatime", "com.fasterxml.jackson.jr:jackson-jr-objects", "com.fasterxml.jackson.jr:jackson-jr-retrofit2", "com.fasterxml.jackson.jr:jackson-jr-stree" ],
                    allowedVersions : "2.18.4",
                    groupName : "com.fasterxml.jackson.jr"
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson:jackson-bom" ],
                    allowedVersions : "2.18.4.1",
                    groupName : "com.fasterxml.jackson"
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson.datatype:jackson-datatype-eclipse-collections", "com.fasterxml.jackson.datatype:jackson-datatype-guava", "com.fasterxml.jackson.datatype:jackson-datatype-hibernate4", "com.fasterxml.jackson.datatype:jackson-datatype-hibernate5", "com.fasterxml.jackson.datatype:jackson-datatype-hibernate5-jakarta", "com.fasterxml.jackson.datatype:jackson-datatype-hibernate6", "com.fasterxml.jackson.datatype:jackson-datatype-hppc", "com.fasterxml.jackson.datatype:jackson-datatype-jakarta-jsonp", "com.fasterxml.jackson.datatype:jackson-datatype-jaxrs", "com.fasterxml.jackson.datatype:jackson-datatype-jdk8", "com.fasterxml.jackson.datatype:jackson-datatype-joda", "com.fasterxml.jackson.datatype:jackson-datatype-joda-money", "com.fasterxml.jackson.datatype:jackson-datatype-json-org", "com.fasterxml.jackson.datatype:jackson-datatype-jsr310", "com.fasterxml.jackson.datatype:jackson-datatype-jsr353", "com.fasterxml.jackson.datatype:jackson-datatype-pcollections" ],
                    allowedVersions : "2.18.4",
                    groupName : "com.fasterxml.jackson.datatype"
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-base", "com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-cbor-provider", "com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-json-provider", "com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-smile-provider", "com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-xml-provider", "com.fasterxml.jackson.jakarta.rs:jackson-jakarta-rs-yaml-provider" ],
                    allowedVersions : "2.18.4",
                    groupName : "com.fasterxml.jackson.jakarta.rs"
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson.module:jackson-module-afterburner", "com.fasterxml.jackson.module:jackson-module-android-record", "com.fasterxml.jackson.module:jackson-module-blackbird", "com.fasterxml.jackson.module:jackson-module-guice", "com.fasterxml.jackson.module:jackson-module-guice7", "com.fasterxml.jackson.module:jackson-module-jakarta-xmlbind-annotations", "com.fasterxml.jackson.module:jackson-module-jaxb-annotations", "com.fasterxml.jackson.module:jackson-module-jsonSchema", "com.fasterxml.jackson.module:jackson-module-jsonSchema-jakarta", "com.fasterxml.jackson.module:jackson-module-kotlin", "com.fasterxml.jackson.module:jackson-module-mrbean", "com.fasterxml.jackson.module:jackson-module-no-ctor-deser", "com.fasterxml.jackson.module:jackson-module-osgi", "com.fasterxml.jackson.module:jackson-module-parameter-names", "com.fasterxml.jackson.module:jackson-module-paranamer", "com.fasterxml.jackson.module:jackson-module-scala_2.11", "com.fasterxml.jackson.module:jackson-module-scala_2.12", "com.fasterxml.jackson.module:jackson-module-scala_2.13", "com.fasterxml.jackson.module:jackson-module-scala_3" ],
                    allowedVersions : "2.18.4",
                    groupName : "com.fasterxml.jackson.module"
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson.core:jackson-annotations", "com.fasterxml.jackson.core:jackson-databind" ],
                    allowedVersions : "2.18.4",
                    groupName : "com.fasterxml.jackson.core"
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson.core:jackson-core" ],
                    allowedVersions : "2.18.4.1",
                    groupName : "com.fasterxml.jackson.core"
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson.jaxrs:jackson-jaxrs-base", "com.fasterxml.jackson.jaxrs:jackson-jaxrs-cbor-provider", "com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider", "com.fasterxml.jackson.jaxrs:jackson-jaxrs-smile-provider", "com.fasterxml.jackson.jaxrs:jackson-jaxrs-xml-provider", "com.fasterxml.jackson.jaxrs:jackson-jaxrs-yaml-provider" ],
                    allowedVersions : "2.18.4",
                    groupName : "com.fasterxml.jackson.jaxrs"
                  }, {
                    matchPackageNames : [ "com.fasterxml.jackson.dataformat:jackson-dataformat-avro", "com.fasterxml.jackson.dataformat:jackson-dataformat-cbor", "com.fasterxml.jackson.dataformat:jackson-dataformat-csv", "com.fasterxml.jackson.dataformat:jackson-dataformat-ion", "com.fasterxml.jackson.dataformat:jackson-dataformat-properties", "com.fasterxml.jackson.dataformat:jackson-dataformat-protobuf", "com.fasterxml.jackson.dataformat:jackson-dataformat-smile", "com.fasterxml.jackson.dataformat:jackson-dataformat-toml", "com.fasterxml.jackson.dataformat:jackson-dataformat-xml", "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml" ],
                    allowedVersions : "2.18.4",
                    groupName : "com.fasterxml.jackson.dataformat"
                  } ],
                  labels : [ "renovate" ]
                };""", result);
    }
}
