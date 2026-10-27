import { ConnectionHealth, ConnectionUsedBy, StoredSecret, TestOutcome } from '../../core/api/connections.api';
import { ConnectorCard, ConnectorGroup, ConnectorKind, ConnectorTool } from '../../core/api/connector-types.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { EnvPolicy } from '../../core/api/policies.api';
import { Env, JsonObject, JsonValue, ToolAnnotations, ToolMode } from '../../core/api/types';
import { duration, everyInterval, formatCount } from '../../core/time/time-format';
import { ProdDefault } from '../../kit/elements/connection-form/connection-form';
import { testConfirmTitle, testFailureTitle } from '../../kit/elements/connection-form/connection-test';
import { clientName } from '../../kit/elements/shared/client-mark';

/**
 * The words and decisions of Add connection (design/pages/connection-new.html) that need no DOM: the wizard's stepper,
 * the type picker's default, where the test connects, the endpoints a new connection may join and the tools it would
 * bring there, what prod turns on, and the edit view's summaries. The page component wires them to the core.
 */

/** The page's three modes: the wizard, the wizard from Configure with a copy's settings, one connection. */
export type Mode = 'add' | 'duplicate' | 'edit';
export type StepNumber = 1 | 2 | 3 | 4;
export const STEP_NAMES = ['Choose type', 'Configure', 'Test', 'Add to endpoint'] as const;

/** "a, b and c". */
export function list(words: readonly string[]): string {
  if (words.length < 2) return words.join('');
  return `${words.slice(0, -1).join(', ')} and ${words.at(-1)}`;
}

// ─── Stepper ───

/** What a test came to, as the stepper writes it under Test ("Connected · 12 ms") and whether it passed. */
export interface TestSummary {
  readonly passed: boolean;
  readonly text: string;
}

export function testSummary(outcome: TestOutcome | null, address: string | null): TestSummary | null {
  if (!outcome) return null;
  if (outcome.ok) return { passed: true, text: `Connected · ${duration(outcome.latencyMs) || '0 ms'}` };
  if (outcome.needsConfirmation) return { passed: false, text: testConfirmTitle(outcome, address) };
  return { passed: false, text: testFailureTitle(outcome, address) };
}

export type StepState = 'complete' | 'current' | 'upcoming' | 'error';

/** One step of the stepper: its state, and the choice made at it once it is done (kit `__description`). */
export interface StepView {
  readonly n: StepNumber;
  readonly name: string;
  readonly state: StepState;
  readonly current: boolean;
  /** Plain words (Choose type, Test), or the connection's name and env (Configure: "dev-db · dev"). */
  readonly description: { readonly text: string } | { readonly name: string; readonly env: Env } | null;
}

/**
 * The stepper of the wizard: steps before the current one are complete and say what was chosen; Test stays failed
 * (`error`, "…, failed") until a test passes — the person may still continue and test later (kit).
 */
export function stepperSteps(
  current: StepNumber | 5,
  facts: { readonly typeName: string | null; readonly name: string; readonly env: Env; readonly test: TestSummary | null; readonly endpoints: readonly string[] },
  firstRun = false,
): StepView[] {
  return STEP_NAMES.map((stepName, i) => {
    const n = (i + 1) as StepNumber;
    // First run: step 4 creates the first endpoint instead of picking among existing ones (kit ?state=first-run).
    const name = n === 4 && firstRun ? 'Create endpoint' : stepName;
    const failed = n === 3 && !!facts.test && !facts.test.passed && current >= 3;
    const state: StepState = failed ? 'error' : n < current ? 'complete' : n === current ? 'current' : 'upcoming';
    let description: StepView['description'] = null;
    if (state === 'complete' || state === 'error') {
      if (n === 1) description = facts.typeName ? { text: facts.typeName } : null;
      else if (n === 2) description = { name: facts.name || 'this connection', env: facts.env };
      else if (n === 3) description = facts.test ? { text: facts.test.text } : { text: 'Not tested' };
      else if (firstRun) description = { text: `Endpoint ${facts.env}` };
      else description = { text: facts.endpoints.length ? `Endpoint ${facts.endpoints.join(', ')}` : 'No endpoint yet' };
    }
    return { n, name, state, current: n === current, description };
  });
}

// ─── Step 1: the type picker ───

/** A picker card's value: `type`, or `type:variant` for the Logs back ends. */
export function cardKey(c: { readonly type: string; readonly variant: string | null }): string {
  return c.variant ? `${c.type}:${c.variant}` : c.type;
}

