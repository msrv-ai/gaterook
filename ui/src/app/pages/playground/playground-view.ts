import { ActivityEvent } from '../../core/api/activity.api';
import { ConnectionRow } from '../../core/api/connections.api';
import { EndpointSummary, ToolGroup } from '../../core/api/endpoints.api';
import { Era, PlaygroundCallView, PlaygroundTool } from '../../core/api/playground.api';
import { Decision, Env, JsonObject, JsonValue, ToolMode } from '../../core/api/types';
import { duration, formatBytes, formatCount } from '../../core/time/time-format';
import { Look, decisionLook } from '../../kit/elements/shared/dictionaries';
import { ToolPolicyGroup } from '../../kit/elements/tool-policy-list/tool-policy-view';
import { SchemaNode } from '../../schema-form/compile';
import { valueProblem } from '../../schema-form/messages';

/**
 * What the Playground shows (design/pages/playground.html, ARCHITECTURE.md §8.4 "playground"), from the core's answers:
 * `GET /api/playground/tools`, `GET /api/endpoints/{slug}/tools` (the mode of every tool × connection pair),
 * `POST /api/playground/call` and `GET /api/playground/history`. Pure functions, tested against the kit's wording.
 */

const isObject = (v: unknown): v is JsonObject => !!v && typeof v === 'object' && !Array.isArray(v);

// ─── Endpoint ───

/**
 * One option of the Endpoint select, as the kit writes them: "dev · 8 connections, 22 tools", "stage-readonly ·
 * 3 connections, read only", "incident-prod · 3 prod connections".
 */
export function endpointOption(e: EndpointSummary): string {
  const n = e.connections.total;
  const word = n === 1 ? 'connection' : 'connections';
  let text: string;
  if (e.access === 'read-only') text = `${e.slug} · ${n} ${word}, read only`;
  else if (e.env === 'prod') text = `${e.slug} · ${n} prod ${word}`;
  else {
    const tools = e.tools.read + e.tools.approval + e.tools.off;
    text = `${e.slug} · ${n} ${word}, ${formatCount(tools)} ${tools === 1 ? 'tool' : 'tools'}`;
  }
  return e.status === 'disabled' ? `${text}, disabled` : text;
}

/**
 * The endpoint to open: the one the link names when it exists; otherwise the first that isn't prod (a first look
 * should not run reads on production), else the first. `missing` is the slug a link named that doesn't exist.
 */
export function chooseEndpoint(list: readonly EndpointSummary[], wanted: string | null): { readonly slug: string | null; readonly missing: string | null } {
  if (wanted && list.some((e) => e.slug === wanted)) return { slug: wanted, missing: null };
  const first = list.find((e) => e.env !== 'prod' && e.status !== 'disabled') ?? list[0];
  return { slug: first?.slug ?? null, missing: wanted || null };
}

/** "a", "a and b", "a, b and c". */
export function joinNames(names: readonly string[]): string {
  if (names.length <= 1) return names.join('');
  return `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`;
}

/**
 * The prod warning under the select (kit `#pg-prod`): the connections calls run on, then "They are real reads on
 * production, and a runbook waits for approval like an agent's; on prod, approving needs a comment." — the middle
 * part only when a tool there waits for a person, the last only when the prod policy requires the comment.
 */
export function prodWarningTail(tools: readonly PlaygroundTool[], commentRequired: boolean | null): string {
  const waiting = tools.filter((t) => t.mode !== 'OFF' && t.mode !== 'ALWAYS' && t.runnable && t.needsApproval);
  const what = !waiting.length ? '' : waiting.every((t) => t.name === 'ssh_run') ? ', and a runbook waits for approval like an agent’s' : ', and a change waits for approval like an agent’s';
  return `They are real reads on production${what}${commentRequired ? '; on prod, approving needs a comment.' : '.'}`;
}

/** The connections of a prod endpoint, in the order of its tool groups (the kit names them in its warning). */
export function groupConnections(groups: readonly ToolGroup[]): string[] {
  return groups.map((g) => g.connection).filter((c): c is string => !!c);
}

// ─── Tool picker ───

export const GENERAL_TITLE = 'General';
export const GENERAL_DETAIL = 'Endpoint tools, no connection of their own';

