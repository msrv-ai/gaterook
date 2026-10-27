import { isApiError } from '../../core/api/api-error';
import { MasterKeyProtects, MasterKeyStatus } from '../../core/api/secrets.api';
import { CoreStep, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { CoreState } from '../../core/stores/core.store';
import { duration } from '../../core/time/time-format';
import { TextPart } from '../../kit/elements/shared/literals';
import { StatusScreenView, StatusStep } from '../../kit/elements/status-screen/status-screen';

/**
 * The core-starting screen in words (design/pages/core-starting.html; ARCHITECTURE.md §7.1, §8.4): pure functions from
 * what Electron's main and `/api/health` / `/api/master-key` said to the status screen's view, the "Use another port"
 * checks and the toasts after a restore or a new key. Nothing here invents a fact: what nobody reported is left out.
 */

/** Where the desktop app keeps the key and the log (`-Dgateway.home=~/.mcp-gateway`, §7.1) until the core says. */
export const DEFAULT_KEY_PATH = '~/.mcp-gateway/master.keyset';
export const DEFAULT_LOG_FILE = '~/.mcp-gateway/logs/core.log';

/** The stepper of the kit, in the order the core runs them: the process, the key, the database, the local API. */
const STEPS = ['runtime', 'master-key', 'db-migrations', 'local-api'] as const;
type StepId = (typeof STEPS)[number];

const STATE: Record<CoreStep['state'], StatusStep['state']> = { running: 'current', done: 'complete', failed: 'error' };

/** What the screen needs besides the core's state. */
export interface ScreenFacts {
  /** `127.0.0.1:7465`: where the core listens or is being started. */
  readonly address: string;
  /** `GET /api/master-key` in the locked mode (path, secrets it protects, last backup); null until it answers. */
  readonly key: MasterKeyStatus | null;
  /** Approval requests waiting when the core was last seen; null when this window never saw it run. */
  readonly pending: number | null;
  /** Whether Electron reports the core's state (in a browser nothing does until `/api/health` answers). */
  readonly desktop: boolean;
}

/**
 * The core's log file: the one Electron or the core named, else beside the key file the locked core reported
 * (`<home>/master.keyset` → `<home>/logs/core.log`, §7.1 `GatewayPaths`), else the desktop app's default.
 */
export function logFileOf(named: string | null, keyPath: string | null): string {
  if (named) return named;
  if (keyPath) {
    const i = Math.max(keyPath.lastIndexOf('/'), keyPath.lastIndexOf('\\'));
    if (i > 0) return `${keyPath.slice(0, i + 1)}logs${keyPath[i]}core.log`;
  }
  return DEFAULT_LOG_FILE;
}

/** "0.4 s": the kit writes start-up steps in tenths of a second. */
export function stepTime(ms: number): string {
  if (ms >= 10_000) return duration(ms);
  return `${Math.max(0.1, Math.round(ms / 100) / 10).toFixed(1)} s`;
}

/**
 * The four steps from the progress lines (§7.1 `{"step","state"}`; main adds `runtime` and each step's time):
 * the latest line of a step decides its state. A step with no line yet is upcoming — unless a later step already
 * reported, then the core is past it. Before any line (Electron just spawned the core) the process itself runs.
 */
export function progressSteps(lines: readonly CoreStep[], address: string, keyPath: string): StatusStep[] {
  const last = new Map<string, CoreStep>();
  for (const l of lines) last.set(l.step, l);
  const reached = STEPS.reduce((n, id, i) => (last.has(id) ? i : n), -1);
  return STEPS.map((id, i) => {
    const line = last.get(id);
    let state: StatusStep['state'];
    if (line) state = STATE[line.state];
    else if (i < reached || (id === 'runtime' && reached > 0)) state = 'complete';
    else if (id === 'runtime' && reached < 0) state = 'current';
    else state = 'upcoming';
    return { id, state, ...describe(id, state, line?.ms ?? null, address, keyPath) };
  });
}

/** The line under a step, as the kit writes it for that state; nothing where the kit and the facts say nothing. */
function describe(
  id: StepId,
  state: StatusStep['state'],
  ms: number | null,
  address: string,
  keyPath: string,
): Pick<StatusStep, 'description' | 'mono'> {
  const took = ms !== null ? ` · ${stepTime(ms)}` : '';
  switch (id) {
    case 'runtime':
      // Decision 2 (§8.6.1): the kit's "Bundled JRE 25 · 0.6 s" is a native image here.
      return state === 'complete' ? { description: `Native image${took}` } : {};
    case 'master-key':
      if (state === 'complete') return { description: 'Loaded', mono: keyPath };
      if (state === 'current') return { description: 'Reading', mono: keyPath };
      return state === 'error' ? { description: 'Couldn’t read', mono: keyPath } : {};
    case 'db-migrations':
      return state === 'complete' ? { description: `Up to date${took}` } : {};
    case 'local-api':
      if (state === 'current') return { description: 'Binding', mono: address };
      if (state === 'complete') return { description: 'Listening on', mono: address };
      return {};
  }
}

/** A failure stops at the step that ran: one that was still "running" when the core exited is the failed one. */
function stopped(steps: readonly StatusStep[]): StatusStep[] {
  return steps.map((s) => (s.state === 'current' ? { ...s, state: 'error', description: null, mono: null } : s));
}

/** Titles and first sentences of the start failures the kit has no card for (`ready:false` reasons, §7.1). */
const START_FAILURES: Record<string, { readonly icon: string; readonly title: string; readonly lead: string }> = {
  DB_LOCKED: {
    icon: 'lock',
    title: 'Another core is using the database',
    lead: 'Another MCP Gateway core on this computer holds the database, so this one stopped. Quit the other MCP Gateway, then restart the core.',
  },
  DB_MIGRATION_FAILED: {
    icon: 'database',
    title: 'The database couldn’t be updated',
    lead: 'The core stopped while it migrated its database. The log names the migration that failed.',
  },
  DB_CHECK_FAILED: {
    icon: 'database',
    title: 'The database check failed',
    lead: 'The core stopped: its database didn’t pass the start-up check. The log has the details.',
  },
  BOOTSTRAP_TIMEOUT: {
    icon: 'unplug',
    title: 'The core didn’t get its settings',
    lead: 'The core stopped waiting for its start-up settings from the app. Restart the core.',
  },
  BOOTSTRAP_INVALID: {
    icon: 'unplug',
    title: 'The core didn’t accept its settings',
    lead: 'The core stopped: the start-up settings from the app didn’t fit this core. Restart the core; if it happens again, install MCP Gateway again.',
  },
  READY_TIMEOUT: {
    icon: 'hourglass',
    title: 'The core didn’t start in time',
    lead: 'The core didn’t say it was ready within a minute, so the app stopped it.',
  },
};

const START_FAILED = {
  icon: 'circle-x',
  title: 'Core failed to start',
  lead: 'The core stopped before its local API was ready. The log has the cause.',
};

/** "a", "a and b", "a, b and c". */
function andWords(words: readonly string[]): string {
  return words.length < 2 ? (words[0] ?? '') : `${words.slice(0, -1).join(', ')} and ${words.at(-1)}`;
}

/** `vault`, `gcp-sm`, `aws-sm` (or a store's name, `gcp-acme-prod`) as the kit names them: Vault, GCP, AWS. */
function storeWord(source: string): string {
  const s = source.toLowerCase();
  if (s.startsWith('vault')) return 'Vault';
  if (s.startsWith('gcp')) return 'GCP';
  if (s.startsWith('aws')) return 'AWS';
  return source;
}

/**
 * Whose the secrets encrypted here are, as the kit says it on the locked screen: "6 connections, the github-mcp server
 * token and the Vault sign-in" — owners counted per kind, a single MCP server or store named.
 */
export function protectsWords(by: readonly MasterKeyProtects[]): TextPart[] {
  const t = (text: string): TextPart => ({ text, code: false });
  const c = (text: string): TextPart => ({ text, code: true });
  const n = (k: number, one: string, many: string) => `${k} ${k === 1 ? one : many}`;
  const items: TextPart[][] = [];
  for (const p of by) {
    const k = p.owners.length;
    switch (p.kind) {
      case 'connection':
        items.push([t(n(k, 'connection', 'connections'))]);
        break;
      case 'mcp-server':
        items.push(k === 1 ? [t('the '), c(p.owners[0]), t(' server token')] : [t(n(k, 'MCP server token', 'MCP server tokens'))]);
        break;
      case 'store':
        items.push([t(k === 1 ? `the ${storeWord(p.owners[0])} sign-in` : n(k, 'secret store sign-in', 'secret store sign-ins'))]);
        break;
      case 'notification':
        items.push([t(k === 1 ? 'the notification token' : n(k, 'notification token', 'notification tokens'))]);
        break;
      case 'tunnel':
        items.push([t('the tunnel token')]);
        break;
      case 'audit-export':
        items.push([t('the audit export credentials')]);
        break;
      default:
        items.push([t(n(p.secrets, `${p.kind} secret`, `${p.kind} secrets`))]);
    }
  }
  const out: TextPart[] = [];
  items.forEach((parts, i) => {
    if (i > 0) out.push(t(i === items.length - 1 ? ' and ' : ', '));
    out.push(...parts);
  });
  return out;
}

/** The status screen for the core's state: one of the kit's cards, filled with what is known. */
export function statusView(state: CoreState, facts: ScreenFacts): StatusScreenView {
  const keyPath = facts.key?.path ?? DEFAULT_KEY_PATH;
  switch (state.kind) {
    case 'connecting':
      // In Electron the core has just been spawned; a browser knows nothing until /api/health answers.
      return { kind: 'starting', steps: facts.desktop ? progressSteps([], facts.address, keyPath) : [] };
    case 'starting':
      return { kind: 'starting', steps: progressSteps(state.steps, facts.address, keyPath) };
    case 'locked': {
      const reason = state.reason === 'MASTER_KEY_INVALID' ? 'MASTER_KEY_INVALID' : 'MASTER_KEY_MISSING';
      // The core is up and locked (§7.1 case b): it migrated the database and listens, but holds no key that opens
      // the stored secrets. So the key is the failed step, and the steps after it are done — not "not started".
      return {
        kind: 'locked',
        reason,
        keyPath,
        secrets: facts.key ? facts.key.protects : null,
        // The locked core answers only /api/master-key; since step API it says whose the secrets are and how many
        // references keep working.
        references: facts.key?.references?.count ?? null,
        lastBackupAt: facts.key?.lastBackupAt ?? null,
        breakdown: facts.key?.protectsBy?.length ? protectsWords(facts.key.protectsBy) : null,
        referenceTo: facts.key?.references?.sources.length ? andWords(facts.key.references.sources.map(storeWord)) : null,
        steps: [
          { id: 'runtime', state: 'complete' },
          {
            id: 'master-key',
            state: 'error',
            description: reason === 'MASTER_KEY_INVALID' ? 'Can’t open the stored secrets with' : 'No file at',
            mono: keyPath,
          },
          { id: 'db-migrations', state: 'complete' },
          { id: 'local-api', state: 'complete' },
        ],
      };
    }
    case 'failed': {
      // A ready:false line means the process ran and spoke: the runtime step is done whatever main reported last.
      // Only "no ready line in time" may have failed there.
      const lines =
        state.reason === 'READY_TIMEOUT'
          ? state.steps
          : [...state.steps.filter((l) => l.step !== 'runtime'), { step: 'runtime', state: 'done' as const, ms: state.steps.find((l) => l.step === 'runtime')?.ms ?? null }];
      const steps = stopped(progressSteps(lines, facts.address, keyPath));
      if (state.reason === 'PORT_IN_USE') {
        // The failure line names the port that is taken ("Address already in use: 127.0.0.1:7466",
        // platform.boot.StartupFailures): the core binds the tunnel's public listener at start too.
        const taken = takenPort(state.detail);
        const publicListener = taken !== null && taken === state.publicPort && taken !== portOf(facts.address);
        const where = publicListener ? `127.0.0.1:${taken}` : facts.address;
        return {
          kind: 'port-in-use',
          portOwner: state.portOwner,
          takenPort: publicListener ? taken : null,
          publicListener,
          // The core got as far as binding the port: everything before it is done.
          steps: steps.map((s) =>
            s.id === 'local-api'
              ? { id: s.id, state: 'error', description: 'Address already in use:', mono: where }
              : { id: s.id, state: 'complete' },
          ),
        };
      }
      const words = START_FAILURES[state.reason ?? ''] ?? START_FAILED;
      // The steps tell where it stopped only when the core got as far as one (a second core stops at the database
      // lock before any step: then the facts below say it all).
      const progressed = state.steps.some((l) => l.step !== 'runtime') || state.reason === 'READY_TIMEOUT';
      return {
        kind: 'start-failed',
        ...words,
        steps: progressed ? steps : [],
        exitCode: state.exitCode,
        reason: state.detail ? `${state.reason}: ${state.detail}` : state.reason || 'STARTUP_FAILED',
        logLines: state.logTail,
      };
    }
    case 'stopped':
      return {
        kind: 'stopped',
        at: state.exitedAt,
        exitCode: state.exitCode,
        reason: state.reason,
        uptimeMs: state.uptimeMs,
        logLines: state.logTail,
        pending: facts.pending,
      };
    case 'unreachable':
    case 'running':
      return { kind: 'unreachable', detail: state.detail };
  }
}

// ── Use another port ──────────────────────────────────────────────────────────────────────────────────────────────

export const PORT_MIN = 1024;
export const PORT_MAX = 65535;

/** The port of `127.0.0.1:7465`. */
export function portOf(address: string): number {
  return Number(address.split(':').at(-1));
}

/** The port a `PORT_IN_USE` line names ("Address already in use: 127.0.0.1:7466"), or null. */
export function takenPort(detail: string | null | undefined): number | null {
  const m = /127\.0\.0\.1:(\d{1,5})\b/.exec(detail ?? '');
  return m ? Number(m[1]) : null;
}

/**
 * Why a typed port can't be used, or null: a whole number from 1024 to 65535, not the one that is taken now and not
 * the public listener's (§7.1: the tunnel's port ≠ the local API's). Main checks again that it is free.
 */
export function portError(text: string, current: number, publicPort: number | null): string | null {
  const t = text.trim();
  if (!t) return 'Enter a port from 1024 to 65535.';
  if (!/^\d{1,5}$/.test(t)) return 'Use digits only: a port from 1024 to 65535.';
  const port = Number(t);
  if (port < PORT_MIN || port > PORT_MAX) return `${port} is outside 1024 to 65535. Use a port in that range.`;
  if (port === current) return `${port} is the port that is in use. Choose another one.`;
  if (publicPort !== null && port === publicPort) return `${port} is the tunnel’s port. Choose another one.`;
  return null;
}

/** Where to look for a free port first: ten up from the taken one, past the tunnel's (ARCHITECTURE §8.6.9: 7475). */
export function firstCandidate(current: number, publicPort: number | null): number {
  let p = current + 10;
  if (p > PORT_MAX) p = PORT_MIN + ((p - PORT_MIN) % (PORT_MAX - PORT_MIN));
  if (p === publicPort) p += 1;
  return p;
}

/** The endpoint URLs on the new port, the kit's way: the first in full, the others by path. */
export function endpointUrls(port: number, slugs: readonly string[]): { readonly first: string; readonly rest: readonly string[] } | null {
  if (!slugs.length) return null;
  return { first: `http://127.0.0.1:${port}/mcp/${slugs[0]}`, rest: slugs.slice(1).map((s) => `/mcp/${s}`) };
}

/** What main said when it refused (an Error over IPC, prefixed by Electron), or `fallback`. */
export function desktopError(e: unknown, fallback: string): string {
  return desktopErrorText(e, fallback);
}

/** Main refused the port (taken just now, reserved): for the field. */
export function usePortError(e: unknown): string {
  return desktopError(e, 'The app couldn’t start the core on this port. Choose another one.');
}

// ── Restore and a new key ─────────────────────────────────────────────────────────────────────────────────────────

export interface ToastText {
  readonly tone: 'success' | 'warning' | 'danger';
  readonly title: string;
  readonly body: string;
}

function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** The kit's lists: "a, b and c". */
function list(items: readonly string[]): string {
  return items.length < 2 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} and ${items.at(-1)}`;
}

/** After Restore: the core is unlocked without a restart (§7.1). */
export function restoredToast(secretsReadable: number): ToastText {
  return {
    tone: 'success',
    title: 'Master key restored',
    body: secretsReadable > 0 ? `The ${plural(secretsReadable, 'secret')} stored here can be read again.` : 'The core is unlocked.',
  };
}

/** After Create a new key: which secrets lost their value (the core destroyed them, §6 `{needValue[]}`). */
export function createdToast(needValue: readonly string[]): ToastText {
  if (!needValue.length) return { tone: 'success', title: 'New master key created', body: 'No stored secret needed the old key.' };
  const shown = needValue.length <= 3 ? list(needValue) : `${needValue.slice(0, 2).join(', ')} and ${needValue.length - 2} more`;
  return {
    tone: 'warning',
    title: 'New master key created',
    body: `${plural(needValue.length, 'secret needs', 'secrets need')} a value again: ${shown}. Enter each one on Secrets.`,
  };
}

/** A failed restore, worded for the dialog; `passphrase` when the passphrase field is the thing to fix. */
export function restoreProblem(e: unknown): { readonly message: string; readonly passphrase: boolean; readonly unlocked: boolean } {
  if (!isApiError(e)) return { message: 'The core didn’t answer. Try again.', passphrase: false, unlocked: false };
  if (e.kind === 'network') return { message: 'The core didn’t answer. Try again.', passphrase: false, unlocked: false };
  // 409 "The master key works": the core is no longer locked (restored meanwhile).
  if (e.status === 409) return { message: e.detail, passphrase: false, unlocked: true };
  return { message: e.detail || e.title, passphrase: /passphrase/i.test(e.detail), unlocked: false };
}

/** A failed new key, for the card's notice. */
export function createProblem(e: unknown): { readonly title: string; readonly body: string } {
  return {
    title: 'No new key was created',
    body: isApiError(e) && e.kind !== 'network' ? e.detail || e.title : 'The core didn’t answer. Try again.',
  };
}
