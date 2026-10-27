import { ApiError } from '../../core/api/api-error';
import { splitSlug } from '../../kit/elements/risk-callout/split-dialog';
import { toolPolicyGroups } from '../../kit/elements/tool-policy-list/tool-policy-view';
import {
  activeTokens,
  addCandidates,
  addedToast,
  addressOf,
  andList,
  busiestMeter,
  clientsNote,
  clientsOfRevision,
  connectedToast,
  connectionViews,
  copySlug,
  deleteFacts,
  disableBody,
  disconnectedToast,
  effectiveLimits,
  endpointClients,
  envCounts,
  exampleToken,
  flowText,
  headerDescription,
  instructionsCount,
  limitHints,
  limitText,
  limitsLine,
  parseLimit,
  parseTab,
  policyEnvs,
  primaryEnv,
  registrationText,
  removeText,
  resourceMetadataUrl,
  revisionsByClient,
  revokedToast,
  sentence,
  servedRevisions,
  signInText,
  slugError,
  sortTokens,
  splitClients,
  splitOptions,
  splitToast,
  statusLook,
  tabCounts,
  toolsCell,
  unreachableAlerts,
  wiringClients,
  withTargets,
} from './endpoint-detail-view';
import { clientsView, connectionRows, groups, policies, preview, risk, settings, summary, token, tokens, wired } from './endpoint-detail.fixtures';

