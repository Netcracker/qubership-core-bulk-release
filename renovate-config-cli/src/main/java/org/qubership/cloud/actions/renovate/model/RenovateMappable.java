package org.qubership.cloud.actions.renovate.model;

import java.util.regex.Pattern;

public interface RenovateMappable {

    Pattern booleanPattern = Pattern.compile("^(true|false)$", Pattern.CASE_INSENSITIVE);
    Pattern intPattern = Pattern.compile("^\\d+$");
    Pattern listPattern = Pattern.compile("^\\[(.+)]$");
    Pattern mapPattern = Pattern.compile("^\\{(.+)}$");

}
