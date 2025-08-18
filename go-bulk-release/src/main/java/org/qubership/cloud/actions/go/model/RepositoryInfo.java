package org.qubership.cloud.actions.go.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.qubership.cloud.actions.go.model.gomod.GoModFile;
import org.qubership.cloud.actions.go.model.gomod.GoModFileFactory;
import org.qubership.cloud.actions.go.proxy.GoModule;
import org.qubership.cloud.actions.go.util.FilesUtils;
import org.qubership.cloud.actions.go.util.UrlUtils;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Getter
@EqualsAndHashCode(callSuper = true)
public class RepositoryInfo extends RepositoryConfig {
    String baseDir;

    Set<GAV> modules = new HashSet<>();
    Set<GAV> moduleDependencies = new HashSet<>();
    Map<GA, Set<GAV>> perModuleDependencies = new HashMap<>();

    List<GoModFile> goModFiles;

    public RepositoryInfo(RepositoryConfig repositoryConfig, String baseDir) {
        super(repositoryConfig.getUrl(), repositoryConfig.getBranch(), repositoryConfig.isSkipTests(), repositoryConfig.getVersion(), repositoryConfig.getVersionIncrementType());
        this.baseDir = baseDir;

        resolveDependencies();
    }

    public File getRepositoryDirFile() {
        return Paths.get(getBaseDir(), getDir()).toFile();
    }

    private static final Pattern SEMVER_PATTERN = Pattern.compile("v(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)");

    public String calculateReleaseVersion(VersionIncrementType versionIncrementType) {
        String currentVersion;

        try {
            currentVersion = getLastGitTag(getRepositoryDirFile());
        } catch (NoTagsFoundException e) {
            log.debug("No tags found -> calculate current version from go.mod");
            //todo vlla getFirst is HACK
            String moduleName = getGoModFiles().getFirst().getModuleName();
            String moduleVersion = extractGoModuleVersion(moduleName);
            currentVersion = moduleVersion + ".0.0";
        }

        Matcher matcher = SEMVER_PATTERN.matcher(currentVersion);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Non-semver version: %s. Must match pattern: '%s'", currentVersion, SEMVER_PATTERN.pattern()));
        }
        int major = Integer.parseInt(matcher.group("major"));
        int minor = Integer.parseInt(matcher.group("minor"));
        int patch = Integer.parseInt(matcher.group("patch"));
        switch (versionIncrementType) {
            case MAJOR -> {
                major++;
                minor = 0;
                patch = 0;
            }
            case MINOR -> {
                minor++;
                patch = 0;
            }
            case PATCH -> {
                patch++;
            }
        }
        return String.format("v%d.%d.%d", major, minor, patch);
    }

    public static String getLastGitTag(File repoDir) throws NoTagsFoundException {
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

    record TagInfo(String name, Instant date) {
        @Override
        public String name() {
            return name.replace("refs/tags/", "");
        }
    }

    public static String extractGoModuleVersion(String moduleName) {
        if (moduleName == null || moduleName.isEmpty()) {
            return null;
        }

        int lastSlash = moduleName.lastIndexOf('/');
        if (lastSlash == -1) {
            return "v1";
        }

        String lastSegment = moduleName.substring(lastSlash + 1);

        if (lastSegment.matches("v\\d+")) {
            return lastSegment;
        }

        return "v1";
    }

    void resolveDependencies() {
        this.modules.clear();
        this.moduleDependencies.clear();
        this.perModuleDependencies.clear();

        try {
            String dir = UrlUtils.getDirName(getUrl());
            Path repositoryDirPath = Paths.get(baseDir, UrlUtils.getDirName(getUrl()));
            boolean repositoryDirExists = Files.exists(repositoryDirPath);
            if (!repositoryDirExists || Files.list(repositoryDirPath).findAny().isEmpty()) {
                throw new IllegalStateException(String.format("Repository directory '%s' does not exist or is empty", repositoryDirPath));
            }

            List<Path> goModFilePaths = FilesUtils.findAll(Path.of(baseDir, dir), "go.mod");

            //todo vlla collect all, not only first
            List<GoModFile> goModFiles = goModFilePaths.stream().map(path -> GoModFileFactory.create(goModFilePaths.getFirst())).toList();

            this.goModFiles = goModFiles;

            for (GoModFile goModFile : goModFiles) {
                String moduleName = goModFile.getModuleName();
                GAV moduleGAV = new GAV("TMP", moduleName, "");
                modules.add(moduleGAV);
                perModuleDependencies.put(moduleGAV, new HashSet<>());

                goModFile.getDependencies().forEach(goDependency -> {
                    GAV gav = new GAV("TMP", goDependency.getModule(), goDependency.getVersion());
                    moduleDependencies.add(gav);
                    perModuleDependencies.get(moduleGAV).add(gav);
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void updateDepVersions(Collection<GAV> dependencies) {
        log.info("=== UPDATE DEPENDENCIES ===");
        for (GoModFile goModFile : goModFiles) {
            log.debug("process goModFile {}", goModFile.getFile());
            GoModule goModule = new GoModule(goModFile.getFile().getParent());

            dependencies.stream().filter(this::isModuleContainsDependency).forEach(goModule::get);

            log.info("go mod tidy");
            goModule.tidy();
        }
        this.resolveDependencies();
    }

    private boolean isModuleContainsDependency(GAV dependency) {
        return getModuleDependencies().stream().anyMatch(gav -> gav.isSameArtifact(dependency));
    }

    @Override
    public String toString() {
        return getUrl();
    }
}
