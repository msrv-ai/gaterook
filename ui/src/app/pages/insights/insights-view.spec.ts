import { ClientCard } from '../../core/api/clients.api';
import { InsightUnusual } from '../../core/api/insights.api';
import { NOW, WINDOWS, bucket, clientCards, firstRun, healthNow, insights } from './insights.fixtures';
import {
  activityLink,
  approvalWindows,
  approvalsView,
  bodyOf,
  callsChart,
  callsDelta,
  clientsChart,
  countDelta,
  countWords,
  deniedView,
  errorsView,
  fractionWords,
  latency,
  latencyView,
  niceMs,
  percent,
  plain,
  rangeQuery,
  rangeWords,
  ruleMeaning,
  shareWords,
  signedDelta,
  statTiles,
  statusLine,
  toolsChart,
  unusualAlert,
  waitText,
  widerRange,
  windowsWords,
} from './insights-view';

/** What Insights says about the kit's world (design/pages/insights.html, BLUEPRINT §9), word for word. */

const tiles = () => statTiles({ insights: insights(), key: '24h', pageQuery: '', now: NOW });
const tile = (id: string) => tiles().find((x) => x.id === id)!;

describe('the filter bar', () => {
  it("counts the range's decided calls against the previous range", () => {
    expect(statusLine(insights(), false, '24h')).toBe('1,284 calls · compared with the previous 24 hours');
    expect(statusLine(insights({ unfilteredCalls: 1284, kpis: { ...insights().kpis, current: { ...insights().kpis.current, calls: 312 } } }), true, '7d')).toBe(
      '312 of 1,284 calls · compared with the previous 7 days',
    );
    expect(statusLine(firstRun(), false, '24h')).toBe('0 calls');
  });

  it('asks for the range up to now and knows the next longer one', () => {
    expect(rangeQuery('1h', NOW)).toEqual({ from: new Date(NOW - 3_600_000).toISOString(), to: new Date(NOW).toISOString() });
    expect(rangeQuery('30d', NOW).from).toBe(new Date(NOW - 30 * 86_400_000).toISOString());
    expect(widerRange('1h')).toBe('24h');
    expect(widerRange('7d')).toBe('30d');
    expect(widerRange('30d')).toBeNull();
    expect(rangeWords('1h')).toEqual({ span: 'last hour', previous: 'previous hour' });
  });
});

describe('links into Activity', () => {
  it('narrows the page query by the number behind it, with the same range', () => {
    expect(activityLink('', '24h', ['tool:sql_query', 'tool:logs_search'])).toEqual({
      route: '/activity',
      query: { q: 'tool:sql_query tool:logs_search', range: '24h' },
    });
    // A tag the query has isn't repeated; free text stays last.
    expect(activityLink('client:codex sync', '7d', ['client:codex', 'decision:denied']).query).toEqual({ q: 'client:codex decision:denied sync', range: '7d' });
    expect(activityLink('', '24h', [], { view: 'traces' }).query).toEqual({ range: '24h', view: 'traces' });
  });
});

