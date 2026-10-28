import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { dockerArgs, parseArgs, validateOutput } from './windows-docker.mjs';

test('Docker uses Linux amd64, read-only inputs, separate output and explicit environment', () => {
  const args = dockerArgs({ inputs: '/Windows inputs', out: '/Windows output', image: 'electronuserland/builder:wine' }, '/source snapshot');
  assert.equal(args[args.indexOf('--platform') + 1], 'linux/amd64');
  assert.ok(args.includes('type=bind,source=/Windows inputs,target=/windows-inputs,readonly'));
  assert.ok(args.includes('type=bind,source=/source snapshot,target=/input,readonly'));
  assert.ok(args.includes('type=bind,source=/Windows output,target=/output'));
  assert.equal(args.includes('--env-file'), false);
  const environment = args.flatMap((arg, i) => arg === '--env' ? [args[i + 1]] : []);
  assert.ok(environment.every((entry) => entry.includes('=')));
  assert.equal(environment.some((entry) => /TOKEN|PASSWORD|CSC_LINK|GITHUB/.test(entry)), false);
  assert.equal(args.includes('-t'), false);
});

test('arguments reject ambiguous values and unsafe image or mount syntax', () => {
  for (const argv of [['--out'], ['--inputs', '--dry-run'], ['--unknown'], ['--image', '--privileged'], ['--image', 'bad image']]) {
    assert.throws(() => parseArgs(argv));
  }
  assert.equal(parseArgs(['--dry-run']).dryRun, true);
  assert.throws(() => dockerArgs({ inputs: '/input,readonly', out: '/output', image: 'image' }, '/source'), /commas/);
});

test('output cannot overwrite inputs, their ancestors, existing artifacts or symlink aliases', (t) => {
  const base = mkdtempSync(join(tmpdir(), 'gaterook-windows-output-'));
  t.after(() => rmSync(base, { recursive: true, force: true }));
  const source = join(base, 'source');
  const inputs = join(base, 'inputs');
  mkdirSync(source);
  mkdirSync(inputs);
  assert.throws(() => validateOutput(base, inputs, source), /overlap/);
  assert.throws(() => validateOutput(inputs, inputs, source), /overlap/);
  assert.throws(() => validateOutput(join(inputs, 'child'), inputs, source), /overlap/);
  const output = join(base, 'output');
  mkdirSync(output);
  validateOutput(output, inputs, source);
  writeFileSync(join(output, 'previous.exe'), 'previous release');
  assert.throws(() => validateOutput(output, inputs, source), /new or empty/);
  if (process.platform !== 'win32') {
    symlinkSync(inputs, join(base, 'alias'));
    assert.throws(() => validateOutput(join(base, 'alias', 'child'), inputs, source), /overlap/);
  }
  validateOutput(join(source, 'desktop', 'release', 'windows-x64'), inputs, source);
});
