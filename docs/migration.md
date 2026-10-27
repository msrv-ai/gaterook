# Future application and design-kit migration

**Status: migration has not started.** This document records observed context and required checks. It does not confirm that sources, successful builds, or a release-ready implementation exist in this repository.

## Existing implementation context

The application to be migrated uses Electron, Angular, and Java/Spring Boot; its core is packaged as a GraalVM native image. npm groups `ui`, `desktop`, and `bridge`, while the design kit is a separate package. The UI references it through `file:../design` within the same repository.

The Java portion uses a Maven reactor and includes the core, connectors, API, plugins, plugin host, and CLI bridge. The observed configuration uses Java 25 and Node 24. Exact versions must come from the actual manifests and version-pinning files, not from this description.

Existing staging includes the UI, native core and SQLite library, jlink JRE, plugin-host JAR, bridges, and a pinned version of cloudflared. The Electron Builder configuration includes DMG/ZIP for macOS, NSIS EXE for Windows, and AppImage/DEB for Linux.

The existing release workflow defines five targets: Linux x64/arm64, macOS x64/arm64, and Windows x64. It runs on tags or manual dispatch, retains installers, blockmaps, and update manifests, merges macOS manifests, and creates a draft GitHub Release. Signing and notarization depend on CI secrets; unsigned or ad-hoc builds are supported when those secrets are absent. The existence of these settings does not prove that all platforms have passed verification.

## What to preserve during migration

- Sources, Maven modules, npm packages, lockfiles, and exact tool versions.
- Tests, fixtures, native-image metadata, local test services, and related checks.
- Build, staging, packaging, signing configuration, and dependencies required by the installer.
- Design tokens, SCSS, components, fonts, icons, brand assets, generators, inventory, and local instructions.
- Current architectural decisions and development documentation, after reviewing their suitability for a public repository.
- Third-party licenses and notices. Do not select the product's license automatically.

Do not migrate live user data, secrets, local IDE settings, caches, installed dependencies, or build outputs as source code. Identify generated assets that are already versioned parts of the kit separately; do not exclude them with a blanket rule for all `dist` directories.

Translate imported documentation, comments, descriptions, and other authored prose into English while preserving their technical meaning. The English-only repository rule also applies to files brought in from the migration source.

## Separating the website from the kit

The existing kit contains both shared UI assets and the public website: the promotional page, documentation content, page-generation and static-export scripts, and download-button behavior. The current website export produces `site-dist/`; this is build output, not the canonical source.

During migration:

1. Keep canonical tokens, shared components, assets, and kit checks in `gaterook`.
2. Move public pages, content, and the website build to `gaterook-platform`, preserving provenance and required licenses.
3. Define a reproducible way to deliver a specific kit version to the platform. Do not create a second editable set of identical components.
4. Check generator, SCSS, asset, inventory, and link dependencies after the split. Do not assume that copying a single HTML page is sufficient to separate the website.
5. Check that the public export does not accidentally include private material or reference application screens.

## Auditing names, identifiers, and URLs

The implementation to be migrated still contains legacy technical names and product labels. Assess compatibility separately for each change:

- Display name, package metadata, icons, copyright, and installer name.
- `appId`, Windows AUMID, deep-link scheme, Java packages, and application identifiers.
- User-data paths, client settings, startup behavior, and updates for existing installations.
- GitHub owner/repository, update-provider URL, and documentation, website, and download links.
- Tags, version numbers, artifact names, and update metadata.

Use **GateRook** as the product name and `gaterook` as the technical slug. Do not perform a global string replacement without checking references and behavior. Until real installers have been published, the website must not present missing downloads as available.

## Planned sequence

1. Record the selected source revision in the migration work plan and review the file inventory; do not disclose private details in public documentation.
2. Migrate the application, kit, and required build dependencies while preserving structure and licenses.
3. Separate website and shared-asset responsibilities, and document the platform contract.
4. Configure names, URLs, and CI for the new repository; verify user-data and update compatibility.
5. Add real development and verification commands to `README.md` and `AGENTS.md` based on the migrated manifests.
6. Verify a standalone application clone, followed by the platform's associated artifact-retrieval process.

## Migration completion criteria

- The build does not read files from a sibling repository or the old working directory.
- Java, UI, Electron, and design-kit checks have been performed for the migrated changes, with platform limitations listed explicitly.
- Installer runtime assets, license files, and native-image metadata have been preserved.
- Artifacts and installation have been verified for supported platforms; signing and notarization status are stated accurately.
- Documentation explains how the platform selects a revision, triggers the build, and obtains a consistent artifact set.
- Public documentation reflects the actual state; the preparatory status is removed only after migration is complete.

The build and these checks are unavailable until sources have been migrated. The current preparation can be verified through documentation links, the file inventory, and `git diff --check`.
