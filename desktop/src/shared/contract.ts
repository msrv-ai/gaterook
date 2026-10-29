// What main and preload share: the renderer-facing types of `window.gwDesktop` and the IPC channel names.
//
// The types mirror ui/src/app/core/desktop/desktop-bridge.ts (the UI's contract, ARCHITECTURE.md §7.1 "Preload"):
// the renderer gets exactly these shapes, never a secret or an Electron object. The private-key picker returns the
// explicitly chosen path for connection settings; no desktop method accepts it back to read or open the file.

/** A progress line of the core's start, plus the `runtime` step main adds itself. */
export interface CoreStep {
  readonly step: string;
  readonly state: 'running' | 'done' | 'failed';
  /** Main's measure between the step's running and done lines; absent while it runs. */
  readonly ms?: number | null;
}

export type CoreLock = 'MASTER_KEY_MISSING' | 'MASTER_KEY_INVALID';

export type DesktopCoreState =
  | {
      readonly kind: 'unlock-required';
      readonly unlocking: boolean;
      readonly error: string | null;
      readonly authentication?: 'touch-id' | 'windows-hello' | 'system';
      /** The compatibility name shown by macOS in its access dialog. */
      readonly keychainService?: string;
    }
  | {
      readonly kind: 'starting';
      readonly steps: readonly CoreStep[];
      readonly port?: number;
      readonly publicPort?: number;
      readonly version?: string;
    }
  | {
      readonly kind: 'ready';
      readonly firstRun: boolean;
      readonly locked: CoreLock | null;
      readonly version: string;
      readonly ports: { readonly local: number; readonly public: number };
      readonly runtime: 'native' | 'jvm';
    }
  | {
      readonly kind: 'failed';
      readonly reason: string;
      readonly detail: string | null;
      readonly portOwner: { readonly pid: number; readonly user: string } | null;
      readonly log: string | null;
      readonly logTail?: string | null;
      readonly exitCode?: number | null;
      readonly port?: number;
      readonly publicPort?: number;
      readonly version?: string;
    }
  | {
      readonly kind: 'stopped';
      readonly exitCode: number | null;
      readonly reason: string | null;
      readonly uptimeMs: number | null;
      readonly at?: string | null;
      readonly log: string | null;
      readonly logTail?: string | null;
      readonly port?: number;
    };

export type UpdateState =
  | {
      readonly state: 'checking' | 'available' | 'downloaded' | 'installing' | 'up-to-date';
      readonly version?: string;
      readonly lastCheckedAt?: string;
    }
  | { readonly state: 'downloading'; readonly version?: string; readonly percent?: number; readonly lastCheckedAt?: string }
  | { readonly state: 'error'; readonly message: string; readonly version?: string; readonly lastCheckedAt?: string };

/** One server-sent event of the core, relayed to the renderer when it asked for the IPC fallback. */
export interface RelayedEvent {
  readonly type: string;
  readonly data: unknown;
  readonly id: string | null;
}

/** The settings of General that main applies itself (`applyGeneral`). */
export interface GeneralSettings {
  readonly openAtLogin?: boolean;
  readonly startHidden?: boolean;
}

/**
 * IPC channels. `invoke` ones answer; `send` ones are fire-and-forget; `push` ones go from main to the renderer.
 * Main handles a fixed list (ipc.ts) and checks every sender (ipc-guard.ts).
 */
export const IPC = {
  // invoke
  /** The states to replay to a new subscriber, oldest first: the last `starting` (its steps) and the current one. */
  coreState: 'gw:core-state',
  pendingDeepLink: 'gw:pending-deep-link',
  updateState: 'gw:update-state',
  openExternal: 'gw:open-external',
  saveFile: 'gw:save-file',
  openFile: 'gw:open-file',
  choosePrivateKeyFile: 'gw:choose-private-key-file',
  signInToGoogle: 'gw:sign-in-to-google',
  openMcpb: 'gw:open-mcpb',
  openClientConfig: 'gw:open-client-config',
  reveal: 'gw:reveal',
  applyGeneral: 'gw:apply-general',
  unlock: 'gw:unlock',
  restartCore: 'gw:restart-core',
  resetApplication: 'gw:reset-application',
  createFullBackup: 'gw:create-full-backup',
  restoreFullBackup: 'gw:restore-full-backup',
  usePort: 'gw:use-port',
  findFreePort: 'gw:find-free-port',
  checkForUpdates: 'gw:check-for-updates',
  installUpdate: 'gw:install-update',
  // send
  setNativeTheme: 'gw:set-native-theme',
  quit: 'gw:quit',
  relayEvents: 'gw:relay-events',
  // push
  coreStatePush: 'gw:core-state-push',
  deepLinkPush: 'gw:deep-link-push',
  eventPush: 'gw:event-push',
  updateStatePush: 'gw:update-state-push',
} as const;

export type IpcChannel = (typeof IPC)[keyof typeof IPC];
