#!/usr/bin/env node
// Stages everything the installers carry besides Electron and the main bundle (ARCHITECTURE.md §7.1 "Packaging",
// report 14 §7.7) into desktop/stage/, the layout desktop/electron-builder.yml packs:
//   ui/                          the built Angular UI (into the asar, served as app://gw/)
//   core/mcp-gateway-core[.exe]  the native core + libsqlitejdbc next to it (-Dorg.sqlite.lib.path=<resources>/core)
//   core/app.jar                 instead, with --jar: the JVM fallback (a CI artifact only, never an installer)
//   jre/                         the jlink JRE of the plugin host (scripts/jlink.sh → target/plugin-host-jre), else a placeholder
//   plugin-host/plugin-host.jar  the plugin host (step K4), else a placeholder
//   bridge/                      gw-bridge.mjs (Claude Desktop extensions), the native gw-bridge, gw-bridge.jar
//   cloudflared/<platform>/      the pinned cloudflared (scripts/stage-cloudflared.mjs, hashes checked)
//   icons/icon.png               the canonical app icon from design/assets/gaterook/gaterook-icon.png
//
//   node scripts/stage.mjs                         this machine's platform, the native core of app/target
//   node scripts/stage.mjs --core <binary>         another native core (its native-libs/ folder comes along)
//   node scripts/stage.mjs --jar                   the JVM fallback instead of the native core
//   node scripts/stage.mjs --jre <dir>             another JRE to put in jre/ (the plugin host's)
//   node scripts/stage.mjs --platform darwin-arm64 --skip-cloudflared --out <dir>
//
// Missing optional parts become placeholders (README.txt saying what goes there); a missing UI or core is an error.
import { chmodSync, cpSync, existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** linux-x64, darwin-arm64, windows-x64: the bundle's platform key (as scripts/stage-cloudflared.mjs). */
export function platformKey(platform = process.platform, arch = process.arch) {
  const os = platform === 'darwin' ? 'darwin' : platform === 'win32' ? 'windows' : 'linux';
  return `${os}-${arch === 'arm64' ? 'arm64' : 'x64'}`;
}

/** The SQLite JDBC native library's file name on a platform key. */
export function sqliteLibName(key) {
  if (key.startsWith('darwin')) return 'libsqlitejdbc.dylib';
  if (key.startsWith('windows')) return 'sqlitejdbc.dll';
  return 'libsqlitejdbc.so';
}

export function parseArgs(argv) {
  const o = { out: join(root, 'desktop', 'stage'), platform: platformKey(), core: null, jar: false, jre: null,
    skipCloudflared: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--out') o.out = resolve(argv[++i]);
    else if (a === '--platform') o.platform = argv[++i];
    else if (a === '--core') o.core = resolve(argv[++i]);
    else if (a === '--jar') o.jar = true;
    else if (a === '--jre') o.jre = resolve(argv[++i]);
    else if (a === '--skip-cloudflared') o.skipCloudflared = true;
    else throw new Error(`unknown argument ${a}`);
  }
  return o;
}

function placeholder(dir, text) {
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'README.txt'), `${text}\n`);
}

function copyFile(from, to, mode) {
  mkdirSync(dirname(to), { recursive: true });
  cpSync(from, to);
  if (mode !== undefined && process.platform !== 'win32') chmodSync(to, mode);
}

/**
 * Stages into `o.out`. `sources` names where the inputs are (the repository by default; the tests point it at a
 * folder of fakes). Returns what was staged, one line per part.
 */
