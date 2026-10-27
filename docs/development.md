# Local development

## Toolchains and dependencies

Use JDK 25, Maven 3.9+, and Node 24.22.1 with npm 11.19.1. The root `.nvmrc` pins Node. Set `JAVA_HOME` and put its `bin` directory on `PATH`; check `java -version`, `mvn -version`, and `node --version` before building. A macOS or Linux installation may have older toolchains earlier on `PATH`.

From the repository root:

```bash
npm ci
npm ci --prefix design
npm run build
npm start
```

Dependencies and pinned artifacts require network access on the first build. The root npm workspace contains `ui`, `desktop`, and `bridge`; the design kit is a local package with its own build dependencies and lockfile. Do not replace lockfile-based installs with unpinned upgrades during routine builds.

## IntelliJ IDEA

Open the cloned repository directory in IDEA and import its root `pom.xml` as a Maven project. If the repository is inside an existing IDEA project, use **Link Maven Projects** in the Maven tool window and select this repository's root `pom.xml`. Wait for Maven synchronization to finish. Enable IDEA's JavaScript/TypeScript and Node.js support to use npm run configurations.

Set the project SDK to **JDK 25** in **Project Structure**. In Maven settings, select **Maven 3.9.16** (or another compatible 3.9+ installation), and set both the importer JDK and runner JRE to the project SDK. In the Node.js settings, select **Node 24.22.1** and its npm package manager (**11.19.1**).

In **Maven → Runner**, enable **Delegate IDE build/run actions to Maven**. The Java modules require Maven lifecycle plugin phases, including the dependency shading in `third-party/sshd-nofs`; IDEA's internal compiler does not run those phases. Delegated **Build Project** builds the Java modules. Use **GateRook: Build** below for the complete application, including the design kit, UI, and Electron bundle.

In the repository-root terminal, run `npm ci` and `npm ci --prefix design` once before the first build. Repeat these installs after the corresponding lockfiles change. Check that this terminal uses the intended Node and Java versions; the IDE terminal and run configurations can inherit different environments.

Under **Run → Edit Configurations**, create these **npm** configurations. For each configuration, select the repository-root `package.json`, the Node 24 interpreter, and its npm package manager:

| Name | Command | Script | Arguments | Before launch |
| --- | --- | --- | --- | --- |
| `GateRook: Build` | `run` | `build` | Empty | None |
| `GateRook: Run` | `run` | `start` | Empty | Run `GateRook: Build` |
| `GateRook: Demo` | `run` | `start` | `-- --demo --home ./desktop/.dev/idea-demo` | Run `GateRook: Build` |

For the two launch configurations, add **Run Another Configuration → GateRook: Build** to **Before launch**. The full npm build produces the design kit, Java core, UI, and Electron bundle before starting the application. IDEA's Java **Build Project** action does not run this complete build.

In each configuration's environment variables, set `JAVA_HOME` to the local JDK 25 installation and ensure `PATH` starts with the selected Node, Maven, and JDK `bin` directories while retaining the system paths. Choose paths for your machine; keep these IDE settings local. The npm build invokes `mvn` through `PATH`, so changing IDEA's Maven home alone does not select the Maven used by this build. This also prevents a desktop-launched IDE from using an older system Node or Java installation.

Select **GateRook: Run** and click **Run** to build and start the desktop application. **GateRook: Demo** uses a separate home and seeds demo data only when that home is empty. It keeps the default OS credential protection, so the operating system may request key-store access. Neither configuration resets existing data. Use the Run tool window's **Stop** action to stop the application.

On macOS, the development launcher caches a locally signed `GateRook.app` under the ignored `desktop/.dev/runtime/` directory so the Dock and application menu show the product name. It refreshes the cache when Electron changes and leaves the installed npm dependency untouched. Deep links use `gaterook://`; the default application data directory is `~/.gaterook`.

Run configurations must point to the actual application `package.json`: `$PROJECT_DIR$` refers to the directory opened in IDEA, which may be a parent directory. Keep machine-specific paths in local `.idea` settings, which are ignored by Git.

## Build outputs

`npm run build` runs the design generator, `mvn -B -ntp -DskipTests package`, the Angular production build, and the Electron development bundle. Tests are compiled but not run by this command.

