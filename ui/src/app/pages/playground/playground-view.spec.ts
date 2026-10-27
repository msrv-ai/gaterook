import { compileSchema } from '../../schema-form/compile';
import { CONNECTIONS, DEV_GROUPS, ENDPOINTS, PROD_GROUPS, TABLE, callView, event, playTools, prodTools } from './playground.fixtures';
import {
  GENERAL_DETAIL,
  GENERAL_TITLE,
  approvalToast,
  carryOver,
  cellText,
  chooseEndpoint,
  chosenAnnouncement,
  connectionOptions,
  defaultPair,
  endpointAnnouncement,
  endpointOption,
  fieldHint,
  findPair,
  groupConnections,
  historyRows,
  httpStatusLine,
  isEmptyResult,
  isLongText,
  isMasked,
  joinNames,
  maskedFacts,
  modelFromArguments,
  parseArguments,
  pickerGroups,
  prodWarningTail,
  requestText,
  responseText,
  resultTable,
  revisionNote,
  runDecision,
  runState,
  runStatus,
  sizeText,
  toolDescriptions,
  traceIdOf,
  urlParts,
  wireJson,
} from './playground-view';

const [prod, dev, readonly] = ENDPOINTS;
const tools = playTools();
const toolNamed = (name: string) => tools.find((t) => t.name === name)!;
const nodesOf = (name: string) => compileSchema(toolNamed(name).inputSchema).all;
const nodeOf = (tool: string, key: string) => nodesOf(tool).find((n) => n.key === key)!;

describe('Playground view: endpoint', () => {
  it('words the select options as the kit does', () => {
    expect(endpointOption(dev)).toBe('dev · 8 connections, 22 tools');
    expect(endpointOption(readonly)).toBe('stage-readonly · 3 connections, read only');
    expect(endpointOption(prod)).toBe('incident-prod · 3 prod connections');
    expect(endpointOption({ ...dev, connections: { total: 1, byEnv: {} }, tools: { read: 1, approval: 0, off: 0 } })).toBe('dev · 1 connection, 1 tool');
    expect(endpointOption({ ...dev, status: 'disabled' })).toBe('dev · 8 connections, 22 tools, disabled');
  });

  it('opens the endpoint the link names, else the first that is not prod', () => {
    expect(chooseEndpoint(ENDPOINTS, 'incident-prod')).toEqual({ slug: 'incident-prod', missing: null });
    expect(chooseEndpoint(ENDPOINTS, null)).toEqual({ slug: 'dev', missing: null });
    expect(chooseEndpoint(ENDPOINTS, 'gone')).toEqual({ slug: 'dev', missing: 'gone' });
    expect(chooseEndpoint([prod], null)).toEqual({ slug: 'incident-prod', missing: null });
    expect(chooseEndpoint([{ ...dev, status: 'disabled' }, readonly], null).slug).toBe('stage-readonly');
    expect(chooseEndpoint([], 'dev')).toEqual({ slug: null, missing: 'dev' });
  });

  it('names connections like a sentence', () => {
    expect(joinNames([])).toBe('');
    expect(joinNames(['a'])).toBe('a');
    expect(joinNames(['a', 'b'])).toBe('a and b');
    expect(joinNames(['prod-replica', 'prod-logs', 'prod-vm'])).toBe('prod-replica, prod-logs and prod-vm');
  });

  it('says in the prod warning what waits for a person and whether approving needs a comment', () => {
    expect(prodWarningTail(prodTools(), true)).toBe('They are real reads on production, and a runbook waits for approval like an agent’s; on prod, approving needs a comment.');
    expect(prodWarningTail(prodTools(), null)).toBe('They are real reads on production, and a runbook waits for approval like an agent’s.');
    expect(prodWarningTail(tools, false)).toBe('They are real reads on production, and a change waits for approval like an agent’s.');
    expect(prodWarningTail([toolNamed('sql_query')], true)).toBe('They are real reads on production; on prod, approving needs a comment.');
    expect(groupConnections(PROD_GROUPS)).toEqual(['prod-replica', 'prod-logs', 'prod-vm']);
  });
});