describe('KPI tiles', () => {
  it('six tiles in the kit order with values and units', () => {
    expect(tiles().map((x) => [x.label, x.value, x.unit])).toEqual([
      ['Calls', '1,284', null],
      ['Approved by you', '46', null],
      ['Denied by policy', '17', null],
      ['Errors', '8', null],
      ['Gateway overhead p95', '31', 'ms'],
      ['Agent tasks', '61', null],
    ]);
  });

  it('each change in its own words, coloured by what it means', () => {
    expect(tiles().map((x) => [x.delta?.text, x.delta?.dir, x.delta?.sentiment])).toEqual([
      ['+12% vs previous 24 hours', 'up', 'neutral'],
      ['6 more than previous 24 hours', 'up', 'neutral'],
      ['+5 vs previous 24 hours', 'up', 'bad'],
      ['3 fewer than previous 24 hours', 'down', 'good'],
      ['−4 ms vs previous 24 hours', 'down', 'good'],
      ['7 more than previous 24 hours', 'up', 'neutral'],
    ]);
    expect(countDelta(5, 5, 'previous hour', 'bad', 'good')).toEqual({ dir: 'flat', sentiment: null, icon: 'minus', text: 'No change vs previous hour' });
    expect(callsDelta(12, 0, 'previous hour').text).toBe('+12 vs previous hour');
    expect(signedDelta(-1284, '', 'previous 7 days', 'bad', 'good').text).toBe('−1,284 vs previous 7 days');
  });

  it('without a previous range there is no change to show', () => {
    const t = statTiles({ insights: insights({ kpis: { current: insights().kpis.current, previous: null } }), key: '24h', pageQuery: '', now: NOW });
    expect(t.every((x) => x.delta === null)).toBe(true);
  });

  it('calls: the hourly sparkline in words, and what still waits', () => {
    const calls = tile('calls');
    expect(calls.chart!.label).toBe('Calls per hour: quiet overnight, peak of 166 at 11:00');
    expect(calls.chart!.points.startsWith('0,18.3 10,14.2 20,16.7')).toBe(true);
    expect(calls.meta.map((m) => m.text + (m.sr ?? ''))).toEqual(['Plus 3 waiting for approval · ', 'Review 3 waiting requests']);
    expect(calls.meta[1].link).toEqual({ route: '/approvals' });
  });

  it('approved, denied, errors, tasks: the line under the change', () => {
    expect(tile('approved').meta).toEqual([{ text: 'Median wait 47 s' }]);
    expect(tile('denied').meta).toEqual([{ text: 'See by rule', section: 'in-denied-title' }]);
    expect(tile('errors').meta).toEqual([{ text: 'See by connection', section: 'in-errors-title' }]);
    expect(tile('tasks').meta[0].text).toBe('21 calls per task on average · ');
    expect(tile('tasks').meta[1]).toEqual({ text: 'View traces', link: { route: '/activity', query: { range: '24h', view: 'traces' } } });
  });

  it('overhead: the meter against the 50 ms target, judged in words too', () => {
    expect(tile('overhead').meter).toEqual({
      label: 'Target ≤ 50 ms · p50 9 ms',
      value: '31 / 50 ms',
      fraction: 0.62,
      level: 'ok',
      icon: null,
      now: 31,
      max: 50,
      valueText: '31 ms of the 50 ms target, within target',
    });
    const slow = statTiles({ insights: insights({ kpis: { current: { ...insights().kpis.current, gatewayP95Ms: 140 }, previous: null } }), key: '24h', pageQuery: '', now: NOW });
    const m = slow.find((x) => x.id === 'overhead')!.meter!;
    expect([m.level, m.icon, m.fraction, m.now, m.valueText]).toEqual(['critical', 'circle-alert', 1, 50, '140 ms of the 50 ms target, over target']);
  });

  it('a range without completed calls says so instead of a number', () => {
    const t = statTiles({ insights: firstRun(), key: '1h', pageQuery: '', now: NOW });
    const o = t.find((x) => x.id === 'overhead')!;
    expect([o.value, o.unit, o.meter, o.meta]).toEqual(['None', null, null, [{ text: 'No completed calls in the last hour' }]]);
    expect(t.find((x) => x.id === 'calls')!.chart).toBeNull();
    expect(t.find((x) => x.id === 'tasks')!.meta).toEqual([]);
  });
});

