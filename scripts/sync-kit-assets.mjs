#!/usr/bin/env node
// Copies the design kit's runtime assets into the Angular app (ARCHITECTURE.md §8.1, owner U0).
//
// Angular only serves assets from inside its workspace ("asset path must be within the workspace root"), and a
// symlink into design/ is unreliable on Windows, so `npm start|build|test -w ui` run this first (pre* scripts):
//
//   design/assets/fonts/*        → ui/public/assets/fonts/      (the kit's @font-face urls, $gw-font-path)
//   design/assets/theme-boot.js  → ui/public/assets/theme-boot.js (classic <script> in index.html: theme before paint)
//   design/assets/logo-mark.svg  → ui/public/assets/logo-mark.svg (the shell's gw-logo, the favicon)
//
// The copies are build inputs, not sources: ui/public/assets is git-ignored. A file is rewritten only when its
// bytes differ, so a running `ng serve` does not rebuild for nothing.
//
// Usage: node scripts/sync-kit-assets.mjs [--check]   (--check: exit 1 when a copy is missing or stale)

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const KIT = join(ROOT, 'design', 'assets');
const OUT = join(ROOT, 'ui', 'public', 'assets');

/** [source relative to design/assets, target relative to ui/public/assets]. */
function entries() {
  const fonts = readdirSync(join(KIT, 'fonts'))
    .filter((f) => f.endsWith('.woff2') || f.endsWith('.txt'))
    .map((f) => [join('fonts', f), join('fonts', f)]);
  return [...fonts, ['theme-boot.js', 'theme-boot.js'], ['logo-mark.svg', 'logo-mark.svg']];
}

const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');

function sync(checkOnly) {
  let changed = 0;
  let stale = 0;
  for (const [from, to] of entries()) {
    const source = readFileSync(join(KIT, from));
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
    console.log('kit assets are in sync');
  } else {
    console.log(`kit assets: ${changed === 0 ? 'up to date' : `${changed} file(s) copied`} → ui/public/assets`);
  }
}

sync(process.argv.includes('--check'));
