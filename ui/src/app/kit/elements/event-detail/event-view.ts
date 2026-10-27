import { AuditRow, EventDetail, PolicyFacet, TraceDetail, TracePhase } from '../../../core/api/activity.api';
import { Env, JsonValue } from '../../../core/api/types';
import { duration, elapsed, formatBytes } from '../../../core/time/time-format';
import { clientName } from '../shared/client-mark';
import { decisionLook } from '../shared/dictionaries';
import { TextPart } from '../shared/literals';

/**
 * What the event-detail element says about one logged call, from the audit rows the core wrote for it
 * (`GET /api/activity/events/{id}`: received, resolved, completed). Pure functions, tested against the kit's wording
 * (design/elements/event-detail.html).
 */

const t = (text: string): TextPart => ({ text, code: false });
const c = (text: string): TextPart => ({ text, code: true });

export interface CallRows {
  readonly received: AuditRow | null;
  readonly resolved: AuditRow | null;
  readonly completed: AuditRow | null;
  /** The newest row: what the call came to. */
  readonly last: AuditRow | null;
}

export function callRows(d: EventDetail): CallRows {
  const by = (phase: string) => d.rows.find((r) => r.phase === phase) ?? null;
  return { received: by('received'), resolved: by('resolved'), completed: by('completed'), last: d.rows.at(-1) ?? null };
}

/** "Codex", "Claude Desktop"; unknown clients as their id, or "An agent". */
export function clientLabel(client: string | null | undefined): string {
  return client ? clientName(client) : 'An agent';
}

/** The decision badge's icon and words (BLUEPRINT §7). */
export const decisionBadge = decisionLook;

/** "47.0 s · 47 s waiting for approval", "41 ms", "1.8 s, timed out", "Not run: denied in the gateway". */
export function durationText(d: EventDetail): string {
  const { completed, resolved } = callRows(d);
  const wait = resolved?.approvalWaitMs ?? completed?.approvalWaitMs ?? null;
  const total = completed?.durationMs ?? d.event.durationMs;
  switch (d.event.decision) {
    case 'denied':
      return 'Not run: denied in the gateway';
    case 'expired':
      return wait != null ? `${elapsed(wait)} waiting for approval, then expired` : 'Expired before a decision';
    case 'rejected':
      return wait != null ? `${elapsed(wait)} waiting for approval, then rejected` : 'Rejected';
    case 'pending':
      return 'Waiting for approval';
  }
  const base = total != null ? precise(total) : '';
  if (d.event.decision === 'error') {
    const timedOut = (completed?.errorKind ?? d.event.errorKind) === 'TIMEOUT';
    return base ? `${base}, ${timedOut ? 'timed out' : 'failed'}` : timedOut ? 'Timed out' : 'Failed';
  }
  if (wait != null && wait > 0) return `${base} · ${elapsed(wait)} waiting for approval`;
  return base;
}

/** A call's length to a tenth of a second under a minute ("47.0 s", "2.0 s"), as the kit's summaries write it. */
export function precise(ms: number): string {
  return ms >= 1000 && ms < 60_000 ? `${(ms / 1000).toFixed(1)} s` : duration(ms);
}

/**
 * "Codex" + "codex-laptop · short-lived, issued on the fly", or a named token with its prefix. The kinds are the
 * core's (`platform.Caller.Kind`, lower case in the audit row): `local_token`, `bridge`, `oauth`, `playground`.
 */
export function tokenParts(row: AuditRow | null, fallback: string | null): TextPart[] {
  const label = row?.tokenLabel ?? fallback;
  const kind = row?.principalKind ?? null;
  if (!label) return [t('Unknown')];
  if (kind === 'bridge' || kind === 'grant') return [c(label), t(' · short-lived, issued on the fly')];
  if (kind === 'oauth') return [c(label), t(' · signed in through the browser')];
  if (kind === 'local_token' || kind === 'token') return [c(label), t(' · named token')];
  if (kind === 'playground') return [c(label), t(' · this app’s Playground')];
  return [c(label)];
}