export function findCard(groups: readonly ConnectorGroup[], key: string | null): ConnectorCard | null {
  if (!key) return null;
  for (const g of groups) for (const c of g.cards) if (cardKey(c) === key) return c;
  return null;
}

/**
 * The card the picker opens on: `?type=` (and `&variant=`) from Connectors, else the first type that can be used —
 * the kit's page opens with PostgreSQL chosen. A type that isn't available never becomes the choice.
 */
export function defaultCardKey(groups: readonly ConnectorGroup[], type?: string | null, variant?: string | null): string | null {
  const cards = groups.flatMap((g) => g.cards).filter((c) => c.available);
  if (type) {
    const exact = cards.find((c) => c.type === type && (variant ? c.variant === variant : true));
    if (exact) return cardKey(exact);
  }
  return cards[0] ? cardKey(cards[0]) : null;
}

/** "3 connections use PostgreSQL": how many connections a card's type (and back end) already has. */
export function usage(c: ConnectorCard): string | null {
  if (!c.connections) return null;
  return `${formatCount(c.connections)} ${c.connections === 1 ? 'connection uses' : 'connections use'} ${c.name}`;
}

// ─── Step 3: where the test goes ───

const ADDRESS_KEYS = /^(url|baseUrl|apiServer|server|endpoint|address|uri)$/i;

function walk(node: JsonValue | undefined, visit: (key: string, value: JsonValue) => void): void {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return;
  for (const [k, v] of Object.entries(node)) {
    visit(k, v);
    walk(v, visit);
  }
}

/**
 * Where the test connects and as whom, from the config ("app_rw at db.dev.internal:5432/app"): host, port and
 * database where the connector has them, else its URL-like field (base URL, API server, region).
 */
export interface TestTargetView {
  readonly user: string | null;
  /** "db.dev.internal:5432/app": the card's description. */
  readonly address: string | null;
  /** "db.dev.internal:5432": the testing line ("Connecting to … as app_rw") and the failure titles. */
  readonly hostPort: string | null;
  /** The connect timeout, for "Gives up after 5 s". */
  readonly timeoutMs: number | null;
}

export function testTarget(config: JsonObject | null): TestTargetView {
  let host: string | null = null;
  let port: number | string | null = null;
  let db: string | null = null;
  let user: string | null = null;
  let url: string | null = null;
  let timeoutMs: number | null = null;
  walk(config ?? {}, (k, v) => {
    if (k === 'databases' && Array.isArray(v) && v.length && !db) db = v.join(', ');
    if (typeof v !== 'string' && typeof v !== 'number') return;
    if (/^(host|hostname)$/i.test(k) && typeof v === 'string' && !host) host = v;
    else if (/^port$/i.test(k) && port === null) port = v;
    else if (/^(database|db|dbname)$/i.test(k) && typeof v === 'string' && !db) db = v;
    else if (/^(user|username|login)$/i.test(k) && typeof v === 'string' && !user) user = v;
    else if (/^connect(ion)?timeout(ms)?$/i.test(k) && typeof v === 'number' && timeoutMs === null) timeoutMs = v;
    else if (ADDRESS_KEYS.test(k) && typeof v === 'string' && !url) url = v;
  });
  const hostPort = host ? `${host}${port !== null ? `:${port}` : ''}` : url;
  const address = host ? `${hostPort}${db ? `/${db}` : ''}` : url;
  return { user, address, hostPort, timeoutMs };
}

/**
 * The copyable fix a connector attached to a hint (SQL "Create a read-only user" for `gw_reader`): what the
 * disclosure says, the block's language and the line above the code ("Run as an admin on app, then use gw_reader as
 * the user").
 */
export function fixSnippet(outcome: TestOutcome | null, config: JsonObject | null) {
  const hint = (outcome?.hints ?? []).find((h) => h.snippet?.text);
  if (!hint?.snippet) return null;
  const { language, text } = hint.snippet;
  const sql = language.toLowerCase() === 'sql';
  const role = /CREATE\s+(?:ROLE|USER)\s+'?([A-Za-z_][\w]*)/i.exec(text)?.[1] ?? null;
  let db: string | null = null;
  walk(config ?? {}, (k, v) => {
    if (k === 'databases' && Array.isArray(v) && v.length && !db) db = v.join(', ');
    if (/^(database|db|dbname)$/i.test(k) && typeof v === 'string' && !db) db = v;
  });
  const filename = role ? `Run as an admin${db ? ` on ${db}` : ''}, then use ${role} as the user` : 'Run as an admin';
  return {
    toggle: sql && role ? 'Create a read-only user' : 'Show how to fix it',
    lang: language.toUpperCase() === 'SQL' ? 'SQL' : language,
    language,
    text,
    filename,
    literals: [db, role].filter((x): x is string => !!x),
  };
}

