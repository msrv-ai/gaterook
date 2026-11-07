import assert from 'node:assert/strict';
import { createServer, type ServerResponse } from 'node:http';
import { mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test, type TestContext } from 'node:test';
import type { ReadyCore } from '../src/main/core-supervisor.ts';
import { EndpointShares, endpointShareInArgv, endpointSharePath } from '../src/main/endpoint-shares.ts';
import { decryptEndpointShare, encryptEndpointShare } from '../src/main/endpoint-share-archive.ts';

const password = 'fictional endpoint sharing key';
const importSecret = 'fictional imported password';
const danger = { confirm: 'danger', status: 409, title: 'Review production access warnings',
  detail: 'The imported database account can write production data.',
  errors: [{ field: '/bundle/connections/db', message: 'Review the account permissions before importing.' }] };

async function importFixture(t: TestContext, reply: (body: Record<string, unknown>, res: ServerResponse) => void | Promise<void>) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'gaterook-share-import-')));
  const archive = join(root, 'endpoint.grshare');
  const bundle = { format: 'gaterook/endpoint-share/1', secret: importSecret, endpoint: { slug: 'source' } };
  const payload = Buffer.from(JSON.stringify(bundle));
  const encrypted = await encryptEndpointShare(payload, password);
  try { writeFileSync(archive, encrypted); } finally { payload.fill(0); encrypted.fill(0); }
  const imports: Record<string, unknown>[] = [];
  const server = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString()) as Record<string, unknown>;
    res.setHeader('X-Gateway-Instance', 'import-test');
    res.setHeader('Content-Type', 'application/json');
    if (req.url?.endsWith('/preview')) res.end(JSON.stringify({ connections: [{ id: 'db', secretCount: 1 }] }));
    else { imports.push(body); await reply(body, res); }
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  let core: ReadyCore = { port: (server.address() as { port: number }).port, publicPort: 7466,
    launchSecret: Buffer.alloc(32, 1).toString('base64url'), instanceNonce: 'import-test', pid: null,
    locked: null, version: '1', runtime: 'jvm', firstRun: false };
  const shares = new EndpointShares({ home: root, core: () => core,
    saveDialog: async () => ({ canceled: true, filePath: '' }), openDialog: async () => ({ canceled: true, filePaths: [] }) });
  t.after(async () => {
    shares.discard();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(root, { recursive: true, force: true });
  });
  return { shares, imports, bundle, archive,
    restart: () => { core = { ...core, instanceNonce: 'restarted-core' }; shares.coreChanged(); },
    review: async () => {
      shares.openFile(archive);
      const { handle } = await shares.preview(password);
      return { handle, connectionIds: ['db'], includeEndpoint: true, includeSecrets: true, endpointSlug: 'imported' };
    } };
}

test('production preflight warnings retain the review for explicit confirmation without exposing raw error extensions', async (t) => {
  const imported = { endpointSlug: 'imported', connections: [{ id: 'db', name: 'db', needsSetup: false }], warnings: [] };
  const fixture = await importFixture(t, (body, res) => {
    if (body['confirm'] === 'danger') res.end(JSON.stringify(imported));
    else {
      res.statusCode = 409;
      res.end(JSON.stringify({ ...danger, bundle: { secret: importSecret }, stack: importSecret,
        errors: [{ ...danger.errors[0], rejectedValue: importSecret }] }));
    }
  });
  const selection = await fixture.review();
  for (const confirm of [true, null, 'yes', '', {}, ['danger']]) {
    await assert.rejects(fixture.shares.import({ ...selection, confirm }), /Choose what to import/);
  }
  assert.equal(fixture.imports.length, 0, 'invalid confirmation does not send a request or consume the review');
  const warning = await fixture.shares.import(selection);
  assert.deepEqual(warning, { confirmationRequired: danger });
  assert.equal(JSON.stringify(warning).includes(importSecret), false);
  assert.deepEqual(fixture.shares.selectedFile(), { fileName: 'endpoint.grshare' });
  assert.deepEqual(await fixture.shares.import({ ...selection, confirm: 'danger', ignored: importSecret }), imported);
  assert.deepEqual(fixture.imports, [
    { bundle: fixture.bundle, connectionIds: ['db'], includeEndpoint: true, includeSecrets: true, endpointSlug: 'imported' },
    { bundle: fixture.bundle, connectionIds: ['db'], includeEndpoint: true, includeSecrets: true, endpointSlug: 'imported', confirm: 'danger' },
  ]);
  assert.equal(fixture.shares.selectedFile(), null);
  await assert.rejects(fixture.shares.import({ ...selection, confirm: 'danger' }), /expired/);
  assert.equal(fixture.imports.length, 2, 'a completed confirmed import cannot be repeated');
});

