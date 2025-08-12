package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

@ToString(exclude = {"password", "credentialsProvider"})
@Data
public class GitConfig {
    String username;
    String email;
    @JsonIgnore
    String password;
    String url;
    int checkoutParallelism;
    @JsonIgnore
    CredentialsProvider credentialsProvider;

    @Builder
    public GitConfig(String username, String email, String password, String url, int checkoutParallelism) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.url = url;
        this.checkoutParallelism = checkoutParallelism <= 0 ? 1 : checkoutParallelism;
        CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(this.getUsername(), this.getPassword());
        CredentialsProvider.setDefault(credentialsProvider);
        this.credentialsProvider = credentialsProvider;
    }
}
