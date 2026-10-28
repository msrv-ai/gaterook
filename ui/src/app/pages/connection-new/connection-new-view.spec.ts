import { TestOutcome } from '../../core/api/connections.api';
import { ConnectorGroup, ConnectorTool } from '../../core/api/connector-types.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { EnvPolicy } from '../../core/api/policies.api';
import { JsonObject } from '../../core/api/types';
import {
  activityQuery,
  copyName,
  defaultCardKey,
  deletedToast,
  endpointChoices,
  endpointUses,
  findCard,
  firstRunModes,
  firstRunPreview,
  firstRunTestText,
  firstRunToast,
  fixSnippet,
  healthStat,
  initialEndpoints,
  isFirstRun,
  list,
  policyDefaults,
  refusalFor,
  savedToast,
  secretsSentence,
  secretWord,
  sparkPoints,
  starterEndpoint,
  stepperSteps,
  testSummary,
  testTarget,
  testToast,
  toolMode,
  toolPreview,
  toolsLine,
  toolsSentence,
  usage,
  usedByMeta,
  usedByRows,
} from './connection-new-view';

/** Add connection's words and decisions against the kit (design/pages/connection-new.html) and the core's rules. */

const card = (over: Partial<ConnectorGroup['cards'][number]>) => ({
  type: 'postgres',
  variant: null,
  name: 'PostgreSQL',
  icon: 'database',
  description: 'Read queries, schema, EXPLAIN. Writes wait for approval.',
  kind: 'built-in',
  version: null,
  signed: null,
  tools: 4,
  toolsFrom: null,
  connections: 3,
  available: true,
  reason: null,
  presetConfig: null,
  ...over,
});

const GROUPS: ConnectorGroup[] = [
  { name: 'Databases', cards: [card({}), card({ type: 'clickhouse', name: 'ClickHouse', kind: 'plugin', available: false, reason: 'Not installed.', connections: 0 })] },
  {
    name: 'Logs',
    cards: [
      card({ type: 'logs', variant: 'gcp-logging', name: 'GCP Cloud Logging', tools: 2, connections: 1 }),
      card({ type: 'logs', variant: 'loki', name: 'Loki', tools: 2, connections: 1, presetConfig: { backend: 'loki' } }),
    ],
  },
];

const tool = (over: Partial<ConnectorTool>): ConnectorTool => ({
  name: 'sql_query',
  title: 'Query',
  description: 'Run one SELECT.',
  nature: 'read',
  defaultMode: 'read-only',
  prodDefaultMode: 'read-only',
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  ...over,
});

const SQL_TOOLS = [
  tool({ name: 'sql_list_schemas' }),
  tool({ name: 'sql_describe_table' }),
  tool({ name: 'sql_query' }),
  tool({
    name: 'sql_execute',
    nature: 'mutation',
    defaultMode: 'approval',
    prodDefaultMode: 'approval',
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
  }),
];

const endpoint = (over: Partial<EndpointSummary>): EndpointSummary => ({
  slug: 'dev',
  description: 'Local development. Reads run, writes wait for approval.',
  url: 'http://127.0.0.1:7465/mcp/dev',
  env: 'dev',
  access: 'standard',
  status: 'running',
  tools: { read: 3, approval: 1, off: 0 },
  connections: { total: 4, byEnv: { dev: 4 } },
  health: {},
  approvalsWaiting: 0,
  clients: ['claude-desktop', 'codex'],
  tokens: { active: 2, expiringSoon: 0 },
  published: false,
  risk: null as never,
  blockedReason: null,
  version: 1,
  ...over,
});

const ENDPOINTS = [
  endpoint({}),
  endpoint({ slug: 'stage-readonly', description: 'Read only.', env: 'stage', access: 'read-only', clients: ['claude-ai'], published: true }),
  endpoint({ slug: 'incident-prod', description: 'Prod logs, read only.', env: 'prod', clients: ['claude-desktop'] }),
];

const outcome = (over: Partial<TestOutcome>): TestOutcome => ({
  ok: false,
  latencyMs: 0,
  serverVersion: null,
  identity: null,
  permissions: null,
  hints: [],
  checks: [],
  error: null,
  needsConfirmation: null,
  confirmationId: null,
  suggestedPolicyChange: null,
  details: null,
  ...over,
});

