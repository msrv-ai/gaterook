import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '../../core/api/api-error';
import { MasterKeyStatus } from '../../core/api/secrets.api';
import { CoreStep } from '../../core/desktop/desktop-bridge';
import { CoreState } from '../../core/stores/core.store';
import {
  DEFAULT_KEY_PATH,
  ScreenFacts,
  createProblem,
  createdToast,
  endpointUrls,
  firstCandidate,
  logFileOf,
  portError,
  portOf,
  progressSteps,
  protectsWords,
  restoreProblem,
  restoredToast,
  statusView,
  stepTime,
  takenPort,
  usePortError,
} from './core-starting-view';

/**
 * The core-starting screen's words against the kit (design/pages/core-starting.html) and the core's real protocol
 * (ARCHITECTURE.md §7.1): which card, which steps in which state, and what the dialogs check.
 */

const ADDRESS = '127.0.0.1:7465';
const KEY = '/home/alex/.mcp-gateway/master.keyset';

const state = (p: Partial<CoreState>): CoreState => ({
  kind: 'starting',
  steps: [],
  reason: null,
  detail: null,
  portOwner: null,
  exitCode: null,
  uptimeMs: null,
  exitedAt: null,
  logFile: null,
  logTail: null,
  port: 7465,
  publicPort: 7466,
  version: '0.4.0',
  firstRun: false,
  ...p,
});

const facts = (p: Partial<ScreenFacts> = {}): ScreenFacts => ({ address: ADDRESS, key: null, pending: null, desktop: true, ...p });

const masterKey = (p: Partial<MasterKeyStatus> = {}): MasterKeyStatus => ({
  path: KEY,
  mode: '0400',
  owner: 'alex',
  keys: [],
  osProtection: 'keychain',
  protects: 8,
  lastBackupAt: '2026-10-10T14:30:00+02:00',
  problems: ['key-missing'],
  warnings: [],
  locked: 'MASTER_KEY_MISSING',
  rotation: { state: 'idle', done: 0, total: 0, targetKeyId: null, error: null },
  ...p,
});

/** The kit's live state: three steps done, Local API binding (pages/core-starting.html). */
const KIT_LIVE: CoreStep[] = [
  { step: 'runtime', state: 'done', ms: 140 },
  { step: 'master-key', state: 'running' },
  { step: 'master-key', state: 'done', ms: 12 },
  { step: 'db-migrations', state: 'running' },
  { step: 'db-migrations', state: 'done', ms: 412 },
  { step: 'local-api', state: 'running' },
];

describe('start-up steps from the progress lines', () => {
  it('reads the kit’s live state: three done with their lines, Local API binding the address', () => {
    expect(progressSteps(KIT_LIVE, ADDRESS, '~/.mcp-gateway/master.keyset')).toEqual([
      { id: 'runtime', state: 'complete', description: 'Native image · 0.1 s' },
      { id: 'master-key', state: 'complete', description: 'Loaded', mono: '~/.mcp-gateway/master.keyset' },
      { id: 'db-migrations', state: 'complete', description: 'Up to date · 0.4 s' },
      { id: 'local-api', state: 'current', description: 'Binding', mono: ADDRESS },
    ]);
  });

  it('before any line the process itself is starting; the rest has not started', () => {
    expect(progressSteps([], ADDRESS, KEY).map((s) => s.state)).toEqual(['current', 'upcoming', 'upcoming', 'upcoming']);
  });

  it('a later step that reported means the earlier ones are done, even without their lines', () => {
    const steps = progressSteps([{ step: 'db-migrations', state: 'running' }], ADDRESS, KEY);
    expect(steps.map((s) => [s.id, s.state])).toEqual([
      ['runtime', 'complete'],
      ['master-key', 'complete'],
      ['db-migrations', 'current'],
      ['local-api', 'upcoming'],
    ]);
  });

  it('a failed line is the failed step; the local API done listens on the address', () => {
    expect(progressSteps([{ step: 'db-migrations', state: 'failed' }], ADDRESS, KEY)[2].state).toBe('error');
    expect(progressSteps([{ step: 'local-api', state: 'done' }], ADDRESS, KEY)[3]).toEqual({ id: 'local-api', state: 'complete', description: 'Listening on', mono: ADDRESS });
  });

  it('writes step times in tenths of a second, as the kit', () => {
    expect(stepTime(412)).toBe('0.4 s');
    expect(stepTime(30)).toBe('0.1 s');
    expect(stepTime(2_400)).toBe('2.4 s');
    expect(stepTime(13_505)).toBe('14 s');
  });
});

