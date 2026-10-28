import { SecretSourceOption } from '../core/api/connector-types.api';
import { StoredSecret, TestOutcome } from '../core/api/connections.api';
import { ConnectorCardView } from '../kit/elements/connector-card/connector-card';
import { NotificationRequest } from '../kit/elements/approval-notification/approval-notification';
import { GettingStarted, SetupNode } from '../core/api/overview.api';
import { EndpointRisk, IssuedToken, SplitPreview } from '../core/api/endpoints.api';
import { SplitClient, SplitOption } from '../kit/elements/risk-callout/split-dialog';

/**
 * The demo world of BLUEPRINT §9 for the gallery (dev only, never imported by production code). Shapes are the
 * admin API's; values are the ones the kit's pages show, so each demo can be compared with design/elements/*.html.
 */

const steps = (states: string[], details: Record<string, object> = {}): GettingStarted['steps'] =>
  (['master-key', 'connection', 'endpoint', 'client', 'playground', 'notifications'] as const).map((id, i) => ({
    id,
    state: states[i] as 'done' | 'current' | 'todo',
    detail: (details[id] ?? {}) as GettingStarted['steps'][number]['detail'],
  }));

const DONE_DETAILS = {
  'master-key': { lastBackupAt: '2026-10-10T14:30:00+02:00' },
  connection: { name: 'dev-db', typeName: 'PostgreSQL 16' },
  endpoint: { name: 'dev', tools: 22 },
  client: { clients: ['Claude Desktop', 'Codex'] },
  playground: { tool: 'sql_query', latencyMs: 38 },
  notifications: { channels: ['Desktop', 'Slack'] },
};

/** `/api/overview` getting-started: fresh install, in progress, complete. */
export const DEMO_GETTING_STARTED: Record<'fresh' | 'progress' | 'complete', GettingStarted> = {
  fresh: { done: 0, total: 6, dismissed: false, steps: steps(['current', 'todo', 'todo', 'todo', 'todo', 'todo']) },
  progress: {
    done: 3,
    total: 6,
    dismissed: false,
    steps: steps(['done', 'done', 'done', 'current', 'todo', 'todo'], DONE_DETAILS),
  },
  complete: { done: 6, total: 6, dismissed: false, steps: steps(['done', 'done', 'done', 'done', 'done', 'done'], DONE_DETAILS) },
};

const node = (id: SetupNode['id'], state: SetupNode['state'], status: string, count = 0): SetupNode => ({ id, state, status, count });

/** `/api/overview` setup map: all set up, first run, needs attention. */
export const DEMO_SETUP_MAP: Record<'done' | 'first' | 'attention', SetupNode[]> = {
  done: [
    node('secrets', 'done', 'Master key backed up · 11 secrets', 11),
    node('connections', 'done', '12 connections · all healthy', 12),
    node('policies', 'done', '3 environments · mutations need approval', 3),
    node('endpoints', 'done', '3 endpoints · 2 active tokens', 3),
    node('clients', 'done', '2 connected · 1 detected', 2),
  ],
  first: [
    node('secrets', 'done', 'Master key ready · no secrets yet'),
    node('connections', 'todo', 'None yet · start here'),
    node('policies', 'todo', 'Safe defaults · not reviewed yet'),
    node('endpoints', 'todo', 'None yet · needs a connection first'),
    node('clients', 'todo', '3 detected · needs an endpoint first'),
  ],
  attention: [
    node('secrets', 'done', 'Master key backed up · 11 secrets', 11),
    node('connections', 'attention', '12 connections · billing-api unreachable', 12),
    node('policies', 'done', '3 environments · mutations need approval', 3),
    node('endpoints', 'done', '3 endpoints · 2 active tokens', 3),
    node('clients', 'attention', '3 connected · Cursor needs restart', 3),
  ],
};

const src = (ingredient: string, tool: string, connection: string, text: string) => ({ ingredient, tool, connection, text });

