import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { ReadyCore } from '../src/main/core-supervisor.ts';
import { createFullBackup, restoreFullBackup, type FullBackupHost } from '../src/main/full-backup.ts';
import { decryptFullBackup } from '../src/main/full-backup-archive.ts';
import { unpackBackup } from '../src/main/full-backup-files.ts';

const PASSWORD = 'A long unique recovery phrase';
test('full backup crosses native selection, encrypted file, validation, confirmation, offline replacement', async () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'gaterook-full-flow-')));
  const home = join(root, 'home');
  mkdirSync(home);
  const oldDb = 'original live database';
  const newDb = 'consistent database snapshot';
  const portable = Buffer.from('portable secret key').toString('base64');
  const protectedKey = Buffer.from('OS protected replacement key');
  writeFileSync(join(home, 'gateway.db'), oldDb);
  writeFileSync(join(home, 'master.keyset'), 'original key');
  writeFileSync(join(home, 'desktop.json'), JSON.stringify({ port: 7465, publicPort: 7466, openAtLogin: true }));
  mkdirSync(join(home, 'plugin-data', 'sample'), { recursive: true });
  writeFileSync(join(home, 'plugin-data', 'sample', 'state.json'), '{"enabled":false}');
  let validateCalls = 0;
  const server = createServer(async (req, res) => {
    const buffers: Buffer[] = [];
    for await (const chunk of req) buffers.push(chunk);
    const request = JSON.parse(Buffer.concat(buffers).toString());
    res.setHeader('X-Gateway-Instance', 'test-instance');
    res.setHeader('Content-Type', 'application/json');
    if (req.url === '/internal/full-backup/snapshot') {
      writeFileSync(join(request.directory, 'gateway.db'), newDb);
      res.end(JSON.stringify({ keyset: portable, pluginFiles: [] }));
    } else {
      validateCalls++;
      assert.equal(req.url, '/internal/full-backup/validate');
      assert.equal(request.keyset, portable);
      assert.equal(readFileSync(join(request.directory, 'gateway.db'), 'utf8'), newDb);
      res.end(JSON.stringify({ keyset: protectedKey.toString('base64') }));
    }
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const endpoint: ReadyCore = { port: (server.address() as { port: number }).port, publicPort: 7466,
    launchSecret: Buffer.alloc(32, 1).toString('base64url'), instanceNonce: 'test-instance', pid: null,
    locked: null, version: '1', runtime: 'jvm', firstRun: false };
  const archive = join(root, 'backup.grbackup');
  const events: string[] = [];
  let confirm = false;
  let selected = archive;
  const host: FullBackupHost = {
    home, version: '1', core: () => endpoint,
    saveDialog: async () => ({ canceled: false, filePath: archive }),
    openDialog: async () => ({ canceled: false, filePaths: [selected] }),
    confirmRestore: async (options) => {
      events.push('confirm');
      assert.equal(options.defaultId, 0);
      assert.equal(readFileSync(join(home, 'gateway.db'), 'utf8'), oldDb);
      return { response: confirm ? 1 : 0, checkboxChecked: false };
    },
    pause: async () => { events.push('pause'); }, resume: async () => { events.push('resume'); },
    stop: async () => { events.push('stop'); assert.equal(readFileSync(join(home, 'gateway.db'), 'utf8'), oldDb); },
    restored: async () => {
      events.push('restored');
      assert.equal(readFileSync(join(home, 'gateway.db'), 'utf8'), newDb);
      assert.deepEqual(readFileSync(join(home, 'master.keyset')), protectedKey);
    },
  };
  try {
    await assert.rejects(createFullBackup(host, 'short'), /passphrase/);
    await assert.rejects(restoreFullBackup(host, PASSWORD, 'restore'), /Type RESTORE/);
    assert.deepEqual(events, []);
    assert.deepEqual(await createFullBackup(host, PASSWORD), { cancelled: false, fileName: 'backup.grbackup' });
    assert.deepEqual(events, ['pause', 'resume']);
    const bytes = readFileSync(archive);
    assert.equal(bytes.includes(Buffer.from('portable secret key')), false);
    assert.equal(bytes.includes(Buffer.from(newDb)), false);
    const plain = await decryptFullBackup(bytes, PASSWORD);
    const inspected = join(root, 'inspected');
    const meta = unpackBackup(plain, inspected);
    plain.fill(0);
    assert.equal(meta.keyset, portable);
    assert.equal(readFileSync(join(inspected, 'plugin-data', 'sample', 'state.json'), 'utf8'), '{"enabled":false}');
    events.length = 0;
    await assert.rejects(restoreFullBackup(host, 'An incorrect recovery phrase', 'RESTORE'), /incorrect|damaged/);
    assert.equal(validateCalls, 0);
    assert.deepEqual(events, []);
    assert.deepEqual(await restoreFullBackup(host, PASSWORD, 'RESTORE'), { cancelled: true });
    assert.deepEqual(events, ['confirm']);
    assert.equal(readFileSync(join(home, 'master.keyset'), 'utf8'), 'original key');
    events.length = 0;
    confirm = true;
    assert.deepEqual(await restoreFullBackup(host, PASSWORD, 'RESTORE'), { cancelled: false });
    assert.deepEqual(events, ['confirm', 'stop', 'restored']);
    assert.equal(existsSync(join(home, '.full-restore')), false);
    assert.equal(JSON.parse(readFileSync(join(home, 'desktop.json'), 'utf8')).openAtLogin, false);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(root, { recursive: true, force: true });
  }
});