describe('the card for each state of the core', () => {
  it('connecting: in Electron the process just started; a browser knows nothing yet', () => {
    const desktop = statusView(state({ kind: 'connecting' }), facts());
    expect(desktop).toEqual({ kind: 'starting', steps: expect.any(Array) });
    expect(desktop.kind === 'starting' && desktop.steps[0].state).toBe('current');
    expect(statusView(state({ kind: 'connecting' }), facts({ desktop: false }))).toEqual({ kind: 'starting', steps: [] });
  });

  it('starting: the steps of the progress lines', () => {
    const v = statusView(state({ steps: KIT_LIVE }), facts());
    expect(v.kind).toBe('starting');
    expect(v.kind === 'starting' && v.steps.map((s) => s.state)).toEqual(['complete', 'complete', 'complete', 'current']);
  });

  it('locked (key missing): the key from /api/master-key; the core is up, so the steps after the key are done', () => {
    const v = statusView(state({ kind: 'locked', reason: 'MASTER_KEY_MISSING' }), facts({ key: masterKey() }));
    expect(v).toEqual({
      kind: 'locked',
      reason: 'MASTER_KEY_MISSING',
      keyPath: KEY,
      secrets: 8,
      references: null,
      lastBackupAt: '2026-10-10T14:30:00+02:00',
      breakdown: null,
      referenceTo: null,
      steps: [
        { id: 'runtime', state: 'complete' },
        { id: 'master-key', state: 'error', description: 'No file at', mono: KEY },
        { id: 'db-migrations', state: 'complete' },
        { id: 'local-api', state: 'complete' },
      ],
    });
  });

  it("locked: whose the secrets are and where the references point, when the core says (step API)", () => {
    const v = statusView(
      state({ kind: 'locked', reason: 'MASTER_KEY_MISSING' }),
      facts({
        key: masterKey({
          protectsBy: [
            { kind: 'connection', secrets: 6, owners: ['a', 'b', 'c', 'd', 'e', 'f'] },
            { kind: 'mcp-server', secrets: 1, owners: ['github-mcp'] },
            { kind: 'store', secrets: 1, owners: ['vault'] },
          ],
          references: { count: 3, sources: ['vault', 'gcp-sm', 'aws-sm'] },
        }),
      }),
    );
    expect(v.kind === 'locked' && v.references).toBe(3);
    expect(v.kind === 'locked' && v.referenceTo).toBe('Vault, GCP and AWS');
    expect(v.kind === 'locked' && v.breakdown!.map((p) => (p.code ? `[${p.text}]` : p.text)).join('')).toBe(
      '6 connections, the [github-mcp] server token and the Vault sign-in',
    );
    expect(protectsWords([{ kind: 'connection', secrets: 1, owners: ['dev-db'] }]).map((p) => p.text).join('')).toBe('1 connection');
    expect(
      protectsWords([
        { kind: 'mcp-server', secrets: 2, owners: ['a', 'b'] },
        { kind: 'tunnel', secrets: 1, owners: ['tunnel'] },
      ])
        .map((p) => p.text)
        .join(''),
    ).toBe('2 MCP server tokens and the tunnel token');
  });

  it('locked before /api/master-key answers: the desktop default path, nothing counted; invalid key worded apart', () => {
    const v = statusView(state({ kind: 'locked', reason: 'MASTER_KEY_INVALID' }), facts());
    expect(v.kind === 'locked' && [v.reason, v.keyPath, v.secrets, v.lastBackupAt]).toEqual(['MASTER_KEY_INVALID', DEFAULT_KEY_PATH, null, null]);
    expect(v.kind === 'locked' && v.steps[1].description).toBe('Can’t open the stored secrets with');
  });

  it('port in use: the owner from the ready:false line, Local API failed on the address, the rest done', () => {
    const v = statusView(
      state({
        kind: 'failed',
        reason: 'PORT_IN_USE',
        detail: 'Address already in use: 127.0.0.1:7465',
        portOwner: { pid: 4242, user: 'alex' },
        steps: [{ step: 'runtime', state: 'done' }, { step: 'master-key', state: 'done' }, { step: 'db-migrations', state: 'done' }, { step: 'local-api', state: 'failed' }],
      }),
      facts(),
    );
    expect(v).toEqual({
      kind: 'port-in-use',
      portOwner: { pid: 4242, user: 'alex' },
      takenPort: null,
      publicListener: false,
      steps: [
        { id: 'runtime', state: 'complete' },
        { id: 'master-key', state: 'complete' },
        { id: 'db-migrations', state: 'complete' },
        { id: 'local-api', state: 'error', description: 'Address already in use:', mono: ADDRESS },
      ],
    });
  });

  it('port in use on the tunnel’s public listener: the line names that port (StartupFailures), not the local one', () => {
    const v = statusView(
      state({ kind: 'failed', reason: 'PORT_IN_USE', detail: 'Address already in use: 127.0.0.1:7466', publicPort: 7466, portOwner: null, steps: [] }),
      facts(),
    );
    expect(v.kind === 'port-in-use' && [v.takenPort, v.publicListener, v.steps[3].mono]).toEqual([7466, true, '127.0.0.1:7466']);
    expect(takenPort('Address already in use: 127.0.0.1:7465')).toBe(7465);
    expect(takenPort(null)).toBeNull();
  });

  it('a second core on the same home stops at the database lock, before any step: no stepper, the facts say it', () => {
    const v = statusView(
      state({ kind: 'failed', reason: 'DB_LOCKED', detail: 'Another GateRook core is using ~/.mcp-gateway', exitCode: 2, logTail: 'Action: …', steps: [{ step: 'runtime', state: 'running' }] }),
      facts(),
    );
    expect(v).toEqual({
      kind: 'start-failed',
      icon: 'lock',
      title: 'Another core is using the database',
      lead: expect.stringContaining('Quit the other GateRook, then restart the core.'),
      steps: [],
      exitCode: 2,
      reason: 'DB_LOCKED: Another GateRook core is using ~/.mcp-gateway',
      logLines: 'Action: …',
    });
  });

  it('a failed migration shows where it stopped: the step running then is the failed one', () => {
    const v = statusView(
      state({ kind: 'failed', reason: 'DB_MIGRATION_FAILED', steps: [{ step: 'runtime', state: 'done' }, { step: 'master-key', state: 'done' }, { step: 'db-migrations', state: 'running' }] }),
      facts(),
    );
    expect(v.kind === 'start-failed' && v.title).toBe('The database couldn’t be updated');
    expect(v.kind === 'start-failed' && v.steps.map((s) => s.state)).toEqual(['complete', 'complete', 'error', 'upcoming']);
  });

  it('a ready:false line means the process ran, even if main still had the runtime running', () => {
    const v = statusView(state({ kind: 'failed', reason: 'STARTUP_FAILED', steps: [{ step: 'runtime', state: 'running' }, { step: 'master-key', state: 'running' }] }), facts());
    expect(v.kind === 'start-failed' && v.steps.map((s) => s.state)).toEqual(['complete', 'error', 'upcoming', 'upcoming']);
    expect(v.kind === 'start-failed' && [v.title, v.reason]).toEqual(['Core failed to start', 'STARTUP_FAILED']);
  });

  it('no ready line in time: the step that hung is the failed one, the runtime included', () => {
    const v = statusView(state({ kind: 'failed', reason: 'READY_TIMEOUT', steps: [{ step: 'runtime', state: 'running' }] }), facts());
    expect(v.kind === 'start-failed' && v.steps.map((s) => s.state)).toEqual(['error', 'upcoming', 'upcoming', 'upcoming']);
    expect(v.kind === 'start-failed' && v.title).toBe('The core didn’t start in time');
  });

  it('stopped: the exit as main saw it, and the requests this window last saw waiting', () => {
    const v = statusView(
      state({ kind: 'stopped', exitCode: 3, reason: 'java.lang.OutOfMemoryError: Java heap space', uptimeMs: 11_520_000, exitedAt: '2026-10-22T14:29:52+02:00', logTail: 'line' }),
      facts({ pending: 3 }),
    );
    expect(v).toEqual({
      kind: 'stopped',
      at: '2026-10-22T14:29:52+02:00',
      exitCode: 3,
      reason: 'java.lang.OutOfMemoryError: Java heap space',
      uptimeMs: 11_520_000,
      logLines: 'line',
      pending: 3,
    });
  });

  it('a browser without a core: unreachable', () => {
    expect(statusView(state({ kind: 'unreachable', detail: 'no answer' }), facts({ desktop: false }))).toEqual({ kind: 'unreachable', detail: 'no answer' });
  });
});

