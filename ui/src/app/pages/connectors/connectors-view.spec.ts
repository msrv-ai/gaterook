import { ConnectionDetail, ConnectionRow } from '../../core/api/connections.api';
import { InstalledConnector } from '../../core/api/connector-types.api';
import { EndpointSummary, ToolGroup, ToolRow } from '../../core/api/endpoints.api';
import { CatalogEntry, CatalogIndex } from '../../core/api/plugins.api';
import { moduleCard, moduleList } from './connectors.fixtures';
import {
  catalogOffer,
  catalogViews,
  compareVersions,
  connectionsQuery,
  defaultToolPrefix,
  endpointFor,
  formatArgv,
  formatBytes,
  installedCountText,
  installedViews,
  joinAnd,
  listsCheckToast,
  matches,
  mcpServerView,
  moduleCatalogViews,
  npmPackage,
  onEndpointsText,
  onThisComputer,
  pluginUpdate,
  processStatus,
  serverActivityQuery,
  serverPaths,
  serverStatus,
  signInHost,
  signInText,
  templateEndpoints,
  templateModes,
  templateSignature,
  templateToolNames,
  tokenizeCommand,
  toolLiterals,
} from './connectors-view';

// Fixtures in the shapes the core answers (§6), with the kit page's names and numbers.

function type(over: Partial<InstalledConnector> & Pick<InstalledConnector, 'id' | 'name'>): InstalledConnector {
  return {
    kind: 'built-in',
    icon: '',
    group: 'Databases',
    description: `${over.name} connector.`,
    version: '0.4.0',
    signed: null,
    tools: 6,
    connections: 0,
    enabled: true,
    verified: true,
    state: 'enabled',
    process: null,
    variants: [],
    ...over,
  };
}

function connection(name: string, typeId: string, over: Partial<ConnectionRow> = {}): ConnectionRow {
  return {
    name,
    type: typeId,
    typeName: typeId,
    env: 'dev',
    host: null,
    badges: [],
    secretSource: null,
    health: { state: 'healthy', latencyMs: 3, message: null, checkedAt: null },
    usedBy: [],
    verified: true,
    version: 1,
    ...over,
  };
}

function entry(over: Partial<CatalogEntry> & Pick<CatalogEntry, 'id' | 'kind' | 'name'>): CatalogEntry {
  return { version: '1.0.0', signed: true, description: '', tools: 3, installed: null, ...over };
}

function catalog(entries: CatalogEntry[]): CatalogIndex {
  return { checkedAt: '2026-10-25T08:00:00Z', source: 'https://catalog.example', offline: false, signature: null, entries };
}

function tool(toolKey: string, name: string, mode: ToolRow['mode']): ToolRow {
  return {
    toolKey,
    name,
    annotations: {} as ToolRow['annotations'],
    declaredBy: null,
    mode,
    storedMode: mode,
    readOnlyAllowed: true,
    reason: null,
    readOnlyConfirmed: false,
    definitionHash: null,
    overrides: null,
    version: 1,
  };
}

function detail(name: string, config: ConnectionDetail['config'], secrets: ConnectionDetail['secrets'] = []): ConnectionDetail {
  return {
    name,
    type: 'mcp-server',
    typeName: 'MCP server',
    env: 'dev',
    config,
    version: 1,
    secrets,
    health: { state: 'unknown', latencyMs: null, message: null, checkedAt: null },
    spark24h: [],
    usedBy: [],
    verified: true,
  };
}

