// Updates (ARCHITECTURE.md §7.1 "electron-updater", A20; Settings › General "Check for updates automatically"):
// electron-updater's events as the UI's UpdateState, and when a check is due. Only an installed, signed build of
// Windows or macOS updates itself: a development build has nothing to update, and Linux AppImage updates wait for
// a detached signature check of latest-linux.yml (until then Linux gets no automatic updates).
import type { UpdateState } from '../shared/contract.ts';

export type UpdaterEvent =
  | { readonly type: 'checking-for-update' }
  | { readonly type: 'update-available'; readonly version?: string; readonly beta?: boolean }
  | { readonly type: 'update-not-available'; readonly version?: string }
  | { readonly type: 'update-downloaded'; readonly version?: string }
  | { readonly type: 'error'; readonly message?: string };

/** The state after an updater event; `at` is when the check began (lastCheckedAt). */
export function updateStateFor(e: UpdaterEvent, lastCheckedAt: string | null): UpdateState {
  const at = lastCheckedAt ? { lastCheckedAt } : {};
  switch (e.type) {
    case 'checking-for-update':
      return { state: 'checking', ...at };
    case 'update-available':
      return { state: 'available', ...(e.version ? { version: e.version } : {}), ...(e.beta ? { beta: true } : {}), ...at };
    case 'update-not-available':
      return { state: 'up-to-date', ...(e.version ? { version: e.version } : {}), ...at };
    case 'update-downloaded':
      return { state: 'downloaded', ...(e.version ? { version: e.version } : {}), ...at };
    case 'error':
      return { state: 'error', message: friendlyUpdateError(e.message), ...at };
  }
}

/** One line for Settings, not a stack trace (electron-updater's messages carry whole HTTP answers). */
export function friendlyUpdateError(message: string | undefined): string {
  const m = (message ?? '').split('\n')[0]?.trim() ?? '';
  if (/ENOTFOUND|EAI_AGAIN|ECONNREFUSED|ETIMEDOUT|net::ERR_/.test(m)) return 'Couldn’t reach the update server. Check the connection and try again.';
  if (/code signature|signature/i.test(m)) return 'The update isn’t signed by GateRook, so it wasn’t installed.';
  if (/404/.test(m)) return 'No update information was found for this version.';
  return m ? m.slice(0, 200) : 'The update check failed.';
}

/**
 * Why this build can't update itself, or null when it can. `updateConfig` is the text of the bundle's app-update.yml,
 * null without one. Only a signed build updates itself (§7.1): on Windows electron-updater checks the installer's
 * signature only against the `publisherName` electron-builder writes there for a signed build — without it
 * (`NsisUpdater.verifySignature` returns null) whatever the feed offers would be installed. macOS needs nothing here:
 * Squirrel.Mac refuses an update that doesn't satisfy the running app's own code signature.
 */
export function updatesUnavailable(packaged: boolean, platform: NodeJS.Platform, updateConfig: string | null,
  windowsStore = false): string | null {
  if (!packaged) return 'Updates come with the installed app; this is a development build.';
  if (platform === 'win32' && windowsStore) return 'Microsoft Store manages updates for this app. Open Microsoft Store to check for updates.';
  if (platform === 'linux') return 'Automatic updates aren’t available on Linux yet. Install new versions from the releases page.';
  if (updateConfig === null) return 'This build has no update source.';
  if (platform === 'win32' && !namesPublisher(updateConfig)) {
    return 'This build isn’t signed, so it can’t tell an update from GateRook from anyone else’s. Install new versions from the releases page.';
  }
  return null;
}

