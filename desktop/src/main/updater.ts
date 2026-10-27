// electron-updater in the app (ARCHITECTURE.md A20, §7.1 "checkForUpdates(), onUpdateState(cb) … autoUpdate flag in
// General, once a day"). Pure rules in updates.ts. The updater module is loaded only where it may run (an installed
// Windows or macOS build with an update source), so a development run never reaches the network for it.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { UpdateState } from '../shared/contract.ts';
import type { Logger } from './log.ts';
import { checkDue, FIRST_CHECK_AFTER_MS, updatesUnavailable, updateStateFor, type UpdaterEvent } from './updates.ts';

export interface UpdatesOptions {
  readonly packaged: boolean;
  readonly resourcesPath: string;
  readonly log: Logger;
  readonly push: (state: UpdateState) => void;
  /** General › "Check for updates automatically", asked before every automatic check. */
  readonly autoUpdate: () => Promise<boolean>;
}

type Updater = import('electron-updater').AppUpdater;

/** The bundle's app-update.yml (electron-builder writes it into an installed build), or null. */
function updateConfig(resourcesPath: string): string | null {
  try {
    return readFileSync(join(resourcesPath, 'app-update.yml'), 'utf8');
  } catch {
    return null;
  }
}

export class Updates {
  private readonly o: UpdatesOptions;
  private state: UpdateState | null = null;
  private lastCheckedAt: string | null = null;
  private lastCheckAt: number | null = null;
  private updater: Updater | null = null;
  private timer: NodeJS.Timeout | null = null;
  private readonly unavailable: string | null;

  constructor(o: UpdatesOptions) {
    this.o = o;
    this.unavailable = updatesUnavailable(o.packaged, process.platform, o.packaged ? updateConfig(o.resourcesPath) : null);
  }

  current(): UpdateState | null {
    return this.state;
  }

  /** "Check now" in Settings › General. */
  async check(): Promise<void> {
    this.lastCheckAt = Date.now();
    this.lastCheckedAt = new Date(this.lastCheckAt).toISOString();
    if (this.unavailable) {
      this.set({ state: 'error', message: this.unavailable, lastCheckedAt: this.lastCheckedAt });
      return;
    }
    const updater = await this.load();
    try {
      await updater.checkForUpdates();
    } catch (e) {
      this.set(updateStateFor({ type: 'error', message: (e as Error).message }, this.lastCheckedAt));
    }
  }

  /** Automatic checks: a minute after start, then daily, while the setting is on. */
  schedule(): void {
    if (this.unavailable || this.timer) return;
    const tick = async (): Promise<void> => {
      try {
        if (checkDue(await this.o.autoUpdate(), this.lastCheckAt, Date.now())) await this.check();
      } catch (e) {
        this.o.log(`automatic update check skipped: ${(e as Error).message}`);
      }
    };
    // Hourly ticks; a check happens when a day has passed since the last one (a laptop asleep over night included).
    const run = (): void => {
      void tick();
      this.timer = setTimeout(run, 60 * 60 * 1000);
      this.timer.unref?.();
    };
    this.timer = setTimeout(run, FIRST_CHECK_AFTER_MS);
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private set(state: UpdateState): void {
    this.state = state;
    this.o.push(state);
  }

  private async load(): Promise<Updater> {
    if (this.updater) return this.updater;
    const { autoUpdater } = await import('electron-updater');
    autoUpdater.logger = {
      info: (m: unknown) => this.o.log(`updater: ${String(m)}`),
      warn: (m: unknown) => this.o.log(`updater warning: ${String(m)}`),
      error: (m: unknown) => this.o.log(`updater error: ${String(m)}`),
      debug: () => undefined,
    };
    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.allowPrerelease = false;
    autoUpdater.allowDowngrade = false;
    const on = (e: UpdaterEvent): void => this.set(updateStateFor(e, this.lastCheckedAt));
    autoUpdater.on('checking-for-update', () => on({ type: 'checking-for-update' }));
    autoUpdater.on('update-available', (info) => on({ type: 'update-available', version: info.version }));
    autoUpdater.on('update-not-available', (info) => on({ type: 'update-not-available', version: info.version }));
    autoUpdater.on('update-downloaded', (info) => on({ type: 'update-downloaded', version: info.version }));
    autoUpdater.on('error', (e) => on({ type: 'error', message: e?.message }));
    this.updater = autoUpdater;
    return autoUpdater;
  }
}
