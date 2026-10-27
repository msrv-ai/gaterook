// Main's log: `<home>/logs/desktop.log` (0600, next to the core's core.log), rotated at 5 MB, and the terminal in a
// development run. Lines say what happened, never a secret: no launch secret, file key, token or grant id is ever
// passed to it (the broker logs grant prefixes only).
import { appendFileSync, chmodSync, mkdirSync, renameSync, statSync } from 'node:fs';
import { join } from 'node:path';

export type Logger = (line: string) => void;

const MAX_BYTES = 5 * 1024 * 1024;

export function createLogger(home: string | null, echo: boolean): Logger {
  const dir = home ? join(home, 'logs') : null;
  const file = dir ? join(dir, 'desktop.log') : null;
  let ready = false;
  let written = 0;
  return (line: string) => {
    const text = `${new Date().toISOString()} ${line.replace(/[\r\n]+/g, ' ')}\n`;
    if (echo) process.stderr.write(`[desktop] ${text}`);
    if (!file || !dir) return;
    try {
      if (!ready) {
        mkdirSync(dir, { recursive: true, mode: 0o700 });
        ready = true;
      }
      // Checked now and then, not on every line.
      if (written++ % 200 === 0) {
        try {
          if (statSync(file).size > MAX_BYTES) renameSync(file, `${file}.1`);
        } catch {
          // No file yet.
        }
      }
      appendFileSync(file, text, { mode: 0o600 });
      if (written === 1 && process.platform !== 'win32') chmodSync(file, 0o600);
    } catch {
      // A log that can't be written must not take the app down.
    }
  };
}