const policy = (sections: JsonObject): EnvPolicy =>
  ({ env: 'dev', inherits: null, sections, explicit: {}, inherited: {}, locks: [], runbooks: [], version: 1, verified: true, serving: true }) as EnvPolicy;

describe('Add connection: the stepper', () => {
  it('marks the steps before the current one complete with the choice made there (kit page script)', () => {
    const steps = stepperSteps(3, { typeName: 'PostgreSQL', name: 'dev-db', env: 'dev', test: null, endpoints: [] });
    expect(steps.map((s) => s.state)).toEqual(['complete', 'complete', 'current', 'upcoming']);
    expect(steps.map((s) => s.description)).toEqual([{ text: 'PostgreSQL' }, { name: 'dev-db', env: 'dev' }, null, null]);
    expect(steps.filter((s) => s.current).map((s) => s.n)).toEqual([3]);
  });

  it('keeps Test failed until a test passes, also on the next step (COMPONENTS.md stepper, error)', () => {
    const failed = testSummary(outcome({ error: { code: 'unreachable', message: 'Timed out', technical: null } }), 'db.dev.internal:5432');
    expect(failed).toEqual({ passed: false, text: "Can't reach db.dev.internal:5432" });
    const steps = stepperSteps(4, { typeName: 'PostgreSQL', name: 'dev-db', env: 'dev', test: failed, endpoints: ['dev'] });
    expect(steps.map((s) => s.state)).toEqual(['complete', 'complete', 'error', 'current']);
    expect(steps[2].description).toEqual({ text: "Can't reach db.dev.internal:5432" });
    const passed = testSummary(outcome({ ok: true, latencyMs: 12 }), null);
    expect(passed).toEqual({ passed: true, text: 'Connected · 12 ms' });
    expect(stepperSteps(4, { typeName: null, name: '', env: 'dev', test: passed, endpoints: [] })[2].state).toBe('complete');
  });

  it('says what step 4 chose once it is done, and names an untested step', () => {
    const done = stepperSteps(4, { typeName: 'PostgreSQL', name: '', env: 'prod', test: null, endpoints: [] });
    expect(done[1].description).toEqual({ name: 'this connection', env: 'prod' });
    // Step 4 is current, never complete: its description shows once the person goes back from a later state.
    expect(done[3].description).toBeNull();
  });

  it('words a test that stopped for a host key as the question it asks', () => {
    const o = outcome({ needsConfirmation: { kind: 'host-key', text: 'New key', data: { host: '10.20.0.14', port: 22 } } });
    expect(testSummary(o, 'x')).toEqual({ passed: false, text: 'Check the host key of 10.20.0.14:22' });
  });
});

describe('Add connection: choose type', () => {
  it('opens on ?type= (and &variant=), else on the first type that can be used; never on an unavailable one', () => {
    expect(defaultCardKey(GROUPS)).toBe('postgres');
    expect(defaultCardKey(GROUPS, 'logs', 'loki')).toBe('logs:loki');
    expect(defaultCardKey(GROUPS, 'logs')).toBe('logs:gcp-logging');
    expect(defaultCardKey(GROUPS, 'clickhouse')).toBe('postgres');
    expect(findCard(GROUPS, 'logs:loki')?.presetConfig).toEqual({ backend: 'loki' });
  });

  it('says how many connections a type already has', () => {
    expect(usage(card({}))).toBe('3 connections use PostgreSQL');
    expect(usage(card({ name: 'Loki', connections: 1 }))).toBe('1 connection uses Loki');
    expect(usage(card({ connections: 0 }))).toBeNull();
  });
});

