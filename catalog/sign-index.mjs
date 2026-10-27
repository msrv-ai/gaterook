#!/usr/bin/env node
// Signs the catalog index (ARCHITECTURE.md §7.5 "Catalogue", report 11 §4.4; step K6).
//
// The core trusts a catalog only by an Ed25519 signature over the raw bytes of index.json, made with a key whose
// public half a release ships (gateway.plugins.catalog-keys). This script turns the source index of this folder into
// the published pair index.json + index.json.sig:
//   - every artifact is pinned: sha256 and size of each plugin JAR and npm lockfile, read from the built files;
//     a lockfile must pin its package at the version and integrity the entry names;
//   - sequence grows with every publication (--sequence, or --previous: the published index + 1), so the core can
//     refuse a rollback; generatedAt is now, expiresAt now + --days (at most 90), after which the core refuses it;
//   - revoked[] lists artifacts the core must refuse and unload (--revoke <sha256>=<reason>, kept across releases).
// The private key comes from a file (--key-file, PKCS#8 PEM or a raw 32-byte seed in base64) or the environment
// (GATEROOK_CATALOG_KEY, same forms). It is never written anywhere and never printed; keep it out of the repo.
//
// Usage:
//   node catalog/sign-index.mjs sign --index catalog/index.json --artifacts dist --out dist --key-id catalog-2026-1
//        (--sequence 412 | --previous published/index.json) [--days 30] [--revoke <sha256>=<reason>]...
//        [--key-file key.pem] [--now 2026-10-26T12:00:00Z]
//   node catalog/sign-index.mjs verify --index dist/index.json --sig dist/index.json.sig --public-key <base64url>
//   node catalog/sign-index.mjs keygen --key-file key.pem --key-id catalog-2026-1
//
// Exit code: 0 done (verify: the signature holds), 1 refused or a bad signature, 2 usage error.

import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const KEY_ENV = 'GATEROOK_CATALOG_KEY';
export const MAX_DAYS = 90;
const KINDS = new Set(['plugin', 'template', 'mcp-server']);
const ID = /^[a-z][a-z0-9-]{1,47}$/;
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?$/;
const SHA256 = /^[0-9a-f]{64}$/;
const KEY_ID = /^[A-Za-z0-9._-]{1,64}$/;
/** PKCS#8 header of an Ed25519 private key; the 32-byte seed follows. */
const PKCS8_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex');
/** SPKI header of an Ed25519 public key; the 32 raw bytes follow. */
const SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

/** Why the script refuses to sign (or verify): a message for the person, never a stack trace. */
export class Refused extends Error {}

// ── Keys ────────────────────────────────────────────────────────────────────────────────────────────────────────

