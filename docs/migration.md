# Application source scope and compatibility

This repository contains the full desktop application source, canonical design kit, tests, fixtures, architecture, curated research, and local build inputs. Maven and npm manifests, lockfiles, code and asset generators, and development/test scripts are included so contributors can build from a clone.

Website page templates, marketing compositions, and website guide generators are outside the application design kit. Its reference pages demonstrate the desktop application and reusable UI.

Internal AI work, implementation progress logs, local editor settings, installed dependencies, generated runtime homes, and operational CI/release workflows are excluded. Test-service credentials are deliberately public, disposable fixtures; generated certificates and private keys stay in ignored output directories.

## Compatibility

The import preserves the Java package namespace `ai.msrv.gaterook`, dependency versions, resource paths, tests, and application behavior. Some runtime identifiers still use `mcp-gateway`, including npm workspace names, development/runtime paths, and the deep-link scheme. The GateRook naming decision is documented in [product decisions](DECISIONS.md); do not assume the remaining rename has been implemented or silently rewrite stored user data.

The design kit remains the canonical source of tokens, components, fonts, and brand assets. The UI consumes it through a local npm package and an asset synchronization script. Keep third-party notices with their assets. No new application license is selected by this import.

## Maintaining the public source tree

- Keep all runtime and build dependencies resolvable from repository-relative paths.
- Keep documentation in English, with links to actual source and commands.
- Publish architectural decisions and curated findings, not chronological task logs.
- Place new temporary AI files and research in root `ai/`, and never commit them.
- Verify source completeness, ignore behavior, documentation links, and the documented build before publishing changes.
- Treat JVM, native image, signed installer, and release publication checks as distinct outcomes.
