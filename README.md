# GateRook

GateRook is a desktop MCP gateway for connecting AI to databases, logs, APIs, and cloud services, with scoped access and human-reviewed changes.

[Website](https://msrv.dev) · [Documentation](https://msrv.dev/docs/) · [Issues](https://github.com/msrv-ai/gaterook/issues)

**Status: in development.** This repository currently contains preparatory documentation. Application sources and the design kit have not been added yet. Installers are not available yet.

## Repository contents

This repository is intended to contain application runtime source, canonical design-kit tokens and components, reusable assets, test sources and fixtures, and source-facing documentation.

The planned application uses Electron, Angular, Java/Spring Boot, and a GraalVM native core. These describe the implementation being prepared, not code already present here.

## Working on GateRook

Start with [AGENTS.md](AGENTS.md). All AI-authored repository content, including documentation, comments, descriptions, and commit messages, must be in English.

| Document | Purpose |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Instructions for contributors and AI agents |
| [Repository layout](docs/repository-layout.md) | Current contents and planned source layout |
| [Source migration](docs/migration.md) | Application and design-kit migration checklist |

There are no application build or runtime verification commands in this documentation scaffold. Update the documentation as source becomes available and report only checks that have actually run.

The application's license will be established during source migration. Preserve third-party asset licenses and notices; repository visibility alone does not grant a license.
