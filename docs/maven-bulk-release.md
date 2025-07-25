# Maven Bulk Release

A comprehensive tool for automating bulk releases of Maven projects with dependency management and version synchronization across multiple repositories.

## Overview

The Maven Bulk Release tool is designed to handle complex multi-repository release scenarios where projects have interdependencies. It automatically manages version increments, dependency updates, and release coordination across a dependency graph of Maven projects.

## Main Operating Principles

### 1. Dependency Graph Analysis

The tool builds a directed dependency graph of all repositories to understand the release order:

- **Repository Discovery**: Clones and analyzes all configured repositories
- **Dependency Resolution**: Parses POM files to identify inter-repository dependencies
- **Graph Construction**: Creates a topological ordering of repositories based on dependencies
- **Level-based Processing**: Releases repositories in dependency order (leaf nodes first, then parents)

### 2. Version Management

Supports three types of version increments:

- **PATCH**: Increments the patch version (e.g., 1.0.0 → 1.0.1)
- **MINOR**: Increments the minor version (e.g., 1.0.0 → 1.1.0)
- **MAJOR**: Increments the major version (e.g., 1.0.0 → 2.0.0)

### 3. Release Process

The release process follows a two-phase approach:

#### Phase 1: Prepare
- Updates dependencies to use new versions from other repositories
- Increments versions according to the specified increment type
- Commits changes to Git (only to local repo, does not pushes to remote repository)
- Runs Maven validation (compile, test)

#### Phase 2: Perform
- Creates Git tags for the release
- Pushes changes and tags to remote repositories
- Handles rollback on failure
- Deploys artifacts to configured Maven repositories
- Handles deployment failures gracefully

### 4. Configuration-Driven

The tool is fully configuration-driven through YAML configuration files. For detailed configuration options and examples, see [Advanced Usage](advanced-usage.md).

### 5. Parallel Processing

- **Concurrent Repository Processing**: Multiple repositories can be processed in parallel within the same dependency level
- **Configurable Threading**: Supports both sequential and parallel execution modes
- **Thread Safety**: Uses thread-safe operations for Git and Maven operations

### 6. Error Handling and Rollback

- **Graceful Failure Handling**: Individual repository failures don't stop the entire process
- **Automatic Rollback**: Failed releases are automatically rolled back
- **Detailed Logging**: Comprehensive logging for debugging and monitoring
- **Exception Tracking**: Captures and reports exceptions for each repository

### 7. Effective Dependencies Analysis

The tool includes sophisticated dependency analysis capabilities:

- **Effective POM Resolution**: Resolves effective dependencies including inheritance and dependency management
- **Conflict Detection**: Identifies version conflicts across repositories
- **Dependency Impact Analysis**: Shows which repositories consume specific artifacts
- **Visualization**: Generates DOT files for dependency graph visualization

### 8. Git Integration

- **Multi-Branch Support**: Works with different branches across repositories
- **Credential Management**: Supports various Git authentication methods
- **Tag Management**: Creates and manages release tags
- **Change Tracking**: Tracks and commits dependency updates

### 9. Maven Integration

- **Settings Configuration**: Uses custom Maven settings for repository access
- **Local Repository Management**: Configurable local Maven repository
- **Profile Support**: Supports Maven profiles for different environments
- **Plugin Execution**: Runs Maven goals for validation and deployment

### 10. Dry Run Mode

- **Validation Without Changes**: Allows testing the release process without making actual changes
- **Dependency Graph Preview**: Shows what the dependency graph would look like
- **Version Preview**: Displays what versions would be created
- **Conflict Detection**: Identifies potential issues before actual release


This tool provides a robust solution for managing complex multi-repository release scenarios while ensuring consistency and reliability across the entire dependency graph.

## Examples

For detailed examples of how the tool handles version transformations in multi-repository projects, see [Multi-Repository Release Example](examples/multi-repo-release-example.md). 