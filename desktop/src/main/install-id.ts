import { randomInt } from 'node:crypto';
import { lstatSync, mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { writeFileAtomic } from './atomic-file.ts';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
export const INSTALL_ID_PATTERN = /^[A-Za-z0-9]{16}$/;

/** One random identity per application data directory, retained across application upgrades. */
export function installId(userDataPath: string): string {
  const path = join(userDataPath, 'install-id');
  try {
    const stat = lstatSync(path);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 17) throw new Error('Invalid installation identity file.');
    const id = readFileSync(path, 'utf8').trim();
    if (!INSTALL_ID_PATTERN.test(id)) throw new Error('Invalid installation identity file.');
    return id;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  }
  // The desktop owns its single-instance lock before this runs. Atomic replacement survives an interrupted write.
  mkdirSync(userDataPath, { recursive: true, mode: 0o700 });
  const id = Array.from({ length: 16 }, () => ALPHABET[randomInt(ALPHABET.length)]).join('');
  writeFileAtomic(path, `${id}\n`);
  return id;
}

/** Updater errors can embed request URLs; never expose the persistent identity in logs or renderer messages. */
export function redactInstallId(message: unknown, id?: string | null): string {
  let text = String(message).replace(/(install_id(?:=|%3[dD]))[^&\s"'<>]*/gi, '$1[redacted]');
  if (id) text = text.replaceAll(id, '[redacted]');
  return text;
}
