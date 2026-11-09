# Working on GateRook

## Language

All documentation and AI-authored repository content must be English only, including comments, docstrings, configuration explanations, commit messages, and pull request text. Translate imported prose without changing identifiers, technical meaning, or behavior. Preserve intentional Unicode test data. Do not edit existing Flyway migrations solely to translate comments: their exact bytes are part of the schema-history checksum contract. Chat replies may follow the user's language.

## Before making changes

Read `README.md`, `docs/repository-layout.md`, and the instructions for affected directories. Check the working tree, branch, and origin (`https://github.com/gaterook/gaterook`). Preserve existing changes and do not rewrite history or clean unrelated files.

## Release branches after AI work

Before finishing work in an AI-managed linked worktree, release its checked-out branch so other worktrees can use it. This also applies after checking out `develop`, `main`, or another target branch for an authorized merge.

- Keep completed commits reachable from the task branch or the authorized merge target, then run `git switch --detach` at the current commit.
- Verify with `git status` and `git worktree list --porcelain` that this worktree is detached and no longer holds the branch.
- Preserve all commits and uncommitted changes. Do not reset, force checkout, delete branches, or modify another worktree to release a branch. If detaching is blocked, report the blocker without discarding work.
- Leave a branch checked out only when the user explicitly asks for it. Do not change the user's primary checkout solely for this cleanup.

## Product addresses

Use `https://gaterook.com` for the application website, documentation links, OAuth metadata and update services. Public support is `support@gaterook.com`. Preserve technical application IDs and Java package names; a website-domain change is not a package-identity rename.

## Repository scope

This repository contains the complete application source, tests and fixtures, runtime assets, architecture and curated research, and the manifests, lockfiles, toolchain pins, and scripts required to build, test, and run locally. Keep these inputs together so a clone builds without another checkout. Deployment credentials and production operations do not belong here.

Do not add production release orchestration or production installer workflows to this repository. Keep local development builds and reusable build inputs independent of release operations.

Preserve runtime behavior, dependency relationships, resources, and third-party notices. Use **GateRook** as the product name and `gaterook` as its slug. Existing Java packages, protocol names, persisted paths, application IDs, and client settings are compatibility contracts: do not rename them indiscriminately. Do not choose or replace the application license without an explicit instruction.

## Internal AI work

- Put temporary files, scratch scripts, investigation notes, research drafts, task plans, and progress logs under the repository-root `ai/` directory.
- **Never commit anything in `ai/`. Never force-add it, remove its ignore rule, or move internal AI material elsewhere to commit it.** Verify the staged file list before every commit.
- Use `/ai/` in `.gitignore`, not `ai/`: Java source packages named `ai` must remain tracked.
- `docs/research/` contains the curated product research explicitly requested for publication. New working research belongs in `ai/`; create or update public documentation only when it is an intentional deliverable, after removing internal notes and sensitive information.
- Build tools may use their established ignored output directories. Do not commit generated runtime homes, logs, private keys, installed dependencies, or build outputs.

## Design and documentation

Read `ui/README.md` and the UI architecture before changing the interface. Reuse existing Angular components, semantic CSS variables, and bundled runtime assets. Preserve keyboard behavior, accessible names, focus handling, and all four themes. Treat the compiled styles and bundled assets in `assets/` as a read-only distribution; do not introduce a second editable styling system.

Website page templates, marketing compositions, website guide content, and static-site generators are outside this application repository.

Keep README commands and links aligned with actual scripts. Record architectural contracts and research findings, not implementation progress logs. Do not publish internal repository names, private infrastructure, real user configurations, credentials, or personal filesystem paths. Examples must use fictional values or explicit placeholders.

## Verification

Run checks relevant to the change using the commands in `README.md` and `docs/development.md`. For UI changes, run `npm test -w ui` and `npm run build -w ui`. For documentation, check local links, English-only prose, file selection, ignore behavior, and `git diff --check`. Report checks actually completed and any limitations; a local build is not proof of a signed installer or published release.

For macOS packaging or updater changes, preserve the canonical `afterPack` hook: it creates update metadata and makes packaged resources owner-writable before signing. Keep JRE license notices, file contents, executable bits, symbolic links, and source runtimes intact. Run `npm test -w desktop` and `node desktop/scripts/verify-mac-update-permissions.mjs /path/to/GateRook.app` against the packaged app and a fresh extraction of its final updater ZIP. Every real file and directory needs the owner-write mode bit; a privileged process's ability to write is not sufficient. A permission-check failure blocks acceptance of that artifact. Successful signing or notarization does not prove that Squirrel can install it. Diagnose failures after restart using the ShipIt log documented in `docs/development.md`.