describe('Playground view: picker', () => {
  it('puts the endpoint’s own tools first as General, then each connection with its type and host', () => {
    const groups = pickerGroups(DEV_GROUPS, CONNECTIONS);
    expect(groups.map((g) => g.group.connection)).toEqual([null, 'dev-db', 'dev-vm', 'billing-api']);
    expect(groups[0]).toMatchObject({ title: GENERAL_TITLE, detail: GENERAL_DETAIL, type: null });
    expect(groups[1]).toMatchObject({ type: 'postgres', detail: 'PostgreSQL · db.dev.internal:5432/app' });
    expect(groups[1].title).toBeUndefined();
    // Connections that didn't load: the core's group name is all there is.
    expect(pickerGroups(DEV_GROUPS, [])[1]).toMatchObject({ type: null, detail: 'PostgreSQL' });
  });

  it('gives each tool its sentence', () => {
    expect(toolDescriptions(tools)['sql_query']).toBe('Run one SELECT in a read-only transaction. PII columns come back masked.');
  });

  it('finds the row of a tool, on a connection when named', () => {
    expect(findPair(DEV_GROUPS, 'sql_execute')).toEqual({ name: 'sql_execute', connection: 'dev-db', env: 'dev', mode: 'APPROVAL' });
    expect(findPair(DEV_GROUPS, 'list_connections')).toEqual({ name: 'list_connections', connection: null, env: null, mode: 'READ_ONLY' });
    expect(findPair(DEV_GROUPS, 'ssh_exec')?.mode).toBe('OFF');
    expect(findPair(DEV_GROUPS, 'nope')).toBeNull();
    const twice = [...DEV_GROUPS, { group: 'PostgreSQL', connection: 'stage-db', env: 'stage' as const, tools: [{ ...DEV_GROUPS[0].tools[1], mode: 'OFF' as const }] }];
    expect(findPair(twice, 'sql_query', 'stage-db')).toMatchObject({ connection: 'stage-db', mode: 'OFF' });
    expect(findPair(twice, 'sql_query', 'gone')).toMatchObject({ connection: 'dev-db' });
  });

  it('opens on sql_query, else the first tool that runs, General first', () => {
    expect(defaultPair(DEV_GROUPS)?.name).toBe('sql_query');
    const noSql = DEV_GROUPS.map((g) => ({ ...g, tools: g.tools.filter((t) => !t.name.startsWith('sql_')) }));
    expect(defaultPair(noSql)?.name).toBe('list_connections');
    const allOff = [{ ...DEV_GROUPS[1], tools: [DEV_GROUPS[1].tools[1]] }];
    expect(defaultPair(allOff)).toMatchObject({ name: 'ssh_exec', mode: 'OFF' });
    expect(defaultPair([])).toBeNull();
  });

  it('knows what Run does for the chosen row', () => {
    expect(runState(findPair(DEV_GROUPS, 'sql_query')!, toolNamed('sql_query'))).toBe('run');
    expect(runState(findPair(DEV_GROUPS, 'sql_execute')!, toolNamed('sql_execute'))).toBe('approval');
    expect(runState(findPair(DEV_GROUPS, 'ssh_exec')!, toolNamed('ssh_exec'))).toBe('off');
    expect(runState(findPair(DEV_GROUPS, 'sql_query')!, { ...toolNamed('sql_query'), runnable: false, reason: 'Endpoint dev is disabled.' })).toBe('blocked');
    expect(runState(findPair(DEV_GROUPS, 'sql_query')!, null)).toBe('run');
  });

  it('announces a choice and another endpoint', () => {
    expect(chosenAnnouncement('sql_execute', 'APPROVAL', 'dev')).toBe('sql_execute chosen. Mode on dev: approval. This call needs approval.');
    expect(chosenAnnouncement('ssh_exec', 'OFF', 'dev')).toBe("ssh_exec chosen. Mode on dev: off. It can't run.");
    expect(chosenAnnouncement('sql_query', 'READ_ONLY', 'dev')).toBe('sql_query chosen. Mode on dev: read only.');
    expect(endpointAnnouncement('incident-prod', 10, ['prod-replica', 'prod-logs', 'prod-vm'], 'sql_query')).toBe('Endpoint incident-prod: 10 tools. Warning: calls run on prod-replica, prod-logs and prod-vm. sql_query chosen.');
    expect(endpointAnnouncement('dev', 1, null, null)).toBe('Endpoint dev: 1 tool.');
  });
});

