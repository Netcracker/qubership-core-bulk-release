package org.qubership.cloud.actions.maven.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.stream.Collectors;

@Getter
@EqualsAndHashCode(callSuper = true)
public class Repository extends RepositoryConfig {
    @Setter
    String dir;

    public Repository(String url) {
        super(url);
    }

    public Repository(String url, Map<String, String> params) {
        super(url, params);
    }

    public Repository(String url, Map<String, String> params, String dir) {
        super(url, params);
        this.dir = dir;
    }

    @Override
    public String toString() {
        return String.format("%s [%s] [dir=%s]", url,
                params().entrySet().stream()
                        .map(entry-> String.format("%s=%s", entry.getKey(), entry.getValue()))
                        .collect(Collectors.joining(",")),
                dir);
    }
}
