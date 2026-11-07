#!/usr/bin/env node
// Copies bundled runtime assets into Angular's workspace before start, build, and test.
// ui/public/assets is ignored; only changed bytes are rewritten to avoid unnecessary rebuilds.
// Usage: node scripts/sync-ui-assets.mjs [--check]

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ASSETS = join(ROOT, 'assets');
const OUT = join(ROOT, 'ui', 'public', 'assets');

/** [source relative to assets, target relative to ui/public/assets]. */
function entries() {
  const fonts = readdirSync(join(ASSETS, 'fonts'))
    .filter((f) => f.endsWith('.woff2') || f.endsWith('.txt'))
    .map((f) => [join('fonts', f), join('fonts', f)]);
  const integrations = readdirSync(join(ASSETS, 'integrations'))
    .filter((f) => f.endsWith('.svg') || f.endsWith('.md'))
    .map((f) => [join('integrations', f), join('integrations', f)]);
  return [...fonts, ...integrations, ['theme-boot.js', 'theme-boot.js'], ['logo-mark.svg', 'logo-mark.svg']];
}

const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');

function sync(checkOnly) {
  let changed = 0;
  let stale = 0;
  for (const [from, to] of entries()) {
    const source = readFileSync(join(ASSETS, from));
    const target = join(OUT, to);
    const same = existsSync(target) && sha(readFileSync(target)) === sha(source);
    if (same) continue;
    if (checkOnly) {
      stale += 1;
      console.error(`stale or missing: ui/public/assets/${to}`);
      continue;
    }
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, source);
    changed += 1;
  }
  if (checkOnly) {
    if (stale > 0) process.exit(1);
    console.log('UI assets are in sync');
  } else {
    console.log(`UI assets: ${changed === 0 ? 'up to date' : `${changed} file(s) copied`} → ui/public/assets`);
  }
}

sync(process.argv.includes('--check'));
