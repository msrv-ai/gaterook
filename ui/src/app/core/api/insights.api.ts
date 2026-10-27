import { Injectable, inject } from '@angular/core';
import { Histogram } from './activity.api';
import { ApiClient } from './api-client';
import { Env, Iso } from './types';

/**
 * `GET /api/insights?q&from&to` (ARCHITECTURE.md §6 insights, step K5). Until K5 is merged the route answers 404
 * without a problem body and the page says "not available yet" (never made-up numbers).
 *
 * The contract the Insights page renders (U13): counts and percentiles over the audit (`tool_call`) of the range,
 * narrowed by `q` (Activity's query language), plus the same numbers for the previous range of the same length for
 * the changes. The UI writes every sentence and builds every "View in Activity" query from these facts (Activity's
 * `q` syntax: `tool:sql_query connection:prod-replica`), so the core sends numbers and names, not prose — except
 * `InsightUnusual.text`, the plain sentence of a detector this UI doesn't know yet.
 */

/** Calls per audit decision: `{allowed: 1201, approved: 46, denied: 17, …}`; a missing key is 0. */
export type DecisionCounts = { readonly [decision: string]: number };

/** The headline numbers of one range (the KPI tiles). */
export interface InsightsTotals {
  /** Calls with a decision in the range (waiting ones are not counted: they have none yet). */
  readonly calls: number;
  /** Calls waiting for approval at the end of the range. */
  readonly pending: number;
  /** Mutations a person approved (decision `approved`). */
  readonly approved: number;
  /** Calls the policy denied (decision `denied`). */
  readonly denied: number;
  /** Calls that failed in the gateway or the resource (decision `error`). */
  readonly errors: number;
  /** Agent tasks (traces) with calls in the range. */
  readonly tasks: number;
  /** p50 and p95 of the gateway's own time per call (`tool_call.gateway_ms`), null without completed calls. */
  readonly gatewayP50Ms: number | null;
  readonly gatewayP95Ms: number | null;
}

/** One client's calls (`tool_call.client`: `codex`, `claude-desktop`, `claude-ai`, …). */
export interface InsightClient {
  readonly client: string;
  /** Decided calls. */
  readonly count: number;
  readonly decisions: DecisionCounts;
  /** Endpoints and connections it called, most calls first. */
  readonly endpoints: readonly string[];
  readonly connections: readonly string[];
  readonly firstAt: Iso | null;
  readonly lastAt: Iso | null;
}

/** One of the approval requests that waited longest for a person. */
export interface InsightSlowDecision {
  readonly approvalId: string;
  /** The Activity event of the call (`/activity#event-<id>`); null when the audit row is gone (retention). */
  readonly eventId: number | null;
  readonly tool: string;
  readonly connection: string | null;
  readonly env: Env | null;
  readonly requestedAt: Iso;
  readonly status: 'approved' | 'rejected';
  readonly waitS: number;
}

/** Approval requests decided (or expired) in the range. */
export interface InsightsApprovals {
  readonly approved: number;
  readonly rejected: number;
  readonly expired: number;
  /** Over requests a person decided (approved and rejected): expired ones never got an answer. */
  readonly medianWaitS: number | null;
  readonly p90WaitS: number | null;
  /** approved ÷ (approved + rejected + expired), null without requests. */
  readonly approvedShare: number | null;
  /**
   * How long a person took, by upper bound in seconds, ascending; the last bucket's `toS` is null (everything
   * slower): `[{toS: 60, count: 38}, {toS: 120, count: 11}, {toS: 300, count: 4}, {toS: null, count: 2}]`.
   */
  readonly waitHistogram: readonly { readonly toS: number | null; readonly count: number }[];
  /** The longest waits, longest first (up to 5). */
  readonly slowest: readonly InsightSlowDecision[];
}

/** Calls one policy rule denied (`tool_call.rule_id`), with one example of what it stopped. */
export interface InsightDeniedRule {
  readonly rule: string;
  readonly count: number;
  /**
   * The literal that names what was stopped — a tool (`ssh_exec`), a statement (`DROP TABLE`), a request
   * (`GET 169.254.169.254`), a namespace (`kube-system`) — and where; null when the rows carry no example.
   */
  readonly example: { readonly literal: string; readonly connection: string | null; readonly env: Env | null } | null;
}

