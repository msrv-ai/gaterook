import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { deflateRawSync } from 'node:zlib';
import {
  bump, bumped, checkModules, listEntry, locked, modules, readZipEntry, releaseUrl, sourceIndex, status,
} from './modules.mjs';

const BASE = 'https://github.com/gaterook/gaterook-plugins/releases/download/';

/** A ZIP with one deflated entry and one stored entry, as a JAR holds its manifest and its classes. */
function zip(files) {
  const locals = [];
  const central = [];
  let offset = 0;
  for (const [name, { data, stored }] of Object.entries(files)) {
    const raw = Buffer.from(data);
    const body = stored ? raw : deflateRawSync(raw);
    const head = Buffer.alloc(30);
    head.writeUInt32LE(0x04034b50, 0);
    head.writeUInt16LE(stored ? 0 : 8, 8);
    head.writeUInt32LE(body.length, 18);
    head.writeUInt32LE(raw.length, 22);
    head.writeUInt16LE(Buffer.byteLength(name), 26);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(stored ? 0 : 8, 10);
    entry.writeUInt32LE(body.length, 20);
    entry.writeUInt32LE(raw.length, 24);
    entry.writeUInt16LE(Buffer.byteLength(name), 28);
    entry.writeUInt32LE(offset, 42);
    locals.push(head, Buffer.from(name), body);
    central.push(entry, Buffer.from(name));
    offset += 30 + Buffer.byteLength(name) + body.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(Object.keys(files).length, 8);
  end.writeUInt16LE(Object.keys(files).length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

const manifest = (version) => ({
  manifestVersion: 2, id: 'alpha', version, name: 'Alpha', description: 'The first module.',
  requires: { gateway: '>=0.9.2 & <1.0.0', connectorApi: '>=1.2.0 & <2.0.0' },
  types: [{ id: 'alpha', name: 'Alpha', icon: 'database', group: 'Databases', description: 'Reads alpha.',
    tools: [{ name: 'alpha_read' }, { name: 'alpha_write' }] }],
});

/** A checkout with the module `alpha`, built unless `built` is false; `content` changes its package's bytes. */
function fixture(t, { version = '1.0.0', built = true, content = 'classes', delivery = 'module', pomVersion = version,
  inReactor = true } = {}) {
  const scratch = new URL('../ai/', import.meta.url);
  mkdirSync(scratch, { recursive: true });
  const root = mkdtempSync(new URL('modules-test-', scratch));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'modules', 'alpha', 'target'), { recursive: true });
  mkdirSync(join(root, 'connectors', 'alpha'), { recursive: true });
  writeFileSync(join(root, 'connectors', 'alpha', 'pom.xml'), '<project/>');
  writeFileSync(join(root, 'connectors', 'delivery.json'), JSON.stringify({
    schema: 1, budget: { bundledImageMegabytes: 3, exempt: {} },
    connectors: { alpha: { types: ['alpha'], delivery, ...(delivery === 'module' ? { why: 'Its driver is heavy.' } : {}) } },
  }));
  writeFileSync(join(root, 'pom.xml'), `<project><modules><module>connectors/alpha</module>${inReactor
    ? '<module>modules/alpha</module>' : ''}</modules></project>`);
  writeFileSync(join(root, 'modules', 'alpha', 'module.json'), `${JSON.stringify({ id: 'alpha', version, name: 'Alpha' }, null, 2)}\n`);
  writeFileSync(join(root, 'modules', 'alpha', 'pom.xml'),
    `<project><properties><module.version>${pomVersion}</module.version></properties></project>`);
  if (built) {
    writeFileSync(join(root, 'modules', 'alpha', 'target', `alpha-${version}.jar`), zip({
      'mcp-gateway-plugin.json': { data: JSON.stringify(manifest(version)) },
      'ai/Alpha.class': { data: content, stored: true },
    }));
  }
  return root;
}

test('this checkout: its modules agree with the delivery map and the poms', () => {
  assert.deepEqual(checkModules(), []);
  assert.ok(modules().some((m) => m.descriptor.id === 'cassandra'));
});

test('an entry of a JAR is read whether it is deflated or stored', () => {
  const bytes = zip({ 'a.json': { data: '{"a":1}' }, 'b.bin': { data: 'stored', stored: true } });
  assert.equal(readZipEntry(bytes, 'a.json').toString(), '{"a":1}');
  assert.equal(readZipEntry(bytes, 'b.bin').toString(), 'stored');
  assert.equal(readZipEntry(bytes, 'missing'), null);
});

test('the layout is checked: versions, the reactor and the delivery map', (t) => {
  assert.deepEqual(checkModules(fixture(t)), []);
  assert.deepEqual(checkModules(fixture(t, { pomVersion: '0.9.0' })),
    ['modules/alpha: pom.xml says module.version 0.9.0, module.json says 1.0.0']);
  assert.deepEqual(checkModules(fixture(t, { inReactor: false })), ['modules/alpha is not a module of the root pom']);
  assert.deepEqual(checkModules(fixture(t, { delivery: 'bundled' })),
    ["modules/alpha packages a connector that connectors/delivery.json doesn't deliver as a module"]);
});

test('a connector that exists only as a module needs its module and nothing under connectors/', (t) => {
  const root = fixture(t);
  writeFileSync(join(root, 'connectors', 'delivery.json'), JSON.stringify({
    schema: 1,
    budget: { bundledImageMegabytes: 3, exempt: {} },
    connectors: {},
    moduleOnly: {
      $comment: 'Connectors that exist only as modules.',
      alpha: { types: ['alpha'], why: 'It relies on the module host.' },
      beta: { types: ['beta'], why: 'It relies on the module host.' },
    },
  }));
  assert.deepEqual(checkModules(root), ['beta is module only but modules/beta/module.json is missing']);
});

test('a package is new, released, or changed without a new version', (t) => {
  const root = fixture(t);
  assert.deepEqual(status(fixture(t, { built: false })).map((s) => s.state), ['unbuilt']);
  const [first] = status(root);
  assert.equal(first.state, 'new');
  const lock = locked(root);
  assert.deepEqual(lock.modules.alpha['1.0.0'], { sha256: first.sha256, size: first.size });
  assert.equal(status(root, lock)[0].state, 'released');

  // The same version with other bytes: nothing may be released until it has a new version.
  const changed = fixture(t, { content: 'other classes' });
  assert.equal(status(changed, lock)[0].state, 'changed');
  assert.throws(() => locked(changed, lock), /released with other bytes/);
  // A new version is new again, and the lock keeps both.
  const next = fixture(t, { version: '1.0.1', content: 'other classes' });
  assert.equal(status(next, lock)[0].state, 'new');
  assert.deepEqual(Object.keys(locked(next, lock).modules.alpha), ['1.0.0', '1.0.1']);
});

test('a list keeps what it released and adds the built packages it lacks', (t) => {
  const root = fixture(t);
  const first = sourceIndex(root, { base: BASE });
  assert.equal(first.entries.length, 1);
  const [entry] = first.entries;
  assert.equal(entry.artifact.url, `${BASE}module-alpha-1.0.0/alpha-1.0.0.jar`);
  assert.deepEqual(entry.types, [{ id: 'alpha', name: 'Alpha', icon: 'database', group: 'Databases',
    description: 'Reads alpha.', tools: ['alpha_read', 'alpha_write'] }]);
  assert.deepEqual(entry.requires, { gateway: '>=0.9.2 & <1.0.0', connectorApi: '>=1.2.0 & <2.0.0' });

  assert.deepEqual(sourceIndex(root, { base: BASE, previous: first }), first);
  const next = sourceIndex(fixture(t, { version: '1.1.0', content: 'newer' }), { base: BASE,
    previous: { ...first, revoked: [{ sha256: 'a'.repeat(64), reason: 'leaked' }] } });
  assert.deepEqual(next.entries.map((e) => e.version), ['1.0.0', '1.1.0']);
  assert.deepEqual(next.entries[0], entry);
  assert.equal(next.revoked.length, 1);
  assert.throws(() => sourceIndex(fixture(t, { content: 'rebuilt differently' }), { base: BASE, previous: first }),
    /listed with other bytes/);
});

test('a version is bumped in module.json and in the pom together', (t) => {
  assert.equal(bumped('1.2.3'), '1.2.4');
  assert.equal(bumped('1.2.3', 'minor'), '1.3.0');
  assert.equal(bumped('1.2.3', 'major'), '2.0.0');
  assert.throws(() => bumped('1.2'), /major\.minor\.patch/);
  const root = fixture(t, { built: false });
  assert.equal(bump(root, 'alpha', 'minor'), '1.1.0');
  assert.equal(JSON.parse(readFileSync(join(root, 'modules', 'alpha', 'module.json'), 'utf8')).version, '1.1.0');
  assert.match(readFileSync(join(root, 'modules', 'alpha', 'pom.xml'), 'utf8'), /<module\.version>1\.1\.0<\/module\.version>/);
  assert.deepEqual(checkModules(root), []);
  assert.throws(() => bump(root, 'beta'), /no module beta/);
});

test('an entry is written from the module\'s own manifest', () => {
  const entry = listEntry(manifest('2.0.0'), { url: releaseUrl(BASE, 'alpha', '2.0.0'), sha256: 'b'.repeat(64), size: 5 });
  assert.equal(entry.kind, 'module');
  assert.equal(entry.artifact.url, `${BASE}module-alpha-2.0.0/alpha-2.0.0.jar`);
  assert.throws(() => listEntry({ ...manifest('2.0.0'), manifestVersion: 1 }, {}), /not a connector module/);
});
