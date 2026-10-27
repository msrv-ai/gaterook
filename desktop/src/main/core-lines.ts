// The core's stdout protocol (ARCHITECTURE.md §7.1; core: platform.boot.StartupProtocol) and what the status screen
// gets from it (DesktopCoreState, ui/src/app/core/desktop/desktop-bridge.ts):
//
//   {"v":1,"step":"master-key|db-migrations|local-api","state":"running|done|failed"}   progress, any number
//   {"v":1,"ready":true,"firstRun":false,"locked":null|"MASTER_KEY_…","version":"0.4.0","ports":{…},"runtime":"native","pid":…}
//   {"v":1,"ready":false,"reason":"PORT_IN_USE|…","detail":"…","portOwner":{"pid","user"},"log":"…/core.log"}
//
// Nothing else is ever written there; a line that isn't one of these is ignored (and logged by the caller).
import type { CoreLock, CoreStep, DesktopCoreState } from '../shared/contract.ts';

export type CoreLine =
  | { readonly kind: 'progress'; readonly step: string; readonly state: 'running' | 'done' | 'failed' }
  | {
      readonly kind: 'ready';
      readonly firstRun: boolean;
      readonly locked: CoreLock | null;
      readonly version: string;
      readonly ports: { readonly local: number; readonly public: number };
      readonly runtime: 'native' | 'jvm';
      readonly pid: number | null;
    }
  | {
      readonly kind: 'failed';
      readonly reason: string;
      readonly detail: string | null;
      readonly portOwner: { readonly pid: number; readonly user: string } | null;
      readonly log: string | null;
    };

const STEP = /^[a-z][a-z0-9-]{0,31}$/;
const REASON = /^[A-Z][A-Z0-9_]{0,63}$/;

/** One stdout line, or null when it isn't a protocol line of version 1. */
export function parseCoreLine(text: string): CoreLine | null {
  let msg: unknown;
  try {
    msg = JSON.parse(text);
  } catch {
    return null;
  }
  if (!isObject(msg) || msg['v'] !== 1) return null;
  if (typeof msg['step'] === 'string') {
    const state = msg['state'];
    if (!STEP.test(msg['step']) || (state !== 'running' && state !== 'done' && state !== 'failed')) return null;
    return { kind: 'progress', step: msg['step'], state };
  }
  if (msg['ready'] === true) {
    const ports = isObject(msg['ports']) ? msg['ports'] : {};
    const local = port(ports['local']);
    const pub = port(ports['public']);
    if (local === null || pub === null) return null;
    const locked = msg['locked'] === 'MASTER_KEY_MISSING' || msg['locked'] === 'MASTER_KEY_INVALID' ? msg['locked'] : null;
    return {
      kind: 'ready',
      firstRun: msg['firstRun'] === true,
      locked,
      version: typeof msg['version'] === 'string' ? msg['version'].slice(0, 64) : 'unknown',
      ports: { local, public: pub },
      runtime: msg['runtime'] === 'native' ? 'native' : 'jvm',
      pid: Number.isSafeInteger(msg['pid']) ? (msg['pid'] as number) : null,
    };
  }
  if (msg['ready'] === false) {
    const reason = typeof msg['reason'] === 'string' && REASON.test(msg['reason']) ? msg['reason'] : 'STARTUP_FAILED';
    const owner = isObject(msg['portOwner']) ? msg['portOwner'] : null;
    const portOwner = owner && Number.isSafeInteger(owner['pid']) && typeof owner['user'] === 'string'
      ? { pid: owner['pid'] as number, user: owner['user'].slice(0, 64) } : null;
    return {
      kind: 'failed',
      reason,
      detail: typeof msg['detail'] === 'string' ? msg['detail'].slice(0, 1000) : null,
      portOwner,
      log: typeof msg['log'] === 'string' ? msg['log'].slice(0, 4096) : null,
    };
  }
  return null;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function port(v: unknown): number | null {
  return Number.isInteger(v) && (v as number) >= 1 && (v as number) <= 65_535 ? (v as number) : null;
}

/** What main knows about one start before the core says anything: the ports it chose and the bundled version. */
export interface StartMeta {
  readonly port: number;
  readonly publicPort: number;
  readonly version: string;
}

/**
 * The progress of one start, as the status screen shows it: the `runtime` step main adds itself (running at spawn,
 * done with the core's first line), then the core's steps in the order they came, each with the time between its
 * running and done lines on main's clock.
 */
export class StartProgress {
  private readonly meta: StartMeta;
  private steps: CoreStep[];
  private readonly began = new Map<string, number>();

  constructor(meta: StartMeta, spawnedAt: number) {
    this.meta = meta;
    this.steps = [{ step: 'runtime', state: 'running' }];
    this.began.set('runtime', spawnedAt);
  }

  /** The state right after spawn. */
  state(): DesktopCoreState {
    return { kind: 'starting', steps: this.steps.map((s) => ({ ...s })), port: this.meta.port, publicPort: this.meta.publicPort,
      version: this.meta.version };
  }

  /** The core spoke: the runtime is up, whatever the line says. */
  firstLine(at: number): void {
    const runtime = this.steps.find((s) => s.step === 'runtime');
    if (runtime && runtime.state === 'running') this.put({ step: 'runtime', state: 'done', ms: this.since('runtime', at) });
  }

  /** A progress line; returns the new starting state. */
  progress(line: { step: string; state: 'running' | 'done' | 'failed' }, at: number): DesktopCoreState {
    this.firstLine(at);
    if (line.state === 'running') {
      this.began.set(line.step, at);
      this.put({ step: line.step, state: 'running' });
    } else if (line.state === 'done') {
      this.put({ step: line.step, state: 'done', ms: this.began.has(line.step) ? this.since(line.step, at) : null });
    } else {
      this.put({ step: line.step, state: 'failed' });
    }
    return this.state();
  }

  get current(): readonly CoreStep[] {
    return this.steps;
  }

  private since(step: string, at: number): number {
    return Math.max(0, Math.round(at - (this.began.get(step) ?? at)));
  }

  private put(step: CoreStep): void {
    const i = this.steps.findIndex((s) => s.step === step.step);
    if (i >= 0) this.steps[i] = step;
    else this.steps.push(step);
  }
}

/** The `failed` state of a `ready:false` line, with what main adds: the exit code, the log's last lines. */
export function failedState(line: Extract<CoreLine, { kind: 'failed' }>, meta: StartMeta,
    extra: { exitCode: number | null; logTail: string | null }): DesktopCoreState {
  return {
    kind: 'failed',
    reason: line.reason,
    detail: line.detail,
    portOwner: line.portOwner,
    log: line.log,
    logTail: extra.logTail,
    exitCode: extra.exitCode,
    port: meta.port,
    publicPort: meta.publicPort,
    version: meta.version,
  };
}

/** The `ready` state the renderer gets (the pid stays in main). */
export function readyState(line: Extract<CoreLine, { kind: 'ready' }>): DesktopCoreState {
  return { kind: 'ready', firstRun: line.firstRun, locked: line.locked, version: line.version, ports: line.ports,
    runtime: line.runtime };
}
