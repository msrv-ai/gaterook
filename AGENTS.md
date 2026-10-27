# Working on GateRook

These instructions are self-contained. The user's task determines the scope; more specific `AGENTS.md` files refine the rules for their directories.

## Repository language

All AI-authored repository content must be **English only**. This includes documentation, README and AGENTS files, code comments, docstrings, TODOs, descriptions, explanatory text in configuration and scripts, changelogs, release notes, commit messages, and pull request or issue titles, descriptions, and comments.

This requirement applies regardless of the conversation language. Do not introduce mixed-language prose or parallel Russian documentation. Translate imported documentation and comments into English while preserving technical meaning, identifiers, paths, and behavior. Chat replies may follow the user's language.

## Current state

This repository contains preparatory documentation and text-file settings. Application source and the design kit have not been added. Do not describe planned directories as existing implementations or invent commands, packages, installers, or successful checks.

Source migration is a separate task. Do not copy source or create placeholder directories until requested.

## Before making changes

1. Read `README.md`, `docs/repository-layout.md`, and the relevant sections of `docs/migration.md`.
2. Check the branch, working tree, and origin. The expected repository is `https://github.com/msrv-ai/gaterook`.
3. Preserve user changes. Do not clean the working directory or rewrite history to prepare for a task.
4. Once sources are present, read the instructions for the affected directories.

## Source scope

- Keep application runtime source, canonical design-kit source and assets, test sources and fixtures, and source-facing documentation here.
- Do not add dependency/build manifests, lockfiles, toolchain pins, generators, build or validation runners, CI workflows, packaging configuration, or deployment automation to this repository.
- Review mixed directories file by file. Distinguish runtime metadata and assets from tooling and generated output.
- Preserve runtime behavior, test coverage, source relationships, and required asset licenses.
- Do not promise a standalone build or publish unverified commands.

## Design and compatibility

Use **GateRook** as the product name and `gaterook` as the technical slug. After migration, read the design kit's rules and inventory before changing UI. Reuse canonical tokens, components, and assets; do not create a second editable design kit or edit generated assets manually.

Assess compatibility before changing application identifiers, protocols, user-data paths, client settings, or update URLs. Preserve required runtime resources and third-party notices. Do not select or replace the product's license without an explicit instruction.

## Publication rules

Publish only application-facing information. Do not include internal project or repository names, links, locations, relationships, access requirements, or operational architecture in files, comments, commit messages, issues, pull requests, releases, or repository metadata.

Do not publish credentials, signing keys, real user configurations, infrastructure addresses, local filesystem paths, or internal source identifiers. Examples must use fictional values and clearly named placeholders.

## Verification and reporting

For this documentation scaffold, check local links, consistency with actual contents, publication rules, and `git diff --check`. Application builds and runtime checks are unavailable at this stage.

After source migration, report the exact source revision, checks actually performed, and remaining limitations. Distinguish source review from a verified build or published release. Keep all repository artifacts in English and limit public reports to this repository's source and user-facing behavior.
