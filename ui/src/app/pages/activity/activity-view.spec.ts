import { clockTime } from '../../core/time/time-format';
import {
  NEWEST_FIRST,
  PRESET_VIEWS,
  applyCall,
  backToRange,
  captionText,
  changeRow,
  changesPagerText,
  changesSince,
  clientView,
  deniedWaiting,
  drawerDescription,
  durationCell,
  emptyState,
  envsOf,
  eventFromFragment,
  exportNote,
  exportedToast,
  histogramTotal,
  histogramWaiting,
  insightsRangeParam,
  localInstants,
  matchedCount,
  mergeFacets,
  mergeHead,
  pagerText,
  parseRange,
  parseView,
  queryWithout,
  rangeParam,
  rangeWords,
  requestWindow,
  retentionNote,
  revokeDescription,
  revokeTarget,
  revokeWords,
  shownViews,
  sortRows,
  tagFields,
  tracesInRange,
  tracesPagerText,
  unsendable,
  viewParam,
  widerRange,
  withView,
} from './activity-view';
import { ENDED_TASK, HOUR, MIN, NOW, OLD_TASK, WAITING_TASK, ago, call, change, devTokens, kitCalls, kitChanges, kitHistogram, token, traceDetail } from './activity.fixtures';

const DAY = { kind: 'relative', key: '24h' } as const;

