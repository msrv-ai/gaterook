import { MetaPart } from '../../kit/elements/getting-started/getting-started';
import { backedUpKey, clients, masterKey, onboarding } from './onboarding.fixtures';
import {
  MAP_FLOW,
  SetupFacts,
  StepId,
  allDone,
  backedUp,
  backedUpToast,
  clientsDescription,
  doneLine,
  formatText,
  keyFileName,
  landingStep,
  permissionsText,
  setupNodes,
  stepAnnouncement,
  stepperSteps,
  stepsDone,
} from './onboarding-view';

/** The words and states of Welcome against the kit (design/pages/onboarding.html) for the core's answers. */

const firstRun = (patch: Partial<SetupFacts> = {}): SetupFacts => ({ onboarding: onboarding(), key: masterKey(), clients: clients(), ...patch });
const text = (parts: readonly MetaPart[]) => parts.map((p) => (p.mono ? `\`${p.text}\`` : p.text)).join('');
const relative = () => 'just now';
const none = new Set<StepId>();

describe('Welcome: steps', () => {
  it('opens a first run on step 1 with the kit stepper', () => {
    const f = firstRun();
    const done = stepsDone(f);
    expect(done).toEqual({ 'master-key': false, connection: false, endpoint: false, client: false });
    expect(landingStep(done, none)).toBe(0);
    expect(stepperSteps(f, 0, none, relative).map((s) => [s.state, s.label, text(s.description)])).toEqual([
      ['current', 'Protect master key', 'Back it up off this computer'],
      ['upcoming', 'Add a connection', 'A database, server, log store or API'],
      ['upcoming', 'Create an endpoint', 'One MCP URL with the tools you choose'],
      ['upcoming', 'Connect a client', 'Claude Desktop, Codex, Cursor and more'],
    ]);
  });

  it('marks the backup done only while the current key is in it', () => {
    const backed = onboarding({ 'master-key': { lastBackupAt: '2026-10-22T12:29:59Z' } });
    expect(backedUp({ onboarding: backed, key: backedUpKey(), clients: null })).toBe(true);
    // A rotation since the backup: the core still has a backup, but not of this key.
    expect(backedUp({ onboarding: backed, key: masterKey({ lastBackupAt: '2026-10-22T12:29:59Z' }), clients: null })).toBe(false);
    // Without the key status, getting started decides.
    expect(backedUp({ onboarding: backed, key: null, clients: null })).toBe(true);
  });

  it('says the backup was put off, and opens past it (kit "I\'ll do it later")', () => {
    const f = firstRun();
    const later = new Set<StepId>(['master-key']);
    expect(landingStep(stepsDone(f), later)).toBe(1);
    const steps = stepperSteps(f, 1, later, relative);
    expect(steps[0]).toMatchObject({ state: 'upcoming', description: [{ text: 'Skipped · no backup yet' }] });
    expect(steps[1].state).toBe('current');
  });

  it('says what each done step set up, from the core facts', () => {
    const f: SetupFacts = {
      onboarding: onboarding({
        'master-key': { lastBackupAt: '2026-10-22T12:29:59Z' },
        connection: { name: 'dev-db', typeName: 'PostgreSQL', count: 1 },
        endpoint: { name: 'dev', tools: 22 },
        client: { clients: ['claude-desktop', 'codex'] },
      }),
      key: backedUpKey(),
      clients: clients(),
    };
    const done = stepsDone(f);
    expect(allDone(done)).toBe(true);
    // Nothing left: the last step, which leads to Overview.
    expect(landingStep(done, none)).toBe(3);
    expect(stepperSteps(f, 3, none, relative).map((s) => [s.state, text(s.description)])).toEqual([
      ['complete', 'Backed up just now'],
      ['complete', '`dev-db` · PostgreSQL'],
      ['complete', '`dev` · 22 tools'],
      // K1 shows no connected card: the clients getting started counted.
      ['current', 'Claude Desktop, Codex'],
    ]);
    const many = { ...f, onboarding: onboarding({ connection: { name: 'dev-db', typeName: 'PostgreSQL', count: 3 } }) };
    expect(text(stepperSteps(many, 0, none, relative)[1].description)).toBe('`dev-db` and 2 more');
  });

  it('announces a step after what just happened', () => {
    expect(stepAnnouncement(1, 'Master key backed up.')).toBe('Master key backed up. Step 2 of 4: Add a connection.');
    expect(stepAnnouncement(0)).toBe('Step 1 of 4: Protect master key.');
  });
});

