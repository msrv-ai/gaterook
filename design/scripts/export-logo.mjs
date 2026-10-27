#!/usr/bin/env node
// Optional asset-authoring tools, isolated from the application dependencies.
// See the regeneration command in design/README.md. No installed font is needed.
import { createRequire } from 'node:module';
import { readFile, mkdir, writeFile, copyFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const require = createRequire(import.meta.url);
const fontkit = require('fontkit');
const { decompress } = require('wawoff2');
const sharp = require('sharp');
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const output = join(root, 'assets/gaterook');
const tokens = JSON.parse(await readFile(join(root, 'tokens/tokens.json'), 'utf8'));
const value = (token, mode = 'light') => token.$extensions?.['gw.modes']?.[mode] ?? token.$value;
const color = {
  violet: value(tokens.color.brand.solid),
  white: value(tokens.color.brand['on-solid']),
  ink: value(tokens.color.text.primary),
  lightInk: value(tokens.color.text.primary, 'dark'),
  canvas: value(tokens.color.bg.canvas),
  darkCanvas: value(tokens.color.bg.canvas, 'dark'),
  surface: value(tokens.color.bg.surface),
  muted: value(tokens.color.text.muted),
  darkMuted: value(tokens.color.text.muted, 'dark'),
  border: value(tokens.color.border.subtle),
};

// Source artwork: a rook with three battlements and an open, arched gateway.
// Coordinates are vector geometry in a 64-unit artboard, not UI CSS values.
// Gate = 16 units wide, battlements/gaps = 8 units: legible down to 16 px.
const rook = [
  'M13.5 12H18.5Q20 12 20 13.5V20H28V13.5Q28 12 29.5 12',
  'H34.5Q36 12 36 13.5V20H44V13.5Q44 12 45.5 12',
  'H50.5Q52 12 52 13.5V26Q52 28 50 28H48L44 44H50',
  'Q52 44 52 46V50.5Q52 52 50.5 52H40V38A8 8 0 0 0 24 38',
  'V52H13.5Q12 52 12 50.5V46Q12 44 14 44H20L16 28H14',
  'Q12 28 12 26V13.5Q12 12 13.5 12Z',
].join('');
const mark = (fill) => `<path fill="${fill}" d="${rook}"/>`;
const icon = () => `<rect width="64" height="64" rx="16" fill="${color.violet}"/>${mark(color.white)}`;
const round = (number) => Number(number.toFixed(4));

// Decode the kit's local WOFF2 first: fontkit's variation engine expects SFNT.
const fontBytes = await decompress(await readFile(join(root, 'assets/fonts/inter-latin-wght-normal.woff2')));
const font = fontkit.create(Buffer.from(fontBytes));
const fonts = new Map();
function typeset(text, size, weight = 600, tracking = 0) {
  if (!fonts.has(weight)) fonts.set(weight, font.getVariation({ wght: weight }));
  const run = fonts.get(weight).layout(text);
  const scale = size / font.unitsPerEm;
  let cursor = 0;
  let minY = Infinity;
  let maxY = -Infinity;
  const paths = run.glyphs.map((glyph, index) => {
    const position = run.positions[index];
    const x = cursor + position.xOffset * scale;
    const y = -position.yOffset * scale;
    if (glyph.bbox.minY !== Infinity) {
      minY = Math.min(minY, -glyph.bbox.maxY * scale + y);
      maxY = Math.max(maxY, -glyph.bbox.minY * scale + y);
    }
    cursor += position.xAdvance * scale + tracking;
    return `<path transform="translate(${round(x)} ${round(y)}) scale(${round(scale)} ${round(-scale)})" d="${glyph.path.toSVG()}"/>`;
  });
  return { paths: paths.join('\n'), width: cursor - tracking, minY, maxY };
}
const word = typeset('GateRook', 44, tokens.font.weight.semibold.$value,
  parseFloat(tokens.font.tracking.tighter.$value) * 44);
const logoWidth = Math.ceil((72 + word.width + 16) / 8) * 8;
const wordBaseline = round(40 - (word.minY + word.maxY) / 2);
const lockup = (fill) => `<g transform="translate(4 8)">${mark(color.violet)}</g>
<g fill="${fill}" transform="translate(72 ${wordBaseline})">${word.paths}</g>`;

function svg(width, height, body, title, description, id) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="${id}-title ${id}-desc">
  <title id="${id}-title">${title}</title>
  <desc id="${id}-desc">${description}</desc>
  ${body}
</svg>\n`;
}

const assets = [
  ['gaterook-logo', logoWidth, 80, lockup(color.ink), 'GateRook', 'Violet rook with an open gateway and the GateRook wordmark. Inter Semibold, converted to vector outlines. For light backgrounds.', logoWidth * 6],
  ['gaterook-logo-dark', logoWidth, 80, lockup(color.lightInk), 'GateRook', 'Violet rook and light GateRook wordmark, for dark backgrounds. All lettering is outlined.', logoWidth * 6],
  ['gaterook-mark', 64, 64, mark(color.violet), 'GateRook symbol', 'Three battlements and an open gateway form a rook. Gate violet from the design kit.', 1024],
  ['gaterook-mark-mono', 64, 64, mark('currentColor'), 'GateRook monochrome symbol', 'The same rook silhouette in one inherited color. Defaults to black when used as an external image.', 1024],
  ['gaterook-mark-white', 64, 64, mark(color.white), 'GateRook white symbol', 'White rook with a transparent gateway, for dark or colored backgrounds.', 1024],
  ['gaterook-icon', 64, 64, icon(), 'GateRook app icon', 'White rook with an open gateway on a Gate violet rounded square.', 1024],
];
await mkdir(output, { recursive: true });
for (const [name, width, height, body, title, description, rasterWidth] of assets) {
  const source = svg(width, height, body, title, description, name);
  await writeFile(join(output, `${name}.svg`), source);
  await sharp(Buffer.from(source), { density: 72 * rasterWidth / width }).png().toFile(join(output, `${name}.png`));
}
const iconSource = await readFile(join(output, 'gaterook-icon.svg'));
for (const size of [16, 20, 24, 32, 48, 64, 128, 256, 512]) {
  await sharp(iconSource, { density: 72 * size / 64 }).png().toFile(join(output, `gaterook-icon-${size}.png`));
}

// Stable kit entry points share the source artwork above. Keep the canonical
// marks' 32 px intrinsic size while retaining the original 64-unit viewBox.
for (const [sourceName, aliasName, intrinsicSize] of [
  ['gaterook-logo', 'logo', null],
  ['gaterook-icon', 'logo-mark', 32],
  ['gaterook-mark-mono', 'logo-mark-mono', 32],
]) {
  let source = await readFile(join(output, `${sourceName}.svg`), 'utf8');
  if (intrinsicSize !== null) {
    source = source.replace('width="64" height="64"', `width="${intrinsicSize}" height="${intrinsicSize}"`);
  }
  source = source.replaceAll(`${sourceName}-title`, `${aliasName}-title`)
    .replaceAll(`${sourceName}-desc`, `${aliasName}-desc`);
  await writeFile(join(root, 'assets', `${aliasName}.svg`), source);
  if (aliasName !== 'logo-mark-mono') {
    await copyFile(join(output, `${sourceName}.png`), join(root, 'assets', `${aliasName}.png`));
  }
}

// A standalone vector specimen sheet, also rendered to PNG for review.
// Labels are outlined too: preview.svg is fully portable and font-independent.
function label(text, x, y, size = 12, fill = color.muted, weight = 500, tracking = 0) {
  return `<g fill="${fill}" transform="translate(${x} ${y})">${typeset(text, size, weight, tracking).paths}</g>`;
}
const place = (body, x, y, scale = 1) => `<g transform="translate(${x} ${y}) scale(${scale})">${body}</g>`;
const panel = (x, y, width, height, fill, stroke = 'none') =>
  `<rect x="${x}" y="${y}" width="${width}" height="${height}" rx="16" fill="${fill}" stroke="${stroke}"/>`;
const previewParts = [
  `<rect width="1200" height="820" fill="${color.canvas}"/>`,
  label('GateRook', 48, 57, 24, color.ink, 600, -0.5),
  label('LOGO / DESIGN KIT', 965, 54, 11, color.muted, 600, 1),
  panel(48, 96, 1104, 300, color.surface, color.border),
  label('APPLICATION ICON', 88, 134, 10, color.muted, 600, 1),
  place(icon(), 104, 168, 3),
  `<path d="M352 144V348" stroke="${color.border}"/>`,
  label('PRIMARY LOGO', 408, 134, 10, color.muted, 600, 1),
  place(lockup(color.ink), 388, 164, 2.5),
  label('Gate + rook. Controlled access.', 432, 344, 14, color.muted, 400),
  panel(48, 420, 536, 228, color.darkCanvas),
  label('ON DARK', 80, 456, 10, color.darkMuted, 600, 1),
  place(lockup(color.lightInk), 89, 492, 1.65),
  panel(608, 420, 544, 228, color.surface, color.border),
  label('ONE SILHOUETTE', 640, 456, 10, color.muted, 600, 1),
  place(mark(color.ink), 650, 488, 1.8),
  place(mark(color.violet), 818, 488, 1.8),
  panel(991, 488, 112, 112, color.violet),
  place(mark(color.white), 991, 488, 1.75),
  label('SMALL SIZES', 48, 702, 10, color.muted, 600, 1),
];
let smallX = 48;
for (const size of [16, 20, 24, 32, 48]) {
  previewParts.push(place(icon(), smallX, 730 + (48 - size) / 2, size / 64));
  previewParts.push(label(String(size), smallX, 798, 10));
  smallX += 80;
}
previewParts.push(
  label('GATE VIOLET', 608, 702, 10, color.muted, 600, 1),
  `<circle cx="628" cy="750" r="20" fill="${color.violet}"/>`,
  label(color.violet, 664, 756, 18, color.ink, 500),
  label('Inter Semibold', 904, 747, 18, color.ink, 600),
  label('Outlined wordmark', 904, 774, 12),
);
const preview = svg(1200, 820, previewParts.join('\n'), 'GateRook logo collection',
  'Application icon, primary logo, dark background version, monochrome variants and small-size checks. Gate violet and Inter from the application design kit.', 'gaterook-preview');
await writeFile(join(output, 'preview.svg'), preview);
await sharp(Buffer.from(preview)).png().toFile(join(output, 'preview.png'));
console.log(`Exported GateRook logo collection to ${output} (${logoWidth} x 80 lockup; transparent PNGs) and canonical assets/logo aliases.`);
