package org.qubership.cloud.actions.go;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.TagOpt;
import org.qubership.cloud.actions.go.model.GitConfig;
import org.qubership.cloud.actions.go.model.repository.RepositoryConfig;
import org.qubership.cloud.actions.go.model.UnexpectedException;
import org.qubership.cloud.actions.go.util.LoggerWriter;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GitService {
    private final GitConfig config;

    public GitService(GitConfig config) {
        this.config = config;
    }

    public void setupGit() {
        Pattern urlPattern = Pattern.compile("^https://(?<host>.+)(:\\d+)?$");
        Pattern gitHostCredsPattern = Pattern.compile("^https://(?<username>.+):(?<password>.+)@(?<host>.+)$");

        Path credentialsFilePath = Paths.get(System.getProperty("user.home"), "/.git-credentials");
        String url = config.getUrl();
        Matcher urlMatcher = urlPattern.matcher(url);
        if (!urlMatcher.matches()) {
            throw new IllegalArgumentException(String.format("Invalid git url: %s, must match pattern: %s", url, urlPattern.pattern()));
        }
        String host = urlMatcher.group("host");
        try {
            if (!Files.exists(credentialsFilePath)) {
                Files.createFile(credentialsFilePath);
            }
            List<String> lines = Files.readAllLines(credentialsFilePath);
            boolean updated = false;
            Optional<Matcher> existingHostMatcher = lines.stream()
                    .map(gitHostCredsPattern::matcher)
                    .filter(Matcher::matches)
                    .filter(matcher -> Objects.equals(matcher.group("host"), host))
                    .findFirst();
            if (existingHostMatcher.isPresent()) {
                Matcher existingGitHostMatcher = existingHostMatcher.get();
                String username = existingGitHostMatcher.group("username");
                String password = existingGitHostMatcher.group("password");
                config.setUsername(username);
                config.setPassword(password);
            } else {
                String username = config.getUsername();
                String password = config.getPassword();
                String newEntry = String.format("https://%s:%s@%s", username, password, host);
                lines.add(newEntry);
                updated = true;
            }
            if (updated) {
                Files.writeString(credentialsFilePath, String.join("\n", lines));
                log.info("Updated ~/.git-credentials.");
            }
        } catch (IOException e) {
            throw new UnexpectedException(e);
        }
    }

    public void gitCheckout(Path repositoryPath, RepositoryConfig repository) {
        log.info("Gei checkout for {} [START]", repository.getUrl());
        try {
            boolean repositoryDirExists = Files.exists(repositoryPath);
            Git git;
            String branch = repository.getBranch();
            //todo vlla do we need this if?
            if (repositoryDirExists && Files.list(repositoryPath).findAny().isPresent()) {
                git = Git.open(repositoryPath.toFile());
                try {
                    git.checkout().setForced(true).setName(branch).call();
                } catch (RefNotFoundException e) {
                    git.checkout().setForced(true).setName("origin/" + branch).call();
                }
            } else {
                log.debug("Checking out {} from: [{}]", repository.getUrl(), branch);
                Files.createDirectories(repositoryPath);

                git = Git.cloneRepository()
                        .setCredentialsProvider(config.getCredentialsProvider())
                        .setURI(repository.getUrl())
                        .setDirectory(repositoryPath.toFile())
                        .setBranch(branch)
                        .setCloneAllBranches(true)
                        .setTagOption(TagOpt.FETCH_TAGS)
                        .call();
            }
            try (git; org.eclipse.jgit.lib.Repository rep = git.getRepository()) {
                StoredConfig gitConfig = rep.getConfig();
                gitConfig.setString("user", null, "name", config.getUsername());
                gitConfig.setString("user", null, "email", config.getEmail());
                gitConfig.setString("credential", null, "helper", "store");
                gitConfig.save();
                log.debug("Saved git config:\n{}", gitConfig.toText());
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public void commitModified(File repository, String msg) {
        log.debug("Commit modified files for {}", repository.getAbsoluteFile());
        try (Git git = Git.open(repository)) {
            List<String> modifiedFiles = getDiff(git, DiffEntry.ChangeType.MODIFY);
            if (!modifiedFiles.isEmpty()) {
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage(msg).call();
                log.info("Commited '{}', changed files:\n{}", msg, String.join("\n", modifiedFiles));
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public void pushChanges(File repository) {
        PrintWriter printWriter = new PrintWriter(new LoggerWriter(), true);
        try (Git git = Git.open(repository)) {
            git.push()
                    .setProgressMonitor(new TextProgressMonitor(printWriter))
                    .setCredentialsProvider(config.getCredentialsProvider())
                    .setPushAll()
                    .call();
        } catch (Exception e) {
            throw new UnexpectedException(e);
        } finally {
            printWriter.flush();
        }
        log.info("Pushed to git: repo: {}", repository.getAbsoluteFile());
    }

    private List<String> getDiff(Git git, DiffEntry.ChangeType changeType) throws GitAPIException {
        List<DiffEntry> diff = git.diff().call();
        return diff.stream().filter(d -> d.getChangeType() == changeType).map(DiffEntry::getNewPath).toList();
    }
}
