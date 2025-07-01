package org.qubership.cloud.actions.renovate;

import org.qubership.cloud.actions.renovate.model.RenovateConfig;
import org.qubership.cloud.actions.renovate.model.RenovateMaven;

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
        Optional.ofNullable(config.getMaven()).map(RenovateMaven::getRepositories).ifPresent(value -> sb.append(tab)
                .append(String.format("maven: {\n%s\n%s},\n",
                        String.format("%srepositories: [\n%s\n%s]",
                                tab.repeat(2),
                                String.join(",\n", value.stream().map(s -> {
                                    StringBuilder sb1 = new StringBuilder(tab.repeat(3) + "{\n");
                                    sb1.append(tab.repeat(4)).append("id: ").append("'").append(s.getId()).append("'").append("\n");
                                    sb1.append(tab.repeat(4)).append("url: ").append("'").append(s.getUrl()).append("'").append("\n");
                                    if (s.getUsername() != null) {
                                        sb1.append(tab.repeat(4)).append("username: ").append("'").append(s.getUsername()).append("'");
                                        if (s.getPassword() != null) sb1.append("\n");
                                    }
                                    if (s.getPassword() != null) {
                                        sb1.append(tab.repeat(4)).append("password: ").append("'").append(s.getPassword()).append("'");
                                    }
                                    sb1.append("\n").append(tab.repeat(3)).append("}");
                                    return sb1.toString();
                                }).toList()),
                                tab.repeat(2)),
                        tab)
                ));
        Optional.ofNullable(config.getPackageRules()).ifPresent(value -> sb.append(tab)
                .append(String.format("packageRules: [%s\n%s],",
                        String.join(",", value.stream().map(p -> {
                            List<String> packageLines = List.of(
                                    String.format("matchPackageNames: [\n%s\n%s]",
                                            String.join(",\n", p.getMatchPackageNames().stream().map(s -> tab.repeat(5) + "'" + s + "'").toList()),
                                            tab.repeat(4)),
                                    String.format("allowedVersions: \"%s\"", p.getAllowedVersions())
                            );
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
