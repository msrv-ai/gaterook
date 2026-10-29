import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { readApplicationVersion, syncVersions } from './sync-version.mjs';

function fixture(t, version = '2.7.3-rc.1') {
  const scratch = new URL('../ai/', import.meta.url);
  mkdirSync(scratch, { recursive: true });
  const root = mkdtempSync(new URL('version-test-', scratch));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(join(root, 'pom.xml'), `<project><parent><version>99.0.0</version></parent>
    <version>\${revision}</version><properties><!-- <revision>8.0.0</revision> -->
    <revision>${version}</revision><connector-api.version>1.0.0</connector-api.version></properties></project>`);
  const packages = {};
  for (const directory of ['', 'ui', 'desktop', 'bridge']) {
    mkdirSync(join(root, directory), { recursive: true });
    packages[directory] = { name: directory || 'gaterook', version: '1.2.0',
      devDependencies: { ...(directory === 'desktop' ? { '@gaterook/broker': '1.2.0' } : {}), unrelated: '1.2.0' } };
    writeFileSync(join(root, directory, 'package.json'), JSON.stringify(packages[directory], null, 2) + '\n');
  }
  packages.design = { version: '1.2.0' };
  packages['node_modules/unrelated'] = { version: '1.2.0', resolved: 'https://example.com/unrelated.tgz', integrity: 'unchanged' };
  packages['node_modules/@gaterook/broker'] = { resolved: 'bridge', link: true };
  writeFileSync(join(root, 'package-lock.json'), JSON.stringify({ version: '1.2.0', lockfileVersion: 3, packages }, null, 2) + '\n');
  return root;
}

const json = (root, file) => JSON.parse(readFileSync(join(root, file), 'utf8'));

test('a single POM change updates all npm application mirrors without changing dependencies', (t) => {
  const root = fixture(t);
  const before = json(root, 'package-lock.json');
  assert.equal(readApplicationVersion(root), '2.7.3-rc.1');
  assert.throws(() => syncVersions(root, { check: true }), /mirrors are stale/);
  assert.deepEqual(json(root, 'package-lock.json'), before);
  assert.equal(syncVersions(root).changed.length, 5);
  const lock = json(root, 'package-lock.json');
  assert.equal(lock.version, '2.7.3-rc.1');
  for (const directory of ['', 'ui', 'desktop', 'bridge']) {
    assert.equal(json(root, join(directory, 'package.json')).version, '2.7.3-rc.1');
    assert.equal(lock.packages[directory].version, '2.7.3-rc.1');
    assert.equal(lock.packages[directory].devDependencies.unrelated, '1.2.0');
  }
  assert.equal(json(root, 'desktop/package.json').devDependencies['@gaterook/broker'], '2.7.3-rc.1');
  assert.equal(lock.packages.desktop.devDependencies['@gaterook/broker'], '2.7.3-rc.1');
  for (const path of ['design', 'node_modules/unrelated', 'node_modules/@gaterook/broker']) {
    assert.deepEqual(lock.packages[path], before.packages[path]);
  }
  const modified = statSync(join(root, 'package-lock.json')).mtimeMs;
  assert.deepEqual(syncVersions(root).changed, []);
  assert.deepEqual(syncVersions(root, { check: true }).changed, []);
  assert.equal(statSync(join(root, 'package-lock.json')).mtimeMs, modified);
});

test('rejects unresolved or invalid revisions before changing any mirror', (t) => {
  for (const version of ['', '${other.version}', '1.2', '01.2.3', '1.2.3-01', '1.2.3-rc..1', '1.2.3+']) {
    const root = fixture(t, version);
    assert.throws(() => syncVersions(root), /literal semantic application version/);
    assert.equal(json(root, 'package.json').version, '1.2.0');
  }
});

test('validates the lockfile before writing package manifests', (t) => {
  const root = fixture(t, '3.1.0-SNAPSHOT');
  const lock = json(root, 'package-lock.json');
  delete lock.packages.bridge;
  writeFileSync(join(root, 'package-lock.json'), JSON.stringify(lock));
  assert.throws(() => syncVersions(root), /missing workspace bridge/);
  assert.equal(json(root, 'desktop/package.json').version, '1.2.0');
});
