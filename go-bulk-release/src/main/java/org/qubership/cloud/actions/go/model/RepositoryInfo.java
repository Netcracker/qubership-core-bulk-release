package org.qubership.cloud.actions.go.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.qubership.cloud.actions.go.model.gomod.GoModFile;
import org.qubership.cloud.actions.go.model.gomod.GoModFileFactory;
import org.qubership.cloud.actions.go.proxy.GoModule;
import org.qubership.cloud.actions.go.util.FilesUtils;
import org.qubership.cloud.actions.go.util.UrlUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Getter
@EqualsAndHashCode(callSuper = true)
//TODO VLLA why inheritance, why not composition?
public class RepositoryInfo extends RepositoryConfig {
    String baseDir;

    Set<GAV> modules = new HashSet<>();
    Set<GAV> moduleDependencies = new HashSet<>();
    Map<GA, Set<GAV>> perModuleDependencies = new HashMap<>();

    List<GoModFile> goModFiles;

    public RepositoryInfo(RepositoryConfig repositoryConfig, String baseDir) {
        super(repositoryConfig.getUrl(), repositoryConfig.getBranch(), repositoryConfig.isSkipTests(),
                repositoryConfig.getVersion(), repositoryConfig.getVersionIncrementType());
        this.baseDir = baseDir;

        resolveDependencies();
    }

    public File getRepositoryDirFile() {
        return Paths.get(getBaseDir(), getDir()).toFile();
    }

//    //TODO VLLA complex logic in the constructor that not only constructs the object, but also changes the file system (git.checkout)
//    // => hard to test, hard to override => move to the service level
//    public GoRepositoryInfo(RepositoryConfig repositoryConfig, String baseDir) {
//        super(repositoryConfig.getUrl(), repositoryConfig.getBranch(), repositoryConfig.isSkipTests(),
//                repositoryConfig.getVersion(), repositoryConfig.getVersionIncrementType());
//        this.baseDir = baseDir;
//        try {
//            Path repositoryDirPath = Paths.get(baseDir, this.getDir());
//            boolean repositoryDirExists = Files.exists(repositoryDirPath);
//            if (!repositoryDirExists || Files.list(repositoryDirPath).findAny().isEmpty()) {
//                throw new IllegalStateException(String.format("Repository directory '%s' does not exist or is empty", repositoryDirPath));
//            }
//            try (Git git = Git.open(repositoryDirPath.toFile())) {
////                TODO VLLA why do checkout again if it's already done at buildDependencyGraph level?
//                String branch = repositoryConfig.getBranch();
//                try {
//                    git.checkout().setName(branch).call();
//                } catch (RefNotFoundException e)  {
//                    git.checkout().setName("origin/" +branch).call();
//                }
//            }
//            this.resolveDependencies();
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }

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

            ProcessBuilder pb = new ProcessBuilder("git", "describe", "--tags", "--abbrev=0");
            pb.directory(repoDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }

            int exitCode = process.waitFor();
            log.debug("VLLA exitCode = {}", exitCode);
            log.debug("VLLA output = {}", output);
            if (exitCode != 0 || output.isEmpty()) {
                throw new NoTagsFoundException("No tags found in the repository " + repoDir.getAbsolutePath());
            }

            return output;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
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
            List<GoModFile> goModFiles = goModFilePaths.stream()
                    .map(path -> GoModFileFactory.create(goModFilePaths.getFirst()))
                    .toList();

            this.goModFiles = goModFiles;

            for (GoModFile goModFile : goModFiles) {
                String moduleName = goModFile.getModuleName();
                GAV moduleGAV = new GAV("TMP", moduleName, "");
                modules.add(moduleGAV);
                perModuleDependencies.put(moduleGAV, new HashSet<>());

                goModFile.getDependencies()
                        .forEach(goDependency -> {
                            GAV gav = new GAV("TMP", goDependency.getModule(), goDependency.getVersion());
                            moduleDependencies.add(gav);
                            perModuleDependencies.get(moduleGAV).add(gav);
                        });
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }

        log.info("VLLA resolveDependencies {}. module deps: {}", getUrl(), moduleDependencies);
    }


    public void updateDepVersions(Collection<GAV> dependencies) {
        log.info("=== UPDATE DEPENDENCIES ===");
        for (GoModFile goModFile : goModFiles) {
            log.debug("process goModFile {}", goModFile.getFile());
            GoModule goModule = new GoModule(goModFile.getFile().getParent());

            log.info("VLLA dependencies: " + dependencies);
            log.info("VLLA getModuleDependencies: " + getModuleDependencies());

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