describe('Unusual activity', () => {
  const alerts = () => insights().unusual.map((u) => unusualAlert(u, '24h', NOW));

  it('the three detectors in the kit words', () => {
    const [ddl, spike, slow] = alerts();
    expect([ddl.tone, ddl.icon, plain(ddl.title)]).toEqual(['danger', 'shield-x', 'An agent tried to change prod']);
    expect(plain(ddl.body)).toBe('Claude Desktop sent TRUNCATE and then DROP TABLE to prod-replica prod within a minute, in one task. Both were denied.');
    expect(ddl.body.filter((p) => p.kind === 'code').map((p) => p.text)).toEqual(['TRUNCATE', 'DROP TABLE', 'prod-replica']);
    expect(ddl.body.some((p) => p.kind === 'env' && p.text === 'prod')).toBe(true);
    expect(ddl.action).toEqual({
      label: 'Open the task',
      sr: ' Investigate failed order sync on prod',
      link: { route: '/activity', query: { q: 'trace:b81e44', range: '24h', view: 'traces' } },
    });

    expect([spike.tone, spike.icon, plain(spike.title)]).toEqual(['warning', 'trending-up', 'sql_query on prod-replica prod is 4× its 7-day average']);
    expect(plain(spike.body)).toBe('65 calls against 16 a day, mostly from Claude Desktop on incident-prod between 09:00 and 12:00.');
    expect(spike.action).toEqual({ label: 'View 65 calls', sr: ' of sql_query on prod-replica', link: { route: '/activity', query: { q: 'tool:sql_query connection:prod-replica', range: '24h' } } });

    expect([slow.icon, plain(slow.title)]).toEqual(['timer', 'cache-redis answers 45× slower than usual']);
    expect(plain(slow.body)).toBe('1.8 s at p95 since 13:30, against 40 ms over the last 7 days. Calls that wait longer than 2.0 s time out.');
    expect(slow.action!.label).toBe('View 31 calls');
    expect(slow.action!.link.query).toEqual({ q: 'connection:cache-redis', range: '24h' });
  });

  it('a task of one statement, a slower one, a partly denied one', () => {
    const one: InsightUnusual = { kind: 'prod-ddl', text: '', statements: ['DROP TABLE'], withinS: 300, denied: 1, connection: 'prod-replica', env: 'prod', client: null };
    expect(plain(unusualAlert(one, '24h', NOW).body)).toBe('An agent sent DROP TABLE to prod-replica prod within 5 min, in one task. It was denied.');
    const three: InsightUnusual = { ...one, statements: ['TRUNCATE', 'ALTER TABLE', 'DROP TABLE'], denied: 2, withinS: 20 };
    expect(plain(unusualAlert(three, '24h', NOW).body)).toContain('sent TRUNCATE, ALTER TABLE and then DROP TABLE to');
    expect(plain(unusualAlert(three, '24h', NOW).body)).toContain('2 of them were denied.');
    expect(unusualAlert(one, '24h', NOW).action).toBeNull();
  });

  it("a detector this UI doesn't know shows the core's sentence", () => {
    const a = unusualAlert({ kind: 'token-burst', text: 'codex-laptop made 400 calls in 5 minutes.' }, '24h', NOW);
    expect([a.tone, plain(a.title), a.body, a.action]).toEqual(['warning', 'codex-laptop made 400 calls in 5 minutes.', [], null]);
  });

  it('since yesterday names the day', () => {
    const u: InsightUnusual = { kind: 'slow-connection', text: '', connection: 'cache-redis', p95Ms: 900, usualMs: 30, factor: 30, since: new Date(2026, 9, 21, 22, 10).toISOString(), calls: 3 };
    expect(plain(unusualAlert(u, '24h', NOW).body)).toBe('900 ms at p95 since 22:10 yesterday, against 30 ms over the last 7 days.');
  });
});

describe('Calls over time', () => {
  const chart = () => callsChart(insights().callsOverTime, '24h', NOW);

  it('the summary and the accessible conclusion are the kit text', () => {
    expect(chart().description).toBe('Traffic peaked at 166 calls at 11:00, and most denials came between 09:00 and 11:00.');
    expect(chart().aria).toBe(
      'Calls per hour, last 24 hours. 1,284 calls finished: 1,247 allowed or approved, 34 denied, rejected or failed, 3 expired; 3 more are waiting for approval. Peak of 166 calls at 11:00. Most denials came between 09:00 and 11:00.',
    );
  });

  it('bars on a 200 scale with four gridlines and five axis labels, the last one now', () => {
    const c = chart();
    expect(c.bars).toHaveLength(24);
    expect(c.bars[0].segments).toEqual([
      { key: 'success', v: 0.475 },
      { key: 'danger', v: 0.005 },
    ]);
    expect(c.bars[23].segments.map((s) => s.key)).toEqual(['success', 'warning', 'danger']);
    expect(c.bars[11].segments).toEqual([]);
    expect(c.gridlines).toEqual([
      { v: 0.25, label: '50' },
      { v: 0.5, label: '100' },
      { v: 0.75, label: '150' },
      { v: 1, label: '200' },
    ]);
    expect(c.axis.map((a) => a.label)).toEqual(['15:00', '21:00', '03:00', '09:00', 'Now']);
    expect(c.legend.map((l) => [l.label, l.value])).toEqual([
      ['Allowed or approved', 1247],
      ['Waiting for approval', 3],
      ['Denied, rejected or failed', 34],
      ['Expired', 3],
    ]);
    expect(c.rows[20]).toEqual({ label: '11:00', counts: { success: 160, warning: 0, danger: 5, neutral: 1 }, total: 166 });
  });

  it('days for a month, no denials said plainly, nothing at all', () => {
    const start = new Date(2026, 8, 23).getTime();
    const days = { from: new Date(NOW - 30 * 86_400_000).toISOString(), to: new Date(NOW).toISOString(), stepHours: 24, buckets: [0, 1, 2].map((i) => bucket(i, { allowed: 10 * (i + 1) }, start, 24 * 3_600_000)) };
    const c = callsChart(days, '30d', NOW);
    expect(c.unit).toBe('day');
    expect(c.rows.map((r) => r.label)).toEqual(['Sep 23', 'Sep 24', 'Sep 25']);
    expect(c.description).toBe('Traffic peaked at 30 calls at Sep 25, and nothing was denied or failed.');
    expect(callsChart({ ...days, buckets: [] }, '30d', NOW).description).toBe('No calls in the last 30 days.');
  });
});

