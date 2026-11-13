// node --test 'catalog/*.test.mjs'  (step K6): the signing script of the catalog index.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash, createPublicKey, verify } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, statSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  KEY_ENV,
  Refused,
  artifactName,
  generateKey,
  main,
  prepareIndex,
  privateKeyFrom,
  publicKeyRaw,
  serialize,
  signBytes,
  trustLine,
  verifyBytes,
} from './sign-index.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const SCRIPT = join(here, 'sign-index.mjs');
const SOURCE = JSON.parse(readFileSync(join(here, 'index.json'), 'utf8'));
const NPM_FIXTURE = {
  kind: 'mcp-server', id: 'fixture-mcp', version: '1.0.0', name: 'Fixture MCP server',
  npm: { package: '@example/catalog-test-server', version: '1.0.0', integrity: 'sha512-AAAA',
    lockfile: { url: 'fixture-mcp.package-lock.json' }, bin: 'fixture-mcp', ignoreScripts: true },
};
// This package exists only in memory: signing tests never download or install it.
const LOCKFILE = Buffer.from(JSON.stringify({ lockfileVersion: 3, packages: {
  'node_modules/@example/catalog-test-server': { version: '1.0.0', integrity: 'sha512-AAAA' },
} }));
const withMcpFixture = () => structuredClone({ ...SOURCE, entries: [...SOURCE.entries, NPM_FIXTURE] });
const NOW = new Date('2026-10-26T12:00:00.123Z');

/** The built files of a release, as fakes: every artifact the source index names. */
function artifacts() {
  return new Map([
    ['gw-jira-service-desk-1.3.0.jar', Buffer.from('jira 1.3.0 jar')],
    ['gw-rabbitmq-0.3.0.jar', Buffer.from('rabbitmq 0.3.0 jar')],
    ['fixture-mcp.package-lock.json', LOCKFILE],
  ]);
}

const sha256 = (b) => createHash('sha256').update(b).digest('hex');

function tempDir() {
  return mkdtempSync(join(tmpdir(), 'sign-index-'));
}

test('the source index of this folder signs: every artifact pinned, sequence, times, revocations', () => {
  const index = prepareIndex(SOURCE, { artifacts: artifacts(), sequence: 7, now: NOW, days: 30 });
  assert.equal(index.schema, 1);
  assert.equal(index.sequence, 7);
  assert.equal(index.generatedAt, '2026-10-26T12:00:00Z');
  assert.equal(index.expiresAt, '2026-11-25T12:00:00Z');
  assert.deepEqual(index.entries.map((e) => `${e.kind}:${e.id}@${e.version}`), [
    'plugin:jira-service-desk@1.3.0', 'plugin:rabbitmq@0.3.0', 'template:pg-slow-queries@1.0.0',
  ]);
  const jira = index.entries.find((e) => e.id === 'jira-service-desk');
  assert.equal(jira.artifact.sha256, sha256(Buffer.from('jira 1.3.0 jar')));
  assert.equal(jira.artifact.size, 'jira 1.3.0 jar'.length);
  assert.equal(index.entries.find((e) => e.id === 'rabbitmq').signed, false);
  assert.deepEqual(index.revoked, []);
  // The kit's cards: names, tool counts, signed or not.
  assert.deepEqual(index.entries.map((e) => [e.name, e.tools, e.signed]), [
    ['Jira Service Desk', 6, true], ['RabbitMQ', 4, false], ['PostgreSQL slow queries', 2, true],
  ]);
});

test('a test-only MCP server pins its npm lockfile without adding a product catalog entry', () => {
  const index = prepareIndex(withMcpFixture(), { artifacts: artifacts(), sequence: 1, now: NOW });
  const server = index.entries.find((e) => e.id === 'fixture-mcp');
  assert.equal(server.npm.lockfile.sha256, sha256(LOCKFILE));
  assert.equal(server.npm.lockfile.size, LOCKFILE.length);
  assert.ok(!SOURCE.entries.some((e) => e.id === 'fixture-mcp'));
});

