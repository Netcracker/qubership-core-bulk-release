package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovateParam;
import picocli.CommandLine;

public class RenovateParamConverter implements CommandLine.ITypeConverter<RenovateParam> {

    @Override
    public RenovateParam convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try {
            return new RenovateParam(value);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}