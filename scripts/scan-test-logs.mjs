#!/usr/bin/env node
// The core's own logs after the process tests hold no secret (ARCHITECTURE.md §9 "Agents never see secrets",
// "Tokens are shown once"): every *.log under the test homes is searched for the secrets the tests gave the core —
// the smoke descriptors' connection secrets, the passwords and tokens of test-services/.env, GW_SCAN_SECRETS — and for
// any whole gateway token (gwt_/gwb_/gwo_/gwr_ and its 43 characters; logs carry a prefix like gwt_K9xP… at most).
//
//   node scripts/scan-test-logs.mjs [folder...]     default: app/target/core-homes
//
// Exit 1 with the places when something leaked, 2 when there was nothing to scan.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const TOKEN = /\b(?:gwt|gwb|gwo|gwr)_[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])/;
const MIN_LENGTH = 6;
const MAX_BYTES = 64 * 1024 * 1024;

/** The secret values the tests hand to the core, by where they come from. */
export function knownSecrets(base = root, env = process.env) {
  const secrets = new Map();
  const add = (value, from) => {
    if (typeof value === 'string' && value.length >= MIN_LENGTH && !value.includes('${')) secrets.set(value, from);
  };
  const connectors = join(base, 'connectors');
  for (const module of existsSync(connectors) ? readdirSync(connectors) : []) {
    const dir = join(connectors, module, 'src/main/resources/META-INF/mcp-gateway/smoke');
    for (const name of existsSync(dir) ? readdirSync(dir).filter((n) => n.endsWith('.json')) : []) {
      const descriptor = JSON.parse(readFileSync(join(dir, name), 'utf8'));
      for (const touch of descriptor.touches ?? []) {
        for (const value of Object.values(touch.connection?.secrets ?? {})) add(value, `smoke/${name}`);
      }
    }
  }
  const dotEnv = join(base, 'test-services/.env');
  if (existsSync(dotEnv)) {
    for (const line of readFileSync(dotEnv, 'utf8').split('\n')) {
      const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
      if (m && /PASSWORD|TOKEN|SECRET/.test(m[1])) add(m[2].replace(/^["']|["']$/g, ''), `test-services/.env ${m[1]}`);
    }
  }
  for (const value of (env.GW_SCAN_SECRETS ?? '').split(',')) add(value.trim(), 'GW_SCAN_SECRETS');
  return secrets;
}

function logs(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    const stat = statSync(path);
    if (stat.isDirectory()) logs(path, out);
    else if (name.includes('.log') && stat.size <= MAX_BYTES) out.push(path);
  }
  return out;
}

/** Every line of the logs under {@code folders} with a known secret or a whole token. */
export function scan(folders, secrets) {
  const files = folders.filter((f) => existsSync(f)).flatMap((f) => logs(f));
  const leaks = [];
  for (const file of files) {
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      for (const [value, from] of secrets) {
        if (line.includes(value)) leaks.push({ file, line: i + 1, what: `${value.slice(0, 3)}… (${from})` });
      }
      const token = TOKEN.exec(line);
      if (token) leaks.push({ file, line: i + 1, what: `${token[0].slice(0, 8)}… (a whole gateway token)` });
    });
  }
  return { files: files.length, leaks };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const folders = process.argv.length > 2 ? process.argv.slice(2) : [join(root, 'app/target/core-homes')];
  const secrets = knownSecrets();
  const { files, leaks } = scan(folders, secrets);
  if (files === 0) {
    console.error(`scan-test-logs: no logs under ${folders.join(', ')}`);
    process.exit(2);
  }
  for (const l of leaks) console.log(`${relative(process.cwd(), l.file)}:${l.line}: ${l.what}`);
  console.log(`scan-test-logs: ${files} logs, ${secrets.size} known secrets, ${leaks.length} leaks`);
  process.exit(leaks.length ? 1 : 0);
}
