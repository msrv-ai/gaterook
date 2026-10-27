#!/usr/bin/env node
// The desktop app from the repository, end to end (ARCHITECTURE.md §7.1): the development bundle of main and preload,
// the built UI served as app://gw/, and the real core — `java -jar app/target/app.jar` (or a native binary) started
// by main with the bootstrap line, exactly as the installed app starts its native core.
//
//   npm run dev -w desktop                         home desktop/.dev/home (kept between runs)
//   npm run dev -w desktop -- --demo --fresh       a new home with the demo world of BLUEPRINT §9
//   npm run dev -w desktop -- --home ~/.mcp-gateway   the real gateway home
//   npm run dev -w desktop -- --core-binary app/target/mcp-gateway-core   the native core instead of the jar
//   npm run dev -w desktop -- --protection off     no OS protection of the master key (no Keychain entry)
//
// Needs JAVA_HOME (JDK 25) for the jar, `npm run build -w ui` (done here when ui/dist is missing) and
// `mvn -B -ntp -pl app -am -DskipTests package` for app/target/app.jar. The development build keeps its own
// Chromium profile in <home>/electron and its own Keychain entry ("MCP Gateway Dev Safe Storage"); it never registers
// itself at login or as the mcp-gateway:// handler. Ctrl+C quits; the core follows (stdin EOF).
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { prepareDevRuntime } from './dev-runtime.mjs';

const desktop = join(dirname(fileURLToPath(import.meta.url)), '..');
const repo = join(desktop, '..');
const args = process.argv.slice(2);
const opt = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
const flag = (name) => args.includes(name);
const electron = createRequire(join(desktop, 'package.json'))('electron');

const home = resolve(opt('--home') ?? join(desktop, '.dev', 'home'));
const binary = opt('--core-binary') ? resolve(opt('--core-binary')) : null;
const jar = join(repo, 'app', 'target', 'app.jar');

if (spawnSync(process.execPath, [join(desktop, 'scripts', 'build.mjs')], { stdio: 'inherit' }).status !== 0) process.exit(1);
if (!process.env.GW_UI_DIST && !existsSync(join(repo, 'ui', 'dist', 'mcp-gateway-ui', 'browser', 'index.html'))) {
  console.log('[dev] ui/dist is missing: npm run build -w ui');
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  if (spawnSync(npm, ['run', 'build', '-w', 'ui'], { cwd: repo, stdio: 'inherit' }).status !== 0) process.exit(1);
}
if (!binary && !existsSync(jar)) {
  console.error(`[dev] ${jar} is missing: mvn -B -ntp -pl app -am -DskipTests package`);
  process.exit(1);
}
if (flag('--fresh')) rmSync(home, { recursive: true, force: true });
mkdirSync(home, { recursive: true, mode: 0o700 });

const env = { ...process.env, GW_HOME: home, GW_DEV_CORE_STDERR: process.env.GW_DEV_CORE_STDERR ?? '1' };
for (const name of ['ELECTRON_RUN_AS_NODE', 'NODE_OPTIONS']) delete env[name];
if (flag('--demo')) env.GW_DEMO = '1';
if (opt('--protection') === 'off') env.GW_DESKTOP_PROTECTION = 'off';
if (binary) {
  env.GW_CORE_BINARY = binary;
  if (existsSync(join(dirname(binary), 'native-libs'))) env.GW_CORE_LIBS = join(dirname(binary), 'native-libs');
}
console.log(`[dev] home ${home}; core ${binary ?? jar}`);
const runtime = prepareDevRuntime(electron, join(desktop, '.dev', 'runtime'));
const child = spawn(runtime, [desktop, ...(process.env.GW_DEV_ALLOW_NO_SANDBOX === '1' ? ['--no-sandbox'] : [])],
  { env, stdio: 'inherit' });
const stop = () => child.kill('SIGTERM');
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
child.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
