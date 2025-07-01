package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovateHostRule;
import picocli.CommandLine;

public class RenovateMavenRepositoryConverter implements CommandLine.ITypeConverter<RenovateHostRule> {

    @Override
    public RenovateHostRule convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try {
            return new RenovateHostRule(value);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}