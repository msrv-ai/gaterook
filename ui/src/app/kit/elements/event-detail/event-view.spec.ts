import { EventDetail } from '../../../core/api/activity.api';
import { JsonObject } from '../../../core/api/types';
import { DEMO_EVENTS, DEMO_TRACE } from '../../../dev/fixtures-events';
import {
  actionMatch,
  approvalPath,
  argumentsJson,
  decisionsText,
  durationText,
  errorAlert,
  maskedCaption,
  phaseLook,
  policyFacet,
  policySteps,
  rawJson,
  resultStatus,
  ruleSection,
  sizeText,
  timing,
  titleSource,
  tokenParts,
  traceLine,
} from './event-view';

const words = (parts: readonly { text: string }[]) => parts.map((p) => p.text).join('');

describe('event-detail view (design/elements/event-detail.html)', () => {
  it('says how long a call took, in the kit words', () => {
    expect(durationText(DEMO_EVENTS.approved)).toBe('47.0 s · 47 s waiting for approval');
    expect(durationText(DEMO_EVENTS.allowed)).toBe('38 ms');
    expect(durationText(DEMO_EVENTS.error)).toBe('2.0 s, timed out');
    expect(durationText(DEMO_EVENTS.denied)).toBe('Not run: denied in the gateway');
    expect(durationText(DEMO_EVENTS.expired)).toBe('10 min waiting for approval, then expired');
  });

  it('names a token by its label only, never a value', () => {
    expect(words(tokenParts(DEMO_EVENTS.approved.rows[0], null))).toBe('codex-laptop · short-lived, issued on the fly');
  });

  it('reads the core’s principal kinds (platform.Caller.Kind, lower case)', () => {
    const row = (principalKind: string) => ({ ...DEMO_EVENTS.approved.rows[0], principalKind, tokenLabel: 'cursor-laptop' });
    expect(words(tokenParts(row('local_token'), null))).toBe('cursor-laptop · named token');
    expect(words(tokenParts(row('bridge'), null))).toBe('cursor-laptop · short-lived, issued on the fly');
    expect(words(tokenParts(row('oauth'), null))).toBe('cursor-laptop · signed in through the browser');
    expect(words(tokenParts(row('playground'), null))).toBe('cursor-laptop · this app’s Playground');
  });

  it('says who decided as the core records it (decided_via), and never "by you" for the client or the core', () => {
    const via = (decision: string, decidedVia: string) => ({
      ...DEMO_EVENTS.approved,
      rows: DEMO_EVENTS.approved.rows
        .filter((r) => r.phase !== 'completed')
        .map((r) => (r.phase === 'resolved' ? { ...r, decision, decidedVia } : r)),
    });
    expect(words(approvalPath(via('approved', 'desktop-notification'))[1].meta)).toBe('From the desktop notification · after 47 s');
    expect(approvalPath(via('rejected', 'client'))[1].title).toBe('Declined by the client');
    expect(approvalPath(via('rejected', 'core'))[1].title).toBe('Rejected by MCP Gateway');
    expect(words(approvalPath(via('rejected', 'core'))[1].meta)).toBe('after 47 s');
    expect(approvalPath(via('rejected', 'app'))[1].title).toBe('Rejected by you');
  });

  it('shows the stored, masked arguments and counts the masks', () => {
    const a = argumentsJson(DEMO_EVENTS.approved.rows[0])!;
    expect(a.masked).toBe(2);
    expect(a.text).toContain('"customer_email": "‹masked›"');
    expect(maskedCaption(2)).toBe('2 values masked by policy. The originals never reach the audit log.');
    expect(maskedCaption(0)).toBe('No values masked.');
    expect(rawJson(DEMO_EVENTS.approved).text).not.toMatch(/@example|\+49/);
  });

  it("tells the chain the audit kept with the request (step API): the policy's words and the rate limits", () => {
    const withChain = (d: EventDetail, policy: JsonObject): EventDetail => ({
      ...d,
      rows: d.rows.map((r) => (r.phase === 'received' ? { ...r, facets: { ...(r.facets ?? {}), policy } } : r)),
    });
    const denied = policySteps(
      withChain(DEMO_EVENTS.denied, {
        mode: 'APPROVAL',
        classification: { kind: 'DENY', reason: 'DDL: DROP TABLE on order_sync_queue_old' },
        rule: { id: 'prod.sql.block_ddl', reason: 'DDL is blocked on prod' },
        outcome: 'DENY',
        rate: { perToken: 120, tokenCalls: 4, perTool: 30, toolCalls: 1 },
      }),
    );
    expect(words(denied.find((s) => s.matched)!.title)).toBe('prod.sql.block_ddl: DDL is blocked on prod');
    // A denial is decided before the limits: they stay "not checked".
    expect(denied.filter((s) => words(s.title).startsWith('limits'))).toHaveLength(1);
    const allowed = policySteps(
      withChain(DEMO_EVENTS.allowed, {
        mode: 'READ_ONLY',
        classification: { kind: 'READ', reason: 'One SELECT on orders' },
        outcome: 'ALLOW',
        rate: { perToken: 120, tokenCalls: 9, perTool: 30, toolCalls: 2 },
        limits: { maxRows: 1000, statementTimeoutMs: 5000, maxResponseKb: 512 },
      }),
    );
    const limits = allowed.find((s) => words(s.title) === 'limits: within rate limit')!;
    expect(limits.tone).toBe('success');
    expect(words(limits.meta)).toMatch(/^9 of 120 calls per minute( for \S+)? · row limit 1,000$/);
    expect(allowed.some((s) => words(s.meta) === 'One SELECT on orders')).toBe(DEMO_EVENTS.allowed.rows.some((r) => r.phase === 'received' && !!r.classification));
    // A row written before step API: no limits step is made up.
    expect(policySteps(DEMO_EVENTS.allowed).some((s) => words(s.title).startsWith('limits'))).toBe(false);
    expect(policyFacet(DEMO_EVENTS.allowed.rows[0])).toBeNull();
  });

  it('marks the rule that decided, and what a denial skipped', () => {
    const denied = policySteps(DEMO_EVENTS.denied);
    const matched = denied.filter((s) => s.matched);
    expect(matched.map((s) => words(s.title))).toEqual(['prod.sql.block_ddl']);
    expect(words(matched[0].meta)).toBe('Set in Policies for prod, sql section. No approval can override it.');
    expect(denied.find((s) => s.tone === null)?.title.map((p) => p.text).join('')).toBe('limits: not checked');
    expect(words(denied.at(-1)!.title)).toBe('Decision: denied by policy');
    expect(words(policySteps(DEMO_EVENTS.allowed).find((s) => s.matched)!.title)).toBe('mode: read only');
    expect(words(policySteps(DEMO_EVENTS.approved).at(-1)!.meta)).toBe('Approval request apr_5d21 sent to you');
    expect(ruleSection('dev.sql.write-approval')).toBe('sql');
    expect(ruleSection('core.prod-isolation')).toBeNull();
  });

  it('walks the approval from the audit when Approvals is not at hand', () => {
    expect(approvalPath(DEMO_EVENTS.approved).map((s) => s.title)).toEqual(['Requested by Codex', 'Approved by you', 'Executed in 38 ms']);
    expect(approvalPath(DEMO_EVENTS.expired).map((s) => s.title)).toEqual(['Requested by Codex', 'Expired, denied automatically']);
    expect(words(approvalPath(DEMO_EVENTS.approved)[1].meta)).toBe('From the desktop notification · after 47 s');
  });

  it('checks that what ran is byte for byte what was approved', () => {
    expect(actionMatch(DEMO_EVENTS.approved).state).toBe('match');
    const tampered = {
      ...DEMO_EVENTS.approved,
      rows: DEMO_EVENTS.approved.rows.map((r) => (r.phase === 'completed' ? { ...r, executedSha256: 'other' } : r)),
    };
    expect(actionMatch(tampered).state).toBe('mismatch');
    expect(actionMatch(DEMO_EVENTS.expired).state).toBe('not-run');
  });

  it('result status and size', () => {
    expect(words(resultStatus(DEMO_EVENTS.denied))).toBe('Not executed: nothing reached prod-replica');
    expect(words(resultStatus(DEMO_EVENTS.error))).toBe('Failed: timeout after 2.0 s');
    expect(words(resultStatus(DEMO_EVENTS.approved))).toBe('Executed · 1 row updated');
    expect(sizeText(DEMO_EVENTS.error)).toBe('118 B (the error returned to Codex)');
    expect(sizeText(DEMO_EVENTS.denied)).toBeNull();
  });

  it('splits the time into gateway, people and resource', () => {
    const t = timing(DEMO_EVENTS.approved)!;
    expect(words(t.summary)).toBe('47.0 s in total: 47 s waiting for you, 38 ms in dev-db, 8 ms in the gateway');
    expect(t.rows.map((r) => r.kind)).toEqual(['gateway', 'approval', 'resource']);
    expect(t.rows.every((r) => r.start >= 0 && r.start + r.len <= 1.0001)).toBe(true);
    expect(t.kinds).toEqual(['gateway', 'resource', 'approval']);
    expect(timing(DEMO_EVENTS.denied)).toBeNull();
  });

  it('puts what failed under the summary', () => {
    const a = errorAlert(DEMO_EVENTS.error)!;
    expect(words(a.title)).toBe('cache-redis did not answer within 2.0 s');
    expect(a.body).toBe('cache.dev.internal:6379 did not answer within 2.0 s. Codex got the error.');
    expect(errorAlert(DEMO_EVENTS.allowed)).toBeNull();
  });

  it('describes a trace and marks the agent-reported name as unverified', () => {
    expect(traceLine(DEMO_TRACE)).toEqual({ short: '4f2a9c…', calls: '23 calls', span: '18 min', who: 'Codex' });
    expect(titleSource(DEMO_TRACE)).toBe('Task name as reported by Codex, unverified');
    expect(decisionsText(DEMO_TRACE.trace.decisions)).toBe('21 allowed, 1 approved, 1 error');
    expect(DEMO_TRACE.phases.map((p) => phaseLook(p).icon)).toEqual(['compass', 'pencil', 'clock', 'circle-check']);
  });
});
