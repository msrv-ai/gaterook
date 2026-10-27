#!/usr/bin/env node
// Stages the pinned cloudflared for the installers (ARCHITECTURE.md A6, decision 8): reads the same pins the core
// checks before every start (core/src/main/resources/ai/msrv/gaterook/core/tunnel/binaries.json), downloads each
// platform's release asset into ~/.cache/mcp-gateway/cloudflared/<version>/, refuses it unless its SHA-256 is the
// pinned one, takes the agent out of a .tgz (macOS) and checks the agent's own hash, then writes it to
// desktop/stage/cloudflared/<platform>/cloudflared[.exe] — the layout electron-builder copies to
// <resources>/cloudflared/<platform>/ (extraResources), where the core looks for it (-Dgateway.resources).
//
//   node scripts/stage-cloudflared.mjs                  this machine's platform
//   node scripts/stage-cloudflared.mjs --all            every pinned platform
//   node scripts/stage-cloudflared.mjs --platform darwin-arm64 [--platform …]
//   node scripts/stage-cloudflared.mjs --out <dir>      another stage root
//
// Downloads honour HTTPS_PROXY through the platform's fetch; nothing is written unless both hashes match.
import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, writeFile, chmod, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const pinsFile = join(root, 'core/src/main/resources/ai/msrv/gaterook/core/tunnel/binaries.json');

function args(argv) {
  const out = { all: false, platforms: [], out: join(root, 'desktop/stage') };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--all') out.all = true;
    else if (argv[i] === '--platform') out.platforms.push(argv[++i]);
    else if (argv[i] === '--out') out.out = resolve(argv[++i]);
    else throw new Error(`unknown argument ${argv[i]}`);
  }
  return out;
}

/** linux-x64, darwin-arm64, windows-x64: the key of the pins and of the bundle (TunnelBinaries.platform). */
export function currentPlatform(platform = process.platform, arch = process.arch) {
  const os = platform === 'darwin' ? 'darwin' : platform === 'win32' ? 'windows' : 'linux';
  return `${os}-${arch === 'arm64' ? 'arm64' : 'x64'}`;
}

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

/** The one regular file named `name` (or ./name) at the top of a gzipped ustar archive, as the core reads it. */
export function fromTarGz(tgz, name) {
  const tar = gunzipSync(tgz);
  for (let off = 0; off + 512 <= tar.length;) {
    const header = tar.subarray(off, off + 512);
    const entry = cstr(header, 0, 100);
    if (!entry) break;
    const prefix = cstr(header, 345, 155);
    const path = prefix ? `${prefix}/${entry}` : entry;
    const size = parseInt(cstr(header, 124, 12).trim() || '0', 8);
    const type = String.fromCharCode(header[156]);
    const start = off + 512;
    if ((type === '0' || type === '\0') && (path === name || path === `./${name}`)) {
      return tar.subarray(start, start + size);
    }
    off = start + Math.ceil(size / 512) * 512;
  }
  throw new Error(`no ${name} in the archive`);
}

function cstr(buf, offset, length) {
  const slice = buf.subarray(offset, offset + length);
  const end = slice.indexOf(0);
  return slice.subarray(0, end < 0 ? length : end).toString('ascii');
}

async function download(url, cacheFile, expected) {
  try {
    const cached = await readFile(cacheFile);
    if (sha256(cached) === expected) return cached;
  } catch {
    // Not cached yet.
  }
  const response = await fetch(url, { redirect: 'follow', headers: { 'User-Agent': 'mcp-gateway-stage' } });
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (sha256(bytes) !== expected) {
    throw new Error(`${url}: SHA-256 ${sha256(bytes)} is not the pinned ${expected}; nothing staged`);
  }
  await mkdir(dirname(cacheFile), { recursive: true });
  await writeFile(`${cacheFile}.part`, bytes);
  await rename(`${cacheFile}.part`, cacheFile);
  return bytes;
}

export async function stage(platform, pins, outRoot) {
  const tool = pins.cloudflared;
  const pin = tool.platforms[platform];
  if (!pin) throw new Error(`cloudflared isn't pinned for ${platform}`);
  const cacheFile = join(homedir(), '.cache/mcp-gateway/cloudflared', tool.version, platform,
    pin.url.substring(pin.url.lastIndexOf('/') + 1));
  const asset = await download(pin.url, cacheFile, pin.sha256);
  const binary = pin.archive === 'tgz' ? fromTarGz(asset, pin.binary) : asset;
  if (sha256(binary) !== pin.binarySha256) {
    throw new Error(`${platform}: the agent's SHA-256 ${sha256(binary)} is not the pinned ${pin.binarySha256}`);
  }
  const target = join(outRoot, 'cloudflared', platform, pin.binary);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(`${target}.part`, binary);
  await chmod(`${target}.part`, 0o755);
  await rename(`${target}.part`, target);
  return { platform, target, bytes: (await stat(target)).size, version: tool.version };
}

async function main() {
  const opts = args(process.argv.slice(2));
  const pins = JSON.parse(await readFile(pinsFile, 'utf8'));
  const platforms = opts.all ? Object.keys(pins.cloudflared.platforms)
    : opts.platforms.length ? opts.platforms : [currentPlatform()];
  for (const p of platforms) {
    const r = await stage(p, pins, opts.out);
    console.log(`cloudflared ${r.version} ${r.platform}: ${r.target} (${r.bytes} bytes, SHA-256 checked)`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(`stage-cloudflared: ${e.message}`);
    process.exit(1);
  });
}
