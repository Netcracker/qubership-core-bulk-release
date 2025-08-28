package org.qubership.cloud.actions.go.model.repository;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.GitService;
import org.qubership.cloud.actions.go.model.GA;
import org.qubership.cloud.actions.go.model.GAV;
import org.qubership.cloud.actions.go.model.GoGAV;
import org.qubership.cloud.actions.go.model.UnexpectedException;
import org.qubership.cloud.actions.go.model.gomod.GoModule;
import org.qubership.cloud.actions.go.model.gomod.GoModuleFactory;
import org.qubership.cloud.actions.go.util.FilesUtils;
import org.qubership.cloud.actions.go.util.UrlUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Getter
@EqualsAndHashCode(callSuper = true)
public class RepositoryInfo extends RepositoryConfig {
    String baseDir;

    Set<GoGAV> modules = new HashSet<>();
    Set<GoGAV> moduleDependencies = new HashSet<>();
    Map<GA, Set<GoGAV>> perModuleDependencies = new HashMap<>();

    List<GoModule> goModFiles;

    private final GitService gitService = new GitService(null);

    public RepositoryInfo(RepositoryConfig repositoryConfig, String baseDir) {
        super(repositoryConfig.getUrl(), repositoryConfig.getBranch(), repositoryConfig.isSkipTests(), repositoryConfig.getVersion());
        this.baseDir = baseDir;

        resolveDependencies();
    }

    public File getRepositoryDirFile() {
        return Paths.get(getBaseDir(), getDir()).toFile();
    }

    void resolveDependencies() {
        this.modules.clear();
        this.moduleDependencies.clear();
        this.perModuleDependencies.clear();

        String dir = UrlUtils.getDirName(getUrl());
        Path repositoryDirPath = Paths.get(baseDir, UrlUtils.getDirName(getUrl()));
        boolean repositoryDirExists = Files.exists(repositoryDirPath);
        try (Stream<Path> files = Files.list(repositoryDirPath)) {
            if (!repositoryDirExists || files.findAny().isEmpty()) {
                throw new IllegalStateException(String.format("Repository directory '%s' does not exist or is empty", repositoryDirPath));
            }
        } catch (IOException e) {
            throw new UnexpectedException(e);
        }

        List<Path> goModFilePaths = FilesUtils.findAll(Path.of(baseDir, dir), "go.mod");
        if (goModFilePaths.size() != 1) {
            throw new UnsupportedOperationException("Repositories with several go.mod files do not supported yet");
        }

        List<GoModule> goModules = goModFilePaths.stream().map(GoModuleFactory::create).toList();

        this.goModFiles = goModules;

        for (GoModule goModule : goModules) {
            String moduleName = goModule.moduleName();
            GoGAV moduleGAV = new GoGAV(moduleName, "");
            modules.add(moduleGAV);
            perModuleDependencies.put(moduleGAV, new HashSet<>());

            goModule.dependencies().forEach(gav -> {
                moduleDependencies.add(gav);
                perModuleDependencies.get(moduleGAV).add(gav);
            });
        }
    }

    public void updateDepVersions(Collection<GoGAV> dependencies) {
        for (GoModule goModule : goModFiles) {
            log.debug("process goModFile {}", goModule.file());

            dependencies.stream().filter(this::isModuleContainsDependency).forEach(goModule::get);
        }
        resolveDependencies();
        checkIsAllDependenciesUpdated(dependencies);
    }

    public void checkIsAllDependenciesUpdated(Collection<GoGAV> dependencies) {
        Set<GoGAV> updatedModuleDependencies = getModuleDependencies();
        Set<GoGAV> missedDependencies = updatedModuleDependencies.stream()
                .filter(new DifferentVersionDependencyPredicate(dependencies))
                .collect(Collectors.toSet());
        if (!missedDependencies.isEmpty()) {
            throw new UnexpectedException("Some dependencies does not updated: " + missedDependencies.stream().map(GAV::toString).collect(Collectors.joining("\n")));
        }
    }

    private boolean isModuleContainsDependency(GAV dependency) {
        return getModuleDependencies().stream().anyMatch(gav -> gav.isSameArtifact(dependency));
    }

    @Override
    public String toString() {
        return getUrl();
    }

    private record DifferentVersionDependencyPredicate(Collection<GoGAV> dependencies) implements Predicate<GoGAV> {
        @Override
        public boolean test(GoGAV gav) {
            Optional<GoGAV> foundGav = dependencies.stream()
                    .filter(dGav -> dGav.isSameArtifact(gav))
                    .findFirst();
            if (foundGav.isEmpty()) return false;
            GAV g = foundGav.get();
            return !Objects.equals(gav.getVersion(), g.getVersion());
        }
    }
}