describe('connectors view: words and numbers', () => {
  it('compares versions by number, then by text', () => {
    expect(compareVersions('0.4.0', '0.3.2')).toBeGreaterThan(0);
    expect(compareVersions('v1.2.0', '1.2.0')).toBe(0);
    expect(compareVersions('1.10.0', '1.9.9')).toBeGreaterThan(0);
    expect(compareVersions('1.2', '1.2.0')).toBe(0);
    expect(compareVersions('1.0.0-beta', '1.0.0-alpha')).toBeGreaterThan(0);
  });

  it('writes sizes and lists as the kit does', () => {
    expect(formatBytes(512)).toBe('512 bytes');
    expect(formatBytes(1)).toBe('1 byte');
    expect(formatBytes(820 * 1024)).toBe('820 KB');
    expect(formatBytes(1.4 * 1024 * 1024)).toBe('1.4 MB');
    expect(formatBytes(48 * 1024 * 1024)).toBe('48 MB');
    expect(formatBytes(null)).toBe('');
    expect(joinAnd(['a'])).toBe('a');
    expect(joinAnd(['a', 'b'])).toBe('a and b');
    expect(joinAnd(['a', 'b', 'c'])).toBe('a, b and c');
    expect(connectionsQuery(['dev-db', 'stage-db'])).toBe('connection:dev-db connection:stage-db');
    expect(connectionsQuery([])).toBeNull();
  });

  it('names process states in the kit’s words and tones', () => {
    expect(processStatus({ state: 'running', rssMb: 54 })).toEqual({ tone: 'success', text: 'Running · 54 MB' });
    expect(processStatus({ state: 'RUNNING', rssMb: null })).toEqual({ tone: 'success', text: 'Running' });
    expect(processStatus({ state: 'stopped', rssMb: null })).toEqual({ tone: 'neutral', text: 'Sleeping' });
    expect(processStatus({ state: 'backoff', rssMb: null })).toEqual({ tone: 'warning', text: 'Restarting' });
    expect(processStatus({ state: 'failed', rssMb: null })).toEqual({ tone: 'danger', text: 'Failed' });
    expect(processStatus({ state: 'draining', rssMb: null })?.text).toBe('Finishing calls');
    expect(processStatus({ state: 'half-open', rssMb: null })?.text).toBe('Half open');
    expect(processStatus(null)).toBeNull();
    expect(serverStatus(null)).toEqual({ tone: 'neutral', text: 'Not started yet' });
    expect(serverStatus('starting')).toEqual({ tone: 'info', text: 'Starting' });
  });

  it('matches every word of the query', () => {
    expect(matches('postgresql built-in sql databases', 'sql post')).toBe(true);
    expect(matches('postgresql built-in', 'mongo')).toBe(false);
    expect(matches('anything', '   ')).toBe(true);
  });
});

