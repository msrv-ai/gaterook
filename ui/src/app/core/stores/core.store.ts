import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiClient } from '../api/api-client';
import { ApiError, isApiError } from '../api/api-error';
import { CoreApi, CoreInfo, Health } from '../api/core.api';
import { CoreStep, DesktopBridge, DesktopCoreState } from '../desktop/desktop-bridge';

/**
 * Where the gateway core is, from the app's point of view (ARCHITECTURE.md §7.1, §8.4 core-starting):
 * - `connecting` — nothing known yet (first paint);
 * - `starting` — Electron reports progress lines (master key, migrations, local API);
 * - `running` — `/api/health` answered `ok` or `degraded`: the shell is shown;
 * - `locked` — the master key is missing or invalid: only restore / create work;
 * - `failed` — the core didn't start (port in use, …), `stopped` — it exited;
 * - `unreachable` — a browser without Electron and no core answering (development).
 */
export type CoreStateKind = 'connecting' | 'starting' | 'running' | 'locked' | 'failed' | 'stopped' | 'unreachable';

export interface CoreState {
  readonly kind: CoreStateKind;
  readonly steps: readonly CoreStep[];
  readonly reason: string | null;
  readonly detail: string | null;
  readonly portOwner: { readonly pid: number; readonly user: string } | null;
  readonly exitCode: number | null;
  readonly uptimeMs: number | null;
  /** When the core exited (`stopped`). */
  readonly exitedAt: string | null;
  /** The core's log file and its last lines, as Electron's main read them. */
  readonly logFile: string | null;
  readonly logTail: string | null;
  /** The ports Electron started the core on: known before `/api/health` answers (port in use, a new port). */
  readonly port: number | null;
  readonly publicPort: number | null;
  /** The core's version as Electron knows it (the app's), before `/api/health` says it. */
  readonly version: string | null;
  readonly firstRun: boolean;
}

const INITIAL: CoreState = {
  kind: 'connecting',
  steps: [],
  reason: null,
  detail: null,
  portOwner: null,
  exitCode: null,
  uptimeMs: null,
  exitedAt: null,
  logFile: null,
  logTail: null,
  port: null,
  publicPort: null,
  version: null,
  firstRun: false,
};

/** How often a browser session asks `/api/health` while the core isn't answering. */
const RETRY_MS = 2000;

/** A health check that hangs this long counts as no answer (a frozen core must not keep the shell waiting). */
const HEALTH_TIMEOUT_MS = 10_000;

/** Two answers whose start times (now − uptime) differ by more than this come from different core instances. */
const SAME_START_MS = 1500;

/** Which core answered: its instance nonce when the proxy passes it on, and when it started on this machine's clock. */
interface Instance {
  readonly nonce: string | null;
  readonly startedAt: number;
}

@Injectable({ providedIn: 'root' })
export class CoreStore {
  private readonly api = inject(CoreApi);
  private readonly desktop = inject(DesktopBridge);
  private readonly stateSignal = signal<CoreState>(INITIAL);
  private readonly healthSignal = signal<Health | null>(null);
  private readonly infoSignal = signal<CoreInfo | null>(null);
  private readonly client = inject(ApiClient);
  private retry: ReturnType<typeof setTimeout> | null = null;
  /** The health check in flight: callers that ask meanwhile (a failing call, a dropped stream) share it. */
  private refreshing: Promise<void> | null = null;
  private started = false;
  private instance: Instance | null = null;
  private readonly instanceChangesSignal = signal(0);

  readonly state = this.stateSignal.asReadonly();
  readonly health = this.healthSignal.asReadonly();
  readonly info = this.infoSignal.asReadonly();

  readonly running = computed(() => this.state().kind === 'running');
  readonly locked = computed(() => this.state().kind === 'locked');
  /**
   * `127.0.0.1:7465`: the sidebar's meta line and every endpoint URL. While the core hasn't answered on it (starting,
   * port in use), the port Electron is starting it on.
   */
  readonly address = computed(() => {
    const s = this.stateSignal();
    const answered = this.healthSignal()?.ports.local;
    const port = s.kind === 'running' || s.kind === 'locked' ? (answered ?? s.port) : (s.port ?? answered);
    return `127.0.0.1:${port ?? 7465}`;
  });
  /** The public listener's port (the tunnel's), which the local API may never take; null when nothing said it yet. */
  readonly publicPort = computed(() => this.healthSignal()?.ports.public ?? this.stateSignal().publicPort ?? null);
  readonly version = computed(() => this.healthSignal()?.version ?? this.stateSignal().version);
  readonly warnings = computed(() => this.healthSignal()?.warnings ?? []);
  /**
   * Counts the times another core instance answered than before (a restart, by Electron or by hand): the shell then
   * reloads what it keeps (approvals, tunnel, settings) and opens a fresh event stream.
   */
  readonly instanceChanges = this.instanceChangesSignal.asReadonly();