// ─── Step 4: endpoints and the tools agents get ───

/** One endpoint card of step 4. A refused one stays visible, focusable and says why (aria-disabled). */
export interface EndpointChoice {
  readonly slug: string;
  readonly description: string;
  /** "Claude Desktop, Codex", "claude.ai · tunnel off". */
  readonly meta: string | null;
  readonly refusal: string | null;
  readonly readOnly: boolean;
  readonly published: boolean;
  /** The endpoint's env once the connection joined it (an empty endpoint takes the first connection's). */
  readonly env: Env;
  /** "Dev data on a public endpoint" while it is published. */
  readonly warning: { readonly title: string; readonly body: string } | null;
}

/**
 * Why a connection can't join an endpoint, as far as the UI can tell before the connection exists: an endpoint
 * changed outside GateRook, prod and non-prod never mix (ARCHITECTURE.md §5 step 7, `Wiring.mixing`), a prod
 * connection never goes on a published endpoint. Live tokens that would cross the prod line are the core's to find
 * when the connection is saved (`POST …/connections/preview` before adding).
 */
export function refusalFor(e: EndpointSummary, conn: { readonly name: string; readonly env: Env }): string | null {
  if (e.blockedReason) return e.blockedReason;
  const prodConn = conn.env === 'prod';
  if (prodConn && e.published) return `Published through the tunnel, and a prod connection never is; ${conn.name || 'this connection'} is prod.`;
  if (e.connections.total === 0) return null;
  const name = conn.name || 'this connection';
  if (e.env === 'prod' && !prodConn) return `Accepts prod connections only; ${name} is ${conn.env}.`;
  if (e.env !== 'prod' && prodConn) return `Accepts dev and stage connections only; ${name} is prod.`;
  return null;
}

export function endpointChoices(
  endpoints: readonly EndpointSummary[],
  conn: { readonly name: string; readonly env: Env },
  tunnelState: string | null = null,
): EndpointChoice[] {
  const name = conn.name || 'this connection';
  const choices = endpoints.map((e): EndpointChoice => {
    const refusal = refusalFor(e, conn);
    const readOnly = e.access === 'read-only';
    const description =
      refusal ??
      e.description ??
      (readOnly ? 'Read only: tools that change data stay off.' : 'Reads run, writes wait for approval.');
    const clients = e.clients.map(clientName);
    const meta = e.published ? [...clients.filter((c) => c !== 'claude.ai'), 'claude.ai', `tunnel ${tunnelState ?? 'off'}`] : clients;
    const warning =
      !refusal && e.published && conn.env === 'dev'
        ? { title: 'Dev data on a public endpoint', body: `While the tunnel is on, claude.ai could read ${name} through ${e.slug}.` }
        : null;
    return {
      slug: e.slug,
      description,
      meta: meta.length ? (e.published ? meta.join(' · ') : meta.join(', ')) : null,
      refusal,
      readOnly,
      published: e.published,
      env: e.connections.total === 0 ? conn.env : e.env,
      warning,
    };
  });
  // Usable endpoints first, the refused ones after them (kit: dev, stage-readonly, then incident-prod).
  return [...choices.filter((c) => !c.refusal), ...choices.filter((c) => !!c.refusal)];
}

/**
 * The endpoints the step opens with ticked: the usable ones on the connection's side of the prod line that let
 * agents act (standard access) and are not public — kit: a dev connection starts on `dev` (an endpoint's env is
 * the strictest of its connections, so `dev` may read "stage"). Never a refused one.
 */
export function initialEndpoints(choices: readonly EndpointChoice[], env: Env): string[] {
  const prod = env === 'prod';
  return choices.filter((c) => !c.refusal && !c.readOnly && !c.published && (c.env === 'prod') === prod).map((c) => c.slug);
}

/** A manifest mode (`read-only`, `approval`, `off`; any case) as the API's `ToolMode`. */
export function toolMode(mode: string | null | undefined): ToolMode {
  const m = (mode ?? '').toLowerCase().replace('_', '-');
  return m === 'read-only' ? 'READ_ONLY' : m === 'approval' ? 'APPROVAL' : 'OFF';
}