describe('connectors view: Installed', () => {
  const postgres = type({ id: 'postgres', name: 'PostgreSQL', connections: 2 });
  const redis = type({ id: 'redis', name: 'Redis', enabled: false, state: 'disabled' });
  const rabbit = type({
    id: 'rabbitmq',
    name: 'RabbitMQ',
    kind: 'plugin',
    version: '0.3.0',
    signed: false,
    tools: 5,
    process: { state: 'running', pid: 4242, rssMb: 54, detail: null },
  });
  const conns = [connection('dev-db', 'postgres'), connection('stage-db', 'postgres', { env: 'stage' })];

  it('lists built-ins, then plugins, then MCP servers with links to their connections', () => {
    const server = mcpServerView(connection('github-mcp', 'mcp-server'), null, null, 12, null, null);
    const views = installedViews([rabbit, postgres, redis], conns, null, [server]);
    expect(views.map((v) => v.key)).toEqual(['type:postgres', 'type:redis', 'plugin:rabbitmq', 'mcp:github-mcp']);
    const pg = views[0];
    expect(pg.card.action).toBe('add');
    expect(pg.activityQuery).toBe('connection:dev-db connection:stage-db');
    expect(pg.connections).toBe(2);
    expect(views[1].state).toBe('disabled');
    expect(views[1].card.action).toBeNull();
    expect(views[1].card.status).toEqual({ tone: 'neutral', text: 'Switched off' });
    expect(views[2].card.status).toEqual({ tone: 'success', text: 'Running · 54 MB' });
    expect(views[3].card.tools).toBe(12);
    expect(installedCountText(views, views.length, '')).toBe('2 built-in, 1 plugin, 1 MCP server');
    expect(installedCountText(views, 1, 'rabbit')).toBe('1 of 4 installed connectors');
  });

  it('shows a type of the person’s own module as a module, unsigned, between the built-ins and the plugins', () => {
    const module = { id: 'shop', version: '1.2.0', state: 'ready', step: null, sizeBytes: 2_700_000, installed: true, message: null, signed: false, listedVersion: null };
    const orders = type({ id: 'orders', name: 'Orders', version: '1.0.0', signed: false, tools: 2, module, process: { state: 'running', pid: 77, rssMb: 61, detail: null } });
    const shipments = type({ id: 'shipments', name: 'Shipments', signed: false, tools: 1, module });
    // GateRook's own module is one of the built-in connectors: nothing sets it apart here.
    const cassandra = type({ id: 'cassandra', name: 'Cassandra', module: { ...module, id: 'cassandra', signed: true } });
    const views = installedViews([rabbit, orders, postgres, shipments, cassandra], conns, null, []);
    expect(views.map((v) => v.key)).toEqual(['type:postgres', 'type:cassandra', 'module:orders', 'module:shipments', 'plugin:rabbitmq']);
    const own = views[2];
    expect(own.kind).toBe('module');
    expect(own.module?.id).toBe('shop');
    expect(own.card).toMatchObject({ kind: 'module', signed: false, version: '1.2.0', action: null, tools: 2 });
    expect(own.card.status).toEqual({ tone: 'success', text: 'Running · 61 MB' });
    expect(views[1].module ?? null).toBeNull();
    expect(views[1].card).toMatchObject({ kind: 'built-in', signed: null, action: 'add' });
    expect(installedCountText(views, views.length, '')).toBe('2 built-in, 2 from your own module, 1 plugin');
    expect(matches(own.search, 'unsigned module')).toBe(true);

    // GateRook's list has the module too: its build is there to return to, after what needs the person first.
    const listed = { ...orders, module: { ...module, listedVersion: '1.3.0' } };
    expect(installedViews([listed], [], null, [])[0].card.status).toEqual({ tone: 'info', text: 'GateRook’s build available · ', mono: 'v1.3.0' });
    expect(installedViews([{ ...listed, module: { ...listed.module, state: 'installing' } }], [], null, [])[0].card.status).toEqual({ tone: 'info', text: 'Installing GateRook’s build' });
    expect(installedViews([{ ...listed, enabled: false }], [], null, [])[0].card.status?.text).toBe('Switched off');
    expect(installedViews([{ ...listed, verified: false }], [], null, [])[0].state).toBe('changed-outside');
    // A core that says nothing of whose build a module is knows only GateRook's own.
    const { signed: _, ...unsaid } = module;
    expect(installedViews([{ ...orders, module: unsaid }], [], null, [])[0].kind).toBe('built-in');
  });

  it('offers the catalog’s newer version of a plugin, after what needs the person first', () => {
    const index = catalog([entry({ id: 'rabbitmq', kind: 'plugin', name: 'RabbitMQ', version: '0.4.0', installed: '0.3.0' })]);
    expect(pluginUpdate(rabbit, index)?.to).toBe('0.4.0');
    expect(pluginUpdate({ id: 'rabbitmq', version: '0.4.0' }, index)).toBeNull();

    const [update] = installedViews([rabbit], [], index, []);
    expect(update.card.status).toEqual({ tone: 'info', text: 'Update available · ', mono: 'v0.4.0' });
    expect(update.update?.to).toBe('0.4.0');

    const [failed] = installedViews([{ ...rabbit, process: { state: 'failed', pid: null, rssMb: null, detail: 'exit 1' } }], [], index, []);
    expect(failed.failed).toBe(true);
    expect(failed.card.status?.text).toBe('Failed');

    const [off] = installedViews([{ ...rabbit, enabled: false }], [], index, []);
    expect(off.card.status?.text).toBe('Disabled');
    expect(off.update).toBeNull();

    const [outside] = installedViews([{ ...rabbit, verified: false }], [], index, []);
    expect(outside.state).toBe('changed-outside');
    expect(outside.card.status).toEqual({ tone: 'warning', text: 'Changed outside GateRook' });
  });
});

