import { ActivityEvent } from '../../core/api/activity.api';
import { ConnectionRow } from '../../core/api/connections.api';
import { EndpointSummary, ToolGroup, ToolRow } from '../../core/api/endpoints.api';
import { PlaygroundCallView, PlaygroundTool, PlaygroundTools } from '../../core/api/playground.api';
import { Decision, Env, JsonObject, JsonValue, ToolMode } from '../../core/api/types';

/**
 * The demo world as the core answers it (GW_DEMO=1, trimmed to what the tests need): endpoints dev (stage, 8
 * connections), stage-readonly and incident-prod; dev's tools and the mode of every tool × connection pair.
 */

export const NOW = Date.parse('2026-10-22T12:30:00Z');

const ANN = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const DESTRUCTIVE = { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false };

function summary(slug: string, env: Env, connections: number, extra: Partial<EndpointSummary> = {}): EndpointSummary {
  return {
    slug,
    description: null,
    url: `http://127.0.0.1:7465/mcp/${slug}`,
    env,
    access: 'standard',
    status: 'running',
    tools: { read: 14, approval: 3, off: 5 },
    connections: { total: connections, byEnv: { [env]: connections } },
    health: {},
    approvalsWaiting: 0,
    clients: [],
    tokens: { active: 1, expiringSoon: 0 },
    published: false,
    risk: { state: 'absent' } as unknown as EndpointSummary['risk'],
    blockedReason: null,
    version: 1,
    ...extra,
  };
}

export const ENDPOINTS: EndpointSummary[] = [
  summary('incident-prod', 'prod', 3, { tools: { read: 7, approval: 0, off: 3 } }),
  summary('dev', 'stage', 8),
  summary('stage-readonly', 'stage', 3, { access: 'read-only', tools: { read: 8, approval: 0, off: 4 } }),
];

function conn(name: string, type: string, typeName: string, env: Env, host: string | null): ConnectionRow {
  return {
    name,
    type,
    typeName,
    env,
    host,
    badges: [],
    secretSource: 'encrypted',
    health: { state: 'unreachable', latencyMs: null, message: null, checkedAt: null },
    usedBy: ['dev'],
    verified: true,
    version: 1,
  };
}

export const CONNECTIONS: ConnectionRow[] = [
  conn('dev-db', 'postgres', 'PostgreSQL', 'dev', 'db.dev.internal:5432/app'),
  conn('dev-vm', 'ssh', 'SSH', 'dev', '10.20.0.14'),
  conn('billing-api', 'http', 'HTTP API', 'stage', 'billing.stage.acme.dev'),
  conn('prod-replica', 'postgres', 'PostgreSQL', 'prod', 'replica.prod.internal:5432/app'),
  conn('prod-logs', 'logs', 'Logs', 'prod', 'loki.prod.internal:3100'),
  conn('prod-vm', 'ssh', 'SSH', 'prod', '10.40.0.2'),
];

function row(name: string, mode: ToolMode, annotations = ANN): ToolRow {
  return {
    toolKey: name,
    name,
    annotations,
    declaredBy: null,
    mode,
    storedMode: mode,
    readOnlyAllowed: true,
    reason: null,
    readOnlyConfirmed: false,
    definitionHash: null,
    overrides: null,
    version: 0,
  };
}

/** `GET /api/endpoints/dev/tools`: the connection groups, the core's own tools last. */
export const DEV_GROUPS: ToolGroup[] = [
  { group: 'PostgreSQL', connection: 'dev-db', env: 'dev', tools: [row('sql_list_schemas', 'READ_ONLY'), row('sql_query', 'READ_ONLY'), row('sql_execute', 'APPROVAL', DESTRUCTIVE)] },
  { group: 'SSH', connection: 'dev-vm', env: 'dev', tools: [row('ssh_run', 'READ_ONLY', DESTRUCTIVE), row('ssh_exec', 'OFF', DESTRUCTIVE)] },
  { group: 'HTTP API', connection: 'billing-api', env: 'stage', tools: [row('http_request', 'APPROVAL', DESTRUCTIVE)] },
  { group: 'Core', connection: null, env: null, tools: [row('list_connections', 'READ_ONLY'), row('describe_connection', 'READ_ONLY')] },
];

export const PROD_GROUPS: ToolGroup[] = [
  { group: 'PostgreSQL', connection: 'prod-replica', env: 'prod', tools: [row('sql_query', 'READ_ONLY')] },
  { group: 'Logs', connection: 'prod-logs', env: 'prod', tools: [row('logs_search', 'READ_ONLY')] },
  { group: 'SSH', connection: 'prod-vm', env: 'prod', tools: [row('ssh_run', 'READ_ONLY', DESTRUCTIVE), row('ssh_exec', 'OFF', DESTRUCTIVE)] },
  { group: 'Core', connection: null, env: null, tools: [row('list_connections', 'READ_ONLY')] },
];

function tool(name: string, mode: PlaygroundTool['mode'], properties: JsonObject, required: string[], extra: Partial<PlaygroundTool> = {}): PlaygroundTool {
  return {
    name,
    title: name,
    description: `${name} description.`,
    mode,
    runnable: mode !== 'OFF',
    reason: mode === 'OFF' ? `${name} is Off on dev. Turn it on in the endpoint's Tools to call it.` : null,
    needsApproval: mode === 'APPROVAL',
    connections: [],
    annotations: ANN,
    declaredBy: null,
    inputSchema: { type: 'object', additionalProperties: false, required, properties },
    ...extra,
  };
}

