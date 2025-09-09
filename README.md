# Qubership Core Bulk Release

A comprehensive toolkit for automating bulk releases of Maven projects with advanced dependency management and version synchronization across multiple repositories.

## Overview

This repository contains tools for managing complex multi-repository release scenarios where projects have interdependencies. It provides automated version management, dependency resolution, and release coordination across dependency graphs of Maven projects.

## Components

### 🚀 Maven Bulk Release
The core library for automating bulk releases of Maven projects with dependency management and version synchronization.

**Key Features:**
- **Dependency Graph Analysis**: Builds directed dependency graphs to determine optimal release order
- **Version Management**: Supports PATCH, MINOR, and MAJOR version increments
- **Parallel Processing**: Concurrent repository processing within dependency levels
- **Error Handling**: Graceful failure handling with automatic rollback
- **Dry Run Mode**: Test release processes without making actual changes

### 📋 Maven Bulk Release CLI
Command-line interface for the Maven Bulk Release tool, providing easy access to bulk release functionality.

### 🔍 Maven Effective Dependencies CLI
Tool for analyzing effective dependencies across Maven projects, including conflict detection and dependency impact analysis.

### ⚙️ Renovate Config CLI
Command-line tool for managing Renovate configuration files, helping with dependency update automation.

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.6+
- Git

### Building the Project
```bash
mvn clean install
```

### Using the CLI Tools

#### Maven Bulk Release
```bash
java -jar maven-bulk-release-cli/target/maven-bulk-release-cli-*-jar-with-dependencies.jar --config config.yaml
```

#### Maven Effective Dependencies
```bash
java -jar maven-effective-dependencies-cli/target/maven-effective-dependencies-cli-*-jar-with-dependencies.jar --config config.yaml
```

#### Renovate Config
```bash
java -jar renovate-config-cli/target/renovate-config-cli-*-jar-with-dependencies.jar --config renovate.json
```

## Configuration

The tools use YAML configuration files to define:
- Repository URLs and branches
- Git authentication credentials
- Maven settings and local repository paths
- Version increment types (PATCH, MINOR, MAJOR)
- Release parameters and options

For detailed configuration examples, see the [Advanced Usage](docs/advanced-usage.md) documentation.

## Documentation

- [Maven Bulk Release Guide](docs/maven-bulk-release.md) - Comprehensive guide to the bulk release functionality
- [Advanced Usage](docs/advanced-usage.md) - Configuration options and advanced features
- [Multi-Repository Release Example](docs/multi-repo-release-example.md) - Detailed examples of version transformations

## Features

### 🔗 Dependency Management
- Automatic dependency graph construction
- Topological ordering for release sequence
- Inter-repository dependency resolution
- Conflict detection and resolution

### 🏷️ Version Control
- Semantic versioning support
- Automated version increments
- Git tag management
- Branch-specific operations

### 🔄 Release Process
- Two-phase release (Prepare/Perform)
- Maven validation and testing
- Artifact deployment
- Rollback capabilities

### 🛡️ Safety Features
- Dry run mode for testing
- Comprehensive error handling
- Detailed logging and monitoring
- Thread-safe operations

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.

## Support

For questions and support, please refer to the documentation or open an issue in the repository. 