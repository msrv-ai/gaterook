# Structure and ownership

## Current contents

```text
gaterook/
├── AGENTS.md
├── README.md
├── .editorconfig
├── .gitignore
└── docs/
    ├── repository-layout.md
    └── migration.md
```

This is the application's independent Git repository. It can be opened on its own or placed next to `gaterook-platform` in a shared local directory. That shared directory must not become a required part of the build.

## Responsibilities

| Area | Owner |
| --- | --- |
| Application sources, UI, core, connectors, and bridges | `gaterook` |
| Canonical design kit, shared tokens, components, and brand assets | `gaterook` |
| Dependencies, tests, native build, packaging, and application signing | `gaterook` |
| Public website, content, and website build | `gaterook-platform` |
| Ansible and server deployment of the website | `gaterook-platform` |
| Triggering application builds at a selected revision, publishing installers, and download links | `gaterook-platform` |

## Structure after migration

The following outline is based on the existing implementation. **These directories have not been migrated yet.** Refine the list against the actual sources during migration; do not create placeholders merely to match this outline.

| Path | Expected contents |
| --- | --- |
| `pom.xml` and Maven modules | Java core, application, API, connectors, plugins, plugin host, and CLI bridge |
| `ui/` | Angular application interface |
| `desktop/` | Electron main/preload, packaging configuration, and assets |
| `bridge/`, `bridge-cli/` | Client connection bridges |
| `design/` | Canonical design kit, sources, inventory, generators, and instructions |
| `scripts/` | Application build, staging, checks, and maintenance |
| `test-services/` | Local services and fixtures for integration tests |
| `catalog/` | Application extension descriptions and related assets |
| `.github/workflows/` | Checks and installer builds, including signing |
| `docs/` | Architecture, decisions, and development instructions |
| Root npm manifests and lockfiles | Versions and relationships of application workspace packages |

A build script in `gaterook` does not imply automatic website publication. Application building and signing remain owned here; the platform invokes that process and distributes the results.

## Contract between repositories

The application provides a defined build process and its artifacts. The platform associates the result with an exact application revision and version, verifies the target OS/architecture and checksums, and then publishes the completed files.

Each release must define its set of installers and accompanying files: for example, DMG and EXE files, plus archives, blockmaps, and manifests required by the chosen update mechanism. The metadata format and exact commands will be established when the existing build is migrated. This repository currently has no executable contract or implemented release manifest.

The website must receive an explicitly selected version of shared design-kit assets. The delivery method has not been chosen yet. It must allow reproducible website builds while retaining one source of editable tokens and components.
