# Local development

## Toolchains and dependencies

Use JDK 25, Maven 3.9+, and Node 24.22.1 with npm 11.19.1. The root `.nvmrc` pins Node. Set `JAVA_HOME` and put its `bin` directory on `PATH`; check `java -version`, `mvn -version`, and `node --version` before building. A macOS or Linux installation may have older toolchains earlier on `PATH`.

From the repository root:

```bash
npm ci
npm run build
npm start
```

The desktop build compiles a small native unlock helper on macOS and Windows. Install Xcode Command Line Tools with Swift on macOS. On Windows, install Visual Studio C++ build tools and the Windows 11 SDK, then build from a Developer Command Prompt targeting the same architecture as Node.js (x64 or arm64). The Windows Hello desktop interop requires a supported Windows version and configured Hello credentials; without them the helper checks the Windows account password through the system credential dialog. Linux keeps its existing Secret Service/KWallet integration and does not compile this helper.

The helper is cached by source and compiler identity under ignored `desktop/.dev/native-unlock/` and copied into `desktop/dist/native/`. Rebuilding unchanged sources keeps the same macOS Keychain identity. Packaged helpers live in the application's `resources/native/` folder and must be signed with the release. A local ad-hoc signature is only for development; signed installer and interactive authentication verification remain separate checks.

Dependencies and pinned artifacts require network access on the first build. The root npm workspace contains `ui`, `desktop`, and `bridge`. Versioned runtime styles, fonts, and icons are included in `assets/`. Do not replace lockfile-based installs with unpinned upgrades during routine builds.

## IntelliJ IDEA

Open the cloned repository directory in IDEA and import its root `pom.xml` as a Maven project. If the repository is inside an existing IDEA project, use **Link Maven Projects** in the Maven tool window and select this repository's root `pom.xml`. Wait for Maven synchronization to finish. Enable IDEA's JavaScript/TypeScript and Node.js support to use npm run configurations.

Set the project SDK to **JDK 25** in **Project Structure**. In Maven settings, select **Maven 3.9.16** (or another compatible 3.9+ installation), and set both the importer JDK and runner JRE to the project SDK. In the Node.js settings, select **Node 24.22.1** and its npm package manager (**11.19.1**).

In **Maven → Runner**, enable **Delegate IDE build/run actions to Maven**. The Java modules require Maven lifecycle plugin phases, including the dependency shading in `third-party/sshd-nofs`; IDEA's internal compiler does not run those phases. Delegated **Build Project** builds the Java modules. Use **GateRook: Build** below for the complete application, including the UI and Electron bundle.

In the repository-root terminal, run `npm ci` once before the first build. Repeat the install after the workspace lockfile changes. Check that this terminal uses the intended Node and Java versions; the IDE terminal and run configurations can inherit different environments.

Under **Run → Edit Configurations**, create these **npm** configurations. For each configuration, select the repository-root `package.json`, the Node 24 interpreter, and its npm package manager:

| Name | Command | Script | Arguments | Before launch |
| --- | --- | --- | --- | --- |
| `GateRook: Build` | `run` | `build` | Empty | None |
| `GateRook: Run` | `run` | `start` | Empty | Run `GateRook: Build` |
| `GateRook: Demo` | `run` | `start` | `-- --demo --home ./desktop/.dev/idea-demo` | Run `GateRook: Build` |

For the two launch configurations, add **Run Another Configuration → GateRook: Build** to **Before launch**. The full npm build produces the Java core, UI, and Electron bundle before starting the application. IDEA's Java **Build Project** action does not run this complete build.

In each configuration's environment variables, set `JAVA_HOME` to the local JDK 25 installation and ensure `PATH` starts with the selected Node, Maven, and JDK `bin` directories while retaining the system paths. Choose paths for your machine; keep these IDE settings local. The npm build invokes `mvn` through `PATH`, so changing IDEA's Maven home alone does not select the Maven used by this build. This also prevents a desktop-launched IDE from using an older system Node or Java installation.