describe('connectors view: Catalog', () => {
  it('maps entries to the kit’s actions', () => {
    const views = catalogViews(
      catalog([
        entry({ id: 'jira', kind: 'plugin', name: 'Jira' }),
        entry({ id: 'rabbitmq', kind: 'plugin', name: 'RabbitMQ', version: '0.4.0', installed: '0.3.0' }),
        entry({ id: 'clickhouse', kind: 'plugin', name: 'ClickHouse', installed: '1.0.0' }),
        entry({ id: 'pg-slow-queries', kind: 'template', name: 'PostgreSQL slow queries', description: 'Adds `pg_slow_queries` and pg_locks as read-only SQL tools.' }),
        entry({ id: 'github', kind: 'mcp-server', name: 'GitHub', installed: 'github-mcp' }),
        entry({ id: 'sentry', kind: 'mcp-server', name: 'Sentry' }),
      ]),
    );
    expect(views.map((v) => v.action)).toEqual(['install-plugin', 'update-plugin', 'add-connection', 'install-template', 'open-server', 'install-server']);
    expect(views[0].card.icon).toBe('puzzle');
    expect(views[1].card).toMatchObject({ action: 'update', runs: 'v0.3.0', updateTo: 'v0.4.0' });
    expect(views[2].card.status).toEqual({ tone: 'success', text: 'Installed' });
    expect(views[3].card.literals).toEqual(['pg_slow_queries', 'pg_locks']);
    expect(views[3].card.icon).toBe('file-code');
    expect(views[4].card.status).toEqual({ tone: 'success', text: 'Installed as ', mono: 'github-mcp' });
    expect(views[5].card.action).toBe('install');
    expect(views[5].search).toContain('mcp server');
  });

  it('finds tool names only where they have an underscore', () => {
    expect(toolLiterals('Reads queues with rabbitmq_queues; plain words stay plain.')).toEqual(['rabbitmq_queues']);
  });

  it('offers GateRook’s own modules that aren’t installed, once per connector type', () => {
    const views = moduleCatalogViews([
      {
        name: 'Databases',
        cards: [
          moduleCard('duckdb', 'DuckDB'),
          // Part of this build, the person's own or GateRook's installed module, and a variant of a type: none is an offer.
          moduleCard('postgres', 'PostgreSQL', { module: null }),
          moduleCard('orders', 'Orders', {}, { installed: true, state: 'ready', signed: false }),
          moduleCard('neo4j', 'Neo4j', {}, { installed: true, state: 'ready' }),
          moduleCard('duckdb', 'MotherDuck', { variant: 'motherduck' }),
          moduleCard('h2', 'H2', {}, { state: 'installing', step: 'downloading', sizeBytes: 2_775_406 }),
          moduleCard('msaccess', 'Microsoft Access', {}, { state: 'failed', message: 'No network.', sizeBytes: 3_566_458 }),
        ],
      },
      { name: 'Data warehouses', cards: [moduleCard('clickhouse', 'ClickHouse', { tools: 6 }, { version: '1.0.0', sizeBytes: 57_032 }), moduleCard('duckdb', 'DuckDB')] },
    ]);
    expect(views.map((v) => v.key)).toEqual(['module:duckdb', 'module:h2', 'module:msaccess', 'module:clickhouse']);
    const duckdb = views[0];
    expect(duckdb.entry).toBeNull();
    expect(duckdb.action).toBe('add-connection');
    expect(duckdb.note).toBe('Not part of the installer: adding a connection downloads it (32 MB).');
    expect(duckdb.card).toMatchObject({ type: 'duckdb', variant: null, kind: 'built-in', version: '1.1.0', signed: null, tools: 4, action: 'add', status: null });
    expect(matches(duckdb.search, 'duck')).toBe(true);
    expect(matches(duckdb.search, 'databases')).toBe(true);
    expect(matches(duckdb.search, 'module')).toBe(true);
    expect(views[1].card.status).toEqual({ tone: 'info', text: 'Downloading' });
    expect(views[1].note).toContain('(2.6 MB)');
    expect(views[2].card.status).toEqual({ tone: 'warning', text: 'The download failed' });
    expect(views[3].note).toBe('Not part of the installer: adding a connection downloads it (56 KB).');
    expect(moduleCatalogViews([])).toEqual([]);
  });

  it('words what Check now found: a list it couldn’t reach, what is new, or nothing', () => {
    const groups = [{ name: 'Databases', cards: [moduleCard('duckdb', 'DuckDB'), moduleCard('h2', 'H2')] }];
    const index = catalog([entry({ id: 'jira', kind: 'plugin', name: 'Jira' })]);
    const checked = moduleList({ configured: true, checkedAt: '2026-10-23T09:00:00Z', offline: false });
    // GateRook's modules come first in what the Catalog offers, then the signed catalog's entries.
    const offer = catalogOffer(groups, index);
    expect(offer.map((x) => x.key)).toEqual(['module:duckdb', 'module:h2', 'plugin:jira']);
    expect(catalogOffer(null, null)).toEqual([]);
    expect(catalogOffer(groups, null).map((x) => x.key)).toEqual(['module:duckdb', 'module:h2']);

    expect(listsCheckToast(offer, offer, index, checked)).toEqual({ tone: 'success', title: 'The catalog is up to date' });
    expect(listsCheckToast(offer.slice(2), offer, index, checked)).toEqual({ tone: 'success', title: '2 new connectors in the catalog', body: 'DuckDB and H2' });
    expect(listsCheckToast(offer.slice(0, 2), offer, index, checked)).toEqual({ tone: 'success', title: '1 new connector in the catalog', body: 'Jira' });
    // What left the lists is no news; the Catalog simply shows less.
    expect(listsCheckToast(offer, offer.slice(1), index, checked).title).toBe('The catalog is up to date');

    // A list the core couldn't reach comes before anything else, the list of modules first.
    const lost = moduleList({ configured: true, checkedAt: '2026-10-22T11:00:00Z', offline: true, problem: ' The list can’t be reached: timeout ' });
    const stale: CatalogIndex = { ...index, offline: true, problem: 'The catalog can’t be reached: timeout' };
    expect(listsCheckToast(offer.slice(2), offer, stale, lost)).toEqual({
      tone: 'warning',
      title: "GateRook's list of connectors couldn't be checked",
      body: 'The list can’t be reached: timeout',
    });
    expect(listsCheckToast(offer, offer, stale, checked)).toEqual({ tone: 'warning', title: "The catalog couldn't be checked", body: 'The catalog can’t be reached: timeout' });
    expect(listsCheckToast(offer, offer, { ...stale, problem: null }, checked)).toEqual({ tone: 'warning', title: "The catalog couldn't be checked", body: null });

    // Nothing to check is no failure: a build that reads no catalog, and one without a channel for modules.
    const none: CatalogIndex = { checkedAt: null, source: 'none', offline: true, signature: null, entries: [], problem: 'No catalog is configured for this build.' };
    expect(listsCheckToast(offer, offer, none, checked).title).toBe('The catalog is up to date');
    expect(listsCheckToast(offer, offer, index, moduleList()).title).toBe('The catalog is up to date');
  });
});

