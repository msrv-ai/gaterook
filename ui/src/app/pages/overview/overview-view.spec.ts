import { HEALTH, ROWS, card, clientsFound, clientsInUse, firstRun, health, overview, setup, tunnelOff } from './overview.fixtures';
import {
  activityLine,
  appAnchor,
  canUse,
  checkPrompt,
  claudeAiTile,
  connectionTile,
  connectionTiles,
  guideAppTile,
  homeAppTiles,
  modesWords,
  overviewState,
  retestToast,
  secretLine,
  secretNoun,
  tileHealth,
} from './overview-view';

/** The words of design/pages/overview.html from the core's answers. */

describe('Overview state', () => {
  it('is first run without a connection, setup until an AI app is connected, then in use', () => {
    expect(overviewState(firstRun())).toBe('first-run');
    expect(overviewState(setup())).toBe('setup');
    expect(overviewState(overview())).toBe('in-use');
  });

  it('counts the connections the core counts (its setup map), external MCP servers aside', () => {
    // The health rows can lag behind (a connection just added): the setup map decides.
    const o = setup();
    expect(overviewState({ ...o, health: [] })).toBe('setup');
    // A core whose setup map has no connections node: the health rows count.
    expect(overviewState({ ...firstRun(), setupMap: { nodes: [] }, health: [health('dev-db', 'healthy')] })).toBe('setup');
  });

  it('a named token or a link counts as an AI app (the core marks its "client" step done)', () => {
    const o = setup();
    const done = o.gettingStarted.steps.map((s) => (s.id === 'client' ? { ...s, state: 'done' as const } : s));
    expect(overviewState({ ...o, gettingStarted: { ...o.gettingStarted, steps: done } })).toBe('in-use');
  });
});

describe('Your connections', () => {
  it('words a tile as the kit does: Healthy, Slow with its time, Unreachable with where it connects', () => {
    expect(tileHealth({ state: 'healthy', latencyMs: 12 })).toEqual({ tone: 'success', label: 'Healthy' });
    expect(tileHealth({ state: 'degraded', latencyMs: 1800 })).toEqual({ tone: 'warning', label: 'Slow · 1.8 s' });
    expect(tileHealth({ state: 'unreachable', latencyMs: null })).toEqual({ tone: 'danger', label: 'Unreachable' });
    expect(tileHealth({ state: 'unknown', latencyMs: null })).toEqual({ tone: 'neutral', label: 'Not checked yet' });

    const billing = connectionTile(HEALTH[2]);
    expect(billing).toMatchObject({ name: 'billing-api', env: 'stage', icon: 'globe', retest: true });
    // The host without its scheme, in mono (kit: "HTTP API · billing.stage.acme.dev").
    expect(billing.meta).toEqual([{ text: 'HTTP API · ' }, { text: 'billing.stage.acme.dev', mono: true }]);
    expect(connectionTile(HEALTH[3]).meta).toEqual([{ text: 'Redis 7' }]);
    expect(connectionTile(health('x', 'healthy', { type: 'mongodb', typeLabel: undefined })).meta).toEqual([{ text: 'MongoDB' }]);
  });

  it('puts problems first, one row in view, the rest behind "N more connections, all healthy"', () => {
    const t = connectionTiles(HEALTH);
    expect(t.shown.map((c) => c.name)).toEqual(['billing-api', 'cache-redis', 'app-logs']);
    expect(t.more).toHaveLength(9);
    // Healthy ones by environment: dev, stage, prod.
    expect(t.more.map((c) => c.env)).toEqual(['dev', 'dev', 'dev', 'dev', 'stage', 'stage', 'prod', 'prod', 'prod']);
    expect(t.moreLabel).toBe('9 more connections, all healthy');
    expect(t.description).toBe("12 connections: 10 healthy, 1 slow, 1 unreachable. The tag shows each one's environment: dev, stage or prod.");
  });

  it('never folds a problem away, and says what the folded ones are when they differ', () => {
    const rows = [
      health('a', 'unreachable'),
      health('b', 'unreachable'),
      health('c', 'degraded', { latencyMs: 2000 }),
      health('d', 'unreachable'),
      health('e', 'healthy'),
      health('f', 'unknown'),
    ];
    const t = connectionTiles(rows);
    expect(t.shown.map((c) => c.name)).toEqual(['a', 'b', 'd', 'c']);
    expect(t.moreLabel).toBe('2 more connections: 1 healthy, 1 not checked yet');
    expect(connectionTiles([health('e', 'healthy'), health('f', 'healthy'), health('g', 'healthy'), health('h', 'healthy')]).moreLabel).toBe(
      '1 more connection, healthy',
    );
    expect(connectionTiles([health('e', 'healthy')]).description).toBe("1 connection: healthy. The tag shows each one's environment: dev, stage or prod.");
    expect(connectionTiles([health('e', 'healthy')]).moreLabel).toBeNull();
  });

  it('tells the result of Test again', () => {
    expect(retestToast({ name: 'billing-api', ok: false, latencyMs: null, message: "Can't reach billing.stage.acme.dev." })).toEqual({
      tone: 'danger',
      title: 'billing-api is still unreachable',
      body: "Can't reach billing.stage.acme.dev.",
    });
    expect(retestToast({ name: 'billing-api', ok: true, latencyMs: 40, message: null })).toEqual({
      tone: 'success',
      title: 'billing-api is reachable again',
      body: 'It answered in 40 ms.',
    });
  });
});