describe('Playground view: arguments', () => {
  it('offers the row’s connection, else what the schema lists', () => {
    expect(connectionOptions(findPair(DEV_GROUPS, 'sql_query')!, toolNamed('sql_query'), ['dev-db', 'stage-db'])).toEqual(['dev-db']);
    const general = findPair(DEV_GROUPS, 'describe_connection')!;
    expect(connectionOptions(general, toolNamed('describe_connection'), ['dev-db', 'dev-vm'])).toEqual(['dev-db', 'dev-vm']);
    expect(connectionOptions(general, { ...toolNamed('describe_connection'), connections: ['billing-api'] }, [])).toEqual(['billing-api']);
  });

  it('draws long strings as code and says the number rules under a number', () => {
    expect(isLongText(nodeOf('sql_query', 'sql'))).toBe(true);
    expect(isLongText(nodeOf('sql_execute', 'note'))).toBe(false);
    expect(fieldHint(nodeOf('sql_query', 'limit'))).toBe('Max rows; the endpoint caps it. Integer, 50 when empty, from 1 to 1,000.');
    expect(fieldHint(nodeOf('sql_query', 'sql'))).toBe('One SELECT, WITH … SELECT, VALUES, TABLE, SHOW or EXPLAIN. Runs in a read-only transaction.');
    expect(fieldHint(nodeOf('sql_list_schemas', 'pattern'))).toBeNull();
    const max = compileSchema({ type: 'object', properties: { n: { type: 'number', maximum: 5 } } }).all[0];
    expect(fieldHint(max)).toBe('Number, at most 5.');
  });

  it('carries what fits to the next tool (sql stays when sql_query becomes sql_execute)', () => {
    const previous = { connection: 'dev-db', sql: 'SELECT 1', limit: 50 };
    expect(carryOver(previous, toolNamed('sql_execute').inputSchema)).toEqual({ connection: 'dev-db', sql: 'SELECT 1' });
    // Another connection than the schema allows, a value of another type, empty values: dropped.
    expect(carryOver({ connection: 'dev-vm', sql: 42, limit: '' }, toolNamed('sql_query').inputSchema)).toEqual({});
    expect(carryOver({ limit: 10 }, { type: 'object', properties: { limit: { type: 'number' } } })).toEqual({ limit: 10 });
  });

  it('prints arguments as the kit does', () => {
    expect(wireJson({ connection: 'dev-db', columns: ['id', 'status'], filter: { a: [{ b: 1 }] }, empty: [], none: {} })).toBe(
      '{\n  "connection": "dev-db",\n  "columns": ["id", "status"],\n  "filter": {\n    "a": [\n      {\n        "b": 1\n      }\n    ]\n  },\n  "empty": [],\n  "none": {}\n}',
    );
    expect(wireJson(undefined)).toBe('null');
    expect(wireJson('x')).toBe('"x"');
  });

  it('checks the JSON tab against the schema', () => {
    const nodes = nodesOf('sql_query');
    expect(parseArguments('{ "connection": "dev-db", "sql": "SELECT 2", "limit": 10 }', nodes)).toEqual({ ok: true, value: { connection: 'dev-db', sql: 'SELECT 2', limit: 10 } });
    const bad = parseArguments('{ "sql": ', nodes);
    expect(bad.ok).toBe(false);
    expect(!bad.ok && bad.problem).toMatch(/^This is not valid JSON: .+\. Fix it, or go back to Form: it keeps the last valid arguments\.$/);
    expect(parseArguments('[1]', nodes)).toEqual({ ok: false, problem: 'Arguments must be one JSON object, like { "connection": "dev-db" }.' });
    expect(parseArguments('{ "limit": 5000 }', nodes)).toEqual({ ok: false, problem: 'limit: 5000 is above the maximum. Use 1000 or less.' });
    expect(parseArguments('{ "limit": "ten" }', nodes)).toEqual({ ok: false, problem: 'limit: ten is not a number. Enter a whole number, for example 50.' });
  });

  it('puts JSON arguments into the form model', () => {
    expect(modelFromArguments({ connection: 'dev-db', sql: 'SELECT 1', limit: 50 }, { sql: 'SELECT 2', extra: true })).toEqual({ connection: '', sql: 'SELECT 2', limit: null, extra: true });
  });
});

