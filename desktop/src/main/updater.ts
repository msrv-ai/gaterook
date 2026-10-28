// electron-updater in the app (ARCHITECTURE.md A20, §7.1 "checkForUpdates(), onUpdateState(cb) … autoUpdate flag in
// General, once a day"). Pure rules in updates.ts. The updater module is loaded only where it may run (an installed
// Windows or macOS build with an update source), so a development run never reaches the network for it.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { UpdateState } from '../shared/contract.ts';
import type { Logger } from './log.ts';
import { downloadMacUpdate } from './mac-update.ts';
import { checkDue, FIRST_CHECK_AFTER_MS, updatesUnavailable, updateStateFor, type UpdaterEvent } from './updates.ts';

export type UpdateDriver = Pick<import('electron-updater').AppUpdater,
  'logger' | 'autoDownload' | 'autoInstallOnAppQuit' | 'allowPrerelease' | 'allowDowngrade'
  | 'checkForUpdates' | 'downloadUpdate' | 'quitAndInstall'> & {
    on(event: 'checking-for-update', listener: () => void): unknown;
    on(event: 'update-available' | 'update-not-available', listener: (info: { version: string }) => void): unknown;
    on(event: 'download-progress', listener: (info: { percent: number }) => void): unknown;
    on(event: 'error', listener: (error: Error) => void): unknown;
  };

