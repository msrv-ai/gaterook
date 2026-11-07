import { ClientCard, ClientLink } from '../../core/api/clients.api';
import { ConnectionRow } from '../../core/api/connections.api';
import { endpoint } from '../endpoints/endpoints.fixtures';
import { endpointUnreachable, mapApps, mapConnections } from './overview-map-view';
import { at, card, health, link, row } from './overview.fixtures';

const dev = endpoint('dev');
const native = (name: string, over: Partial<ConnectionRow> = {}): ConnectionRow => ({ ...row(name, 'dev', ['dev']), ...over });
const mcp = (name: string, usedBy: string[] = [], over: Partial<ConnectionRow> = {}): ConnectionRow => ({
  ...row(name, 'dev', usedBy), type: 'mcp-server', typeName: 'External MCP server', ...over,
});
const app = (links: ClientLink[] = [], over: Partial<ClientCard> = {}): ClientCard => ({
  ...card('codex', 'Codex', 'connected', links), methods: [{ id: 'helper', label: 'Helper', prodAllowed: true, reason: null }], ...over,
});

describe('Overview connection map', () => {
  it('scopes native services to exact endpoint membership and shows installed MCP servers separately', () => {
    const rows = [native('dev-db'), native('other-db', { usedBy: ['dev-other'] }), mcp('docs', ['dev']), mcp('github', ['stage'])];
    const mapped = mapConnections(rows, [], dev)!;
    expect(mapped.map((item) => item.name)).toEqual(['dev-db', 'docs', 'github']);
    expect(mapped[0]).toEqual(expect.objectContaining({ assigned: true, mcp: false, icon: 'database', route: ['/connections', 'dev-db'], fragment: null }));
    expect(mapped[1]).toEqual(expect.objectContaining({ assigned: true, mcp: true, icon: 'plug', effectiveOn: true, state: { tone: 'success', label: 'Connected' } }));
    expect(mapped[2]).toEqual(expect.objectContaining({ assigned: false, mcp: true, effectiveOn: false, state: { tone: 'neutral', label: 'Not connected to dev' } }));
    expect(mapped[2].row).toEqual(rows[3]);
    expect(mapConnections(rows, [], null)?.map((item) => [item.name, item.assigned])).toEqual([['docs', false], ['github', false]]);
    expect(mapConnections(null, [], dev)).toBeNull();
  });

  it('keeps global saved switches distinct from endpoint access and does not count paused or blocked problems', () => {
    const unreachable = { state: 'unreachable', latencyMs: null, message: null, checkedAt: at(14, 29) };
    const rows = [
      native('offline', { health: unreachable }), native('switched-off', { enabled: false, health: unreachable }),
      native('unverified', { verified: false, health: unreachable }), native('incomplete', { setupRequired: true, health: unreachable }),
      native('slow', { health: { ...unreachable, state: 'degraded', latencyMs: 1800 } }),
      mcp('unassigned', [], { health: unreachable }),
    ];
    const mapped = mapConnections(rows, [], dev)!;
    expect(endpointUnreachable(dev, rows, [])).toBe(1);
    expect(mapped.map((item) => item.state.label)).toEqual(['Unreachable', 'Connection off', 'Review required', 'Setup required', 'Slow · 1.8 s', 'Not connected to dev']);
    expect(mapped[2].blockedReason).toContain('Review');
    expect(mapped[3].blockedReason).toContain('Finish setting up');
    const paused = endpoint('dev', { status: 'disabled' });
    const blocked = endpoint('dev', { blockedReason: 'Changed outside GateRook' });
    expect(endpointUnreachable(paused, rows, [])).toBe(0);
    expect(endpointUnreachable(blocked, rows, [])).toBe(0);
    expect(endpointUnreachable(dev, null, [])).toBeNull();
    expect(mapConnections(rows, [], paused)?.[0]).toEqual(expect.objectContaining({ enabled: true, effectiveOn: false, state: { tone: 'neutral', label: 'Endpoint off' } }));
    expect(mapConnections(rows, [], blocked)?.[0]).toEqual(expect.objectContaining({ effectiveOn: false, blockedReason: 'Changed outside GateRook' }));
  });

  it('uses the newest health and includes external MCP health even when overview omits it', () => {
    const rows = [native('db'), mcp('docs', ['dev'], { health: { state: 'unreachable', latencyMs: null, message: 'Offline', checkedAt: at(14, 31) } })];
    const fresh = health('db', 'unreachable', { checkedAt: at(14, 30) });
    const stale = health('docs', 'healthy', { checkedAt: at(14, 29) });
    expect(endpointUnreachable(dev, rows, [fresh, stale])).toBe(2);
    expect(endpointUnreachable(dev, rows, [fresh])).toBe(2);
    expect(mapConnections(rows, [fresh, stale], dev)?.[1].row.health.message).toBe('Offline');
    expect(mapConnections(rows, [health('db', 'unreachable', { checkedAt: null })], dev)?.[0].state.label).toBe('Healthy');
  });

  it('keeps a healthy connection globally On while selected endpoint access is paused', () => {
    const rows = [native('ready-db')];
    const paused = endpoint('dev', { status: 'disabled' });
    const connection = mapConnections(rows, [], paused)![0];
    expect(connection.enabled).toBe(true);
    expect(connection.row.health.state).toBe('healthy');
    expect(connection.effectiveOn).toBe(false);
    expect(connection.state.label).toBe('Endpoint off');
    expect(endpointUnreachable(paused, rows, [])).toBe(0);
    expect(mapConnections(rows, [], dev)![0]).toEqual(expect.objectContaining({
      enabled: true, effectiveOn: true, state: { tone: 'success', label: 'Healthy' },
    }));
  });
});

