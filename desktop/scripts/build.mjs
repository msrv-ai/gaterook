#!/usr/bin/env node
// Bundles main and preload with esbuild into desktop/dist (ARCHITECTURE.md §7.1):
//   dist/main.cjs     main process: our code, @mcp-gateway/broker and electron-updater in one file (`electron` stays
//                     external), so the installer packs no node_modules at all
//   dist/preload.cjs  the sandboxed preload: requires `electron` only
//
//   node scripts/build.mjs             development build: dev-only switches, variables and the smoke are in
//   node scripts/build.mjs --release   release build: __GW_DEV_BUILD__ is false and every dev-only branch is dropped
//
// Type checking is `npm run typecheck` (tsc --noEmit); esbuild only strips types.
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const release = process.argv.includes('--release');
const dist = join(root, 'dist');
rmSync(dist, { recursive: true, force: true });

const common = {
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node24',
  external: ['electron'],
  define: { __GW_DEV_BUILD__: release ? 'false' : 'true' },
  // Folds `if (DEV_BUILD …)` away in a release, so its bundle has no development code paths.
  minifySyntax: release,
  sourcemap: release ? false : 'linked',
  legalComments: 'none',
  logLevel: 'warning',
  absWorkingDir: root,
};

await build({ ...common, entryPoints: ['src/main/main.ts'], outfile: join(dist, 'main.cjs') });
await build({ ...common, entryPoints: ['src/preload/preload.ts'], outfile: join(dist, 'preload.cjs') });

// Runtime and installer icons consume the design kit's exports without redrawing the artwork.
mkdirSync(join(dist, 'icons'), { recursive: true });
for (const name of ['gaterook-icon.png', 'gaterook-mark-mono.png', 'gaterook-icon-macos.png']) {
  copyFileSync(join(root, '..', 'design', 'assets', 'gaterook', name), join(dist, 'icons', name));
}

// The preload runs sandboxed: only `electron` can be required there.
const preload = readFileSync(join(dist, 'preload.cjs'), 'utf8');
const required = [...preload.matchAll(/require\("([^"]+)"\)/g)].map((m) => m[1]);
if (required.some((r) => r !== 'electron')) {
  console.error(`preload requires ${required.join(', ')}: only electron is available to a sandboxed preload`);
  process.exit(1);
}
if (release) {
  const main = readFileSync(join(dist, 'main.cjs'), 'utf8');
  // The smoke runner (smoke.ts) and the no-sandbox opt-out exist only in development bundles; the development
  // variables of layout.ts stay, but a release never reads them (resolveLayout's `dev` is false there).
  for (const marker of ['gw-smoke-runner', 'GW_DEV_ALLOW_NO_SANDBOX']) {
    if (main.includes(marker)) {
      console.error(`the release bundle still contains ${marker}: development code wasn't dropped`);
      process.exit(1);
    }
  }
}
console.log(`desktop: ${release ? 'release' : 'development'} build in ${dist}`);