describe('AI apps', () => {
  it('names each app’s card on AI apps as the kit does (#cl-<app>-t)', () => {
    expect(['claude-desktop', 'codex', 'cursor', 'antigravity', 'claude-ai'].map(appAnchor)).toEqual(['cl-cd-t', 'cl-cx-t', 'cl-cu-t', 'cl-ag-t', 'cl-ai-t']);
  });

  it('counts what a connected app can use, each connection once, and how many are prod', () => {
    const [claude, codex] = clientsInUse().clients;
    expect(canUse(claude, ROWS)).toBe('Can use 11 connections, 3 of them prod');
    expect(canUse(codex, ROWS)).toBe('Can use 8 connections');
    // Without the connections list, its endpoints.
    expect(canUse(claude, null)).toBe('Endpoints dev and incident-prod');
    expect(canUse(codex, null)).toBe('Endpoint dev');
    expect(canUse(codex, [])).toBe('Endpoint dev, no connections yet');
  });

  it('lists the home screen’s apps: the one to connect first, connected ones, claude.ai last; not installed ones stay on AI apps', () => {
    const tiles = homeAppTiles(clientsInUse().clients, ROWS, claudeAiTile(tunnelOff(), () => '12:10'));
    expect(tiles.map((t) => [t.name, t.state.label, t.meta, t.action, t.linked])).toEqual([
      ['Cursor', 'Not connected', 'Found on this computer', 'connect', false],
      ['Claude Desktop', 'Connected', 'Can use 11 connections, 3 of them prod', null, true],
      ['Codex', 'Connected', 'Can use 8 connections', null, true],
      ['claude.ai', 'Tunnel off', 'In the browser · used until 12:10', null, true],
    ]);
  });

  it('puts an app that needs a restart or failed before the connected ones, with the core’s words', () => {
    const restart = { ...card('codex', 'Codex', 'needs-restart'), statusText: 'Restart Codex: Settings › MCP servers › Restart' };
    const failed = { ...card('cursor', 'Cursor', 'error'), statusText: 'The entry gateway-dev was changed outside the app' };
    const tiles = homeAppTiles([card('claude-desktop', 'Claude Desktop', 'connected'), restart, failed], null, null);
    expect(tiles.map((t) => [t.name, t.state.tone, t.state.label, t.meta])).toEqual([
      ['Cursor', 'danger', 'Error', 'The entry gateway-dev was changed outside the app'],
      ['Codex', 'warning', 'Needs restart', 'Restart Codex: Settings › MCP servers › Restart'],
      ['Claude Desktop', 'success', 'Connected', ''],
    ]);
  });

  it('says where claude.ai stands: public, last used, never published', () => {
    const off = tunnelOff();
    expect(claudeAiTile(null, () => '')).toBeNull();
    expect(claudeAiTile({ ...off, history: [] }, () => '')).toMatchObject({ meta: 'In the browser · never published', state: { tone: 'neutral', label: 'Tunnel off' } });
    expect(claudeAiTile({ ...off, state: 'on', since: '2026-10-22T12:02:00Z' }, () => '14:02')).toMatchObject({
      meta: 'In the browser · public since 14:02',
      state: { tone: 'warning', label: 'Public · stage-readonly' },
    });
  });

  it('shows the guide’s apps found here with Connect, the others with How to install', () => {
    expect(clientsFound().clients.map(guideAppTile).map((t) => [t.name, t.state.label, t.meta, t.action, t.linked])).toEqual([
      ['Claude Desktop', 'Not connected', 'Found on this computer', 'connect', false],
      ['Codex', 'Not connected', 'Found on this computer', 'connect', false],
      ['Cursor', 'Not connected', 'Found on this computer', 'connect', false],
      ['Antigravity', 'Not installed', 'Not found on this computer', 'install', false],
    ]);
  });
});