function mutating(t: ConnectorTool): boolean {
  return (t.nature ?? '').toLowerCase() !== 'read';
}

function readOnlyAllowed(t: ConnectorTool): boolean {
  return (t.nature ?? '').toLowerCase() !== 'mutation' && t.annotations.destructiveHint !== true;
}

/** One row of "Tools agents get": the tool, its annotations and the mode it starts in, with why it can't be more. */
export interface PreviewRow {
  readonly name: string;
  readonly description: string;
  readonly annotations: ToolAnnotations;
  readonly mode: ToolMode;
  readonly note: { readonly icon: string; readonly text: string } | null;
}

export interface ToolPreview {
  readonly slug: string;
  readonly rows: readonly PreviewRow[];
  /** "4 tools · 3 read only · 1 approval", "… · 1 off, locked by the read-only endpoint". */
  readonly summary: string;
  /** Tools that come from the server once it runs (an external MCP server): nothing to list yet. */
  readonly fromServer: boolean;
}

/**
 * The modes a new connection's tools start in on an endpoint, by the core's rules (`endpoints.ToolPairs.initial`):
 * the manifest's default for the endpoint's env (prod has its own), Approval for every tool of a plugin or an MCP
 * server, and on a read-only endpoint nothing that changes data and nothing in Approval. The core decides again when
 * the connection is added; this is what the person agrees to.
 */
export function toolPreview(
  tools: readonly ConnectorTool[],
  kind: ConnectorKind,
  dynamicTools: boolean,
  choice: EndpointChoice,
): ToolPreview {
  const builtIn = kind === 'built-in';
  const rows = tools.map((t): PreviewRow => {
    let mode = toolMode(choice.env === 'prod' ? t.prodDefaultMode : t.defaultMode);
    if (!builtIn) mode = 'APPROVAL';
    if (choice.readOnly) {
      // ToolPairs.clampReadOnly: never Approval, never a tool that only changes data, Read only only where allowed.
      if ((t.nature ?? '').toLowerCase() === 'mutation' || mode === 'APPROVAL') mode = 'OFF';
      else if (mode === 'READ_ONLY' && !readOnlyAllowed(t)) mode = 'OFF';
    }
    const locked = choice.readOnly && mode === 'OFF' && mutating(t);
    const note = locked
      ? { icon: 'lock', text: `${choice.slug} is read only` }
      : !readOnlyAllowed(t)
        ? { icon: 'lock', text: 'Mutates data: approval or off' }
        : null;
    return { name: t.name, description: t.description, annotations: t.annotations, mode, note };
  });
  const count = (m: ToolMode) => rows.filter((r) => r.mode === m).length;
  const lockedOff = rows.filter((r) => r.mode === 'OFF' && r.note?.text.endsWith('is read only')).length;
  const parts = [
    count('READ_ONLY') ? `${count('READ_ONLY')} read only` : null,
    count('APPROVAL') ? `${count('APPROVAL')} approval` : null,
    count('OFF') ? `${count('OFF')} off${lockedOff === count('OFF') ? ', locked by the read-only endpoint' : ''}` : null,
  ].filter(Boolean);
  const n = rows.length;
  return {
    slug: choice.slug,
    rows,
    summary: [`${formatCount(n)} ${n === 1 ? 'tool' : 'tools'}`, ...parts].join(' · '),
    fromServer: dynamicTools && rows.length === 0,
  };
}

// ─── First run: the first endpoint ───

/**
 * Whether Add connection runs as the first run (kit connection-new `?state=first-run`): nothing is set up yet — no
 * connection and no endpoint — so the wizard speaks to a beginner and step 4 creates the first endpoint.
 */
export function isFirstRun(connections: number, endpoints: number): boolean {
  return connections === 0 && endpoints === 0;
}

/**
 * The tools of the first endpoint, which Save creates and names after the connection's environment: the type's
 * default modes for that env, and on prod nothing that waits for approval — "prod is read only by default" (kit) —
 * so a tool that changes data starts Off there.
 */
