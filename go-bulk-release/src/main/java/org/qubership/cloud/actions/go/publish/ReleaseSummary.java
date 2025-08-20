package org.qubership.cloud.actions.go.publish;

import org.qubership.cloud.actions.go.model.GAV;
import org.qubership.cloud.actions.go.model.RepositoryConfig;
import org.qubership.cloud.actions.go.model.Result;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
                            String.format("%s/releases/tag/%s", r.getRepository().getUrl(), r.getTag()) :
                            r.getRepository().getUrl();
                    String gavs = r.getGavs().stream().map(GAV::toString).collect(Collectors.joining("\n"));
                    String urlName = r.getRepository().getUrl();
                    if (!RepositoryConfig.HEAD.equals(r.getRepository().getBranch())) {
                        urlName += String.format(" [%s]", r.getRepository().getBranch());
                    }
                    String link = String.format("[%s](%s)", urlName, tagUrl);
                    return String.format(repositoryPart, link, gavs);
                }).toList());
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM.dd.yyyy HH:mm"));
        return String.format("""
                ### Release Summary%s (%s)
                %s
                """, result.isDryRun()? " [DRY RUN]" : "", time, releasedRepositoriesGavs);
    }

    public static String gavs(Result result) {
        return result.getReleases().stream().flatMap(r -> r.getGavs().stream()).map(GAV::toString).collect(Collectors.joining(","));
    }

    public static String dependencyGraphDOT(Result result) {
        return result.getDependenciesDot();
    }

}