describe('Welcome: setup map', () => {
  it('reads like the kit on a first run', () => {
    const nodes = setupNodes(firstRun());
    expect(nodes.map((n) => [n.id, n.state, n.status])).toEqual([
      ['secrets', 'attention', 'Step 1 · master key not backed up'],
      ['connections', 'todo', 'Step 2 · none yet'],
      ['policies', 'done', 'Safe defaults on · review any time'],
      ['endpoints', 'todo', 'Step 3 · none yet'],
      ['clients', 'todo', 'Step 4 · 3 detected'],
    ]);
  });

  it('turns the secrets node done after the backup (kit), and names a key problem the core reports', () => {
    const backed = onboarding({ 'master-key': { lastBackupAt: '2026-10-22T12:29:59Z' } });
    expect(setupNodes({ onboarding: backed, key: backedUpKey(), clients: null })[0]).toMatchObject({
      state: 'done',
      status: 'Step 1 · master key backed up, no secrets yet',
    });
    expect(setupNodes({ onboarding: backed, key: backedUpKey(), clients: null })[0]).not.toMatchObject({ state: 'attention' });
    const noOs = masterKey({ lastBackupAt: '2026-10-22T12:29:59Z', problems: ['no-os-protection'], osProtection: 'unavailable' });
    expect(setupNodes({ onboarding: backed, key: noOs, clients: null })[0]).toMatchObject({
      state: 'attention',
      status: 'Step 1 · master key backed up, no secrets yet · no OS protection',
    });
    expect(setupNodes({ onboarding: onboarding(), key: masterKey({ problems: ['backup-needed', 'perms'] }), clients: null })[0].status).toBe(
      'Step 1 · master key not backed up, other users can read the key file',
    );
  });

  it('counts what later steps set up', () => {
    const base = onboarding({ connection: { name: 'dev-db' }, endpoint: { name: 'dev' } });
    const o = {
      ...base,
      setupMap: {
        nodes: base.setupMap.nodes.map((n) =>
          n.id === 'connections'
            ? { ...n, count: 3, status: '3 connections' }
            : n.id === 'endpoints'
              ? { ...n, count: 1, state: 'attention' as const, status: '1 changed outside MCP Gateway' }
              : n,
        ),
      },
    };
    const nodes = setupNodes({ onboarding: o, key: masterKey(), clients: clients(['codex']) });
    expect(nodes.map((n) => n.status)).toEqual([
      'Step 1 · master key not backed up',
      'Step 2 · 3 connections',
      'Safe defaults on · review any time',
      'Step 3 · 1 changed outside MCP Gateway',
      'Step 4 · 1 connected · 2 detected',
    ]);
  });

  it('says what it knows about clients before K1 answers, and when none is found', () => {
    expect(setupNodes(firstRun({ clients: null }))[4].status).toBe('Step 4 · none yet');
    const none = { scannedAt: null, clients: clients().clients.map((c) => ({ ...c, status: 'not-installed' as const, detected: false })) };
    expect(setupNodes(firstRun({ clients: none }))[4].status).toBe('Step 4 · none found');
  });

  it('describes the chain by the steps, as the kit does', () => {
    expect(MAP_FLOW).toBe(
      "Set it up top to bottom: step 1 protects secrets and resources, step 2 adds a connection that uses them, policies already have safe defaults, step 3 publishes tools on an endpoint and step 4 connects a client to it. An agent's call travels the other way: client, endpoint (with a token or OAuth), policy (mode, limits, approval), connection, where the secret is injected. The agent never receives the secret.",
    );
  });
});

describe('Welcome: step cards', () => {
  it('names the installed clients (kit) or what can connect before K1', () => {
    expect(clientsDescription(firstRun())).toBe('Claude Desktop, Codex and Cursor are installed on this computer.');
    expect(clientsDescription(firstRun({ clients: null }))).toBe('Claude Desktop, Codex, Cursor and Antigravity can connect.');
    const one = { scannedAt: null, clients: [clients().clients[2]] };
    expect(clientsDescription(firstRun({ clients: one }))).toBe('Cursor is installed on this computer.');
    expect(clientsDescription(firstRun({ clients: { scannedAt: null, clients: [] } }))).toBe(
      'No MCP client was found on this computer yet. Install one, then connect it here.',
    );
  });

  it('says the key file\'s permissions and format', () => {
    expect(permissionsText(masterKey())).toEqual({ mode: '0400', text: 'only your user can read it', wrong: false });
    expect(permissionsText(masterKey({ mode: '0644', problems: ['perms'] }))).toEqual({ mode: '0644', text: 'other users can read it', wrong: true });
    expect(formatText(masterKey())).toBe('Google Tink keyset, sealed with a key from the macOS Keychain');
    expect(formatText(masterKey({ osProtection: 'off' }))).toBe('Google Tink keyset');
    expect(keyFileName('/Users/you/.mcp-gateway/master.keyset')).toBe('master.keyset');
    expect(keyFileName(null)).toBe('master.keyset');
  });

  it('says what a done card set up', () => {
    const f: SetupFacts = {
      onboarding: onboarding({ connection: { name: 'dev-db', count: 1 }, endpoint: { name: 'dev', tools: 22 }, client: { clients: ['codex'] } }),
      key: masterKey(),
      clients: clients(['claude-desktop', 'codex']),
    };
    expect(text(doneLine(f, 'connection'))).toBe('Connection `dev-db` is set up.');
    expect(text(doneLine(f, 'endpoint'))).toBe('Endpoint `dev` is set up with 22 tools.');
    expect(text(doneLine(f, 'client'))).toBe('Claude Desktop and Codex use MCP Gateway.');
    const many = { ...f, onboarding: onboarding({ connection: { name: 'dev-db', count: 3 } }) };
    expect(text(doneLine(many, 'connection'))).toBe('3 connections are set up, including `dev-db`.');
  });

  it('names the copy in the backup toast and warns about a synced folder', () => {
    expect(backedUpToast({ path: '/Users/you/Downloads/master-keyset-2026-10-22.json', passphrase: false, warnings: [] })).toEqual({
      tone: 'success',
      title: 'Master key backed up',
      body: 'Saved to /Users/you/Downloads/master-keyset-2026-10-22.json. Move it to your password manager or an encrypted drive, then delete it from this computer.',
    });
    const synced = backedUpToast({ path: '/Users/you/Dropbox/k.json', passphrase: true, warnings: ['synced-folder'] });
    expect(synced.tone).toBe('warning');
    expect(synced.title).toBe('Master key backed up, encrypted with your passphrase');
    expect(synced.body).toContain('That folder syncs to other devices');
  });
});
