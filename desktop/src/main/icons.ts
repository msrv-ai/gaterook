// The app's mark (design/assets/logo-mark.svg, logo-mark-mono.svg): the GateRook rook drawn in code — the tray and
// window icons at any size, and the installer's icon.png at build time — no binary images in the repository to keep in
// step with the kit. test/misc.test.ts checks the path and the colour against the kit's files.
import { deflateSync, crc32 } from 'node:zlib';

/** The rook of design/scripts/export-logo.mjs, in the kit's 64-unit artboard: three battlements and an open gateway. */
export const ROOK_PATH = 'M13.5 12H18.5Q20 12 20 13.5V20H28V13.5Q28 12 29.5 12H34.5Q36 12 36 13.5V20H44V13.5Q44 12 45.5 12'
  + 'H50.5Q52 12 52 13.5V26Q52 28 50 28H48L44 44H50Q52 44 52 46V50.5Q52 52 50.5 52H40V38A8 8 0 0 0 24 38'
  + 'V52H13.5Q12 52 12 50.5V46Q12 44 14 44H20L16 28H14Q12 28 12 26V13.5Q12 12 13.5 12Z';
/** Gate violet, the kit's `color.brand.solid`: the icon's rounded square (`<rect width="64" height="64" rx="16">`). */
export const BRAND_HEX = '#6C56EE';

const ARTBOARD = 64;
const BRAND: readonly [number, number, number] = [0x6c, 0x56, 0xee];

type Point = readonly [number, number];

/**
 * The path as one closed polygon. It holds only absolute M, H, V, L, Q, A and Z (as the kit writes it); the rounded
 * corners (Q) and the gateway's arc (A) are split into short segments.
 */
export function flatten(d: string): Point[] {
  const tokens = d.match(/[MHVLQAZ]|-?\d*\.?\d+(?:e-?\d+)?/gi) ?? [];
  const out: Point[] = [];
  let i = 0;
  let x = 0;
  let y = 0;
  const num = (): number => Number(tokens[i++]);
  while (i < tokens.length) {
    const cmd = tokens[i++];
    switch (cmd) {
      case 'M': case 'L': x = num(); y = num(); out.push([x, y]); break;
      case 'H': x = num(); out.push([x, y]); break;
      case 'V': y = num(); out.push([x, y]); break;
      case 'Q': {
        const cx = num(); const cy = num(); const ex = num(); const ey = num();
        for (let k = 1; k <= 16; k++) {
          const t = k / 16;
          out.push([(1 - t) ** 2 * x + 2 * (1 - t) * t * cx + t * t * ex, (1 - t) ** 2 * y + 2 * (1 - t) * t * cy + t * t * ey]);
        }
        x = ex; y = ey;
        break;
      }
      case 'A': {
        // A circular arc without rotation (rx = ry), the only kind the mark uses: its centre from the end points.
        const r = num(); num(); num();
        const large = num(); const sweep = num(); const ex = num(); const ey = num();
        const mx = (x + ex) / 2; const my = (y + ey) / 2;
        const half = Math.hypot(ex - x, ey - y) / 2;
        const h = Math.sqrt(Math.max(0, r * r - half * half));
        const side = large === sweep ? 1 : -1; // SVG F.6.5: the centre's side from the two flags
        const ux = (ey - y) / (2 * half); const uy = -(ex - x) / (2 * half);
        const ccx = mx + side * h * ux; const ccy = my + side * h * uy;
        const a0 = Math.atan2(y - ccy, x - ccx);
        let delta = Math.atan2(ey - ccy, ex - ccx) - a0;
        if (sweep && delta < 0) delta += 2 * Math.PI;
        if (!sweep && delta > 0) delta -= 2 * Math.PI;
        const steps = Math.max(8, Math.ceil(Math.abs(delta) / (Math.PI / 90))); // 2° a segment: smooth at 1024 px
        for (let k = 1; k <= steps; k++) {
          const a = a0 + (delta * k) / steps;
          out.push([ccx + r * Math.cos(a), ccy + r * Math.sin(a)]);
        }
        x = ex; y = ey;
        break;
      }
      case 'Z': case 'z': break;
      default: throw new Error(`icons: path command ${cmd} isn't supported`);
    }
  }
  return out;
}

const ROOK = flatten(ROOK_PATH);

/**
 * How much of each pixel of a `size`×`size` grid the polygon covers (0..1): 4×4 samples per pixel, nonzero winding,
 * one scanline per sample row.
 */
