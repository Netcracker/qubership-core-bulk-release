package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class RenovateReportPackageFiles {
     List<RenovateReportMaven> maven;

     /*
     {
            "datasource": "maven",
            "packageFile": "bg-operator-integration-tests/pom.xml",
            "deps": [
              {
                "datasource": "maven",
                "depName": "ch.qos.logback:logback-classic",
                "currentValue": "1.5.18",
                "fileReplacePosition": 1396,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "compile",
                "updates": [],
                "packageName": "ch.qos.logback:logback-classic",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/qos-ch/logback",
                "homepage": "http://logback.qos.ch",
                "changelogUrl": "https://logback.qos.ch/news.html",
                "respectLatest": false,
                "currentVersion": "1.5.18",
                "currentVersionTimestamp": "2025-03-18T12:48:00.000Z",
                "currentVersionAgeInDays": 217,
                "fixedVersion": "1.5.18"
              },
              {
                "datasource": "maven",
                "depName": "com.google.code.gson:gson",
                "currentValue": "2.11.0",
                "fileReplacePosition": 1572,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "compile",
                "updates": [
                  {
                    "bucket": "non-major",
                    "newVersion": "2.13.2",
                    "newValue": "2.13.2",
                    "releaseTimestamp": "2025-09-10T20:41:08.000Z",
                    "newVersionAgeInDays": 40,
                    "registryUrl": "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                    "newMajor": 2,
                    "newMinor": 13,
                    "newPatch": 2,
                    "updateType": "minor",
                    "isBreaking": false,
                    "libYears": 1.312531265854896,
                    "branchName": "renovate-master/com.google.code.gson"
                  }
                ],
                "packageName": "com.google.code.gson:gson",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/google/gson",
                "respectLatest": false,
                "currentVersion": "2.11.0",
                "currentVersionTimestamp": "2024-05-19T18:54:42.000Z",
                "currentVersionAgeInDays": 519,
                "isSingleVersion": true,
                "fixedVersion": "2.11.0"
              },
              {
                "datasource": "maven",
                "depName": "com.fasterxml.jackson.core:jackson-databind",
                "currentValue": "2.19.2",
                "fileReplacePosition": 1766,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "compile",
                "updates": [],
                "packageName": "com.fasterxml.jackson.core:jackson-databind",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/FasterXML/jackson-databind",
                "homepage": "https://github.com/FasterXML/jackson",
                "packageScope": "com.fasterxml.jackson.core",
                "respectLatest": false,
                "currentVersion": "2.19.2",
                "currentVersionTimestamp": "2025-10-21T05:34:12.000Z",
                "currentVersionAgeInDays": 0,
                "fixedVersion": "2.19.2"
              },
              {
                "datasource": "maven",
                "depName": "com.netcracker.cloud.junit.cloudcore:cloud-core-extension",
                "currentValue": "6.0.12",
                "fileReplacePosition": 1974,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "compile",
                "updates": [
                  {
                    "bucket": "major",
                    "newVersion": "8.4.1",
                    "newValue": "8.4.1",
                    "releaseTimestamp": "2025-10-14T05:56:43.000Z",
                    "newVersionAgeInDays": 7,
                    "registryUrl": "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                    "newMajor": 8,
                    "newMinor": 4,
                    "newPatch": 1,
                    "updateType": "major",
                    "isBreaking": true,
                    "libYears": 0.7114356925418569,
                    "branchName": "renovate-master/major-netcracker"
                  }
                ],
                "packageName": "com.netcracker.cloud.junit.cloudcore:cloud-core-extension",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/Netcracker/qubership-core-junit-k8s-extension",
                "respectLatest": false,
                "currentVersion": "6.0.12",
                "currentVersionTimestamp": "2025-01-27T13:46:07.000Z",
                "currentVersionAgeInDays": 266,
                "isSingleVersion": true,
                "fixedVersion": "6.0.12"
              },
              {
                "datasource": "maven",
                "depName": "org.hamcrest:hamcrest",
                "currentValue": "3.0",
                "fileReplacePosition": 2146,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "test",
                "updates": [],
                "packageName": "org.hamcrest:hamcrest",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/hamcrest/JavaHamcrest",
                "homepage": "http://hamcrest.org/JavaHamcrest/",
                "packageScope": "org.hamcrest",
                "respectLatest": false,
                "currentVersion": "3.0",
                "currentVersionTimestamp": "2024-08-01T09:09:00.000Z",
                "currentVersionAgeInDays": 446,
                "fixedVersion": "3.0"
              },
              {
                "datasource": "maven",
                "depName": "org.junit.jupiter:junit-jupiter-engine",
                "currentValue": "5.11.0",
                "fileReplacePosition": 2364,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "compile",
                "updates": [
                  {
                    "bucket": "non-major",
                    "newVersion": "5.12.2",
                    "newValue": "5.12.2",
                    "releaseTimestamp": "2025-04-11T14:10:55.000Z",
                    "newVersionAgeInDays": 192,
                    "registryUrl": "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                    "newMajor": 5,
                    "newMinor": 12,
                    "newPatch": 2,
                    "updateType": "minor",
                    "isBreaking": false,
                    "libYears": 0.6580843480466768,
                    "branchName": "renovate-master/junit"
                  }
                ],
                "packageName": "org.junit.jupiter:junit-jupiter-engine",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/junit-team/junit-framework",
                "homepage": "https://junit.org/",
                "packageScope": "org.junit.jupiter",
                "respectLatest": false,
                "currentVersion": "5.11.0",
                "currentVersionTimestamp": "2024-08-14T09:21:47.000Z",
                "currentVersionAgeInDays": 433,
                "isSingleVersion": true,
                "fixedVersion": "5.11.0"
              },
              {
                "datasource": "maven",
                "depName": "org.projectlombok:lombok",
                "currentValue": "1.18.38",
                "fileReplacePosition": 2539,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "compile",
                "updates": [
                  {
                    "bucket": "non-major",
                    "newVersion": "1.18.40",
                    "newValue": "1.18.40",
                    "releaseTimestamp": "2025-09-04T22:18:00.000Z",
                    "newVersionAgeInDays": 46,
                    "registryUrl": "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                    "newMajor": 1,
                    "newMinor": 18,
                    "newPatch": 40,
                    "updateType": "patch",
                    "isBreaking": false,
                    "libYears": 0.43127181633688483,
                    "branchName": "renovate-master/org.projectlombok"
                  }
                ],
                "packageName": "org.projectlombok:lombok",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/projectlombok/lombok",
                "homepage": "https://projectlombok.org",
                "packageScope": "org.projectlombok",
                "respectLatest": false,
                "currentVersion": "1.18.38",
                "currentVersionTimestamp": "2025-03-31T12:21:32.000Z",
                "currentVersionAgeInDays": 204,
                "isSingleVersion": true,
                "fixedVersion": "1.18.38"
              },
              {
                "datasource": "maven",
                "depName": "com.squareup.okhttp3:okhttp",
                "currentValue": "4.12.0",
                "fileReplacePosition": 2718,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "compile",
                "updates": [],
                "packageName": "com.squareup.okhttp3:okhttp",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/square/okhttp",
                "homepage": "https://square.github.io/okhttp/",
                "packageScope": "com.squareup.okhttp3",
                "respectLatest": false,
                "currentVersion": "4.12.0",
                "currentVersionTimestamp": "2023-10-17T02:23:57.000Z",
                "currentVersionAgeInDays": 735,
                "fixedVersion": "4.12.0"
              },
              {
                "datasource": "maven",
                "depName": "org.apache.maven.plugins:maven-surefire-plugin",
                "currentValue": "2.22.2",
                "fileReplacePosition": 2978,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "build",
                "updates": [
                  {
                    "bucket": "major",
                    "newVersion": "3.2.5",
                    "newValue": "3.2.5",
                    "releaseTimestamp": "2024-01-06T19:37:41.000Z",
                    "newVersionAgeInDays": 653,
                    "registryUrl": "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                    "newMajor": 3,
                    "newMinor": 2,
                    "newPatch": 5,
                    "updateType": "major",
                    "isBreaking": true,
                    "libYears": 4.699534443938474,
                    "branchName": "renovate-master/major-org.apache.maven.plugins"
                  }
                ],
                "packageName": "org.apache.maven.plugins:maven-surefire-plugin",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/apache/maven-surefire",
                "homepage": "https://maven.apache.org/surefire/",
                "packageScope": "org.apache.maven.plugins",
                "respectLatest": false,
                "currentVersion": "2.22.2",
                "currentVersionTimestamp": "2019-04-25T18:55:03.000Z",
                "currentVersionAgeInDays": 2370,
                "isSingleVersion": true,
                "fixedVersion": "2.22.2"
              },
              {
                "datasource": "maven",
                "depName": "org.junit.platform:junit-platform-surefire-provider",
                "currentValue": "1.3.2",
                "fileReplacePosition": 3808,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "build",
                "updates": [],
                "packageName": "org.junit.platform:junit-platform-surefire-provider",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/junit-team/junit5",
                "homepage": "http://junit.org/junit5/",
                "packageScope": "org.junit.platform",
                "respectLatest": false,
                "currentVersion": "1.3.2",
                "currentVersionTimestamp": "2018-11-25T19:12:23.000Z",
                "currentVersionAgeInDays": 2521,
                "fixedVersion": "1.3.2"
              },
              {
                "datasource": "maven",
                "depName": "org.apache.maven.plugins:maven-antrun-plugin",
                "currentValue": "3.1.0",
                "fileReplacePosition": 4019,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "build",
                "updates": [],
                "packageName": "org.apache.maven.plugins:maven-antrun-plugin",
                "versioning": "maven",
                "warnings": [],
                "sourceUrl": "https://github.com/apache/maven-antrun-plugin",
                "homepage": "https://maven.apache.org/plugins/",
                "respectLatest": false,
                "currentVersion": "3.1.0",
                "currentVersionTimestamp": "2022-04-18T19:42:00.000Z",
                "currentVersionAgeInDays": 1281,
                "fixedVersion": "3.1.0"
              },
              {
                "datasource": "maven",
                "depName": "ant:ant-junit",
                "currentValue": "1.6.5",
                "fileReplacePosition": 5180,
                "registryUrls": [
                  "https://artifactorycn.netcracker.com/pd.saas-release.mvn.group",
                  "https://artifactorycn.netcracker.com/pd.saas.mvn.group",
                  "https://repo.maven.apache.org/maven2"
                ],
                "depType": "build",
                "updates": [],
                "packageName": "ant:ant-junit",
                "versioning": "maven",
                "warnings": [],
                "packageScope": "ant",
                "respectLatest": false,
                "currentVersion": "1.6.5",
                "currentVersionTimestamp": "2005-11-08T22:24:34.000Z",
                "currentVersionAgeInDays": 7286,
                "fixedVersion": "1.6.5"
              }
            ],
            "packageFileVersion": "1.0.0-SNAPSHOT"
          }
      */
}
