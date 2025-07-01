package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovateConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RenovateConfigToJsConverter {

    public static String convert(RenovateConfig config, int tabSize) {
        String tab = " ".repeat(tabSize);
        StringBuilder sb = new StringBuilder();

        Optional.ofNullable(config.getUsername()).ifPresent(value -> sb.append(tab).append(String.format("username: '%s',\n", value)));
        Optional.ofNullable(config.getGitAuthor()).ifPresent(value -> sb.append(tab).append(String.format("gitAuthor: '%s',\n", value)));
        Optional.ofNullable(config.getPlatform()).ifPresent(value -> sb.append(tab).append(String.format("platform: '%s',\n", value)));
        Optional.ofNullable(config.getDryRun()).ifPresent(value -> sb.append(tab).append(String.format("dryRun: '%s',\n", value)));
        Optional.ofNullable(config.isOnboarding()).ifPresent(value -> sb.append(tab).append(String.format("onboarding: %s,\n", value)));
        Optional.ofNullable(config.getRepositories()).ifPresent(value -> sb.append(tab)
                .append(String.format("repositories: [\n%s\n%s],\n",
                        String.join(",\n", value.stream().map(s -> tab.repeat(2) + "'" + s + "'").toList()),
                        tab)
                ));
        Optional.ofNullable(config.getHostRules()).ifPresent(value -> sb.append(tab)
                .append(String.format("hostRules: [\n%s\n%s],\n",
                        String.join(",\n", value.stream().map(s -> {
                            StringBuilder sb1 = new StringBuilder(tab.repeat(3) + "{\n");
                            sb1.append(tab.repeat(4)).append("hostType: ").append("'").append(s.getHostType()).append("'").append(",").append("\n");
                            sb1.append(tab.repeat(4)).append("matchHost: ").append("'").append(s.getMatchHost()).append("'").append(",").append("\n");
                            if (s.getUsername() != null) {
                                if (s.getUsername().startsWith("process.")) {
                                    sb1.append(tab.repeat(4)).append("username: ").append(s.getUsername());
                                } else {
                                    sb1.append(tab.repeat(4)).append("username: ").append("'").append(s.getUsername()).append("'");
                                }
                                if (s.getPassword() != null) sb1.append(",").append("\n");
                            }
                            if (s.getPassword() != null) {
                                if (s.getPassword().startsWith("process.")) {
                                    sb1.append(tab.repeat(4)).append("password: ").append(s.getPassword());
                                } else {
                                    sb1.append(tab.repeat(4)).append("password: ").append("'").append(s.getPassword()).append("'");
                                }
                            }
                            sb1.append("\n").append(tab.repeat(3)).append("}");
                            return sb1.toString();
                        }).toList()),
                        tab.repeat(2))
                ));
        Optional.ofNullable(config.getPackageRules()).ifPresent(value -> sb.append(tab)
                .append(String.format("packageRules: [%s\n%s],",
                        String.join(",", value.stream().map(p -> {
                            List<String> packageLines = new ArrayList<>();
                            if (p.getMatchDatasources() != null) {
                                packageLines.add(String.format("matchDatasources: [\n%s\n%s]",
                                        String.join(",\n", p.getMatchDatasources().stream().map(s -> tab.repeat(5) + "'" + s + "'").toList()),
                                        tab.repeat(4)));
                            }
                            if (p.getMatchPackageNames() != null) {
                                packageLines.add(String.format("matchPackageNames: [\n%s\n%s]",
                                        String.join(",\n", p.getMatchPackageNames().stream().map(s -> tab.repeat(5) + "'" + s + "'").toList()),
                                        tab.repeat(4)));
                            }
                            if (p.getRegistryUrls() != null) {
                                packageLines.add(String.format("registryUrls: [\n%s\n%s]",
                                        String.join(",\n", p.getRegistryUrls().stream().map(s -> tab.repeat(5) + "'" + s + "'").toList()),
                                        tab.repeat(4)));
                            }
                            if (p.getAllowedVersions() != null) {
                                packageLines.add(String.format("allowedVersions: \"%s\"", p.getAllowedVersions()));
                            }
                            return "\n" + tab.repeat(3) + "{\n" +
                                   String.join(",\n", packageLines.stream().map(s -> tab.repeat(4) + s).toList()) +
                                   "\n" + tab.repeat(3) + "}";
                        }).toList()),
                        tab)));

        return String.format("""
                module.exports = {
                %s
                };""", sb);
    }
}
