# Future application and design-kit migration

**Status: migration has not started.** This document records observed context and required checks. It does not confirm that sources, successful builds, or a release-ready implementation exist in this repository.

## Existing implementation context

The application to be migrated uses Electron, Angular, and Java/Spring Boot, with a GraalVM native core. Its source includes the application UI, desktop main/preload code, core, connectors, plugins, plugin host, and client bridges. The design kit contains shared tokens, components, and reusable assets.

The existing project mixes these sources with build configuration, generators, validation tooling, packaging, and release automation. Preserve runtime behavior while separating the files by ownership. Detailed build context, toolchain versions, runner targets, staging, signing behavior, and release procedures belong in the private platform's documentation.

## Migration destinations

| Keep in public `gaterook` | Move to private `gaterook-platform` |
| --- | --- |
| Application runtime source and runtime assets | All build/dependency manifests, lockfiles, and toolchain pins |
| Canonical design tokens, component sources, and reusable assets | Code and asset generators, design-kit build tooling, and validation runners |
| Test source and fixture data | Test execution tooling, local test-service orchestration, and CI workflows/runners |
| Source-facing architecture, decisions, and design instructions | Maven/npm/Electron build configuration, native-image build metadata, staging, and packaging |
| Required source and asset license notices | Signing/notarization, installer checks, publication, deployment, and operational key management |

Preserve the implementation and its dependencies across this separation. The private platform is the sole build implementation; the public repository is the canonical application and kit source. Do not promise that a standalone public clone can build the application.

Review individual files in mixed directories before moving them. Separate runtime metadata from build metadata, source assets from packaging-only assets, and test sources from test execution tooling. Some generated kit assets may already be versioned source dependencies; assess them explicitly rather than deleting every `dist` directory. All generators and build-related configuration still belong to the private platform.

Do not migrate live user data, keys, secrets, local IDE state, caches, installed dependencies, or build outputs into the public source repository. All operational key and secret management, including its documentation, belongs to the private platform. Never commit plaintext secrets. This preparation does not transfer any keys.

Translate imported documentation, comments, descriptions, and other authored prose into English while preserving their technical meaning. Preserve third-party licenses and notices, and review documentation before publication. Do not select the product's license automatically.

## Separating the website from the kit

The existing kit contains both shared UI assets and the public website: the promotional page, documentation content, page-generation and static-export scripts, and download-button behavior. Website export output is a build artifact rather than canonical source.

During migration:

1. Keep canonical tokens, component sources, reusable assets, and source-facing design rules in public `gaterook`.
2. Move website pages/content and all website, kit-generation, build, and validation tooling to private `gaterook-platform`, preserving provenance and licenses.
3. Define a reproducible private build context that consumes an exact public kit source revision and produces application and website assets. Do not create a second editable set of shared components.
4. Verify generator, SCSS, asset, inventory, and link relationships after the split. Separate any mixed source/build inventory metadata according to its actual use.
5. Check that public source and website exports do not accidentally include private build configuration or operational material.

## Auditing names, identifiers, and URLs

The implementation to be migrated still contains legacy technical names and product labels. Assess compatibility separately for each change:

- Display name, package metadata, icons, copyright, and installer name.
- `appId`, Windows AUMID, deep-link scheme, Java packages, and application identifiers.
- User-data paths, client settings, startup behavior, and updates for existing installations.
- GitHub owner/repository, update-provider URL, and documentation, website, and download links.
- Tags, version numbers, artifact names, and update metadata.

Use **GateRook** as the product name and `gaterook` as the technical slug. Do not perform a global string replacement without checking references and behavior. Preserve the application updater contract when integrating with the private publication process; detailed artifact and delivery requirements belong to the platform. Until real installers have been published, the website must not present missing downloads as available.

## Planned sequence

1. Record the selected source revision in the private migration work plan and review the file inventory; do not disclose private details in public documentation.
2. Classify each source, asset, manifest, generator, validation tool, and mixed file by the ownership rules above.
3. Migrate public runtime/kit/test sources and source-facing documentation into `gaterook`; move all build, validation, packaging, and operational tooling/configuration into `gaterook-platform`.
4. Implement the private build context using an exact public application commit SHA and a selected private platform revision. Document source layout and configuration assembly without relying on uncommitted local files.
5. Audit application names, identifiers, and URLs; verify user-data and updater compatibility. Keep CI and operational configuration in the private platform.
6. Run private validation, build, packaging, and release checks against the selected source revision. Keep actual commands and toolchain pins in the private platform, and update public docs to reflect the source boundary.

## Migration completion criteria

- Public `gaterook` contains canonical runtime/kit/test sources and source-facing documentation, with no build manifests, dependency lockfiles, generators, validation runners, CI workflows, or operational keys.
- Private `gaterook-platform` contains the complete build and validation implementation and assembles a reproducible context from an exact public source SHA and selected private revision.
- The build does not depend on uncommitted sibling files or the old working directory, and public source is not maintained as a duplicate editable copy in the platform.
- Java, UI, Electron, and design-kit checks have run through private tooling, with platform limitations listed explicitly.
- Runtime assets, licenses, native-image behavior, and the application updater contract have been preserved.
- The private platform has verified builds and installation for supported targets and documented release readiness in its own records.
- Public documentation reflects actual source ownership and build-access requirements; the preparatory status is removed only after migration is complete.

Application builds and runtime checks are unavailable until sources and private tooling have been migrated. The current preparation can be verified through documentation links, ownership consistency, the file inventory, and `git diff --check`.
