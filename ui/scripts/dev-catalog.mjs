// A local plugin catalog and a local list of connector modules for the development loop (ui/scripts/dev.mjs): the
// plugins of this checkout, offered in Add connection and Connectors › Catalog and installed from the catalog as a
// release's would be, and the connector modules whose source lives in the modules repository
// (gaterook/gaterook-plugins: ClickHouse, DuckDB, H2, Microsoft Access), which the development core doesn't compile
// in and installs from the list when one of them is chosen, as a release installs them from GateRook's list.
//
// 1. The JARs catalog/index.json names and plugin-host.jar are built when one is missing or older than its module's
//    sources (mvn -pl plugin-host,plugins/... -am package), under the shared heavy-build lock.
// 2. The module packages come from a checkout of the modules repository: the folder GATEROOK_MODULES_REPO names, or
//    gaterook-plugins beside this checkout. They are built there (its README), never here: a module that isn't
//    built is left out, and without a checkout the loop runs without a local list of modules.
// 3. catalog/index.json, and a list of the built module packages, are signed with a throwaway key made for this run
//    (catalog/sign-index.mjs), with a sequence from the clock, so a kept home (--keep) accepts them as newer.
// 4. Each signed index, its signature and the files it pins are served over HTTPS on 127.0.0.1, the catalog and the
//    list of modules on a port each, with one certificate the JDK's keytool makes for this run.
// 5. The core gets the plugin host JAR, the addresses and the key of both, and the certificate as its test CA
//    (gateway.test.catalog.ca: a non-release build may then fetch them from loopback).
//
// Nothing here is a release: the key and the certificate live in the development home and die with it.
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:https';
import { homedir } from 'node:os';
import { basename, dirname, join, resolve as absolute } from 'node:path';
import { listEntry, modules as checkoutModules, readPackage } from '../../scripts/modules.mjs';

/** The JAR a catalog entry pins, as the build leaves it in plugins/<dir>/target, or null when it isn't built. */
function builtJar(root, entry) {
  const name = entry.artifact?.url;
  const targets = readdirSync(join(root, 'plugins'), { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .map((d) => join(root, 'plugins', d.name, 'target'))
    .filter((t) => existsSync(t));
  for (const target of targets) {
    if (name && existsSync(join(target, name))) return join(target, name);
  }
  // Jira Service Desk keeps Maven's own name: plugins-jira-service-desk-<build>-<version>.jar.
  const own = join(root, 'plugins', entry.id, 'target');
  if (existsSync(own)) {
    const match = readdirSync(own).find((f) => f.endsWith(`-${entry.version}.jar`) && !f.startsWith('original-'));
    if (match) return join(own, match);
  }
  return null;
}

/** The newest modification time of a module's own inputs: pom.xml and everything under src. */
function newestSource(module) {
  let newest = existsSync(join(module, 'pom.xml')) ? statSync(join(module, 'pom.xml')).mtimeMs : 0;
  const walk = (dir) => {
    for (const d of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, d.name);
      if (d.isDirectory()) walk(path);
      else newest = Math.max(newest, statSync(path).mtimeMs);
    }
  };
  if (existsSync(join(module, 'src'))) walk(join(module, 'src'));
  return newest;
}

/** Whether a JAR is missing or older than its module's sources (a module is the JAR's target/..). */
function stale(jar) {
  return !jar || !existsSync(jar) || statSync(jar).mtimeMs < newestSource(dirname(dirname(jar)));
}

/**
 * The checkout of the modules repository whose packages the loop offers: the folder GATEROOK_MODULES_REPO names, else
 * gaterook-plugins beside this checkout; null when there is none.
 */
export function modulesRepo(root, env = process.env) {
  const folder = env.GATEROOK_MODULES_REPO ? absolute(env.GATEROOK_MODULES_REPO) : join(root, '..', 'gaterook-plugins');
  return existsSync(join(folder, 'modules')) ? folder : null;
}

/** The connector modules of a modules repository checkout, each with its package when it is built there. */
function repositoryModules(repo) {
  return repo ? checkoutModules(repo) : [];
}

/** Whether a module's package is older than its sources: it is offered as it is, with a line that says so. */
function staleModule(repo, module) {
  return module.jar && statSync(module.jar).mtimeMs < newestSource(join(repo, 'modules', module.dir));
}

/** Serves `files` (name → path) by name over HTTPS on a free port of 127.0.0.1. */
async function serve(files, tls) {
  const server = createServer({ pfx: readFileSync(tls.store), passphrase: tls.password }, (req, res) => {
    const name = decodeURIComponent((req.url ?? '/').split('?')[0]).replace(/^\/+/, '');
    const file = req.method === 'GET' ? files.get(basename(name) === name ? name : '') : undefined;
    if (!file) {
      res.writeHead(404).end();
      return;
    }
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': name.endsWith('.json') ? 'application/json' : 'application/octet-stream',
      'Content-Length': body.length }).end(body);
  });
  const port = await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
  return { port, stop: () => server.close() };
}

