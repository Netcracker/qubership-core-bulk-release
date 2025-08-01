import org.qubership.cloud.actions.maven.MavenVersionDependenciesCli;

import java.util.Arrays;
import java.util.stream.Collectors;

public class MavenEffectiveDependenciesCliOSTest {

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
                "--repositories=" + Arrays.stream("""
                                https://github.com/Netcracker/qubership-core-utils
                                https://github.com/Netcracker/qubership-core-error-handling
                                https://github.com/Netcracker/qubership-core-process-orchestrator
                                https://github.com/Netcracker/qubership-core-context-propagation
                                https://github.com/Netcracker/qubership-core-microservice-framework-extensions
                                https://github.com/Netcracker/qubership-core-mongo-evolution
                                https://github.com/Netcracker/qubership-core-restclient
                                https://github.com/Netcracker/qubership-core-rest-libraries
                                https://github.com/Netcracker/qubership-core-blue-green-state-monitor
                                https://github.com/Netcracker/qubership-maas-client
                                https://github.com/Netcracker/qubership-maas-client-spring
                                https://github.com/Netcracker/qubership-maas-declarative-client-commons
                                https://github.com/Netcracker/qubership-maas-declarative-client-spring
                                https://github.com/Netcracker/qubership-dbaas-client
                                https://github.com/Netcracker/qubership-core-microservice-dependencies
                                https://github.com/Netcracker/qubership-core-microservice-framework
                                https://github.com/Netcracker/qubership-core-springboot-starter
                                https://github.com/Netcracker/qubership-core-context-propagation-quarkus
                                https://github.com/Netcracker/qubership-core-quarkus-extensions
                                https://github.com/Netcracker/qubership-core-blue-green-state-monitor-quarkus
                                https://github.com/Netcracker/qubership-maas-client-quarkus
                                https://github.com/Netcracker/qubership-maas-declarative-client-quarkus
                                https://github.com/Netcracker/qubership-core-config-server
                                https://github.com/Netcracker/qubership-core-core-operator
                                https://github.com/Netcracker/qubership-dbaas
                                """
                                .split("\n"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .filter(s -> !s.startsWith("//"))
                        .collect(Collectors.joining(",")),
                "--resultOutputFile=diff-os.yaml",
                "--gavsOutputFile=/Users/denis/IdeaProjects/cloud-core/lib/qubership-core-bulk-release/renovate-config-cli/src/test/java/gavs-os.txt",
        };
        MavenVersionDependenciesCli.main(args);
        System.out.println();
    }
}