Select **GateRook: Run** and click **Run** to build and start the desktop application. **GateRook: Demo** uses a separate home and seeds demo data only when that home is empty. Both keep the default OS credential protection: select **Unlock GateRook** in the application window before the operating system can request key-store access. If access is denied or cancelled, GateRook closes without starting the core. Neither configuration resets existing data. Use the Run tool window's **Stop** action to stop the application.

On macOS, the development launcher caches a locally signed `GateRook.app` under the ignored `desktop/.dev/runtime/` directory so the Dock and application menu show the product name. It refreshes the cache when Electron changes and leaves the installed npm dependency untouched. Deep links use `gaterook://`; the default application data directory is `~/.gaterook`.

The macOS development app uses the notification identity `ai.msrv.gaterook.desktop.dev`, separate from the installed app and generic Electron development apps. Allow notifications when macOS asks. If approval counters appear inside GateRook but system alerts or the Dock badge are missing, check **System Settings → Notifications → GateRook** and allow notifications and badges. Focus can delay banners even when notifications are allowed. After updating an older development runtime, restart with `npm start` to use the new identity; the launcher refreshes its cached runtime automatically.

Run configurations must point to the actual application `package.json`: `$PROJECT_DIR$` refers to the directory opened in IDEA, which may be a parent directory. Keep machine-specific paths in local `.idea` settings, which are ignored by Git.

## Build outputs

### Application version

The root [`pom.xml`](../pom.xml) defines the application version once, in `<properties><revision>`.
Change that value for the next release, then run `npm run build`. Maven modules inherit `${revision}`;
the independently versioned connector API retains its own version.

The build synchronizes the root, UI, desktop, and bridge npm manifests and the workspace lockfile from
that revision. These npm version fields are derived mirrors; do not bump them individually. Run
`npm run version:sync` to refresh them without building, or `node scripts/sync-version.mjs --check`
to check for stale mirrors. Include any updated manifests and lockfile in the version-change commit.
Desktop builds also synchronize versions when invoked directly or by `npm start`.

Use the root POM for version changes rather than overriding Maven's revision on the command line:
the Node build reads the POM. Rebuild both Java and desktop outputs after a change. The filtered Java
resources, bundled bridges, and Electron metadata carry this version. It appears in the sidebar,
**Settings → About**, and the native **About GateRook** panel.

### Component outputs

`npm run build` runs `mvn -B -ntp -DskipTests package`, the Angular production build, and the Electron development bundle. Tests are compiled but not run by this command.

| Output | Purpose |
| --- | --- |
| `app/target/app.jar` | Executable JVM core |
| `bridge-cli/target/gw-bridge.jar` | JVM client helper |
| `plugin-host/target/plugin-host.jar` | JVM plugin host |
| `ui/dist/gaterook-ui/browser/` | Renderer assets |
| `desktop/dist/` | Electron main/preload bundle |

Build just one area when appropriate:

```bash
npm run build:java
npm run build -w ui
npm run build -w desktop
```

## Desktop launcher

```bash
npm start
npm start -- --demo --home ./desktop/.dev/demo --protection off
```

The launcher rebuilds Electron, uses the built renderer, and starts the core as its child. Its default home is `desktop/.dev/home`. `--demo` seeds an empty home; `--protection off` avoids OS key-store use for a disposable instance. `--fresh` recursively deletes the chosen development home, so use it only for disposable data. Ctrl+C stops Electron and the core.

With protection enabled, startup waits at **Unlock GateRook** before accessing any stored key or showing authentication. A capability check can identify available authentication before that click without requesting access. macOS uses Touch ID or the Mac password. Windows uses Windows Hello face, fingerprint, or PIN when configured, else the Windows account password, while DPAPI protects the stored key. A home whose first file key is made where Hello is available binds it to a Windows Hello key instead (a `gaterook-hello-key-v1` reference in `master.keyset.osk`), which only Windows Hello opens. Cancellation closes the app without starting the core; helper failures do not silently bypass authentication.

An existing macOS vault initially uses its previous **GateRook Dev Safe Storage** entry. After successful access, the same file key is saved in **GateRook Vault Unlock**, and `master.keyset.osk` is atomically replaced with a reference to that Keychain item. The encrypted master keyset is unchanged. If migration cannot finish, the previous ciphertext remains usable. A locked Keychain or changed helper signature uses the Keychain access dialog alone; ordinary subsequent launches use Touch ID/password alone. The app retains a private file-key copy for core restarts and zeroes it on quit.

