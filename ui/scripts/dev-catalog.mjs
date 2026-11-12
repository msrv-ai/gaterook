// A local plugin catalog for the development loop (ui/scripts/dev.mjs): the plugins of this checkout, offered in Add
// connection and Connectors › Catalog and installed from it, as a release's catalog would be.
//
// 1. The JARs catalog/index.json names, and plugin-host.jar, are built when one is missing or older than its module's
//    sources (mvn -pl plugin-host,plugins/... -am package, as catalog/publish.sh builds them), under the shared
//    heavy-build lock.
// 2. catalog/index.json is signed with a throwaway key made for this run (catalog/sign-index.mjs), with a sequence
//    from the clock, so a kept home (--keep) accepts it as newer.
// 3. The signed index, its signature and the JARs are served over HTTPS on 127.0.0.1 with a certificate the JDK's
//    keytool makes for this run.
// 4. The core gets the plugin host JAR, the catalog's address and key, and the certificate as its test CA
//    (gateway.test.catalog.ca: a non-release build may then fetch the catalog from loopback).
//
// Nothing here is a release: the key and the certificate live in the development home and die with it.
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs';
import { createServer } from 'node:https';
import { homedir } from 'node:os';
import { basename, dirname, join } from 'node:path';

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
 * Starts the local catalog. Returns the core's system properties and a stop function, or null (with a line saying why)
 * when the plugins can't be built: the development loop then runs without a catalog, as before.
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
  const server = createServer({ pfx: readFileSync(store), passphrase: password }, (req, res) => {
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
  const offered = plugins.map((e) => `${e.id} ${e.version}`).join(', ');
  console.log(`[dev] local plugin catalog: https://127.0.0.1:${port}/ (${offered})`);
  return {
    properties: [
      `-Dgateway.pluginHostJar=${hostJar}`,
      `-Dgateway.plugins.catalog-url=https://127.0.0.1:${port}/`,
      `-Dgateway.plugins.catalog-keys=${trust}`,
      `-Dgateway.test.catalog.ca=${ca}`,
    ],
    stop: () => server.close(),
  };
}