export interface UpdatesOptions {
  readonly packaged: boolean;
  readonly resourcesPath: string;
  readonly log: Logger;
  readonly push: (state: UpdateState) => void;
  /** General › "Check for updates automatically", asked before every automatic check. */
  readonly autoUpdate: () => Promise<boolean>;
  /** Stop the core and broker before the installer can replace their files. */
  readonly prepareInstall: () => Promise<void>;
  /** Recover the desktop if launching the installer fails after its services have stopped. */
  readonly installFailed: (message: string) => void;
  readonly platform?: NodeJS.Platform;
  readonly loadUpdater?: () => Promise<UpdateDriver>;
}

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
  private updater: UpdateDriver | null = null;
  private loading: Promise<UpdateDriver> | null = null;
  private checking: Promise<void> | null = null;
  private installing: Promise<void> | null = null;
  private availableVersion: string | null = null;
  private downloadedVersion: string | null = null;
  private operationError: Error | null = null;
  private closed = false;
  private prepared = false;
  private installFailureReported = false;
  private timer: NodeJS.Timeout | null = null;
  private readonly unavailable: string | null;
  private readonly platform: NodeJS.Platform;

  constructor(o: UpdatesOptions) {
    this.o = o;
    this.platform = o.platform ?? process.platform;
    this.unavailable = updatesUnavailable(o.packaged, this.platform, o.packaged ? updateConfig(o.resourcesPath) : null);
  }

  current(): UpdateState | null {
    return this.state;
  }

  /** "Check now" in Settings › General. */
  check(): Promise<void> {
    if (this.closed || this.installing || this.downloadedVersion) return Promise.resolve();
    if (this.checking) return this.checking;
    this.lastCheckAt = Date.now();
    this.lastCheckedAt = new Date(this.lastCheckAt).toISOString();
    if (this.unavailable) {
      this.set({ state: 'error', message: this.unavailable, lastCheckedAt: this.lastCheckedAt });
      return Promise.resolve();
    }
    this.checking = this.checkNow().finally(() => { this.checking = null; });
    return this.checking;
  }

  private async checkNow(): Promise<void> {
    try {
      const updater = await this.load();
      if (this.closed) return;
      await updater.checkForUpdates();
    } catch (e) {
      this.fail(e);
    }
  }

  /** One click owns download, verification and restart; repeated clicks share the same operation. */
  install(): Promise<void> {
    if (this.closed || this.prepared) return Promise.resolve();
    if (this.installing) return this.installing;
    this.installing = this.downloadAndInstall().finally(() => { this.installing = null; });
    return this.installing;
  }

  private async downloadAndInstall(): Promise<void> {
    let preparing = false;
    try {
      await this.checking;
      if (this.closed) return;
      if (this.unavailable) throw new Error(this.unavailable);
      const version = this.availableVersion;
      if (!version) throw new Error('Check for an available update before installing.');
      const updater = await this.load();
      if (this.closed) return;
      this.operationError = null;
      if (this.downloadedVersion !== version) {
        this.set({ state: 'downloading', version, percent: 0, ...this.checkedAt() });
        if (this.platform === 'darwin') {
          // The library's download event precedes Squirrel's signature verification on macOS.
          const { autoUpdater: nativeUpdater } = await import('electron');
          if (this.closed) return;
          await downloadMacUpdate(updater, nativeUpdater);
        } else {
          await updater.downloadUpdate();
        }
        if (this.closed) return;
        if (this.operationError) throw this.operationError;
        this.downloadedVersion = version;
        this.set({ state: 'downloaded', version, ...this.checkedAt() });
      }
      this.set({ state: 'installing', version, ...this.checkedAt() });
      preparing = true;
      await this.o.prepareInstall();
      // prepareInstall stops further checks while completing the user's already requested installation.
      this.prepared = true;
      if (this.operationError) throw this.operationError;
      updater.quitAndInstall(true, true);
    } catch (e) {
      // A shutdown failure may have already closed the updater and stopped part of the desktop.
      // Recover that process instead of leaving its window alive with no core or working IPC.
      if (preparing && this.closed) this.prepared = true;
      this.fail(e);
    }
  }

  /** Automatic checks: a minute after start, then daily, while the setting is on. */
  schedule(): void {
    if (this.closed || this.unavailable || this.timer) return;
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
    this.closed = true;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private set(state: UpdateState): void {
    this.state = state;
    this.o.push(state);
  }

  private checkedAt(): { lastCheckedAt?: string } {
    return this.lastCheckedAt ? { lastCheckedAt: this.lastCheckedAt } : {};
  }

  private fail(error: unknown): void {
    this.operationError = error instanceof Error ? error : new Error(String(error));
    if (this.closed && !this.prepared) return;
    const state = updateStateFor({ type: 'error', message: this.operationError.message }, this.lastCheckedAt);
    this.set({ ...state, ...(this.availableVersion ? { version: this.availableVersion } : {}) });
    if (this.prepared && !this.installFailureReported) {
      this.installFailureReported = true;
      this.o.installFailed(state.state === 'error' ? state.message : this.operationError.message);
    }
  }

  private load(): Promise<UpdateDriver> {
    if (this.updater) return Promise.resolve(this.updater);
    if (!this.loading) this.loading = this.loadOnce().finally(() => { this.loading = null; });
    return this.loading;
  }

  private async loadOnce(): Promise<UpdateDriver> {
    const autoUpdater = this.o.loadUpdater ? await this.o.loadUpdater() : (await import('electron-updater')).autoUpdater;
    autoUpdater.logger = {
      info: (m: unknown) => this.o.log(`updater: ${String(m)}`),
      warn: (m: unknown) => this.o.log(`updater warning: ${String(m)}`),
      error: (m: unknown) => this.o.log(`updater error: ${String(m)}`),
      debug: () => undefined,
    };
    autoUpdater.autoDownload = false;
    autoUpdater.autoInstallOnAppQuit = false;
    autoUpdater.allowPrerelease = false;
    autoUpdater.allowDowngrade = false;
    const on = (e: UpdaterEvent): void => {
      if (!this.closed) this.set(updateStateFor(e, this.lastCheckedAt));
    };
    autoUpdater.on('checking-for-update', () => on({ type: 'checking-for-update' }));
    autoUpdater.on('update-available', (info) => {
      this.availableVersion = info.version;
      on({ type: 'update-available', version: info.version });
    });
    autoUpdater.on('update-not-available', (info) => {
      this.availableVersion = null;
      on({ type: 'update-not-available', version: info.version });
    });
    autoUpdater.on('download-progress', (info) => {
      if (this.closed || this.state?.state !== 'downloading' || !Number.isFinite(info.percent)) return;
      this.set({ ...this.state, percent: Math.max(0, Math.min(100, Math.floor(info.percent))) });
    });
    autoUpdater.on('error', (e) => this.fail(e));
    this.updater = autoUpdater;
    return autoUpdater;
  }
}
