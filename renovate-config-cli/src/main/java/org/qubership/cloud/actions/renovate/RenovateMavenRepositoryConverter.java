package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovateMavenRepository;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.List;

public class RenovateMavenRepositoryConverter implements CommandLine.ITypeConverter<List<RenovateMavenRepository>> {

    @Override
    public List<RenovateMavenRepository> convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try {
            return Arrays.stream(value.split(",")).map(RenovateMavenRepository::new).toList();
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}