describe('Add connection: test', () => {
  it('names where the test connects and as whom, from the nested config', () => {
    const t = testTarget({ connection: { host: 'db.dev.internal', port: 5432, database: 'app' }, credentials: { user: 'app_rw' }, advanced: { connectTimeoutMs: 5000 } });
    expect(t).toEqual({ user: 'app_rw', address: 'db.dev.internal:5432/app', hostPort: 'db.dev.internal:5432', timeoutMs: 5000 });
    expect(testTarget({ baseUrl: 'https://billing.stage.acme.dev' })).toEqual({ user: null, address: 'https://billing.stage.acme.dev', hostPort: 'https://billing.stage.acme.dev', timeoutMs: null });
    expect(testTarget(null)).toEqual({ user: null, address: null, hostPort: null, timeoutMs: null });
    expect(testTarget({ connection: { host: 'db.internal', port: 3306, databases: ['app', 'reports'] } }).address).toBe('db.internal:3306/app, reports');
    expect(testTarget({ connection: { host: 'cache.internal', port: 6379, databases: [0, 2] } }).address).toBe('cache.internal:6379/0, 2');
  });

  it('turns the connector snippet into the kit disclosure "Create a read-only user" for gw_reader', () => {
    const o = outcome({
      ok: true,
      hints: [{ severity: 'warning', code: 'sql.can-write', field: null, text: 'This user can write.', snippet: { language: 'sql', text: 'CREATE ROLE gw_reader LOGIN;' } }],
    });
    expect(fixSnippet(o, { connection: { database: 'app' } })).toEqual({
      toggle: 'Create a read-only user',
      lang: 'SQL',
      language: 'sql',
      text: 'CREATE ROLE gw_reader LOGIN;',
      filename: 'Run as an admin on app, then use gw_reader as the user',
      literals: ['app', 'gw_reader'],
    });
    expect(fixSnippet(outcome({ ok: true }), null)).toBeNull();
  });
});

describe('Add connection: endpoints', () => {
  it('preselects default without also exposing a new connection on other endpoints', () => {
    const choices = endpointChoices([endpoint({ slug: 'team' }), endpoint({ slug: 'default' })], { name: 'new-db', env: 'dev' });
    expect(initialEndpoints(choices, 'dev')).toEqual(['default']);
    expect(initialEndpoints(choices.map((c) => c.slug === 'default' ? { ...c, refusal: 'Blocked' } : c), 'dev')).toEqual(['team']);
  });

  it('refuses what the core would refuse: prod and non-prod never mix, prod never goes public (Wiring.mixing)', () => {
    const dev = { name: 'dev-db', env: 'dev' as const };
    expect(refusalFor(ENDPOINTS[2], dev)).toBe('Accepts prod connections only; dev-db is dev.');
    expect(refusalFor(ENDPOINTS[0], { name: 'prod-db', env: 'prod' })).toBe('Accepts dev and stage connections only; prod-db is prod.');
    expect(refusalFor(ENDPOINTS[1], { name: 'prod-db', env: 'prod' })).toBe('This endpoint does not accept production connections; prod-db is prod.');
    // An empty endpoint takes any connection; one changed outside GateRook says so first.
    expect(refusalFor(endpoint({ env: 'prod', connections: { total: 0, byEnv: {} } }), dev)).toBeNull();
    expect(refusalFor(endpoint({ blockedReason: 'Changed outside GateRook.' }), dev)).toBe('Changed outside GateRook.');
  });

  it('lists usable endpoints first with their clients, the refused ones after with their reason (kit order)', () => {
    const choices = endpointChoices(ENDPOINTS, { name: 'dev-db', env: 'dev' });
    expect(choices.map((c) => [c.slug, c.meta, c.refusal])).toEqual([
      ['dev', 'Claude Desktop, Codex', null],
      ['stage-readonly', null, null],
      ['incident-prod', 'Claude Desktop', 'Accepts prod connections only; dev-db is dev.'],
    ]);
    expect(choices[2].description).toBe('Accepts prod connections only; dev-db is dev.');
    expect(choices[1].warning).toEqual({ title: 'Review endpoint access', body: 'Check who can use stage-readonly before giving it access to dev-db.' });
    expect(choices[0].warning).toBeNull();
  });

  it('ticks the standard, private endpoints on the connection side of the prod line (kit: dev starts on dev)', () => {
    const forDev = endpointChoices([...ENDPOINTS, endpoint({ slug: 'team', env: 'stage' })], { name: 'dev-db', env: 'dev' });
    expect(initialEndpoints(forDev, 'dev')).toEqual(['dev', 'team']);
    const forProd = endpointChoices(ENDPOINTS, { name: 'prod-db', env: 'prod' });
    expect(initialEndpoints(forProd, 'prod')).toEqual(['incident-prod']);
  });

  it('previews the modes the core starts the tools in (ToolPairs.initial) and the read-only lock', () => {
    const [dev, stage] = endpointChoices(ENDPOINTS, { name: 'dev-db', env: 'dev' });
    const onDev = toolPreview(SQL_TOOLS, 'built-in', false, dev);
    expect(onDev.summary).toBe('4 tools · 3 read only · 1 approval');
    expect(onDev.rows.map((r) => [r.name, r.mode, r.note?.text ?? null])).toEqual([
      ['sql_list_schemas', 'READ_ONLY', null],
      ['sql_describe_table', 'READ_ONLY', null],
      ['sql_query', 'READ_ONLY', null],
      ['sql_execute', 'APPROVAL', 'Mutates data: starts in Approval or Off'],
    ]);
    const onStage = toolPreview(SQL_TOOLS, 'built-in', false, stage);
    expect(onStage.summary).toBe('4 tools · 3 read only · 1 off, locked by the read-only endpoint');
    expect(onStage.rows[3]).toMatchObject({ mode: 'OFF', note: { icon: 'lock', text: 'stage-readonly is read only' } });
  });

  it('starts every tool of a plugin in Approval, and says when the tools come from the server', () => {
    const [dev] = endpointChoices(ENDPOINTS, { name: 'jira', env: 'dev' });
    expect(toolPreview(SQL_TOOLS.slice(0, 1), 'plugin', false, dev).rows[0].mode).toBe('APPROVAL');
    expect(toolPreview([], 'mcp-server', true, dev)).toMatchObject({ fromServer: true, summary: '0 tools' });
    expect(toolMode('READ_ONLY')).toBe('READ_ONLY');
    expect(toolMode('read-only')).toBe('READ_ONLY');
    expect(toolMode(null)).toBe('OFF');
  });
});

