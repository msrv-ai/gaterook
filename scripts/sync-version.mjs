#!/usr/bin/env node
// The root POM owns the application version. npm manifests and lockfile entries are derived mirrors.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PACKAGES = ['', 'ui', 'desktop', 'bridge'];
const NUMBER = '(?:0|[1-9][0-9]*)';
const PRERELEASE = `(?:${NUMBER}|[0-9]*[A-Za-z-][0-9A-Za-z-]*)`;
const SEMVER = new RegExp(`^${NUMBER}\\.${NUMBER}\\.${NUMBER}(?:-${PRERELEASE}(?:\\.${PRERELEASE})*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$`);

/** Read the literal Maven CI-friendly revision without needing Maven or installed npm dependencies. */
export function readApplicationVersion(root = ROOT) {
  const pom = readFileSync(join(root, 'pom.xml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  const properties = /<properties>([\s\S]*?)<\/properties>/.exec(pom)?.[1] ?? '';
  const revisions = [...properties.matchAll(/<revision>([^<]*)<\/revision>/g)];
  const version = revisions[0]?.[1].trim();
  if (revisions.length !== 1 || !SEMVER.test(version ?? '')) {
    throw new Error('pom.xml must define one literal semantic application version in <properties><revision>.');
  }
  return version;
}

/** Update only application metadata; dependency resolutions and independent package versions stay intact. */
export function syncVersions(root = ROOT, { check = false } = {}) {
  const version = readApplicationVersion(root);
  const files = new Map();
  const read = (path) => {
    const source = readFileSync(join(root, path), 'utf8');
    const value = JSON.parse(source);
    files.set(path, { source, value });
    return value;
  };
  for (const directory of PACKAGES) {
    const manifest = read(join(directory, 'package.json'));
    manifest.version = version;
    if (directory === 'desktop') manifest.devDependencies['@gaterook/broker'] = version;
  }
  const lock = read('package-lock.json');
  lock.version = version;
  for (const directory of PACKAGES) {
    if (!lock.packages?.[directory]) throw new Error(`package-lock.json is missing workspace ${directory || '(root)'}.`);
    lock.packages[directory].version = version;
  }
  lock.packages.desktop.devDependencies['@gaterook/broker'] = version;

  // Validate every input before writing any file. Unchanged mirrors keep their timestamps.
  const changed = [...files].filter(([, { source, value }]) => JSON.stringify(JSON.parse(source)) !== JSON.stringify(value));
  if (check && changed.length) {
    throw new Error(`Application version mirrors are stale: ${changed.map(([path]) => path).join(', ')}. Run npm run version:sync.`);
  }
  if (!check) {
    for (const [path, { value }] of changed) writeFileSync(join(root, path), JSON.stringify(value, null, 2) + '\n');
  }
  return { version, changed: changed.map(([path]) => path) };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    if (args.some((arg) => arg !== '--check')) throw new Error('Usage: node scripts/sync-version.mjs [--check]');
    const result = syncVersions(ROOT, { check: args.includes('--check') });
    console.log(`GateRook ${result.version}: ${result.changed.length ? `updated ${result.changed.join(', ')}` : 'version mirrors are up to date'}`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