function build(root, modules) {
  console.log(`[dev] building the catalog's plugins once (mvn -pl ${modules.join(',')} -am -DskipTests package)`);
  const mvn = ['mvn', '-B', '-ntp', '-pl', modules.join(','), '-am', '-DskipTests', 'package'];
  const lock = join(homedir(), '.cache', 'mcp-gateway', 'heavy.lock');
  const hasFlock = spawnSync('flock', ['--version'], { stdio: 'ignore' }).status === 0;
  if (hasFlock) mkdirSync(dirname(lock), { recursive: true });
  const [cmd, ...rest] = hasFlock ? ['flock', '-s', lock, ...mvn] : mvn;
  const r = spawnSync(cmd, rest, { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' });
  return r.status === 0;
}

function run(cmd, args, what) {
  const r = spawnSync(cmd, args, { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`${what} failed: ${(r.stderr || r.stdout || '').trim()}`);
  return r.stdout;
}

/**
 * Starts the local catalog and the local list of connector modules. Returns the core's system properties and a stop
 * function, or null (with a line saying why) when the plugins can't be built: the development loop then runs without
 * them, as before.
 */
export async function startDevCatalog({ root, home, java }) {
  const index = JSON.parse(readFileSync(join(root, 'catalog', 'index.json'), 'utf8'));
  const plugins = index.entries.filter((e) => e.kind === 'plugin');
  const hostJar = join(root, 'plugin-host', 'target', 'plugin-host.jar');
  const outdated = () => [...plugins.filter((e) => stale(builtJar(root, e))).map((e) => e.id),
    ...(stale(hostJar) ? ['plugin-host'] : [])];
  if (outdated().length) {
    console.log(`[dev] missing or older than their sources: ${outdated().join(', ')}`);
    const modules = ['plugin-host', ...readdirSync(join(root, 'plugins'), { withFileTypes: true })
      .filter((d) => d.isDirectory() && d.name !== 'test-fixtures')
      .map((d) => `plugins/${d.name}`)];
    if (!build(root, modules)) {
      console.error('[dev] the plugins did not build: running without the local catalog');
      return null;
    }
    const still = plugins.filter((e) => !builtJar(root, e)).map((e) => e.id);
    if (still.length || !existsSync(hostJar)) {
      console.error(`[dev] not built: ${[...still, ...(existsSync(hostJar) ? [] : ['plugin-host'])].join(', ')}: `
        + 'running without the local catalog');
      return null;
    }
  }
  // The modules of the modules repository's checkout. One whose package isn't built there is left out of the list:
  // its card is missing, the rest of the loop works.
  const repo = modulesRepo(root);
  const packages = repositoryModules(repo).filter((m) => m.jar);
  if (!repo) {
    console.log('[dev] no checkout of gaterook/gaterook-plugins beside this one (or in GATEROOK_MODULES_REPO): '
      + 'ClickHouse, DuckDB, H2 and Microsoft Access are not offered');
  } else {
    const unbuilt = repositoryModules(repo).filter((m) => !m.jar).map((m) => m.dir);
    const older = packages.filter((m) => staleModule(repo, m)).map((m) => m.dir);
    if (unbuilt.length) console.log(`[dev] not built in ${repo}: ${unbuilt.join(', ')} (mvn -B -ntp package -DskipTests there)`);
    if (older.length) console.log(`[dev] older than their sources in ${repo}: ${older.join(', ')}`);
  }

  const dir = join(home, 'dev-catalog');
  rmSync(dir, { recursive: true, force: true });
  const assets = join(dir, 'assets');
  const out = join(dir, 'out');
  mkdirSync(assets, { recursive: true });
  mkdirSync(out, { recursive: true });
  for (const entry of plugins) copyFileSync(builtJar(root, entry), join(assets, entry.artifact.url));

  // A key for this run only; keygen prints the trust line the core takes.
  const sign = join(root, 'catalog', 'sign-index.mjs');
  const keyFile = join(dir, 'catalog-key.pem');
  const keyId = 'dev-catalog';
  const keygen = run(process.execPath, [sign, 'keygen', '--key-file', keyFile, '--key-id', keyId], 'catalog keygen');
  const trust = /gateway\.plugins\.catalog-keys=(\S+)/.exec(keygen)?.[1];
  if (!trust) throw new Error('catalog keygen printed no trust line');
  run(process.execPath, [sign, 'sign', '--index', join(root, 'catalog', 'index.json'), '--artifacts', assets,
    '--out', out, '--key-id', keyId, '--key-file', keyFile, '--days', '30',
    '--sequence', String(Math.floor(Date.now() / 1000))], 'catalog signing');

  // The list of connector modules: an entry per built package, as its own manifest describes it, signed with the same
  // key under the same clock.
  const moduleAssets = join(dir, 'modules', 'assets');
  const moduleOut = join(dir, 'modules', 'out');
  mkdirSync(moduleAssets, { recursive: true });
  mkdirSync(moduleOut, { recursive: true });
  const moduleFiles = new Map();
  if (packages.length) {
    const entries = packages.map((m) => {
      const built = readPackage(m.jar);
      copyFileSync(m.jar, join(moduleAssets, basename(m.jar)));
      moduleFiles.set(basename(m.jar), join(moduleAssets, basename(m.jar)));
      return listEntry(built.manifest, { url: basename(m.jar), sha256: built.sha256, size: built.size });
    });
    const source = join(dir, 'modules', 'index.json');
    writeFileSync(source, `${JSON.stringify({ schema: 1, entries, revoked: [] }, null, 2)}\n`);
    run(process.execPath, [sign, 'sign', '--index', source, '--artifacts', moduleAssets, '--out', moduleOut,
      '--key-id', keyId, '--key-file', keyFile, '--days', '30', '--sequence', String(Math.floor(Date.now() / 1000))],
    'signing the list of connector modules');
    moduleFiles.set('index.json', join(moduleOut, 'index.json'));
    moduleFiles.set('index.json.sig', join(moduleOut, 'index.json.sig'));
  }

  // TLS for 127.0.0.1 from the JDK's keytool: the core trusts this certificate as the catalog's only CA.
  const tool = process.platform === 'win32' ? 'keytool.exe' : 'keytool';
  const keytool = java.includes('/') || java.includes('\\') ? join(dirname(java), tool) : tool;
  const store = join(dir, 'tls.p12');
  const password = randomBytes(18).toString('base64url');
  run(keytool, ['-genkeypair', '-alias', keyId, '-keyalg', 'EC', '-groupname', 'secp256r1', '-dname', 'CN=127.0.0.1',
    '-ext', 'SAN=ip:127.0.0.1', '-validity', '30', '-keystore', store, '-storetype', 'PKCS12', '-storepass', password,
    '-keypass', password], 'keytool -genkeypair');
  const ca = join(dir, 'tls-ca.pem');
  run(keytool, ['-exportcert', '-rfc', '-alias', keyId, '-keystore', store, '-storepass', password, '-file', ca],
    'keytool -exportcert');

  // Only the files of the catalog, by name: index.json, its signature and each pinned JAR.
  const files = new Map([['index.json', join(out, 'index.json')], ['index.json.sig', join(out, 'index.json.sig')],
    ...plugins.map((e) => [e.artifact.url, join(assets, e.artifact.url)])]);
  const tls = { store, password };
  const catalog = await serve(files, tls);
  const offered = plugins.map((e) => `${e.id} ${e.version}`).join(', ');
  console.log(`[dev] local plugin catalog: https://127.0.0.1:${catalog.port}/ (${offered})`);
  const properties = [
    `-Dgateway.pluginHostJar=${hostJar}`,
    `-Dgateway.plugins.catalog-url=https://127.0.0.1:${catalog.port}/`,
    `-Dgateway.plugins.catalog-keys=${trust}`,
    `-Dgateway.test.catalog.ca=${ca}`,
  ];
  // The list of modules on a port of its own: it has an index.json too.
  const modules = packages.length ? await serve(moduleFiles, tls) : null;
  if (modules) {
    const listed = packages.map((m) => `${m.descriptor.id} ${m.descriptor.version}`).join(', ');
    console.log(`[dev] local list of connector modules: https://127.0.0.1:${modules.port}/ (${listed})`);
    properties.push(`-Dgateway.modules.channel-url=https://127.0.0.1:${modules.port}/`,
      `-Dgateway.modules.channel-keys=${trust}`);
  } else {
    console.log('[dev] running without the local list of modules');
  }
  return {
    properties,
    stop: () => {
      catalog.stop();
      modules?.stop();
    },
  };
}
