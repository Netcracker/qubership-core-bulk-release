package org.qubership.cloud.actions.renovate.converters;

import org.qubership.cloud.actions.maven.model.RepositoryConfig;
import picocli.CommandLine;

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
