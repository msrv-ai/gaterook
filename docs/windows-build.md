# Windows builds with Docker and Wine

The local Windows x64 workflow has two stages: build the native application inputs on Windows, then package them with the renderer and Electron bundles in `electronuserland/builder:wine`. The result is an unsigned NSIS installer and its updater files. These scripts do not sign, upload, or publish artifacts. Packaging currently accepts stable `x.y.z` versions only; prerelease and other version forms are refused because the output contract uses `latest.yml`.

[Electron-builder recommends the Wine Docker image for Windows packaging](https://www.electron.build/docs/features/multi-platform-build/), but Windows native dependencies still need Windows build tools. GateRook requires a Windows GraalVM core, its SQLite library, a native bridge, a Java runtime for the plugin host, and the Windows Hello helper. Wine does not replace the GraalVM/MSVC toolchain that produces these inputs.

## Prepare native inputs on Windows

Use a Windows x64 machine with:

- Git and the Node.js version in [`.nvmrc`](../.nvmrc), with the npm version in [`package.json`](../package.json).
- GraalVM JDK 25 with `native-image`, and Maven 3.9 or newer.
- Visual Studio C++ build tools and the Windows 11 SDK.

Open an **x64 Developer Command Prompt**. Set `JAVA_HOME` to GraalVM JDK 25 and put its `bin` directory, Maven, Node, and Git on `PATH`. Node and the compiler must both target x64. Native compilation can require substantial memory and takes longer than the JVM development build.

Check out the application commit you want to package. Preparation requires a clean source checkout, including no nonignored untracked files, and checks it again after building. It also requires the exact Node.js version from `.nvmrc`. Run from the repository root:

```bat
npm run prepare:win -- --out target/windows-inputs
```

The preparation script installs locked npm dependencies, builds the design kit, builds the core and bridge with Maven profiles `native,release`, builds the plugin-host JAR, creates a Windows jlink runtime, and compiles the Windows unlock helper. Maven compiles tests but skips running them during this preparation; run the relevant tests separately before accepting a build.

The default output is `target/windows-inputs/`. The output directory must not exist, even if empty; choose a new path when retaining an earlier bundle. Keep custom output under an ignored directory such as `target/`, or outside the checkout, so generated files do not fail the source-cleanliness check. The bundle contains:

```text
windows-inputs/
├── windows-inputs.json
├── core/
│   ├── gaterook-core.exe
│   └── sqlitejdbc.dll
├── jre/
├── plugin-host/plugin-host.jar
├── bridge/
│   ├── gw-bridge.exe
│   └── gw-bridge.jar
└── native/gaterook-unlock.exe
```

The manifest records the application commit, version, target, and SHA-256 hashes of the payload files. Validation rejects missing inputs, changed payloads, the wrong PE architecture, and an incompatible Java runtime. Keep the entire directory together when transferring it to the Docker host. Do not copy a JRE or native executable from a Linux or macOS build.

## Package with Docker

On a macOS or Linux host, install Git, the pinned Node.js/npm toolchain, and a running Docker engine. On macOS, use Docker Desktop with Linux containers. Check out the same application commit recorded in `windows-inputs.json` and keep tracked source files clean.

Copy the input bundle into `target/windows-inputs/`, or provide its path explicitly:

```bash
npm run dist:win:docker -- --inputs /path/to/windows-inputs
```

The default input is `target/windows-inputs/`, so `npm run dist:win:docker` works when the bundle is already there. The default output is `desktop/release/windows-x64/`. To inspect the planned command or select another output directory and image:

```bash
npm run dist:win:docker -- --inputs /path/to/windows-inputs --dry-run
npm run dist:win:docker -- --inputs /path/to/windows-inputs --out /path/to/new-output
npm run dist:win:docker -- --help
```

`--dry-run` prints the Docker plan without checking source cleanliness, native inputs, or Docker availability. A successful dry run does not validate a build.

`--image <reference>` overrides the default `electronuserland/builder:wine`. Use a compatible image pinned by digest when you need to repeat a build with the same container tools. Output directories must be new or empty; use a separate directory for each retained build.

The launcher explicitly selects `linux/amd64`. Apple Silicon hosts therefore require Docker's x86-64 emulation; native packaging tools under Wine may have emulation limitations. An x86-64 Linux Docker host is the fallback if Wine cannot run correctly under emulation.

The container tests a Windows command through Wine before installing dependencies. An `anon_mmap_fixed` assertion followed by a QEMU abort means the host's emulation cannot run this Wine build; selecting `linux/amd64` alone does not resolve it. Use an x86-64 Docker host for packaging in that case.

The container uses a Git archive of the selected application commit and verifies that the native-input manifest matches it. It installs the pinned Node/npm toolchain when the image's versions differ, installs dependencies from the lockfiles, builds the design kit and renderer, builds the Electron release bundle, stages `windows-x64` resources, and copies the prepared Windows unlock helper after bundling. Electron-builder runs with `--win nsis --x64 --publish never`.

The source checkout and its installed host dependencies are isolated from container builds. Named Docker volumes cache npm, Electron, electron-builder, and cloudflared downloads. A first build needs network access for the image, toolchain, dependencies, Electron, packaging tools, and the pinned Windows cloudflared binary. Build caches do not replace the input manifest or lockfile checks.

## Outputs and acceptance

A completed packaging run retains the NSIS `.exe`, its `.exe.blockmap`, `latest.yml`, `SHA256SUMS`, and a local `build-info.json`. Keep the updater metadata and blockmap with the matching executable. The build information identifies the inputs and records that signing and Windows runtime acceptance have not been completed.

Packaging and checksums establish that the artifact set was produced; they do not establish that the installer or application works on Windows. Test installation, launch and core startup, Windows Hello approval and cancellation, plugin loading, tunnel startup, upgrade/update behavior, and uninstallation on Windows before distributing a build. The local scripts do not provide signing credentials or activate downloads.

Rebuild the native bundle after changing the application commit. A revision or version mismatch is an error; do not edit the manifest to combine binaries from one revision with source from another. Preserve previously accepted artifacts until their replacement has passed its installation and runtime checks.
