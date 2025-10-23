package org.qubership.cloud.actions.renovate.converters;

import org.qubership.cloud.actions.maven.model.RepositoryConfig;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public class RepositoriesFileConfigConverter implements CommandLine.ITypeConverter<List<RepositoryConfig>> {

    @Override
    public List<RepositoryConfig> convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try{
            return Files.readAllLines(Path.of(value)).stream()
                    .filter(s -> !s.isBlank())
                    .map(RepositoryConfig::fromConfig)
                    .toList();
        } catch (IOException e) {
            throw new CommandLine.TypeConversionException("Failed to read content of the file:\n" + value + "\n. Error: " + e.getMessage());
        }
    }
}