describe('Add connection: what prod turns on', () => {
  const schema: JsonObject = {
    sections: {
      sql: {
        properties: {
          readOnlyTransactions: { type: 'boolean', const: true, readOnly: true, title: 'Run queries in a read-only transaction' },
          statementTimeoutMs: { type: 'integer', title: 'Statement timeout', 'x-unit': 'ms' },
          rowLimit: { type: 'integer', title: 'Row limit', 'x-unit': 'rows' },
          denyDdl: { type: 'boolean', title: 'Block DDL' },
          maxResponseKb: { type: 'integer', title: 'Max response size', 'x-unit': 'KB' },
        },
      },
    },
  };
  const dev = policy({ sql: { readOnlyTransactions: true, statementTimeoutMs: 5000, rowLimit: 1000, denyDdl: false, maxResponseKb: 512 }, approvals: { timeoutMinutes: 5, comment: 'optional' } });
  const prod = policy({ sql: { readOnlyTransactions: true, statementTimeoutMs: 3000, rowLimit: 100, denyDdl: true, maxResponseKb: 512 }, approvals: { timeoutMinutes: 10, comment: 'required' } });

  it('reads the lines from the prod policy against dev, in the kit order and words', () => {
    expect(policyDefaults('postgres', dev, prod, schema)).toEqual([
      { icon: 'lock', text: 'Read-only transactions' },
      { icon: 'rows-3', text: 'Row limit 100 (dev: 1,000)' },
      { icon: 'timer', text: 'Statement timeout 3 s (dev: 5 s)' },
      { icon: 'ban', text: 'No DDL: CREATE, ALTER, DROP, TRUNCATE are refused', literals: ['CREATE', 'ALTER', 'DROP', 'TRUNCATE'] },
      { icon: 'hourglass', text: 'Approval timeout 10 min, a comment is required to approve' },
    ]);
  });

  it('only lists what differs: a type without a policy section gets the approvals line alone', () => {
    expect(policyDefaults('mcp', dev, prod, schema)).toEqual([{ icon: 'hourglass', text: 'Approval timeout 10 min, a comment is required to approve' }]);
    expect(policyDefaults('mcp', dev, dev, schema)).toEqual([]);
  });
});

