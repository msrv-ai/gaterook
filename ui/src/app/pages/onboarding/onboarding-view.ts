import { ClientCard, ClientsView } from '../../core/api/clients.api';
import { GettingStartedStep, Onboarding, SetupNode, SetupNodeId } from '../../core/api/overview.api';
import { MasterKeyStatus } from '../../core/api/secrets.api';
import { JsonValue } from '../../core/api/types';
import { MetaPart, joinNames } from '../../kit/elements/getting-started/getting-started';
import { clientName } from '../../kit/elements/shared/client-mark';

/**
 * What Welcome shows, as pure functions of the core's answers (design/pages/onboarding.html; ARCHITECTURE.md §6
 * onboarding, §8.4): the four steps of the stepper and their state, the step card of each, the setup map in the
 * order of the steps, and the words of the toasts and announcements. No Angular here: the page and its spec share it.
 */

/** The four steps of the first run, in order; getting started (Overview) continues with Playground and notifications. */
export type StepId = 'master-key' | 'connection' | 'endpoint' | 'client';
export const STEP_IDS: readonly StepId[] = ['master-key', 'connection', 'endpoint', 'client'];

/** The kit's stepper copy (onboarding.html `#ob-stepper`). */
export const STEP_COPY: Record<StepId, { readonly label: string; readonly description: string }> = {
  'master-key': { label: 'Protect master key', description: 'Back it up off this computer' },
  connection: { label: 'Add a connection', description: 'A database, server, log store or API' },
  endpoint: { label: 'Create an endpoint', description: 'One MCP URL with the tools you choose' },
  client: { label: 'Connect your AI app', description: 'Claude Desktop, Codex, Cursor and more' },
};

/** What the page knows: the onboarding answer, plus the master key and the clients when the core answered. */
export interface SetupFacts {
  readonly onboarding: Onboarding;
  /** `GET /api/master-key`; null when it didn't answer (the step then goes by getting started alone). */
  readonly key: MasterKeyStatus | null;
  /** `GET /api/clients` (K1); null when the core has no clients area or it didn't answer. */
  readonly clients: ClientsView | null;
}

export type StepState = 'complete' | 'current' | 'upcoming';

/** One step of the stepper as the kit draws it: marker, label, the line under it. */
export interface StepperStep {
  readonly id: StepId;
  readonly index: number;
  readonly label: string;
  readonly state: StepState;
  readonly description: readonly MetaPart[];
}

const str = (v: JsonValue | undefined): string | null => (typeof v === 'string' && v ? v : null);
const num = (v: JsonValue | undefined): number | null => (typeof v === 'number' ? v : null);
const list = (v: JsonValue | undefined): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);

/** "1 connection", "3 connections". */
export function plural(n: number, word: string, many = `${word}s`): string {
  return `${n.toLocaleString('en-US')} ${n === 1 ? word : many}`;
}

function gsStep(o: Onboarding, id: StepId): GettingStartedStep | null {
  return o.gettingStarted.steps.find((s) => s.id === id) ?? null;
}

function node(o: Onboarding, id: SetupNodeId): SetupNode | null {
  return o.setupMap.nodes.find((n) => n.id === id) ?? null;
}

/**
 * Whether the current master key is in a backup: getting started says a backup exists, and the key status (when it
 * answered) doesn't say the current key is missing from it (a rotation since the last backup brings `backup-needed`
 * back, and old copies don't open the new key).
 */
export function backedUp(f: SetupFacts): boolean {
  const done = gsStep(f.onboarding, 'master-key')?.state === 'done';
  return done && !(f.key?.problems.includes('backup-needed') ?? false);
}

/** Which of the four steps are done, from the core's facts. */
export function stepsDone(f: SetupFacts): Record<StepId, boolean> {
  return {
    'master-key': backedUp(f),
    connection: gsStep(f.onboarding, 'connection')?.state === 'done',
    endpoint: gsStep(f.onboarding, 'endpoint')?.state === 'done',
    client: gsStep(f.onboarding, 'client')?.state === 'done',
  };
}

/**
 * The step Welcome opens on: the first one neither done nor put off ("I'll do it later"); when nothing is left, the
 * last one, which then leads to Overview.
 */
