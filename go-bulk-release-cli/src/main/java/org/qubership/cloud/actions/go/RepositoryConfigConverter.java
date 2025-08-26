package org.qubership.cloud.actions.go;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.RepositoryConfig;
import picocli.CommandLine;

@Slf4j
public class RepositoryConfigConverter implements CommandLine.ITypeConverter<RepositoryConfig> {

    @Override
    public RepositoryConfig convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try{
            RepositoryConfig repositoryConfig = RepositoryConfig.fromConfig(value);
            return repositoryConfig;
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}
