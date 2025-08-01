package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

@ToString(exclude = {"password", "credentialsProvider"})
@Data
@Builder
public class GitConfig {
    String username;
    String email;
    @JsonIgnore
    String password;
    String url;
    @JsonIgnore
    CredentialsProvider credentialsProvider;

    public synchronized CredentialsProvider getCredentialsProvider() {
        if (credentialsProvider == null) {
            credentialsProvider = new UsernamePasswordCredentialsProvider(this.getUsername(), this.getPassword());
            CredentialsProvider.setDefault(credentialsProvider);
        }
        return credentialsProvider;
    }
}
