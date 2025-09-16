# Go Bulk Release CLI

A command-line tool for performing **bulk releases of interdependent Go libraries**.  
It automates dependency resolution, release preparation, and artifact publishing across multiple repositories.

## Prerequisites
- Java 21+
- Maven 3.6+
- Git
- Go 1.24+
- [go-semantic-release v2.31.0+](https://github.com/go-semantic-release/semantic-release)
- [go-pack v1.1.4+](https://github.com/taurmorchant/go-pack)
- [gomajor v0.15.0+](https://github.com/icholy/gomajor)

## Features
- Bulk release of multiple related Go repositories.
- Supports partial releases starting from changed repositories.
- Generates dependency graphs (DOT format).
- Saves release results, summaries, and GAV coordinates.
- Optional dry-run mode (no Git push / deployment).
- Skippable test execution.

## Usage

```bash
java -jar go-bulk-release-cli.jar  \
  --gitURL https://git.example.com  \
  --gitUsername user   \
  --gitEmail user@example.com   \
  --gitPassword pass   \
  --baseDir /tmp/releases   \
  --repositories repo1,repo2,repo3   \
  [--repositoriesToReleaseFrom repo2]   \
  [--goProxyDir /tmp/GOPROXY]   \
  [--skipTests]   \
  [--dryRun]   \
  [--summaryFile summary.txt]   \
  [--resultOutputFile results.txt]   \
  [--dependencyGraphFile deps.dot]   \
  [--gavsResultFile gavs.yaml]
```

## Options

| Option | Required | Description |
|--------|----------|------------|
| `--gitURL` | ✔ | Git host URL |
| `--gitUsername` | ✔ | Git username |
| `--gitEmail` | ✔ | Git email |
| `--gitPassword` | ✔ | Git password |
| `--baseDir` | ✔ | Base directory to store release results |
| `--repositories` | ✔ | Comma-separated list of interdependent repositories |
| `--repositoriesToReleaseFrom` | ✖ | Comma-separated list of repositories changed (transitive dependents also released) |
| `--goProxyDir` | ✖ | Directory for Go proxy cache (default: `/tmp/GOPROXY`) |
| `--skipTests` | ✖ | Skip tests |
| `--dryRun` | ✖ | Run release prepare only, no Git push / deploy |
| `--summaryFile` | ✖ | File path to save release summary |
| `--resultOutputFile` | ✖ | File path to save resulting GAVs |
| `--dependencyGraphFile` | ✖ | Save dependency graph in DOT format |
| `--gavsResultFile` | ✖ | Save GAV results (YAML/other format) |

## Exit Codes
- `0` – Success
- Non-zero – Failure  

## Design
You can read more about the app [Design Specification](https://github.com/netcracker/qubership-core-bulk-release/blob/main/go-bulk-release-cli/Design.md)