/** The arguments as stored (masked), pretty JSON for the code block, and how many values the policy masked. */
export function argumentsJson(row: AuditRow | null): { readonly text: string; readonly masked: number } | null {
  if (!row?.args) return null;
  const text = JSON.stringify(row.args, null, 2);
  return { text, masked: (text.match(/‹masked[^›]*›/g) ?? []).length };
}

export function maskedCaption(masked: number): string {
  if (!masked) return 'No values masked.';
  return `${masked} ${masked === 1 ? 'value' : 'values'} masked by policy. The originals never reach the audit log.`;
}

/** One rule of the Policy decision timeline. */
export interface PolicyStep {
  /** null: an empty marker (not checked). */
  readonly tone: 'info' | 'success' | 'warning' | 'danger' | null;
  readonly icon: string | null;
  readonly title: TextPart[];
  readonly matched: boolean;
  readonly meta: TextPart[];
}

/** `dev.sql.write-approval` → `sql`; `core.prod-isolation` → null. */
export function ruleSection(rule: string | null): string | null {
  const parts = (rule ?? '').split('.');
  return parts.length >= 3 && parts[0] !== 'core' ? parts[1] : null;
}

const CLASSIFIED: Record<string, string> = { read: 'reads only', mutation: 'changes data', deny: 'refused by the classifier' };

/**
 * The decision's chain the call's request row keeps (facet `policy`, step API), or null for a row written before it
 * or without one.
 */
export function policyFacet(row: AuditRow | null | undefined): PolicyFacet | null {
  const p = row?.facets?.['policy'];
  if (!p || typeof p !== 'object' || Array.isArray(p)) return null;
  const f = p as unknown as PolicyFacet;
  return typeof f.outcome === 'string' && f.rate && typeof f.rate === 'object' ? f : null;
}

/** "9 of 120 calls per minute for codex-laptop · row limit 1,000": what the limits were when the call came. */
function limitsMeta(p: PolicyFacet, token: string | null): TextPart[] {
  const out: TextPart[] = [t(`${p.rate.tokenCalls.toLocaleString('en-US')} of ${p.rate.perToken.toLocaleString('en-US')} calls per minute`)];
  if (token) out.push(t(' for '), c(token));
  if (p.limits?.maxRows) out.push(t(` · row limit ${p.limits.maxRows.toLocaleString('en-US')}`));
  return out;
}

/**
 * The rules the call went through, in order, as far as the audit records them: the classification, the rule that
 * decided (marked "Matched rule"), the rate limits (from the chain the request row keeps, step API), what was skipped
 * after a denial, and the decision in words. The connector's and the policy's own words come from that chain too.
 */
