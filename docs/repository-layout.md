# Repository layout

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

The application and design-kit source directories have not been added yet.

## Planned source areas

Determine final paths from the application source during migration. Do not create empty directories merely to match this outline.

| Area | Contents |
| --- | --- |
| Java source directories | Core, API, connectors, plugins, plugin host, and client bridge source |
| `ui/` | Angular interface source and runtime assets |
| `desktop/` | Electron main/preload source and runtime assets |
| `bridge/`, `bridge-cli/` | Client connection source |
| `design/` | Canonical tokens, components, reusable assets, and design rules |
| Test and fixture directories | Test implementation and sanitized fixture data |
| `catalog/` | Runtime extension descriptions and related assets |
| `docs/` | Application architecture, source-level decisions, and contributor documentation |

Keep reusable design elements in one canonical source. Preserve licenses and notices alongside the relevant assets.

## File selection

Review files by purpose. Include runtime code, required runtime metadata, reusable design sources, tests, fixtures, and source-facing documentation. Exclude build configuration, generators, dependency locks, CI workflows, packaging and deployment tooling, credentials, caches, installed dependencies, and local user data.

Some generated design assets may be required runtime resources. Review their use before removing them; a directory name alone does not establish whether its contents belong here.

See [the migration checklist](migration.md) for source compatibility and completion criteria.