describe('Top tools', () => {
  it('six bars, the rest as one, the two leaders in the summary and the footer', () => {
    const t = toolsChart(insights().topTools, '24h', '');
    expect(t.rows.map((r) => [r.label, r.value, r.mono])).toEqual([
      ['sql_query', '512', true],
      ['logs_search', '341', true],
      ['k8s_get', '150', true],
      ['ssh_run', '98', true],
      ['http_request', '61', true],
      ['sql_execute', '47', true],
      ['5 other tools', '75', false],
    ]);
    expect(t.rows[1].segments[0].v).toBeCloseTo(0.666, 3);
    expect(t.rows[6].segments).toEqual([{ key: 'neutral', v: 75 / 512 }]);
    expect(plain(t.description)).toBe('sql_query and logs_search make up two thirds of all calls.');
    expect(t.aria).toBe(
      'Top tools by calls, last 24 hours: sql_query leads with 512, then logs_search 341, k8s_get 150, ssh_run 98, http_request 61 and sql_execute 47; 5 other tools made 75 calls.',
    );
    expect(t.table).toHaveLength(11);
    expect(plain(t.footer!.parts)).toBe('View sql_query and logs_search calls in Activity');
    expect(t.footer!.link.query).toEqual({ q: 'tool:sql_query tool:logs_search', range: '24h' });
  });

  it('seven tools are all drawn; one tool made every call', () => {
    const seven = insights().topTools.slice(0, 7);
    expect(toolsChart(seven, '24h', '').rows.map((r) => r.label)).toContain('redis_get');
    const one = toolsChart([{ name: 'sql_query', count: 3 }], '1h', 'client:codex');
    expect(plain(one.description)).toBe('sql_query made every call.');
    expect(one.footer!.link.query).toEqual({ q: 'client:codex tool:sql_query', range: '1h' });
  });
});

describe('By client', () => {
  it('outcomes per client, who leads, how often they fail, and where a clean client went', () => {
    const c = clientsChart(insights().byClient, clientCards(), false, '24h', '');
    expect(c.rows.map((r) => [r.label, r.client, r.value])).toEqual([
      ['Codex', 'codex', '772'],
      ['Claude Desktop', 'claude-desktop', '493'],
      ['claude.ai', 'claude-ai', '19'],
    ]);
    const [codex] = c.rows;
    expect(codex.segments.map((s) => [s.key, Math.round(s.v * 10_000) / 10_000])).toEqual([
      ['success', 0.9715],
      ['danger', 0.0259],
      ['neutral', 0.0026],
    ]);
    expect(plain(c.description)).toBe(
      'Codex makes 60% of calls; Codex and Claude Desktop are denied or fail on fewer than 3% of them. claude.ai: 19 calls from 08:41 to 12:08, all allowed, to stage-db through stage-readonly.',
    );
    expect(c.aria).toBe(
      'Calls by client, last 24 hours: Codex 772 (60%), of them 20 denied, rejected or failed and 2 expired; Claude Desktop 493 (38%), of them 14 denied, rejected or failed and 1 expired; claude.ai 19 (1%), all allowed or approved.',
    );
    expect(c.table[0]).toEqual({ name: 'Codex', ok: 750, bad: 20, expired: 2, total: 772 });
    expect(c.links.map((l) => [l.label, l.link.query?.['q']])).toEqual([
      ['View Codex calls', 'client:codex'],
      ['View Claude Desktop calls', 'client:claude-desktop'],
      ['View claude.ai calls', 'client:claude-ai'],
    ]);
  });

  it('clients of this computer without calls get the empty state, only without filters', () => {
    const c = clientsChart(insights().byClient, clientCards(), false, '24h', '');
    expect(c.idle).toEqual({ names: ['Cursor', 'Antigravity'], title: 'No calls from Cursor or Antigravity', text: 'Cursor is detected but not connected, and Antigravity is not installed.' });
    expect(clientsChart(insights().byClient, clientCards(), true, '24h', 'client:codex').idle).toBeNull();
    expect(clientsChart(insights().byClient, null, false, '24h', '').idle).toBeNull();
    const restart: ClientCard[] = [{ ...clientCards()[2], status: 'needs-restart' }];
    expect(clientsChart(insights().byClient, restart, false, '24h', '').idle!.text).toBe('Cursor needs a restart.');
  });
});