export function policySteps(d: EventDetail): PolicyStep[] {
  const e = d.event;
  const { received } = callRows(d);
  const chain = policyFacet(received);
  const rule = received?.rule ?? e.rule;
  const classification = received?.classification ?? null;
  const decision = received?.decision ?? e.decision;
  const section = ruleSection(rule) ?? 'tool';
  const who = clientLabel(e.client);
  const steps: PolicyStep[] = [];

  if (classification) {
    const why = chain?.classification?.reason;
    steps.push({
      tone: 'info',
      icon: 'scan-search',
      title: [c(section), t(`: ${CLASSIFIED[classification] ?? `classified as ${classification}`}`)],
      matched: false,
      meta: why ? [t(why)] : [t('Tool '), c(e.tool), ...(e.connection ? [t(' on '), c(e.connection)] : [])],
    });
  }

  if (!rule && decision === 'allowed') {
    steps.push({
      tone: 'info',
      icon: 'eye',
      title: [c('mode'), t(': read only')],
      matched: true,
      meta: [t('Endpoint '), c(e.endpoint), t(', tool '), c(e.tool)],
    });
  } else if (rule) {
    const denied = decision === 'denied';
    const why = chain?.rule?.id === rule ? chain.rule.reason : undefined;
    steps.push({
      tone: denied ? 'danger' : 'warning',
      icon: denied ? 'shield-x' : 'user-check',
      // Kit: "prod.sql.block_ddl: DDL is blocked on prod" — the policy's own words when the audit kept them.
      title: why ? [c(rule), t(`: ${why}`)] : [c(rule)],
      matched: true,
      meta: ruleMeta(rule, e.endpoint, e.env),
    });
  }

  if (chain && decision !== 'denied') {
    steps.push({ tone: 'success', icon: 'check', title: [c('limits'), t(': within rate limit')], matched: false, meta: limitsMeta(chain, e.tokenLabel) });
  }

  if (decision === 'denied') {
    steps.push({ tone: null, icon: null, title: [c('limits'), t(': not checked')], matched: false, meta: [t('Skipped: an earlier rule denied the call')] });
    steps.push({
      tone: 'danger',
      icon: 'shield-x',
      title: [t('Decision: denied by policy')],
      matched: false,
      meta: [t(`No approval request was created. ${who} got an error naming the rule.`)],
    });
  } else if (decision === 'allowed' || (decision === 'error' && !e.approvalId)) {
    steps.push({
      tone: 'success',
      icon: 'check',
      title: [t('Decision: allowed')],
      matched: false,
      meta: [t(e.decision === 'error' ? 'The failure came later, from the resource' : 'Ran at once, nobody was asked')],
    });
  } else {
    const id = received?.approvalId ?? e.approvalId;
    steps.push({
      tone: 'warning',
      icon: 'hourglass',
      title: [t('Decision: ask for approval')],
      matched: false,
      meta: id ? [t('Approval request '), c(id), t(' sent to you')] : [t('Sent to you')],
    });
  }
  return steps;
}

function ruleMeta(rule: string, endpoint: string, env: Env | null): TextPart[] {
  const section = ruleSection(rule);
  if (rule.endsWith('.write-approval')) return [t(`Changes data: every change waits for a person${env ? ` on ${env}` : ''}.`)];
  if (rule.endsWith('.approval-mode')) return [t('The tool is set to Approval on '), c(endpoint)];
  if (rule.endsWith('.endpoint.read-only')) return [c(endpoint), t(" is read only: it doesn't run anything that changes data")];
  if (rule === 'core.prod-isolation') return [t('Prod connections are used only through prod endpoints')];
  if (rule === 'core.integrity') return [t('The policy was changed outside MCP Gateway: review it in Policies')];
  if (rule.startsWith('core.')) return [t('A rule of MCP Gateway itself; no policy can change it')];
  const envName = rule.split('.')[0];
  return [t(`Set in Policies for ${envName}${section ? `, ${section} section` : ''}. No approval can override it.`)];
}

/** The fallback approval path from the audit alone (no `ApprovalDetail`): requested → decided → executed. */
export interface PathStep {
  readonly tone: 'neutral' | 'info' | 'success' | 'warning' | 'danger';
  readonly icon: string;
  readonly title: string;
  readonly meta: TextPart[];
  readonly at: string;
}

/** `approval.decided_via` (`approvals.ApprovalService.CHANNELS`, plus `client` and `core` for rejections). */
const VIA: Record<string, string> = {
  app: 'In the app',
  'desktop-notification': 'From the desktop notification',
  desktop: 'From the desktop notification',
  slack: 'From Slack',
  telegram: 'From Telegram',
  timeout: 'No answer',
};

/** Who rejected: a person, the agent's client (it declined the request) or the core (its connection or endpoint went). */
function rejectedBy(via: string | null): string {
  if (via === 'client') return 'Declined by the client';
  if (via === 'core') return 'Rejected by MCP Gateway';
  return 'Rejected by you';
}