describe('connectors view: Templates', () => {
  it('words the Tools and default mode cell', () => {
    expect(templateModes({ kind: 'sql-query', natures: { read: 1 }, status: 'active' })).toEqual({
      chips: [{ mode: 'read', icon: 'eye', text: 'Read only' }],
      note: '1 tool',
    });
    expect(templateModes({ kind: 'sql-query', natures: { mutation: 1 }, status: 'active' }).note).toBe('1 tool, changes state');
    expect(templateModes({ kind: 'ssh-runbook', natures: { mutation: 1 }, status: 'active' })).toEqual({
      chips: [{ mode: 'approval', icon: 'user-check', text: 'Approval' }],
      note: 'via ssh_run, changes state',
    });
    const api = templateModes({ kind: 'openapi', natures: { read: 9, mutation: 5 }, status: 'active' });
    expect(api.chips.map((c) => c.text)).toEqual(['9 read only', '5 approval']);
    expect(api.note).toBe('14 operations');
    expect(templateModes({ kind: 'sql-query', natures: { read: 1 }, status: 'invalid' })).toEqual({
      chips: [{ mode: 'off', icon: 'circle-off', text: 'Off' }],
      note: 'its connection was deleted',
    });
    expect(templateModes({ kind: 'sql-query', natures: null, status: 'disabled' }).note).toBe('switched off');
  });

  it("names a template with its signature when the core sends its parameters (step API)", () => {
    const params = [{ name: 'status', type: 'string' }, { name: 'since', type: 'string' }];
    expect(templateSignature({ kind: 'sql-query', name: 'orders_by_status', parameters: params })).toBe('orders_by_status(status, since)');
    expect(templateSignature({ kind: 'sql-query', name: 'recent_orders', parameters: [] })).toBe('recent_orders()');
    // A runbook goes by its name, parameters or not (kit: "compose-ps", which takes a service).
    expect(templateSignature({ kind: 'ssh-runbook', name: 'compose-ps', parameters: [{ name: 'service' }] })).toBe('compose-ps');
    expect(templateSignature({ kind: 'openapi', name: 'billing', parameters: null })).toBe('billing');
    // A core before step API (or a template that doesn't verify): the name alone.
    expect(templateSignature({ kind: 'sql-query', name: 'orders_by_status' })).toBe('orders_by_status');
    expect(templateSignature({ kind: 'sql-query', name: 'orders_by_status', parameters: null })).toBe('orders_by_status');
  });

  it('finds where a template’s tools are on and where to add it', () => {
    const tools = new Map<string, ToolGroup[]>([
      ['dev', [{ group: 'dev-db', connection: 'dev-db', env: 'dev', tools: [tool('tpl:t1:main', 'orders_by_status', 'READ_ONLY'), tool('postgres:query', 'sql_query', 'READ_ONLY')] }]],
      ['stage', [{ group: 'stage-db', connection: 'stage-db', env: 'stage', tools: [tool('tpl:t1:main', 'orders_by_status', 'OFF')] }]],
      ['ops', [{ group: 'prod-db', connection: 'prod-db', env: 'prod', tools: [tool('tpl:t10:main', 'other', 'APPROVAL')] }]],
    ]);
    expect(templateEndpoints('t1', tools)).toEqual(['dev']);
    expect(templateEndpoints('t10', tools)).toEqual(['ops']);
    expect(templateToolNames('t1', tools.get('dev'))).toEqual(['orders_by_status']);
    expect(templateToolNames('t1', undefined)).toEqual([]);

    const endpoints = [
      { slug: 'dev', env: 'dev', status: 'running' },
      { slug: 'stage', env: 'stage', status: 'running' },
      { slug: 'old-stage', env: 'stage', status: 'disabled' },
    ] as EndpointSummary[];
    expect(endpointFor('stage-db', 'stage', endpoints, tools)).toBe('stage');
    expect(endpointFor('new-db', 'stage', endpoints, tools)).toBe('stage');
    expect(endpointFor(null, 'prod', endpoints, tools)).toBeNull();
  });
});

