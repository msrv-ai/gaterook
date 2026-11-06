// electron-updater in the app (ARCHITECTURE.md A20, §7.1 "checkForUpdates(), onUpdateState(cb) … autoUpdate flag in
// General"), checking every hour on macOS and every day elsewhere. Pure rules in updates.ts. The updater module is
// loaded only where it may run (an installed Windows or macOS build with an update source), so a development run
// never reaches the network for it.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { UpdateState } from '../shared/contract.ts';
import type { Logger } from './log.ts';
import { downloadMacUpdate } from './mac-update.ts';
import { installId, redactInstallId } from './install-id.ts';
import { checkStoreMetadata, siteUpdateOptions, updateFeedUrl } from './update-provider.ts';
import { ASK_AGAIN_AFTER_MS, CHECK_TICK_MS, checkEveryMs, due, FIRST_CHECK_AFTER_MS, updatesUnavailable, updateStateFor,
  type UpdaterEvent } from './updates.ts';

export type UpdateDriver = Pick<import('electron-updater').AppUpdater,
  'logger' | 'autoDownload' | 'autoInstallOnAppQuit' | 'allowPrerelease' | 'allowDowngrade'
  | 'checkForUpdates' | 'downloadUpdate' | 'quitAndInstall' | 'setFeedURL'> & {
    on(event: 'checking-for-update', listener: () => void): unknown;
    on(event: 'update-available' | 'update-not-available', listener: (info: { version: string }) => void): unknown;
    on(event: 'download-progress', listener: (info: { percent: number }) => void): unknown;
    on(event: 'error', listener: (error: Error) => void): unknown;
  };

export interface UpdatesOptions {
  readonly packaged: boolean;
  readonly resourcesPath: string;
  readonly userDataPath: string;
  readonly log: Logger;
  readonly push: (state: UpdateState) => void;
  /** General › "Check for updates automatically", asked before every automatic check. */
  readonly autoUpdate: () => Promise<boolean>;
  /** Stop the core and broker before the installer can replace their files. */
  readonly prepareInstall: () => Promise<void>;
  /** Recover the desktop if launching the installer fails after its services have stopped. */
  readonly installFailed: (message: string) => void;
  readonly platform?: NodeJS.Platform;
  readonly arch?: string;
  readonly storeMetadataCheck?: (url: string) => Promise<void>;
  /** MSIX packages receive updates through Microsoft Store, never an external installer. */
  readonly windowsStore?: boolean;
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
  private readonly identity: string | null;
  private readonly storeMetadata: boolean;

  constructor(o: UpdatesOptions) {
    this.o = o;
    this.platform = o.platform ?? process.platform;
    const windowsStore = o.windowsStore ?? process.windowsStore === true;
    this.storeMetadata = o.packaged && this.platform === 'win32' && windowsStore;
    let identity: string | null = null;
    if (o.packaged) {
      try { identity = installId(o.userDataPath); }
      catch { o.log('Could not load the installation identity; update checks are unavailable.'); }
    }
    this.identity = identity;
    this.unavailable = updatesUnavailable(o.packaged, this.platform, o.packaged ? updateConfig(o.resourcesPath) : null,
      windowsStore);
  }

  current(): UpdateState | null {
    return this.state;
  }

  /** "Check now" in Settings › General. */
  check(): Promise<void> {
    return this.checkFrom(Date.now());
  }

  /**
   * A check counted from `at`. The schedule passes the time of its tick, so a core slow to answer can't move the next
   * check a tick later.
   */
  private checkFrom(at: number): Promise<void> {
    if (this.closed || this.installing || this.downloadedVersion) return Promise.resolve();
    if (this.checking) return this.checking;
    this.lastCheckAt = at;
    this.lastCheckedAt = new Date(at).toISOString();
    if (this.unavailable && !this.storeMetadata) {
      this.set({ state: 'error', message: this.unavailable, lastCheckedAt: this.lastCheckedAt });
      return Promise.resolve();
    }
    this.checking = (this.storeMetadata ? this.checkStore() : this.checkNow()).finally(() => { this.checking = null; });
    return this.checking;
  }

  private async checkStore(): Promise<void> {
    try {
      if (!this.identity) throw new Error('The installation identity is unavailable.');
      const url = new URL('store.json', updateFeedUrl(this.platform, this.o.arch ?? process.arch));
      url.searchParams.set('install_id', this.identity);
      await (this.o.storeMetadataCheck ?? checkStoreMetadata)(url.href);
    } catch {
      // Metadata availability never changes Microsoft Store's update authority or exposes a request URL.
      this.o.log('Store update metadata check unavailable.');
    }
    if (!this.closed) this.set({ state: 'error', message: this.unavailable!, lastCheckedAt: this.lastCheckedAt! });
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

  /** Automatic checks: a minute after start, then hourly on macOS and daily elsewhere, while the setting is on. */
  schedule(): void {
    if (this.closed || (this.unavailable && !this.storeMetadata) || this.timer) return;
    const every = checkEveryMs(this.platform);
    let askedAt: number | null = null;
    const tick = async (): Promise<void> => {
      const at = Date.now();
      // The core is asked for the setting only when a check is due; while the setting is off, at most once an hour.
      if (!due(this.lastCheckAt, at, every) || !due(askedAt, at, ASK_AGAIN_AFTER_MS)) return;
      askedAt = at;
      try {
        // "Check now" may have run while the core answered.
        if (await this.o.autoUpdate() && due(this.lastCheckAt, at, every)) await this.checkFrom(at);
      } catch (e) {
        this.o.log(`automatic update check skipped: ${redactInstallId((e as Error).message, this.identity)}`);
      }
    };
    // Minute ticks against the wall clock: a check is due an interval after the last one, so a laptop that slept
    // through it checks within a minute of waking.
    const run = (): void => {
      void tick();
      this.timer = setTimeout(run, CHECK_TICK_MS);
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
    const state = updateStateFor({ type: 'error', message: redactInstallId(this.operationError.message, this.identity) }, this.lastCheckedAt);
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
    if (!this.identity) throw new Error('The installation identity is unavailable. Restart GateRook and try again.');
    const autoUpdater = this.o.loadUpdater ? await this.o.loadUpdater() : (await import('electron-updater')).autoUpdater;
    autoUpdater.logger = {
      info: (m: unknown) => this.o.log(`updater: ${redactInstallId(m, this.identity)}`),
      warn: (m: unknown) => this.o.log(`updater warning: ${redactInstallId(m, this.identity)}`),
      error: (m: unknown) => this.o.log(`updater error: ${redactInstallId(m, this.identity)}`),
      debug: () => undefined,
    };
    autoUpdater.setFeedURL(siteUpdateOptions(this.platform, this.o.arch ?? process.arch, this.identity));
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
