# Working on GateRook

This file is self-contained: following it does not require access to the parent workspace or related private repositories. The user's task determines the current scope; more specific `AGENTS.md` files in subdirectories refine the rules for those areas.

## Repository language

All AI-authored repository content must be **English only**. This includes documentation, README and AGENTS files, code comments, docstrings, TODOs, descriptions, explanatory text in configuration files and scripts, changelogs, release notes, commit messages, and pull request or issue titles, descriptions, and comments.

This requirement applies even when the user communicates in another language. Do not introduce mixed-language prose or parallel Russian documentation. Translate imported documentation and comments into English during future migration while preserving technical meaning, identifiers, paths, and behavior. Chat replies may follow the user's language; repository artifacts must remain English.

## Current state

The repository contains only preparatory documentation and text-file settings. The application, design kit, dependencies, build system, and CI have not been migrated. Do not present the proposed structure as an existing implementation or add fictitious commands, packages, or successful check results.

Application and design-kit migration is a separate task. Until it is requested, improve the preparation without copying sources or creating empty directories that imply a working application.

## Before making changes

1. Read `README.md`, `docs/repository-layout.md`, and the relevant sections of `docs/migration.md`.
2. Check the current branch, `git status`, and `origin`. The expected repository is `https://github.com/msrv-ai/gaterook`.
3. Preserve existing user changes. Do not clean the working directory or rewrite history to prepare for a task.
4. Once sources are present, read the local instructions for affected directories and the actual manifests before choosing commands.

## Ownership boundaries

- This repository owns the entire application, canonical design kit, tests, fixtures, lockfiles, build scripts, packaging, signing, and installer verification.
- `msrv-ai/gaterook-platform` owns the public website, Ansible, application-build orchestration, and publication of completed downloads.
- The application build must be reproducible from a standalone `gaterook` clone. Do not introduce dependencies on `../gaterook-platform`, parent-workspace scripts, or machine-specific absolute paths.
- The platform invokes the application's defined build process at an explicitly selected revision. Do not create a second implementation of building and packaging in the platform.
- Describe shared-contract changes explicitly: application revision, version, OS, architecture, artifact names, checksums, and required updater metadata. Do not assume a neighboring branch is automatically compatible.
- Commits and checks are independent in the two repositories. For coordinated tasks, report changes and results separately for each repository.

## Design and migration

The application owns the canonical design kit. After migration, read its `AGENTS.md`, documentation, inventory, and generation rules before changing any UI. Use existing tokens and components; do not create a parallel set of website styles.

The source implementation contains the website and some of its generators inside the design kit. During migration, separate public content and website export from shared components according to their purpose. Document and verify how a version of the kit is delivered to the platform; do not replace this process with undocumented copies or a dependency on a local sibling directory.

Do not edit generated files manually. Preserve generators, lockfiles, notices, and asset licenses. Write the brand as **GateRook** and the technical slug as `gaterook`. Analyze compatibility before changing application identifiers, protocols, user-data paths, or update URLs.

## Public visibility and data

This is a public repository. Do not add secrets, signing keys, real user configurations, private infrastructure addresses, absolute local paths, or identifiers of the private migration source. Examples must use fictional values and clearly identified variables.

Keep signing secrets in the designated CI secret store; do not copy them into documentation or neighboring repositories. Do not publish an unsigned installer as a verified official release. Server-deployment details belong to the platform.

## Verification and reporting

For now, check links, the accuracy of the documented structure, and `git diff --check`. Building the application is impossible until manifests and sources have been migrated; state that clearly.

After migration, choose checks based on the changed areas and actual manifests. Distinguish an existing workflow from a successful build, signature verification, and actual publication. Report changed files, checks performed, and remaining limitations. Update these instructions alongside the actual structure and commands.