export function firstRunPreview(tools: readonly ConnectorTool[], kind: ConnectorKind, dynamicTools: boolean, env: Env): ToolPreview {
  const choice: EndpointChoice = { slug: env, description: '', meta: null, refusal: null, readOnly: false, published: false, env, warning: null };
  const base = toolPreview(tools, kind, dynamicTools, choice);
  if (env !== 'prod') return base;
  const rows = base.rows.map((r): PreviewRow =>
    r.mode === 'APPROVAL' ? { ...r, mode: 'OFF', note: { icon: 'lock', text: PROD_OFF_NOTE } } : r,
  );
  const count = (m: ToolMode) => rows.filter((r) => r.mode === m).length;
  const parts = [
    count('READ_ONLY') ? `${count('READ_ONLY')} read only` : null,
    count('APPROVAL') ? `${count('APPROVAL')} approval` : null,
    count('OFF') ? `${count('OFF')} off` : null,
  ].filter(Boolean);
  const n = rows.length;
  return { ...base, rows, summary: [`${formatCount(n)} ${n === 1 ? 'tool' : 'tools'}`, ...parts].join(' · ') };
}

/** Tool names as the kit lists them: "sql_list_schemas, sql_describe_table and sql_query". */
export interface ToolsLine {
  /** The tools that run at once (read only). */
  readonly reads: readonly string[];
  /** The tools that wait for approval. */
  readonly approval: readonly string[];
  /** The tools that are off. */
  readonly off: readonly string[];
}

export function toolsLine(rows: readonly Pick<PreviewRow, 'name' | 'mode'>[]): ToolsLine {
  return {
    reads: rows.filter((r) => r.mode === 'READ_ONLY').map((r) => r.name),
    approval: rows.filter((r) => r.mode === 'APPROVAL').map((r) => r.name),
    off: rows.filter((r) => r.mode === 'OFF').map((r) => r.name),
  };
}

/** The toast of a first-run Save (kit: "Endpoint dev created with its tools. Next: connect your AI app."). */
export function firstRunToast(name: string, endpoint: string): { readonly title: string; readonly body: string } {
  return { title: `Connection ${name} saved`, body: `Endpoint ${endpoint} created with its tools. Next: connect your AI app.` };
}

/** The note of a tool that would wait for approval and starts Off on a first prod endpoint. */
export const PROD_OFF_NOTE = 'Off on prod: read only by default';

/** A sentence with the literals (tool names) the template sets in mono. */
export interface Sentence {
  readonly text: string;
  readonly literals: readonly string[];
}

/**
 * "What your AI apps can do" of the first endpoint (kit): "Reads run at once. Anything that changes data, like
 * sql_execute, waits for your approval. You can change the modes later." — on prod "sql_execute is off: prod is
 * read only by default." instead of the approval line.
 */
export function firstRunModes(preview: ToolPreview): Sentence {
  const rows = preview.rows;
  const approval = rows.filter((r) => r.mode === 'APPROVAL').map((r) => r.name);
  const prodOff = rows.filter((r) => r.note?.text === PROD_OFF_NOTE).map((r) => r.name);
  const parts: string[] = [];
  if (preview.fromServer) parts.push('Its tools come from the server once it runs, and each one waits for your approval.');
  if (rows.some((r) => r.mode === 'READ_ONLY')) parts.push('Reads run at once.');
  if (approval.length) parts.push(`Anything that changes data, like ${approval[0]}, waits for your approval.`);
  if (prodOff.length) parts.push(`${list(prodOff)} ${prodOff.length === 1 ? 'is' : 'are'} off: prod is read only by default.`);
  parts.push('You can change the modes later.');
  return { text: parts.join(' '), literals: [...approval.slice(0, 1), ...prodOff] };
}

/**
 * The ready card's Tools (kit): "sql_list_schemas, sql_describe_table and sql_query run at once (read only);
 * sql_execute waits for your approval" — on prod "sql_execute is off: prod is read only by default". From the
 * endpoint's tools as the core answered them after Save.
 */
export function toolsSentence(line: ToolsLine, env: Env): Sentence {
  const verb = (names: readonly string[], one: string, many: string) => (names.length === 1 ? one : many);
  const parts = [
    line.reads.length ? `${list(line.reads)} ${verb(line.reads, 'runs', 'run')} at once (read only)` : null,
    line.approval.length ? `${list(line.approval)} ${verb(line.approval, 'waits', 'wait')} for your approval` : null,
    line.off.length ? `${list(line.off)} ${verb(line.off, 'is', 'are')} off${env === 'prod' ? ': prod is read only by default' : ''}` : null,
  ].filter((p): p is string => !!p);
  return {
    text: parts.length ? parts.join('; ') : 'They come from the server once it runs',
    literals: [...line.reads, ...line.approval, ...line.off],
  };
}