describe('connectors view: MCP servers', () => {
  it('names the host of a sign-in only when it is a name, never an IP address or localhost', () => {
    expect(signInHost('mcp.sentry.dev')).toBe('mcp.sentry.dev');
    expect(signInHost('mcp.example.com:8443')).toBe('mcp.example.com');
    for (const host of ['127.0.0.1', '127.0.0.1:7781', 'localhost', 'auth.localhost', '10.1.2.3', '[::1]', '[::1]:7781', '::1', 'fe80::1', ' ', null, undefined]) {
      expect(signInHost(host), String(host)).toBeNull();
    }
    expect(['127.0.0.1', '127.1.2.3:7781', 'localhost', 'Auth.LOCALHOST', '[::1]:7781', '::1'].map(onThisComputer)).toEqual([true, true, true, true, true, true]);
    expect(['10.1.2.3', 'mcp.sentry.dev', 'localhost.example.com', '', null].map(onThisComputer)).toEqual([false, false, false, false, false]);
    // The row of a server says where it is signed in only by a name.
    const signedIn = (issuer: string) => signInText(mcpServerView(
      connection('tracker-mcp', 'mcp-server'),
      detail('tracker-mcp', { transport: 'http', url: 'http://127.0.0.1:7781/mcp', auth: { type: 'oauth' } }),
      null, null, null, null,
      { server: 'tracker-mcp', state: 'signed-in', issuer },
    ));
    expect(signedIn('https://mcp.sentry.dev')).toEqual({ tone: 'success', text: 'Signed in at mcp.sentry.dev' });
    expect(signedIn('http://127.0.0.1:7781')).toEqual({ tone: 'success', text: 'Signed in' });
  });

  it('splits a command line at whitespace with quotes, never as a shell', () => {
    expect(tokenizeCommand('npx -y @sentry/mcp-server@0.12.0').argv).toEqual(['npx', '-y', '@sentry/mcp-server@0.12.0']);
    expect(tokenizeCommand(`node "/Users/me/My Tools/index.js" --flag='a b' ""`).argv).toEqual(['node', '/Users/me/My Tools/index.js', '--flag=a b', '']);
    expect(tokenizeCommand('echo "a \\"quoted\\" word"').argv).toEqual(['echo', 'a "quoted" word']);
    expect(tokenizeCommand('run $HOME; rm -rf *').argv).toEqual(['run', '$HOME;', 'rm', '-rf', '*']);
    expect(tokenizeCommand('node "unclosed')).toEqual({ argv: [], error: 'A quote in the command isn’t closed.' });
    expect(tokenizeCommand('   ').argv).toEqual([]);
  });

  it('writes argv back as a line that reads to the same argv', () => {
    const argv = ['node', '/Users/me/My Tools/index.js', 'say "hi"', '--port=3000'];
    const line = formatArgv(argv);
    expect(line).toBe('node "/Users/me/My Tools/index.js" "say \\"hi\\"" --port=3000');
    expect(tokenizeCommand(line).argv).toEqual(argv);
  });

  it('reads the npm package and its pin', () => {
    expect(npmPackage(['npx', '-y', '@sentry/mcp-server@0.12.0'])).toEqual({ name: '@sentry/mcp-server@0.12.0', version: '0.12.0' });
    expect(npmPackage(['npx', '-y', '@sentry/mcp-server'])).toEqual({ name: '@sentry/mcp-server', version: '' });
    expect(npmPackage(['npx', 'pkg@latest'])?.version).toBe('');
    expect(npmPackage(['/usr/local/bin/pnpm', 'dlx', 'pkg@1.2.3'])?.version).toBe('1.2.3');
    expect(npmPackage(['./server', '--stdio'])).toBeNull();
    expect(npmPackage([])).toBeNull();
  });

  it('builds a server from its connection, its config and what its process does', () => {
    const row = connection('github-mcp', 'mcp-server', { usedBy: ['dev'] });
    const config = {
      transport: 'stdio',
      command: ['node', '/Users/me/.gaterook/mcp/github-mcp/dist/index.js'],
      env: [{ name: 'GITHUB_PERSONAL_ACCESS_TOKEN', secretRef: 'secret:abc' }],
      inheritEnv: ['HTTPS_PROXY'],
      catalogEntry: 'github@0.6.2',
      idleStopMinutes: 30,
    };
    const secrets = [{ field: '/env/0/secretRef', secretId: 'abc', name: 'github-mcp/token' }] as ConnectionDetail['secrets'];
    const index = catalog([entry({ id: 'github', kind: 'mcp-server', name: 'GitHub', description: 'Issues, pull requests and code search.' })]);
    const s = mcpServerView(row, detail('github-mcp', config, secrets), null, 26, 'running', index);
    expect(s).toMatchObject({
      name: 'github-mcp',
      transport: 'stdio',
      command: 'node /Users/me/.gaterook/mcp/github-mcp/dist/index.js',
      secrets: [{ name: 'GITHUB_PERSONAL_ACCESS_TOKEN', secret: 'github-mcp/token' }],
      inherited: ['HTTPS_PROXY'],
      version: '0.6.2',
      pinned: true,
      packageName: null,
      signed: true,
      description: 'Issues, pull requests and code search.',
      tools: 26,
      state: 'running',
      usedBy: ['dev'],
      start: 'on-first-call',
      idleStopMinutes: 30,
      toolPrefix: 'github_',
    });
    expect(serverActivityQuery(s)).toBe('tool:github_*');
    expect(serverPaths('github-mcp')).toEqual({ workdir: '~/.gaterook/mcp/github-mcp', log: '~/.gaterook/logs/mcp/github-mcp.log' });
  });

  it('tells a typed command and an HTTP server apart from a catalog one', () => {
    const typed = mcpServerView(
      connection('sentry-mcp', 'mcp-server'),
      detail('sentry-mcp', { transport: 'stdio', command: ['npx', '-y', '@sentry/mcp-server'], toolPrefix: 'sentry_' }),
      null,
      null,
      null,
      null,
    );
    expect(typed).toMatchObject({ packageName: '@sentry/mcp-server', pinned: false, version: null, signed: null, tools: null, state: null, toolPrefix: 'sentry_', description: 'Runs over stdio on this computer.' });

    const http = mcpServerView(
      connection('linear', 'mcp-server', { verified: false, host: 'https://mcp.linear.app/mcp' }),
      detail('linear', { transport: 'http', url: 'https://mcp.linear.app/mcp', headers: [{ name: 'Authorization' }], start: 'with-core' }),
      null,
      null,
      null,
      null,
    );
    expect(http).toMatchObject({
      transport: 'http',
      command: null,
      url: 'https://mcp.linear.app/mcp',
      secrets: [{ name: 'Authorization', secret: null }],
      pinned: null,
      start: 'with-core',
      verified: false,
      description: 'Calls https://mcp.linear.app/mcp over HTTP.',
    });
    // A key the server takes in its URL stays in the config; the card shows the URL as the core lists it.
    const keyed = mcpServerView(
      connection('search-mcp', 'mcp-server', { host: 'https://mcp.example.com/‹masked›/v2/mcp' }),
      detail('search-mcp', { transport: 'http', url: 'https://mcp.example.com/k-7f3aZq/v2/mcp?apiKey=k-7f3aZq' }),
      null,
      null,
      null,
      null,
    );
    expect(keyed.url).toBe('https://mcp.example.com/‹masked›/v2/mcp');
    expect(keyed.description).toBe('Calls https://mcp.example.com/‹masked›/v2/mcp over HTTP.');

    // Without the detail (it failed to load) the row still shows, with what the row says.
    const bare = mcpServerView(connection('x-mcp', 'mcp-server'), null, null, null, null, null);
    expect(bare).toMatchObject({ transport: 'stdio', command: null, secrets: [], toolPrefix: 'x_' });
  });

  it('derives the default tool prefix as the connector does', () => {
    expect(defaultToolPrefix('github-mcp')).toBe('github_');
    expect(defaultToolPrefix('my2-server')).toBe('my2_');
    expect(defaultToolPrefix('-x')).toBe('mcp_');
    expect(onEndpointsText([])).toBe('not on an endpoint yet');
    expect(onEndpointsText(['dev', 'incident-prod'])).toBe('on dev and incident-prod');
  });
});
