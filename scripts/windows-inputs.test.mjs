import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { JRE_MODULES } from './jlink.mjs';
import { copyRuntimeLibraries } from './prepare-windows.mjs';
import { validateWindowsInputs, writeWindowsInputsManifest } from './windows-inputs.mjs';

const identity = { revision: 'a'.repeat(40), version: '0.4.0' };
const nativeFiles = ['core/gaterook-core.exe', 'core/sqlitejdbc.dll', 'bridge/gw-bridge.exe',
  'native/gaterook-unlock.exe', 'jre/bin/java.exe', 'jre/bin/server/jvm.dll'];

function pe(machine = 0x8664) {
  const bytes = Buffer.alloc(256);
  bytes.write('MZ');
  bytes.writeUInt32LE(128, 60);
  bytes.write('PE\0\0', 128);
  bytes.writeUInt16LE(machine, 132);
  bytes.writeUInt16LE(0x20b, 152);
  return bytes;
}

function write(dir, name, data) {
  mkdirSync(dirname(join(dir, name)), { recursive: true });
  writeFileSync(join(dir, name), data);
}

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'gaterook-windows-inputs-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  for (const name of nativeFiles) write(dir, name, pe());
  for (const name of ['plugin-host/plugin-host.jar', 'bridge/gw-bridge.jar']) {
    write(dir, name, Buffer.from([0x50, 0x4b, 0x03, 0x04]));
  }
  write(dir, 'jre/lib/modules', 'jimage fixture');
  write(dir, 'jre/release', `JAVA_VERSION="25.0.1"\nJAVA_RUNTIME_VERSION="25.0.1+8"\nOS_NAME="Windows"\nOS_ARCH="amd64"\nMODULES="${JRE_MODULES.join(' ')}"\n`);
  writeWindowsInputsManifest(dir, identity);
  return dir;
}

function editManifest(dir, edit) {
  const path = join(dir, 'windows-inputs.json');
  const manifest = JSON.parse(readFileSync(path, 'utf8'));
  edit(manifest);
  writeFileSync(path, JSON.stringify(manifest));
}

function rehash(dir, name) {
  editManifest(dir, (manifest) => {
    manifest.files[name] = createHash('sha256').update(readFileSync(join(dir, name))).digest('hex');
  });
}

test('accepts a complete Windows x64 bundle for the selected revision and version', (t) => {
  const dir = fixture(t);
  const manifest = validateWindowsInputs(dir, identity);
  assert.equal(manifest.applicationRevision, identity.revision);
  assert.equal(manifest.platform, 'windows-x64');
  assert.equal(Object.keys(manifest.files).length, 10);
});

test('rejects changed payload bytes and mismatched application identity', (t) => {
  const dir = fixture(t);
  assert.throws(() => validateWindowsInputs(dir, { revision: 'b'.repeat(40) }), /different application revision/);
  assert.throws(() => validateWindowsInputs(dir, { version: '0.5.0' }), /version does not match/);
  write(dir, 'bridge/gw-bridge.jar', 'tampered');
  assert.throws(() => validateWindowsInputs(dir), /SHA-256 mismatch: bridge\/gw-bridge.jar/);
});

test('rejects missing mandatory resources even when removed from the manifest', (t) => {
  const dir = fixture(t);
  rmSync(join(dir, 'native/gaterook-unlock.exe'));
  editManifest(dir, (manifest) => { delete manifest.files['native/gaterook-unlock.exe']; });
  assert.throws(() => validateWindowsInputs(dir), /Missing required Windows input: native\/gaterook-unlock.exe/);
});

test('rejects extra files omitted from the manifest', (t) => {
  const dir = fixture(t);
  write(dir, 'core/stale.dll', pe());
  assert.throws(() => validateWindowsInputs(dir), /missing or extra payload/);
});

