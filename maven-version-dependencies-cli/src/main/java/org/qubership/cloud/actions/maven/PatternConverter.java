package org.qubership.cloud.actions.maven;

import picocli.CommandLine;

import java.util.regex.Pattern;

public class PatternConverter implements CommandLine.ITypeConverter<Pattern> {

    @Override
    public Pattern convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try {
            return Pattern.compile(value);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}
