import { ToolGroup, ToolRow } from '../core/api/endpoints.api';
import { Env, JsonObject, ToolAnnotations, ToolMode } from '../core/api/types';
import { ToolPolicyGroup } from '../kit/elements/tool-policy-list/tool-policy-view';

/**
 * Endpoint tools of the demo world (BLUEPRINT §9) as `GET /api/endpoints/{slug}/tools` returns them, for the
 * tool-policy-list gallery (design/elements/tool-policy-list.html). Dev only.
 */

const READ: ToolAnnotations = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const WRITE: ToolAnnotations = { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false };
const READ_ONCE: ToolAnnotations = { readOnlyHint: true, destructiveHint: false, idempotentHint: false, openWorldHint: false };
const WRITE_IDEMPOTENT: ToolAnnotations = { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false };
const MIXED: ToolAnnotations = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false };

const tool = (name: string, mode: ToolMode, annotations: ToolAnnotations, extra: Partial<ToolRow> = {}): ToolRow => ({
  toolKey: name,
  name,
  annotations,
  declaredBy: null,
  mode,
  storedMode: mode,
  readOnlyAllowed: !annotations.destructiveHint,
  reason: annotations.destructiveHint ? `${name} changes data, so Read only isn't available: it always waits for approval` : null,
  readOnlyConfirmed: false,
  definitionHash: `sha256-${name}`,
  overrides: null,
  version: 0,
  ...extra,
});

const group = (name: string, connection: string, env: Env, tools: ToolRow[]): ToolGroup => ({ group: name, connection, env, tools });

/** Endpoint dev: five of its eight connections, as the kit's demo shows them. */
export const DEMO_TOOL_GROUPS: ToolPolicyGroup[] = [
  {
    type: 'postgres',
    detail: 'PostgreSQL 16',
    group: group('PostgreSQL', 'dev-db', 'dev', [
      tool('sql_list_schemas', 'READ_ONLY', READ),
      tool('sql_describe_table', 'READ_ONLY', READ),
      tool('sql_query', 'READ_ONLY', READ, { overrides: { rowLimit: 200 } }),
      tool('sql_execute', 'APPROVAL', WRITE),
    ]),
  },
  {
    type: 'ssh',
    detail: 'SSH · 10.20.0.14',
    group: group('SSH', 'dev-vm', 'dev', [tool('ssh_run', 'READ_ONLY', MIXED), tool('ssh_exec', 'OFF', WRITE)]),
  },
  {
    type: 'kubernetes',
    detail: 'Kubernetes · namespace app-dev',
    group: group('Kubernetes', 'dev-cluster', 'dev', [
      tool('k8s_get', 'READ_ONLY', READ),
      tool('k8s_logs', 'OFF', READ_ONCE),
      tool('k8s_rollout_restart', 'APPROVAL', WRITE),
      tool('k8s_apply', 'OFF', WRITE_IDEMPOTENT),
    ]),
  },
  {
    type: 'logs',
    detail: 'Loki',
    group: group('Logs', 'app-logs', 'dev', [tool('logs_search', 'READ_ONLY', READ), tool('logs_tail', 'OFF', READ_ONCE)]),
  },
  {
    type: 'http',
    detail: 'HTTP API · billing.stage.acme.dev',
    group: group('HTTP API', 'billing-api', 'stage', [
      tool('http_get', 'OFF', READ),
      tool('http_request', 'APPROVAL', WRITE, { overrides: { maxResponseKb: 64 } }),
    ]),
  },
];

/** Endpoint incident-prod (read only): the stricter prod policy, sql_execute locked off. */
export const DEMO_TOOL_GROUPS_PROD: ToolPolicyGroup[] = [
  {
    type: 'postgres',
    detail: 'PostgreSQL read replica · Vault dynamic credentials',
    group: group('PostgreSQL', 'prod-replica', 'prod', [
      tool('sql_describe_table', 'READ_ONLY', READ),
      tool('sql_query', 'READ_ONLY', READ),
      tool('sql_execute', 'OFF', WRITE),
    ]),
  },
  {
    type: 'logs',
    detail: 'GCP Cloud Logging · impersonation',
    group: group('Logs', 'prod-logs', 'prod', [tool('logs_search', 'READ_ONLY', READ)]),
  },
];

