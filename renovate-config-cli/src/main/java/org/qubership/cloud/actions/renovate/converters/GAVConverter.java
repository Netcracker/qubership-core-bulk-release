package org.qubership.cloud.actions.renovate.converters;

import org.qubership.cloud.actions.maven.model.GAV;
import picocli.CommandLine;

public class GAVConverter implements CommandLine.ITypeConverter<GAV> {

    @Override
    public GAV convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try{
            return new GAV(value);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}