const risk = (
  state: string,
  levels: [string, string, string],
  sources: EndpointRisk['sources'],
  extra: Partial<EndpointRisk> = {},
): EndpointRisk => ({
  state,
  underlying: state,
  present: (['privateData', 'untrustedContent', 'externalChannel'] as const).filter((_, i) => levels[i] !== 'absent'),
  ingredients: {
    privateData: levels[0] as 'present',
    untrustedContent: levels[1] as 'present',
    externalChannel: levels[2] as 'present',
  },
  sources,
  published: false,
  dismissed: null,
  ...extra,
});

/** `/api/endpoints/{slug}/risk` of the demo world (risk-callout). */
export const DEMO_RISK = {
  dev: risk('present', ['present', 'present', 'present'], [
    src('privateData', 'sql_query', 'dev-db', 'sql_query reads dev-db'),
    src('untrustedContent', 'logs_search', 'app-logs', 'logs_search returns log lines users wrote'),
    src('externalChannel', 'http_request', 'billing-api', 'http_request can POST to billing-api'),
  ]),
  stage: risk(
    'present',
    ['present', 'present', 'present'],
    [
      src('privateData', 'sql_query', 'stage-db', 'sql_query reads stage-db'),
      src('untrustedContent', 'http_get', 'billing-api', 'sql_query rows and http_get responses'),
      src('externalChannel', 'http_get', 'billing-api', 'http_get can put data in a URL to billing-api'),
    ],
    { published: true },
  ),
  partial: risk('partial', ['present', 'absent', 'present'], [
    src('privateData', 'aws_describe', 'aws-stage', 'aws_describe reads aws-stage'),
    src('externalChannel', 'http_request', 'billing-api', 'http_request can POST to billing-api'),
  ]),
  limited: risk('limited', ['present', 'present', 'limited'], [
    src('privateData', 'sql_query', 'prod-replica', 'sql_query reads prod-replica'),
    src('untrustedContent', 'logs_search', 'prod-logs', 'logs_search on prod-logs, sql_query rows'),
    src('externalChannel', 'ssh_run', 'prod-vm', 'ssh_run runs 2 fixed runbooks on prod-vm, no free-text parameters'),
  ]),
  dismissed: risk('dismissed', ['present', 'present', 'present'], [], {
    dismissed: { at: '2026-10-21T16:42:00+02:00', by: 'you', reason: 'billing-api is a sandbox with test cards only' },
  }),
};

/** The split dialog of endpoint dev (risk-callout, endpoint-detail #ep-dlg-split). */
export const DEMO_SPLIT: { options: SplitOption[]; clients: SplitClient[]; preview: SplitPreview } = {
  options: [
    {
      connection: 'billing-api',
      env: 'stage',
      ingredient: 'externalChannel',
      tools: ['http_request', 'http_get'],
      effect: 'Agents on dev can no longer send data out.',
      meta: 'Recommended · removes the risk · Codex called http_request today',
      recommended: true,
    },
    {
      connection: 'app-logs',
      env: 'dev',
      ingredient: 'untrustedContent',
      tools: ['logs_search', 'logs_tail'],
      effect: 'Agents on dev no longer read text other people wrote.',
      meta: 'Leaves private data and an external channel on dev',
      recommended: false,
    },
  ],
  clients: [
    { id: 'codex', name: 'Codex', note: 'called http_request today', checked: true },
    { id: 'claude-desktop', name: 'Claude Desktop', note: null, checked: false },
  ],
  preview: {
    newSlug: 'dev-billing',
    movedTools: ['http_request', 'http_get'],
    before: risk('present', ['present', 'present', 'present'], []),
    remaining: risk('none', ['present', 'present', 'absent'], []),
    moved: risk('none', ['absent', 'absent', 'present'], []),
  },
};

/** The answer of Issue token: the only copy of the token. */
export const DEMO_ISSUED: IssuedToken = {
  id: 't9',
  name: 'cursor-laptop',
  token: 'gwt_Vb8rT2kLq9XwN4cZ7mHs1PdF6yJa3eUg5RiK0oBt',
  prefix: 'gwt_Vb8r',
  expiresAt: '2027-04-24T14:30:00+01:00',
};

const action = (text: string, language: string) => ({
  kind: language,
  language,
  text,
  segments: [{ t: 'text' as const, v: text }],
  hiddenChars: 0,
  confusables: 0,
  warning: null,
  diff: null,
});