The development option `--protection off` skips this screen; use a separate disposable home because it cannot open an existing OS-protected keyset without its key. Never use a real home to test missing keys or recovery. Test native authentication on each release platform, including cancellation, password/PIN fallback, migration, restart, and changed signing identity; automated tests cannot validate the actual biometric dialog.

The launcher builds the renderer automatically only when its output is missing. Run the build again to pick up later UI or Java changes. The default installed-app home is `~/.gaterook`; it does not import or modify `~/.mcp-gateway`. The development launcher continues to use its selected repository-local home. See [product decisions](DECISIONS.md).

Electron now uses the GateRook application identity for OS key protection. An explicitly selected development home protected under the earlier application identity may require restoring its master-key backup. Use a separate home for a fresh instance; keep an existing home and its key backup intact.

To start over, open **Settings → General → Reset GateRook**, type `RESET`, and select **Delete all local data**. The desktop stops the core and token broker, disables login startup, removes its local database, credentials, connections, backups, history, plugins and settings, clears the renderer's stored preferences, then closes. Open GateRook again with default settings and reconnect AI apps. External databases, services and AI app configuration files are not deleted. This action cannot be undone; local backups are included in the reset.

An interrupted reset is retried before the core starts on the next launch. The reset targets GateRook-owned paths in the selected home and its Electron session; unrelated files in a development home are retained. On macOS it deletes only that home's native unlock Keychain item. Shared OS safe-storage entries are retained because other homes may use them.

## Windows Store client integration

The MSIX desktop supports the same reviewed **Connect** flow for shared user-profile files. Claude Desktop uses an endpoint-specific MCPB under `~/.gaterook/clients/claude-desktop`; GateRook opens the bundle and Claude asks the user to install it. Codex uses `~/.codex/config.toml` (or `CODEX_HOME`) with the headers helper. Cursor uses `~/.cursor/mcp.json`, with its browser installation link as an OAuth alternative for non-production endpoints. Creating a bundle or opening an installation dialog does not establish a connection: the client must finish installation and use its access.

