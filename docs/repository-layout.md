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

This is the application's independent public Git repository. It can be opened on its own or placed next to private `gaterook-platform` in a shared local directory. A standalone public clone is not a complete build environment; build configuration and execution belong to the private platform.

## Responsibilities

| Area | Owner |
| --- | --- |
| Application runtime sources: UI, core, connectors, plugins, and bridges | Public `gaterook` |
| Canonical design-kit sources: tokens, components, reusable assets, and source-facing rules | Public `gaterook` |
| Test and fixture sources | Public `gaterook` |
| Source-facing architecture and development documentation | Public `gaterook` |
| All build/dependency manifests, lockfiles, and toolchain pins | Private `gaterook-platform` |
| Code and asset generation, design-kit build tooling, and validation runners | Private `gaterook-platform` |
| Maven/npm/Electron build configuration, CI workflows, and runners | Private `gaterook-platform` |
| Staging, packaging, signing, notarization, and installer verification | Private `gaterook-platform` |
| Website, release publication, download distribution, Ansible, and deployment | Private `gaterook-platform` |
| All operational key and secret management | Private `gaterook-platform` |

## Source layout after migration

The following outline is based on the existing implementation. **These directories have not been migrated yet.** Determine the final layout from the actual files during migration; do not create placeholders merely to match this outline.

| Source area | Public contents |
| --- | --- |
| Java source directories | Core, application, API, connectors, plugins, plugin host, and CLI bridge sources; no Maven build manifests |
| `ui/` | Angular interface source and runtime assets; no dependency or build configuration |
| `desktop/` | Electron main/preload source and runtime assets; packaging configuration and packaging-only assets belong to the platform |
| `bridge/`, `bridge-cli/` | Client connection source |
| `design/` | Canonical tokens, component source, reusable assets, and source-facing documentation; no generators or build/validation tooling |
| Test and fixture directories | Test source and fixture data; service orchestration and execution tooling belong to the platform |
| `catalog/` | Runtime extension descriptions and related assets |
| `docs/` | Public architecture, source-level decisions, and source-facing development instructions |

Review mixed files and directories individually. Runtime metadata may remain public when required by application code, while build configuration belongs to the private platform. Do not retain `pom.xml`, dependency-bearing `package.json` files, lockfiles, toolchain pins, generators, build scripts, or CI workflows here merely to preserve the former directory layout. Separate mixed runtime/build metadata during migration without changing application behavior.

## Source-to-build contract

The private platform selects an exact public application commit SHA, checks out its source, and combines it with a selected platform revision in a reproducible build context. The assembly method is not implemented yet and will be documented during migration. It must avoid dependence on uncommitted sibling files or the old source workspace.

The platform is the sole implementation of building and validation. It does not invoke a separate public application build workflow, and it must not maintain another editable copy of public application or kit sources.

The private release process must preserve the application updater contract and verify release compatibility against the exact source and platform revisions. Detailed build and release procedures, artifact requirements, and operational documentation belong to the platform.

The website consumes assets built privately from an explicitly selected public design-kit source revision. Shared tokens and components have one canonical source in `gaterook`; generators, dependency configuration, and distribution tooling reside in `gaterook-platform`.