describe('Activity line and the guide', () => {
  it('says the day in one line', () => {
    expect(activityLine({ calls24h: 1287, denied24h: 17 })).toBe('1,287 calls from your AI apps in the last 24 hours, 17 denied by policy. Each one is in');
    expect(activityLine({ calls24h: 1, denied24h: 0 })).toBe('1 call from your AI apps in the last 24 hours. Each one is in');
    expect(activityLine({ calls24h: 0, denied24h: 0 })).toBe('No calls from your AI apps in the last 24 hours. When one calls a tool, it shows up in');
  });

  it('asks the kit’s question for a database, the same idea for other types, "my database" before any connection', () => {
    expect(checkPrompt(null, null)).toBe('Show me the tables in my database');
    expect(checkPrompt('postgres', 'dev-db')).toBe('Show me the tables in dev-db');
    expect(checkPrompt('mongodb', 'orders')).toBe('Show me the collections in orders');
    expect(checkPrompt('kubernetes', 'dev-cluster')).toBe('Show me the pods in dev-cluster');
    expect(checkPrompt('logs', 'app-logs')).toBe('Show me the latest errors in app-logs');
    expect(checkPrompt('ssh', 'dev-vm')).toBe('Show me what you can see in dev-vm');
  });

  it('says what the first connection does for an AI app, from its tool modes', () => {
    expect(modesWords(null)).toBeNull();
    expect(modesWords([])).toBe('not on an endpoint yet');
    expect(modesWords([{ mode: 'READ_ONLY' }, { mode: 'APPROVAL' }])).toBe('changes need your approval');
    expect(modesWords([{ mode: 'BYPASS' }, { mode: 'APPROVAL' }])).toBe('some tools run without approval in Bypass');
    expect(modesWords([{ mode: 'READ_ONLY' }, { mode: 'OFF' }])).toBe('read only');
    expect(modesWords([{ mode: 'OFF' }])).toBe('its tools are off');
  });

  it('says where the secret is and calls it by its field', () => {
    expect(secretNoun('/credentials/password')).toBe('password');
    expect(secretNoun('/auth/token')).toBe('token');
    expect(secretNoun('/privateKey')).toBe('private key');
    expect(secretNoun('/apiKey')).toBe('key');
    expect(secretNoun('/whatever')).toBe('secret');
    expect(secretLine([])).toBeNull();
    expect(secretLine([{ field: '/credentials/password', secretId: 's', name: null, state: 'stored', version: 1, source: 'local' }])).toEqual({ noun: 'password', where: 'here' });
    expect(secretLine([{ field: '/credentials/password', secretId: null, name: null, state: 'reference', version: null, source: 'vault' }])).toEqual({ noun: 'password', where: 'manager' });
  });
});
