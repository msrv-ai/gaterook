import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { ReadyCore } from '../src/main/core-supervisor.ts';
import { EndpointShares, endpointShareInArgv, endpointSharePath } from '../src/main/endpoint-shares.ts';
import { decryptEndpointShare } from '../src/main/endpoint-share-archive.ts';

const password = 'fictional endpoint sharing key';
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
