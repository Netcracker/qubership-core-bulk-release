package org.qubership.cloud.actions.go;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.ReleaseTerminationException;
import org.qubership.cloud.actions.go.model.ReleaseVersion;
import org.qubership.cloud.actions.go.model.Semver;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;
import org.qubership.cloud.actions.go.util.CommandExecutionException;
import org.qubership.cloud.actions.go.util.CommandRunner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
public class SemanticReleaseService {
    private static final String GO_SEMANTIC_RELEASE_CURRENT_VERSION = "found version: ";
    private static final String GO_SEMANTIC_RELEASE_NEW_VERSION = "new version: ";
    private static final String CHANGELOG_FORMAT_TEMPLATE = "* {{with .Scope -}} **{{.}}:** {{end}} {{- .Message}} ({{trimSHA .SHA}}) {{- with index .Annotations \"author_login\" }} - by @{{.}} {{- end}}";

    ReleaseVersion resolveReleaseVersion(RepositoryInfo repository) {
        try {
            List<String> cmd = new ArrayList<>(List.of("semantic-release", "--no-ci", "--dry", "--allow-no-changes", "--force-bump-patch-version",
                    "--provider", "git",
                    "--provider-opt", "default_branch=main",
                    "--provider-opt", "log_order=ctime",
                    "--ci-condition", "default"));

            latestReachableTag(repository).ifPresent(tag -> {
                cmd.add("--match");
                cmd.add("v" + tag.getMajor() + "\\." + tag.getMinor() + "\\.");
            });

            List<String> result = CommandRunner.execWithResult(repository.getRepositoryDirFile(), cmd.toArray(new String[0]));

            String currentVersion = null;
            String newVersion = null;

            for (String line : result) {
                if (line.contains(GO_SEMANTIC_RELEASE_CURRENT_VERSION)) {
                    currentVersion = "v" + getSubstringAfter(line, GO_SEMANTIC_RELEASE_CURRENT_VERSION);
                    continue;
                }
                if (line.contains(GO_SEMANTIC_RELEASE_NEW_VERSION)) {
                    newVersion = "v" + getSubstringAfter(line, GO_SEMANTIC_RELEASE_NEW_VERSION);
                }
            }
            if (currentVersion == null || currentVersion.equals("v0.0.0")) {
                String msg = "Cannot find any valid tag for repository %s. Please, create at least one tag".formatted(repository.getUrl());
                throw new UnsupportedOperationException(msg);
            } else if (newVersion == null) {
                return new ReleaseVersion(currentVersion, currentVersion);
            } else {
                return new ReleaseVersion(currentVersion, newVersion);
            }
        } catch (CommandExecutionException e) {
            String msg = "Cannot resolve next release version for repository %s. See logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    void release(RepositoryInfo repository) {
        log.info("=== DEPLOY RELEASE {} ===", repository.getDir());

        try {
            List<String> cmd = new ArrayList<>(List.of("semantic-release", "--allow-no-changes", "--no-ci", "--force-bump-patch-version",
                    "--provider", "github",
                    "--provider-opt", "slug=" + repository.getDir(),
                    "--provider-opt", "github_use_compare_commits=true",
                    "--ci-condition", "default",
                    "--changelog-generator-opt", "format_commit_template=" + CHANGELOG_FORMAT_TEMPLATE));

            latestReachableTag(repository).ifPresent(tag -> {
                cmd.add("--match");
                cmd.add("v" + tag.getMajor() + "\\." + tag.getMinor() + "\\.");
            });

            CommandRunner.exec(repository.getRepositoryDirFile(), cmd.toArray(new String[0]));
        } catch (CommandExecutionException e) {
            String msg = "Cannot deploy release for repository %s. Se logs for more info".formatted(repository.getUrl());
            throw new ReleaseTerminationException(msg, e);
        }
    }

    private Optional<Semver> latestReachableTag(RepositoryInfo repository) {
        try {
            return CommandRunner.execWithResult(repository.getRepositoryDirFile(), "git", "tag", "--merged", "HEAD")
                    .stream()
                    .filter(t -> t.matches("v\\d+\\.\\d+\\.\\d+"))
                    .map(Semver::new)
                    .max(Comparator.comparingInt(Semver::getMajor)
                            .thenComparingInt(Semver::getMinor)
                            .thenComparingInt(Semver::getPatch));
        } catch (CommandExecutionException e) {
            return Optional.empty();
        }
    }

    static String getSubstringAfter(String line, String substring) {
        return line.substring(line.indexOf(substring) + substring.length());
    }
}