export function approvalPath(d: EventDetail): PathStep[] {
  const { received, resolved, completed } = callRows(d);
  const who = clientLabel(d.event.client);
  const steps: PathStep[] = [];
  if (received) {
    steps.push({ tone: 'neutral', icon: 'send', title: `Requested by ${who}`, meta: [t('Token '), c(received.tokenLabel ?? 'unknown')], at: received.at });
  }
  if (resolved) {
    const after = resolved.approvalWaitMs != null ? `after ${elapsed(resolved.approvalWaitMs)}` : '';
    // The title says who rejected for `client` and `core`; anything unknown is shown as the core wrote it.
    const via = VIA[resolved.decidedVia ?? ''] ?? (resolved.decidedVia === 'client' || resolved.decidedVia === 'core' ? '' : (resolved.decidedVia ?? ''));
    const meta = [via, after].filter(Boolean).join(' · ');
    switch (resolved.decision) {
      case 'approved':
        steps.push({ tone: 'success', icon: 'user-check', title: 'Approved by you', meta: [t(meta)], at: resolved.at });
        break;
      case 'rejected':
        steps.push({ tone: 'danger', icon: 'user-x', title: rejectedBy(resolved.decidedVia ?? null), meta: [t(meta)], at: resolved.at });
        break;
      case 'expired':
        steps.push({ tone: 'neutral', icon: 'timer-off', title: 'Expired, denied automatically', meta: [t(`No decision ${after}`.trim())], at: resolved.at });
        break;
    }
  }
  if (completed) {
    const ok = completed.decision !== 'error';
    steps.push({
      tone: ok ? 'success' : 'danger',
      icon: ok ? 'circle-check' : 'circle-alert',
      title: ok ? `Executed in ${duration(completed.resourceMs ?? completed.durationMs)}` : `Failed after ${duration(completed.durationMs)}`,
      meta: [t(ok ? `${completed.resultPreview ? `${completed.resultPreview} · ` : ''}result returned to ${who}` : (completed.errorMessage ?? 'The resource returned an error'))],
      at: completed.at,
    });
  }
  return steps;
}

/** The approved action against the executed one. */
export function actionMatch(d: EventDetail): { readonly approved: string | null; readonly state: 'match' | 'mismatch' | 'not-run' } {
  const { resolved, completed } = callRows(d);
  const approved = resolved?.actionSha256 ?? completed?.actionSha256 ?? null;
  const executed = completed?.executedSha256 ?? null;
  if (!executed) return { approved, state: 'not-run' };
  return { approved, state: approved && executed === approved ? 'match' : 'mismatch' };
}

/** The Result's status line. */
export function resultStatus(d: EventDetail): TextPart[] {
  const { completed } = callRows(d);
  const conn = d.event.connection;
  switch (d.event.decision) {
    case 'denied':
      return conn ? [t('Not executed: nothing reached '), c(conn)] : [t('Not executed')];
    case 'rejected':
      return [t('Not executed: you rejected it')];
    case 'expired':
      return [t('Not executed: nobody decided in time')];
    case 'pending':
      return [t('Waiting for approval')];
    case 'error': {
      const kind = completed?.errorKind ?? d.event.errorKind;
      return [t(kind === 'TIMEOUT' ? `Failed: timeout after ${durationText(d).replace(/, timed out$/, '')}` : 'Failed')];
    }
    default:
      return [t(completed?.resultPreview ? `Executed · ${completed.resultPreview}` : 'Executed')];
  }
}

/** "312 B", "164 B (the error returned to Codex)". */
export function sizeText(d: EventDetail): string | null {
  const { completed } = callRows(d);
  const bytes = completed?.resultBytes;
  if (bytes == null) return null;
  const isError = d.event.decision === 'error';
  return `${formatBytes(bytes)}${isError ? ` (the error returned to ${clientLabel(d.event.client)})` : ''}${completed?.truncated ? ', truncated' : ''}`;
}

/** One row of the Timing waterfall: where the time of the call went. */
export interface TimingRow {
  readonly label: TextPart[];
  readonly kind: 'gateway' | 'approval' | 'resource';
  readonly ms: number;
  readonly start: number;
  readonly len: number;
}

