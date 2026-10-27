// node --test scripts/agent-diff.test.mjs
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const script = join(dirname(fileURLToPath(import.meta.url)), 'agent-diff.mjs');

function write(path, json) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(json));
}

test('lists what the agent saw that no metadata file declares, in both formats and with globs', () => {
  const dir = mkdtempSync(join(tmpdir(), 'agent-diff-'));
  write(join(dir, 'agent', 'reachability-metadata.json'), {
    reflection: [{ type: 'a.Declared' }, { type: 'a.Legacy' }, { type: 'a.Missing' }, { type: { proxy: ['a.P'] } }],
    resources: [{ glob: 'a/b/one.properties' }, { glob: 'c/legacy.txt' }, { glob: 'z/missing.txt' }],
  });
  write(join(dir, 'root', 'm', 'META-INF', 'native-image', 'x', 'reachability-metadata.json'), {
    reflection: [{ type: 'a.Declared' }, { type: { proxy: ['a.P'] } }], resources: [{ glob: 'a/**/*.properties' }],
  });
  write(join(dir, 'root', 'n', 'reflect-config.json'), [{ name: 'a.Legacy' }]);
  write(join(dir, 'root', 'n', 'resource-config.json'), { resources: { includes: [{ pattern: '\\Qc/legacy.txt\\E' }] } });

  const out = execFileSync('node', [script, join(dir, 'agent'), join(dir, 'root')], { encoding: 'utf8' });
  assert.match(out, /4 types, 3 resources seen; 3 metadata files compared/);
  assert.match(out, /type {5}a\.Missing/);
  assert.match(out, /resource z\/missing\.txt/);
  assert.doesNotMatch(out, /a\.Declared|a\.Legacy|proxy:a\.P|one\.properties|legacy\.txt/);
});

test('an agent folder without its output is an error', () => {
  const r = spawnSync('node', [script, join(tmpdir(), 'no-such-agent-output')], { encoding: 'utf8' });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /no reachability-metadata\.json/);
});