export async function stage(o, sources = {}) {
  const src = {
    uiDist: join(root, 'ui', 'dist', 'mcp-gateway-ui', 'browser'),
    nativeCore: join(root, 'app', 'target', `mcp-gateway-core${o.platform.startsWith('windows') ? '.exe' : ''}`),
    jar: join(root, 'app', 'target', 'app.jar'),
    jre: join(root, 'target', 'plugin-host-jre'),
    pluginHost: join(root, 'plugin-host', 'target', 'plugin-host.jar'),
    bridgeMjs: join(root, 'bridge', 'gw-bridge.mjs'),
    bridgeNative: join(root, 'bridge-cli', 'target', `gw-bridge${o.platform.startsWith('windows') ? '.exe' : ''}`),
    bridgeJar: join(root, 'bridge-cli', 'target', 'gw-bridge.jar'),
    ...sources,
  };
  const report = [];
  // The folder is cleared first: only one this script made (or the default), never whatever --out happens to name.
  const marker = join(o.out, '.mcp-gateway-stage');
  const isDefault = resolve(o.out) === join(root, 'desktop', 'stage');
  if (existsSync(o.out) && readdirSync(o.out).length > 0 && !existsSync(marker) && !isDefault) {
    throw new Error(`refusing to clear ${o.out}: it isn't a stage folder (no .mcp-gateway-stage in it)`);
  }
  rmSync(o.out, { recursive: true, force: true });
  mkdirSync(o.out, { recursive: true });
  writeFileSync(marker, 'Made by scripts/stage.mjs; cleared and refilled on every run.\n');

  // UI: required.
  if (!existsSync(join(src.uiDist, 'index.html'))) {
    throw new Error(`no UI at ${src.uiDist}: npm run build -w ui`);
  }
  cpSync(src.uiDist, join(o.out, 'ui'), { recursive: true });
  report.push(`ui ← ${src.uiDist}`);

  // Core: the native binary (with its libraries), or the jar on request.
  const coreDir = join(o.out, 'core');
  if (o.jar) {
    if (!existsSync(src.jar)) throw new Error(`no ${src.jar}: mvn -B -ntp -pl app -am -DskipTests package`);
    copyFile(src.jar, join(coreDir, 'app.jar'));
    report.push(`core/app.jar ← ${src.jar} (JVM fallback: needs jre/)`);
  } else {
    const binary = o.core ?? src.nativeCore;
    if (!existsSync(binary)) {
      throw new Error(`no native core at ${binary}: build it (-Pnative, step N1), pass --core <binary>, or --jar for the JVM fallback`);
    }
    const name = `mcp-gateway-core${o.platform.startsWith('windows') ? '.exe' : ''}`;
    copyFile(binary, join(coreDir, name), 0o755);
    const lib = sqliteLibName(o.platform);
    const libs = [join(dirname(binary), 'native-libs', lib), join(dirname(binary), lib)].find((p) => existsSync(p));
    if (!libs) throw new Error(`no ${lib} next to ${binary} (native-libs/): the core can't open SQLite without it`);
    copyFile(libs, join(coreDir, lib));
    report.push(`core/${name} + ${lib} ← ${binary}`);
  }

  // The plugin host's JRE (scripts/jlink.sh, or --jre) and jar (mvn -pl plugin-host package): placeholders without them.
  const jre = o.jre ?? (existsSync(join(src.jre, 'bin')) ? src.jre : null);
  if (jre) {
    if (!existsSync(join(jre, 'bin'))) throw new Error(`${jre} is not a JRE (no bin/)`);
    cpSync(jre, join(o.out, 'jre'), { recursive: true, verbatimSymlinks: true });
    report.push(`jre ← ${jre}`);
  } else {
    placeholder(join(o.out, 'jre'), 'The plugin host\'s jlink JRE goes here (scripts/jlink.sh, step N1): java.se + the jdk modules of '
      + 'ARCHITECTURE.md §7.1, with --release-info=add:JAVA_RUNTIME_VERSION=….');
    report.push('jre: placeholder');
  }
  if (existsSync(src.pluginHost)) {
    copyFile(src.pluginHost, join(o.out, 'plugin-host', 'plugin-host.jar'));
    report.push(`plugin-host/plugin-host.jar ← ${src.pluginHost}`);
  } else {
    placeholder(join(o.out, 'plugin-host'), 'plugin-host.jar goes here (mvn -pl plugin-host package, step K4).');
    report.push('plugin-host: placeholder');
  }

  // Bridges: the Node bridge always (Claude Desktop extensions carry a copy the core builds), the Java ones if built.
  copyFile(src.bridgeMjs, join(o.out, 'bridge', 'gw-bridge.mjs'));
  const bridges = ['gw-bridge.mjs'];
  if (existsSync(src.bridgeNative)) {
    copyFile(src.bridgeNative, join(o.out, 'bridge', basename(src.bridgeNative)), 0o755);
    bridges.push(basename(src.bridgeNative));
  }
  if (existsSync(src.bridgeJar)) {
    copyFile(src.bridgeJar, join(o.out, 'bridge', 'gw-bridge.jar'));
    bridges.push('gw-bridge.jar');
  }
  report.push(`bridge: ${bridges.join(', ')}`);

  // cloudflared, pinned and checked by its own script (A6, decision 8).
  if (o.skipCloudflared) {
    placeholder(join(o.out, 'cloudflared', o.platform), 'cloudflared goes here: node scripts/stage-cloudflared.mjs');
    report.push('cloudflared: skipped');
  } else {
    const r = spawnSync(process.execPath, [join(root, 'scripts', 'stage-cloudflared.mjs'), '--platform', o.platform, '--out', o.out],
      { stdio: 'inherit' });
    if (r.status !== 0) throw new Error('staging cloudflared failed');
    report.push(`cloudflared/${o.platform}`);
  }

  // Windows/Linux keep the compact kit mark. macOS uses its padded export at every native icon size.
  copyFile(join(root, 'design', 'assets', 'gaterook', 'gaterook-icon.png'), join(o.out, 'icons', 'icon.png'));
  report.push('icons/icon.png (1024 px)');
  for (const extension of ['png', 'icns']) {
    copyFile(join(root, 'design', 'assets', 'gaterook', `gaterook-icon-macos.${extension}`),
      join(o.out, 'icons', `icon-macos.${extension}`));
    report.push(`icons/icon-macos.${extension}`);
  }
  return report;
}

/** Size of a staged tree, for the summary. */
export function treeBytes(dir) {
  let total = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    total += entry.isDirectory() ? treeBytes(p) : statSync(p).size;
  }
  return total;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  try {
    const o = parseArgs(process.argv.slice(2));
    const report = await stage(o);
    for (const line of report) console.log(`stage: ${line}`);
    console.log(`stage: ${o.out} (${Math.round(treeBytes(o.out) / 1024 / 1024)} MB)`);
  } catch (e) {
    console.error(`stage: ${e.message}`);
    process.exit(1);
  }
}
