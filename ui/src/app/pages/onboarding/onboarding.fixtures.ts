import { ClientCard, ClientsView } from '../../core/api/clients.api';
import { GettingStartedStepId, Onboarding } from '../../core/api/overview.api';
import { MasterKeyStatus } from '../../core/api/secrets.api';
import { JsonValue } from '../../core/api/types';

/**
 * Welcome's world for tests: the first run of design/pages/onboarding.html as the core answers it (an empty gateway,
 * a key the core just created, Claude Desktop, Codex and Cursor found on this computer). Tests only.
 */

export const NOW = Date.parse('2026-10-22T12:30:00Z');

const STEP_IDS: GettingStartedStepId[] = ['master-key', 'connection', 'endpoint', 'client', 'playground', 'notifications'];

/** `GET /api/onboarding` of a first start, with some steps done (`done`: id → detail). */
export function onboarding(done: Partial<Record<GettingStartedStepId, { readonly [key: string]: JsonValue }>> = {}): Onboarding {
  let current = false;
  const steps = STEP_IDS.map((id) => {
    if (done[id]) return { id, state: 'done' as const, detail: done[id] };
    const state = current ? ('todo' as const) : ('current' as const);
    current = true;
    return { id, state, detail: {} };
  });
  const count = (id: GettingStartedStepId) => (done[id] ? 1 : 0);
  return {
    firstRun: true,
    skipped: false,
    gettingStarted: { done: Object.keys(done).length, total: 6, dismissed: false, steps },
    setupMap: {
      nodes: [
        done['master-key']
          ? { id: 'secrets', count: 0, state: 'todo', status: '0 encrypted here · 0 references' }
          : { id: 'secrets', count: 0, state: 'attention', status: 'Master key: backup-needed' },
        { id: 'connections', count: count('connection'), state: done.connection ? 'done' : 'todo', status: `${count('connection')} connections` },
        { id: 'policies', count: 3, state: 'done', status: 'dev, stage, prod' },
        { id: 'endpoints', count: count('endpoint'), state: done.endpoint ? 'done' : 'todo', status: `${count('endpoint')} endpoints` },
        { id: 'clients', count: count('client'), state: done.client ? 'done' : 'todo', status: done.client ? 'claude-desktop' : null as unknown as string },
      ],
    },
  };
}

/** `GET /api/master-key` right after the core created the key (0400, protected by the macOS Keychain). */
export function masterKey(patch: Partial<MasterKeyStatus> = {}): MasterKeyStatus {
  return {
    path: '/Users/you/.mcp-gateway/master.keyset',
    mode: '0400',
    owner: 'you',
    keys: [{ id: 3855850642, status: 'enabled', primary: true }],
    osProtection: 'keychain',
    protects: 0,
    lastBackupAt: null,
    problems: ['backup-needed'],
    warnings: [],
    locked: null,
    rotation: { state: 'idle', done: 0, total: 0, targetKeyId: null, error: null },
    ...patch,
  };
}

/** The key after a backup a moment ago. */
export function backedUpKey(at = new Date(NOW - 1_000).toISOString()): MasterKeyStatus {
  return masterKey({ lastBackupAt: at, problems: [] });
}

function card(client: ClientCard['client'], name: string, status: ClientCard['status'], links: ClientCard['links'] = []): ClientCard {
  return {
    client,
    name,
    status,
    statusText: status,
    configPath: null,
    configDisplay: null,
    detected: status !== 'not-installed',
    evidence: [],
    version: null,
    warnings: [],
    methods: [],
    links,
  };
}

/** `GET /api/clients`: Claude Desktop, Codex and Cursor installed (kit: "3 detected"), Antigravity not. */
export function clients(connected: readonly ClientCard['client'][] = []): ClientsView {
  const status = (id: ClientCard['client']): ClientCard['status'] => (connected.includes(id) ? 'connected' : 'detected');
  return {
    scannedAt: new Date(NOW).toISOString(),
    clients: [
      card('claude-desktop', 'Claude Desktop', status('claude-desktop')),
      card('codex', 'Codex', status('codex')),
      card('cursor', 'Cursor', status('cursor')),
      card('antigravity', 'Antigravity', 'not-installed'),
    ],
  };
}
