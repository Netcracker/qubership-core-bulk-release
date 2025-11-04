package org.qubership.cloud.actions.renovate.converters;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class YamlFromFileConverter implements CommandLine.ITypeConverter<Map<String, Object>> {
    static ObjectMapper mapper = new YAMLMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    @Override
    public Map<String, Object> convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try {
            String str = Files.readString(Path.of(value));
            return mapper.readValue(str, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new CommandLine.TypeConversionException("Failed to parse content of the file:\n" + value + "\n. Error: " + e.getMessage());
        }
    }
}