/**
 * The picker's groups: the endpoint's own tools first as "General" (kit playground.html), then one group per
 * connection with its type's icon and "PostgreSQL · db.dev.internal:5432/app" before the counts.
 */
export function pickerGroups(groups: readonly ToolGroup[], connections: readonly ConnectionRow[]): ToolPolicyGroup[] {
  const general = groups.filter((g) => g.connection === null);
  const wired = groups.filter((g) => g.connection !== null);
  return [
    ...general.map((group) => ({ group, type: null, detail: GENERAL_DETAIL, title: GENERAL_TITLE })),
    ...wired.map((group) => {
      const c = connections.find((x) => x.name === group.connection);
      const detail = [c?.typeName ?? group.group, c?.host].filter((x): x is string => !!x).join(' · ');
      return { group, type: c?.type ?? null, detail: detail || null };
    }),
  ];
}

/** Tool name → its sentence for the picker rows (the core's tool descriptions). */
export function toolDescriptions(tools: readonly PlaygroundTool[]): Record<string, string> {
  return Object.fromEntries(tools.filter((t) => t.description).map((t) => [t.name, t.description]));
}

/** The tool a person picked: one row of the picker, i.e. one tool × connection pair, and its mode there. */
export interface Chosen {
  readonly name: string;
  /** The connection of the row; null for the endpoint's own tools (list_connections, describe_connection). */
  readonly connection: string | null;
  readonly env: Env | null;
  readonly mode: ToolMode;
}

/**
 * The row of `tool` in the picker: the one on `connection` when given, else the first row of the tool that isn't
 * Off, else the first row of the tool. Null when the endpoint has no such tool.
 */
export function findPair(groups: readonly ToolGroup[], tool: string, connection?: string | null): Chosen | null {
  const rows = groups.flatMap((g) => g.tools.filter((t) => t.name === tool).map((t) => ({ name: t.name, connection: g.connection, env: g.env, mode: t.mode })));
  if (!rows.length) return null;
  if (connection !== undefined) {
    const exact = rows.find((r) => r.connection === connection);
    if (exact) return exact;
  }
  return rows.find((r) => r.mode !== 'OFF') ?? rows[0];
}

/**
 * The tool the page opens with when the link names none: `sql_query` when the endpoint has it (the kit's script
 * does so on every endpoint), else the first row that isn't Off, in the picker's order (General first), else the
 * first row.
 */
export function defaultPair(groups: readonly ToolGroup[]): Chosen | null {
  const ordered = [...groups.filter((g) => g.connection === null), ...groups.filter((g) => g.connection !== null)];
  const sql = findPair(ordered, 'sql_query');
  if (sql) return sql;
  const rows = ordered.flatMap((g) => g.tools.map((t) => ({ name: t.name, connection: g.connection, env: g.env, mode: t.mode })));
  return rows.find((r) => r.mode !== 'OFF') ?? rows[0] ?? null;
}

/**
 * What Run does for the chosen tool:
 * - `off` — Off on the endpoint: agents don't get it, so it can't run here either (kit `#pg-off-alert`);
 * - `blocked` — the endpoint can't serve it (disabled, changed outside GateRook, a name clash): the core's reason;
 * - `approval` — its mode is Approval: running it sends an approval request (kit `#pg-approval-alert`);
 * - `run` — it runs at once.
 */
export type RunState = 'run' | 'approval' | 'off' | 'blocked';

export function runState(chosen: Chosen, tool: PlaygroundTool | null): RunState {
  if (chosen.mode === 'OFF') return 'off';
  if (tool && !tool.runnable) return 'blocked';
  return chosen.mode === 'APPROVAL' ? 'approval' : 'run';
}

const MODE_WORDS: Record<ToolMode, string> = { OFF: 'off', READ_ONLY: 'read only', APPROVAL: 'approval' };

/** "sql_execute chosen. Mode on dev: approval. This call needs approval." (kit `choose()`). */
export function chosenAnnouncement(tool: string, mode: ToolMode, slug: string): string {
  const tail = mode === 'APPROVAL' ? '. This call needs approval.' : mode === 'OFF' ? ". It can't run." : '.';
  return `${tool} chosen. Mode on ${slug}: ${MODE_WORDS[mode]}${tail}`;
}

