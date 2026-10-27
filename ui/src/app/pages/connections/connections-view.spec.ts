import { ApprovalItem } from '../../core/api/approvals.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import {
  NO_FILTERS,
  andList,
  countText,
  credentialsView,
  deleteImpact,
  deleteLead,
  deletedToast,
  filtered,
  headerStatuses,
  healthCell,
  healthCounts,
  healthOptions,
  idOf,
  isFiltered,
  isMcpServer,
  lastChecked,
  nextHealth,
  parseEnv,
  parseHealth,
  pendingTitle,
  rowAlert,
  secretLabel,
  secretsSentence,
  sortRows,
  splitMessage,
  testAllToast,
  testToast,
  typeOptions,
  typeText,
} from './connections-view';
import { SECRETS, SHORT_LIVED, at, detail, kitRows, outcome, row, secret } from './connections.fixtures';

/** The page's wording and grouping against the kit page (design/pages/connections.html). */

describe('Connections view: filters and counts', () => {
  it('sorts by name and filters by search, type, environment and health together', () => {
    const rows = sortRows([...kitRows()].reverse());
    expect(rows.map((r) => r.name).slice(0, 3)).toEqual(['app-logs', 'aws-stage', 'billing-api']);
    // Search covers the name, the host and the type (with its badges), case-insensitive.
    expect(filtered(rows, { ...NO_FILTERS, q: 'BILLING' }).map((r) => r.name)).toEqual(['billing-api']);
    expect(filtered(rows, { ...NO_FILTERS, q: '10.40.0' }).map((r) => r.name)).toEqual(['prod-vm']);
    expect(filtered(rows, { ...NO_FILTERS, q: 'redis' }).map((r) => r.name)).toEqual(['cache-redis']);
    expect(filtered(rows, { ...NO_FILTERS, q: 'read replica' }).map((r) => r.name)).toEqual(['prod-replica']);
    expect(filtered(rows, { ...NO_FILTERS, type: 'postgres' }).map((r) => r.name)).toEqual(['dev-db', 'prod-replica', 'stage-db']);
    expect(filtered(rows, { ...NO_FILTERS, type: 'postgres', env: 'prod' }).map((r) => r.name)).toEqual(['prod-replica']);
    expect(filtered(rows, { ...NO_FILTERS, health: 'degraded' }).map((r) => r.name)).toEqual(['cache-redis']);
    expect(filtered(rows, { ...NO_FILTERS, q: 'zzz' })).toEqual([]);
    expect(isFiltered(NO_FILTERS)).toBe(false);
    expect(isFiltered({ ...NO_FILTERS, q: '  ' })).toBe(false);
    expect(isFiltered({ ...NO_FILTERS, env: 'dev' })).toBe(true);
  });

  it('reads filters from links in the kit’s words and the API’s', () => {
    expect(parseHealth('slow')).toBe('degraded');
    expect(parseHealth('degraded')).toBe('degraded');
    expect(parseHealth('Unreachable')).toBe('unreachable');
    expect(parseHealth('nonsense')).toBe('all');
    expect(parseHealth(undefined)).toBe('all');
    expect(parseEnv('prod')).toBe('prod');
    expect(parseEnv('production')).toBe('all');
  });

  it('lists the types the connections have in the kit’s order, with counts', () => {
    const options = typeOptions(kitRows());
    expect(options.map((o) => `${o.label} ${o.count}`)).toEqual([
      'All types 12',
      'PostgreSQL 3',
      'MongoDB 1',
      'Redis 1',
      'SSH 2',
      'Logs 2',
      'Kubernetes 1',
      'AWS 1',
      'HTTP API 1',
    ]);
    expect(options.map((o) => o.icon)).toEqual(['shapes', 'database', 'leaf', 'layers', 'terminal', 'scroll-text', 'ship-wheel', 'cloud', 'globe']);
    // A type picked from a link stays an option while no row has it (after a delete).
    expect(typeOptions([row('a')], 'redis').map((o) => `${o.label} ${o.count}`)).toEqual(['All types 1', 'PostgreSQL 1', 'Redis 0']);
    // A connector the kit doesn't name comes after the known ones, by its own name.
    expect(typeOptions([row('x', { type: 'jira', typeName: 'Jira Service Desk' }), row('a')]).map((o) => o.label)).toEqual(['All types', 'PostgreSQL', 'Jira Service Desk']);
  });

  it('counts health live; "Not checked yet" only while some connection is unchecked', () => {
    const rows = kitRows();
    expect(healthCounts(rows)).toEqual({ healthy: 10, degraded: 1, unreachable: 1, unknown: 0 });
    expect(healthOptions(rows).map((o) => `${o.label} ${o.count}`)).toEqual(['Any health 12', 'Healthy 10', 'Slow 1', 'Unreachable 1']);
    const unchecked = [...rows, row('new-db', { health: { state: 'unknown', latencyMs: null, message: null, checkedAt: null } })];
    expect(healthOptions(unchecked).at(-1)).toEqual({ value: 'unknown', label: 'Not checked yet', icon: null, count: 1 });
    expect(healthOptions(rows, 'unknown').at(-1)?.count).toBe(0);
    expect(headerStatuses(healthCounts(rows))).toEqual([
      { tone: 'success', label: '10 healthy' },
      { tone: 'warning', label: '1 slow' },
      { tone: 'danger', label: '1 unreachable' },
    ]);
    expect(headerStatuses({ healthy: 0, degraded: 0, unreachable: 0, unknown: 12 })).toEqual([{ tone: 'neutral', label: '12 not checked yet' }]);
    expect(lastChecked(rows)).toBe(at('14:29:42'));
    expect(lastChecked([row('a', { health: { state: 'unknown', latencyMs: null, message: null, checkedAt: null } })])).toBeNull();
  });

  it('says how many rows show', () => {
    expect(countText(12, 12)).toBe('12 connections');
    expect(countText(1, 1)).toBe('1 connection');
    expect(countText(3, 12)).toBe('3 of 12 connections');
    expect(countText(0, 12)).toBe('0 of 12 connections');
  });

  it('writes health like BLUEPRINT §7', () => {
    const [, , billing, redis, , devDb] = kitRows();
    expect(healthCell(devDb)).toMatchObject({ tone: 'success', text: 'Healthy · 12 ms' });
    expect(healthCell(redis)).toMatchObject({ tone: 'warning', text: 'Slow · 1.8 s' });
    expect(healthCell(billing)).toMatchObject({ tone: 'danger', text: 'Unreachable' });
    expect(healthCell(row('a', { health: { state: 'unknown', latencyMs: null, message: null, checkedAt: null } }))).toMatchObject({
      tone: 'neutral',
      text: 'Not checked yet',
    });
    expect(idOf('conn-menu', 'dev-db')).toBe('conn-menu-dev-db');
    expect(idOf('conn-menu', 'a b/c')).toBe('conn-menu-a_b_c');
  });
});