describe('Overview AI app map', () => {
  it('shows a named manual app with a generic mark and a pending setup path', () => {
    const manual = app([{ ...link('codex', 'dev', 'dev'), client: 'manual-example', method: 'manual', status: 'pending' }], { client: 'manual-example', name: 'Research assistant', manual: true, status: 'pending', methods: [] });
    expect(mapApps([manual], dev)?.[0]).toEqual(expect.objectContaining({
      mark: 'R', tone: 'generic', assigned: true, connected: false, pending: true,
      state: { tone: 'info', label: 'Awaiting connection' }, action: 'manage', fragment: 'cl-manual-example-t',
    }));
    expect(mapApps([manual], endpoint('other'))?.[0]).toEqual(expect.objectContaining({ assigned: false, action: 'manage', actionReason: null }));
  });

  it('never treats global Connected or a similar endpoint slug as access to this endpoint', () => {
    const cards = [app([link('codex', 'dev-other', 'dev')])];
    expect(mapApps(cards, dev)?.[0]).toEqual(expect.objectContaining({
      assigned: false, connected: false, pending: false, state: { tone: 'neutral', label: 'Not connected' }, action: 'connect', actionReason: null,
    }));
    expect(mapApps(cards, null)?.[0].actionReason).toBe('Create or select an endpoint first.');
    expect(mapApps(null, dev)).toBeNull();
  });

  it('counts observed OAuth sign-in without a config file or tool call as connected to its endpoint', () => {
    const cards = [app([link('codex', 'dev', 'dev', { method: 'oauth', configPath: null, lastCallAt: null })])];
    expect(mapApps(cards, dev)?.[0]).toEqual(expect.objectContaining({
      assigned: true, connected: true, pending: false, state: { tone: 'success', label: 'Connected' }, action: null, fragment: 'cl-cx-t',
    }));
    expect(mapApps(cards, endpoint('dev', { status: 'disabled' }))?.[0]).toEqual(expect.objectContaining({
      assigned: true, connected: false, pending: true, state: { tone: 'neutral', label: 'Endpoint off' }, action: 'manage',
    }));
    expect(mapApps(cards, endpoint('dev', { blockedReason: 'Review changes' }))?.[0]).toEqual(expect.objectContaining({
      connected: false, pending: true, state: { tone: 'warning', label: 'Access blocked' },
    }));
  });

  it('keeps restart and repair paths distinct from revoked and removed connections', () => {
    const mapped = (status: ClientLink['status']) => mapApps([app([link('codex', 'dev', 'dev', { status })])], dev)![0];
    expect(mapped('needs-restart')).toEqual(expect.objectContaining({ assigned: true, pending: true, state: { tone: 'warning', label: 'Needs restart' }, action: 'manage' }));
    expect(mapped('needs-repair')).toEqual(expect.objectContaining({ assigned: true, pending: true, state: { tone: 'danger', label: 'Needs repair' } }));
    expect(mapped('error')).toEqual(expect.objectContaining({ assigned: true, pending: true, state: { tone: 'danger', label: 'Needs repair' } }));
    expect(mapped('removed-outside')).toEqual(expect.objectContaining({ assigned: false, pending: false, state: { tone: 'danger', label: 'Needs repair' }, action: 'manage' }));
    expect(mapped('revoked')).toEqual(expect.objectContaining({ assigned: false, pending: false, state: { tone: 'neutral', label: 'Not connected' }, action: 'connect' }));
  });

  it('gives install and allowed-method guidance instead of offering unsupported or paused access', () => {
    const noProdMethod = app([], { methods: [{ id: 'token', label: 'Token', prodAllowed: false, reason: 'Not supported on production' }] });
    expect(mapApps([noProdMethod], endpoint('production', { env: 'prod' }))?.[0].actionReason).toContain('No supported connection method');
    expect(mapApps([app()], endpoint('dev', { status: 'disabled' }))?.[0].actionReason).toContain('Turn on this endpoint');
    expect(mapApps([app([], { status: 'not-installed' })], dev)?.[0]).toEqual(expect.objectContaining({
      connected: false, state: { tone: 'neutral', label: 'Not installed' }, action: 'install', actionReason: null,
    }));
  });
});
