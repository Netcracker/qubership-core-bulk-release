package org.qubership.cloud.actions.renovate.converters;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.IOException;
import java.util.Map;

public class JsonConverter implements CommandLine.ITypeConverter<Map<String, Object>> {
    static ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    @Override
    public Map<String, Object> convert(String value) throws CommandLine.TypeConversionException {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new CommandLine.TypeConversionException("Failed to parse json value:\n" + value + "\n. Error: " + e.getMessage());
        }
    }
}