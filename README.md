# GateRook

The public source repository for the GateRook desktop application and its canonical design kit.

**Status: documentation scaffolding is ready. Application sources and the design kit have not been migrated yet.** The private build system is also not implemented. Migration is a separate task.

All repository documentation, comments, descriptions, and other AI-authored content must be **English only**. See [AGENTS.md](AGENTS.md) for the full rule.

## Repository responsibilities

This repository will contain application runtime sources, canonical design-kit sources and reusable assets, test and fixture sources, and source-facing documentation.

Private [gaterook-platform](https://github.com/msrv-ai/gaterook-platform) owns everything related to builds, validation tooling, deployment, and operational key management: build/dependency manifests and lockfiles, toolchain pins, generators, Maven/npm/Electron configuration, CI, staging, packaging, signing, notarization, release publication, and deployment. It also owns the public website and download distribution.

A standalone clone of this public repository is **not a complete build environment**. The private platform will check out an exact public application commit SHA and assemble a build context using its private configuration. That process will be defined during migration. Canonical application and design-kit sources remain here; the platform does not maintain another editable copy of them.

## Documentation

| Document | Purpose |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Self-contained instructions for AI agents and developers |
| [docs/repository-layout.md](docs/repository-layout.md) | Public/private ownership and the proposed source layout |
| [docs/migration.md](docs/migration.md) | Existing implementation context and the future migration plan |

## Planned implementation

The implementation to be migrated uses Electron, Angular, Java/Spring Boot, and a GraalVM native core. Its design kit contains tokens, components, assets, and interface rules. These details describe migration context, not the current contents of this repository.

Target installers include DMG for macOS and EXE for Windows. The existing configuration also includes Linux packages and several architectures; the supported matrix must be confirmed by the private platform's build and release verification during migration.

## Working with the repository now

Clone `https://github.com/msrv-ai/gaterook.git` and start with `AGENTS.md`. At this stage, review documentation, ownership boundaries, and the absence of unintended artifacts. Build commands, dependency configuration, validation runners, and exact toolchain pins belong to the private platform and will not be added to this repository.

The application's license must be established separately during migration; public repository visibility does not determine it. Preserve notices and licenses for third-party assets.