Default MSIX virtualization redirects new AppData files, while ordinary user-writable locations outside AppData remain shared. The core rejects Claude's configuration-file fallback and any client, runtime, or backup path that resolves into AppData, including redirected folders and symlinks/junctions. It checks again when applying a preview. This behavior does not require disabling virtualization. See [Microsoft's virtualization rules](https://learn.microsoft.com/en-us/windows/msix/desktop/flexible-virtualization) and [Claude Desktop extensions](https://www.anthropic.com/research/desktop-extensions).

Electron supplies `gateway.clients.windows-store` from its actual Windows package identity. UI visibility alone is not the policy boundary. On Windows, the Codex shim and stdio clients run the exported `~/.gaterook/bin/gw-bridge.exe` with its adjacent runtime DLLs; they do not depend on access to GateRook's private `WindowsApps` installation. MCPB and helper configurations contain grant identifiers rather than bearer tokens. Production access retains its existing broker confirmation and revocation behavior.

For acceptance, use a disposable Windows profile and a real MSIX launch. Inspect created configuration from an unpackaged process, test first-time creation and preservation of unrelated entries, then complete installation and an MCP request in each client. Also check a custom `CODEX_HOME` under AppData, path redirection between preview and confirmation, and client disconnection. JVM integration tests and a successful exported-helper launch do not replace those client tests.

## Full backup and restore

In the desktop app, open **Settings → Backup → Create full backup**. Enter and repeat a unique passphrase of 12–1,024 characters, then save the `.grbackup` file outside the GateRook data folder. Store a copy away from this computer and keep the passphrase separately, for example in a password manager. GateRook cannot recover a forgotten passphrase. Creating the backup briefly stops and restarts the gateway to capture local plugin files; active calls may be interrupted.

The encrypted file includes the database (connections, endpoints, policies, stored secrets, settings and audit history), its portable master key, desktop settings, installed plugin files, plugin data and local connector files. AES-256-GCM encrypts the backup data and manifest and authenticates them together with the format header. Each backup uses scrypt to derive its encryption key from the passphrase, with a fresh random salt and nonce. Full backups have a 256 MiB file limit. Weekly database backups remain separate and do not include the master key. They run every Sunday at 03:00 local time and keep the last seven copies. At startup, an enabled backup also runs if no copy exists or the newest copy is at least seven days old. The existing automatic-backup preference is preserved.

Generated runtimes, caches, logs, previous backups, browser sessions and files referenced outside the GateRook data folder are excluded. External secret-store values remain external. Reinstall MCP runtimes and relink external private keys, configuration files and other references on the destination computer as needed. A full backup restores GateRook's local state; it does not copy external databases or services.

To recover, use the same GateRook version, open **Settings → Backup → Restore full backup**, enter the backup passphrase and type `RESTORE`. If the master key is missing or invalid, or the core cannot open the database, the recovery screen also offers **Restore full backup**; **Restore master key** remains available for a separate key-only copy. Select the encrypted file and review the final native confirmation. Validation runs in a separate temporary core, so recovery remains available when the original database cannot start. GateRook verifies the authenticated file, database integrity, matching database schema and master key before replacing local data. Create a full backup of the destination first if you need to retain its current state. Replacement runs with the core stopped; an interrupted replacement is rolled back before the next core startup.

GateRook closes when restoration succeeds. Reopen it, review the restored configuration and reconnect your AI apps: previous client access is revoked. Restored plugins are disabled, external MCP servers start on first use, and tunnels stay off until enabled. This computer's listener ports are retained, and startup at login is disabled. The restored master key uses the destination's local protection; the source computer's OS key-store credentials are not transferred.

## Endpoint sharing

Open an endpoint and choose **Share endpoint**. Select the connections to include, choose whether to include their stored local secrets, and enter and repeat a passphrase of 12–1,024 characters. Secrets are excluded by default. Save the `.grshare` file outside the GateRook data folder and send it to the recipient; communicate its passphrase separately. Sharing does not stop the gateway or change the source endpoint.

Open **Endpoints → Import shared endpoint** to select a file. Installed desktop packages also register `.grshare` as a GateRook document type; opening one brings up the same import review. Enter its passphrase to see the endpoint, connections, tool modes and warnings. Select or skip individual connections, choose whether to create the endpoint, and choose whether to import any included secrets. Nothing is created until **Import selected** is confirmed. An incorrect passphrase, modified file or unsupported format is rejected before review.

Imports create new copies with new internal identifiers. Existing connections and endpoints, including paused ones, are preserved; colliding names receive a numeric suffix. Confirming the import enables the new endpoint and every connection with complete settings and included credentials, without opening and saving each connection again. Entries with missing settings, omitted credentials or local file/profile references remain disabled with **Setup required**; their available settings and included secrets are retained. Production permission warnings are reviewed together before any copies are created. Connect AI apps separately: source client grants, bearer tokens, OAuth sign-ins, master keys, audit history, global settings and external secret stores are never shared. External credentials and local file/profile references must be configured on the destination computer. Plugin connections require the same connector to be installed and enabled locally; plugin code is not included. Unavailable connector types cannot be selected for import. External MCP servers use their separate installation and sign-in review and cannot be transferred in a share.

Active SQL query, SSH runbook and OpenAPI template definitions travel with their selected connection. The destination compiles them again and remaps their tool identifiers. Read-only confirmations do not transfer: imported SSH runbooks and HTTP operations other than GET/HEAD require approval. Bypass modes become Approval, and temporary bypass windows do not transfer. Review warnings for excluded templates and credentials.

The share envelope uses the full-backup encryption implementation with a distinct format header: scrypt derives a key from the passphrase, and AES-256-GCM encrypts and authenticates the payload and header using a new random salt and nonce for every export. This protects confidentiality and detects changes; it does not certify the sender's identity. The encrypted file limit is 8 MiB, with at most 6 MiB of portable JSON data. Decrypted data stays in desktop main-process memory during review, is not written to a temporary file or returned to the renderer, and expires after 15 minutes or a core restart. Only sanitized review metadata reaches the UI.

For local verification, run `npm test -w desktop`, `npm test -w ui`, and the focused Java checks:

```bash
mvn -B -ntp -pl app -am verify -Dtest=TemplateSharesTest,ModularityTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=EndpointShareIT,HttpCoreProcessIT -Dit.failIfNoSpecifiedTests=false
```

File-association unit tests and packaging metadata checks do not replace opening a file with the installed application on each supported operating system.

## Browser development

```bash
npm run dev:ui
# Optional: npm run dev:ui -- --port 4311 --core-port 7467
```

This starts a development JVM core and the Angular server with a signing proxy. The browser launcher selects a free core port and prints the resulting URLs. `--core-port` requests a preferred port; if it is occupied, the launcher chooses another free one. Plain `ng serve` cannot authenticate administrative API requests by itself. See [UI development](../ui/README.md) for demo homes and element routes.

## Tests

```bash
npm test
npm test -w bridge
npm test -w desktop
npm test -w ui
node --test 'catalog/*.test.mjs'
mvn -B -ntp verify
```

Maven uses Surefire for unit tests and Failsafe for integration tests. Some default integration fixtures need local server binaries; heavy profiles may download pinned fixtures. For a portable Docker-backed suite, follow [test services](../test-services/README.md):

```bash
scripts/test-services.sh up
scripts/test-services.sh run mvn -B -ntp verify -Pservices
scripts/test-services.sh down
```

Tests reset their service databases and users. Use only disposable test servers. The wrapper serializes runs that share these services.

Run a focused Java module with `mvn -B -ntp -pl core -am verify` or `-pl connectors/sql -am verify`. Individual integration tests can be selected with `-Dit.test=NameIT -Dit.failIfNoSpecifiedTests=false`; add `-Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` when skipping unit tests for that focused run.

MCP Apps approval cards live in `core/src/main/resources/ai/msrv/gaterook/core/mcp/apps/`. Maven packages the versioned interface stylesheet from `assets/` alongside the HTML and script. The renderer and MCP cards use the same bundled styles. `npm test` exercises the card's sandbox message lifecycle. `McpApprovalCardsIT` uses local fake connectors to cover authenticated negotiation, caller isolation, denial, navigation, and approval-forgery rejection. Clients that connect through either stdio bridge keep their declared capabilities across core restarts. A Claude Desktop extension contains its own copy of `gw-bridge.mjs`, so reinstall it from GateRook after bridge changes; Claude's Code and Cowork sessions also keep the extension's first tool list until it reconnects. Other legacy clients must reconnect after a core restart before they show cards again. Ordinary MCP clients retain the handle flow. Protocol fixtures do not establish compatibility with every host or replace a real host smoke test.

The desktop smoke suite starts real Electron instances in disposable homes, including a real JVM core scenario:

```bash
npm run smoke -w desktop
# Or only the real core scenario:
npm run smoke -w desktop -- --only real
```

Linux without a display needs `xvfb-run -a`. Native image and OS-specific sandbox checks have additional requirements.

## Update checks and installation identity

On first launch, an installed app creates a cryptographically random 16-character ASCII letter/digit ID in `install-id` inside its Electron application data directory. Updates retain the file. The ID is independent of account information, hardware, gateway settings and credentials. It is not part of gateway backup archives; deleting the application data directory creates a new ID on the next launch. An invalid, unreadable or unwritable identity never blocks opening the app or checking for updates: the check omits the ID and is not counted, while any existing identity file remains untouched.

Update checks send `install_id` to `https://msrv.dev/api/updates/{platform}/{arch}/latest-mac.yml` on macOS or `latest.yml` for supported standalone Windows builds. Metadata contains versioned GitHub release download URLs with the updater's hashes, sizes and blockmap information. The request parameter is added only to metadata requests and is redacted from updater logs and errors; it is not attached to GitHub downloads or blockmaps. Packaging retains its GitHub publish configuration so release metadata remains compatible, while the installed app selects the site metadata provider at runtime.

Automatic checks run one minute after launch, then hourly on macOS and daily on Windows, when **Settings → General → Check for updates automatically** is enabled. **Check now** performs an explicit check regardless of that preference. Microsoft Store builds read `store.json` at the Windows metadata path for the same activity count; they never load an external installer, and Microsoft Store remains responsible for all installation and updates. Development builds never make these requests. Linux automatic updates remain unavailable.

The ID measures distinct installations that checked for updates within a rolling 30-day window, not a lifetime installation count or the number of people. Offline apps and apps with automatic checks disabled are not counted unless they make a successful manual check. No connection configuration, credentials, queries or gateway activity are included in these requests. Existing signature verification, explicit download/install actions and rollback restrictions remain in force.

## Native binaries and local packaging

Use a compatible GraalVM JDK 25 with `native-image` on `PATH` and the platform's native compiler/linker toolchain. The native build can require substantial RAM. The research documents describe the original toolchain validation; check current manifests before selecting a release toolchain.

```bash
mvn -B -ntp -pl app,bridge-cli -am -Pnative -DskipTests package
npm start -- --core-binary app/target/gaterook-core
```

Windows outputs use `.exe`. Native runtime libraries must remain next to the executable or in its adjacent `native-libs/` directory. `scripts/aot-check.sh` validates the AOT context without a full native compilation; `scripts/native-smoke.sh` exercises a built native core.

Installer inputs and staging scripts are retained in `desktop/` and `scripts/`. Packaging also requires a plugin-host JRE (`scripts/jlink.sh`), platform binaries, and staged resources. Signing, notarization, release credentials, and publication are separate concerns. Do not treat a successful JVM build as installer validation or run publishing commands as part of local setup.

On macOS, the packaging `afterPack` hook writes `Contents/Resources/app-update.yml` from the configured update source before signing, including directory-only builds. Keep this hook when overriding packaging settings: packaging a prebuilt application does not regenerate its update configuration. An installed app missing this file cannot check for updates; install a corrected signed build manually. Adding the file to an already signed application invalidates its resource seal.

The same hook adds owner-write permission to packaged resource files and directories before signing, preserving their contents, executable bits and symbolic links. JRE license files can be read-only in the source runtime; without this adjustment, Squirrel.Mac cannot remove their quarantine attributes during an update and reopens the previous version after installation fails. The source JRE is unchanged. On macOS, inspect `~/Library/Caches/ai.msrv.gaterook.desktop.ShipIt/ShipIt_stderr.log` for installation errors that occur after GateRook quits. A corrected update archive must be packaged with this hook; retrying an unchanged archive repeats the failure.

Check the complete packaged app, and repeat the check on a fresh extraction of the final updater ZIP:

```bash
node desktop/scripts/verify-mac-update-permissions.mjs /path/to/GateRook.app
```

This read-only check requires POSIX file modes. It rejects real files or directories without owner-write permission, reports the relative path and mode, and does not follow symbolic links. It never repairs a signed app or changes quarantine attributes. Fix packaging inputs before signing and regenerate the archive when it fails. Signature verification, notarization, and permission validation are separate checks; none replaces an actual installation/update test.

## Troubleshooting

- **Wrong Node or Java:** select the pinned Node and JDK before running npm or Maven. A stale Intel Maven installation on Apple Silicon can reference an incompatible Java executable; use a Maven 3.9+ installation with the correct `JAVA_HOME`.
- **Electron binary missing after install:** check npm's install-script policy. Electron's package install script downloads its platform binary; the desktop launcher needs that step to complete.
- **Core JAR missing:** run `npm run build:java`.
- **Port occupied:** the browser launcher falls back to a free port; use its printed URLs. The desktop status screen can select another core port.
- **Demo approvals expired:** restart with a new disposable home; demo approvals are intentionally short-lived.
- **Integration fixture unavailable:** use the Docker services or the fixture-specific environment override documented in the test source. A skipped or unavailable fixture is not a passing integration test.

Put task-specific notes, investigation scripts, and logs in root `ai/`, which is ignored and must never be committed. Build outputs retain their standard ignored locations.