test('rejects path traversal, absolute paths, and Windows path aliases', async (t) => {
  for (const path of ['../outside', '/absolute', 'core/../outside', 'core\\outside', 'C:/outside']) {
    await t.test(path, (t) => {
      const dir = fixture(t);
      editManifest(dir, (manifest) => { manifest.files[path] = 'a'.repeat(64); });
      assert.throws(() => validateWindowsInputs(dir), /Invalid Windows payload path/);
    });
  }
});

test('rejects case-colliding payload paths for the Windows destination', (t) => {
  const dir = fixture(t);
  editManifest(dir, (manifest) => { manifest.files['core/GATEROOK-CORE.EXE'] = 'a'.repeat(64); });
  assert.throws(() => validateWindowsInputs(dir), /Case-colliding Windows payload path/);
});

test('rejects symlinked payload files', { skip: process.platform === 'win32' }, (t) => {
  const dir = fixture(t);
  const target = join(dir, 'bridge/gw-bridge.jar');
  rmSync(target);
  symlinkSync('../plugin-host/plugin-host.jar', target);
  assert.throws(() => validateWindowsInputs(dir), /Symlinks are not allowed/);
});

test('rejects wrong native architecture for every required executable and DLL', async (t) => {
  for (const name of nativeFiles) {
    await t.test(name, (t) => {
      const dir = fixture(t);
      write(dir, name, pe(0xaa64));
      rehash(dir, name);
      assert.throws(() => validateWindowsInputs(dir), /Windows x64 PE32\+/);
    });
  }
});

test('rejects a renamed host executable instead of a Windows binary', (t) => {
  const dir = fixture(t);
  write(dir, 'core/gaterook-core.exe', Buffer.alloc(128));
  rehash(dir, 'core/gaterook-core.exe');
  assert.throws(() => validateWindowsInputs(dir), /Not a Windows PE executable/);
});

test('rejects incomplete or incompatible Java runtimes', async (t) => {
  const changes = [
    ['wrong Java major', (text) => text.replace('JAVA_VERSION="25', 'JAVA_VERSION="24'), /Java 25/],
    ['missing runtime version', (text) => text.replace(/^JAVA_RUNTIME_VERSION=.*\n/m, ''), /JAVA_RUNTIME_VERSION/],
    ['wrong OS', (text) => text.replace('OS_NAME="Windows"', 'OS_NAME="Linux"'), /Windows x64/],
    ['wrong architecture', (text) => text.replace('OS_ARCH="amd64"', 'OS_ARCH="aarch64"'), /Windows x64/],
    ['missing module', (text) => text.replace('jdk.unsupported', ''), /missing required modules: jdk.unsupported/],
  ];
  for (const [name, change, expected] of changes) {
    await t.test(name, (t) => {
      const dir = fixture(t);
      write(dir, 'jre/release', change(readFileSync(join(dir, 'jre/release'), 'utf8')));
      rehash(dir, 'jre/release');
      assert.throws(() => validateWindowsInputs(dir), expected);
    });
  }
});

test('collects adjacent runtime DLLs and SQLite exports without unrelated build output', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'gaterook-windows-libraries-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  write(dir, 'target/java.dll', pe());
  write(dir, 'target/native-libs/sqlitejdbc.dll', pe());
  write(dir, 'target/native-libs/java.dll', pe());
  write(dir, 'target/core.pdb', 'debug symbols');
  copyRuntimeLibraries([join(dir, 'target'), join(dir, 'target/native-libs')], join(dir, 'out'));
  assert.deepEqual(readFileSync(join(dir, 'out/java.dll')), pe());
  assert.deepEqual(readFileSync(join(dir, 'out/sqlitejdbc.dll')), pe());
  assert.throws(() => readFileSync(join(dir, 'out/core.pdb')), /ENOENT/);
});

test('rejects different runtime DLLs with the same Windows filename', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'gaterook-windows-libraries-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  write(dir, 'target/java.dll', pe());
  write(dir, 'target/native-libs/JAVA.DLL', pe(0xaa64));
  assert.throws(() => copyRuntimeLibraries([join(dir, 'target'), join(dir, 'target/native-libs')], join(dir, 'out')),
    /Conflicting native runtime DLLs/);
});
