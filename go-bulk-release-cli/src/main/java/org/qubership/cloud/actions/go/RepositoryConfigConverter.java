package org.qubership.cloud.actions.go;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;
import picocli.CommandLine;

@Slf4j
public class RepositoryConfigConverter implements CommandLine.ITypeConverter<RepositoryConfig> {

    @Override
    public RepositoryConfig convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try{
            return RepositoryConfig.fromConfig(value);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}