describe('Approvals', () => {
  const view = () => approvalsView(insights().approvals, WINDOWS, '24h', '', NOW);

  it('the kit summary, with the approval windows of the policies', () => {
    expect(view().description).toBe('You approved 4 of 5 requests, most within a minute; 3 expired before you answered (5 min on dev, 10 min on stage and prod).');
    expect(approvalsView(insights().approvals, null, '24h', '', NOW).description).toBe('You approved 4 of 5 requests, most within a minute; 3 expired before you answered.');
  });

  it('outcome bar, legend with shares, the wait facts and the link', () => {
    const v = view();
    expect(v.decided).toBe(58);
    expect(v.segments.map((s) => [s.key, Math.round(s.v * 10_000) / 10_000])).toEqual([
      ['success', 0.7931],
      ['danger', 0.1552],
      ['neutral', 0.0517],
    ]);
    expect(v.legend.map((l) => [l.label, l.value, l.share])).toEqual([
      ['Approved', 46, '79%'],
      ['Rejected', 9, '16%'],
      ['Expired', 3, '5%'],
    ]);
    expect(v.aria).toBe('58 approval requests decided in the last 24 hours: 46 approved (79%), 9 rejected (16%), 3 expired (5%).');
    expect([v.withinMinute, v.p90]).toEqual(['38 of 55', '1 min 40 s']);
    expect(v.link.query).toEqual({ q: 'decision:approved decision:rejected decision:expired', range: '24h' });
  });

  it('the slowest decisions open their call', () => {
    expect(view().slowest.map((s) => [s.tool, s.env, s.look.text, s.wait, s.sr, s.link?.fragment])).toEqual([
      ['k8s_rollout_restart', 'dev', 'Approved', '3 min 05 s', ' on dev-cluster at 09:47, open call', 'event-1102'],
      ['sql_execute', 'dev', 'Approved', '2 min 31 s', ' on dev-db at 11:20, open call', 'event-1187'],
      ['http_request', 'stage', 'Rejected', '2 min 12 s', ' on billing-api at 10:06, open call', 'event-1129'],
      ['ssh_run', 'dev', 'Approved', '1 min 48 s', ' on dev-vm at 16:41 yesterday, open call', 'event-431'],
    ]);
  });

  it('slow decisions, all approved, none at all', () => {
    const a = insights().approvals;
    const slow = { ...a, waitHistogram: [{ toS: 60, count: 10 }, { toS: null, count: 45 }], medianWaitS: 130 };
    expect(approvalsView(slow, WINDOWS, '24h', '', NOW).description).toContain('with a median wait of 2 min 10 s;');
    const all = { ...a, approved: 12, rejected: 0, expired: 0, approvedShare: 1 };
    expect(approvalsView(all, WINDOWS, '24h', '', NOW).description).toBe('You approved all 12 requests, most within a minute.');
    const none = firstRun().approvals;
    const v = approvalsView(none, WINDOWS, '1h', '', NOW);
    expect([v.decided, v.description, v.withinMinute, v.p90]).toEqual([0, 'No approval requests were decided in the last hour.', null, null]);
  });

  it('approval windows from the policies, grouped as a sentence says them', () => {
    const p = (m: number) => ({ sections: { approvals: { timeoutMinutes: m } } });
    expect(approvalWindows({ dev: p(5), stage: p(10), prod: p(10) })).toEqual(WINDOWS);
    expect(approvalWindows({ dev: { sections: {} } })).toBeNull();
    expect(approvalWindows(null)).toBeNull();
    expect(windowsWords({ dev: 5, stage: 10, prod: 10 })).toBe('5 min on dev, 10 min on stage and prod');
    expect(windowsWords({ dev: 5, stage: 5, prod: 10 })).toBe('5 min on dev and stage, 10 min on prod');
  });
});