export function landingStep(done: Record<StepId, boolean>, later: ReadonlySet<StepId>): number {
  const i = STEP_IDS.findIndex((id) => !done[id] && !later.has(id));
  return i >= 0 ? i : STEP_IDS.length - 1;
}

/** Every step is done: setup is complete and Skip setup has nothing left to skip. */
export function allDone(done: Record<StepId, boolean>): boolean {
  return STEP_IDS.every((id) => done[id]);
}

/** When the master key was last backed up: the key status, else getting started's fact. */
export function lastBackupAt(f: SetupFacts): string | null {
  return f.key?.lastBackupAt ?? str(gsStep(f.onboarding, 'master-key')?.detail['lastBackupAt']);
}

/**
 * The stepper (kit render(): the viewed step is `current`, a done one `complete`, the rest `upcoming`); the line under
 * each label says what was done ("Backed up just now", "`dev-db` · PostgreSQL"), that the backup was put off, or the
 * kit's hint of what the step is.
 */
export function stepperSteps(
  f: SetupFacts,
  viewing: number,
  later: ReadonlySet<StepId>,
  relative: (at: string) => string,
): StepperStep[] {
  const done = stepsDone(f);
  return STEP_IDS.map((id, index) => ({
    id,
    index,
    label: STEP_COPY[id].label,
    state: index === viewing ? 'current' : done[id] ? 'complete' : 'upcoming',
    description: stepLine(f, id, done[id], later.has(id), relative),
  }));
}

function stepLine(f: SetupFacts, id: StepId, done: boolean, putOff: boolean, relative: (at: string) => string): MetaPart[] {
  const fallback = [{ text: STEP_COPY[id].description }];
  const d = gsStep(f.onboarding, id)?.detail ?? {};
  switch (id) {
    case 'master-key': {
      if (done) {
        const at = lastBackupAt(f);
        return [{ text: at ? `Backed up ${relative(at)}` : 'Backed up' }];
      }
      return putOff ? [{ text: 'Skipped · no backup yet' }] : fallback;
    }
    case 'connection': {
      const name = str(d['name']);
      if (!done || !name) return fallback;
      const count = num(d['count']) ?? 1;
      const type = str(d['typeName']);
      if (count > 1) return [{ text: name, mono: true }, { text: ` and ${(count - 1).toLocaleString('en-US')} more` }];
      return [{ text: name, mono: true }, ...(type ? [{ text: ` · ${type}` }] : [])];
    }
    case 'endpoint': {
      const name = str(d['name']);
      if (!done || !name) return fallback;
      const tools = num(d['tools']);
      return [{ text: name, mono: true }, ...(tools !== null ? [{ text: ` · ${plural(tools, 'tool')}` }] : [])];
    }
    case 'client': {
      if (!done) return fallback;
      const names = connectedClients(f);
      if (names.length) return [{ text: names.join(', ') }];
      const status = str(d['status']);
      return status ? [{ text: status }] : [{ text: 'Connected' }];
    }
  }
}

// ── Clients ──────────────────────────────────────────────────────────────────────────────────────────────────────

/** A client found on this computer (K1 detection), whether or not it is connected yet. */
export function installed(c: ClientCard): boolean {
  return c.detected || c.status !== 'not-installed';
}

/**
 * Names of the clients that use GateRook: K1's connected cards; else the clients getting started counted (the
 * client ids its grants name), which also covers a core whose clients area didn't answer.
 */
export function connectedClients(f: SetupFacts): string[] {
  const cards = f.clients?.clients.filter((c) => c.status === 'connected' || c.links.length > 0).map((c) => c.name) ?? [];
  return cards.length ? cards : list(gsStep(f.onboarding, 'client')?.detail['clients']).map(clientName);
}

/** Names of the clients found here and not connected yet. */
export function detectedClients(f: SetupFacts): string[] {
  if (!f.clients) return [];
  return f.clients.clients.filter((c) => installed(c) && c.status !== 'connected' && c.links.length === 0).map((c) => c.name);
}

/**
 * The step card's description (kit: "Claude Desktop, Codex and Cursor are installed on this computer."): the clients
 * K1 found; before K1 answers, the clients GateRook can connect.
 */
