package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovateMap;
import picocli.CommandLine;

public class RenovateMapConverter implements CommandLine.ITypeConverter<RenovateMap> {

    @Override
    public RenovateMap convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try {
            return new RenovateMap(value);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}