package org.qubership.cloud.actions.maven.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Map;
import java.util.stream.Collectors;

@Getter
@EqualsAndHashCode(callSuper = true)
public class Repository extends RepositoryConfig {

    public Repository(String url) {
        super(url);
    }

    public Repository(String url, Map<String, String> params) {
        super(url, params);
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