describe('Connections view: credentials', () => {
  const of = (name: string) => SECRETS.filter((s) => s.owner === name);
  const rows = Object.fromEntries(kitRows().map((r) => [r.name, r]));

  it('says where the secrets live, never a value', () => {
    expect(credentialsView(rows['dev-db'], of('dev-db'), SHORT_LIVED)).toMatchObject({ icon: 'lock-keyhole', label: 'Encrypted here', caption: null, problems: [] });
    expect(credentialsView(rows['stage-db'], of('stage-db'), SHORT_LIVED)).toMatchObject({
      icon: 'vault',
      label: 'Vault reference',
      caption: 'kv/stage/db#password',
      captionTitle: 'vault://kv/stage/db#password',
      captionCode: true,
    });
    expect(credentialsView(rows['billing-api'], of('billing-api'), SHORT_LIVED)).toMatchObject({
      icon: 'cloud',
      label: 'GCP SM reference',
      caption: null,
      problems: [{ label: 'Webhook secret unresolved', title: "billing-api/webhook-secret points to AWS Secrets Manager, which isn't connected" }],
    });
    // Short-lived credentials the core issued name how; the row alone can't.
    expect(credentialsView(rows['dev-vm'], [], SHORT_LIVED)).toMatchObject({ icon: 'timer', label: 'Short-lived', caption: 'SSH CA certificate', captionTitle: 'vault ssh-client-signer' });
    expect(credentialsView(rows['prod-replica'], [], SHORT_LIVED)).toMatchObject({ label: 'Short-lived', caption: 'Vault dynamic user' });
    // A Vault dynamic secret before anything was issued.
    const dynamic = secret('prod-replica', 'prod-replica/password', { source: 'vault', state: 'short-lived', ref: 'vault://database/creds/x' });
    expect(credentialsView(rows['prod-replica'], [dynamic], [])).toMatchObject({ label: 'Short-lived', caption: 'Vault dynamic credentials' });
    // Nothing stored and nothing issued (impersonation set in the config).
    expect(credentialsView(rows['prod-logs'], [], [])).toMatchObject({ icon: 'circle-dashed', label: 'Nothing stored here', muted: true });
    // Without the secrets table (Secrets didn't answer), the row's source alone.
    expect(credentialsView(rows['stage-db'])).toMatchObject({ label: 'Vault reference', caption: null });
  });

  it('warns about secrets that can’t be used now', () => {
    const lost = secret('dev-db', 'dev-db/password', { state: 'needs-value' });
    expect(credentialsView(rows['dev-db'], [lost]).problems).toEqual([{ label: 'Password needs a value', title: "dev-db/password can't be read with this master key. Replace it in Secrets." }]);
    expect(secretLabel({ name: 'aws-stage/secret-access-key', field: '/x' })).toBe('Secret access key');
    expect(secretLabel({ name: '', field: '/credentials/apiKey' })).toBe('Api key');
  });
});

