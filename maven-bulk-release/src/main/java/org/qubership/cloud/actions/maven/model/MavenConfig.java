package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@ToString(exclude = "password")
@Data
@Builder
public class MavenConfig {
    String user;
    @JsonIgnore
    String password;
    String altDeploymentRepository;
    @Builder.Default
    String localRepositoryPath = "${user.home}/.m2/repository";
}