describe('endpoint detail view', () => {
  describe('header', () => {
    it('reads the tab from ?tab=, tools by default', () => {
      expect(parseTab('tokens')).toBe('tokens');
      expect(parseTab('nope')).toBe('tools');
      expect(parseTab(null)).toBe('tools');
    });

    it('says what the endpoint is for and what its access means (kit)', () => {
      expect(headerDescription(summary(), 8)).toBe('Local development on 8 connections. Agents read freely; anything that changes data waits for your approval.');
      expect(headerDescription(summary({ status: 'disabled' }), 1)).toBe(
        'Local development on 1 connection. Disabled: every call gets an error until you enable it; tokens and settings are kept.',
      );
      expect(headerDescription(summary({ access: 'read-only', description: '' }), 0)).toBe(
        'An endpoint with no connections yet. Read only: agents read, and no tool that changes data is offered.',
      );
    });

    it('shows Running or Disabled, the served revisions oldest first, and the sign-in badge', () => {
      expect(statusLook(summary())).toEqual({ tone: 'success', icon: 'circle-check', text: 'Running' });
      expect(statusLook(summary({ status: 'disabled' })).text).toBe('Disabled');
      expect(servedRevisions(settings())).toEqual(['2025-11-25', '2026-07-28']);
      expect(servedRevisions(null)).toEqual([]);
      expect(signInText(false)).toBe('Tokens · OAuth off');
      expect(signInText(true)).toBe('Tokens · OAuth on');
    });

    it('takes the address and the resource metadata from the URL', () => {
      expect(addressOf('http://127.0.0.1:7465/mcp/dev')).toBe('127.0.0.1:7465');
      expect(resourceMetadataUrl('http://127.0.0.1:7465/mcp/dev')).toBe('http://127.0.0.1:7465/.well-known/oauth-protected-resource/mcp/dev');
      expect(registrationText('cimd')).toBe('CIMD');
      expect(registrationText('cimd+dcr')).toBe('CIMD, then dynamic registration');
    });
  });

  describe('wiring', () => {
    it('lists the linked clients, then the installed ones; not installed are left out', () => {
      const cards = endpointClients(clientsView(), 'dev');
      expect(cards.map((c) => c.client)).toEqual(['claude-desktop', 'codex', 'cursor']);
      expect(wiringClients(cards, 'dev')).toEqual([
        { client: 'claude-desktop', status: 'connected' },
        { client: 'codex', status: 'connected' },
        { client: 'cursor', status: 'detected' },
      ]);
      expect(endpointClients(null, 'dev')).toEqual([]);
    });

    it('says tokens are issued on the fly only when every client uses the bridge or the helper', () => {
      const cards = endpointClients(clientsView(), 'dev');
      expect(clientsNote(cards, 'dev')).toBe('Tokens issued on the fly, none stored');
      const withToken = cards.map((c) => (c.client === 'codex' ? { ...c, links: c.links.map((l) => ({ ...l, method: 'token' as const })) } : c));
      expect(clientsNote(withToken, 'dev')).toBe('Each signs in with its own token');
    });

    it('names the policy most connections follow, and the stricter on a tie', () => {
      expect(primaryEnv(wired(), 'stage')).toBe('dev');
      expect(primaryEnv([], 'stage')).toBe('stage');
      expect(primaryEnv(wired().slice(5, 7), 'dev')).toBe('stage');
      expect(policyEnvs(wired(), 'dev')).toEqual(['dev', 'stage']);
    });

    it('applies the stricter of the policy and the endpoint for each limit', () => {
      const sections = policies().dev.sections;
      expect(effectiveLimits(sections, {})).toEqual({ perToken: 120, perTool: 30, maxResponseKb: 512 });
      expect(effectiveLimits(sections, { rateLimitPerToken: 60, maxResponseKb: 1024 })).toEqual({ perToken: 60, perTool: 30, maxResponseKb: 512 });
      expect(effectiveLimits(null, { rateLimitPerTool: 5 })).toEqual({ perToken: null, perTool: 5, maxResponseKb: null });
      expect(limitsLine(effectiveLimits(sections, {}))).toBe('120 calls per min per token · 512 KB per result');
    });

    it('describes the wiring in words for screen readers', () => {
      expect(flowText('dev', endpointClients(clientsView(), 'dev'), wired())).toBe(
        'Claude Desktop and Codex call endpoint dev with short-lived tokens issued on the fly; Cursor is installed but not connected. dev checks each call against its tool modes and the dev and stage rules, then runs the tool on one of 8 connections, which injects the secret.',
      );
      expect(flowText('new', [], [])).toBe('No AI app calls endpoint new yet. new checks each call against its tool modes and the dev rules.');
    });
  });

  describe('tabs', () => {
    it('counts tools, connections, active tokens without the bridge, and connected clients', () => {
      const cards = endpointClients(clientsView(), 'dev');
      expect(tabCounts(summary(), 8, tokens(), cards, 'dev')).toEqual({ tools: 22, connections: 8, tokens: 2, clients: 2 });
      expect(tabCounts(summary(), 8, null, cards, 'dev').tokens).toBe(2);
      expect(activeTokens(tokens())).toBe(2);
    });

    it('orders tokens like the kit: active (bridge, accesses, named), expired, revoked', () => {
      expect(sortTokens(tokens()).map((t) => t.name)).toEqual(['claude-desktop', 'codex-laptop', 'inspector-debug', 'ci-smoke', 'old-claude']);
      expect(exampleToken(tokens())).toBe('codex-laptop');
      expect(exampleToken(null)).toBeNull();
    });

    it('adds what each connection reaches to its group of tools', () => {
      const g = withTargets(toolPolicyGroups(groups(), wired()), connectionRows());
      expect(g[0].detail).toBe('PostgreSQL · dev-db.internal');
      expect(g.at(-1)?.detail).toBe('Answer from the gateway, never from a resource');
    });
  });

  describe('connections', () => {
    it('counts per environment and tools per mode', () => {
      expect(envCounts(wired())).toEqual([
        { env: 'dev', count: 6 },
        { env: 'stage', count: 2 },
      ]);
      expect(toolsCell({ read: 1, approval: 1, off: 2 })).toEqual({ total: 4, modes: '1 read only, 1 approval, 2 off' });
    });

    it('takes host and the latest health from the connections list', () => {
      const views = connectionViews(wired(), connectionRows());
      const db = views.find((v) => v.c.name === 'dev-db')!;
      expect(db.target).toBe('dev-db.internal');
      expect(db.health.text).toBe('Healthy · 12 ms');
      expect(views.find((v) => v.c.name === 'cache-redis')!.health.tone).toBe('warning');
      expect(connectionViews(wired(), null)[0].target).toBe('');
    });

    it('raises an alert per unreachable connection with the tools that fail', () => {
      const alerts = unreachableAlerts(connectionViews(wired(), connectionRows()), groups());
      expect(alerts).toEqual([
        {
          name: 'billing-api',
          env: 'stage',
          target: 'https://billing.stage.acme.dev',
          since: null,
          checkedAt: connectionRows()[7].health.checkedAt,
          tools: ['http_request'],
          message: 'connect timed out',
        },
      ]);
      // Since when it is unreachable, when the core says (step API).
      const rows = connectionRows().map((r) => (r.name === 'billing-api' ? { ...r, health: { ...r.health, since: '2026-10-22T09:42:00Z' } } : r));
      expect(unreachableAlerts(connectionViews(wired(), rows), groups())[0].since).toBe('2026-10-22T09:42:00Z');
    });

    it('offers the connections not on the endpoint, the refused ones last with the core’s reason', () => {
      const previews = new Map([
        ['stage-db', preview('stage-db')],
        ['prod-replica', preview('prod-replica', { refusal: 'prod-replica is a prod connection: it goes only on a prod endpoint, and dev is stage' })],
      ]);
      const c = addCandidates(connectionRows(), wired(), previews);
      expect(c.map((x) => x.row.name)).toEqual(['stage-db', 'prod-replica']);
      expect(c[0].hint).toBe('PostgreSQL · secret in Vault · its tools start as 3 read only, 1 approval');
      expect(c[1].refusal).toBe('prod-replica is a prod connection: it goes only on a prod endpoint, and dev is stage.');
      expect(c[1].hint).toBe('PostgreSQL · read replica · short-lived credentials');
      // Before the previews answer: by name, without modes.
      expect(addCandidates(connectionRows(), wired(), new Map()).map((x) => [x.row.name, x.refusal])).toEqual([
        ['prod-replica', null],
        ['stage-db', null],
      ]);
    });

    it('says what Remove takes away', () => {
      expect(removeText(wired()[0], 'dev')).toBe('Its 4 tools disappear from dev on the next call, with their modes and limits. The connection, its secret and its audit history stay.');
      expect(removeText({ ...wired()[0], tools: { read: 1, approval: 0, off: 0 } }, 'dev')).toMatch(/^Its tool disappears from dev on the next call, with its mode and limits\./);
    });

    it('turns the core’s fragments into sentences', () => {
      expect(sentence('goes only on a prod endpoint')).toBe('goes only on a prod endpoint.');
      expect(sentence('Done.')).toBe('Done.');
      expect(sentence('')).toBe('');
    });
  });

  describe('delete, disable, duplicate', () => {
    it('lists who loses access and what is revoked and denied (kit)', () => {
      const f = deleteFacts(summary(), endpointClients(clientsView(), 'dev'), tokens());
      expect(f.description).toBe(
        'Claude Desktop and Codex lose access at once, its tokens are revoked and the 3 requests waiting for approval are denied. Connections, their secrets and the audit log stay.',
      );
      expect(f.clients).toBe('Claude Desktop, Codex');
      expect(f.tokens).toBe('codex-laptop, inspector-debug (revoked)');
      expect(f.waiting).toBe('3 approval requests (denied)');
      expect(deleteFacts(summary({ approvalsWaiting: 0 }), [], []).waiting).toBeNull();
      expect(deleteFacts(summary(), [], null).tokens).toBe('Unknown');
    });

    it('says which clients get an error after Disable', () => {
      expect(disableBody(endpointClients(clientsView(), 'dev'), 'dev', true)).toBe('Claude Desktop and Codex get an error on their next call. Tokens and settings are kept.');
      expect(disableBody([], 'dev', false)).toBe('Agents get an error on their next call. Settings are kept.');
    });

    it('checks a slug and suggests a free copy', () => {
      expect(slugError('dev-copy')).toBeNull();
      expect(slugError(' ')).toBe('Name the endpoint, for example dev-copy.');
      expect(slugError('Dev')).toMatch(/^Use lowercase letters/);
      expect(slugError('a'.repeat(41))).toBe('Use at most 40 characters.');
      expect(copySlug('dev', ['dev'])).toBe('dev-copy');
      expect(copySlug('dev', ['dev-copy', 'dev-copy-2'])).toBe('dev-copy-3');
    });
  });

  describe('split', () => {
    it('offers the full channel first, the runbooks-only one after it, and recommends the first', () => {
      const options = splitOptions(risk(), groups(), wired(), 'dev');
      expect(options.map((o) => [o.connection, o.ingredient, o.recommended])).toEqual([
        ['billing-api', 'externalChannel', true],
        ['dev-vm', 'externalChannel', false],
        ['app-logs', 'untrustedContent', false],
        ['dev-db', 'privateData', false],
      ]);
      expect(options[0].tools).toEqual(['http_request']);
      expect(options[0].meta).toBe('Recommended · the widest way out; dev-vm stays');
      expect(options[0].effect).toBe('Agents on dev can no longer send data out through it.');
      expect(options[2].meta).toBe('Leaves private data and an external channel');
    });

    it('suggests the new endpoint’s name from what moves (kit: dev-billing, dev-logs)', () => {
      expect(splitSlug('dev', 'billing-api')).toBe('dev-billing');
      expect(splitSlug('dev', 'app-logs')).toBe('dev-logs');
      expect(splitSlug('dev', 'dev-vm')).toBe('dev-split');
      expect(splitSlug('a'.repeat(38), 'billing-api').length).toBeLessThanOrEqual(40);
    });

    it('pre-checks the clients that called the moving connection today', () => {
      const c = splitClients(endpointClients(clientsView(), 'dev'), 'dev', new Map([['codex', 'http_request']]));
      expect(c).toEqual([
        { id: 'claude-desktop', name: 'Claude Desktop', note: null, checked: false },
        { id: 'codex', name: 'Codex', note: 'called http_request today', checked: true },
      ]);
    });

    it('names the new endpoint, what moved and who connects next', () => {
      expect(splitToast('dev-billing', 'billing-api', 'dev', risk({ state: 'none' }), ['codex'])).toEqual({
        tone: 'success',
        title: 'Endpoint dev-billing created',
        body: 'billing-api and its tools moved from dev. dev has no risky combination now. Next: connect Codex to it.',
      });
      expect(splitToast('dev-logs', 'app-logs', 'dev', risk(), []).body).toBe('app-logs and its tools moved from dev. dev still has a risky combination.');
    });
  });

  describe('settings', () => {
    it('counts the instructions and reads limit fields', () => {
      expect(instructionsCount(765, 4000)).toBe('765 / 4,000');
      expect(parseLimit('')).toEqual({ value: null, error: null });
      expect(parseLimit('1,000')).toEqual({ value: 1000, error: null });
      expect(parseLimit('0').error).toMatch(/whole number/);
      expect(parseLimit('abc').error).toMatch(/whole number/);
      expect(limitText({ rateLimitPerToken: 60 }, 'rateLimitPerToken')).toBe('60');
      expect(limitText({}, 'maxResponseKb')).toBe('');
    });

    it('explains the empty limit fields from the policy (kit)', () => {
      const h = limitHints({ perToken: 120, perTool: 30, maxResponseKb: 512 }, 'dev', 'dev', 'codex-laptop');
      expect(h.size).toBe('Empty: 512 KB from the dev policy. A larger value has no effect.');
      expect(h.perToken).toBe('Empty: 120 from the dev policy. For each token on dev such as codex-laptop.');
      expect(h.perTool).toBe('Empty: 30 from the dev policy. For one tool and one token. Stops an agent stuck in a loop.');
      expect(limitHints({ perToken: null, perTool: null, maxResponseKb: null }, 'prod', 'x', null).perToken).toBe('Empty: no limit in the prod policy. For each token on x.');
    });

    it('lists the revision each client used, and who a revision would break', () => {
      expect(revisionsByClient(settings().revisions)).toEqual([
        { client: 'Claude Desktop', revisions: '2025-11-25' },
        { client: 'Codex', revisions: '2026-07-28' },
      ]);
      expect(clientsOfRevision(settings().revisions, '2025-11-25')).toEqual(['Claude Desktop']);
      expect(clientsOfRevision(undefined, '2025-11-25')).toEqual([]);
    });

    it('meters the busiest token of this endpoint against the per-token limit', () => {
      const m = busiestMeter({ name: 'codex-laptop', calls: 14 }, tokens(), 120, 20, 'dev')!;
      expect(m).toMatchObject({ label: 'Busiest token now · codex-laptop', calls: 14, level: 'ok', value: '14 / 120 calls in the last minute' });
      expect(m.valueText).toBe('14 of 120 calls in the last minute, well under the limit');
      expect(busiestMeter({ name: 'other', calls: 90 }, tokens(), 120, 100, 'dev')).toMatchObject({ label: 'Calls on dev now', calls: 100, level: 'warn' });
      expect(busiestMeter(null, tokens(), 120, 0, 'dev')?.label).toBe('Busiest token now · no calls in the last minute');
      expect(busiestMeter({ name: 'codex-laptop', calls: 119 }, tokens(), 120, 0, 'dev')?.level).toBe('critical');
      expect(busiestMeter(null, null, null, 0, 'dev')).toBeNull();
    });
  });

  describe('toasts', () => {
    it('confirms a revoke with who loses access', () => {
      expect(revokedToast(tokens()[4], 0)).toEqual({ tone: 'success', title: 'codex-laptop revoked', body: 'Codex loses access on its next call.' });
      expect(revokedToast(token('inspector-debug'), 2)).toEqual({
        tone: 'success',
        title: 'Token inspector-debug revoked',
        body: 'Apps using it lose access on their next call. 2 requests waiting for approval were denied.',
      });
    });

    it('confirms what Add connections added and what the core refused', () => {
      const stage = addCandidates(connectionRows(), wired(), new Map([['stage-db', preview('stage-db')]])).find((c) => c.row.name === 'stage-db')!;
      expect(addedToast('dev', [stage], [])).toEqual({ tone: 'success', title: 'stage-db added to dev', body: 'Its tools start as 3 read only, 1 approval; change them under Tools & policies.' });
      expect(addedToast('dev', [stage], [{ name: 'prod-replica', reason: 'Goes only on a prod endpoint.' }]).tone).toBe('warning');
      expect(addedToast('dev', [], [{ name: 'x', reason: 'No.' }])).toEqual({ tone: 'danger', title: 'Nothing was added to dev', body: 'No.' });
    });

    it('confirms Connect and Disconnect', () => {
      expect(connectedToast('Cursor', 'dev', ['Cursor loads the gateway server now.']).body).toBe('Cursor loads the gateway server now.');
      expect(connectedToast('Cursor', 'dev', []).title).toBe('Cursor connected to dev');
      expect(disconnectedToast('Claude Desktop', 'dev', ['incident-prod'], true, 0)).toEqual({
        tone: 'success',
        title: 'Claude Desktop disconnected from dev',
        body: 'incident-prod still works through the bridge.',
      });
      expect(disconnectedToast('Codex', 'dev', [], false, 1).body).toBe('The entry is removed and its access to dev is revoked. 1 request waiting for approval was denied.');
    });
  });

  it('joins names like a sentence', () => {
    expect(andList([])).toBe('');
    expect(andList(['a', 'b', 'c'])).toBe('a, b and c');
  });

  it('keeps ApiError text untouched for the page (sanity)', () => {
    expect(new ApiError('conflict', 409, { detail: 'Changed meanwhile.' }).detail).toBe('Changed meanwhile.');
  });
});
