package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.SneakyThrows;
import org.qubership.cloud.actions.renovate.model.RenovateConfig;

import java.util.regex.Pattern;

public class RenovateConfigToJsConverter {

    static Pattern keyPattern = Pattern.compile("\"(?<key>[^:\"]+?)\" :");
    static Pattern processEnvPattern = Pattern.compile("\"(?<value>process\\.env\\..+?)\"");

    @SneakyThrows
    public static String convert(RenovateConfig config) {
        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
        String json = mapper.writeValueAsString(config);
        json = keyPattern.matcher(json).replaceAll(mr -> mr.group("key") + " :");
        json = processEnvPattern.matcher(json).replaceAll(mr -> mr.group("value"));
        return String.format("module.exports = %s;", json);
    }
}