describe('Playground view: result', () => {
  it('reads rows and columns as a table', () => {
    const t = resultTable(TABLE)!;
    expect(t.columns).toEqual(['id', 'status', 'customer_email', 'total']);
    expect(t.numeric).toEqual([true, false, false, true]);
    expect(t.rows[0]).toEqual([48240, 'pending', '‹masked›', 84.9]);
    expect(t.rowCount).toBe(2);
    expect(t.masked).toEqual(['customer_email']);
    // Rows as objects, column types guessed from the values.
    const objects = resultTable({ columns: [{ name: 'k' }, { name: 'v' }], rows: [{ k: 'a', v: 1 }, { k: 'b' }] })!;
    expect(objects.rows).toEqual([['a', 1], ['b', null]]);
    expect(objects.numeric).toEqual([false, true]);
    expect(resultTable({ connections: [] })).toBeNull();
    expect(resultTable(null)).toBeNull();
  });

  it('marks masked values and prints cells', () => {
    expect(isMasked('‹masked›')).toBe(true);
    expect(isMasked('‹masked›#2')).toBe(true);
    expect(isMasked('masked')).toBe(false);
    expect(cellText(null)).toBe('null');
    expect(cellText({ a: 1 })).toBe('{"a":1}');
    expect(cellText('x')).toBe('x');
  });

  it('takes the decision from the audit, else from the answer', () => {
    expect(runDecision(callView(), null)).toBe('allowed');
    expect(runDecision(callView({ isError: true, text: 'Denied by policy stage.http.url-not-allowed: …' }), null)).toBe('denied');
    expect(runDecision(callView({ isError: true, text: "Can't resolve the host of dev-db." }), null)).toBe('error');
    expect(runDecision(callView({ approval: { handle: 'ap_1', status: 'pending' } }), null)).toBe('pending');
    expect(runDecision(callView({ approval: { handle: 'ap_1', status: 'rejected' } }), null)).toBe('rejected');
    expect(runDecision(callView(), event(1, { decision: 'denied' }))).toBe('denied');
  });

  it('fills the meta strip', () => {
    const call = callView({ structured: TABLE, meta: { durationMs: 38, bytes: 3174, masked: 4, truncated: false } });
    const t = resultTable(call.structured);
    expect(sizeText(call, t)).toBe('3.1 KB · 2 rows');
    expect(sizeText(callView({ meta: { durationMs: 1, bytes: 300, masked: 0, truncated: false } }), null)).toBe('300 B');
    expect(maskedFacts(call, t)).toEqual({ count: 1, names: ['customer_email'] });
    expect(maskedFacts(callView({ meta: { durationMs: 1, bytes: 1, masked: 3, truncated: false } }), null)).toEqual({ count: 3, names: [] });
    expect(traceIdOf(call)).toBe('9e3b51d07a4c4f0e8b2d6c19a5f7e340');
    expect(traceIdOf(callView({ wire: { request: {}, response: {}, httpStatus: 200 } }))).toBeNull();
  });

  it('knows an empty result', () => {
    expect(isEmptyResult(callView({ text: '', structured: null }))).toBe(true);
    expect(isEmptyResult(callView({ text: ' ', structured: {} }))).toBe(true);
    expect(isEmptyResult(callView({ text: '', structured: [] }))).toBe(true);
    expect(isEmptyResult(callView({ text: 'ok' }))).toBe(false);
    expect(isEmptyResult(callView({ text: '', isError: true }))).toBe(false);
    expect(isEmptyResult(callView({ text: '', approval: { handle: 'ap_1', status: 'pending' } }))).toBe(false);
  });

  it('says the outcome in the section’s status', () => {
    const call = callView({ structured: TABLE });
    expect(runStatus('sql_query', 'allowed', call, resultTable(TABLE))).toBe('sql_query allowed in 38 ms: 2 rows, 1 field masked.');
    expect(runStatus('sql_query', 'error', callView({ meta: { durationMs: 5122, bytes: 1, masked: 0, truncated: false } }), null)).toBe('sql_query ended with an error in 5.1 s.');
    expect(runStatus('list_connections', 'allowed', callView({ text: '' }), null)).toBe('list_connections allowed in 38 ms: empty result.');
    expect(runStatus('list_connections', 'allowed', callView(), null)).toBe('list_connections allowed in 38 ms.');
    expect(runStatus('sql_execute', 'pending', callView(), null)).toBe('sql_execute waits for approval in 38 ms.');
  });
});

