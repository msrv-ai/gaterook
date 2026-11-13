#!/usr/bin/env node
// What each connector adds to the native core, and whether a bundled connector stays within the budget of
// connectors/delivery.json.
//
// The native image builder reports only its ten largest origins, so this reads a dashboard dump of the build (the size
// of every compiled method), finds each method's class in the jars of app.jar, and assigns each jar to the core or to
// the connector that brings it (from Maven's dependency lists). A connector's share of the image is estimated from
// its share of the code area: the image heap is mostly code metadata, strings and class objects, which follow the code.
//
// 1. Build the native core with a dump (docs/development.md "Native image size"):
//      NATIVE_IMAGE_OPTIONS="-H:+UnlockExperimentalVMOptions -H:DashboardDump=$PWD/app/target/size -H:+DashboardCode
//        -H:+DashboardJson -H:-DashboardBgv" mvn -B -ntp -pl app -am -Pnative -DskipTests package
// 2. node scripts/size-report.mjs --resolve            (asks Maven for every module's dependency list, then reports)
//    node scripts/size-report.mjs --deps <dir>         (reuses lists made earlier)
//    Options: --dump <file> --app-jar <file> --binary <file> --json <file> --check
//
// --check exits 1 when a bundled connector is over the budget and not listed as exempt.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readDelivery, SHARED } from './delivery.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MB = 1_000_000;

// ── ZIP: names and sizes from the central directory, nothing is inflated ─────────────────────────────────────────

/**
 * The entries of the ZIP archive that occupies `bytes[start, end)`: name, compression method, sizes and where its
 * data begins. Only what a Spring Boot jar and the jars inside it need; ZIP64 archives are refused.
 */
