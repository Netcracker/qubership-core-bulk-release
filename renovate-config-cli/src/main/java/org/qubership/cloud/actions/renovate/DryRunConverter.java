package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovateDryRun;
import picocli.CommandLine;

public class DryRunConverter implements CommandLine.ITypeConverter<RenovateDryRun> {

    @Override
    public RenovateDryRun convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try{
           return RenovateDryRun.valueOf(value.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}