function polygonCoverage(polygon: readonly Point[], size: number): Float32Array {
  const cover = new Float32Array(size * size);
  const scale = size / ARTBOARD;
  const pts = polygon.map(([px, py]) => [px * scale, py * scale] as const);
  const crossings: { x: number; dir: number }[] = [];
  for (let row = 0; row < size * 4; row++) {
    const sy = (row + 0.5) / 4;
    crossings.length = 0;
    for (let k = 0; k < pts.length; k++) {
      const [x0, y0] = pts[k]!;
      const [x1, y1] = pts[(k + 1) % pts.length]!;
      if ((y0 <= sy && y1 > sy) || (y1 <= sy && y0 > sy)) {
        crossings.push({ x: x0 + ((sy - y0) / (y1 - y0)) * (x1 - x0), dir: y1 > y0 ? 1 : -1 });
      }
    }
    crossings.sort((a, b) => a.x - b.x);
    const line = Math.floor(row / 4) * size;
    let winding = 0;
    for (let k = 0; k < crossings.length - 1; k++) {
      winding += crossings[k]!.dir;
      if (winding === 0) continue;
      // Samples sit at (j + 0.5) / 4 of a pixel: those from the left crossing up to the right one are inside.
      const first = Math.max(0, Math.ceil(crossings[k]!.x * 4 - 0.5));
      const last = Math.min(size * 4 - 1, Math.ceil(crossings[k + 1]!.x * 4 - 0.5) - 1);
      for (let j = first; j <= last; j++) cover[line + (j >> 2)]! += 1 / 16;
    }
  }
  return cover;
}

/** The icon's rounded square, as a signed distance (negative inside), in the artboard's units. */
function tileDistance(x: number, y: number): number {
  const half = ARTBOARD / 2;
  const radius = 16;
  const qx = Math.abs(x - half) - (half - radius);
  const qy = Math.abs(y - half) - (half - radius);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - radius;
}

function tileCoverage(px: number, py: number, scale: number): number {
  // Far from the edge (more than a pixel's diagonal) the pixel is wholly in or out: only edge pixels are supersampled.
  const center = tileDistance((px + 0.5) * scale, (py + 0.5) * scale);
  if (center > scale * 1.5) return 0;
  if (center < -scale * 1.5) return 1;
  let inside = 0;
  for (let sy = 0; sy < 4; sy++) {
    for (let sx = 0; sx < 4; sx++) {
      if (tileDistance((px + (sx + 0.5) / 4) * scale, (py + (sy + 0.5) / 4) * scale) <= 0) inside++;
    }
  }
  return inside / 16;
}

/**
 * RGBA pixels of the mark at `size`×`size`: `brand` is the app icon — the white rook on the Gate violet rounded square
 * (Windows, Linux, the installer) — and `mono` the rook alone, black with alpha (the macOS template image, tinted by
 * the system).
 */
export function markPixels(size: number, variant: 'brand' | 'mono'): Buffer {
  const out = Buffer.alloc(size * size * 4);
  const rook = polygonCoverage(ROOK, size);
  const scale = ARTBOARD / size;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = (y * size + x) * 4;
      const glyph = Math.min(1, rook[y * size + x]!);
      if (variant === 'mono') {
        out[i + 3] = Math.round(glyph * 255);
        continue;
      }
      const tile = tileCoverage(x, y, scale);
      const white = glyph * tile;
      out[i] = Math.round(BRAND[0] * (1 - white) + 255 * white);
      out[i + 1] = Math.round(BRAND[1] * (1 - white) + 255 * white);
      out[i + 2] = Math.round(BRAND[2] * (1 - white) + 255 * white);
      out[i + 3] = Math.round(tile * 255);
    }
  }
  return out;
}

/** The same pixels in BGRA, the order `nativeImage.createFromBitmap` reads. */
export function toBgra(rgba: Buffer): Buffer {
  const out = Buffer.from(rgba);
  for (let i = 0; i < out.length; i += 4) {
    const r = out[i] ?? 0;
    out[i] = out[i + 2] ?? 0;
    out[i + 2] = r;
  }
  return out;
}

/** A PNG file of RGBA pixels (8-bit, no interlace). */
export function encodePng(rgba: Buffer, width: number, height: number): Buffer {
  const chunk = (type: string, data: Buffer): Buffer => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const typed = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(typed) >>> 0);
    return Buffer.concat([length, typed, crc]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8; // bit depth
  header[9] = 6; // RGBA
  const rows = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    rows[y * (width * 4 + 1)] = 0; // no filter
    rgba.copy(rows, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(rows, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