describe('Denied by rule', () => {
  it('one row per rule with its example; the summary says what the leader stops', () => {
    const d = deniedView(insights().deniedByRule, '24h', '');
    expect(plain(d.description)).toBe('Most denials were calls to tools that are off; 4 were DDL on prod.');
    expect(d.total).toBe(17);
    expect(d.rows.map((r) => [r.rule, r.label, r.count, r.title])).toEqual([
      ['mode.off', 'mode: off', '6', 'ssh_exec on dev-vm'],
      ['prod.sql.block_ddl', 'prod.sql.block_ddl', '4', 'DROP TABLE on prod-replica'],
      ['sql.read_only_tool', 'sql.read_only_tool', '3', 'DELETE on dev-db'],
      ['http.block_private_ranges', 'http.block_private_ranges', '2', 'GET 169.254.169.254 on billing-api'],
      ['k8s.namespace_allowlist', 'k8s.namespace_allowlist', '2', 'kube-system on dev-cluster'],
    ]);
    // The kit's link for a tool that is off: rule:mode.off.
    expect(d.rows[0].link!.query).toEqual({ q: 'decision:denied rule:mode.off', range: '24h' });
    expect(d.rows[0].example).toEqual([
      { kind: 'code', text: 'ssh_exec' },
      { kind: 'text', text: ' on ' },
      { kind: 'mono', text: 'dev-vm' },
      { kind: 'text', text: ' ' },
      { kind: 'env', text: 'dev' },
    ]);
    expect(d.rows[0].sr).toBe(': view 6 denied calls in Activity');
    expect(d.rows[1].link!.query).toEqual({ q: 'decision:denied rule:prod.sql.block_ddl', range: '24h' });
    expect(d.link.query).toEqual({ q: 'decision:denied', range: '24h' });
  });

  it('an unknown or tied leader is named by its id; one rule; none', () => {
    const tie = deniedView([{ rule: 'mode.off', count: 2, example: null }, { rule: 'dev.k8s.verb-not-allowed', count: 2, example: null }], '24h', '');
    expect(plain(tie.description)).toBe('mode: off denied the most: 2 of 4.');
    expect(plain(deniedView([{ rule: 'dev.k8s.verb-not-allowed', count: 3, example: null }], '24h', '').description)).toBe('Every denial came from dev.k8s.verb-not-allowed.');
    expect(plain(deniedView([], '7d', '').description)).toBe('The policies denied no call in the last 7 days.');
    expect(deniedView([{ rule: 'mode: off', count: 1, example: null }], '24h', '').rows[0].link).toBeNull();
    expect(ruleMeaning('prod.core.integrity')).toBe('calls under a policy changed outside MCP Gateway');
    expect(ruleMeaning('dev.k8s.namespace-not-allowed')).toBe('calls outside the allowed namespaces');
    expect(ruleMeaning('dev.http.write-approval')).toBeNull();
  });
});

