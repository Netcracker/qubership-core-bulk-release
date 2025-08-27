package org.qubership.cloud.actions.go.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlUtils {
    public static final Pattern PATTERN = Pattern.compile("^(?<url>https://[^/]+/(?<dir>[^\\[]+))(\\[(?<params>.*)])?$");

    private UrlUtils() {}

    public static String getDirName(String url) {
        Matcher matcher = PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid repository url: %s. Must match pattern: '%s'", url, PATTERN));
        }
        return matcher.group("dir");
    }
}