/** The approval requests of the demo world as the notifications show them. */
export const DEMO_REQUESTS: Record<'sql' | 'prod' | 'approved' | 'expired', NotificationRequest> = {
  sql: {
    tool: 'sql_execute',
    connection: 'dev-db',
    env: 'dev',
    endpoint: 'dev',
    client: 'codex',
    tokenLabel: 'codex-laptop',
    requestedAt: '2026-10-22T14:29:12+02:00',
    expiresAt: '2026-10-22T14:34:12+02:00',
    action: action("UPDATE orders SET status = 'cancelled' WHERE id = 48213;", 'sql'),
    summary: null,
    rule: { id: 'dev.sql.dml-approval', text: 'Every change waits for a person on dev.' },
    impact: [{ label: 'Estimated rows', text: '1' }],
    commentPolicy: 'optional',
    status: 'pending',
    decidedAt: null,
    decidedVia: null,
    executedAt: null,
  },
  prod: {
    tool: 'ssh_run',
    connection: 'prod-vm',
    env: 'prod',
    endpoint: 'incident-prod',
    client: 'claude-desktop',
    tokenLabel: null,
    requestedAt: '2026-10-22T14:29:05+02:00',
    expiresAt: '2026-10-22T14:39:05+02:00',
    action: action('# runbook restart-api · parameters: none\nsudo systemctl restart api.service', 'sh'),
    summary: 'runbook restart-api · sudo systemctl restart api.service',
    rule: { id: 'prod.ssh.runbook-approval', text: 'Runbooks on prod wait for a person with a comment.' },
    impact: [],
    commentPolicy: 'required',
    status: 'pending',
    decidedAt: null,
    decidedVia: null,
    executedAt: null,
  },
  approved: {
    tool: 'sql_execute',
    connection: 'dev-db',
    env: 'dev',
    endpoint: 'dev',
    client: 'codex',
    tokenLabel: 'codex-laptop',
    requestedAt: '2026-10-22T12:20:31+02:00',
    expiresAt: '2026-10-22T12:25:31+02:00',
    action: action("UPDATE orders SET status = 'cancelled' WHERE id = 48213;", 'sql'),
    summary: null,
    rule: null,
    impact: [],
    commentPolicy: 'optional',
    status: 'completed',
    decidedAt: '2026-10-22T12:21:18.000+02:00',
    decidedVia: 'app',
    executedAt: '2026-10-22T12:21:18.038+02:00',
  },
  expired: {
    tool: 'http_request',
    connection: 'billing-api',
    env: 'stage',
    endpoint: 'dev',
    client: 'codex',
    tokenLabel: 'codex-laptop',
    requestedAt: '2026-10-22T13:05:40+02:00',
    expiresAt: '2026-10-22T13:15:40+02:00',
    action: action('POST https://billing.stage.acme.dev/v1/invoices/inv_2291/void', 'http'),
    summary: null,
    rule: null,
    impact: [],
    commentPolicy: 'optional',
    status: 'expired',
    decidedAt: null,
    decidedVia: null,
    executedAt: null,
  },
};

