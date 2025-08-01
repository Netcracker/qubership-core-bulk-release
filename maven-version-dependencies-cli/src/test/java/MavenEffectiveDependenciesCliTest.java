import org.qubership.cloud.actions.maven.MavenVersionDependenciesCli;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MavenEffectiveDependenciesCliTest {

    static String gitUsername = "denisanfimov";
    static String gitURL = "https://git.netcracker.com";

    static String gitEmail = "denis.anfimov@netcracker.com";
    static String gitPassword = "glpat-p_ByJ1ak4YRVxEyEoUUp";

    public static void main(String... args) {
        args = new String[]{
                "--baseDir=/Users/denis/IdeaProjects/cloud-core/lib/qubership-core-infra/dependencies",
                "--type1=spring",
                "--type1PomRelativeDir=spring-dependencies/pom.xml",
                "--type2=quarkus",
                "--type2PomRelativeDir=quarkus-dependencies/pom.xml",
                "--gitURL=" + gitURL,
                "--gitEmail=" + gitEmail,
                "--gitUsername=" + gitUsername,
                "--gitPassword=" + gitPassword,
                "--repositories=" + Stream.of(
                        "https://git.netcracker.com/PROD.Platform.Cloud_Core/security/core-utils[from=support/1.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/core-error-handling[from=support/2.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/okHttpM2MClient[from=support/5.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Core.Security/core-libs/java/pure/security-common[from=support/5.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/maas-client[from=support/10.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/maas-group/maas-declarative-client-commons[from=support/4.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Core.Security/core-libs/java/pure/access-control-java-libs[from=support/3.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/microservice-dependencies[from=support/10.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/springboot-starter[from=support/10.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/arquillian-cube-extension[from=support/7.x.x]",
//SPRING:
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/vault-client[from=support/6.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Core.Security/core-libs/java/spring/security-core[from=support/5.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/microservice-framework-extensions[from=support/5.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/mongo-evolution[from=support/6.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/microservice-restclient[from=support/5.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/context-propagation[from=support/6.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/rest-libraries[from=support/5.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Core.Security/core-libs/java/spring/access-control-spring-libs[from=support/3.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/blue-green-state-monitor-java[from=support/0.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/dbaas-client[from=support/7.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/maas-client-spring[from=support/7.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/microservice-framework[from=support/6.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/maas-group/maas-declarative-client-spring[from=support/5.x.x]",
// QUARKUS:
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/quarkus/cloud-core-context-propagation[from=support/5.x.x]",
//                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/quarkus/cloud-core-toolset-extensions[from=]", todo - this is dead repo
                                "https://git.netcracker.com/PROD.Platform.Core.Security/core-libs/java/quarkus/security-quarkus-extensions[from=support/6.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/cloud-core-quarkus-extensions[from=support/6.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/blue-green-state-monitor-quarkus[from=support/0.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/libs/maas-client-quarkus[from=support/7.x.x]",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/maas-group/maas-declarative-client-quarkus[from=support/5.x.x]",

                                // MICROSERVICES
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/maas-group/kafka-demo-quarkus",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/maas-group/kafka-demo-spring",
//                                "https://git.netcracker.com/PROD.Platform.Core.Security/core-services/identity-provider",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/config-server",
                                "https://git.netcracker.com/PROD.Platform.Cloud_Core/tenant-manager"
                        )
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .filter(s -> !s.startsWith("//"))
                        .collect(Collectors.joining(",")),
                "--resultOutputFile=diff.yaml",
                "--gavsOutputFile=/Users/denis/IdeaProjects/cloud-core/lib/qubership-core-bulk-release/renovate-config-cli/src/test/java/gavs.txt",
        };
        MavenVersionDependenciesCli.main(args);
        System.out.println();
    }
}
