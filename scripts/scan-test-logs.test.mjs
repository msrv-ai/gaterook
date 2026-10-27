// node --test scripts/scan-test-logs.test.mjs
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { knownSecrets, scan } from './scan-test-logs.mjs';

function write(path, text) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text);
}

test('the secrets come from the smoke descriptors, test-services/.env and GW_SCAN_SECRETS', () => {
  const base = mkdtempSync(join(tmpdir(), 'scan-base-'));
  write(join(base, 'connectors/x/src/main/resources/META-INF/mcp-gateway/smoke/x.json'), JSON.stringify({
    touches: [{ connection: { secrets: { '/token': 'smoke-only-0001', '/password': '${fixture.password}' } } }],
  }));
  write(join(base, 'test-services/.env'), 'GW_PG_PORT=55432\nGW_PG_PASSWORD=postgres-pw-0001\nGW_X_TOKEN="tok-123456"\n');
  const secrets = knownSecrets(base, { GW_SCAN_SECRETS: 'extra-secret-1, ab' });
  assert.deepEqual([...secrets.keys()].sort(), ['extra-secret-1', 'postgres-pw-0001', 'smoke-only-0001', 'tok-123456']);
});

test('a secret or a whole token in a log is a leak; a token prefix and other files are not', () => {
  const homes = mkdtempSync(join(tmpdir(), 'scan-homes-'));
  const token = 'gwt_' + 'A'.repeat(43);
  write(join(homes, 'a/logs/core.log'), `ok\nconnecting with smoke-only-0001\nissued ${token}\nissued gwt_K9xP…\n`);
  write(join(homes, 'a/stderr.log'), 'nothing here\n');
  write(join(homes, 'a/gateway.db'), 'smoke-only-0001');
  const { files, leaks } = scan([homes], new Map([['smoke-only-0001', 'smoke/x.json']]));
  assert.equal(files, 2);
  assert.deepEqual(leaks.map((l) => [l.line, l.what]), [[2, 'smo… (smoke/x.json)'], [3, 'gwt_AAAA… (a whole gateway token)']]);
});