/** Where the time of a call goes on one connection (`gateway_ms` vs `resource_ms`), approval waits excluded. */
export interface InsightLatency {
  readonly connection: string;
  readonly env: Env | null;
  readonly calls: number;
  readonly gatewayP50: number;
  readonly gatewayP95: number;
  readonly resourceP50: number;
  readonly resourceP95: number;
}

/** Failed calls on one connection and their most common error. */
export interface InsightErrors {
  readonly connection: string;
  readonly env: Env | null;
  readonly count: number;
  /** `ConnectorException.Kind` of the most common error: `TIMEOUT`, `RESOURCE_UNAVAILABLE`, `RESOURCE_ERROR`, … */
  readonly kind: string | null;
  /** Its message, masked as the audit stores it ("ETIMEDOUT after 2.0 s"). */
  readonly message: string | null;
}

/**
 * One finding of the Unusual activity detectors (K5), compared with the last 7 days. The known kinds carry their
 * facts; a kind this UI doesn't know is shown by its `text`.
 * - `prod-ddl` — DDL denied on prod within one task: `traceId`, `traceTitle`, `client`, `connection`, `env`,
 *   `statements` (in order, `["TRUNCATE", "DROP TABLE"]`), `withinS`, `calls`, `denied`;
 * - `tool-spike` — a tool on a connection at ≥ 4× its 7-day daily average (≥ 20 calls): `tool`, `connection`,
 *   `env`, `calls`, `dailyAverage`, `factor`, and where most came from (`client`, `endpoint`, `from`–`to`);
 * - `slow-connection` — a connection's p95 ≥ 10× its 7-day median: `connection`, `env`, `p95Ms`, `usualMs`,
 *   `factor`, `since`, `timeoutMs` (the tool timeout of its policy), `calls`.
 */
export interface InsightUnusual {
  readonly kind: 'prod-ddl' | 'tool-spike' | 'slow-connection' | string;
  /** The finding as one plain sentence (for a kind this UI doesn't know, and for the core's log). */
  readonly text: string;
  readonly traceId?: string | null;
  readonly traceTitle?: string | null;
  readonly client?: string | null;
  readonly endpoint?: string | null;
  readonly tool?: string | null;
  readonly connection?: string | null;
  readonly env?: Env | null;
  readonly statements?: readonly string[];
  readonly withinS?: number | null;
  readonly calls?: number | null;
  readonly denied?: number | null;
  readonly dailyAverage?: number | null;
  readonly factor?: number | null;
  readonly from?: Iso | null;
  readonly to?: Iso | null;
  readonly p95Ms?: number | null;
  readonly usualMs?: number | null;
  readonly since?: Iso | null;
  readonly timeoutMs?: number | null;
}

export interface Insights {
  /** The range the numbers cover, as the core understood the request. */
  readonly from: Iso;
  readonly to: Iso;
  /** When the audit's first call was; null — no call has reached the gateway yet (the page's first-run state). */
  readonly firstCallAt: Iso | null;
  /** Decided calls in the range without `q` (the filter bar's "312 of 1,284 calls"). */
  readonly unfilteredCalls: number;
  /** This range and the previous one of the same length (null when the audit doesn't reach back that far). */
  readonly kpis: { readonly current: InsightsTotals; readonly previous: InsightsTotals | null };
  /** Calls per bucket and decision, waiting ones included — the shape of `GET /api/activity/histogram`. */
  readonly callsOverTime: Histogram;
  /** Every tool with decided calls, most first. */
  readonly topTools: readonly { readonly name: string; readonly count: number }[];
  /** Every client with decided calls, most first. */
  readonly byClient: readonly InsightClient[];
  /** Decided calls by decision (the sum is `kpis.current.calls`). */
  readonly decisions: DecisionCounts;
  readonly approvals: InsightsApprovals;
  /** Most calls first. */
  readonly deniedByRule: readonly InsightDeniedRule[];
  /** Connections with completed calls, slowest p95 (gateway + resource) first. */
  readonly latency: { readonly byConnection: readonly InsightLatency[] };
  /** Most errors first. */
  readonly errorsByConnection: readonly InsightErrors[];
  readonly unusual: readonly InsightUnusual[];
}

/** The range and filter of one Insights read. */
export interface InsightsQuery {
  readonly q?: string | null;
  readonly from?: Iso | null;
  readonly to?: Iso | null;
}

@Injectable({ providedIn: 'root' })
export class InsightsApi {
  private readonly api = inject(ApiClient);

  get(query: InsightsQuery = {}): Promise<Insights> {
    return this.api.get('/api/insights', { q: query.q, from: query.from, to: query.to });
  }
}
