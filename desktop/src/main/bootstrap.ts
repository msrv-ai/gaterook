// The core's bootstrap line (ARCHITECTURE.md §7.1): one JSON line on its stdin, then stdin stays open for as long as
// main lives — EOF means the app is gone and the core exits. The reference parser is the core's
// platform.boot.BootstrapParser; this side writes exactly what it reads.
import { randomBytes } from 'node:crypto';

/** The secrets of one core launch. New on every start: a restarted core never accepts an old signature. */
export interface Launch {
  /** 32 random bytes, base64url without padding: signs every admin request (X-Gateway-Auth). */
  readonly launchSecret: string;
  /** Random id of this launch; the core puts it on its answers (X-Gateway-Instance) and main checks it. */
  readonly instanceNonce: string;
}

export function newLaunch(random: (n: number) => Buffer = randomBytes): Launch {
  return {
    launchSecret: random(32).toString('base64url'),
    instanceNonce: random(24).toString('base64url'),
  };
}

export interface BootstrapFields extends Launch {
  /** The 32-byte key of the OS-protected master keyset file, or null (then the keyset is a 0400 file). */
  readonly fileKey: Buffer | null;
  /** `os`, `off`, `unavailable` or the Linux safeStorage backend (`gnome_libsecret`, `basic_text`, …). */
  readonly protection: string;
  readonly port: number;
  readonly publicPort: number;
}

const PROTECTION = /^[a-z0-9_]{1,32}$/;

/**
 * The line, with its line break. Throws on values the core would refuse, so a mistake shows here and not as
 * BOOTSTRAP_INVALID. The caller zeroes `fileKey` once the line is written; the returned string can't be zeroed, so it
 * goes straight to the pipe and isn't kept.
 */
export function bootstrapLine(f: BootstrapFields): string {
  if (Buffer.from(f.launchSecret, 'base64url').length < 32) throw new Error('The launch secret must have 32 bytes');
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(f.instanceNonce)) throw new Error('The instance nonce must be 16-128 base64url characters');
  if (f.fileKey !== null && f.fileKey.length !== 32) throw new Error('The file key must have 32 bytes');
  if (!PROTECTION.test(f.protection)) throw new Error(`Unknown protection ${f.protection}`);
  for (const port of [f.port, f.publicPort]) {
    if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error(`Invalid port ${port}`);
  }
  if (f.port === f.publicPort) throw new Error('The public port must differ from the local port');
  return JSON.stringify({
    v: 1,
    launchSecret: f.launchSecret,
    instanceNonce: f.instanceNonce,
    fileKey: f.fileKey === null ? null : f.fileKey.toString('base64url'),
    protection: f.protection,
    port: f.port,
    publicPort: f.publicPort,
  }) + '\n';
}