/** Step 3's description in a first run (kit): what the test does, "…what that user may do in the database". */
export function firstRunTestText(target: string): string {
  const where = target === 'the server' ? `on ${target}` : target === 'the API' || target === 'the service' ? `with ${target}` : `in ${target}`;
  return `GateRook connects from this computer with what you entered and checks what that user may do ${where}.`;
}

// ─── Environment and safety: what prod turns on ───

/** The policy section that governs a connector type (its manifest's `x-policy-section`). */
export const POLICY_SECTION: Readonly<Record<string, string>> = {
  postgres: 'sql',
  mysql: 'sql',
  ssh: 'ssh',
  kubernetes: 'k8s',
  logs: 'logs',
  gcp: 'cloud',
  aws: 'cloud',
  http: 'http',
};

/** The kit's own words for the defaults it lists (pages/connection-new.html `__defaults`). */
const KIT_LINES: Readonly<Record<string, ProdDefault>> = {
  'sql.readOnlyTransactions': { icon: 'lock', text: 'Read-only transactions' },
  'sql.denyDdl': { icon: 'ban', text: 'No DDL: CREATE, ALTER, DROP, TRUNCATE are refused', literals: ['CREATE', 'ALTER', 'DROP', 'TRUNCATE'] },
};

/** The kit's order inside a section (row limit before the statement timeout); keys not named keep schema order. */
const KIT_ORDER: Readonly<Record<string, readonly string[]>> = {
  sql: ['readOnlyTransactions', 'rowLimit', 'statementTimeoutMs', 'denyDdl'],
};

function iconFor(key: string, title: string): string {
  const p = `${key} ${title}`.toLowerCase();
  if (/read.?only/.test(p)) return 'lock';
  if (/row|lines|items|points|entries/.test(p)) return 'rows-3';
  if (/approval/.test(p)) return 'hourglass';
  if (/timeout|range|wait/.test(p)) return 'timer';
  if (/deny|ddl|block|forbid/.test(p)) return 'ban';
  return 'shield';
}

/** 3000 ms → "3 s", 1000 rows → "1,000" (the title says rows already), 24 h → "24 h". */
function amount(v: number, unit: string | null, title: string): string {
  if (unit === 'ms' && v >= 1000 && v % 1000 === 0) return `${formatCount(v / 1000)} s`;
  const stem = unit?.replace(/s$/, '').toLowerCase();
  const said = !!stem && title.toLowerCase().includes(stem);
  return `${formatCount(v)}${unit && !said ? ` ${unit}` : ''}`;
}

function sameJson(a: JsonValue | undefined, b: JsonValue | undefined): boolean {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}

/**
 * "Prod turns on stricter defaults" from the prod policy against dev's (never hard-coded, kit note): for the type's
 * section, every value prod holds stricter than dev (Row limit 100 (dev: 1,000), Statement timeout 3 s (dev: 5 s),
 * No DDL) and the switches that are always on there (Read-only transactions); then the approvals line — "Approval
 * timeout 10 min, a comment is required to approve".
 */
