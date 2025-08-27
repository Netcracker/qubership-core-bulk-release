package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class RenovateParam implements RenovateMappable {
    String key;
    Object value;

    public static Pattern pattern = Pattern.compile("^(?<key>.+?)=(?<value>.+?)$");

    public RenovateParam(String param) {
        Matcher matcher = pattern.matcher(param);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid param: " + param + ". Must match pattern: " + pattern.pattern());
        }
        key = matcher.group("key");
        String v = matcher.group("value");
        Matcher m;
        if (booleanPattern.matcher(v).matches()) {
            value = Boolean.parseBoolean(v);
        } else if (intPattern.matcher(v).matches()) {
            value = Integer.parseInt(v);
        } else if ((m = listPattern.matcher(v)).matches()) {
            value = Arrays.stream(m.group(1).split(",")).toList();
        } else if ((m = mapPattern.matcher(v)).matches()) {
            value = new RenovateMap(m.group(1));
        } else {
            value = v;
        }
    }
}
