package org.qubership.cloud.actions.renovate.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Data
public class RenovateMap extends LinkedHashMap<String, Object> implements RenovateMappable {

    Pattern mapEntryPattern = Pattern.compile("(?<key>[^=]+?)=(?<map>\\{(.+)})");

    public RenovateMap() {
    }

    public RenovateMap(String value) {
        Map<String, Object> params = Arrays.stream(value.split(";"))
                .map(v -> {
                    Matcher m = mapEntryPattern.matcher(v);
                    if (m.matches()) {
                        return new String[]{m.group("key"), m.group("map")};
                    } else {
                        return v.split("=");
                    }
                })
                .filter(a -> a.length == 2)
                .collect(Collectors.toMap(a -> a[0], a -> {
                    String v = a[1];
                    Matcher m;
                    if (booleanPattern.matcher(v).matches()) {
                        return Boolean.parseBoolean(v);
                    } else if (intPattern.matcher(v).matches()) {
                        return Integer.parseInt(v);
                    } else if ((m = listPattern.matcher(v)).matches()) {
                        return Arrays.stream(m.group(1).split(",")).toList();
                    } else if ((m = mapPattern.matcher(v)).matches()) {
                        return new RenovateMap(m.group(1));
                    } else {
                        return v;
                    }
                }, (v1, v2) -> v2, LinkedHashMap::new));
        this.putAll(params);
    }
}
