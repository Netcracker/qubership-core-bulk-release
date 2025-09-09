package org.qubership.cloud.actions.maven;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.TagOpt;
import org.qubership.cloud.actions.maven.model.GitConfig;
import org.qubership.cloud.actions.maven.model.RepositoryConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class GitService {

    final GitConfig gitConfig;

    public GitService(GitConfig gitConfig) {
        setupGit(gitConfig);
        this.gitConfig = gitConfig;
    }

    private void setupGit(GitConfig gitConfig) {
        Pattern urlPattern = Pattern.compile("^https://(?<host>.+)(:\\d+)?$");
        Pattern gitHostCredsPattern = Pattern.compile("^https://(?<username>.+):(?<password>.+)@(?<host>.+)$");

        Path credentialsFilePath = Paths.get(System.getProperty("user.home"), "/.git-credentials");
        String url = gitConfig.getUrl();
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
                gitConfig.setUsername(username);
                gitConfig.setPassword(password);
            } else {
                String username = gitConfig.getUsername();
                String password = gitConfig.getPassword();
                String newEntry = String.format("https://%s:%s@%s", username, password, host);
                lines.add(newEntry);
                updated = true;
            }
            if (updated) {
                Files.writeString(credentialsFilePath, String.join("\n", lines));
                log.info("Updated ~/.git-credentials.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void gitCheckout(String baseDir, RepositoryConfig repository, OutputStream out) {
        try (out) {
            Path repositoryDirPath = Paths.get(baseDir, repository.getDir());
            boolean repositoryDirExists = Files.exists(repositoryDirPath);
            Git git;
            String branch = repository.getBranch();
            if (repositoryDirExists && Files.list(repositoryDirPath).findAny().isPresent()) {
                git = Git.open(repositoryDirPath.toFile());
                try {
                    git.checkout().setForced(true).setName(branch).call();
                } catch (RefNotFoundException e) {
                    git.checkout().setForced(true).setName("origin/" + branch).call();
                }
            } else {
                PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(out, UTF_8));
                try {
                    printWriter.println(String.format("Checking out %s from: [%s]", repository.getUrl(), branch));
                    Files.createDirectories(repositoryDirPath);

                    git = Git.cloneRepository()
                            .setCredentialsProvider(this.gitConfig.getCredentialsProvider())
                            .setURI(repository.getUrl())
                            .setDirectory(repositoryDirPath.toFile())
                            .setDepth(1)
                            .setBranch(branch)
                            .setCloneAllBranches(false)
                            .setTagOption(TagOpt.FETCH_TAGS)
                            .setProgressMonitor(this.gitConfig.isPrintProgress() ? new TextProgressMonitor(printWriter) : null)
                            .call();
                } finally {
                    printWriter.flush();
                }
            }
            try (git; org.eclipse.jgit.lib.Repository rep = git.getRepository()) {
                StoredConfig storedConfig = rep.getConfig();
                storedConfig.setString("user", null, "name", this.gitConfig.getUsername());
                storedConfig.setString("user", null, "email", this.gitConfig.getEmail());
                storedConfig.setString("credential", null, "helper", "store");
                storedConfig.save();
                log.debug("Saved git config:\n{}", storedConfig.toText());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