  /** Starts watching the core: Electron's state when there is one, `/api/health` in any case. */
  start(): void {
    if (this.started) return;
    this.started = true;
    this.desktop.onCoreState((s) => this.onDesktopState(s));
    void this.refresh();
  }

  /** Asks `/api/health` again (after a restart, on "Check again", when a call met a network error). */
  refresh(): Promise<void> {
    // One check at a time: its own failure also reaches noteApiError (through the interceptor) and must not loop.
    this.refreshing ??= this.check().finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  private async check(): Promise<void> {
    if (this.retry) clearTimeout(this.retry);
    this.retry = null;
    try {
      const health = await withTimeout(this.api.health(), HEALTH_TIMEOUT_MS);
      this.noteInstance({ nonce: this.client.instance(), startedAt: Date.now() - health.uptimeMs });
      this.healthSignal.set(health);
      this.patch({ kind: health.status === 'locked' ? 'locked' : 'running', reason: health.locked });
      // Not awaited: the check is over once health answered (a slow /api/info must not hold the next check back).
      // A locked core answers only health and the master key (§7.1): info waits until it is unlocked.
      if (!this.infoSignal() && health.status !== 'locked') void this.api.info().then((info) => this.infoSignal.set(info), () => undefined);
    } catch (e) {
      this.onUnreachable(e);
    }
  }

  /**
   * A call met `network` or `locked` (problemJsonInterceptor reports every such failure), or the event stream could
   * not reconnect: while the shell thinks the core is running, ask `/api/health` — it decides whether the status
   * screen takes over, so one failed call to a route never does it alone.
   */
  noteApiError(e: unknown): void {
    if (!isApiError(e) || this.state().kind !== 'running') return;
    if (e.kind === 'network' || e.kind === 'locked') void this.refresh();
  }

  /** The event stream failed to reconnect: the same check as a failed call. */
  noteStreamFailure(): void {
    if (this.state().kind === 'running') void this.refresh();
  }

  private noteInstance(next: Instance): void {
    const prev = this.instance;
    this.instance = next;
    if (!prev) return;
    const other =
      prev.nonce && next.nonce ? prev.nonce !== next.nonce : Math.abs(next.startedAt - prev.startedAt) > SAME_START_MS;
    if (other) {
      this.infoSignal.set(null);
      this.instanceChangesSignal.update((n) => n + 1);
    }
  }

  private onUnreachable(e: unknown): void {
    const current = this.state().kind;
    // In Electron, main says what happened; in a browser nobody does, so say "unreachable" and keep asking.
    if (!this.desktop.available && current !== 'starting') {
      this.patch({ kind: 'unreachable', detail: isApiError(e) ? (e as ApiError).detail : null });
    }
    if (this.retry) clearTimeout(this.retry);
    this.retry = setTimeout(() => void this.refresh(), RETRY_MS);
  }

  private onDesktopState(s: DesktopCoreState): void {
    switch (s.kind) {
      case 'starting':
        this.patch({
          kind: 'starting',
          steps: s.steps,
          port: s.port ?? this.state().port,
          publicPort: s.publicPort ?? this.state().publicPort,
          version: s.version ?? this.state().version,
        });
        break;
      case 'ready':
        this.patch({
          firstRun: s.firstRun,
          kind: s.locked ? 'locked' : this.state().kind,
          reason: s.locked,
          port: s.ports.local,
          publicPort: s.ports.public,
          version: s.version,
        });
        void this.refresh();
        break;
      case 'failed':
        this.patch({
          kind: 'failed',
          reason: s.reason,
          detail: s.detail,
          portOwner: s.portOwner,
          exitCode: s.exitCode ?? null,
          logFile: s.log,
          logTail: s.logTail ?? null,
          port: s.port ?? this.state().port,
          publicPort: s.publicPort ?? this.state().publicPort,
          version: s.version ?? this.state().version,
        });
        break;
      case 'stopped':
        this.patch({
          kind: 'stopped',
          exitCode: s.exitCode,
          reason: s.reason,
          uptimeMs: s.uptimeMs,
          exitedAt: s.at ?? null,
          logFile: s.log,
          logTail: s.logTail ?? null,
          port: s.port ?? this.state().port,
        });
        break;
    }
  }

  private patch(p: Partial<CoreState>): void {
    this.stateSignal.update((s) => ({ ...s, ...p }));
  }
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const late = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new ApiError('network', 0, {})), ms);
  });
  return Promise.race([p, late]).finally(() => {
    if (timer) clearTimeout(timer);
  });
}
