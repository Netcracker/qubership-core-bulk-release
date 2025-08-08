package org.qubership.cloud.actions.go.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlUtils {
    public static Pattern pattern = Pattern.compile("^(?<url>https://[^/]+/(?<dir>[^\\[]+))(\\[(?<params>.*)])?$");

    public static String getDirName(String url) {
        Matcher matcher = pattern.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid repository url: %s. Must match pattern: '%s'", url, pattern));
        }
        return matcher.group("dir");
    }
}
