package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovateMavenRepository;
import picocli.CommandLine;

public class RenovateMavenRepositoryConverter implements CommandLine.ITypeConverter<RenovateMavenRepository> {

    @Override
    public RenovateMavenRepository convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try{
            return new RenovateMavenRepository(value);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}