describe('Connections view: the alert under a row', () => {
  it('splits the connector’s message into what happened and what to do', () => {
    expect(splitMessage('Connection refused. Check the stage VPN or the billing service, then test again.')).toEqual({
      what: 'Connection refused',
      todo: 'Check the stage VPN or the billing service, then test again.',
    });
    // Hosts and decimals don't end a sentence.
    expect(splitMessage("Can't reach db.dev.internal:5432 after 1.8 s. Check the VPN.")).toEqual({ what: "Can't reach db.dev.internal:5432 after 1.8 s", todo: 'Check the VPN.' });
    expect(splitMessage("PostgreSQL connections can't be tested yet: the connector is not implemented.")).toEqual({
      what: "PostgreSQL connections can't be tested yet: the connector is not implemented",
      todo: null,
    });
    expect(splitMessage(null)).toEqual({ what: null, todo: null });
  });

  it('shows the error of an unreachable row, and a row changed outside GateRook', () => {
    const [, , billing] = kitRows();
    expect(rowAlert(billing)).toEqual({
      kind: 'unreachable',
      tone: 'danger',
      icon: 'circle-alert',
      title: 'Connection refused',
      body: 'Check the stage VPN or the billing service, then test again.',
    });
    expect(rowAlert(row('x', { health: { state: 'unreachable', latencyMs: null, message: null, checkedAt: null } }))).toMatchObject({
      title: 'Unreachable',
      body: 'Check the host or the network, then test again.',
    });
    expect(rowAlert(row('ok'))).toBeNull();
    // Changed outside wins over its health: it stays off until reviewed.
    expect(rowAlert({ ...billing, verified: false })).toMatchObject({ kind: 'integrity', tone: 'warning', title: 'Changed outside GateRook' });
  });

  it('says since when a row is unreachable when the core tells (kit: "Connection refused since 11:42")', () => {
    const [, , billing] = kitRows();
    const since = { ...billing, health: { ...billing.health, since: '2026-10-22T10:42:00Z' } };
    expect(rowAlert(since, () => '11:42')!.title).toBe('Connection refused since 11:42');
    // Without the clock or the field (a core before step API), the connector's words alone.
    expect(rowAlert(since)!.title).toBe('Connection refused');
    expect(rowAlert(billing, () => '11:42')!.title).toBe('Connection refused');
  });

  it("keeps since when an event repeats the state, and starts it at the check that changed it", () => {
    const before = { state: 'unreachable', latencyMs: null, message: 'x', checkedAt: 'a', since: 's0' };
    expect(nextHealth(before, { state: 'unreachable', latencyMs: null, message: 'y', checkedAt: 'b' }).since).toBe('s0');
    expect(nextHealth(before, { state: 'healthy', latencyMs: 12, message: null, checkedAt: 'c' }).since).toBe('c');
    expect(nextHealth(null, { state: 'healthy', latencyMs: 12, message: null, checkedAt: 'c' }).since).toBe('c');
  });

  it("writes the Type cell in the core's words, and leaves the external MCP servers to Connectors", () => {
    expect(typeText(row('dev-db', { typeLabel: 'PostgreSQL 16' }))).toBe('PostgreSQL 16');
    expect(typeText(row('dev-db'))).toBe('PostgreSQL');
    expect(filtered([row('dev-db', { typeLabel: 'PostgreSQL 16' })], { ...NO_FILTERS, q: 'postgresql 16' })).toHaveLength(1);
    expect(isMcpServer(row('github-mcp', { type: 'mcp-server' }))).toBe(true);
    expect(isMcpServer(row('dev-db'))).toBe(false);
  });
});

