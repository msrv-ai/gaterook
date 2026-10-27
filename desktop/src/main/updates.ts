// Updates (ARCHITECTURE.md §7.1 "electron-updater", A20; Settings › General "Check for updates automatically"):
// electron-updater's events as the UI's UpdateState, and when a check is due. Only an installed, signed build of
// Windows or macOS updates itself: a development build has nothing to update, and Linux AppImage updates wait for
// a detached signature check of latest-linux.yml (until then Linux gets no automatic updates).
import type { UpdateState } from '../shared/contract.ts';

export type UpdaterEvent =
  | { readonly type: 'checking-for-update' }
  | { readonly type: 'update-available'; readonly version?: string }
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
      return { state: 'available', ...(e.version ? { version: e.version } : {}), ...at };
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
export function updatesUnavailable(packaged: boolean, platform: NodeJS.Platform, updateConfig: string | null): string | null {
  if (!packaged) return 'Updates come with the installed app; this is a development build.';
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

/** A day between automatic checks; the first one a little after start, so it doesn't compete with the core. */
export const CHECK_EVERY_MS = 24 * 60 * 60 * 1000;
export const FIRST_CHECK_AFTER_MS = 60 * 1000;

/** Whether an automatic check is due. */
export function checkDue(autoUpdate: boolean, lastCheckAt: number | null, now: number): boolean {
  return autoUpdate && (lastCheckAt === null || now - lastCheckAt >= CHECK_EVERY_MS);
}

/**
 * Whether automatic checks are on: what the core says now; while it can't (stopped, locked), what it said last
 * (main's copy in desktop.json) — a person who turned them off isn't overruled by a core that is down; before the core
 * ever answered, its default (on).
 */
export function autoUpdateWanted(fromCore: boolean | undefined, lastKnown: boolean | undefined): boolean {
  return fromCore ?? lastKnown ?? true;
}