test('other failures consume the review and omit potentially sensitive response data from IPC errors', async (t) => {
  let response: { status: number; body?: unknown; nonce?: string; disconnect?: boolean };
  const fixture = await importFixture(t, (_body, res) => {
    if (response.disconnect) { res.destroy(); return; }
    res.statusCode = response.status;
    if (response.nonce) res.setHeader('X-Gateway-Instance', response.nonce);
    res.end(JSON.stringify(response.body));
  });
  for (response of [
    { status: 409, body: { title: importSecret, detail: importSecret } },
    { status: 409, body: { ...danger, confirm: 'different' } },
    { status: 409, body: { ...danger, errors: [{ field: '/bundle', rejectedValue: importSecret }] } },
    { status: 422, body: { ...danger, detail: importSecret } },
    { status: 500, body: { detail: importSecret, bundle: fixture.bundle } },
    { status: 409, body: danger, nonce: 'another-process' },
    { status: 0, disconnect: true },
  ]) {
    const selection = await fixture.review();
    await assert.rejects(fixture.shares.import(selection), (error: unknown) => {
      assert.ok(error instanceof Error);
      assert.match(error.message, /could not be imported.*review the file again/);
      assert.equal(error.message.includes(importSecret), false);
      assert.equal(JSON.stringify(error).includes(importSecret), false);
      assert.equal('body' in error, false);
      return true;
    });
    const count = fixture.imports.length;
    await assert.rejects(fixture.shares.import({ ...selection, confirm: 'danger' }), /expired/);
    assert.equal(fixture.imports.length, count, 'an ambiguous result must not be retried with the same handle');
  }
});

test('requesting danger confirmation does not extend the original decrypted review lifetime', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const fixture = await importFixture(t, (_body, res) => { res.statusCode = 409; res.end(JSON.stringify(danger)); });
  const selection = await fixture.review();
  t.mock.timers.tick(15 * 60 * 1000 - 1_000);
  assert.deepEqual(await fixture.shares.import(selection), { confirmationRequired: danger });
  t.mock.timers.tick(1_001);
  await assert.rejects(fixture.shares.import({ ...selection, confirm: 'danger' }), /expired/);
  assert.equal(fixture.imports.length, 1);
});

test('an in-flight preflight cannot restore an invalidated review or import twice', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const invalidate of ['file', 'core', 'expired'] as const) {
    let answer!: () => void;
    let received!: () => void;
    const requestStarted = new Promise<void>((resolve) => { received = resolve; });
    const fixture = await importFixture(t, async (_body, res) => {
      await new Promise<void>((resolve) => { answer = resolve; received(); });
      res.statusCode = 409; res.end(JSON.stringify(danger));
    });
    const selection = await fixture.review();
    const pending = fixture.shares.import(selection);
    await requestStarted;
    await assert.rejects(fixture.shares.import(selection), /already running/);
    if (invalidate === 'file') fixture.shares.openFile(join(fixture.archive, '..', 'another.grshare'));
    else if (invalidate === 'core') fixture.restart();
    else t.mock.timers.tick(15 * 60 * 1000 + 1);
    answer();
    await assert.rejects(pending, /could not be imported/);
    await assert.rejects(fixture.shares.import({ ...selection, confirm: 'danger' }), /expired/);
    assert.equal(fixture.imports.length, 1);
    if (invalidate === 'file') assert.deepEqual(fixture.shares.selectedFile(), { fileName: 'another.grshare' });
  }
});

test('OS file activation accepts absolute .grshare paths while raw URL and command arguments remain invalid paths', () => {
  assert.equal(endpointShareInArgv(['app', '--inspect', '/tmp/shared.GRSHARE'], 'linux'), '/tmp/shared.GRSHARE');
  assert.equal(endpointShareInArgv(['app', 'C:\\Users\\Example\\shared.grshare'], 'win32'), 'C:\\Users\\Example\\shared.grshare');
  for (const value of [null, 'shared.grshare', '--file=shared.grshare', 'gaterook://shared.grshare', 'file:///tmp/shared.grshare', '/tmp/shared.grbackup', '/tmp/a\n.grshare']) {
    assert.equal(endpointSharePath(value, 'linux'), null);
  }
});

test('argv activation decodes local file URLs from Linux desktop entries without accepting remote or ambiguous URLs', () => {
  assert.equal(endpointShareInArgv(['app', 'file:///tmp/team%20share.grshare'], 'linux'), '/tmp/team share.grshare');
  assert.equal(endpointShareInArgv(['app', 'file://localhost/tmp/shared.grshare'], 'linux'), '/tmp/shared.grshare');
  assert.equal(endpointShareInArgv(['app', 'file:///C:/Users/Example/team%20share.grshare'], 'win32'), 'C:\\Users\\Example\\team share.grshare');
  for (const value of [
    'file://remote.example/shared.grshare', 'file://user:password@localhost/tmp/shared.grshare',
    'file://localhost:80/tmp/shared.grshare', 'file:////remote.example/shared.grshare',
    'file:///tmp/shared.grshare?download=1', 'file:///tmp/shared.grshare?', 'file:///tmp/shared.grshare#',
    'file:///tmp/shared.grshare#section', 'file:///tmp/a%2Fb.grshare', 'file:///tmp/a%00.grshare',
    'file:///tmp/a%0A.grshare', 'file:///tmp/a%ZZ.grshare', 'https://example.test/shared.grshare',
  ]) assert.equal(endpointShareInArgv(['app', value], 'linux'), null, value);
});