const declared = (name: string, annotations: ToolAnnotations): ToolRow =>
  tool(name, 'APPROVAL', annotations, { declaredBy: 'github-mcp', readOnlyAllowed: false, reason: null });

/** github-mcp just added to endpoint dev: 4 of its 26 tools, all in Approval. */
export const DEMO_TOOL_GROUPS_EXTERNAL: ToolPolicyGroup[] = [
  {
    type: 'mcp-server',
    detail: 'MCP server · signed catalog entry v0.6.2',
    group: group('MCP server', 'github-mcp', 'dev', [
      declared('list_issues', READ_ONCE),
      declared('search_code', READ),
      declared('create_issue', MIXED),
      declared('merge_pull_request', WRITE),
    ]),
  },
];

/** The tools' sentences, as `GET /api/connector-types/{type}` describes them. */
export const DEMO_TOOL_DESCRIPTIONS: Record<string, string> = {
  sql_list_schemas: 'List the schemas the connection user can see.',
  sql_describe_table: 'Columns, types, indexes and foreign keys of one table.',
  sql_query: 'Run one SELECT in a read-only transaction. PII columns come back masked.',
  sql_execute: 'INSERT, UPDATE or DELETE. You approve the exact statement.',
  ssh_run: 'Run a saved runbook with typed parameters, such as compose-ps.',
  ssh_exec: 'Run any shell command on the host.',
  k8s_get: 'Get or list resources in namespace app-dev.',
  k8s_logs: 'Read the logs of one container.',
  k8s_rollout_restart: 'Restart a deployment, one pod at a time.',
  k8s_apply: 'Apply a manifest. You approve the diff.',
  logs_search: 'Search log lines with a LogQL query.',
  logs_tail: 'Stream new log lines for up to 5 minutes.',
  http_get: 'GET or HEAD on the allowed paths of the API.',
  http_request: 'POST, PUT or DELETE on the allowed paths. You approve the exact request.',
  list_issues: 'List issues of a repository, with filters.',
  search_code: 'Search code across the repositories the GitHub token can see.',
  create_issue: 'Open an issue in a repository.',
  merge_pull_request: 'Merge a pull request into its base branch.',
};

const sections = (sql: JsonObject, ssh: JsonObject, http: JsonObject, limits: JsonObject): JsonObject => ({ sql, ssh, http, limits });

/** The effective policy sections of the demo world (BLUEPRINT §9 numbers): dev, stage and prod. */
export const DEMO_POLICY_SECTIONS: Record<Env, JsonObject> = {
  dev: sections(
    { rowLimit: 1000, statementTimeoutMs: 5000, maxResponseKb: 512 },
    { commandTimeoutSeconds: 30, maxOutputKb: 256 },
    { toolTimeoutSeconds: 10, maxResponseKb: 512 },
    { toolTimeoutSeconds: 10, maxResponseKb: 512, logLines: 1000 },
  ),
  stage: sections(
    { rowLimit: 1000, statementTimeoutMs: 5000, maxResponseKb: 512 },
    { commandTimeoutSeconds: 30, maxOutputKb: 256 },
    { toolTimeoutSeconds: 10, maxResponseKb: 256 },
    { toolTimeoutSeconds: 10, maxResponseKb: 512, logLines: 1000 },
  ),
  prod: sections(
    { rowLimit: 100, statementTimeoutMs: 3000, maxResponseKb: 256 },
    { commandTimeoutSeconds: 30, maxOutputKb: 256 },
    { toolTimeoutSeconds: 5, maxResponseKb: 256 },
    { toolTimeoutSeconds: 5, maxResponseKb: 256, logLines: 200 },
  ),
};