describe('Connection: edit view', () => {
  it('words the health stat from the check and its last 24 hours', () => {
    expect(healthStat({ state: 'healthy', latencyMs: 12, message: null, checkedAt: null }, [10, 15, 12], 60)).toEqual({
      tone: 'success',
      value: '12',
      unit: 'ms',
      meta: 'Between 10 ms and 15 ms · checked every minute',
    });
    expect(healthStat({ state: 'degraded', latencyMs: 1800, message: null, checkedAt: null }, [])).toMatchObject({ tone: 'warning', value: '1.8', unit: 's' });
    expect(healthStat({ state: 'unreachable', latencyMs: null, message: 'Connection refused', checkedAt: null }, [], 60)).toEqual({
      tone: 'danger',
      value: 'No answer',
      unit: null,
      meta: 'Connection refused · checked every minute',
    });
    expect(healthStat({ state: 'unknown', latencyMs: null, message: null, checkedAt: null }, []).value).toBe('Not checked yet');
    expect(healthStat({ state: 'healthy', latencyMs: 12, message: null, checkedAt: null }, [], 300).meta).toBe('Checked every 5 minutes');
  });

  it("doesn't claim how often health is checked when the core doesn't say (before step API)", () => {
    expect(healthStat({ state: 'healthy', latencyMs: 12, message: null, checkedAt: null }, [10, 15]).meta).toBe('Between 10 ms and 15 ms');
    expect(healthStat({ state: 'unreachable', latencyMs: null, message: 'Connection refused', checkedAt: null }, []).meta).toBe('Connection refused');
    expect(healthStat({ state: 'healthy', latencyMs: 12, message: null, checkedAt: null }, []).meta).toBe('Answered its last check');
  });

  it('draws the sparkline over the kit’s 230 × 40 box, and nothing with fewer than two checks', () => {
    expect(sparkPoints([10, 20])).toBe('0,20 230,4');
    expect(sparkPoints([5])).toBeNull();
  });

  it('groups "Used by" by endpoint in the connector’s tool order, and sums it for the stat and Delete', () => {
    const rows = usedByRows(
      [
        { tool: 'sql_execute', endpoint: 'dev', mode: 'APPROVAL' },
        { tool: 'sql_query', endpoint: 'dev', mode: 'READ_ONLY' },
        { tool: 'sql_query', endpoint: 'stage-readonly', mode: 'READ_ONLY' },
        { tool: 'sql_execute', endpoint: 'stage-readonly', mode: 'OFF' },
      ],
      SQL_TOOLS.map((t) => t.name),
    );
    expect(rows.map((r) => `${r.endpoint}/${r.tool}`)).toEqual(['dev/sql_query', 'dev/sql_execute', 'stage-readonly/sql_query', 'stage-readonly/sql_execute']);
    const uses = endpointUses(rows, ENDPOINTS);
    expect(uses).toEqual([
      { slug: 'dev', tools: ['sql_query', 'sql_execute'], clients: ['Claude Desktop', 'Codex'] },
      { slug: 'stage-readonly', tools: ['sql_query'], clients: [] },
    ]);
    expect(usedByMeta(uses)).toEqual({ tools: 3, endpoints: ['dev', 'stage-readonly'], clients: 'Claude Desktop and Codex' });
  });

  it('says what Delete does to the secrets: destroyed here, references left in their store', () => {
    const titles = { '/credentials/password': 'Password' };
    expect(secretsSentence([{ field: '/credentials/password', secretId: 's1', name: 'dev-db/password', state: 'stored', version: 3, source: 'local' }], titles)).toBe(
      'Its encrypted password (version 3) is destroyed.',
    );
    expect(secretsSentence([{ field: '/credentials/password', secretId: 's1', name: 'vault://kv/db#pw', state: 'reference', version: null, source: 'vault' }], titles)).toBe(
      'Its reference is removed; the secret itself stays in its store.',
    );
    expect(secretsSentence([], titles)).toBe('Nothing is stored here for it.');
    expect(secretWord([{ pointer: '/credentials/password', title: 'Password' }], '/credentials/password')).toBe('password');
    expect(secretWord([], '/auth/token')).toBe('token');
  });

  it('builds the Activity query of a connection and of one of its tools on one endpoint', () => {
    expect(activityQuery('dev-db')).toBe('connection:dev-db');
    expect(activityQuery('dev-db', 'sql_query', 'dev')).toBe('connection:dev-db tool:sql_query endpoint:dev');
  });
});