describe('Use another port', () => {
  it('takes 1024 to 65535, not the taken port and not the tunnel’s', () => {
    expect(portError('', 7465, 7466)).toBe('Enter a port from 1024 to 65535.');
    expect(portError('74x5', 7465, 7466)).toBe('Use digits only: a port from 1024 to 65535.');
    expect(portError('80', 7465, 7466)).toBe('80 is outside 1024 to 65535. Use a port in that range.');
    expect(portError('70000', 7465, 7466)).toBe('70000 is outside 1024 to 65535. Use a port in that range.');
    expect(portError('7465', 7465, 7466)).toBe('7465 is the port that is in use. Choose another one.');
    expect(portError('7466', 7465, 7466)).toBe('7466 is the tunnel’s port. Choose another one.');
    expect(portError(' 7475 ', 7465, 7466)).toBeNull();
    expect(portError('7466', 7465, null)).toBeNull();
  });

  it('looks ten up first (§8.6.9: 7475), past the tunnel’s port, inside the range', () => {
    expect(firstCandidate(7465, 7466)).toBe(7475);
    expect(firstCandidate(7456, 7466)).toBe(7467);
    expect(firstCandidate(65530, null)).toBeGreaterThanOrEqual(1024);
    expect(portOf('127.0.0.1:7465')).toBe(7465);
  });

  it('lists the endpoint URLs as the kit does, the first in full', () => {
    expect(endpointUrls(7475, ['dev', 'stage-readonly', 'incident-prod'])).toEqual({
      first: 'http://127.0.0.1:7475/mcp/dev',
      rest: ['/mcp/stage-readonly', '/mcp/incident-prod'],
    });
    expect(endpointUrls(7475, [])).toBeNull();
  });

  it('says what main refused, without Electron’s IPC prefix', () => {
    expect(usePortError(new Error("Error invoking remote method 'gw:use-port': Error: Port 7475 is in use now. Choose another one."))).toBe(
      'Port 7475 is in use now. Choose another one.',
    );
    expect(usePortError('nope')).toBe('The app couldn’t start the core on this port. Choose another one.');
  });
});