export function policyDefaults(type: string, dev: EnvPolicy, prod: EnvPolicy, schema: JsonObject | null): ProdDefault[] {
  const out: ProdDefault[] = [];
  const section = POLICY_SECTION[type] ?? null;
  const sections = (schema?.['sections'] ?? {}) as Record<string, JsonObject>;
  if (section) {
    const props = (sections[section]?.['properties'] ?? {}) as Record<string, JsonObject>;
    const p = (prod.sections[section] ?? {}) as Record<string, JsonValue>;
    const d = (dev.sections[section] ?? {}) as Record<string, JsonValue>;
    const keys = Object.keys(props).length ? Object.keys(props) : Object.keys(p);
    const order = KIT_ORDER[section] ?? [];
    keys.sort((a, b) => (order.includes(a) ? order.indexOf(a) : 99) - (order.includes(b) ? order.indexOf(b) : 99));
    for (const key of keys) {
      const title = typeof props[key]?.['title'] === 'string' ? (props[key]['title'] as string) : key;
      const unit = typeof props[key]?.['x-unit'] === 'string' ? (props[key]['x-unit'] as string) : null;
      const pv = p[key];
      const dv = d[key];
      const kit = KIT_LINES[`${section}.${key}`];
      if (typeof pv === 'boolean') {
        // A switch that is on for prod: always on (a lock), or on where dev has it off.
        if (pv && (props[key]?.['readOnly'] === true || props[key]?.['const'] === true || dv !== true)) {
          out.push(kit ?? { icon: iconFor(key, title), text: title });
        }
      } else if (typeof pv === 'number' && typeof dv === 'number' && pv !== dv) {
        out.push(kit ?? { icon: iconFor(key, title), text: `${title} ${amount(pv, unit, title)} (dev: ${amount(dv, unit, title)})` });
      } else if (Array.isArray(pv) && pv.length && !sameJson(pv, dv)) {
        const values = pv.filter((x): x is string => typeof x === 'string');
        out.push(kit ?? { icon: iconFor(key, title), text: `${title}: ${values.join(', ')}`, literals: values });
      }
    }
  }
  const pa = (prod.sections['approvals'] ?? {}) as Record<string, JsonValue>;
  const da = (dev.sections['approvals'] ?? {}) as Record<string, JsonValue>;
  const timeout = typeof pa['timeoutMinutes'] === 'number' ? pa['timeoutMinutes'] : null;
  const required = pa['comment'] === 'required';
  if (timeout !== null && (timeout !== da['timeoutMinutes'] || required)) {
    out.push({
      icon: 'hourglass',
      text: `Approval timeout ${formatCount(timeout)} min${required ? ', a comment is required to approve' : ''}`,
    });
  }
  return out;
}

// ─── Edit view ───

/** The header's health line and the stat "Health check, last 24 hours". */
export interface HealthStat {
  readonly tone: 'success' | 'warning' | 'danger' | 'neutral';
  readonly value: string;
  readonly unit: string | null;
  readonly meta: string;
}

/**
 * The stat's words; "· checked every minute" (kit) from the core's interval (`healthIntervalSeconds`, step API), left
 * out when the core doesn't say it.
 */
export function healthStat(health: ConnectionHealth, spark: readonly number[], intervalSeconds: number | null = null): HealthStat {
  const state = (health.state ?? 'unknown').toLowerCase();
  const ms = health.latencyMs;
  const every = intervalSeconds ? `checked ${everyInterval(intervalSeconds)}` : null;
  const and = (text: string) => (every ? `${text} · ${every}` : text);
  const range = spark.filter((v) => v > 0);
  if (state === 'unreachable') {
    return { tone: 'danger', value: 'No answer', unit: null, meta: and(health.message ?? 'Unreachable') };
  }
  if (state === 'unknown' || ms === null) {
    return { tone: 'neutral', value: 'Not checked yet', unit: null, meta: and('No result yet') };
  }
  const value = ms >= 1000 ? (ms / 1000).toFixed(1) : String(ms);
  const unit = ms >= 1000 ? 's' : 'ms';
  const between = range.length > 1 ? and(`Between ${duration(Math.min(...range))} and ${duration(Math.max(...range))}`) : null;
  if (state === 'degraded') return { tone: 'warning', value, unit, meta: between ?? and('Slower than 1 s') };
  return { tone: 'success', value, unit, meta: between ?? (every ? `C${every.slice(1)}` : 'Answered its last check') };
}

/** The sparkline's points over the kit's 230 × 40 box (left = 24 h ago); null without two checks to draw. */
export function sparkPoints(values: readonly number[]): string | null {
  if (values.length < 2) return null;
  const max = Math.max(...values, 1);
  const step = 230 / (values.length - 1);
  return values.map((v, i) => `${Math.round(i * step)},${Math.round(36 - (Math.max(v, 0) / max) * 32)}`).join(' ');
}

/** The rows of "Used by", grouped by endpoint, tools in the connector's order (the core sends them by name). */
export function usedByRows(usedBy: readonly ConnectionUsedBy[], toolOrder: readonly string[]): ConnectionUsedBy[] {
  const rank = (t: string) => {
    const i = toolOrder.indexOf(t);
    return i < 0 ? toolOrder.length : i;
  };
  return [...usedBy].sort((a, b) => a.endpoint.localeCompare(b.endpoint) || rank(a.tool) - rank(b.tool) || a.tool.localeCompare(b.tool));
}

/** The endpoints a connection is on and the tools that are on there (not Off): delete's "Stops working". */
export interface EndpointUse {
  readonly slug: string;
  readonly tools: readonly string[];
  readonly clients: readonly string[];
}