test('a signature holds over the exact bytes and breaks on a flipped byte', () => {
  const key = generateKey();
  const bytes = serialize(prepareIndex(SOURCE, { artifacts: artifacts(), sequence: 1, now: NOW }));
  const sig = signBytes(bytes, key, 'catalog-test');
  assert.equal(sig.alg, 'Ed25519');
  assert.equal(Buffer.from(sig.sig, 'base64').length, 64);
  assert.ok(verifyBytes(bytes, sig, publicKeyRaw(key)));
  // The same check node's own crypto makes, so the bytes and the key are what the core reads.
  assert.ok(verify(null, bytes, createPublicKey(key), Buffer.from(sig.sig, 'base64')));
  const flipped = Buffer.from(bytes);
  flipped[flipped.length >> 1] ^= 0x01;
  assert.equal(verifyBytes(flipped, sig, publicKeyRaw(key)), false);
  assert.equal(verifyBytes(bytes, sig, publicKeyRaw(generateKey())), false);
  assert.equal(verifyBytes(bytes, { ...sig, alg: 'RS256' }, publicKeyRaw(key)), false);
});

test('a key from PEM and the same key as a raw seed sign alike (Ed25519 is deterministic)', () => {
  const key = generateKey();
  const pem = key.export({ format: 'pem', type: 'pkcs8' });
  const der = key.export({ format: 'der', type: 'pkcs8' });
  const seed = der.subarray(der.length - 32);
  const bytes = Buffer.from('{"schema":1}\n');
  const a = signBytes(bytes, privateKeyFrom(pem), 'k');
  const b = signBytes(bytes, privateKeyFrom(seed.toString('base64')), 'k');
  const c = signBytes(bytes, privateKeyFrom(seed.toString('base64url')), 'k');
  assert.equal(a.sig, b.sig);
  assert.equal(b.sig, c.sig);
  assert.match(trustLine('catalog-2026-1', key), /^catalog-2026-1=[A-Za-z0-9_-]{43}$/);
  assert.throws(() => privateKeyFrom(''), Refused);
  assert.throws(() => privateKeyFrom('c2hvcnQ='), /32 bytes/);
});

test('the sequence must grow and the index must expire within 90 days', () => {
  const a = artifacts();
  assert.throws(() => prepareIndex(SOURCE, { artifacts: a, now: NOW }), /--sequence or --previous/);
  assert.equal(prepareIndex(SOURCE, { artifacts: a, previous: { sequence: 411 }, now: NOW }).sequence, 412);
  assert.throws(() => prepareIndex(SOURCE, { artifacts: a, sequence: 411, previous: { sequence: 411 }, now: NOW }),
    /must grow/);
  assert.throws(() => prepareIndex(SOURCE, { artifacts: a, sequence: 1, now: NOW, days: 91 }), /1 to 90/);
  assert.throws(() => prepareIndex(SOURCE, { artifacts: a, sequence: 0, now: NOW }), /positive/);
});