test('native selection, decryption, sanitized review and selected import keep plaintext out of renderer results', async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'gaterook-share-')));
  const home = join(root, 'home'); mkdirSync(home);
  let archive = join(root, 'endpoint.grshare');
  let selected = archive;
  const calls: { path: string; body: any }[] = [];
  const bundle = { format: 'gaterook/endpoint-share/1', secret: 'fictional password', endpoint: { slug: 'source' } };
  const preview = { endpoint: { slug: 'source' }, suggestedSlug: 'source-2', connections: [{ id: 'db', secretCount: 1 }] };
  const imported = { endpointSlug: 'new-share', connections: [{ id: 'db', name: 'db-2', needsSetup: true }], warnings: [] };
  const server = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    calls.push({ path: req.url!, body });
    res.setHeader('X-Gateway-Instance', 'share-test');
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(req.url!.endsWith('/export') ? bundle : req.url!.endsWith('/preview') ? preview : imported));
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const endpoint: ReadyCore = { port: (server.address() as { port: number }).port, publicPort: 7466,
    launchSecret: Buffer.alloc(32, 1).toString('base64url'), instanceNonce: 'share-test', pid: null,
    locked: null, version: '1', runtime: 'jvm', firstRun: false };
  let core: ReadyCore | null = endpoint;
  let cancel = false;
  const shares = new EndpointShares({ home, core: () => core,
    saveDialog: async () => ({ canceled: cancel, filePath: archive }),
    openDialog: async () => ({ canceled: cancel, filePaths: [selected] }) });
  const options = { endpointSlug: 'source', connectionNames: ['db'], includeSecrets: true };
  try {
    await assert.rejects(shares.create(options, 'short'), /passphrase/);
    assert.equal(calls.length, 0);
    cancel = true;
    assert.deepEqual(await shares.create(options, password), { cancelled: true });
    assert.equal(calls.length, 0);
    cancel = false;
    assert.deepEqual(await shares.create(options, password), { cancelled: false, fileName: 'endpoint.grshare' });
    const file = readFileSync(archive);
    assert.equal(file.includes(Buffer.from(bundle.secret)), false);
    const plain = await decryptEndpointShare(file, password);
    assert.deepEqual(JSON.parse(plain.toString()), bundle); plain.fill(0);
    assert.deepEqual(calls[0], { path: '/internal/endpoint-shares/export', body: options });
    assert.deepEqual(await shares.choose(), { fileName: 'endpoint.grshare' });
    assert.equal(calls.length, 1, 'choosing a file does not decrypt or import');
    await assert.rejects(shares.preview('a different fictional key'), /incorrect.*damaged/);
    assert.equal(calls.length, 1);
    const review = await shares.preview(password);
    assert.deepEqual(review.preview, preview);
    assert.equal(JSON.stringify(review).includes(bundle.secret), false);
    assert.deepEqual(calls[1], { path: '/internal/endpoint-shares/preview', body: { bundle } });
    const choices = { handle: review.handle, connectionIds: ['db'], includeEndpoint: true, includeSecrets: false, endpointSlug: 'new-share' };
    assert.deepEqual(await shares.import(choices), imported);
    assert.deepEqual(calls[2], { path: '/internal/endpoint-shares/import', body: { bundle, connectionIds: ['db'], includeEndpoint: true, includeSecrets: false, endpointSlug: 'new-share' } });
    assert.equal(shares.selectedFile(), null);
    await assert.rejects(shares.import(choices), /expired/);
    assert.equal(calls.length, 3, 'consumed handles cannot duplicate an import');

    shares.openFile(archive);
    const old = await shares.preview(password);
    shares.openFile(join(root, 'another.grshare'));
    shares.discard(old.handle);
    assert.deepEqual(shares.selectedFile(), { fileName: 'another.grshare' }, 'a stale page cannot discard a newer file');
    await assert.rejects(shares.import({ ...choices, handle: old.handle }), /expired/);
    assert.equal(calls.at(-1)?.path, '/internal/endpoint-shares/preview');

    shares.openFile(archive);
    const restarted = await shares.preview(password);
    core = { ...endpoint, instanceNonce: 'new-core' };
    shares.coreChanged();
    await assert.rejects(shares.import({ ...choices, handle: restarted.handle }), /expired/);
    core = endpoint;
    shares.openFile(archive);
    const inFlight = shares.preview(password);
    await assert.rejects(shares.preview(password), /already running/);
    shares.openFile(join(root, 'another.grshare'));
    await assert.rejects(inFlight, /changed/);
    shares.discard();
    assert.equal(shares.selectedFile(), null);

    archive = join(home, 'inside.grshare');
    await assert.rejects(shares.create(options, password), /outside the GateRook/);
    if (process.platform !== 'win32') {
      selected = join(root, 'link.grshare'); symlinkSync(join(root, 'endpoint.grshare'), selected);
      await shares.choose();
      await assert.rejects(shares.preview(password), /regular backup file/);
    }
  } finally {
    shares.discard();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(root, { recursive: true, force: true });
  }
});
