# Advanced Usage

## Configuration-Driven Approach

The tool is fully configuration-driven through YAML configuration files:

```yaml
baseDir: "/path/to/workspace"
gitConfig:
  username: "git-user"
  password: "git-password"
mavenConfig:
  settingsXml: "/path/to/settings.xml"
  localRepo: "/path/to/local/repo"
repositories:
  - url: "https://github.com/org/repo1"
    from: "main"
  - url: "https://github.com/org/repo2"
    from: "develop"
repositoriesToReleaseFrom:
  - url: "https://github.com/org/repo1"
    from: "main"
gavs:
  - "org.example:library:1.0.0"
versionIncrementType: "PATCH"
skipTests: false
dryRun: true
runSequentially: false
```

### Configuration Options

#### Git Configuration
- `username`: Git username for authentication
- `password`: Git password or token for authentication

#### Maven Configuration
- `settingsXml`: Path to custom Maven settings.xml file
- `localRepo`: Path to custom Maven local repository

#### Repository Configuration
- `url`: Git repository URL
- `from`: Branch name to work with (use `HEAD` for default branch)

#### Release Configuration
- `repositoriesToReleaseFrom`: Specific repositories to start the release from
- `gavs`: Collection of GAV coordinates to include in the release
- `versionIncrementType`: Type of version increment (PATCH, MINOR, MAJOR)
- `skipTests`: Whether to skip running tests during release
- `dryRun`: Whether to perform a dry run without making actual changes
- `runSequentially`: Whether to run operations sequentially instead of in parallel

### Advanced Configuration Examples

#### Multi-Environment Configuration
```yaml
baseDir: "/workspace"
gitConfig:
  username: "${GIT_USERNAME}"
  password: "${GIT_TOKEN}"
mavenConfig:
  settingsXml: "/maven/settings.xml"
  localRepo: "/maven/repo"
repositories:
  - url: "https://github.com/org/core-lib"
    from: "main"
  - url: "https://github.com/org/service-a"
    from: "develop"
  - url: "https://github.com/org/service-b"
    from: "develop"
repositoriesToReleaseFrom:
  - url: "https://github.com/org/core-lib"
    from: "main"
versionIncrementType: "MINOR"
skipTests: false
dryRun: false
runSequentially: true
```

#### Selective Release Configuration
```yaml
baseDir: "/workspace"
gitConfig:
  username: "release-bot"
  password: "${GIT_PASSWORD}"
mavenConfig:
  settingsXml: "/maven/settings.xml"
repositories:
  - url: "https://github.com/org/library-a"
    from: "main"
  - url: "https://github.com/org/library-b"
    from: "main"
  - url: "https://github.com/org/service-x"
    from: "develop"
  - url: "https://github.com/org/service-y"
    from: "develop"
repositoriesToReleaseFrom:
  - url: "https://github.com/org/library-a"
    from: "main"
gavs:
  - "org.example:library-a:1.2.0"
  - "org.example:library-b:2.1.0"
versionIncrementType: "PATCH"
dryRun: true
```

### Environment Variables

The tool supports environment variable substitution in configuration files:

- `${GIT_USERNAME}`: Git username
- `${GIT_PASSWORD}`: Git password or token
- `${MAVEN_SETTINGS}`: Path to Maven settings file
- `${WORKSPACE_DIR}`: Workspace directory path

### Configuration Validation

The tool validates configuration files before execution:

- Repository URLs must be valid Git URLs
- Branch names must exist in the repositories
- Maven settings file must be accessible
- GAV coordinates must follow Maven naming conventions
- Version increment types must be valid (PATCH, MINOR, MAJOR) 