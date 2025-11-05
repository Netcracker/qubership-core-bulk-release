package org.qubership.cloud.actions.renovate.converters;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.SneakyThrows;

import java.util.Map;
import java.util.regex.Pattern;

public class RenovateConfigToJsConverter {

    static Pattern keyPattern = Pattern.compile("\"(?<key>[^:\"]+?)\" :");
    static Pattern codeFragmentValuePattern = Pattern.compile("\"\\$(?<value>.+?)\"");

    @SneakyThrows
    public static String convert(Map<String, Object> config) {
        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
        String json = mapper.writeValueAsString(config);
        json = keyPattern.matcher(json).replaceAll(mr -> {
            String key = mr.group("key");
            if (key.startsWith("'") && key.endsWith("'")) {
                return String.format("\"%s\" :", key.substring(1, key.length() - 1));
            } else if (!key.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                return "\"%s\" :".formatted(key);
            } else {
                return key + " :";
            }
        });
        json = codeFragmentValuePattern.matcher(json).replaceAll(mr -> mr.group("value"));
        return String.format("module.exports = %s;", json);
    }
}