describe('Add connection: toasts and names', () => {
  it('says what Save, Test and Delete did, in the kit words', () => {
    expect(savedToast('dev-db', ['dev'], 60)).toEqual({ title: 'Connection dev-db saved', body: 'Added to dev. Health is checked every minute.' });
    expect(savedToast('dev-db', ['dev'])).toEqual({ title: 'Connection dev-db saved', body: 'Added to dev.' });
    expect(savedToast('dev-db', [])).toEqual({ title: 'Connection dev-db saved', body: "It is not on an endpoint yet, so agents can't use it." });
    expect(testToast('dev-db', outcome({ ok: true, latencyMs: 12 }), null)).toEqual({ tone: 'success', title: 'dev-db is healthy', body: 'Answered in 12 ms.' });
    expect(testToast('cache-redis', outcome({ ok: true, latencyMs: 1800 }), null)).toEqual({ tone: 'warning', title: 'cache-redis is slow', body: 'Answered in 1.8 s.' });
    expect(testToast('billing-api', outcome({ error: { code: 'unreachable', message: 'Connection refused.', technical: null } }), null)).toEqual({
      tone: 'danger',
      title: 'billing-api is unreachable',
      body: 'Connection refused.',
    });
    expect(deletedToast('dev-db', ['dev', 'stage-readonly'])).toEqual({ title: 'Connection dev-db deleted', body: 'Removed from dev and stage-readonly.' });
    expect(deletedToast('x', [])).toEqual({ title: 'Connection x deleted', body: 'It was on no endpoint.' });
  });

  it('names a duplicate after its source and lists words the kit way', () => {
    expect(copyName('dev-db', [])).toBe('dev-db-copy');
    expect(copyName('dev-db', ['dev-db-copy', 'dev-db-copy-2'])).toBe('dev-db-copy-3');
    expect(list(['a', 'b', 'c'])).toBe('a, b and c');
    expect(list(['a'])).toBe('a');
  });
});