| Output | Purpose |
| --- | --- |
| `app/target/app.jar` | Executable JVM core |
| `bridge-cli/target/gw-bridge.jar` | JVM client helper |
| `plugin-host/target/plugin-host.jar` | JVM plugin host |
| `ui/dist/gaterook-ui/browser/` | Renderer assets |
| `desktop/dist/` | Electron main/preload bundle |
| `design/dist/gw-kit.css` | Generated design-kit CSS |

Build just one area when appropriate:

```bash
npm run build:java
npm run build -w ui
npm run build -w desktop
npm --prefix design run verify
```

## Desktop launcher

```bash
npm start
npm start -- --demo --home ./desktop/.dev/demo --protection off
```

The launcher rebuilds Electron, uses the built renderer, and starts the core as its child. Its default home is `desktop/.dev/home`. `--demo` seeds an empty home; `--protection off` avoids OS key-store use for a disposable instance. `--fresh` recursively deletes the chosen development home, so use it only for disposable data. Ctrl+C stops Electron and the core.

The launcher builds the renderer automatically only when its output is missing. Run the build again to pick up later UI or Java changes. The default installed-app home is `~/.gaterook`; it does not import or modify `~/.mcp-gateway`. The development launcher continues to use its selected repository-local home. See [product decisions](DECISIONS.md).

Electron now uses the GateRook application identity for OS key protection. An explicitly selected development home protected under the earlier application identity may require restoring its master-key backup. Use a separate home for a fresh instance; keep an existing home and its key backup intact.

## Browser development

```bash
npm run dev:ui
# Optional: npm run dev:ui -- --port 4311 --core-port 7467
```

This starts a development JVM core and the Angular server with a signing proxy. The browser launcher selects a free core port and prints the resulting URLs. `--core-port` requests a preferred port; if it is occupied, the launcher chooses another free one. Plain `ng serve` cannot authenticate administrative API requests by itself. See [UI development](../ui/README.md) for demo homes, element routes, and visual comparisons.

## Tests

```bash
npm test
npm test -w bridge
npm test -w desktop
npm test -w ui
npm --prefix design run verify
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

The desktop smoke suite starts real Electron instances in disposable homes, including a real JVM core scenario:

```bash
npm run smoke -w desktop
# Or only the real core scenario:
npm run smoke -w desktop -- --only real
```

Linux without a display needs `xvfb-run -a`. Native image and OS-specific sandbox checks have additional requirements.

## Native binaries and local packaging

Use a compatible GraalVM JDK 25 with `native-image` on `PATH` and the platform's native compiler/linker toolchain. The native build can require substantial RAM. The research documents describe the original toolchain validation; check current manifests before selecting a release toolchain.

```bash
mvn -B -ntp -pl app,bridge-cli -am -Pnative -DskipTests package
npm start -- --core-binary app/target/gaterook-core
```

Windows outputs use `.exe`. Native runtime libraries must remain next to the executable or in its adjacent `native-libs/` directory. `scripts/aot-check.sh` validates the AOT context without a full native compilation; `scripts/native-smoke.sh` exercises a built native core.

Installer inputs and staging scripts are retained in `desktop/` and `scripts/`. Packaging also requires a plugin-host JRE (`scripts/jlink.sh`), platform binaries, and staged resources. Signing, notarization, release credentials, and publication are separate concerns. Do not treat a successful JVM build as installer validation or run publishing commands as part of local setup.

## Troubleshooting

- **Wrong Node or Java:** select the pinned Node and JDK before running npm or Maven. A stale Intel Maven installation on Apple Silicon can reference an incompatible Java executable; use a Maven 3.9+ installation with the correct `JAVA_HOME`.
- **Electron binary missing after install:** check npm's install-script policy. Electron's package install script downloads its platform binary; the desktop launcher needs that step to complete.
- **Core JAR missing:** run `npm run build:java`.
- **Port occupied:** the browser launcher falls back to a free port; use its printed URLs. The desktop status screen can select another core port.
- **Demo approvals expired:** restart with a new disposable home; demo approvals are intentionally short-lived.
- **Integration fixture unavailable:** use the Docker services or the fixture-specific environment override documented in the test source. A skipped or unavailable fixture is not a passing integration test.

Put task-specific notes, investigation scripts, and logs in root `ai/`, which is ignored and must never be committed. Build outputs retain their standard ignored locations.
