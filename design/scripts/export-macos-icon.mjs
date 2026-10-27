#!/usr/bin/env node
// Export the kit's vector artwork for Electron's raster Dock API and classic macOS application bundles.
// Optional authoring dependency: sharp (installed under the ignored root ai/ directory; see design/README.md).
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const sharp = require('sharp');
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const output = join(root, 'assets', 'gaterook');
const source = await readFile(join(output, 'gaterook-icon.svg'), 'utf8');
if (!source.includes('viewBox="0 0 64 64"')) throw new Error('Expected the canonical 64-unit icon artboard.');
if (process.platform !== 'darwin') throw new Error('Exporting the macOS icon set requires Apple iconutil on macOS.');

// Classic raster icons need their own clear space: dock.setIcon does not use Icon Composer's system composition.
// An 824 px body matches the scale of Apple's bundled Activity Monitor raster icon (206 px on its 256 px canvas).
// This is a platform export measurement, not a UI token or a numerical requirement in the current layered-icon HIG.
const canvas = 1024;
const body = 824;
const inset = (canvas - body) / 2;
const artwork = source.match(/<svg\b[^>]*>([\s\S]*?)<\/svg>/)?.[1]
  .replace(/<(title|desc)\b[^>]*>[\s\S]*?<\/\1>/g, '').trim();
if (!artwork) throw new Error('The canonical icon has no vector artwork.');
const mac = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024" role="img" aria-labelledby="mac-icon-title mac-icon-desc">
  <title id="mac-icon-title">GateRook macOS app icon</title>
  <desc id="mac-icon-desc">The canonical white rook and violet tile, centered with transparent macOS clear space.</desc>
  <g transform="translate(${inset} ${inset}) scale(${body / 64})">${artwork}</g>
</svg>
`;
await writeFile(join(output, 'gaterook-icon-macos.svg'), mac);
await sharp(Buffer.from(mac)).png().toFile(join(output, 'gaterook-icon-macos.png'));

const temporaryRoot = join(root, '..', 'ai', 'mac-icon-export');
await mkdir(temporaryRoot, { recursive: true });
const temporary = await mkdtemp(join(temporaryRoot, 'icon-'));
try {
  const iconset = join(temporary, 'GateRook.iconset');
  await mkdir(iconset);
  // Apple's classic iconset sizes: 16, 32, 128, 256 and 512 points, each at 1x and 2x.
  for (const size of [16, 32, 128, 256, 512]) {
    for (const scale of [1, 2]) {
      const pixels = size * scale;
      const name = `icon_${size}x${size}${scale === 2 ? '@2x' : ''}.png`;
      await sharp(Buffer.from(mac)).resize(pixels, pixels).png().toFile(join(iconset, name));
    }
  }
  const result = spawnSync('/usr/bin/iconutil', ['--convert', 'icns', '--output',
    join(output, 'gaterook-icon-macos.icns'), iconset], { encoding: 'utf8' });
  if (result.error || result.status !== 0) throw new Error(result.error?.message ?? result.stderr.trim());
} finally {
  await rm(temporary, { recursive: true, force: true });
}
console.log('Exported the macOS SVG, 1024 px PNG and complete 1x/2x ICNS from the canonical kit artwork.');