describe('Activity view (design/pages/activity.html)', () => {
  it('reads the address: ?view= and #event-<id>', () => {
    expect([parseView(null), parseView('traces'), parseView('changes'), parseView('nonsense')]).toEqual(['events', 'traces', 'changes', 'events']);
    expect([viewParam('events'), viewParam('traces'), viewParam('changes')]).toEqual([null, 'traces', 'changes']);
    expect(eventFromFragment('event-412')).toBe(412);
    expect([eventFromFragment('event-'), eventFromFragment('event-ev_2b9f'), eventFromFragment('ac-panel-events'), eventFromFragment(null)]).toEqual([null, null, null, null]);
  });

  it('reads ?range= (Activity’s ranges and the 30 days of Insights links) and writes it back', () => {
    expect(['15m', '1h', '24h', '7d', '30d'].map((r) => parseRange(r))).toEqual(['15m', '1h', '24h', '7d', '30d'].map((key) => ({ kind: 'relative', key })));
    expect([parseRange('90d'), parseRange('custom'), parseRange(''), parseRange(null)]).toEqual([null, null, null, null]);
    expect([rangeParam(DAY), rangeParam({ kind: 'relative', key: '7d' }), rangeParam({ kind: 'custom', from: 'a', to: 'b' })]).toEqual([null, '7d', null]);
    // Insights has no 15 minutes, and 24 hours is its default.
    expect(['15m', '1h', '24h', '7d', '30d'].map((key) => insightsRangeParam({ kind: 'relative', key } as never))).toEqual([null, '1h', null, '7d', '30d']);
  });

  it('says why Send to SIEM can’t use a destination (the core answers 409 for these)', () => {
    expect(unsendable({ enabled: true, verified: true, demo: false })).toBeNull();
    expect(unsendable({ enabled: false })).toBe('paused');
    expect(unsendable({ enabled: true, verified: false })).toBe('changed outside GateRook');
    expect(unsendable({ enabled: true, demo: true })).toBe('demo, never called');
  });

  it('counts each facet within the other filters', () => {
    expect(tagFields('decision:denied -decision:allowed env:prod sync')).toEqual(['decision', 'env']);
    expect(queryWithout('decision:denied env:prod sync', 'decision')).toBe('env:prod sync');
    expect(queryWithout('-tool:logs_* env:prod', 'tool')).toBe('env:prod');
    const merged = mergeFacets({ decision: [{ name: 'denied', count: 2 }], env: [{ name: 'prod', count: 2 }] }, { decision: { decision: [{ name: 'allowed', count: 9 }, { name: 'denied', count: 2 }] } });
    expect(merged['decision'].map((f) => f.name)).toEqual(['allowed', 'denied']);
    expect(merged['env']).toEqual([{ name: 'prod', count: 2 }]);
  });

  it('counts calls from the histogram: all, waiting, the zoomed hour', () => {
    const h = kitHistogram();
    expect(histogramTotal(h)).toBe(10);
    expect(histogramTotal(null)).toBeNull();
    expect(histogramWaiting(h)).toBe(2);
    expect(matchedCount(h, null)).toBe(10);
    expect(matchedCount(h, h.buckets[22])).toBe(1);
    expect(matchedCount(null, null)).toBeNull();
  });

  it('asks the core for a relative range up to its own now, a custom one or a zoomed hour as given', () => {
    expect(requestWindow(DAY, null, NOW)).toEqual({ from: ago(24 * HOUR), to: null });
    const custom = { kind: 'custom', from: ago(5 * HOUR), to: ago(2 * HOUR) } as const;
    expect(requestWindow(custom, null, NOW)).toEqual({ from: custom.from, to: custom.to });
    const zoom = { from: ago(2 * HOUR), to: ago(HOUR) };
    expect(requestWindow(DAY, zoom, NOW)).toEqual(zoom);
  });

  it('names the range in sentences', () => {
    expect(rangeWords(DAY, null, NOW)).toBe('in the last 24 hours');
    expect(rangeWords({ kind: 'relative', key: '1h' }, null, NOW)).toBe('in the last hour');
    const zoom = { from: new Date(2026, 9, 22, 10).toISOString(), to: new Date(2026, 9, 22, 11).toISOString() };
    expect(rangeWords(DAY, zoom, NOW)).toBe('between 10:00 and 11:00');
    const custom = { kind: 'custom', from: new Date(2026, 9, 21, 9).toISOString(), to: new Date(2026, 9, 22, 12).toISOString() } as const;
    expect(rangeWords(custom, null, NOW)).toBe('between Oct 21 09:00 and Today 12:00');
    expect(backToRange(DAY, NOW)).toBe('Show the last 24 hours');
    expect(backToRange(custom, NOW)).toBe('Show the whole range');
    expect(widerRange(DAY)).toEqual({ kind: 'relative', key: '7d' });
    expect(widerRange({ kind: 'relative', key: '7d' })).toBeNull();
    expect(widerRange(custom)).toBeNull();
  });

  it('says who called: a client, the Playground, or nobody known', () => {
    expect(clientView('codex')).toEqual({ id: 'codex', name: 'Codex', letter: null });
    expect(clientView('playground')).toEqual({ id: 'playground', name: 'Playground', letter: 'P' });
    expect(clientView(null)).toEqual({ id: 'unknown', name: 'Unknown client', letter: '?' });
  });

  it('writes the Duration cell as the kit does', () => {
    const [waiting, , allowed, ssh, denied, rejected, approved, error, expired] = kitCalls();
    expect(durationCell(waiting, NOW)).toBe('30 s so far');
    expect(durationCell(allowed, NOW)).toBe('38 ms');
    expect(durationCell(ssh, NOW)).toBe('412 ms');
    expect(durationCell(approved, NOW)).toBe('47.0 s');
    expect(durationCell(error, NOW)).toBe('2.0 s');
    expect(durationCell({ ...rejected, durationMs: 52_000 }, NOW)).toBe('52 s');
    expect(durationCell({ ...expired, durationMs: 600_000 }, NOW)).toBe('10 min');
    // The core sends no duration for a call that never ran.
    expect(durationCell(denied, NOW)).toBe('Not run');
    expect(durationCell(expired, NOW)).toBe('Not run');
    expect(durationCell({ ...error, durationMs: null }, NOW)).toBe('Failed');
    // An allowed call still running (received, not completed) counts up.
    expect(durationCell({ ...allowed, durationMs: null, at: ago(4 * MIN) }, NOW)).toBe('4 min so far');
  });

  it('sorts what is on screen by time or duration', () => {
    const rows = kitCalls();
    expect(sortRows(rows, NEWEST_FIRST, NOW).map((e) => e.id)).toEqual(rows.map((e) => e.id));
    expect(sortRows(rows, { key: 'time', direction: 'ascending' }, NOW)[0].id).toBe(1);
    const byDuration = sortRows(rows, { key: 'duration', direction: 'descending' }, NOW).map((e) => e.id);
    expect(byDuration.slice(0, 3)).toEqual([17, 4, 18]); // the 48 s wait still going, 47 s, the 30 s wait
    expect(byDuration.slice(-3).sort()).toEqual([2, 6, 9]); // never ran
  });

  it('Live merges the newest page over the list without a gap', () => {
    const rows = kitCalls().slice(2);
    const head = [call(20, 1000, 'allowed', 'sql_query'), { ...rows[0], id: 99, decision: 'allowed' as const }];
    const merged = mergeHead(head, rows);
    expect(merged.restarted).toBe(false);
    expect(merged.rows.map((e) => e.id)).toEqual([20, 99, ...rows.slice(1).map((e) => e.id)]);
    const far = mergeHead([call(30, 1000, 'allowed', 'sql_query')], rows);
    expect(far).toEqual({ rows: [call(30, 1000, 'allowed', 'sql_query')], restarted: true });
  });

  it('updates a listed call in place from its newer audit row', () => {
    const rows = kitCalls();
    const sse = { id: 40, callId: 'ev_17', at: ago(0), phase: 'resolved', decision: 'approved' as const, tool: 'sql_execute', connection: 'dev-db', env: 'dev' as const, client: 'codex', endpoint: 'dev', token: 'codex-laptop', durationMs: null, rule: 'dev.sql.write', approvalId: 'ap_sql', traceId: null };
    const next = applyCall(rows, sse)!;
    expect(next[1]).toMatchObject({ id: 40, decision: 'approved', rule: 'dev.sql.write', traceId: 'c7e19d4a02b84f6e9a31d5c8b7f20e64' });
    expect(next.length).toBe(rows.length);
    expect(applyCall(rows, { ...sse, callId: 'ev_unknown' })).toBeNull();
    expect(applyCall(rows, { ...sse, id: 1 })).toBeNull(); // an older row never overwrites a newer one
  });

  it('captions and pages the table', () => {
    expect(captionText('in the last 24 hours', '')).toBe("Calls in the last 24 hours, newest first. Each row opens the call's details.");
    expect(captionText('in the last 24 hours', 'decision:denied')).toBe("Calls in the last 24 hours matching decision:denied, newest first. Each row opens the call's details.");
    expect(pagerText(18, 1287, false, 'in the last 24 hours')).toBe('Showing 18 of 1,287 calls in the last 24 hours');
    expect(pagerText(9, 9, true, 'in the last hour')).toBe('All 9 calls that match in the last hour');
    expect(pagerText(1, 1, false, 'in the last hour')).toBe('The only call in the last hour');
  });

  it('says why nothing is listed and what to do', () => {
    const base = { q: '', words: 'in the last 24 hours', zoom: null, back: 'Show the last 24 hours', widest: false };
    expect(emptyState({ ...base, q: 'connection:aws-stage' })).toEqual({
      title: 'No calls match these filters',
      text: 'No call in the last 24 hours matches connection:aws-stage. Remove a filter or widen the range.',
      reset: null,
      widen: false,
      clear: true,
    });
    const zoom = { from: ago(12 * HOUR), to: ago(11 * HOUR) };
    expect(emptyState({ ...base, words: 'between 02:00 and 03:00', zoom })).toEqual({
      title: 'No calls match these filters',
      text: 'No agent called the gateway between 02:00 and 03:00. Pick another hour or go back to the last 24 hours.',
      reset: 'Show the last 24 hours',
      widen: false,
      clear: false,
    });
    expect(emptyState(base)).toMatchObject({ title: 'No calls in the last 24 hours', widen: true, clear: false });
    expect(emptyState({ ...base, widest: true }).text).toBe('Agents made no calls in this range. Pick a custom range to look further back.');
  });

  it('describes the export and what it wrote', () => {
    expect(exportNote(1287, true)).toBe('Exports the 1,287 calls that match the filters and range below. Masked values stay masked.');
    expect(exportNote(null, false)).toBe('Exports the calls that match the filters and range below. Masked values stay masked. Saving a file needs the GateRook desktop app.');
    expect(exportedToast({ path: '/home/me/activity.csv', rows: 9, format: 'csv' })).toEqual({ title: '9 calls exported', body: '/home/me/activity.csv. Masked values stay masked.' });
  });

  it('offers the starting views until the person saves their own; a name saved again replaces it', () => {
    expect(shownViews(null)).toBe(PRESET_VIEWS);
    expect(shownViews([])).toBe(PRESET_VIEWS);
    const mine = [{ name: 'Prod', q: 'env:prod', view: 'events' }];
    expect(shownViews(mine)).toBe(mine);
    const added = withView(PRESET_VIEWS, { name: 'Prod', q: 'env:prod', view: 'events' });
    expect(added.map((v) => v.name)).toEqual(['Without log reads', 'Denied on prod', 'Waiting for approval', 'Slow calls', 'Prod']);
    const replaced = withView(added, { name: 'slow CALLS', q: 'duration:>5s', view: 'events' });
    expect(replaced).toHaveLength(5);
    expect(replaced[3]).toEqual({ name: 'slow CALLS', q: 'duration:>5s', view: 'events' });
  });

  it('notes the retention in the custom range dialog', () => {
    expect(retentionNote(1)).toBe('The audit log keeps calls for 1 year, the default retention in Settings › Audit and retention.');
    expect(retentionNote(3)).toBe('The audit log keeps calls for 3 years, as set in Settings › Audit and retention.');
    expect(retentionNote(null)).toBeNull();
  });

  it('lists the tasks of the range and pages them', () => {
    const from = NOW - 24 * HOUR;
    expect(tracesInRange([WAITING_TASK, ENDED_TASK, OLD_TASK], from, null)).toEqual([WAITING_TASK, ENDED_TASK]);
    expect(tracesInRange([WAITING_TASK, ENDED_TASK], from, NOW - HOUR + 30 * MIN)).toEqual([ENDED_TASK]);
    expect(tracesPagerText(4, 61, false, 'in the last 24 hours')).toBe('Newest 4 of 61 tasks in the last 24 hours');
    expect(tracesPagerText(3, 3, false, 'in the last hour')).toBe('All 3 tasks in the last hour');
    expect(tracesPagerText(1, 1, false, 'in the last hour')).toBe('The only task in the last hour');
    expect(tracesPagerText(2, 2, true, 'in the last 24 hours')).toBe('2 tasks match in the last 24 hours');
  });

  it('places a call in its task for the drawer', () => {
    const calls = kitCalls().slice(0, 3).reverse();
    const d = { event: calls[1], rows: [] };
    expect(drawerDescription(d, traceDetail(WAITING_TASK, calls))).toBe('Call 2 of 5 in trace c7e19d');
    expect(drawerDescription({ event: call(77, 0, 'allowed', 'x'), rows: [] }, traceDetail(WAITING_TASK, calls))).toBe('Call in trace c7e19d');
    expect(drawerDescription(d, null)).toBe('Not part of a traced task');
  });

  it('shows Setup changes as the kit table does', () => {
    const [tunnelOff, modeOff, , , revoked] = kitChanges().map(changeRow);
    expect(tunnelOff).toMatchObject({ what: { text: 'Tunnel', route: ['/settings', 'tunnel'], caption: 'Cloudflare' }, by: 'You', reason: null });
    expect(tunnelOff.from).toEqual({ kind: 'status', tone: 'success', text: 'On' });
    expect(tunnelOff.to).toEqual({ kind: 'status', tone: 'neutral', text: 'Off' });
    expect(modeOff.what).toEqual({ text: 'http_get', mono: true, route: ['/endpoints', 'dev'], queryParams: { tab: 'tools' }, caption: 'on billing-api, endpoint dev' });
    expect(modeOff.from).toEqual({ kind: 'mode', cls: 'gw-mode--read', icon: 'eye', text: 'Read only' });
    expect(modeOff.to).toEqual({ kind: 'mode', cls: 'gw-mode--off', icon: 'circle-off', text: 'Off' });
    expect(revoked.what).toMatchObject({ text: 'old-claude', mono: true, route: ['/endpoints', 'dev'], caption: 'token, endpoint dev' });
    expect(revoked.to).toEqual({ kind: 'status', tone: 'danger', text: 'Revoked' });

    const grant = changeRow(change(9, 0, 'grant', 'Grant codex-laptop (codex) for dev', { to: 'Active' }));
    expect(grant.what).toMatchObject({ text: 'codex-laptop', caption: 'access of Codex, endpoint dev' });
    expect(changeRow(change(10, 0, 'client', 'Codex → dev', { from: 'Not connected', to: 'Connected' })).what).toMatchObject({ text: 'Codex', route: ['/clients'], caption: 'endpoint dev' });
    expect(changeRow(change(11, 0, 'connection', 'Connection dev-db created', { connection: 'dev-db' })).what).toMatchObject({ text: 'dev-db', mono: true, route: ['/connections', 'dev-db'], caption: 'created' });
    expect(changeRow(change(12, 0, 'settings', 'Endpoint stage-readonly · access', { endpoint: 'stage-readonly', from: 'standard', to: 'read-only' })).what).toMatchObject({ text: 'stage-readonly', caption: 'endpoint, access' });
    expect(changeRow(change(13, 0, 'policy', 'Policy prod', { env: 'prod' })).what.route).toEqual(['/policies', 'prod']);
    expect(changeRow(change(14, 0, 'something-new', 'Something new')).what).toEqual({ text: 'Something new', mono: false, route: null, queryParams: null, caption: null });
  });

  it('writes instants of the core in local time, and keeps the day for the tab count', () => {
    const t = new Date(2026, 10, 1, 18, 54).toISOString();
    expect(localInstants(`gwt_bDCj… · expires ${t}`)).toBe(`gwt_bDCj… · expires 2026-11-01 ${clockTime(t, false)}`);
    expect(localInstants('no instant here')).toBe('no instant here');
    expect(changesSince(kitChanges(), NOW - 24 * HOUR).map((c) => c.id)).toEqual([4, 3, 2, 1]);
    expect(changesPagerText(4, 4, false)).toBe('All 4 changes in the last 24 hours');
    expect(changesPagerText(1, 1, false)).toBe('The only change in the last 24 hours');
    expect(changesPagerText(46, 4, true)).toBe('46 changes, newest first');
  });

  it('finds the access behind a call while it can be revoked, and says what revoking does', () => {
    const tokens = devTokens();
    expect(revokeTarget({ tokenLabel: 'codex-laptop' }, tokens)?.id).toBe('gr_codex-laptop');
    expect(revokeTarget({ tokenLabel: 'old-claude' }, tokens)).toBeNull(); // already revoked
    expect(revokeTarget({ tokenLabel: null }, tokens)).toBeNull();

    const grant = revokeWords(tokens[0], 'dev', null);
    expect(grant.title).toBe('Revoke codex-laptop?');
    expect(revokeDescription(grant)).toBe(
      "Codex's current token stops working at once and its headers helper gets no new one, so Codex loses access to endpoint dev on its next call. Connect Codex again under Clients to restore it. Its past calls stay in this log.",
    );
    expect(grant.endpoint).toBe('dev');
    expect(grant.accessRest).toBe(' · 15-minute tokens issued on the fly, none stored');
    expect(grant.usedBy).toBe('Codex');
    expect(grant.through).toEqual({ text: 'gw-bridge headers', mono: true });
    expect([grant.button, grant.toastTitle, grant.toastBody]).toEqual(['Revoke codex-laptop', 'codex-laptop revoked', 'Codex loses access on its next call.']);

    const bridge = revokeWords(token('claude-desktop', 'grant', { client: 'claude-desktop' }), 'dev', null);
    expect(revokeDescription(bridge)).toContain("Claude Desktop's current token stops working at once and its bridge gets no new one");
    expect(bridge.through).toEqual({ text: 'the bridge extension (.mcpb)', mono: false });

    const named = revokeWords(tokens[1], 'dev', 'in 3 days');
    expect(revokeDescription(named)).toBe(
      'Token inspector-debug stops working at once, so whatever uses it loses access to endpoint dev on its next call. Issue a new token on the endpoint to restore it. Its past calls stay in this log.',
    );
    expect(named.accessRest).toBe(' · gwt_Ie-a…, expires in 3 days');
    expect(named.through).toBeNull();
    expect([deniedWaiting(0), deniedWaiting(1), deniedWaiting(2)]).toEqual(['', ' 1 waiting request was denied.', ' 2 waiting requests were denied.']);
  });

  it('knows each connection’s environment for the facets', () => {
    expect(envsOf([{ name: 'dev-db', env: 'dev' }, { name: 'prod-replica', env: 'prod' }])).toEqual({ 'dev-db': 'dev', 'prod-replica': 'prod' });
  });
});
