// Shared bits of the unit tests: throwaway folders, waiting for a condition, the fake core's location.
import { mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const FAKE_CORE = join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-core.mjs');

/** A fresh folder (real path: macOS's /var is a symlink to /private/var), removed by the returned function. */
export function tempDir(name: string): { dir: string; remove: () => void } {
  const dir = realpathSync(mkdtempSync(join(tmpdir(), `gw-desktop-${name}-`)));
  return { dir, remove: () => rmSync(dir, { recursive: true, force: true }) };
}

export async function waitFor<T>(what: string, probe: () => T | null | undefined | false, timeoutMs = 10_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = probe();
    if (value !== null && value !== undefined && value !== false) return value;
    if (Date.now() > deadline) throw new Error(`Timed out waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 20));
  }
}

export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));
