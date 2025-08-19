package org.qubership.cloud.actions.go.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.GitService;
import org.qubership.cloud.actions.go.model.gomod.GoModule;
import org.qubership.cloud.actions.go.model.gomod.GoModuleFactory;
import org.qubership.cloud.actions.go.util.FilesUtils;
import org.qubership.cloud.actions.go.util.UrlUtils;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    List<GoModule> goModFiles;

    private final GitService gitService = new GitService();

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
            currentVersion = gitService.getLastGitTag(this);
        } catch (NoTagsFoundException e) {
            log.debug("No tags found -> calculate current version from go.mod");
            //todo vlla getFirst is HACK - we need to support several modules structure
            String moduleName = getGoModFiles().getFirst().moduleName();
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
            if (goModFilePaths.size() != 1) {
                throw new UnsupportedEncodingException("Repositories with several go.mod files do not supported yet");
            }

            List<GoModule> goModules = goModFilePaths.stream().map(GoModuleFactory::create).toList();

            this.goModFiles = goModules;

            for (GoModule goModule : goModules) {
                String moduleName = goModule.moduleName();
                GAV moduleGAV = new GoGAV(moduleName, "");
                modules.add(moduleGAV);
                perModuleDependencies.put(moduleGAV, new HashSet<>());

                goModule.dependencies().forEach(gav -> {
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
        for (GoModule goModule : goModFiles) {
            log.debug("process goModFile {}", goModule.file());

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