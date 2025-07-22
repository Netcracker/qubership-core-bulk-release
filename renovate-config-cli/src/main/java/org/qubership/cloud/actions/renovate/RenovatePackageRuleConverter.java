package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovatePackageRule;
import picocli.CommandLine;

public class RenovatePackageRuleConverter implements CommandLine.ITypeConverter<RenovatePackageRule> {

    @Override
    public RenovatePackageRule convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try {
            return new RenovatePackageRule(value);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.TypeConversionException(e.getMessage());
        }
    }
}