describe('restore, a new key and the log file', () => {
  const problem = (status: number, body: object) => ApiError.fromHttp(new HttpErrorResponse({ status, error: body }));

  it('a passphrase the backup needs is the field’s problem; a key that doesn’t fit is the dialog’s', () => {
    expect(restoreProblem(problem(422, { title: 'Can’t read the backup', detail: 'This backup is protected by a passphrase. Enter it to restore.' }))).toEqual({
      message: 'This backup is protected by a passphrase. Enter it to restore.',
      passphrase: true,
      unlocked: false,
    });
    expect(restoreProblem(problem(422, { title: 'The backup doesn’t fit', detail: 'This backup doesn’t hold the key of the stored secrets (1 key id(s) missing).' })).passphrase).toBe(false);
    expect(restoreProblem(problem(409, { title: 'The master key works', detail: 'GateRook is not locked' })).unlocked).toBe(true);
    expect(restoreProblem(new Error('boom')).message).toBe('The core didn’t answer. Try again.');
  });

  it('toasts for the app that comes back', () => {
    expect(restoredToast(8)).toEqual({ tone: 'success', title: 'Master key restored', body: 'The 8 secrets stored here can be read again.' });
    expect(restoredToast(0).body).toBe('The core is unlocked.');
    expect(createdToast(['dev-db/password', 'orders-mongo/password', 'cache-redis/password', 'app-logs/token'])).toEqual({
      tone: 'warning',
      title: 'New master key created',
      body: '4 secrets need a value again: dev-db/password, orders-mongo/password and 2 more. Enter each one on Secrets.',
    });
    expect(createdToast(['dev-db/password']).body).toBe('1 secret needs a value again: dev-db/password. Enter each one on Secrets.');
    expect(createdToast([]).tone).toBe('success');
    expect(createProblem(problem(422, { title: 'Confirmation needed', detail: 'confirm with "danger"' }))).toEqual({ title: 'No new key was created', body: 'confirm with "danger"' });
  });

  it('finds the log beside the key the locked core named, else the desktop default', () => {
    expect(logFileOf('/var/log/core.log', KEY)).toBe('/var/log/core.log');
    expect(logFileOf(null, KEY)).toBe('/home/alex/.mcp-gateway/logs/core.log');
    expect(logFileOf(null, 'C:\\Users\\alex\\.mcp-gateway\\master.keyset')).toBe('C:\\Users\\alex\\.mcp-gateway\\logs\\core.log');
    expect(logFileOf(null, null)).toBe('~/.mcp-gateway/logs/core.log');
  });
});
