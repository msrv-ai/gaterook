import assert from 'node:assert/strict';
import { chmodSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it, type TestContext } from 'node:test';
import { prepareBridgeLibraries } from '../src/main/bridge-libraries.ts';
import { StartupProblem } from '../src/main/startup-failure.ts';
import { tempDir } from './helpers.ts';

function fixture(t: TestContext) {
  const temp = tempDir('bridge-libraries');
  t.after(() => temp.remove());
  const resources = join(temp.dir, 'resources');
  const source = join(resources, 'bridge');
  const home = join(temp.dir, 'home');
  mkdirSync(source, { recursive: true });
  mkdirSync(home);
  return { root: temp.dir, resources, source, home, bin: join(home, 'bin') };
}

describe('exported Windows bridge dependencies', () => {
  it('places bundled DLLs beside the external bridge without copying or replacing other files', (t) => {
    const f = fixture(t);
    mkdirSync(f.bin);
    writeFileSync(join(f.bin, 'gw-bridge.exe'), 'core-owned exported bridge');
    writeFileSync(join(f.bin, 'client-note.txt'), 'keep');
    writeFileSync(join(f.source, 'gw-bridge.exe'), 'bundled bridge');
    writeFileSync(join(f.source, 'gw-bridge.jar'), 'JAR fallback');
    writeFileSync(join(f.source, 'java.dll'), 'native JNI library');
    writeFileSync(join(f.source, 'VCRUNTIME140.dll'), 'Microsoft C runtime');
    assert.deepEqual(prepareBridgeLibraries(f.resources, f.home, 'win32'), ['VCRUNTIME140.dll', 'java.dll']);
    assert.equal(readFileSync(join(f.bin, 'java.dll'), 'utf8'), 'native JNI library');
    assert.equal(readFileSync(join(f.bin, 'VCRUNTIME140.dll'), 'utf8'), 'Microsoft C runtime');
    assert.equal(readFileSync(join(f.bin, 'gw-bridge.exe'), 'utf8'), 'core-owned exported bridge');
    assert.equal(readFileSync(join(f.bin, 'client-note.txt'), 'utf8'), 'keep');
    assert.equal(existsSync(join(f.bin, 'gw-bridge.jar')), false);
  });

  it('creates the bridge directory, skips equal DLLs and atomically replaces changed libraries', (t) => {
    const f = fixture(t);
    const target = join(f.bin, 'net.dll');
    writeFileSync(join(f.source, 'net.dll'), 'first version');
    assert.deepEqual(prepareBridgeLibraries(f.resources, f.home, 'win32'), ['net.dll']);
    const before = new Date('2020-01-01T00:00:00Z');
    utimesSync(target, before, before);
    assert.deepEqual(prepareBridgeLibraries(f.resources, f.home, 'win32'), []);
    assert.equal(statSync(target).mtimeMs, before.getTime());
    writeFileSync(join(f.source, 'net.dll'), 'second version');
    assert.deepEqual(prepareBridgeLibraries(f.resources, f.home, 'win32'), ['net.dll']);
    assert.equal(readFileSync(target, 'utf8'), 'second version');
    assert.deepEqual(readdirSync(f.bin), ['net.dll']);
  });

  it('leaves non-Windows, development and older bundles without DLLs unchanged', (t) => {
    const f = fixture(t);
    writeFileSync(join(f.source, 'java.dll'), 'library');
    assert.deepEqual(prepareBridgeLibraries(f.resources, f.home, 'darwin'), []);
    assert.deepEqual(prepareBridgeLibraries(f.resources, f.home, 'linux'), []);
    assert.deepEqual(prepareBridgeLibraries(null, f.home, 'win32'), []);
    assert.deepEqual(prepareBridgeLibraries(join(f.root, 'absent'), f.home, 'win32'), []);
    assert.equal(existsSync(f.bin), false);
  });

  it('rejects directories with DLL names before copying any files', (t) => {
    const f = fixture(t);
    writeFileSync(join(f.source, 'a.dll'), 'library');
    mkdirSync(join(f.source, 'z.dll'));
    assert.throws(() => prepareBridgeLibraries(f.resources, f.home, 'win32'), /regular files/);
    assert.equal(existsSync(f.bin), false);
  });

  it('rejects source and destination links, preserving the outside target', { skip: process.platform === 'win32' }, (t) => {
    const f = fixture(t);
    const outside = join(f.root, 'outside.dll');
    writeFileSync(outside, 'outside');
    symlinkSync(outside, join(f.source, 'linked.dll'));
    assert.throws(() => prepareBridgeLibraries(f.resources, f.home, 'win32'), /regular files/);
    assert.equal(existsSync(f.bin), false);
    const other = fixture(t);
    writeFileSync(join(other.source, 'java.dll'), 'library');
    mkdirSync(other.bin);
    symlinkSync(outside, join(other.bin, 'java.dll'));
    assert.throws(() => prepareBridgeLibraries(other.resources, other.home, 'win32'), /regular files/);
    assert.equal(readFileSync(outside, 'utf8'), 'outside');
  });

  it('a changed DLL another process holds stops the start with every library left as it was', { skip: process.platform === 'win32' }, (t) => {
    const f = fixture(t);
    writeFileSync(join(f.source, 'a.dll'), 'old a');
    writeFileSync(join(f.source, 'b.dll'), 'old b');
    prepareBridgeLibraries(f.resources, f.home, 'win32');
    writeFileSync(join(f.source, 'a.dll'), 'new a');
    writeFileSync(join(f.source, 'b.dll'), 'new b');
    // On Windows a running gw-bridge.exe keeps its DLLs from being opened for writing; read-only stands in here.
    chmodSync(join(f.bin, 'b.dll'), 0o400);

    assert.throws(() => prepareBridgeLibraries(f.resources, f.home, 'win32'), (e: unknown) => {
      assert.ok(e instanceof StartupProblem);
      assert.match(e.message, /while another app uses them\. Quit the AI apps that connect through GateRook/);
      return true;
    });
    assert.equal(readFileSync(join(f.bin, 'a.dll'), 'utf8'), 'old a', 'no mix of old and new libraries');
    assert.equal(readFileSync(join(f.bin, 'b.dll'), 'utf8'), 'old b');
  });

  it('accepts a home that is a link, as the core does, and still refuses a linked bin', { skip: process.platform === 'win32' }, (t) => {
    const f = fixture(t);
    writeFileSync(join(f.source, 'java.dll'), 'library');
    const linked = join(f.root, 'linked-home');
    symlinkSync(f.home, linked);
    assert.deepEqual(prepareBridgeLibraries(f.resources, linked, 'win32'), ['java.dll']);
    assert.equal(readFileSync(join(f.bin, 'java.dll'), 'utf8'), 'library');
  });

  it('rejects a linked bridge directory', (t) => {
    const f = fixture(t);
    const outside = join(f.root, 'outside');
    mkdirSync(outside);
    writeFileSync(join(f.source, 'java.dll'), 'library');
    symlinkSync(outside, f.bin, 'junction');
    assert.throws(() => prepareBridgeLibraries(f.resources, f.home, 'win32'), /regular directory/);
    assert.deepEqual(readdirSync(outside), []);
  });
});
