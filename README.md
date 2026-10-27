# GateRook

The repository for the GateRook desktop application and everything required to build, verify, and package it.

**Status: documentation scaffolding is ready. Application sources and the design kit have not been migrated yet.** This repository does not yet contain build commands or working CI workflows. Migration is a separate task.

All repository documentation, comments, descriptions, and other AI-authored content must be **English only**. See [AGENTS.md](AGENTS.md) for the full rule.

## Repository responsibilities

This repository will contain the application code, canonical design kit, tests and fixtures, pinned dependencies, build scripts, installer packaging, and application-signing workflows. It must support building from a standalone clone without depending on a neighboring working directory.

The public website, Ansible infrastructure, platform-initiated application builds, and distribution of completed installers belong to [gaterook-platform](https://github.com/msrv-ai/gaterook-platform). Access to that repository may require separate permissions. It is not required to read these instructions or build the application once migration is complete.

## Documentation

| Document | Purpose |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Self-contained instructions for AI agents and developers |
| [docs/repository-layout.md](docs/repository-layout.md) | Repository boundaries and the proposed structure after migration |
| [docs/migration.md](docs/migration.md) | Existing implementation context and the future migration plan |

## Planned implementation

The implementation to be migrated uses Electron, Angular, Java/Spring Boot, and a GraalVM native core. Its design kit contains tokens, components, assets, and interface rules. These details describe migration context, not the current contents of this repository.

Target installers include DMG for macOS and EXE for Windows. The existing configuration also includes Linux packages and several architectures; the final supported matrix must be confirmed during migration and build verification.

## Working with the repository now

Clone `https://github.com/msrv-ai/gaterook.git` and start with `AGENTS.md`. At this stage, review documentation, ownership boundaries, and the absence of unintended artifacts. Run instructions and exact tool versions will be added alongside actual manifests and verified commands.

The application's license must be established separately during migration; public repository visibility does not determine it. Preserve notices and licenses for third-party assets.
