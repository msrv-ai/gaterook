import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import { createJre, JRE_MODULES, parseRuntimeVersion } from './jlink.mjs';

test('runtime version parsing accepts Java 25 releases and rejects unsupported or missing versions', () => {
  assert.equal(parseRuntimeVersion('    java.runtime.version = 25.0.1+8-LTS\r\n'), '25.0.1+8-LTS');
  assert.equal(parseRuntimeVersion('java.runtime.version = 25-ea+20'), '25-ea+20');
  assert.throws(() => parseRuntimeVersion('java.runtime.version = 21.0.6+7'), /requires Java 25/);
  assert.throws(() => parseRuntimeVersion('java.version = 25'), /Could not read/);
});

function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'gaterook-jlink-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}

function runtimeRunner(calls, { failure = false, version = '25.0.1+8' } = {}) {
  return (file, args) => {
    calls.push({ file, args });
    if (args.includes('-version')) return { status: 0, stderr: `    java.runtime.version = ${version}\r\n` };
    if (failure) return { status: 1 };
    const output = args[args.indexOf('--output') + 1];
    mkdirSync(join(output, 'bin'), { recursive: true });
    mkdirSync(join(output, 'lib'));
    writeFileSync(join(output, 'release'), `JAVA_RUNTIME_VERSION="${version}"\n`);
    writeFileSync(join(output, 'bin', file.endsWith('.exe') ? 'java.exe' : 'java'), 'runtime');
    writeFileSync(join(output, 'lib', 'modules'), 'modules');
    return { status: 0 };
  };
}

test('Windows runtime uses JDK executables directly and preserves canonical modules and metadata', (t) => {
  const directory = fixture(t);
  const output = join(directory, 'runtime with spaces');
  const calls = [];
  createJre(output, { javaHome: join(directory, 'jdk 25'), platform: 'win32', run: runtimeRunner(calls) });
  assert.equal(calls[0].file, join(directory, 'jdk 25', 'bin', 'java.exe'));
  assert.equal(calls[1].file, join(directory, 'jdk 25', 'bin', 'jlink.exe'));
  assert.equal(calls[1].args[1], JRE_MODULES.join(','));
  assert.ok(calls[1].args.includes('--release-info=add:JAVA_RUNTIME_VERSION=25.0.1+8'));
  assert.ok(existsSync(join(output, 'bin', 'java.exe')));
  assert.deepEqual(readdirSync(directory), ['runtime with spaces']);
});

test('failed jlink preserves the existing generated runtime and cleans temporary output', (t) => {
  const directory = fixture(t);
  const output = join(directory, 'runtime');
  createJre(output, { javaHome: directory, run: runtimeRunner([]) });
  writeFileSync(join(output, 'existing-data'), 'preserve');
  assert.throws(() => createJre(output, { javaHome: directory, run: runtimeRunner([], { failure: true }) }), /jlink failed/);
  assert.equal(readFileSync(join(output, 'existing-data'), 'utf8'), 'preserve');
  assert.deepEqual(readdirSync(directory), ['runtime']);
});

test('refuses unknown output directories and symlinks without invoking Java', (t) => {
  const directory = fixture(t);
  const output = join(directory, 'unrelated');
  mkdirSync(output);
  writeFileSync(join(output, 'keep'), 'preserve');
  const calls = [];
  assert.throws(() => createJre(output, { javaHome: directory, run: runtimeRunner(calls) }), /not a generated GateRook JRE/);
  const link = join(directory, 'link');
  symlinkSync(output, link, 'junction');
  assert.throws(() => createJre(link, { javaHome: directory, run: runtimeRunner(calls) }), /symlink/);
  assert.equal(calls.length, 0);
  assert.equal(readFileSync(join(output, 'keep'), 'utf8'), 'preserve');
});

test('an unsupported JDK cannot delete an existing generated runtime', (t) => {
  const directory = fixture(t);
  const output = join(directory, 'runtime');
  createJre(output, { javaHome: directory, run: runtimeRunner([]) });
  const original = readFileSync(join(output, 'release'), 'utf8');
  assert.throws(() => createJre(output, { javaHome: directory, run: runtimeRunner([], { version: '21.0.6+7' }) }), /requires Java 25/);
  assert.equal(readFileSync(join(output, 'release'), 'utf8'), original);
});