const SQL = { type: 'string', minLength: 1, maxLength: 100000, description: 'One SELECT, WITH … SELECT, VALUES, TABLE, SHOW or EXPLAIN. Runs in a read-only transaction.' };
const LIMIT = { type: 'integer', minimum: 1, maximum: 1000, default: 50, description: 'Max rows; the endpoint caps it.' };

export function playTools(connection = 'dev-db', vm = 'dev-vm'): PlaygroundTool[] {
  return [
    tool('sql_query', 'READ_ONLY', { connection: { type: 'string', enum: [connection] }, sql: SQL, limit: LIMIT }, ['connection', 'sql'], { connections: [connection], description: 'Run one SELECT in a read-only transaction. PII columns come back masked.' }),
    tool('sql_list_schemas', 'READ_ONLY', { connection: { type: 'string', enum: [connection] }, pattern: { type: 'string' } }, ['connection'], { connections: [connection] }),
    tool('sql_execute', 'APPROVAL', { connection: { type: 'string', enum: [connection] }, sql: { ...SQL, description: 'INSERT, UPDATE or DELETE.' }, note: { type: 'string', maxLength: 500 } }, ['connection', 'sql'], { connections: [connection] }),
    tool('ssh_run', 'READ_ONLY', { connection: { type: 'string', enum: [vm] }, runbook: { type: 'string' } }, ['connection', 'runbook'], { connections: [vm], needsApproval: true }),
    tool('ssh_exec', 'OFF', { connection: { type: 'string' }, command: { type: 'string', minLength: 1 } }, ['connection', 'command']),
    tool('http_request', 'APPROVAL', { connection: { type: 'string', enum: ['billing-api'] }, method: { type: 'string', enum: ['POST', 'PUT', 'DELETE'] }, path: { type: 'string', minLength: 1 } }, ['connection', 'method', 'path'], { connections: ['billing-api'] }),
    tool('list_connections', 'READ_ONLY', {}, []),
    tool('describe_connection', 'READ_ONLY', { connection: { type: 'string' } }, ['connection']),
    tool('approval_status', 'ALWAYS', { handle: { type: 'string' } }, ['handle']),
  ];
}

/** incident-prod: reads, a runbook that may wait for a person, the shell Off. */
export function prodTools(): PlaygroundTool[] {
  return playTools('prod-replica', 'prod-vm').filter((t) => ['sql_query', 'ssh_run', 'ssh_exec', 'list_connections', 'approval_status'].includes(t.name));
}

export function toolsAnswer(endpoint = 'dev', tools = playTools()): PlaygroundTools {
  return { endpoint, era: '2026-07-28', tools, wirePreview: {} };
}

/** A `POST /api/playground/call` answer. */
export function callView(over: Partial<PlaygroundCallView> & { readonly tool?: string; readonly args?: JsonObject; readonly traceId?: string } = {}): PlaygroundCallView {
  const { tool: name = 'sql_query', args = { connection: 'dev-db', sql: 'SELECT 1', limit: 50 }, traceId = '9e3b51d07a4c4f0e8b2d6c19a5f7e340', ...rest } = over;
  const structured: JsonValue = rest.structured ?? null;
  const text = rest.text === undefined ? 'ok' : rest.text;
  const callId = rest.callId ?? 'tc_one';
  return {
    callId,
    wire: rest.wire ?? {
      request: { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name, arguments: args } },
      response: {
        jsonrpc: '2.0',
        id: 3,
        result: {
          content: [{ type: 'text', text }],
          ...(structured !== null ? { structuredContent: structured } : {}),
          isError: rest.isError ?? false,
          _meta: { 'ai.msrv.gaterook/callId': callId, 'ai.msrv.gaterook/traceId': traceId },
        },
      },
      httpStatus: 200,
    },
    structured,
    text,
    isError: rest.isError ?? false,
    meta: rest.meta ?? { durationMs: 38, bytes: 3174, masked: 0, truncated: false },
    approval: rest.approval ?? null,
  };
}

/** The kit's sql_query result: rows and columns, two columns masked. */
export const TABLE: JsonObject = {
  columns: ['id', 'status', 'customer_email', 'total'],
  types: ['int4', 'text', 'text', 'numeric'],
  rows: [
    [48240, 'pending', '‹masked›', 84.9],
    [48239, 'synced', '‹masked›', 129],
  ],
  rowCount: 2,
  truncated: false,
  masked: ['customer_email'],
};

/** A history row (`GET /api/playground/history`). */
export function event(id: number, over: Partial<ActivityEvent> & { readonly decision?: Decision } = {}): ActivityEvent {
  return {
    id,
    callId: `tc_${id}`,
    at: '2026-10-22T12:29:52Z',
    phase: 'completed',
    decision: 'allowed',
    tool: 'sql_query',
    connection: 'dev-db',
    env: 'dev',
    client: 'playground',
    endpoint: 'dev',
    tokenLabel: 'playground',
    durationMs: 38,
    rule: null,
    approvalId: null,
    traceId: null,
    errorKind: null,
    errorMessage: null,
    ...over,
  };
}