describe('Connections view: delete', () => {
  const endpoints = [
    { slug: 'dev', clients: ['claude-desktop', 'codex'] },
    { slug: 'stage-readonly', clients: ['claude-ai'] },
  ] as unknown as EndpointSummary[];
  const pending = [
    { id: 'ap_1', tool: 'sql_execute', connection: 'dev-db', client: 'codex', tokenLabel: 'codex-laptop', status: 'pending' },
    { id: 'ap_2', tool: 'http_request', connection: 'billing-api', client: 'codex', tokenLabel: 'codex-laptop', status: 'pending' },
  ] as unknown as ApprovalItem[];

  it('lists what stops working like the kit’s dev-db confirmation', () => {
    const devDb = kitRows()[5];
    const impact = deleteImpact({
      row: devDb,
      detail: detail('dev-db'),
      toolOrder: ['sql_list_schemas', 'sql_describe_table', 'sql_query', 'sql_execute'],
      endpoints,
      pending,
    });
    expect(impact).toEqual({
      endpoints: [{ slug: 'dev', tools: ['sql_list_schemas', 'sql_describe_table', 'sql_query', 'sql_execute'], clients: 'Claude Desktop and Codex' }],
      pending: [{ tool: 'sql_execute', client: 'Codex' }],
      secrets: 'Its encrypted password (version 3) is destroyed.',
    });
    expect(deleteLead(devDb)).toBe('Agents lose it on their next call.');
    expect(pendingTitle(1)).toBe('1 pending approval is denied');
    expect(pendingTitle(2)).toBe('2 pending approvals are denied');
  });

  it('leaves out what the core didn’t answer and doesn’t list Off tools', () => {
    const devDb = kitRows()[5];
    const offOnly = detail('dev-db', { usedBy: [{ tool: 'sql_query', endpoint: 'dev', mode: 'OFF' }] });
    expect(deleteImpact({ row: devDb, detail: offOnly, toolOrder: null, endpoints: null, pending: null })).toEqual({
      endpoints: [{ slug: 'dev', tools: [], clients: null }],
      pending: [],
      secrets: 'Its encrypted password (version 3) is destroyed.',
    });
    expect(deleteImpact({ row: devDb, detail: null, toolOrder: null, endpoints: null, pending: null })).toEqual({
      endpoints: [{ slug: 'dev', tools: [], clients: null }],
      pending: [],
      secrets: null,
    });
    expect(deleteLead(row('lonely', { usedBy: [] }))).toBe('No endpoint uses it, so no agent loses a tool.');
  });

  it('says what happens to the secrets', () => {
    const s = (name: string, source: string, state = 'stored', version: number | null = 1) => ({ field: '/x', secretId: name, name, state, version, source });
    expect(secretsSentence([])).toBe('Nothing is stored here.');
    expect(secretsSentence([s('app-logs/token', 'local')])).toBe('Its encrypted token (version 1) is destroyed.');
    expect(secretsSentence([s('a/password', 'local'), s('a/token', 'local')])).toBe('Its encrypted password and token are destroyed.');
    expect(secretsSentence([s('stage-db/password', 'vault', 'reference', 7)])).toBe('The Vault reference is removed; the secret itself stays in Vault.');
    expect(secretsSentence([s('billing-api/token', 'gcp-sm', 'reference'), s('billing-api/webhook-secret', 'aws-sm', 'not-connected', null)])).toBe(
      'Its two references are removed; the secrets stay in GCP Secret Manager and AWS Secrets Manager.',
    );
    expect(andList(['a', 'b', 'c'])).toBe('a, b and c');
  });

  it('confirms what the core did', () => {
    const [, aws] = kitRows();
    expect(deletedToast('aws-stage', aws, { stoppedTools: [], rejectedApprovals: 0, templates: [], clients: [], secretsDestroyed: [] })).toEqual({
      title: 'Connection aws-stage deleted',
      body: 'Removed from dev and stage-readonly.',
    });
    expect(deletedToast('x', row('x', { usedBy: [] }), { stoppedTools: [], rejectedApprovals: 2, templates: ['orders_by_status'], clients: [], secretsDestroyed: [] }).body).toBe(
      "It was on no endpoint. 2 pending approvals denied. 1 template can't run until you pick another connection.",
    );
  });
});

describe('Connections view: test toasts', () => {
  it('words one test like the kit', () => {
    expect(testToast('dev-db', outcome())).toEqual({ tone: 'success', title: 'dev-db is healthy', body: 'Connected in 12 ms.' });
    expect(testToast('billing-api', outcome({ ok: false, error: { code: 'refused', message: 'Connection refused. Check the VPN.', technical: null } }))).toEqual({
      tone: 'danger',
      title: 'billing-api is unreachable',
      body: 'Connection refused. Check the VPN.',
    });
    expect(testToast('dev-vm', outcome({ ok: false, needsConfirmation: { kind: 'host-key', text: 'The host key changed.', data: null } }))).toMatchObject({
      tone: 'warning',
      title: 'dev-vm needs your confirmation',
    });
  });

  it('sums up Test all, and says when some didn’t answer', () => {
    expect(testAllToast([...Array(10).fill('healthy'), 'degraded', 'unreachable'], 12)).toEqual({
      tone: 'warning',
      title: '12 connections tested',
      body: '10 healthy, 1 slow, 1 unreachable.',
    });
    expect(testAllToast(['healthy', 'healthy'], 2)).toEqual({ tone: 'success', title: '2 connections tested', body: '2 healthy, 0 slow, 0 unreachable.' });
    expect(testAllToast(['healthy'], 3)).toEqual({
      tone: 'warning',
      title: '1 of 3 connections tested',
      body: "1 healthy, 0 slow, 0 unreachable. 2 didn't answer in time; their health shows the last check.",
    });
  });
});