/** "Endpoint incident-prod: 10 tools. Warning: calls run on prod-replica, prod-logs and prod-vm. sql_query chosen." */
export function endpointAnnouncement(slug: string, tools: number, prodConnections: readonly string[] | null, chosen: string | null): string {
  const warning = prodConnections?.length ? `Warning: calls run on ${joinNames(prodConnections)}. ` : '';
  return `Endpoint ${slug}: ${formatCount(tools)} ${tools === 1 ? 'tool' : 'tools'}. ${warning}${chosen ? `${chosen} chosen.` : ''}`.trim();
}

// ─── Arguments ───

/** The options of the `connection` argument: the picked row's connection only (kit), else what the schema lists. */
export function connectionOptions(chosen: Chosen, tool: PlaygroundTool | null, schemaEnum: readonly JsonValue[]): string[] {
  if (chosen.connection) return [chosen.connection];
  const listed = schemaEnum.filter((v): v is string => typeof v === 'string');
  return listed.length ? listed : [...(tool?.connections ?? [])];
}

/** A string argument long enough to be text of its own (SQL, a manifest, a query, a body): a code textarea. */
export function isLongText(node: SchemaNode): boolean {
  if (node.kind === 'textarea' || node.kind === 'code') return true;
  const max = node.schema['maxLength'];
  return node.kind === 'text' && typeof max === 'number' && max > 10_000;
}

/** "Integer, 50 when empty, at most 1,000." after the field's own sentence (kit `#pg-a-limit-hint`). */
export function fieldHint(node: SchemaNode): string | null {
  const parts: string[] = [];
  if (node.description) parts.push(node.description.replace(/\s+$/, ''));
  if (node.kind === 'number') {
    const s = node.schema;
    const integer = s['type'] === 'integer' || (Array.isArray(s['type']) && s['type'].includes('integer'));
    const facts = [integer ? 'Integer' : 'Number'];
    if (typeof s['default'] === 'number') facts.push(`${formatCount(s['default'])} when empty`);
    const min = s['minimum'];
    const max = s['maximum'];
    if (typeof min === 'number' && typeof max === 'number') facts.push(`from ${formatCount(min)} to ${formatCount(max)}`);
    else if (typeof max === 'number') facts.push(`at most ${formatCount(max)}`);
    else if (typeof min === 'number') facts.push(`at least ${formatCount(min)}`);
    parts.push(`${facts.join(', ')}.`);
  }
  if (!parts.length) return null;
  return parts.map((p) => (/[.!?…]$/.test(p) ? p : `${p}.`)).join(' ');
}

/** The JSON type of a value, as a schema's `type` would name it. */
function jsonType(v: JsonValue): string {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  if (typeof v === 'number') return Number.isInteger(v) ? 'integer' : 'number';
  return typeof v;
}

/**
 * Arguments that go on to the next tool (kit: the form keeps its inputs while the tool changes, `sql` stays when
 * `sql_query` becomes `sql_execute`): the previous values the new schema has a property for and accepts by type.
 */
export function carryOver(previous: JsonObject, schema: JsonObject): JsonObject {
  const props = isObject(schema['properties']) ? schema['properties'] : {};
  const out: JsonObject = {};
  for (const [key, value] of Object.entries(previous)) {
    const prop = props[key];
    if (!isObject(prop) || value === null || value === '') continue;
    const declared = prop['type'];
    const types = Array.isArray(declared) ? declared.filter((t): t is string => typeof t === 'string') : typeof declared === 'string' ? [declared] : [];
    const t = jsonType(value);
    const fits = !types.length || types.includes(t) || (t === 'integer' && types.includes('number'));
    const inEnum = !Array.isArray(prop['enum']) || prop['enum'].some((e) => e === value);
    if (fits && inEnum) out[key] = value;
  }
  return out;
}

/**
 * The arguments as JSON, the way the kit prints them: two-space indent, a list of plain values on one line
 * (`["id", "status"]`), objects and lists of objects one member per line.
 */