describe('Playground view: wire', () => {
  it('notes the revision of the call', () => {
    expect(revisionNote('2026-07-28').literals).toEqual(['Mcp-Method', 'Mcp-Name']);
    expect(revisionNote('2025-11-25').text).toContain('initialize');
  });

  it('names HTTP statuses and splits the endpoint URL', () => {
    expect(httpStatusLine(200)).toBe('200 OK');
    expect(httpStatusLine(299)).toBe('299');
    expect(urlParts('http://127.0.0.1:7465/mcp/dev', 'dev')).toEqual({ path: '/mcp/dev', host: '127.0.0.1:7465' });
    expect(urlParts(null, 'dev')).toEqual({ path: '/mcp/dev', host: '127.0.0.1' });
  });

  it('rebuilds the request as a client of the revision sends it, the credential masked', () => {
    const call = callView({ args: { connection: 'dev-db', sql: 'SELECT 1' } });
    const url = urlParts('http://127.0.0.1:7465/mcp/dev', 'dev');
    const modern = requestText(call, '2026-07-28', url, 'sql_query');
    expect(modern.split('\n').slice(0, 9)).toEqual([
      'POST /mcp/dev HTTP/1.1',
      'Host: 127.0.0.1:7465',
      'Content-Type: application/json',
      'Accept: application/json, text/event-stream',
      'MCP-Protocol-Version: 2026-07-28',
      'Mcp-Method: tools/call',
      'Mcp-Name: sql_query',
      'Authorization: Bearer ‹masked›',
      '',
    ]);
    expect(modern).toContain('"name": "sql_query"');
    const older = requestText(call, '2025-11-25', url, 'sql_query');
    expect(older).not.toContain('Mcp-Method');
    expect(older).toContain('MCP-Protocol-Version: 2025-11-25');
    expect(responseText(call).split('\n').slice(0, 3)).toEqual(['HTTP/1.1 200 OK', 'Content-Type: application/json', '']);
  });
});

describe('Playground view: approval and history', () => {
  it('words the toast of a request', () => {
    expect(approvalToast('sql_execute', 'dev-db')).toEqual({ title: 'Approval request sent', body: 'sql_execute on dev-db waits for you in Approvals. The result shows up here once you approve it.' });
    expect(approvalToast('approval_status', null).body).toBe('approval_status waits for you in Approvals. The result shows up here once you approve it.');
  });

  it('keeps the last five runs, fills a denied call’s duration from this page and marks the one shown', () => {
    const events = [
      event(7, { decision: 'denied', durationMs: null, callId: 'tc_7', tool: 'http_request', connection: 'billing-api', env: 'stage' }),
      event(6, { decision: 'denied', durationMs: null, callId: 'tc_6' }),
      event(5),
      event(4, { decision: 'error', durationMs: 5122 }),
      event(3),
      event(2),
    ];
    const rows = historyRows(events, 'tc_7', new Map([['tc_7', 3]]));
    expect(rows).toHaveLength(5);
    expect(rows[0]).toMatchObject({ id: 7, tool: 'http_request', connection: 'billing-api', env: 'stage', duration: '3 ms', current: true, badge: { text: 'Denied by policy', tone: 'danger' } });
    expect(rows[1]).toMatchObject({ duration: '', current: false });
    expect(rows[3]).toMatchObject({ duration: '5.1 s', badge: { text: 'Error' } });
  });
});
