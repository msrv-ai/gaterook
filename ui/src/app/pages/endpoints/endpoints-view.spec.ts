import { relativeTime } from '../../core/time/time-format';
import { channelSource, compactLine } from '../../kit/elements/risk-callout/risk-callout';
import {
  andList,
  blockedText,
  clientParts,
  connectionsByEnv,
  copySlug,
  createField,
  createdToast,
  deleteFacts,
  deletedToast,
  disableBody,
  duplicatedToast,
  headerStatuses,
  healthParts,
  publishedMeta,
  revocableNames,
  slugError,
  startFromMeta,
  tokensFact,
  toolParts,
  tunnelStatus,
} from './endpoints-view';
import { NOW, TUNNEL_OFF, at, conn, endpoint, kitConnections, kitEndpoints, kitTokens, risk, token } from './endpoints.fixtures';

/** The page's words against the kit's (design/pages/endpoints.html), for the kit's own world. */

const rel = (iso: string) => relativeTime(iso, NOW);
const [dev, stage, prod] = kitEndpoints();
const tokens = kitTokens();
const line = (parts: { text: string; code: boolean }[]) => parts.map((p) => (p.code ? `\`${p.text}\`` : p.text)).join('');

describe('Endpoints view', () => {
  it('checks slugs as the kit’s field does', () => {
    expect(slugError('payments-dev')).toBeNull();
    expect(slugError('dev2')).toBeNull();
    expect(slugError('')).toBe('Enter a slug. It becomes the end of the endpoint URL.');
    expect(slugError('   ')).toBe('Enter a slug. It becomes the end of the endpoint URL.');
    for (const bad of ['Payments', 'pay_dev', '-dev', 'dev-', 'a--b', 'with space']) {
      expect(slugError(bad)).toMatch(/^Use lowercase letters, digits and single hyphens/);
    }
    expect(slugError('a'.repeat(41))).toBe('Use at most 40 characters.');
    expect(slugError('a'.repeat(40))).toBeNull();
  });

  it('suggests a free slug for a copy', () => {
    expect(copySlug('dev', ['dev', 'stage-readonly'])).toBe('dev-copy');
    expect(copySlug('dev', ['dev', 'dev-copy', 'dev-copy-2'])).toBe('dev-copy-3');
    // Cut to 40, never ending on a hyphen.
    for (const n of [35, 36, 38, 39, 40]) {
      const long = 'a'.repeat(n);
      const first = copySlug(long, [long]);
      expect(slugError(first)).toBeNull();
      expect(slugError(copySlug(long, [long, first]))).toBeNull();
    }
  });

  it('says whether the endpoints run, and what is published', () => {
    expect(headerStatuses(kitEndpoints())).toEqual([{ tone: 'success', text: 'All running' }]);
    const mixed = [dev, { ...stage, status: 'disabled' as const }, { ...prod, blockedReason: 'endpoint row' }];
    expect(headerStatuses(mixed)).toEqual([
      { tone: 'success', text: '1 running' },
      { tone: 'neutral', text: '1 disabled' },
      { tone: 'warning', text: '1 changed outside GateRook' },
    ]);
    expect(publishedMeta(kitEndpoints(), TUNNEL_OFF)).toEqual({ text: '1 published for claude.ai', tunnel: { text: 'tunnel off', at: at(22, '12:10:00') } });
    // Before K3 the core has no tunnel to describe: the count alone.
    expect(publishedMeta(kitEndpoints(), null)).toEqual({ text: '1 published for claude.ai', tunnel: null });
    expect(publishedMeta([dev, prod], TUNNEL_OFF)).toBeNull();
  });

  it('counts connections by environment, tools by mode', () => {
    expect(connectionsByEnv(dev)).toEqual({ total: 8, envs: [{ env: 'dev', count: 6 }, { env: 'stage', count: 2 }] });
    expect(connectionsByEnv(prod)).toEqual({ total: 3, envs: [{ env: 'prod', count: 3 }] });
    expect(toolParts(dev)).toEqual({
      total: 22,
      modes: [
        { cls: 'gw-mode--read', icon: 'eye', text: '14 read only' },
        { cls: 'gw-mode--approval', icon: 'user-check', text: '3 approval' },
        { cls: 'gw-mode--off', icon: 'circle-off', text: '5 off' },
      ],
    });
    // A mode with no tool is left out (kit stage-readonly: "10 · 8 read only 2 off").
    expect(toolParts(stage).modes.map((m) => m.text)).toEqual(['8 read only', '2 off']);
  });

  it('names the connections that are slow or unreachable', () => {
    expect(healthParts(dev, kitConnections())).toEqual([
      { tone: 'success', text: '6 healthy' },
      { tone: 'warning', text: 'cache-redis slow · 1.8 s' },
      { tone: 'danger', text: 'billing-api unreachable' },
    ]);
    expect(healthParts(stage, kitConnections()).map((p) => p.text)).toEqual(['2 healthy', 'billing-api unreachable']);
    expect(healthParts(prod, kitConnections()).map((p) => p.text)).toEqual(['3 healthy']);
    // Many problems of one kind are counted, not listed; unchecked ones say so.
    const many = ['a', 'b', 'c', 'd'].map((n) => conn(n, 'dev', 'unreachable', null, ['x']));
    const x = endpoint('x', { connections: { total: 5, byEnv: { dev: 5 } } });
    expect(healthParts(x, [...many, conn('e', 'dev', 'unknown', null, ['x'])])).toEqual([
      { tone: 'danger', text: '4 unreachable' },
      { tone: 'neutral', text: '1 not checked yet' },
    ]);
  });

  it('falls back to the core’s health counts without the connections', () => {
    expect(healthParts(dev, null).map((p) => p.text)).toEqual(['6 healthy', '1 slow', '1 unreachable']);
    // The list doesn't know all of the endpoint's connections yet (just added): the core's counts again.
    expect(healthParts(dev, kitConnections().slice(0, 3)).map((p) => p.text)).toEqual(['6 healthy', '1 slow', '1 unreachable']);
    expect(healthParts(endpoint('empty'), kitConnections())).toEqual([]);
  });

  it('lists the clients, and claude.ai with the tunnel on a published endpoint', () => {
    expect(clientParts(dev)).toEqual([
      { id: 'claude-desktop', name: 'Claude Desktop' },
      { id: 'codex', name: 'Codex' },
    ]);
    expect(clientParts(stage)).toEqual([{ id: 'claude-ai', name: 'claude.ai' }]);
    expect(tunnelStatus(TUNNEL_OFF)).toEqual({ tone: 'neutral', text: 'Tunnel off', at: at(22, '12:10:00') });
    expect(tunnelStatus({ ...TUNNEL_OFF, state: 'on' }).tone).toBe('warning');
    expect(tunnelStatus(null)).toEqual({ tone: 'neutral', text: 'Tunnel off', at: null });
  });

  it('counts tokens as the token table does, with the one that expires soon', () => {
    // dev: inspector-debug and the helper grant codex-laptop are active, ci-smoke expired; the bridge isn't a token.
    expect(tokensFact(dev, tokens['dev'], 0, NOW, rel)).toEqual({ counts: '2 active · 1 expired', expiring: '1 expires in 3 days', oauth: null, none: null });
    expect(tokensFact(prod, tokens['incident-prod'], 0, NOW, rel)).toEqual({
      counts: null,
      expiring: null,
      oauth: null,
      none: 'None · the Claude Desktop bridge gets short-lived ones',
    });
    expect(tokensFact(stage, [], 1, NOW, rel)).toEqual({ counts: null, expiring: null, oauth: '1 OAuth sign-in you allowed', none: null });
    expect(tokensFact(endpoint('x'), [], 0, NOW, rel).none).toBe('None');
    // Two expiring: the soonest is named.
    const two = [token('a', 'named', 'a', 'active', at(24, '14:30:00')), token('b', 'named', 'b', 'active', at(27, '14:30:00'))];
    expect(tokensFact(endpoint('x'), two, 0, NOW, rel).expiring).toBe('2 expire, the first in 2 days');
  });

  it('falls back to the core’s token counts without the token list', () => {
    expect(tokensFact(dev, null, 0, NOW, rel)).toEqual({ counts: '1 active', expiring: '1 expires within 3 days', oauth: null, none: null });
    expect(tokensFact(prod, null, 0, NOW, rel).none).toBe('None');
    expect(revocableNames(dev, null, NOW)).toBeNull();
    expect(revocableNames(dev, tokens['dev'], NOW)).toEqual(['codex-laptop', 'inspector-debug']);
  });

  it('says what Disable does, in the kit’s words', () => {
    const all = kitEndpoints();
    expect(disableBody(dev, all, tokens['dev'], NOW)).toBe('Claude Desktop and Codex get an error on their next call. Tokens and settings are kept.');
    expect(disableBody(stage, all, [], NOW)).toBe('claude.ai gets an error on its next call, even with the tunnel on. Settings are kept.');
    expect(disableBody(prod, all, tokens['incident-prod'], NOW)).toBe('Claude Desktop gets an error on its next call to it. Settings are kept.');
    expect(disableBody(endpoint('lonely'), all, [], NOW)).toBe('Agents get an error on their next call. Settings are kept.');
  });

  it('says what Delete does, in the kit’s words', () => {
    expect(deleteFacts(dev, tokens['dev'], NOW)).toEqual({
      description:
        'Claude Desktop and Codex lose access at once, its tokens are revoked and the 3 requests waiting for approval are denied. Connections, their secrets and the audit log stay.',
      clients: 'Claude Desktop, Codex',
      tokens: 'codex-laptop, inspector-debug (revoked)',
      waiting: '3 approval requests (denied)',
    });
    expect(deleteFacts(stage, [], NOW)).toEqual({
      description: 'claude.ai loses this connector, and the tunnel stops publishing it. Connections, their secrets and the audit log stay.',
      clients: 'claude.ai, through the tunnel',
      tokens: 'None stored',
      waiting: null,
    });
    expect(deleteFacts(prod, tokens['incident-prod'], NOW)).toEqual({
      description:
        'Claude Desktop loses access to the prod connections at once, and its URL stops answering. Connections, their secrets and the audit log stay.',
      clients: 'Claude Desktop, through its bridge',
      tokens: 'None stored',
      waiting: null,
    });
    expect(deleteFacts(endpoint('x', { approvalsWaiting: 1 }), null, NOW)).toEqual({
      description: 'The request waiting for approval is denied. Connections, their secrets and the audit log stay.',
      clients: 'None',
      tokens: 'None stored',
      waiting: '1 approval request (denied)',
    });
    expect(deleteFacts(endpoint('x'), [], NOW).description).toBe('Its URL stops answering at once. Connections, their secrets and the audit log stay.');
  });

  it('writes the toasts of create, duplicate and delete', () => {
    expect(createdToast('payments-dev', 'empty')).toEqual({ tone: 'success', title: 'Endpoint payments-dev created', body: 'Add its connections and pick tool modes next.' });
    expect(createdToast('ro', 'read-only-dev').body).toBe('Check its connections and tool modes next.');
    expect(duplicatedToast('dev-copy', 'dev')).toEqual({ tone: 'success', title: 'Endpoint dev-copy created', body: 'A copy of dev without tokens or AI app links.' });
    expect(deletedToast('dev', { tokensRevoked: [], rejectedApprovals: 0 }).body).toBe('Its URL stops answering. The audit log keeps every call.');
    expect(deletedToast('dev', { tokensRevoked: ['a', 'b'], rejectedApprovals: 3 }).body).toBe(
      'Its URL stops answering. The audit log keeps every call. 2 tokens revoked, 3 waiting requests denied.',
    );
  });

  it('counts the connections each Start from would take', () => {
    expect(startFromMeta(kitConnections())).toEqual({
      empty: 'Start from scratch',
      'read-only-dev': '6 dev connections',
      'incident-response': '3 prod connections · stricter prod limits',
    });
    expect(startFromMeta([])['read-only-dev']).toBe('No dev connections yet');
    // A connection changed outside GateRook is skipped by the core.
    expect(startFromMeta([{ ...conn('a', 'dev', 'healthy', 1, []), verified: false }])['read-only-dev']).toBe('No dev connections yet');
    expect(startFromMeta(null)['read-only-dev']).toBe('Dev connections');
    // An external MCP server joins an endpoint only when the person adds it.
    expect(startFromMeta([{ ...conn('github-mcp', 'dev', 'healthy', 1, []), type: 'mcp-server' }])['read-only-dev']).toBe('No dev connections yet');
  });

  it("counts the tools each Start from would turn on, from the core's preview (step API)", () => {
    const starts = [
      { startFrom: 'empty' as const, env: 'dev' as const, access: 'standard' as const, connections: [], tools: { read: 0, approval: 0, off: 0 } },
      {
        startFrom: 'read-only-dev' as const,
        env: 'dev' as const,
        access: 'read-only' as const,
        connections: ['app-logs', 'cache-redis', 'dev-cluster', 'dev-db', 'dev-vm', 'stage-db'].slice(0, 6),
        tools: { read: 12, approval: 0, off: 9 },
      },
      { startFrom: 'incident-response' as const, env: 'prod' as const, access: 'standard' as const, connections: ['prod-logs', 'prod-replica', 'prod-vm'], tools: { read: 7, approval: 0, off: 3 } },
    ];
    expect(startFromMeta(kitConnections(), starts)).toEqual({
      empty: 'Start from scratch',
      'read-only-dev': '6 dev connections · 12 tools',
      'incident-response': '3 prod connections · stricter prod limits',
    });
    // The core's count of connections wins over the list's (it skips what it wouldn't wire).
    expect(startFromMeta(null, starts)['read-only-dev']).toBe('6 dev connections · 12 tools');
    expect(startFromMeta(kitConnections(), [{ ...starts[1], connections: [] }])['read-only-dev']).toBe('No dev connections yet');
  });

  it('maps the core’s field errors to the Create dialog', () => {
    expect(createField('/slug')).toBe('slug');
    expect(createField('/description')).toBe('description');
    expect(createField('/startFrom')).toBe('startFrom');
    expect(createField(null)).toBeNull();
    expect(blockedText('tool sql_execute')).toBe('The tool sql_execute was changed outside GateRook. It serves nothing until you review it.');
    expect(andList(['a', 'b', 'c'])).toBe('a, b and c');
  });

  it('writes the compact risk line of a card (risk-callout `review`)', () => {
    // The channel is the tool that can send data out, not the fixed runbooks listed before it.
    expect(channelSource(dev.risk)?.tool).toBe('http_request');
    expect(line(compactLine(dev.risk, true))).toBe('private data + untrusted content + external channel through `http_request`');
    expect(line(compactLine(stage.risk, true))).toBe('private data + untrusted content + external channel through `http_get`, published to the internet');
    expect(line(compactLine(prod.risk, true, 2))).toBe('private data + untrusted content + limited channel (2 fixed runbooks)');
    expect(line(compactLine(prod.risk, true, 1))).toBe('private data + untrusted content + limited channel (1 fixed runbook)');
    // Without `review` (elements/risk-callout.html "Compact, in the endpoint list"): no tool, no count.
    expect(line(compactLine(dev.risk))).toBe('private data + untrusted content + external channel');
    expect(line(compactLine(prod.risk, false, 2))).toBe('private data + untrusted content + limited channel (fixed runbooks)');
    expect(line(compactLine(risk('partial', 'present', []), true))).toBe('private data + untrusted content + external channel');
  });
});
