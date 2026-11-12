#!/usr/bin/env node
// connectors/delivery.json says how each connector module reaches the application: `bundled` (compiled into the core)
// or `module` (not part of the installer). This helper reads it and checks that the Maven build agrees: a bundled
// connector is a dependency of the `app` assembly, a module one is only in its `all-bundled` profile, and every
// connector directory has an entry.
//
//   node scripts/delivery.mjs            print the map
//   node scripts/delivery.mjs --check    exit 1 with every disagreement
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** Shared libraries under connectors/ that are not connectors themselves. */
export const SHARED = Object.freeze(['support', 'cloud-auth']);
const DELIVERIES = Object.freeze(['bundled', 'module']);
const TYPE_ID = /^[a-z][a-z0-9-]{0,47}$/;

/** The delivery map as written, after checking its own shape. */
export function readDelivery(root = ROOT) {
  const file = join(root, 'connectors', 'delivery.json');
  const map = JSON.parse(readFileSync(file, 'utf8'));
  const problems = [];
  if (map.schema !== 1) problems.push('schema must be 1');
  if (!map.connectors || typeof map.connectors !== 'object') problems.push('connectors must be an object');
  const megabytes = map.budget?.bundledImageMegabytes;
  if (typeof megabytes !== 'number' || !(megabytes > 0)) problems.push('budget.bundledImageMegabytes must be a positive number');
  const seen = new Map();
  for (const [name, entry] of Object.entries(map.connectors ?? {})) {
    if (!DELIVERIES.includes(entry?.delivery)) problems.push(`${name}: delivery must be one of ${DELIVERIES.join(', ')}`);
    if (!Array.isArray(entry?.types) || entry.types.length === 0) problems.push(`${name}: types must list its connector types`);
    for (const type of entry?.types ?? []) {
      if (!TYPE_ID.test(type)) problems.push(`${name}: ${type} is not a connector type id`);
      if (seen.has(type)) problems.push(`${type} is listed by both ${seen.get(type)} and ${name}`);
      seen.set(type, name);
    }
    if (entry?.delivery === 'module' && !entry.why) problems.push(`${name}: a module says why it is not compiled in`);
  }
  for (const name of Object.keys(map.budget?.exempt ?? {})) {
    if (!map.connectors?.[name]) problems.push(`budget.exempt names ${name}, which is not a connector`);
    else if (map.connectors[name].delivery !== 'bundled') problems.push(`budget.exempt names ${name}, which is not bundled`);
  }
  if (problems.length) throw new Error(`connectors/delivery.json: ${problems.join('; ')}`);
  return map;
}

const withoutComments = (xml) => xml.replace(/<!--[\s\S]*?-->/g, ' ');

/** Connector artifacts among the `<dependency>` elements of an XML fragment, as their directory names. */
function connectorDependencies(xml) {
  const out = [];
  for (const dependency of xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const artifact = /<artifactId>\s*connectors-([a-z0-9-]+)\s*<\/artifactId>/.exec(dependency[1])?.[1];
    if (artifact && !/<scope>\s*test\s*<\/scope>/.test(dependency[1])) out.push(artifact);
  }
  return out;
}

/** What `app/pom.xml` puts on the core's classpath: always, and only with the `all-bundled` profile. */
export function appAssembly(root = ROOT) {
  const pom = withoutComments(readFileSync(join(root, 'app', 'pom.xml'), 'utf8'));
  const profilesAt = pom.indexOf('<profiles>');
  const main = profilesAt < 0 ? pom : pom.slice(0, profilesAt);
  const always = connectorDependencies(main);
  let allBundled = [];
  for (const profile of (profilesAt < 0 ? '' : pom.slice(profilesAt)).matchAll(/<profile>([\s\S]*?)<\/profile>/g)) {
    if (/<id>\s*all-bundled\s*<\/id>/.test(profile[1])) allBundled = connectorDependencies(profile[1]);
  }
  return { always, allBundled };
}

/** Connector directories of the checkout and the modules the root pom builds. */
function sources(root) {
  const directories = readdirSync(join(root, 'connectors'), { withFileTypes: true })
    .filter((d) => d.isDirectory() && existsSync(join(root, 'connectors', d.name, 'pom.xml')))
    .map((d) => d.name).filter((name) => !SHARED.includes(name));
  const pom = withoutComments(readFileSync(join(root, 'pom.xml'), 'utf8'));
  const modules = [...pom.matchAll(/<module>\s*connectors\/([a-z0-9-]+)\s*<\/module>/g)].map((m) => m[1]);
  return { directories, modules };
}

/** Every disagreement between the delivery map and the build; empty when they agree. */
export function checkDelivery(root = ROOT) {
  const map = readDelivery(root);
  const { always, allBundled } = appAssembly(root);
  const { directories, modules } = sources(root);
  const problems = [];
  for (const name of directories) {
    if (!map.connectors[name]) problems.push(`connectors/${name} has no entry in connectors/delivery.json`);
    if (!modules.includes(name)) problems.push(`connectors/${name} is not a module of the root pom`);
  }
  for (const [name, entry] of Object.entries(map.connectors)) {
    if (!directories.includes(name)) {
      problems.push(`connectors/delivery.json lists ${name}, but connectors/${name} is not a connector module`);
      continue;
    }
    const inAlways = always.includes(name);
    const inProfile = allBundled.includes(name);
    if (entry.delivery === 'bundled') {
      if (!inAlways) problems.push(`${name} is bundled but app/pom.xml does not depend on connectors-${name}`);
      if (inProfile) problems.push(`${name} is bundled, so it does not belong in the all-bundled profile`);
    } else {
      if (inAlways) problems.push(`${name} is a module but app/pom.xml always depends on connectors-${name}`);
      if (!inProfile) problems.push(`${name} is a module but the all-bundled profile of app/pom.xml lacks connectors-${name}`);
    }
  }
  for (const name of [...always, ...allBundled]) {
    if (!map.connectors[name] && !SHARED.includes(name)) {
      problems.push(`app/pom.xml depends on connectors-${name}, which connectors/delivery.json does not list`);
    }
  }
  return problems;
}

/** The connector types by delivery, for callers that only need the ids. */
export function typesByDelivery(root = ROOT) {
  const map = readDelivery(root);
  const out = { bundled: [], module: [] };
  for (const entry of Object.values(map.connectors)) out[entry.delivery].push(...entry.types);
  return out;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.includes('--check')) {
    const problems = checkDelivery();
    if (problems.length) {
      console.error(problems.map((p) => `delivery: ${p}`).join('\n'));
      process.exit(1);
    }
    console.log('delivery: connectors/delivery.json and the build agree');
  } else {
    const map = readDelivery();
    for (const [name, entry] of Object.entries(map.connectors)) {
      console.log(`${entry.delivery.padEnd(8)} ${name.padEnd(12)} ${entry.types.join(', ')}`);
    }
  }
}