/** The waterfall: gateway, the wait for a person (if any), the resource; fractions of the whole. */
export function timing(d: EventDetail): { readonly summary: TextPart[]; readonly rows: TimingRow[]; readonly kinds: TimingRow['kind'][] } | null {
  const { resolved, completed } = callRows(d);
  const gateway = completed?.gatewayMs ?? null;
  const resource = completed?.resourceMs ?? null;
  const wait = resolved?.approvalWaitMs ?? completed?.approvalWaitMs ?? null;
  if (gateway == null && resource == null && wait == null) return null;
  const parts: { readonly label: TextPart[]; readonly kind: TimingRow['kind']; readonly ms: number }[] = [];
  if (gateway != null) parts.push({ label: [t('Authenticate, check policy, mask and audit')], kind: 'gateway', ms: gateway });
  if (wait != null) parts.push({ label: [t('Waiting for approval')], kind: 'approval', ms: wait });
  if (resource != null) parts.push({ label: d.event.connection ? [t('Execute on '), c(d.event.connection)] : [t('Execute')], kind: 'resource', ms: resource });
  const total = parts.reduce((n, p) => n + p.ms, 0) || 1;
  let at = 0;
  const rows = parts.map((p) => {
    const len = Math.max(p.ms / total, 0.0001);
    const row = { ...p, start: Math.min(at, 0.9999), len: Math.min(len, 1 - Math.min(at, 0.9999)) };
    at += p.ms / total;
    return row;
  });
  const conn = d.event.connection;
  const summary: TextPart[] = [t(`${precise(total)} in total`)];
  const bits: TextPart[][] = [];
  if (wait != null) bits.push([t(`${elapsed(wait)} waiting for you`)]);
  if (resource != null) bits.push(conn ? [t(`${duration(resource)} in `), c(conn)] : [t(`${duration(resource)} in the resource`)]);
  if (gateway != null) bits.push([t(`${duration(gateway)} in the gateway`)]);
  if (bits.length === 1 && gateway != null) summary.push(t(', all in the gateway'));
  else bits.forEach((b, i) => summary.push(t(i === 0 ? ': ' : ', '), ...b));
  const kinds = (['gateway', 'resource', 'approval'] as const).filter((k) => rows.some((r) => r.kind === k));
  return { summary, rows, kinds };
}

/** The stored record, pretty, and its size. */
export function rawJson(d: EventDetail): { readonly text: string; readonly size: string } {
  const text = JSON.stringify({ event: d.event, rows: d.rows }, null, 2);
  return { text, size: formatBytes(new TextEncoder().encode(text).length) };
}

/** The error alert under the summary: what happened and what to do. */
export function errorAlert(d: EventDetail): { readonly title: TextPart[]; readonly body: string } | null {
  if (d.event.decision !== 'error') return null;
  const { completed } = callRows(d);
  const kind = completed?.errorKind ?? d.event.errorKind;
  const message = completed?.errorMessage ?? d.event.errorMessage ?? 'The resource returned an error.';
  const conn = d.event.connection;
  const who = clientLabel(d.event.client);
  const title: TextPart[] =
    kind === 'TIMEOUT' && conn ? [c(conn), t(` did not answer within ${precise(completed?.resourceMs ?? completed?.durationMs ?? 0)}`)] : conn ? [c(conn), t(' returned an error')] : [t('The call failed')];
  return { title, body: `${message.replace(/\.?$/, '.')} ${who} got the error.` };
}

/** Decisions of a trace or phase: "21 allowed, 1 approved, 1 error". */
export function decisionCounts(decisions: { readonly [d: string]: JsonValue }): { readonly decision: string; readonly count: number }[] {
  const order = ['allowed', 'approved', 'pending', 'denied', 'rejected', 'expired', 'error'];
  return Object.entries(decisions)
    .filter(([, n]) => typeof n === 'number' && n > 0)
    .map(([decision, n]) => ({ decision, count: n as number }))
    .sort((a, b) => order.indexOf(a.decision) - order.indexOf(b.decision));
}