export function clientsDescription(f: SetupFacts): string {
  if (!f.clients) return 'Claude Desktop, Codex, Cursor and Antigravity can connect.';
  const names = f.clients.clients.filter(installed).map((c) => c.name);
  if (!names.length) return 'No AI app was found on this computer yet. Install one, then connect it here.';
  return `${joinNames(names)} ${names.length === 1 ? 'is' : 'are'} installed on this computer.`;
}

// ── Setup map ────────────────────────────────────────────────────────────────────────────────────────────────────

/** The master key's problem codes in words (`GET /api/master-key` `problems`). */
const KEY_PROBLEMS: Record<string, string> = {
  perms: 'other users can read the key file',
  'synced-folder': 'key file in a synced folder',
  'no-os-protection': 'no OS protection',
  'key-missing': 'master key missing',
  'key-invalid': "master key doesn't open the secrets",
};

/**
 * The setup map of Welcome (kit `ob-map`): the core's five nodes in setup order, each status line saying which step
 * fills it in ("Step 1 · master key not backed up", "Step 2 · none yet", "Step 4 · 3 detected"). Policies is no step:
 * safe defaults are on from the start, so it reads "Safe defaults on · review any time" while nothing was changed
 * outside the app. The secrets node needs attention until the key is backed up, as the kit draws the first run.
 */
export function setupNodes(f: SetupFacts): SetupNode[] {
  const o = f.onboarding;
  const out: SetupNode[] = [];
  for (const n of o.setupMap.nodes) {
    switch (n.id) {
      case 'secrets':
        out.push(secretsNode(f, n));
        break;
      case 'connections':
        out.push(countedNode(n, 2, 'connection'));
        break;
      case 'policies':
        out.push({ ...n, status: n.state === 'done' ? 'Safe defaults on · review any time' : n.status });
        break;
      case 'endpoints':
        out.push(countedNode(n, 3, 'endpoint'));
        break;
      case 'clients':
        out.push({ ...n, status: `Step 4 · ${clientsStatus(f, n)}` });
        break;
      default:
        out.push(n);
    }
  }
  return out;
}

function secretsNode(f: SetupFacts, n: SetupNode): SetupNode {
  const safe = backedUp(f);
  const problems = (f.key?.problems ?? []).filter((p) => p !== 'backup-needed' && p in KEY_PROBLEMS).map((p) => KEY_PROBLEMS[p]);
  if (!safe) return { ...n, state: 'attention', status: ['Step 1 · master key not backed up', ...problems].join(', ') };
  const secrets = n.count === 0 ? 'master key backed up, no secrets yet' : `master key backed up · ${plural(n.count, 'secret')}`;
  if (problems.length) return { ...n, state: 'attention', status: `Step 1 · ${secrets} · ${problems.join(', ')}` };
  // Without the key status, a problem the core reports stands (the page couldn't read which one).
  return { ...n, state: f.key || n.state !== 'attention' ? 'done' : 'attention', status: `Step 1 · ${secrets}` };
}

/** "Step 2 · none yet", "Step 2 · 3 connections", "Step 2 · 1 needs attention" (the core's attention line). */
function countedNode(n: SetupNode, step: number, word: string): SetupNode {
  if (n.count === 0) return { ...n, state: n.state === 'attention' ? 'attention' : 'todo', status: `Step ${step} · none yet` };
  const status = n.state === 'attention' ? n.status.replace(/^1 need attention$/, '1 needs attention') : plural(n.count, word);
  return { ...n, status: `Step ${step} · ${status}` };
}

/** "3 detected", "1 connected · 2 detected", "none found"; before K1 answers, what the core says or "none yet". */
function clientsStatus(f: SetupFacts, n: SetupNode): string {
  if (!f.clients) return n.status ? n.status.replace(/^1 named tokens$/, '1 named token') : 'none yet';
  const connected = f.clients.clients.filter((c) => c.status === 'connected' || c.links.length > 0).length;
  const detected = detectedClients(f).length;
  const parts = [...(connected ? [`${connected} connected`] : []), ...(detected ? [`${detected} detected`] : [])];
  return parts.length ? parts.join(' · ') : 'none found';
}

/** The setup map's description for screen readers, tied to the steps (kit `#ob-map-flow`). */
export const MAP_FLOW =
  'Set it up top to bottom: step 1 protects secrets and resources, step 2 adds a connection that uses them, policies ' +
  'already have safe defaults, step 3 publishes tools on an endpoint and step 4 connects an AI app to it. An agent\'s ' +
  'call travels the other way: AI app, endpoint (with a token or OAuth), policy (mode, limits, approval), connection, ' +
  'where the secret is injected. The agent never receives the secret.';