describe('Latency and errors', () => {
  it('latency: gateway vs resource on one 2.0 s axis, the kit summary and conclusion', () => {
    const l = latencyView(insights().latency.byConnection, '24h', '');
    expect(l.axis).toBe('2.0 s');
    expect(l.rows[0]).toEqual({
      label: 'cache-redis',
      mono: true,
      segments: [
        { key: 'neutral', v: 0.009 },
        { key: 'brand', v: 0.9 },
      ],
      value: '18 ms + 1.8 s',
    });
    expect(l.rows.map((r) => r.value).slice(-1)).toEqual(['21 ms + 27 ms']);
    expect(plain(l.description)).toBe('The gateway adds 18 to 38 ms at p95 everywhere; slow calls come from the resources, led by cache-redis and prod-logs.');
    expect(l.aria).toBe(
      'p95 time per call by connection, last 24 hours, gateway then resource: cache-redis 18 ms plus 1.8 s, prod-logs 38 ms plus 1.1 s, dev-vm 35 ms plus 820 ms, billing-api 27 ms plus 480 ms, app-logs 33 ms plus 240 ms, dev-cluster 22 ms plus 96 ms, prod-replica 29 ms plus 62 ms, stage-db 25 ms plus 58 ms, dev-db 24 ms plus 41 ms, orders-mongo 21 ms plus 27 ms.',
    );
    expect(l.link.query).toEqual({ q: 'duration:>1s', range: '24h' });
    const fast = latencyView([{ connection: 'dev-db', env: 'dev', calls: 4, gatewayP50: 5, gatewayP95: 12, resourceP50: 3, resourceP95: 9 }], '1h', '');
    expect(plain(fast.description)).toBe('The gateway adds 12 ms at p95; the resources answer within 9 ms at p95.');
    expect(fast.axis).toBe('25 ms');
  });

  it('errors: each connection with its most common error and its health now', () => {
    const e = errorsView(insights().errorsByConnection, healthNow(), '24h', '');
    expect(plain(e.description)).toBe("All 8 errors come from two connections: cache-redis times out and billing-api can't be reached.");
    expect(e.rows.map((r) => [r.connection, r.env, r.count, r.message, r.health.text, r.health.tone])).toEqual([
      ['cache-redis', 'dev', '5', 'ETIMEDOUT after 2.0 s', 'Slow · 1.8 s', 'warning'],
      ['billing-api', 'stage', '3', 'ECONNREFUSED billing.stage.acme.dev:443', 'Unreachable', 'danger'],
    ]);
    expect(e.rows[0].link!.query).toEqual({ q: 'decision:error connection:cache-redis', range: '24h' });
    expect(e.rows[0].sr).toBe(': view 5 errors in Activity');
    const unknown = errorsView(insights().errorsByConnection, new Map(), '24h', '');
    expect(unknown.rows[0].health.text).toBe('Not checked yet');
  });

  it('one connection, many, none', () => {
    const one = errorsView([{ connection: 'dev-db', env: 'dev', count: 1, kind: 'RESOURCE_ERROR', message: 'syntax error' }], new Map(), '1h', '');
    expect(plain(one.description)).toBe('All 1 error come from dev-db: it returns errors.');
    const many = errorsView(
      [
        { connection: 'a', env: 'dev', count: 5, kind: 'OUTCOME_UNKNOWN', message: null },
        { connection: 'b', env: 'dev', count: 2, kind: null, message: null },
        { connection: 'c', env: 'dev', count: 1, kind: 'DENIED', message: null },
      ],
      new Map(),
      '24h',
      '',
    );
    expect(plain(many.description)).toBe('8 errors come from three connections, most from a, which drops calls mid-way.');
    expect(plain(errorsView([], new Map(), '30d', '').description)).toBe('No call failed in the last 30 days.');
  });
});

describe('what the page shows', () => {
  it('first run until the audit holds a call, no calls in an empty range, else the numbers', () => {
    expect(bodyOf(firstRun())).toBe('first-run');
    expect(bodyOf({ ...firstRun(), firstCallAt: '2026-09-25T08:40:00Z' })).toBe('no-calls');
    expect(bodyOf(insights())).toBe('data');
    const waiting = { ...firstRun(), firstCallAt: '2026-09-25T08:40:00Z', kpis: { current: { ...firstRun().kpis.current, pending: 1 }, previous: null } };
    expect(bodyOf(waiting)).toBe('data');
  });
});

describe('words and numbers', () => {
  it('shares, fractions and percentages', () => {
    expect(shareWords(853 / 1284)).toBe('two thirds');
    expect(shareWords(0.5)).toBe('half');
    expect(shareWords(0.99)).toBe('almost all');
    expect(shareWords(0.26)).toBe('a quarter');
    expect(fractionWords(46 / 58)).toBe('4 of 5');
    expect(fractionWords(0.5)).toBe('1 of 2');
    expect(fractionWords(1)).toBe('all');
    expect(fractionWords(0)).toBe('none');
    expect(percent(19, 1284)).toBe('1%');
    expect(percent(1, 1284)).toBe('<1%');
    expect(percent(0, 0)).toBe('0%');
  });

  it('times as the kit writes them', () => {
    expect([latency(18), latency(1800), latency(2000), latency(820), latency(null)]).toEqual(['18 ms', '1.8 s', '2.0 s', '820 ms', '']);
    expect([waitText(185), waitText(100), waitText(47), waitText(3725), waitText(-1)]).toEqual(['3 min 05 s', '1 min 40 s', '47 s', '1 h 2 min', '']);
    expect([niceMs(1818), niceMs(21), niceMs(45), niceMs(3)]).toEqual([2000, 25, 50, 10]);
    expect([countWords(2, 'connection'), countWords(12, 'connection'), countWords(1, 'error')]).toEqual(['two connections', '12 connections', 'one error']);
  });
});
