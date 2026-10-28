import { Injectable } from '@angular/core';

/**
 * A progress line of the core's start (stdout protocol, ARCHITECTURE.md §7.1). Main adds `runtime` itself (the
 * process started: running at spawn, done with the core's first line) and how long each finished step took.
 */
export interface CoreStep {
  readonly step: 'runtime' | 'master-key' | 'db-migrations' | 'local-api' | string;
  readonly state: 'running' | 'done' | 'failed';
  /** Main's measure between the step's running and done lines; absent while it runs. */
  readonly ms?: number | null;
}

/**
 * The core's state as Electron's main process sees it: progress lines, the ready line, a failed start, an exit.
 * In a browser (development against a separately started core) nothing is reported: CoreStore asks `/api/health`.
 */
export type DesktopCoreState =
  | {
      readonly kind: 'unlock-required';
      readonly unlocking: boolean;
      readonly error: string | null;
      /** Available system authentication; the renderer never receives passwords or biometric data. */
      readonly authentication?: 'touch-id' | 'windows-hello' | 'system';
      /** The existing macOS Keychain entry that its permission dialog may name. */
      readonly keychainService?: string;
    }
  | {
      readonly kind: 'starting';
      readonly steps: readonly CoreStep[];
      /** The ports main started the core on (`desktop.json`): the status screen names them before the API answers. */
      readonly port?: number;
      readonly publicPort?: number;
      /** The bundled core's version (the app's): the status screen's meta line before `/api/health` answers. */
      readonly version?: string;
    }
  | {
      readonly kind: 'ready';
      readonly firstRun: boolean;
      readonly locked: 'MASTER_KEY_MISSING' | 'MASTER_KEY_INVALID' | null;
      readonly version: string;
      readonly ports: { readonly local: number; readonly public: number };
      readonly runtime: 'native' | 'jvm';
    }
  | {
      /** The core didn't start: the `ready:false` line (`reason` PORT_IN_USE, DB_LOCKED, …), or no ready line in time. */
      readonly kind: 'failed';
      readonly reason: string;
      readonly detail: string | null;
      readonly portOwner: { readonly pid: number; readonly user: string } | null;
      /** The core's log file, as the `ready:false` line names it (`log`). */
      readonly log: string | null;
      /** The last lines of that file, read by main. */
      readonly logTail?: string | null;
      readonly exitCode?: number | null;
      readonly port?: number;
      readonly publicPort?: number;
      readonly version?: string;
    }
  | {
      /** The core ran and exited (after main's automatic restarts gave up). */
      readonly kind: 'stopped';
      readonly exitCode: number | null;
      /** What main knows of the cause: the signal, or the log's last error line. */
      readonly reason: string | null;
      readonly uptimeMs: number | null;
      /** When it exited (ISO). */
      readonly at?: string | null;
      /** The core's log file. */
      readonly log: string | null;
      readonly logTail?: string | null;
      readonly port?: number;
    };

export type UpdateState =
  | { readonly state: 'checking' | 'available' | 'downloaded' | 'installing' | 'up-to-date'; readonly version?: string; readonly lastCheckedAt?: string }
  | { readonly state: 'downloading'; readonly version?: string; readonly percent?: number; readonly lastCheckedAt?: string }
  | { readonly state: 'error'; readonly message: string; readonly version?: string; readonly lastCheckedAt?: string };

/** The listeners `usePort` may move: the local API's, the tunnel's public one, or both. */
export interface DesktopPorts {
  readonly port?: number;
  readonly publicPort?: number;
}

/** What Electron's preload exposes as `window.gwDesktop` (§7.1): no secrets; paths only from an explicit key selection. */
export interface GwDesktop {
  readonly platform: 'darwin' | 'win32' | 'linux';
  setNativeTheme(theme: 'system' | 'light' | 'dark'): void;
  openExternal(url: string): Promise<void>;
  /** Shows a save dialog in main; resolves the one-time `saveToken` for the core, or null when cancelled. */
  saveFile(purpose: string): Promise<string | null>;
  openFile(purpose: string): Promise<string | null>;
  /** Shows the native picker and returns the selected private key path, without reading its contents. */
  choosePrivateKeyFile(): Promise<string | null>;
  openMcpb(linkId: string): Promise<void>;
  /** Opens an MCP client's config file in the default editor; main resolves the path from the client id itself. */
  openClientConfig(client: string): Promise<void>;
  revealInFolder(kind: 'backups' | 'logs'): Promise<void>;
  onDeepLink(cb: (route: string) => void): () => void;
  onCoreState(cb: (state: DesktopCoreState) => void): () => void;
  onEvent(cb: (type: string, data: unknown, id: string | null) => void): () => void;
  applyGeneral(settings: { readonly openAtLogin?: boolean; readonly startHidden?: boolean }): Promise<void>;
  /** Requests OS access to the encryption key after the person chooses Unlock GateRook. */
  unlock(): Promise<void>;
  restartCore(): Promise<void>;
  /** Deletes local application data and closes GateRook after an exact RESET confirmation. */
  resetApplication(confirmation: string): Promise<void>;
  /**
   * Writes `desktop.json` and restarts the core on `port` — a number moves the local API, `{ publicPort }` the tunnel's
   * public listener (or both at once); rejects with a reason main can't use it (taken, reserved).
   */
  usePort(port: number | DesktopPorts): Promise<void>;
  /** The first port from `from` up that main could bind just now (never the public listener's), or null. */
  findFreePort(from: number): Promise<number | null>;
  /** Quits the app (the status screen's Quit). */
  quit(): void;
  checkForUpdates(): Promise<void>;
  /** Downloads the available update, then installs it and restarts GateRook. */
  installUpdate(): Promise<void>;
  onUpdateState(cb: (state: UpdateState) => void): () => void;
}

