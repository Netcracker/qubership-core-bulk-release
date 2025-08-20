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
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@Getter
@EqualsAndHashCode(callSuper = true)
public class RepositoryInfo extends RepositoryConfig {
    String baseDir;

    Set<GAV> modules = new HashSet<>();
    Set<GAV> moduleDependencies = new HashSet<>();
    Map<GA, Set<GAV>> perModuleDependencies = new HashMap<>();

    List<GoModule> goModFiles;

    private final GitService gitService = new GitService(null);

    public RepositoryInfo(RepositoryConfig repositoryConfig, String baseDir) {
        super(repositoryConfig.getUrl(), repositoryConfig.getBranch(), repositoryConfig.isSkipTests(), repositoryConfig.getVersion(), repositoryConfig.getVersionIncrementType());
        this.baseDir = baseDir;

        resolveDependencies();
    }

    public File getRepositoryDirFile() {
        return Paths.get(getBaseDir(), getDir()).toFile();
    }

    public ReleaseVersion calculateReleaseVersion(VersionIncrementType versionIncrementType) {
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

        return new ReleaseVersion(currentVersion, versionIncrementType);
    }

    public String extractGoModuleVersion(String moduleName) {
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
        for (GoModule goModule : goModFiles) {
            log.debug("process goModFile {}", goModule.file());

            dependencies.stream().filter(this::isModuleContainsDependency).forEach(goModule::get);

            log.info("go mod tidy");
            goModule.tidy();
        }
        resolveDependencies();
        checkIsAllDependenciesUpdated(dependencies);
    }

    public void checkIsAllDependenciesUpdated(Collection<GAV> dependencies) {
        Set<GAV> updatedModuleDependencies = getModuleDependencies();
        Set<GAV> missedDependencies = updatedModuleDependencies.stream()
                .filter(new DifferentVersionDependencyPredicate(dependencies))
                .collect(Collectors.toSet());
        if (!missedDependencies.isEmpty()) {
            throw new RuntimeException("Some dependencies does not updated: " + missedDependencies.stream().map(GAV::toString).collect(Collectors.joining("\n")));
        }
    }

    private boolean isModuleContainsDependency(GAV dependency) {
        return getModuleDependencies().stream().anyMatch(gav -> gav.isSameArtifact(dependency));
    }

    @Override
    public String toString() {
        return getUrl();
    }

    private record DifferentVersionDependencyPredicate(Collection<GAV> dependencies) implements Predicate<GAV> {
        @Override
        public boolean test(GAV gav) {
            Optional<GAV> foundGav = dependencies.stream()
                    .filter(dGav -> dGav.isSameArtifact(gav))
                    .findFirst();
            if (foundGav.isEmpty()) return false;
            GAV g = foundGav.get();
            return !Objects.equals(gav.getVersion(), g.getVersion());
        }
    }
}