export function wireJson(value: JsonValue | undefined, indent = ''): string {
  if (value === undefined) return 'null';
  if (Array.isArray(value)) {
    if (!value.length) return '[]';
    if (value.every((v) => v === null || typeof v !== 'object')) return `[${value.map((v) => JSON.stringify(v)).join(', ')}]`;
    const inner = indent + '  ';
    return `[\n${value.map((v) => inner + wireJson(v, inner)).join(',\n')}\n${indent}]`;
  }
  if (isObject(value)) {
    const keys = Object.keys(value);
    if (!keys.length) return '{}';
    const inner = indent + '  ';
    return `{\n${keys.map((k) => `${inner}${JSON.stringify(k)}: ${wireJson(value[k], inner)}`).join(',\n')}\n${indent}}`;
  }
  return JSON.stringify(value);
}

/** What the JSON tab says about its text, or the arguments it holds. */
export type ParsedArguments = { readonly ok: true; readonly value: JsonObject } | { readonly ok: false; readonly problem: string };

/**
 * The JSON tab's text → arguments (kit `#pg-json` input handler): not JSON, not one object, or a value the schema
 * refuses (`limit: 2000 is above the maximum…`) are said under the field; the form keeps the last valid arguments.
 */
export function parseArguments(text: string, nodes: readonly SchemaNode[]): ParsedArguments {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    const detail = e instanceof Error ? e.message.replace(/^JSON\.parse: /, '') : 'unexpected input';
    return { ok: false, problem: `This is not valid JSON: ${detail}. Fix it, or go back to Form: it keeps the last valid arguments.` };
  }
  if (!isObject(parsed)) return { ok: false, problem: 'Arguments must be one JSON object, like { "connection": "dev-db" }.' };
  for (const node of nodes) {
    if (node.path.length !== 1 || !(node.key in parsed)) continue;
    const problem = valueProblem(node, parsed[node.key] as JsonValue);
    if (problem) return { ok: false, problem: `${node.key}: ${problem}` };
  }
  return { ok: true, value: parsed };
}

/**
 * The form's model for arguments typed as JSON: every field the JSON names takes its value, the others empty out
 * (text fields to `''`, as the form holds them), and members the schema doesn't know stay (the core says what it
 * makes of them, as it would to an agent).
 */
export function modelFromArguments(model: JsonObject, args: JsonObject): JsonObject {
  const out: JsonObject = {};
  for (const [key, current] of Object.entries(model)) out[key] = key in args ? args[key] : typeof current === 'string' ? '' : null;
  for (const [key, value] of Object.entries(args)) if (!(key in out)) out[key] = value;
  return out;
}

// ─── Result ───

/** structuredContent in rows and columns (`sql_query`): the Structured tab draws it as the kit's table. */
export interface ResultTable {
  readonly columns: readonly string[];
  /** Database types per column (`int4`, `numeric`): numbers align right. */
  readonly numeric: readonly boolean[];
  readonly rows: readonly (readonly JsonValue[])[];
  readonly rowCount: number;
  readonly truncated: boolean;
  /** Columns the masking rules replaced (`customer_email`). */
  readonly masked: readonly string[];
}

const NUMERIC_TYPES = /^(int|integer|smallint|bigint|serial|bigserial|numeric|decimal|real|double|float|money|int[248]|float[48]|number)/i;

export function resultTable(structured: JsonValue | null | undefined): ResultTable | null {
  if (!isObject(structured)) return null;
  const columns = structured['columns'];
  const rows = structured['rows'];
  if (!Array.isArray(columns) || !Array.isArray(rows)) return null;
  const names = columns.map((c) => (typeof c === 'string' ? c : isObject(c) && typeof c['name'] === 'string' ? c['name'] : String(c)));
  const types = Array.isArray(structured['types']) ? structured['types'] : columns.map((c) => (isObject(c) ? (c['type'] ?? null) : null));
  const table = rows.map((r) => (Array.isArray(r) ? r : isObject(r) ? names.map((n) => r[n] ?? null) : [r]));
  const numeric = names.map((_, i) => {
    const t = types[i];
    if (typeof t === 'string') return NUMERIC_TYPES.test(t);
    const values = table.map((r) => r[i]).filter((v) => v !== null && v !== undefined);
    return values.length > 0 && values.every((v) => typeof v === 'number');
  });
  const masked = Array.isArray(structured['masked']) ? structured['masked'].filter((m): m is string => typeof m === 'string') : [];
  const count = typeof structured['rowCount'] === 'number' ? structured['rowCount'] : table.length;
  return { columns: names, numeric, rows: table, rowCount: count, truncated: structured['truncated'] === true, masked };
}

