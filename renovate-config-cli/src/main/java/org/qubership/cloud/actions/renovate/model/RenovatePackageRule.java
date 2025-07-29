package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
public class RenovatePackageRule extends LinkedHashMap<String, Object> {

    public static Pattern pattern = Pattern.compile("^\\[(?<params>.+)]$");
    public static Pattern booleanPattern = Pattern.compile("^(true|false)$", Pattern.CASE_INSENSITIVE);
    public static Pattern intPattern = Pattern.compile("^\\d+$");
    public static Pattern listPattern = Pattern.compile("^\\[(.+)]$");


    public RenovatePackageRule() {
    }

    public RenovatePackageRule(String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid package rule: '%s'. Must match: '%s'", value, pattern));
        }
        Map<String, Object> params = Arrays.stream(matcher.group("params").split(";"))
                .map(v -> v.split("="))
                .filter(a -> a.length == 2)
                .collect(Collectors.toMap(a -> a[0], a -> {
                    String v = a[1];
                    Matcher m;
                    if ((m = booleanPattern.matcher(v)).matches()) {
                        return Boolean.parseBoolean(v);
                    } else if ((m = intPattern.matcher(v)).matches()) {
                        return Integer.parseInt(v);
                    } else if ((m = listPattern.matcher(v)).matches()) {
                        return Arrays.stream(m.group(1).split("&")).toList();
                    } else {
                        return v;
                    }
                }));
        this.putAll(params);
    }
}