export function zipEntries(bytes, start = 0, end = bytes.length) {
  let eocd = -1;
  for (let i = end - 22; i >= Math.max(start, end - 22 - 65_535); i--) {
    if (bytes.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('Not a ZIP archive: no end of central directory.');
  const count = bytes.readUInt16LE(eocd + 10);
  const directorySize = bytes.readUInt32LE(eocd + 12);
  const directoryOffset = bytes.readUInt32LE(eocd + 16);
  if (count === 0xffff || directoryOffset === 0xffffffff) throw new Error('ZIP64 archives are not supported.');
  // Offsets are relative to the archive's first byte, which is `start` for a jar stored inside another one.
  const base = eocd - directorySize - directoryOffset;
  const entries = [];
  let at = base + directoryOffset;
  for (let n = 0; n < count; n++) {
    if (bytes.readUInt32LE(at) !== 0x02014b50) throw new Error('Corrupt ZIP central directory.');
    const method = bytes.readUInt16LE(at + 10);
    const compressedSize = bytes.readUInt32LE(at + 20);
    const size = bytes.readUInt32LE(at + 24);
    const nameLength = bytes.readUInt16LE(at + 28);
    const extraLength = bytes.readUInt16LE(at + 30);
    const commentLength = bytes.readUInt16LE(at + 32);
    const local = base + bytes.readUInt32LE(at + 42);
    const name = bytes.toString('utf8', at + 46, at + 46 + nameLength);
    const dataStart = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    entries.push({ name, method, compressedSize, size, dataStart });
    at += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

/**
 * Which jar of a Spring Boot `app.jar` each class comes from, the jars of each package, and each jar's size.
 * The jars under BOOT-INF/lib are stored uncompressed, so their own directories are read in place.
 */
export function classIndex(appJar) {
  const bytes = readFileSync(appJar);
  const classes = new Map();
  const packages = new Map();
  const jars = new Map();
  for (const entry of zipEntries(bytes)) {
    if (!entry.name.startsWith('BOOT-INF/lib/') || !entry.name.endsWith('.jar')) continue;
    if (entry.method !== 0) throw new Error(`${entry.name} is compressed inside ${appJar}; expected a stored jar.`);
    const jar = entry.name.slice(entry.name.lastIndexOf('/') + 1);
    jars.set(jar, entry.size);
    for (const inner of zipEntries(bytes, entry.dataStart, entry.dataStart + entry.compressedSize)) {
      if (!inner.name.endsWith('.class')) continue;
      let name = inner.name;
      if (name.startsWith('META-INF/versions/')) name = name.split('/').slice(3).join('/');
      const cls = name.slice(0, -'.class'.length).replaceAll('/', '.');
      if (!classes.has(cls)) classes.set(cls, jar);
      const pkg = cls.includes('.') ? cls.slice(0, cls.lastIndexOf('.')) : '';
      if (!packages.has(pkg)) packages.set(pkg, new Set());
      packages.get(pkg).add(jar);
    }
  }
  return { classes, packages, jars };
}

// ── Classes to jars ──────────────────────────────────────────────────────────────────────────────────────────────

export const RUNTIME = '(jdk and image runtime)';
export const PROXIES = '(dynamic proxies)';
const JDK = ['java.', 'javax.', 'jdk.', 'sun.', 'com.sun.', 'org.graalvm.', 'com.oracle.svm.', 'com.oracle.graal.',
  'org.w3c.', 'org.xml.', 'org.ietf.', 'org.jcp.'];
const GENERATED = ['$$Lambda', '$$SpringCGLIB', '$HibernateProxy', '$$EnhancerBySpring', '$$FastClassBySpring'];
/** Package names under ai.msrv.gaterook.connectors that differ from their module's directory. */
const PACKAGE_MODULE = { cloudauth: 'cloud-auth', mcpserver: 'mcp-server', mcp: 'mcp-server' };

/**
 * The origin of a class of the image: a jar of the index, `module:<connector>` or `module:core` for a class generated
 * next to our own code (Spring AOT), the JDK and image runtime, or dynamic proxies.
 */
export function originOfClass(className, index) {
  let cls = className.replaceAll('/', '.');
  for (const marker of GENERATED) {
    const at = cls.indexOf(marker);
    if (at > 0) cls = cls.slice(0, at);
  }
  if (cls.startsWith('jdk.proxy') || cls.startsWith('com.sun.proxy')) return PROXIES;
  for (let probe = cls; ;) {
    const jar = index.classes.get(probe);
    if (jar) return jar;
    const inner = probe.lastIndexOf('$');
    if (inner < 0) break;
    probe = probe.slice(0, inner);
  }
  const connector = /^ai\.msrv\.gaterook\.connectors\.([a-z0-9]+)\./.exec(cls)?.[1];
  if (connector) return `module:${PACKAGE_MODULE[connector] ?? connector}`;
  if (cls.startsWith('ai.msrv.gaterook.')) return 'module:core';
  const pkg = cls.includes('.') ? cls.slice(0, cls.lastIndexOf('.')) : '';
  const owners = index.packages.get(pkg);
  if (owners?.size === 1) return [...owners][0];
  if (JDK.some((prefix) => cls.startsWith(prefix))) return RUNTIME;
  if (owners) return [...owners].sort()[0];
  return `(other: ${cls.split('.')[0]})`;
}

/** Bytes of compiled code by origin, from the `code-breakdown` of a dashboard dump. */
export function codeByOrigin(dump, index) {
  const methods = dump?.['code-breakdown']?.['code-size'];
  if (!Array.isArray(methods)) throw new Error('The dump has no code breakdown: build with -H:+DashboardCode.');
  const out = new Map();
  for (const method of methods) {
    const head = method.name.split('(', 1)[0];
    const cls = head.includes('.') ? head.slice(0, head.lastIndexOf('.')) : head;
    const origin = originOfClass(cls, index);
    out.set(origin, (out.get(origin) ?? 0) + method.size);
  }
  return out;
}

// ── Jars to connectors ───────────────────────────────────────────────────────────────────────────────────────────

const SCOPES = new Set(['compile', 'runtime', 'provided', 'test', 'system']);

/** The jar file names of one `mvn dependency:list` output (`group:artifact:jar[:classifier]:version[:scope]`). */
export function parseDependencyList(text) {
  const jars = new Set();
  for (const line of text.split('\n')) {
    const coordinates = /^\s+([\w.-]+(?::[\w.-]+){3,5})(?:\s|$)/.exec(line)?.[1]?.split(':');
    if (!coordinates || coordinates[2] !== 'jar') continue;
    const rest = coordinates.slice(3);
    if (SCOPES.has(rest.at(-1))) rest.pop();
    const version = rest.pop();
    const classifier = rest.pop();
    jars.add(`${coordinates[1]}-${version}${classifier ? `-${classifier}` : ''}.jar`);
  }
  return jars;
}

/** `<artifactId>.txt` files of a folder, as artifact id → jar names. */
export function readDependencyLists(directory) {
  const lists = new Map();
  for (const file of readdirSync(directory)) {
    if (file.endsWith('.txt')) lists.set(file.slice(0, -4), parseDependencyList(readFileSync(join(directory, file), 'utf8')));
  }
  if (!lists.has('core')) throw new Error(`${directory} has no core.txt: run with --resolve.`);
  return lists;
}

const ownJar = (jars, artifact) => [...jars.keys()].find((jar) => new RegExp(`^${artifact}-\\d`).test(jar));

/**
 * Who each jar of the image belongs to: `core` (the core's own closure, with the shared connector libraries), or the
 * connectors that bring it. A jar several connectors bring belongs to the one the others depend on (the CloudWatch
 * Logs client is the `logs` connector's, though `azure` and `gcp` build on `logs`); otherwise they share it.
 */
export function jarOwners(lists, jars, connectors) {
  const core = new Set(lists.get('core'));
  for (const shared of ['core', ...SHARED.map((name) => `connectors-${name}`)]) {
    for (const jar of lists.get(shared) ?? []) core.add(jar);
    const own = ownJar(jars, shared);
    if (own) core.add(own);
  }
  const present = connectors.filter((name) => ownJar(jars, `connectors-${name}`));
  const closure = new Map(present.map((name) => {
    const set = new Set(lists.get(`connectors-${name}`) ?? []);
    set.add(ownJar(jars, `connectors-${name}`));
    return [name, set];
  }));
  const dependsOn = (a, b) => a !== b && closure.get(a).has(ownJar(jars, `connectors-${b}`));
  const owners = new Map();
  for (const jar of jars.keys()) {
    if (core.has(jar)) { owners.set(jar, ['core']); continue; }
    let users = present.filter((name) => closure.get(name).has(jar));
    const roots = users.filter((name) => !users.some((other) => dependsOn(name, other)));
    if (roots.length) users = roots;
    owners.set(jar, users.length ? users : ['core']);
  }
  return owners;
}

// ── The report ───────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Code and estimated image bytes of the core, the runtime and every connector of the build.
 *
 * @param code       origin → bytes ({@link codeByOrigin})
 * @param owners     jar → owners ({@link jarOwners})
 * @param imageBytes the size of the native executable
 */
export function attribute(code, owners, imageBytes) {
  const byOwner = new Map();
  const add = (owner, bytes) => byOwner.set(owner, (byOwner.get(owner) ?? 0) + bytes);
  let total = 0;
  for (const [origin, bytes] of code) {
    total += bytes;
    if (origin.startsWith('module:')) {
      const name = origin.slice('module:'.length);
      add(name === 'core' || SHARED.includes(name) ? 'core' : name, bytes);
    } else if (origin.startsWith('(')) {
      add('runtime', bytes);
    } else {
      const users = owners.get(origin) ?? ['core'];
      for (const user of users) add(user, bytes / users.length);
    }
  }
  const factor = total ? imageBytes / total : 0;
  const rows = [...byOwner].map(([owner, codeBytes]) => ({ owner, codeBytes: Math.round(codeBytes),
    imageBytes: Math.round(codeBytes * factor) })).sort((a, b) => b.codeBytes - a.codeBytes);
  return { rows, codeBytes: total, imageBytes, factor };
}

/** The budget of connectors/delivery.json against the attributed sizes: problems fail `--check`, notes do not. */
export function checkBudget(attributed, delivery) {
  const budget = delivery.budget.bundledImageMegabytes * MB;
  const exempt = delivery.budget.exempt ?? {};
  const problems = [];
  const notes = [];
  for (const row of attributed.rows) {
    const entry = delivery.connectors[row.owner];
    if (!entry) continue;
    const megabytes = (row.imageBytes / MB).toFixed(1);
    if (entry.delivery === 'module') {
      problems.push(`${row.owner} is delivered as a module but is compiled into this image (${megabytes} MB)`);
    } else if (entry.delivery === 'bridge') {
      notes.push(`${row.owner} is a bridge connector: ${megabytes} MB leave the image with the release after`);
    } else if (row.imageBytes > budget && !exempt[row.owner]) {
      problems.push(`${row.owner} adds ${megabytes} MB to the image, over the ${delivery.budget.bundledImageMegabytes} MB `
        + 'budget of a bundled connector: deliver it as a module or list it in budget.exempt with the reason');
    } else if (row.imageBytes <= budget && exempt[row.owner]) {
      notes.push(`${row.owner} is listed in budget.exempt but adds only ${megabytes} MB`);
    }
  }
  return { problems, notes };
}

const megabytes = (bytes) => (bytes / MB).toFixed(bytes < 10 * MB ? 2 : 1).padStart(8);

/** The report as text: totals, then one line per owner. */
export function render(attributed, delivery) {
  const lines = [`Native image ${megabytes(attributed.imageBytes).trim()} MB, code area ${megabytes(attributed.codeBytes).trim()} MB `
    + `(image estimated as code x ${attributed.factor.toFixed(2)})`, '',
  `${'owner'.padEnd(14)} ${'code MB'.padStart(8)} ${'image MB'.padStart(8)}  delivery`];
  for (const row of attributed.rows) {
    const entry = delivery.connectors[row.owner];
    const note = entry ? entry.delivery + (delivery.budget.exempt?.[row.owner] ? ' (exempt)' : '') : '';
    lines.push(`${row.owner.padEnd(14)} ${megabytes(row.codeBytes)} ${megabytes(row.imageBytes)}  ${note}`);
  }
  const connectors = attributed.rows.filter((row) => delivery.connectors[row.owner]);
  const sum = (key) => connectors.reduce((total, row) => total + row[key], 0);
  lines.push('', `${'connectors'.padEnd(14)} ${megabytes(sum('codeBytes'))} ${megabytes(sum('imageBytes'))}`);
  return lines.join('\n');
}

// ── Command line ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Asks Maven for the runtime dependency list of the core and of every connector module, into `directory`. */
export function resolveDependencyLists(root, directory, delivery, run = spawnSync) {
  mkdirSync(directory, { recursive: true });
  const modules = ['core', ...SHARED.map((name) => `connectors/${name}`),
    ...Object.keys(delivery.connectors).map((name) => `connectors/${name}`)];
  const result = run('mvn', ['-B', '-ntp', '-q', 'dependency:list', '-DincludeScope=runtime', '-Dmdep.outputScope=false',
    `-DoutputFile=${directory}/\${project.artifactId}.txt`, '-pl', modules.join(',')],
  { cwd: root, stdio: ['ignore', 'inherit', 'inherit'], shell: process.platform === 'win32' });
  if (result.status !== 0) throw new Error('Maven could not list the dependencies (build the modules first).');
}

const readJson = (file) => JSON.parse(readFileSync(file, 'utf8'));

export function parseArgs(argv, root = ROOT) {
  const o = { dump: join(root, 'app', 'target', 'size.dump'), appJar: join(root, 'app', 'target', 'app.jar'),
    binary: join(root, 'app', 'target', process.platform === 'win32' ? 'gaterook-core.exe' : 'gaterook-core'),
    deps: join(root, 'app', 'target', 'size-report', 'deps'), resolve: false, check: false, json: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dump') o.dump = resolve(argv[++i]);
    else if (a === '--app-jar') o.appJar = resolve(argv[++i]);
    else if (a === '--binary') o.binary = resolve(argv[++i]);
    else if (a === '--deps') o.deps = resolve(argv[++i]);
    else if (a === '--json') o.json = resolve(argv[++i]);
    else if (a === '--resolve') o.resolve = true;
    else if (a === '--check') o.check = true;
    else throw new Error(`unknown argument ${a}`);
  }
  return o;
}

export function main(argv, root = ROOT) {
  const o = parseArgs(argv, root);
  for (const [what, file] of [['dashboard dump', o.dump], ['app.jar', o.appJar], ['native core', o.binary]]) {
    if (!existsSync(file)) throw new Error(`No ${what} at ${file}. See the head of scripts/size-report.mjs.`);
  }
  const delivery = readDelivery(root);
  if (o.resolve) resolveDependencyLists(root, o.deps, delivery);
  const index = classIndex(o.appJar);
  const owners = jarOwners(readDependencyLists(o.deps), index.jars, Object.keys(delivery.connectors));
  const attributed = attribute(codeByOrigin(readJson(o.dump), index), owners, statSync(o.binary).size);
  const budget = checkBudget(attributed, delivery);
  console.log(render(attributed, delivery));
  for (const note of budget.notes) console.log(`note: ${note}`);
  for (const problem of budget.problems) console.error(`size: ${problem}`);
  if (o.json) writeFileSync(o.json, JSON.stringify({ ...attributed, ...budget }, null, 2) + '\n');
  return o.check && budget.problems.length ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    console.error(`size-report: ${error.message}`);
    process.exitCode = 2;
  }
}