/** A private key from PEM text or a base64/base64url 32-byte seed. */
export function privateKeyFrom(text) {
  const value = String(text ?? '').trim();
  if (!value) throw new Refused('The signing key is empty.');
  let key;
  if (value.includes('-----BEGIN')) {
    try {
      key = createPrivateKey(value);
    } catch {
      throw new Refused('The signing key is not a PEM private key.');
    }
  } else {
    const seed = Buffer.from(value.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
    if (seed.length !== 32) throw new Refused('A raw signing key is 32 bytes in base64.');
    key = createPrivateKey({ key: Buffer.concat([PKCS8_PREFIX, seed]), format: 'der', type: 'pkcs8' });
    seed.fill(0);
  }
  if (key.asymmetricKeyType !== 'ed25519') throw new Refused('The signing key is not an Ed25519 key.');
  return key;
}

/** The signing key of --key-file, else of the environment. */
export function loadPrivateKey({ keyFile, env = process.env }) {
  if (keyFile) {
    if (!existsSync(keyFile)) throw new Refused(`The key file ${keyFile} doesn't exist.`);
    return privateKeyFrom(readFileSync(keyFile, 'utf8'));
  }
  if (env[KEY_ENV]) return privateKeyFrom(env[KEY_ENV]);
  throw new Refused(`No signing key: give --key-file or set ${KEY_ENV}.`);
}

/** The raw 32-byte public key of a private (or public) key. */
export function publicKeyRaw(key) {
  const spki = createPublicKey(key).export({ format: 'der', type: 'spki' });
  return Buffer.from(spki.subarray(SPKI_PREFIX.length));
}

/** The line the core's gateway.plugins.catalog-keys takes: keyId=base64url of the raw public key. */
export function trustLine(keyId, key) {
  return `${keyId}=${publicKeyRaw(key).toString('base64url')}`;
}

/** A fresh key pair (keygen): the private key as PKCS#8 PEM. */
export function generateKey() {
  const { privateKey } = generateKeyPairSync('ed25519');
  return privateKey;
}

// ── Index ───────────────────────────────────────────────────────────────────────────────────────────────────────

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

/** The file name an artifact is found under: the last segment of its URL. */
export function artifactName(url) {
  if (typeof url !== 'string' || !url) throw new Refused('An artifact has no url.');
  if (/^[a-z][a-z0-9+.-]*:/i.test(url) && !url.startsWith('https://')) {
    throw new Refused(`Artifacts are downloaded over https:// only, not ${url}.`);
  }
  const path = url.split(/[?#]/)[0];
  const name = basename(path);
  if (!name || name === '.' || name === '..') throw new Refused(`The artifact url ${url} names no file.`);
  return name;
}

/** Pins a file reference ({url, sha256, size}) to the bytes of the artifact with its name. */
function pin(ref, what, artifacts) {
  const name = artifactName(ref?.url);
  const bytes = artifacts.get(name);
  if (!bytes) throw new Refused(`${what}: the artifact ${name} isn't among the built files.`);
  return { ...ref, sha256: sha256(bytes), size: bytes.length };
}

function checkSemver(entry, where) {
  if (!SEMVER.test(String(entry.version ?? ''))) throw new Refused(`${where}: version must be a semantic version.`);
}

/**
 * The index as it will be published: the source's entries with every artifact pinned, the sequence, the times and the
 * revocations. Pure: reads nothing but its arguments.
 *
 * @param {object} source the source index (catalog/index.json)
 * @param {{artifacts: Map<string, Buffer>, sequence?: number, previous?: object, now: Date, days?: number,
 *          revoke?: Array<{sha256: string, reason: string}>}} options
 */
export function prepareIndex(source, { artifacts, sequence, previous, now, days = 30, revoke = [] }) {
  if (!source || typeof source !== 'object' || source.schema !== 1) throw new Refused('The index must have schema 1.');
  if (!Array.isArray(source.entries) || source.entries.length === 0) throw new Refused('The index lists no entries.');
  if (!Number.isInteger(days) || days < 1 || days > MAX_DAYS) {
    throw new Refused(`--days is 1 to ${MAX_DAYS}: an index must expire, and soon enough to be renewed.`);
  }
  const last = previous ? previous.sequence : undefined;
  if (previous && !Number.isInteger(last)) throw new Refused('The previous index has no sequence.');
  let next = sequence;
  if (next === undefined) {
    if (last === undefined) throw new Refused('Give --sequence or --previous: the sequence must grow with every release.');
    next = last + 1;
  }
  if (!Number.isInteger(next) || next < 1) throw new Refused('The sequence is a positive integer.');
  if (last !== undefined && next <= last) {
    throw new Refused(`The sequence must grow: ${next} isn't greater than the published ${last}.`);
  }

  const seen = new Set();
  const entries = source.entries.map((entry, i) => {
    const where = `entry ${i} (${entry?.kind}:${entry?.id})`;
    if (!KINDS.has(entry?.kind)) throw new Refused(`${where}: kind is plugin, template or mcp-server.`);
    if (!ID.test(String(entry.id ?? ''))) throw new Refused(`${where}: id must match ${ID}.`);
    checkSemver(entry, where);
    const key = `${entry.kind}:${entry.id}@${entry.version}`;
    if (seen.has(key)) throw new Refused(`${where} is listed twice.`);
    seen.add(key);
    if (typeof entry.name !== 'string' || !entry.name.trim()) throw new Refused(`${where}: name is required.`);
    const out = structuredClone(entry);
    if (entry.kind === 'plugin') {
      out.artifact = pin(entry.artifact, where, artifacts);
    } else if (entry.kind === 'mcp-server') {
      const npm = entry.npm;
      if (!npm?.package || !npm?.version || !/^sha512-[A-Za-z0-9+/]+={0,2}$/.test(String(npm.integrity ?? ''))) {
        throw new Refused(`${where}: npm needs package, version and a sha512 integrity.`);
      }
      if (!npm.bin) throw new Refused(`${where}: npm.bin names the package's executable.`);
      if (npm.ignoreScripts === false) throw new Refused(`${where}: a catalog server installs with --ignore-scripts.`);
      out.npm = { ...npm, lockfile: pin(npm.lockfile, where, artifacts) };
      const lock = JSON.parse(artifacts.get(artifactName(npm.lockfile.url)).toString('utf8'));
      const pinned = lock?.packages?.[`node_modules/${npm.package}`];
      if (!pinned || pinned.version !== npm.version || pinned.integrity !== npm.integrity) {
        throw new Refused(`${where}: the lockfile doesn't pin ${npm.package}@${npm.version} with the entry's integrity.`);
      }
    } else {
      if (!Array.isArray(entry.definitions) || entry.definitions.length === 0) {
        throw new Refused(`${where}: a template lists its definitions.`);
      }
      if (typeof entry.templateKind !== 'string' || !entry.templateKind) {
        throw new Refused(`${where}: templateKind names the kind of its definitions (sql-query).`);
      }
      if (entry.tools !== entry.definitions.length) {
        throw new Refused(`${where}: tools must count the definitions (${entry.definitions.length}).`);
      }
    }
    return out;
  });

  const revoked = [];
  for (const r of [...(source.revoked ?? []), ...(previous?.revoked ?? []), ...revoke]) {
    const hash = String(r?.sha256 ?? '').toLowerCase();
    if (!SHA256.test(hash)) throw new Refused(`A revoked artifact needs its sha256 (64 hex digits), not ${r?.sha256}.`);
    if (typeof r.reason !== 'string' || !r.reason.trim()) throw new Refused(`Say why ${hash} is revoked.`);
    if (!revoked.some((x) => x.sha256 === hash)) revoked.push({ sha256: hash, reason: r.reason.trim() });
  }
  for (const e of entries) {
    const hash = e.artifact?.sha256 ?? e.npm?.lockfile?.sha256;
    if (hash && revoked.some((r) => r.sha256 === hash)) {
      throw new Refused(`${e.kind}:${e.id}@${e.version} is revoked: take it out of the index.`);
    }
  }

  const at = new Date(Math.floor(now.getTime() / 1000) * 1000);
  return {
    schema: 1,
    sequence: next,
    generatedAt: at.toISOString().replace('.000Z', 'Z'),
    expiresAt: new Date(at.getTime() + days * 86_400_000).toISOString().replace('.000Z', 'Z'),
    entries,
    revoked,
  };
}

/** The exact bytes that are signed and published. */
export function serialize(index) {
  return Buffer.from(`${JSON.stringify(index, null, 2)}\n`, 'utf8');
}

/** index.json.sig: {keyId, alg, sig} over the raw bytes of index.json. */
export function signBytes(bytes, privateKey, keyId) {
  if (!KEY_ID.test(String(keyId ?? ''))) throw new Refused(`--key-id must match ${KEY_ID}.`);
  return { keyId, alg: 'Ed25519', sig: sign(null, bytes, privateKey).toString('base64') };
}

/** Whether {sig} holds for {bytes} under the raw 32-byte {publicKey}. */
export function verifyBytes(bytes, sigFile, publicKey) {
  if (sigFile?.alg !== 'Ed25519' || typeof sigFile?.sig !== 'string') return false;
  const raw = Buffer.from(publicKey);
  if (raw.length !== 32) return false;
  const key = createPublicKey({ key: Buffer.concat([SPKI_PREFIX, raw]), format: 'der', type: 'spki' });
  return verify(null, bytes, key, Buffer.from(sigFile.sig, 'base64'));
}

// ── CLI ─────────────────────────────────────────────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const [command, ...rest] = argv;
  const opts = { artifacts: [], revoke: [] };
  for (let i = 0; i < rest.length; i++) {
    const flag = rest[i];
    const value = () => {
      if (i + 1 >= rest.length) throw new UsageError(`${flag} needs a value.`);
      return rest[++i];
    };
    switch (flag) {
      case '--index': opts.index = value(); break;
      case '--artifacts': opts.artifacts.push(value()); break;
      case '--out': opts.out = value(); break;
      case '--key-id': opts.keyId = value(); break;
      case '--key-file': opts.keyFile = value(); break;
      case '--sequence': opts.sequence = Number(value()); break;
      case '--previous': opts.previous = value(); break;
      case '--days': opts.days = Number(value()); break;
      case '--now': opts.now = value(); break;
      case '--sig': opts.sig = value(); break;
      case '--public-key': opts.publicKey = value(); break;
      case '--revoke': {
        const v = value();
        const eq = v.indexOf('=');
        if (eq < 0) throw new UsageError('--revoke takes <sha256>=<reason>.');
        opts.revoke.push({ sha256: v.slice(0, eq), reason: v.slice(eq + 1) });
        break;
      }
      default: throw new UsageError(`Unknown option ${flag}.`);
    }
  }
  return { command, opts };
}

class UsageError extends Error {}

/** The plain files of the --artifacts folders by name: what a release uploads next to the index. */
function readArtifacts(dirs) {
  const map = new Map();
  for (const dir of dirs) {
    if (!existsSync(dir)) throw new Refused(`The artifacts folder ${dir} doesn't exist.`);
    for (const name of readdirSync(dir)) {
      if (!statSync(join(dir, name)).isFile()) continue;
      const bytes = readFileSync(join(dir, name));
      if (map.has(name) && !map.get(name).equals(bytes)) throw new Refused(`Two different artifacts are named ${name}.`);
      map.set(name, bytes);
    }
  }
  return map;
}

export function main(argv, { env = process.env, stdout = process.stdout, stderr = process.stderr } = {}) {
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (e) {
    stderr.write(`${e.message}\n`);
    return 2;
  }
  const { command, opts } = parsed;
  try {
    if (command === 'sign') {
      if (!opts.index || !opts.out || !opts.keyId || opts.artifacts.length === 0) {
        throw new UsageError('sign needs --index, --artifacts, --out and --key-id.');
      }
      const key = loadPrivateKey({ keyFile: opts.keyFile, env });
      const source = JSON.parse(readFileSync(opts.index, 'utf8'));
      const previous = opts.previous ? JSON.parse(readFileSync(opts.previous, 'utf8')) : undefined;
      const index = prepareIndex(source, {
        artifacts: readArtifacts(opts.artifacts),
        sequence: opts.sequence,
        previous,
        now: opts.now ? new Date(opts.now) : new Date(),
        days: opts.days ?? 30,
        revoke: opts.revoke,
      });
      const bytes = serialize(index);
      const sig = signBytes(bytes, key, opts.keyId);
      if (!verifyBytes(bytes, sig, publicKeyRaw(key))) throw new Refused('The signature doesn\'t verify.');
      mkdirSync(opts.out, { recursive: true });
      writeFileSync(join(opts.out, 'index.json'), bytes);
      writeFileSync(join(opts.out, 'index.json.sig'), `${JSON.stringify(sig)}\n`);
      stdout.write(`Signed sequence ${index.sequence}, ${index.entries.length} entries, expires ${index.expiresAt}, `
        + `key ${opts.keyId}.\nThe core trusts it with gateway.plugins.catalog-keys=${trustLine(opts.keyId, key)}\n`);
      return 0;
    }
    if (command === 'verify') {
      if (!opts.index || !opts.sig || !opts.publicKey) throw new UsageError('verify needs --index, --sig and --public-key.');
      const bytes = readFileSync(opts.index);
      const sigFile = JSON.parse(readFileSync(opts.sig, 'utf8'));
      const ok = verifyBytes(bytes, sigFile, Buffer.from(opts.publicKey, 'base64url'));
      stdout.write(ok ? `The signature of ${opts.index} holds (key ${sigFile.keyId}).\n`
        : `The signature of ${opts.index} does NOT hold.\n`);
      return ok ? 0 : 1;
    }
    if (command === 'keygen') {
      if (!opts.keyFile || !opts.keyId) throw new UsageError('keygen needs --key-file and --key-id.');
      if (existsSync(opts.keyFile)) throw new Refused(`${opts.keyFile} exists: a key is never overwritten.`);
      const key = generateKey();
      writeFileSync(opts.keyFile, key.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600 });
      chmodSync(opts.keyFile, 0o600);
      stdout.write(`Wrote ${opts.keyFile} (keep it out of the repository).\n`
        + `gateway.plugins.catalog-keys=${trustLine(opts.keyId, key)}\n`);
      return 0;
    }
    throw new UsageError('Commands: sign, verify, keygen.');
  } catch (e) {
    if (e instanceof UsageError) {
      stderr.write(`${e.message}\n`);
      return 2;
    }
    if (e instanceof Refused || e instanceof SyntaxError) {
      stderr.write(`${e.message}\n`);
      return 1;
    }
    throw e;
  }
}

const invoked = process.argv[1] && realpathSync(resolve(process.argv[1])) === realpathSync(fileURLToPath(import.meta.url));
if (invoked) {
  process.exitCode = main(process.argv.slice(2));
}