describe('Add connection: first run (kit ?state=first-run)', () => {
  it('is the first run only while nothing is set up: no connection and no endpoint', () => {
    expect(isFirstRun(0, 0)).toBe(true);
    expect(isFirstRun(1, 0)).toBe(false);
    expect(isFirstRun(0, 1)).toBe(false);
  });

  it('keeps the beginner flow for the sole usable empty default endpoint', () => {
    const empty = endpoint({ slug: 'default', connections: { total: 0, byEnv: {} } });
    expect(starterEndpoint([empty])).toBe(empty);
    expect(isFirstRun(0, [empty])).toBe(true);
    expect(isFirstRun(0, [])).toBe(true);
    expect(isFirstRun(1, [empty])).toBe(false);
    for (const change of [
      { slug: 'custom' }, { connections: { total: 1, byEnv: { dev: 1 } } }, { access: 'read-only' as const },
      { status: 'disabled' as const }, { published: true }, { blockedReason: 'Changed outside GateRook' },
    ]) {
      expect(starterEndpoint([{ ...empty, ...change }])).toBeNull();
      expect(isFirstRun(0, [{ ...empty, ...change }])).toBe(false);
    }
    expect(starterEndpoint([empty, endpoint({})])).toBeNull();
    expect(isFirstRun(0, [empty, endpoint({})])).toBe(false);
  });

  it('names the reused default endpoint in the first-run stepper, preview and save result', () => {
    const facts = { typeName: 'PostgreSQL', name: 'stage-db', env: 'stage' as const, test: null, endpoints: [] };
    expect(stepperSteps(4, facts, true, 'default')[3].name).toBe('Add to endpoint');
    expect(stepperSteps(5, facts, true, 'default')[3].description).toEqual({ text: 'Endpoint default' });
    expect(firstRunPreview(SQL_TOOLS, 'built-in', false, 'stage', 'default').slug).toBe('default');
    expect(firstRunToast('stage-db', 'default', false)).toEqual({
      title: 'Connection stage-db saved',
      body: 'Added to endpoint default with its tools. Next: connect your AI app.',
    });
  });

  it('names step 4 Create endpoint, and once done says the endpoint after the environment', () => {
    const facts = { typeName: 'PostgreSQL', name: 'dev-db', env: 'stage' as const, test: null, endpoints: [] };
    expect(stepperSteps(1, facts, true).map((s) => s.name)).toEqual(['Choose type', 'Configure', 'Test', 'Create endpoint']);
    // After Save every step is complete (5): the card takes the wizard's place.
    const done = stepperSteps(5, facts, true);
    expect(done.map((s) => s.state)).toEqual(['complete', 'complete', 'complete', 'complete']);
    expect(done.filter((s) => s.current)).toEqual([]);
    expect(done[3].description).toEqual({ text: 'Endpoint stage' });
  });

  it('previews the first endpoint’s tools: the defaults on dev, nothing waiting for approval on prod', () => {
    const dev = firstRunPreview(SQL_TOOLS, 'built-in', false, 'dev');
    expect(dev.slug).toBe('dev');
    expect(dev.summary).toBe('4 tools · 3 read only · 1 approval');
    const prod = firstRunPreview(SQL_TOOLS, 'built-in', false, 'prod');
    expect(prod.slug).toBe('prod');
    expect(prod.summary).toBe('4 tools · 3 read only · 1 off');
    expect(prod.rows[3]).toMatchObject({ name: 'sql_execute', mode: 'OFF', note: { icon: 'lock', text: 'Off on prod: read only by default' } });
  });

  it('says what the AI apps can do there, in the kit words', () => {
    expect(firstRunModes(firstRunPreview(SQL_TOOLS, 'built-in', false, 'dev'))).toEqual({
      text: 'Reads run at once. Anything that changes data, like sql_execute, waits for your approval. You can change the modes later.',
      literals: ['sql_execute'],
    });
    expect(firstRunModes(firstRunPreview(SQL_TOOLS, 'built-in', false, 'prod'))).toEqual({
      text: 'Reads run at once. sql_execute is off: prod is read only by default. You can change the modes later.',
      literals: ['sql_execute'],
    });
    expect(firstRunModes(firstRunPreview([], 'mcp-server', true, 'dev')).text).toBe(
      'Its tools come from the server once it runs, and each one waits for your approval. You can change the modes later.',
    );
  });

  it('requires an explicit Bypass choice instead of inheriting it from connector defaults', () => {
    const tools = SQL_TOOLS.map((t) => ({ ...t, defaultMode: 'bypass', prodDefaultMode: 'bypass' }));
    expect(firstRunPreview(tools, 'built-in', false, 'dev').rows.every((r) => r.mode === 'APPROVAL')).toBe(true);
    expect(firstRunPreview(tools, 'built-in', false, 'prod').rows.every((r) => r.mode === 'OFF')).toBe(true);
  });

  it('names Bypass tools returned by the endpoint instead of hiding them in the ready summary', () => {
    const rows = [{ name: 'sql_execute', mode: 'BYPASS' as const }, { name: 'restart', mode: 'APPROVAL' as const }];
    expect(toolsSentence(toolsLine(rows), 'dev')).toEqual({
      text: 'restart waits for your approval; sql_execute runs without approval (Bypass)',
      literals: ['restart', 'sql_execute'],
    });
    const preview = firstRunPreview(SQL_TOOLS, 'built-in', false, 'dev');
    expect(firstRunModes({ ...preview, rows: preview.rows.map((r) => r.name === 'sql_execute' ? { ...r, mode: 'BYPASS' } : r) }).text)
      .toBe('Reads run at once. sql_execute runs without approval (Bypass). You can change the modes later.');
  });

  it('words the ready card’s Tools from the endpoint’s tools as the core answered them', () => {
    const line = toolsLine([
      { name: 'sql_list_schemas', mode: 'READ_ONLY' },
      { name: 'sql_describe_table', mode: 'READ_ONLY' },
      { name: 'sql_query', mode: 'READ_ONLY' },
      { name: 'sql_execute', mode: 'APPROVAL' },
    ]);
    expect(line).toEqual({ reads: ['sql_list_schemas', 'sql_describe_table', 'sql_query'], approval: ['sql_execute'], bypass: [], off: [] });
    expect(toolsSentence(line, 'dev').text).toBe(
      'sql_list_schemas, sql_describe_table and sql_query run at once (read only); sql_execute waits for your approval',
    );
    expect(toolsSentence({ reads: ['k8s_get'], approval: [], bypass: [], off: ['k8s_apply', 'k8s_rollout_restart'] }, 'prod')).toEqual({
      text: 'k8s_get runs at once (read only); k8s_apply and k8s_rollout_restart are off: prod is read only by default',
      literals: ['k8s_get', 'k8s_apply', 'k8s_rollout_restart'],
    });
    expect(toolsSentence({ reads: [], approval: [], bypass: [], off: [] }, 'dev').text).toBe('They come from the server once it runs');
  });

  it('says what the test checks where the type signs in, and what Save did', () => {
    expect(firstRunTestText('the database')).toBe(
      'GateRook connects from this computer with what you entered and checks what that user may do in the database.',
    );
    expect(firstRunTestText('the server')).toContain('may do on the server.');
    expect(firstRunTestText('the API')).toContain('may do with the API.');
    expect(firstRunToast('dev-db', 'dev')).toEqual({
      title: 'Connection dev-db saved',
      body: 'Endpoint dev created with its tools. Next: connect your AI app.',
    });
  });
});