export function decisionsText(decisions: { readonly [d: string]: JsonValue }): string {
  return decisionCounts(decisions).map((d) => `${d.count} ${d.decision}`).join(', ') || 'None';
}

/** The marker of a trace phase: its first word decides (Explore, Change, Wait, Verify). */
export function phaseLook(p: TracePhase): { readonly tone: 'info' | 'warning' | 'neutral' | 'success' | 'danger'; readonly icon: string } {
  const decisions = decisionCounts(p.decisions).map((d) => d.decision);
  if (decisions.includes('denied') || decisions.includes('error')) {
    const name = p.name.toLowerCase();
    if (!name.startsWith('explore')) return { tone: 'danger', icon: 'circle-alert' };
  }
  const name = p.name.toLowerCase();
  if (name.startsWith('explore')) return { tone: 'info', icon: 'compass' };
  if (name.startsWith('change')) return { tone: 'warning', icon: 'pencil' };
  if (name.startsWith('wait')) return { tone: 'neutral', icon: 'clock' };
  if (name.startsWith('verify')) return { tone: 'success', icon: 'circle-check' };
  return { tone: 'neutral', icon: 'circle-dot' };
}

/** "Trace 4f2a9c… · 23 calls · 18 min · Codex on endpoint dev". */
export function traceLine(d: TraceDetail): { readonly short: string; readonly calls: string; readonly span: string; readonly who: string } {
  const tr = d.trace;
  const ms = Date.parse(tr.endedAt) - Date.parse(tr.startedAt);
  return {
    short: `${tr.traceId.slice(0, 6)}…`,
    calls: `${tr.calls} ${tr.calls === 1 ? 'call' : 'calls'}`,
    span: duration(ms),
    who: clientLabel(tr.client),
  };
}

/**
 * What a task waits for (Activity › Traces, "Waiting for people"): the tools of its calls waiting for approval, in
 * the order they were asked, and the request to review first.
 */
export function waitingTools(d: TraceDetail): {
  readonly tools: string[];
  readonly review: { readonly approvalId: string; readonly tool: string; readonly connection: string | null } | null;
} {
  const pending = d.calls.filter((c) => c.decision === 'pending').sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  const tools = [...new Set(pending.map((c) => c.tool))];
  const first = pending.find((c) => c.approvalId);
  return { tools, review: first?.approvalId ? { approvalId: first.approvalId, tool: first.tool, connection: first.connection } : null };
}

const OUTLIER_WORDS: Record<string, string> = {
  denied: 'Open the denied call',
  rejected: 'Open the rejected call',
  expired: 'Open the expired call',
  error: 'Open the failed call',
};

/**
 * The call a phase stopped on (kit: "Open the DROP TABLE call" under Clean up): the latest call within the phase's
 * time that was denied, rejected, expired or failed, as a link to its event. Null when every call of the phase went
 * through, or the trace's calls aren't at hand.
 */
export function phaseOutlier(d: TraceDetail, p: TracePhase): { readonly id: number; readonly text: string } | null {
  const from = Date.parse(p.from);
  const to = Date.parse(p.to);
  const hit = d.calls
    .filter((c) => OUTLIER_WORDS[c.decision] && Date.parse(c.at) >= from && Date.parse(c.at) <= to)
    .sort((a, b) => Date.parse(b.at) - Date.parse(a.at))[0];
  return hit ? { id: hit.id, text: OUTLIER_WORDS[hit.decision] } : null;
}

/** Where a trace's title comes from: anything the agent reported is unverified. */
export function titleSource(d: TraceDetail): string | null {
  const who = clientLabel(d.trace.client);
  switch (d.trace.titleSource) {
    case 'meta':
    case 'client':
      return `Task name as reported by ${who}, unverified`;
    case 'note':
      return `Task name from ${who}'s note, unverified`;
    default:
      return null;
  }
}
