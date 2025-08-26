package org.qubership.cloud.actions.go;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.TagOpt;
import org.qubership.cloud.actions.go.model.*;
import org.qubership.cloud.actions.go.util.CommandRunner;
import org.qubership.cloud.actions.go.util.LoggerWriter;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
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
            throw new RuntimeException(e);
        }
    }

    public void gitCheckout(Path repositoryPath, RepositoryConfig repository) {
        log.info("Gei checkout for {} [START]", repository.getUrl());
        try {
            boolean repositoryDirExists = Files.exists(repositoryPath);
            log.debug("VLLA repositoryDirExists = {}", repositoryDirExists);
            Git git;
            String branch = repository.getBranch();
            //todo vlla do we need this if?
            if (repositoryDirExists && Files.list(repositoryPath).findAny().isPresent()) {
                log.debug("VLLA gitCheckout 1");
                git = Git.open(repositoryPath.toFile());
                try {
                    log.debug("VLLA gitCheckout 2");
                    git.checkout().setForced(true).setName(branch).call();
                } catch (RefNotFoundException e) {
                    log.debug("VLLA gitCheckout 3");
                    git.checkout().setForced(true).setName("origin/" + branch).call();
                }
            } else {
                log.debug("VLLA gitCheckout 4");
                PrintWriter printWriter = new PrintWriter(new LoggerWriter(), true);
                try {
                    printWriter.println(String.format("Checking out %s from: [%s]", repository.getUrl(), branch));
                    Files.createDirectories(repositoryPath);

                    TextProgressMonitor monitor = new TextProgressMonitor(printWriter);

                    git = Git.cloneRepository()
                            .setCredentialsProvider(config.getCredentialsProvider())
                            .setURI(repository.getUrl())
                            .setDirectory(repositoryPath.toFile())
                            .setBranch(branch)
                            .setCloneAllBranches(true)
                            .setTagOption(TagOpt.FETCH_TAGS)
                            //.setProgressMonitor(monitor)
                            .call();
                    //todo vlla tmp commented - generates too many logs
                } finally {
                    printWriter.flush();
                }
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
            throw new RuntimeException(e);
        }
    }

    public void commitModified(File repository, String msg) {
        log.debug("Commit modified files for {}", repository.getAbsoluteFile());
        try (Git git = Git.open(repository)) {
            List<String> modifiedFiles = getDiff(git, DiffEntry.ChangeType.MODIFY);
            if (!modifiedFiles.isEmpty()) {
                git.add().setUpdate(true).call();
                git.commit().setMessage(msg).call();
                log.info("Commited '{}', changed files:\n{}", msg, String.join("\n", modifiedFiles));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void commitAdded(File repository, String msg) {
        log.debug("Commit added files for {}", repository.getAbsoluteFile());
        try (Git git = Git.open(repository)) {
            List<String> addedFiles = getDiff(git, DiffEntry.ChangeType.ADD);
            if (!addedFiles.isEmpty()) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage(msg).call();
                log.info("Commited '{}', added files:\n{}", msg, String.join("\n", addedFiles));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void createTag(RepositoryInfo repository, String releaseVersion) {
        CommandRunner.exec(repository.getRepositoryDirFile(), "git", "tag", "-a", releaseVersion, "-m", "Release " + releaseVersion);
    }

    public String getLastGitTag(RepositoryInfo repository) throws NoTagsFoundException {
        File repoDir = repository.getRepositoryDirFile();
        try {
            if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
                throw new IllegalArgumentException("Directory is not a git repository: " + repoDir);
            }

            try (Git git = Git.open(repoDir)) {
                List<Ref> tagRefs = git.tagList().call();

                try (RevWalk walk = new RevWalk(git.getRepository())) {
                    Optional<TagInfo> lastTag = tagRefs.stream().map(ref -> {
                        try {
                            RevObject obj = walk.parseAny(ref.getObjectId());

                            if (obj instanceof RevTag tag) {
                                return new TagInfo(ref.getName(), tag.getTaggerIdent().getWhenAsInstant());
                            }
                            else if (obj instanceof RevCommit commit) {
                                return new TagInfo(ref.getName(), commit.getAuthorIdent().getWhenAsInstant());
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    }).filter(Objects::nonNull).max(Comparator.comparing(TagInfo::date));
                    if (lastTag.isPresent()) {
                        return lastTag.get().name();
                    } else {
                        throw new NoTagsFoundException("No tags found in the repository " + repoDir.getAbsolutePath());
                    }
                }
            }
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    public void pushChanges(File repository /*String releaseVersion*/) {
        PrintWriter printWriter = new PrintWriter(new LoggerWriter(), true);
        try (Git git = Git.open(repository)) {
//            if (releaseVersion != null) {
//                Optional<Ref> tagOpt = git.tagList().call().stream()
//                        .filter(t -> t.getName().equals(String.format("refs/tags/%s", releaseVersion)))
//                        .findFirst();
//                if (tagOpt.isEmpty()) {
//                    throw new IllegalStateException(String.format("git tag: %s not found", releaseVersion));
//                }
//            }
            git.push()
                    .setProgressMonitor(new TextProgressMonitor(printWriter))
                    .setCredentialsProvider(config.getCredentialsProvider())
                    .setPushAll()
                    .call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            printWriter.flush();
        }
        log.info("Pushed to git: repo: {}", repository.getAbsoluteFile());
    }

    record TagInfo(String name, Instant date) {
        @Override
        public String name() {
            return name.replace("refs/tags/", "");
        }
    }

    private List<String> getDiff(Git git, DiffEntry.ChangeType changeType) throws GitAPIException {
        List<DiffEntry> diff = git.diff().call();
        return diff.stream().filter(d -> d.getChangeType() == changeType).map(DiffEntry::getNewPath).toList();
    }
}