/** Whether app-update.yml names the publisher an update's signature must carry (`publisherName: X`, or a list). */
export function namesPublisher(yml: string): boolean {
  const lines = yml.split(/\r?\n/);
  const at = lines.findIndex((l) => /^publisherName\s*:/.test(l));
  if (at < 0) return false;
  const inline = (lines[at] ?? '').replace(/^publisherName\s*:/, '').replace(/\s+#.*$/, '').trim();
  if (inline !== '') return !['null', '~', '[]', "''", '""'].includes(inline);
  // A block list: "  - CN=…".
  return /^\s+-\s*\S/.test(lines[at + 1] ?? '');
}

const HOUR_MS = 60 * 60 * 1000;

/** The time from one automatic check to the next: an hour on macOS, a day elsewhere. */
export function checkEveryMs(platform: NodeJS.Platform): number {
  return platform === 'darwin' ? HOUR_MS : 24 * HOUR_MS;
}

/** The first automatic check comes a little after start, so it doesn't compete with the core. */
export const FIRST_CHECK_AFTER_MS = 60 * 1000;
/** How often the schedule reads the clock: a computer that slept through a due check runs it soon after waking. */
export const CHECK_TICK_MS = 60 * 1000;
/** While "Check for updates automatically" is off, how long until a due check asks the core for it again. */
export const ASK_AGAIN_AFTER_MS = HOUR_MS;

/** Whether `every` has passed since `last`, or there is no `last` yet. */
export function due(last: number | null, now: number, every: number): boolean {
  return last === null || now - last >= every;
}

/**
 * Whether automatic checks are on: what the core says now; while it can't (stopped, locked), what it said last
 * (main's copy in desktop.json) — a person who turned them off isn't overruled by a core that is down; before the core
 * ever answered, its default (on).
 */
export function autoUpdateWanted(fromCore: boolean | undefined, lastKnown: boolean | undefined): boolean {
  return fromCore ?? lastKnown ?? true;
}

/**
 * Whether beta versions are wanted (Settings › General "Get beta versions"): what the core says now; while it can't,
 * what it said last, so a locked core neither starts nor stops offering them; before the core ever answered, its
 * default (off).
 */
export function betaUpdatesWanted(fromCore: boolean | undefined, lastKnown: boolean | undefined): boolean {
  return fromCore ?? lastKnown ?? false;
}

const NUMBER = '(?:0|[1-9]\\d*)';
const IDENTIFIER = `(?:${NUMBER}|\\d*[A-Za-z-][0-9A-Za-z-]*)`;
const VERSION = new RegExp(`^(${NUMBER})\\.(${NUMBER})\\.(${NUMBER})(?:-(${IDENTIFIER}(?:\\.${IDENTIFIER})*))?`
  + '(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$');

/** Two numbers without leading zeros, as text: versions aren't bounded by what a double holds. */
function compareNumbers(a: string, b: string): number {
  return a.length !== b.length ? Math.sign(a.length - b.length) : a < b ? -1 : a > b ? 1 : 0;
}

/**
 * Which of two versions comes later, by Semantic Versioning 2.0.0 precedence: negative when `a` is the older one,
 * positive when it is the newer one, 0 when neither is. A version with a pre-release part comes before the same
 * version without one; build metadata doesn't count. Null when either isn't a version.
 */
export function compareVersions(a: string, b: string): number | null {
  const x = VERSION.exec(a);
  const y = VERSION.exec(b);
  if (!x || !y) return null;
  for (const part of [1, 2, 3]) {
    const order = compareNumbers(x[part]!, y[part]!);
    if (order !== 0) return order;
  }
  const before = x[4]?.split('.');
  const after = y[4]?.split('.');
  if (!before || !after) return before ? -1 : after ? 1 : 0;
  for (let i = 0; i < Math.min(before.length, after.length); i++) {
    const p = before[i]!;
    const q = after[i]!;
    const numeric = [/^\d+$/.test(p), /^\d+$/.test(q)];
    // Numeric identifiers come before alphanumeric ones, which compare in ASCII order.
    const order = numeric[0] && numeric[1] ? compareNumbers(p, q) : numeric[0] ? -1 : numeric[1] ? 1 : p < q ? -1 : p > q ? 1 : 0;
    if (order !== 0) return order;
  }
  return Math.sign(before.length - after.length);
}
