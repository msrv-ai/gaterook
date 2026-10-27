# Application and design-kit source migration

**Status: not started.** This checklist describes preparation for adding application source. It does not establish that runtime code, verified builds, or installers are available in this repository.

## Source scope

The application uses Electron, Angular, and Java/Spring Boot with a GraalVM native core. Its source includes the UI, desktop main/preload code, core, connectors, plugins, plugin host, and client bridges. The design kit contains canonical tokens, component sources, reusable assets, and interface rules.

Include:

- Application runtime source, required runtime metadata, and runtime assets.
- Canonical design-kit source and reusable assets.
- Test implementation and sanitized fixtures.
- Source-facing architecture, decisions, and design instructions.
- Required third-party licenses and notices.

Review mixed directories file by file. Exclude tooling, dependency/build manifests, lockfiles, generators, CI configuration, packaging and deployment configuration, credentials, local user data, caches, and installed dependencies. Preserve required generated runtime assets when their use has been verified.

Translate imported documentation, comments, and descriptions into English without changing technical meaning. Preserve third-party notices and do not choose the product's license automatically.

## Design-kit continuity

1. Keep tokens, component sources, reusable assets, and design rules together.
2. Reuse existing components and semantic tokens when adapting the application UI.
3. Preserve source references, SCSS relationships, asset paths, and the component inventory.
4. Keep a single canonical set of reusable design sources and avoid manually editing generated assets.

## Compatibility review

Check each identifier and URL individually before renaming it:

- Product display names, icons, and copyright notices.
- Application IDs, Windows AUMID, deep-link schemes, Java packages, and runtime identifiers.
- User-data locations, saved client settings, startup behavior, and update compatibility.
- Public documentation, website, download, and update URLs.

Write the product name as **GateRook** and the technical slug as `gaterook`. Do not perform a global replacement without checking behavior and references.

## Migration checklist

1. Review the source inventory and classify files by purpose.
2. Add the selected application, design, test, and fixture sources while preserving their relationships.
3. Review all added content against the publication rules in [AGENTS.md](../AGENTS.md).
4. Verify runtime resources, existing test coverage, identifiers, data compatibility, and updater behavior.
5. Check source documentation, links, asset licenses, and the final file inventory.
6. Update the repository layout and status to match the actual contents. Record only verification that has been completed.

The migration is complete when the intended source is present, compatibility and resource checks are recorded, documentation reflects the actual state, and the published files satisfy the repository's language and publication rules.

Until sources are added, verification is limited to documentation links, the file inventory, and `git diff --check`.