test('every artifact is pinned: a missing one, an http URL or a lockfile that pins something else is refused', () => {
  const missing = artifacts();
  missing.delete('gw-rabbitmq-0.3.0.jar');
  assert.throws(() => prepareIndex(SOURCE, { artifacts: missing, sequence: 1, now: NOW }),
    /gw-rabbitmq-0\.3\.0\.jar isn't among the built files/);

  const insecure = structuredClone(SOURCE);
  insecure.entries[1].artifact.url = 'http://example.com/gw-rabbitmq-0.3.0.jar';
  assert.throws(() => prepareIndex(insecure, { artifacts: artifacts(), sequence: 1, now: NOW }), /https:\/\/ only/);
  assert.equal(artifactName('https://github.com/acme/catalog/releases/latest/download/x.jar?raw=1'), 'x.jar');

  const otherLock = artifacts();
  const lock = JSON.parse(LOCKFILE.toString('utf8'));
  lock.packages['node_modules/@example/catalog-test-server'].integrity = 'sha512-BBBB';
  otherLock.set('fixture-mcp.package-lock.json', Buffer.from(JSON.stringify(lock)));
  assert.throws(() => prepareIndex(withMcpFixture(), { artifacts: otherLock, sequence: 1, now: NOW }),
    /doesn't pin @example\/catalog-test-server@1\.0\.0/);

  const scripts = structuredClone(withMcpFixture());
  scripts.entries.find((e) => e.kind === 'mcp-server').npm.ignoreScripts = false;
  assert.throws(() => prepareIndex(scripts, { artifacts: artifacts(), sequence: 1, now: NOW }), /--ignore-scripts/);
});

test('revocations are kept across releases, checked, and a revoked artifact can\'t stay listed', () => {
  const dead = 'ab'.repeat(32);
  const index = prepareIndex(SOURCE, {
    artifacts: artifacts(), previous: { sequence: 3, revoked: [{ sha256: dead, reason: 'CVE-2026-0001' }] }, now: NOW,
    revoke: [{ sha256: 'CD'.repeat(32), reason: 'leaked build' }, { sha256: dead, reason: 'again' }],
  });
  assert.deepEqual(index.revoked, [
    { sha256: dead, reason: 'CVE-2026-0001' }, { sha256: 'cd'.repeat(32), reason: 'leaked build' },
  ]);
  assert.throws(() => prepareIndex(SOURCE, { artifacts: artifacts(), sequence: 1, now: NOW,
    revoke: [{ sha256: 'xyz', reason: 'r' }] }), /64 hex digits/);
  const listed = sha256(Buffer.from('jira 1.3.0 jar'));
  assert.throws(() => prepareIndex(SOURCE, { artifacts: artifacts(), sequence: 1, now: NOW,
    revoke: [{ sha256: listed, reason: 'bad build' }] }), /jira-service-desk@1\.3\.0 is revoked/);
});

test('the CLI signs with the key of the environment, verifies, and never writes the key', () => {
  const dir = tempDir();
  const built = join(dir, 'built');
  mkdirSync(built);
  for (const [name, bytes] of artifacts()) writeFileSync(join(built, name), bytes);
  const key = generateKey();
  const der = key.export({ format: 'der', type: 'pkcs8' });
  const seed = der.subarray(der.length - 32).toString('base64');
  const out = join(dir, 'dist');

  const signed = spawnSync(process.execPath, [SCRIPT, 'sign', '--index', join(here, 'index.json'), '--artifacts',
    built, '--out', out, '--key-id', 'catalog-test', '--sequence', '5', '--now', NOW.toISOString()],
  { env: { PATH: process.env.PATH, [KEY_ENV]: seed }, encoding: 'utf8' });
  assert.equal(signed.status, 0, signed.stderr);
  assert.match(signed.stdout, /Signed sequence 5, 3 entries/);
  assert.ok(signed.stdout.includes(trustLine('catalog-test', key)));
  const indexBytes = readFileSync(join(out, 'index.json'));
  const sigText = readFileSync(join(out, 'index.json.sig'), 'utf8');
  for (const text of [indexBytes.toString('utf8'), sigText, signed.stdout, signed.stderr]) {
    assert.ok(!text.includes(seed), 'the private key must not leave the process');
  }

  const pub = publicKeyRaw(key).toString('base64url');
  const ok = spawnSync(process.execPath, [SCRIPT, 'verify', '--index', join(out, 'index.json'), '--sig',
    join(out, 'index.json.sig'), '--public-key', pub], { encoding: 'utf8' });
  assert.equal(ok.status, 0, ok.stderr);

  const tampered = Buffer.from(indexBytes);
  tampered[20] ^= 0x01;
  writeFileSync(join(out, 'index.json'), tampered);
  const bad = spawnSync(process.execPath, [SCRIPT, 'verify', '--index', join(out, 'index.json'), '--sig',
    join(out, 'index.json.sig'), '--public-key', pub], { encoding: 'utf8' });
  assert.equal(bad.status, 1);
  assert.match(bad.stdout, /does NOT hold/);
});

test('the CLI refuses without a key, with an unknown option, and never overwrites a key file', () => {
  const quiet = { write() {} };
  assert.equal(main(['sign', '--index', join(here, 'index.json'), '--artifacts', here, '--out', tempDir(),
    '--key-id', 'k', '--sequence', '1'], { env: {}, stdout: quiet, stderr: quiet }), 1);
  assert.equal(main(['sign', '--bogus'], { env: {}, stdout: quiet, stderr: quiet }), 2);
  assert.equal(main(['frobnicate'], { env: {}, stdout: quiet, stderr: quiet }), 2);

  const dir = tempDir();
  const file = join(dir, 'catalog.pem');
  let printed = '';
  assert.equal(main(['keygen', '--key-file', file, '--key-id', 'catalog-test'],
    { stdout: { write: (s) => { printed += s; } }, stderr: quiet }), 0);
  assert.ok(existsSync(file));
  assert.equal(statSync(file).mode & 0o777, 0o600);
  assert.match(printed, /gateway\.plugins\.catalog-keys=catalog-test=/);
  assert.ok(!printed.includes('PRIVATE KEY'));
  assert.equal(main(['keygen', '--key-file', file, '--key-id', 'catalog-test'], { stdout: quiet, stderr: quiet }), 1);
});

const MODULE = {
  kind: 'module', id: 'cassandra', version: '1.0.0', name: 'Apache Cassandra',
  requires: { gateway: '>=0.9.2 & <1.0.0', connectorApi: '>=1.2.0 & <2.0.0' },
  artifact: { url: 'https://github.com/gaterook/gaterook-plugins/releases/download/module-cassandra-1.0.0/cassandra-1.0.0.jar' },
  types: [{ id: 'cassandra', name: 'Apache Cassandra', group: 'Databases', tools: ['cassandra_query'] }],
};
const moduleList = (...entries) => ({ schema: 1, entries });

test('a connector module is pinned to its built package, and a released version keeps the pin it has', () => {
  const jar = Buffer.from('cassandra 1.0.0');
  const built = new Map([['cassandra-1.0.0.jar', jar]]);
  const first = prepareIndex(moduleList(MODULE), { artifacts: built, sequence: 1, now: NOW });
  assert.equal(first.entries[0].artifact.sha256, createHash('sha256').update(jar).digest('hex'));
  assert.equal(first.entries[0].artifact.size, jar.length);
  assert.deepEqual(first.entries[0].types, MODULE.types);

  // The next list keeps 1.0.0 as it was released, without its package at hand, and adds 1.1.0.
  const next = { ...MODULE, version: '1.1.0', artifact: { url: MODULE.artifact.url.replaceAll('1.0.0', '1.1.0') } };
  const second = prepareIndex(moduleList(first.entries[0], next), {
    artifacts: new Map([['cassandra-1.1.0.jar', Buffer.from('cassandra 1.1.0')]]), previous: first, now: NOW,
  });
  assert.equal(second.sequence, 2);
  assert.deepEqual(second.entries[0].artifact, first.entries[0].artifact);
  assert.equal(second.entries[1].version, '1.1.0');

  // A released version is never listed with other bytes.
  assert.throws(() => prepareIndex(moduleList(first.entries[0]), {
    artifacts: new Map([['cassandra-1.0.0.jar', Buffer.from('rebuilt differently')]]), previous: first, now: NOW,
  }), /released with other bytes/);
  assert.throws(() => prepareIndex(moduleList(MODULE), { artifacts: new Map(), sequence: 1, now: NOW }),
    /neither built nor pinned/);
});

test('a module entry says what it runs with and which connector types and tools it adds', () => {
  const built = new Map([['cassandra-1.0.0.jar', Buffer.from('x')]]);
  const refused = (entry, why) => assert.throws(
    () => prepareIndex(moduleList(entry), { artifacts: built, sequence: 1, now: NOW }), why);
  refused({ ...MODULE, requires: { gateway: '>=0.9.2' } }, /which application and connector API versions/);
  refused({ ...MODULE, types: [] }, /lists the connector types it adds/);
  refused({ ...MODULE, types: [{ id: 'Cassandra', tools: [] }] }, /not a connector type id/);
  refused({ ...MODULE, types: [{ id: 'cassandra', tools: ['Bad-Name'] }] }, /lists its tool names/);
  refused({ ...MODULE, kind: 'package' }, /kind is plugin, template, mcp-server or module/);
});