/** A value the gateway masked: `‹masked›` (or `‹masked›#2` for a renamed property). */
export function isMasked(v: JsonValue | undefined): boolean {
  return typeof v === 'string' && /^‹masked[^›]*›(#\d+)?$/.test(v);
}

export function cellText(v: JsonValue | undefined): string {
  if (v === null || v === undefined) return 'null';
  return typeof v === 'string' ? v : JSON.stringify(v);
}

/** The decision of a run: the audit's once it is known, else what the answer itself says. */
export function runDecision(call: PlaygroundCallView, event: ActivityEvent | null): Decision {
  if (event) return event.decision;
  const status = call.approval?.status ?? null;
  if (status) {
    const byStatus: Record<string, Decision> = { pending: 'pending', running: 'pending', completed: 'approved', rejected: 'rejected', expired: 'expired', failed: 'error' };
    return byStatus[status] ?? 'pending';
  }
  if (!call.isError) return 'allowed';
  return /^Denied by policy\b/.test(call.text ?? '') ? 'denied' : 'error';
}

export function decisionBadge(decision: Decision): Look {
  return decisionLook(decision);
}

/** "3.1 KB · 12 rows" (kit meta strip). */
export function sizeText(call: PlaygroundCallView, table: ResultTable | null): string {
  const size = formatBytes(call.meta.bytes);
  return table ? `${size} · ${formatCount(table.rowCount)} ${table.rowCount === 1 ? 'row' : 'rows'}` : size;
}

/** The masked count and, when the result names them, the fields ("2 fields: customer_email, customer_phone"). */
export function maskedFacts(call: PlaygroundCallView, table: ResultTable | null): { readonly count: number; readonly names: readonly string[] } {
  const names = table?.masked ?? [];
  return { count: names.length || call.meta.masked, names };
}

/** The trace id the core put in the answer's `_meta` (both revisions carry it). */
export function traceIdOf(call: PlaygroundCallView): string | null {
  const result = call.wire.response?.['result'];
  const meta = isObject(result) ? result['_meta'] : null;
  const id = isObject(meta) ? meta['ai.msrv.gaterook/traceId'] : null;
  return typeof id === 'string' && id ? id : null;
}

/** A read that ran and came back with nothing (kit `#pg-result-empty`): no text and no structured content. */
export function isEmptyResult(call: PlaygroundCallView): boolean {
  if (call.isError || call.approval) return false;
  const s = call.structured;
  const emptyStructured = s === null || s === undefined || (Array.isArray(s) && !s.length) || (isObject(s) && !Object.keys(s).length);
  return emptyStructured && !(call.text ?? '').trim();
}

const DECISION_WORDS: Record<Decision, string> = {
  allowed: 'allowed',
  approved: 'approved and ran',
  pending: 'waits for approval',
  denied: 'denied by policy',
  rejected: 'rejected',
  expired: 'expired',
  error: 'ended with an error',
};

/** "sql_query allowed in 38 ms: 12 rows, 2 fields masked." (kit `#pg-out-status`). */
export function runStatus(tool: string, decision: Decision, call: PlaygroundCallView, table: ResultTable | null): string {
  const head = `${tool} ${DECISION_WORDS[decision]} in ${duration(call.meta.durationMs)}`;
  if (decision !== 'allowed' && decision !== 'approved') return `${head}.`;
  if (isEmptyResult(call)) return `${head}: empty result.`;
  const facts: string[] = [];
  if (table) facts.push(`${formatCount(table.rowCount)} ${table.rowCount === 1 ? 'row' : 'rows'}`);
  const masked = maskedFacts(call, table).count;
  if (masked) facts.push(`${masked} ${masked === 1 ? 'field' : 'fields'} masked`);
  return facts.length ? `${head}: ${facts.join(', ')}.` : `${head}.`;
}

/** The JSON-RPC tab's note on the revision of the call. */
export function revisionNote(era: Era): { readonly revision: string; readonly text: string; readonly literals: readonly string[] } {
  return era === '2026-07-28'
    ? {
        revision: era,
        text: 'no session, every request stands on its own. Mcp-Method and Mcp-Name are required headers; the gateway still decides on the body.',
        literals: ['Mcp-Method', 'Mcp-Name'],
      }
    : {
        revision: era,
        text: 'the client opened with initialize, and MCP-Protocol-Version names the revision it agreed. There is no session id: the gateway answers every request on its own.',
        literals: ['initialize', 'MCP-Protocol-Version'],
      };
}

const REASONS: Record<number, string> = {
  200: 'OK',
  202: 'Accepted',
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  405: 'Method Not Allowed',
  413: 'Content Too Large',
  415: 'Unsupported Media Type',
  421: 'Misdirected Request',
  500: 'Internal Server Error',
  503: 'Service Unavailable',
};

export function httpStatusLine(status: number): string {
  return `${status}${REASONS[status] ? ` ${REASONS[status]}` : ''}`;
}

/** The path and host of an endpoint URL (`http://127.0.0.1:7465/mcp/dev` → `/mcp/dev`, `127.0.0.1:7465`). */
export function urlParts(url: string | null | undefined, slug: string): { readonly path: string; readonly host: string } {
  try {
    const u = new URL(url ?? '');
    return { path: u.pathname, host: u.host };
  } catch {
    return { path: `/mcp/${slug}`, host: '127.0.0.1' };
  }
}

/**
 * The request as a client of this revision sends it (kit `#pg-req-pre`): the HTTP request line and headers, then the
 * JSON-RPC body byte for byte as the core received it. The Playground has no endpoint token: the app's session takes
 * its place, so Authorization is shown masked, like any secret (the tab's caption says so).
 */
export function requestText(call: PlaygroundCallView, era: Era, url: { readonly path: string; readonly host: string }, tool: string): string {
  const headers = [
    `POST ${url.path} HTTP/1.1`,
    `Host: ${url.host}`,
    'Content-Type: application/json',
    'Accept: application/json, text/event-stream',
    `MCP-Protocol-Version: ${era}`,
    ...(era === '2026-07-28' ? ['Mcp-Method: tools/call', `Mcp-Name: ${tool}`] : []),
    'Authorization: Bearer ‹masked›',
  ];
  return `${headers.join('\n')}\n\n${wireJson(call.wire.request)}`;
}

/** The answer: status line, Content-Type and the JSON-RPC body (kit `#pg-res-pre`). */
export function responseText(call: PlaygroundCallView): string {
  return `HTTP/1.1 ${httpStatusLine(call.wire.httpStatus)}\nContent-Type: application/json\n\n${wireJson(call.wire.response)}`;
}

// ─── Approval ───

/** The kit's toast once a call became an approval request. */
export function approvalToast(tool: string, connection: string | null): { readonly title: string; readonly body: string } {
  const on = connection ? `${tool} on ${connection}` : tool;
  return { title: 'Approval request sent', body: `${on} waits for you in Approvals. The result shows up here once you approve it.` };
}

// ─── History ───

export interface HistoryRow {
  readonly id: number;
  readonly callId: string;
  readonly at: string;
  readonly badge: Look;
  readonly tool: string;
  readonly connection: string | null;
  readonly env: Env | null;
  readonly duration: string;
  /** The run shown above ("shown above", `aria-current`). */
  readonly current: boolean;
}

/** The kit keeps the last five runs. */
export const HISTORY_SIZE = 5;

/**
 * The last runs, newest first (kit `#pg-hist-t`). A denied call has no duration in the audit: the one this page
 * measured for its own run fills it in (`durations` by call id).
 */
export function historyRows(events: readonly ActivityEvent[], currentCallId: string | null, durations: ReadonlyMap<string, number>): HistoryRow[] {
  return events.slice(0, HISTORY_SIZE).map((e) => {
    const ms = e.durationMs ?? durations.get(e.callId) ?? null;
    return {
      id: e.id,
      callId: e.callId,
      at: e.at,
      badge: decisionLook(e.decision),
      tool: e.tool,
      connection: e.connection,
      env: e.env,
      duration: ms === null ? '' : duration(ms),
      current: e.callId === currentCallId,
    };
  });
}