declare global {
  interface Window {
    gwDesktop?: GwDesktop;
  }
}

const NOOP = (): void => undefined;

/**
 * What main said when a desktop call failed (an Error over IPC, prefixed by Electron with "Error invoking remote
 * method '…': Error: "), or `fallback`. `saveFile`/`openFile` reject too — when the core isn't ready or refuses the
 * file the dialog chose — so pages call them inside their `try` and show this.
 */
export function desktopErrorText(e: unknown, fallback: string): string {
  const message = e instanceof Error ? e.message.replace(/^Error invoking remote method '[^']+': (Error: )?/, '').trim() : '';
  return message || fallback;
}

/**
 * The desktop shell, if there is one. In a browser every call is a no-op that says so (`available` false), so pages
 * can disable desktop-only actions with a reason ("Available in the desktop app") instead of failing.
 */
@Injectable({ providedIn: 'root' })
export class DesktopBridge {
  private readonly desktop: GwDesktop | undefined = typeof window === 'undefined' ? undefined : window.gwDesktop;

  /** Whether the app runs inside Electron. */
  readonly available = this.desktop !== undefined;

  /** Also false when a browser UI connects to an older desktop shell. */
  readonly privateKeyPickerAvailable = typeof this.desktop?.choosePrivateKeyFile === 'function';

  /** The OS, for `gw-titlebar--mac`; `web` in a browser. */
  readonly platform: GwDesktop['platform'] | 'web' = this.desktop?.platform ?? 'web';

  setNativeTheme(theme: 'system' | 'light' | 'dark'): void {
    this.desktop?.setNativeTheme(theme);
  }

  async openExternal(url: string): Promise<void> {
    if (this.desktop) await this.desktop.openExternal(url);
    else window.open(url, '_blank', 'noopener,noreferrer');
  }

  /** The `saveToken` for a file chosen in main's dialog; null when cancelled or outside Electron. */
  async saveFile(purpose: string): Promise<string | null> {
    return this.desktop ? this.desktop.saveFile(purpose) : null;
  }

  async openFile(purpose: string): Promise<string | null> {
    return this.desktop ? this.desktop.openFile(purpose) : null;
  }

  async choosePrivateKeyFile(): Promise<string | null> {
    return this.desktop?.choosePrivateKeyFile?.() ?? null;
  }

  async openMcpb(linkId: string): Promise<void> {
    await this.desktop?.openMcpb(linkId);
  }

  async revealInFolder(kind: 'backups' | 'logs'): Promise<void> {
    await this.desktop?.revealInFolder(kind);
  }

  /** Open config on a client card; outside Electron the card shows the action as unavailable. */
  async openClientConfig(client: string): Promise<void> {
    await this.desktop?.openClientConfig(client);
  }

  onDeepLink(cb: (route: string) => void): () => void {
    return this.desktop?.onDeepLink(cb) ?? NOOP;
  }

  onCoreState(cb: (state: DesktopCoreState) => void): () => void {
    return this.desktop?.onCoreState(cb) ?? NOOP;
  }

  /** Events relayed by main over IPC (the fallback when streaming through `app://` doesn't work). */
  onEvent(cb: (type: string, data: unknown, id: string | null) => void): (() => void) | null {
    return this.desktop ? this.desktop.onEvent(cb) : null;
  }

  async applyGeneral(settings: { readonly openAtLogin?: boolean; readonly startHidden?: boolean }): Promise<void> {
    await this.desktop?.applyGeneral(settings);
  }

  async restartCore(): Promise<void> {
    await this.desktop?.restartCore();
  }

  async resetApplication(confirmation: string): Promise<void> {
    if (!this.desktop) throw new Error('Reset is available only in the desktop app.');
    if (confirmation !== 'RESET') throw new Error('Type RESET exactly to confirm.');
    await this.desktop.resetApplication(confirmation);
  }

  async unlock(): Promise<void> {
    await this.desktop?.unlock();
  }

  async usePort(port: number | DesktopPorts): Promise<void> {
    await this.desktop?.usePort(port);
  }

  /** A free port main checked; null outside Electron (nothing can check it there). */
  async findFreePort(from: number): Promise<number | null> {
    return this.desktop ? this.desktop.findFreePort(from) : null;
  }

  quit(): void {
    this.desktop?.quit();
  }

  async checkForUpdates(): Promise<void> {
    await this.desktop?.checkForUpdates();
  }

  async installUpdate(): Promise<void> {
    if (typeof this.desktop?.installUpdate !== 'function') throw new Error('Installing updates requires a supported desktop app.');
    await this.desktop.installUpdate();
  }

  onUpdateState(cb: (state: UpdateState) => void): () => void {
    return this.desktop?.onUpdateState(cb) ?? NOOP;
  }
}