/** The catalog grid on Connectors (connector-card). */
export const DEMO_CONNECTOR_CARDS: ConnectorCardView[] = [
  { type: 'postgres', variant: null, name: 'PostgreSQL', icon: 'database', kind: 'built-in', version: 'v1.4.0', signed: null, description: 'Queries, schema and EXPLAIN. Writes wait for approval.', tools: 4, action: 'add' },
  { type: 'template', variant: 'pg-slow-queries', name: 'PostgreSQL slow queries', icon: 'file-code', kind: 'template', version: 'v1.0.0', signed: true, description: 'pg_slow_queries and pg_locks as read-only SQL tools.', literals: ['pg_slow_queries', 'pg_locks'], tools: 2, action: 'install' },
  { type: 'clickhouse', variant: null, name: 'ClickHouse', icon: 'database', kind: 'plugin', version: 'v0.6.1', signed: true, description: 'Analytics queries with row and time limits. Read only.', tools: 6, action: 'install' },
  {
    type: 'rabbitmq',
    variant: null,
    name: 'RabbitMQ',
    icon: 'puzzle',
    kind: 'plugin',
    version: 'v0.3.0',
    signed: false,
    description: 'Queue depth, consumers and dead letters. Community code, not reviewed.',
    tools: 4,
    action: 'install',
    package: { file: 'gw-rabbitmq-0.3.0.jar', size: '1.4 MB', sha256: '5d41c0f2a9e83b7c1f6e04d2b8a97c3e61f0d5b2e4a8c7f9013b6d2e5a4c8f71' },
  },
  { type: 'mcp-server', variant: 'github', name: 'GitHub', icon: 'plug', kind: 'mcp-server', version: 'v0.6.2', signed: true, description: 'Issues, pull requests and code search. Needs a GitHub token.', tools: 26, action: 'configure' },
  { type: 'jira-service-desk', variant: null, name: 'Jira Service Desk', icon: 'puzzle', kind: 'plugin', version: 'v1.2.0', signed: true, description: 'Search requests and read queues. Comments and transitions wait for approval. 5 tools.', tools: 5, action: 'update', updateTo: 'v1.3.0' },
];

/**
 * Secret sources as a gateway with Vault, GCP and AWS stores connected shows them (the demo world connects none, so
 * the live form spec marks them unavailable); Short-lived as with Vault dynamic credentials configured.
 */
export const DEMO_SECRET_SOURCES: SecretSourceOption[] = [
  { id: 'local', label: 'Encrypted here', available: true, reason: null },
  { id: 'vault', label: 'HashiCorp Vault', available: true, reason: null },
  { id: 'gcp-sm', label: 'GCP Secret Manager', available: true, reason: null },
  { id: 'aws-sm', label: 'AWS Secrets Manager', available: true, reason: null },
  { id: 'dynamic', label: 'Short-lived (Vault dynamic credentials)', available: true, reason: null },
];

/** stage-db's password from Vault KV, prod-replica's user from Vault dynamic credentials (connection-form). */
export const DEMO_REFERENCES: Record<'stage' | 'prodReplica', StoredSecret[]> = {
  stage: [{ field: '/credentials/password', secretId: null, name: 'vault://kv/stage/db#password', state: 'reference', version: null, source: 'vault' }],
  prodReplica: [
    { field: '/credentials/password', secretId: null, name: 'vault://database/creds/prod-replica-readonly', state: 'reference', version: null, source: 'dynamic' },
  ],
};

/** The stored password of dev-db in the kit's edit view: version 3. */
export const DEMO_STORED_PASSWORD: StoredSecret[] = [
  { field: '/credentials/password', secretId: '8e9a0404-511f-4053-a17b-75cccfb38e80', name: 'dev-db/password', state: 'stored', version: 3, source: 'local' },
];

/** Test connection results of connection-form: a user that can write, and a host that doesn't answer. */
export const DEMO_TEST: Record<'passed' | 'failed', TestOutcome> = {
  passed: {
    ok: true,
    latencyMs: 12,
    serverVersion: 'PostgreSQL 16.4',
    identity: 'app_rw',
    permissions: { level: 'WRITE', read: 'SELECT on 42 tables', write: 'INSERT, UPDATE, DELETE on public.*', admin: [], details: null },
    hints: [{ severity: 'WARNING', code: 'write-grant', field: '/credentials/user', text: 'This user can write.', snippet: null }],
    checks: [],
    error: null,
    needsConfirmation: null,
    confirmationId: null,
    suggestedPolicyChange: null,
    details: null,
  },
  failed: {
    ok: false,
    latencyMs: 5000,
    serverVersion: null,
    identity: null,
    permissions: null,
    hints: [],
    checks: [],
    error: {
      code: 'unreachable',
      message: 'The connection timed out after 5 s. Check the VPN or the host, then test again.',
      technical:
        'org.postgresql.util.PSQLException: The connection attempt failed.\nCaused by: java.net.SocketTimeoutException: Connect timed out\n  host=db.dev.internal port=5432 sslmode=verify-full connectTimeout=5s',
    },
    needsConfirmation: null,
    confirmationId: null,
    suggestedPolicyChange: null,
    details: null,
  },
};
