package org.qubership.cloud.actions.maven.model;

import java.util.stream.Collectors;

public class ReleaseSummary {

    public static String md(Result result) {
        String releasedRepositoriesGavs = String.join("\n", result.getReleases().stream()
                .map(r -> {
                    // language=md
                    String repositoryPart = """
                            #### %s
                            ```
                            %s
                            ```
                            """;
                    String tagUrl = r.isPushedToGit() ?
                            String.format("%s/tree/%s", r.getRepository().getUrl(), r.getVersionTag().tag()) :
                            r.getRepository().getUrl();
                    String gavs = r.getGavs().stream().map(GAV::toString).collect(Collectors.joining("\n"));
                    String urlName = r.getRepository().getUrl();
                    urlName += " [branch: %s, folder: %s]"
                            .formatted(r.getRepository().getBranch(), r.getRepository().getPomFolder());
                    String link = String.format("[%s](%s)", urlName, tagUrl);
                    return String.format(repositoryPart, link, gavs);
                }).toList());
        return """
                ### Release Summary%s
                %s
                """.formatted(result.isDryRun() ? " [DRY RUN]" : "", releasedRepositoriesGavs);
    }

    public static String gavs(Result result) {
        return result.getReleases().stream().flatMap(r -> r.getGavs().stream()).map(GAV::toString).collect(Collectors.joining(","));
    }

    public static String dependencyGraphDOT(Result result) {
        return result.getDependenciesDot();
    }

}