// ── Step cards ───────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The key file's permissions as the kit says them ("`0400` · only your user can read it"). A file others can read is
 * the core's `perms` problem; a system without POSIX permissions has no mode.
 */
export function permissionsText(key: MasterKeyStatus): { readonly mode: string | null; readonly text: string; readonly wrong: boolean } {
  const wrong = key.problems.includes('perms');
  return { mode: key.mode, text: wrong ? 'other users can read it' : 'only your user can read it', wrong };
}

/** The key file's format, and what seals it when the OS protects it (`osProtection`). */
export function formatText(key: MasterKeyStatus): string {
  const by: Record<string, string> = { keychain: 'the macOS Keychain', dpapi: 'Windows DPAPI', libsecret: 'the Secret Service', kwallet: 'KWallet' };
  const seal = by[key.osProtection];
  return seal ? `Google Tink keyset, sealed with a key from ${seal}` : 'Google Tink keyset';
}

/** What a done step card says is set up, with its literal in mono. */
export function doneLine(f: SetupFacts, id: StepId): MetaPart[] {
  const o = f.onboarding;
  const d = gsStep(o, id)?.detail ?? {};
  switch (id) {
    case 'master-key':
      return [];
    case 'connection': {
      const name = str(d['name']);
      const count = num(d['count']) ?? node(o, 'connections')?.count ?? 1;
      if (!name) return [{ text: `${plural(count, 'connection')} ${count === 1 ? 'is' : 'are'} set up.` }];
      return count > 1
        ? [{ text: `${plural(count, 'connection')} are set up, including ` }, { text: name, mono: true }, { text: '.' }]
        : [{ text: 'Connection ' }, { text: name, mono: true }, { text: ' is set up.' }];
    }
    case 'endpoint': {
      const name = str(d['name']);
      const count = node(o, 'endpoints')?.count ?? 1;
      if (!name) return [{ text: `${plural(count, 'endpoint')} ${count === 1 ? 'is' : 'are'} set up.` }];
      if (count > 1) return [{ text: `${plural(count, 'endpoint')} are set up, including ` }, { text: name, mono: true }, { text: '.' }];
      const tools = num(d['tools']);
      return [{ text: 'Endpoint ' }, { text: name, mono: true }, { text: tools !== null ? ` is set up with ${plural(tools, 'tool')}.` : ' is set up.' }];
    }
    case 'client': {
      const names = connectedClients(f);
      return [{ text: names.length ? `${joinNames(names)} ${names.length === 1 ? 'uses' : 'use'} GateRook.` : 'An AI app uses GateRook.' }];
    }
  }
}

/** "Step 2 of 4: Add a connection.", after what just happened ("Master key backed up."). */
export function stepAnnouncement(index: number, before?: string): string {
  return `${before ? `${before} ` : ''}Step ${index + 1} of ${STEP_IDS.length}: ${STEP_COPY[STEP_IDS[index]].label}.`;
}

export interface ToastText {
  readonly tone: 'success' | 'warning';
  readonly title: string;
  readonly body: string;
}

/**
 * The toast after a backup (kit: it stays, names the full path of the copy and says to delete it from this computer
 * once stored elsewhere, as in Secrets); a folder that syncs to other devices is a warning.
 */
export function backedUpToast(result: { readonly path: string; readonly passphrase: boolean; readonly warnings: readonly string[] }): ToastText {
  const synced = result.warnings.includes('synced-folder');
  return {
    tone: synced ? 'warning' : 'success',
    title: `Master key backed up${result.passphrase ? ', encrypted with your passphrase' : ''}`,
    body:
      `Saved to ${result.path}. ` +
      (synced
        ? 'That folder syncs to other devices: move the copy to your password manager or an encrypted drive, then delete it there.'
        : 'Move it to your password manager or an encrypted drive, then delete it from this computer.'),
  };
}

/** The file name the busy line names (kit: "the copy of `master.keyset`"). */
export function keyFileName(path: string | null | undefined): string {
  if (!path) return 'master.keyset';
  const i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  return i >= 0 ? path.slice(i + 1) : path;
}