export function endpointUses(rows: readonly ConnectionUsedBy[], endpoints: readonly EndpointSummary[]): EndpointUse[] {
  const slugs = [...new Set(rows.map((r) => r.endpoint))];
  return slugs.map((slug) => ({
    slug,
    tools: rows.filter((r) => r.endpoint === slug && toolMode(r.mode) !== 'OFF').map((r) => r.tool),
    clients: (endpoints.find((e) => e.slug === slug)?.clients ?? []).map(clientName),
  }));
}

/** "4 tools on dev · Claude Desktop and Codex" (the stat's line under "Used by"). */
export function usedByMeta(uses: readonly EndpointUse[]): { readonly tools: number; readonly endpoints: readonly string[]; readonly clients: string } {
  const tools = uses.reduce((n, u) => n + u.tools.length, 0);
  const clients = [...new Set(uses.flatMap((u) => u.clients))];
  return { tools, endpoints: uses.map((u) => u.slug), clients: clients.length ? list(clients) : '' };
}

/** `activity?q=` for a connection, or one of its tools on one endpoint ("connection:dev-db tool:sql_query endpoint:dev"). */
export function activityQuery(connection: string, tool?: string | null, endpoint?: string | null): string {
  return [`connection:${connection}`, tool ? `tool:${tool}` : null, endpoint ? `endpoint:${endpoint}` : null].filter(Boolean).join(' ');
}

/** Delete's sentence about the credentials: what is destroyed here and what stays in its store. */
export function secretsSentence(secrets: readonly StoredSecret[], fieldTitles: Readonly<Record<string, string>>): string {
  const what = (s: StoredSecret) => (fieldTitles[s.field] ?? s.field.split('/').at(-1) ?? 'secret').toLowerCase();
  const local = secrets.filter((s) => s.state === 'stored');
  const refs = secrets.filter((s) => s.state === 'reference');
  const parts: string[] = [];
  if (local.length) {
    const items = local.map((s) => `${what(s)}${s.version != null ? ` (version ${s.version})` : ''}`);
    parts.push(`Its encrypted ${list(items)} ${local.length === 1 ? 'is' : 'are'} destroyed.`);
  }
  if (refs.length) {
    parts.push(refs.length === 1 ? 'Its reference is removed; the secret itself stays in its store.' : 'Its references are removed; the secrets stay in their stores.');
  }
  return parts.length ? parts.join(' ') : 'Nothing is stored here for it.';
}

/** A secret field's word ("password", "service account token") from the form's secret fields. */
export function secretWord(fields: readonly { readonly pointer: string; readonly title: string }[], pointer: string): string {
  return (fields.find((f) => f.pointer === pointer)?.title ?? pointer.split('/').at(-1) ?? 'secret').toLowerCase();
}

// ─── Toasts ───

/** "Health is checked every minute." only when the core said how often (step API). */
export function savedToast(
  name: string,
  added: readonly string[],
  intervalSeconds: number | null = null,
): { readonly title: string; readonly body: string } {
  const every = intervalSeconds ? ` Health is checked ${everyInterval(intervalSeconds)}.` : '';
  return {
    title: `Connection ${name} saved`,
    body: added.length ? `Added to ${list(added)}.${every}` : "It is not on an endpoint yet, so agents can't use it.",
  };
}

/** The edit view's Test connection result (kit: "dev-db is healthy" · "Answered in 12 ms."). */
export function testToast(name: string, outcome: TestOutcome, address: string | null): { readonly tone: 'success' | 'warning' | 'danger'; readonly title: string; readonly body: string } {
  if (outcome.ok) {
    const slow = outcome.latencyMs > 1000;
    return { tone: slow ? 'warning' : 'success', title: `${name} is ${slow ? 'slow' : 'healthy'}`, body: `Answered in ${duration(outcome.latencyMs) || '0 ms'}.` };
  }
  return { tone: 'danger', title: `${name} is unreachable`, body: outcome.error?.message ?? testFailureTitle(outcome, address) };
}

export function deletedToast(name: string, endpoints: readonly string[]): { readonly title: string; readonly body: string } {
  return { title: `Connection ${name} deleted`, body: endpoints.length ? `Removed from ${list(endpoints)}.` : 'It was on no endpoint.' };
}

/** Configure's title names the type: "Configure PostgreSQL"; a deep copy keeps the source's name ("dev-db-copy"). */
export function copyName(source: string, taken: readonly string[]): string {
  let name = `${source}-copy`;
  for (let i = 2; taken.includes(name); i++) name = `${source}-copy-${i}`;
  return name;
}
