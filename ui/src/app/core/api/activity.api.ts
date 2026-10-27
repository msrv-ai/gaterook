import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Decision, Env, Iso, JsonValue } from './types';

/** One call of the events table (and of the `audit.call` SSE event). */
export interface ActivityEvent {
  readonly id: number;
  readonly callId: string;
  readonly at: Iso;
  readonly phase: 'received' | 'resolved' | 'completed';
  readonly decision: Decision;
  readonly tool: string;
  readonly connection: string | null;
  readonly env: Env | null;
  readonly client: string | null;
  readonly endpoint: string;
  readonly tokenLabel: string | null;
  readonly durationMs: number | null;
  readonly rule: string | null;
  readonly approvalId: string | null;
  readonly traceId: string | null;
  readonly errorKind: string | null;
  readonly errorMessage: string | null;
}

export interface EventsPage {
  readonly events: ActivityEvent[];
  readonly nextCursor: string | null;
}

export interface HistogramBucket {
  readonly from: Iso;
  readonly to: Iso;
  readonly decisions: { readonly [decision: string]: number };
  readonly total: number;
}

export interface Histogram {
  readonly from: Iso;
  readonly to: Iso;
  readonly stepHours: number;
  readonly buckets: HistogramBucket[];
}

export interface FacetValue {
  readonly name: string;
  readonly count: number;
}

/** Decision, Tool, Client, Endpoint, Connection, Env → values with counts. */
export type Facets = { readonly [field: string]: FacetValue[] };

/** One stored audit row of a call (received / resolved / completed), arguments already masked. */
export interface AuditRow {
  readonly id: number;
  readonly phase: string;
  readonly at: Iso;
  readonly decision: string;
  readonly classification: string | null;
  readonly rule: string | null;
  readonly approvalId: string | null;
  readonly approvalDelivery: string | null;
  readonly protocolVersion: string | null;
  readonly ingress: string | null;
  readonly principalKind: string | null;
  readonly client: string | null;
  readonly clientInfo: { readonly [key: string]: JsonValue } | null;
  readonly tokenLabel: string | null;
  readonly args: { readonly [key: string]: JsonValue } | null;
  readonly argsSha256: string | null;
  readonly durationMs: number | null;
  readonly gatewayMs: number | null;
  readonly resourceMs: number | null;
  readonly approvalWaitMs: number | null;
  readonly resultBytes: number | null;
  readonly resultSha256: string | null;
  readonly resultPreview: string | null;
  readonly maskedCount: number | null;
  readonly truncated: boolean | null;
  readonly errorKind: string | null;
  readonly errorMessage: string | null;
  /**
   * Non-secret facts of the request (the `received` row only): `runbook`, `template`, `task`, and — from step API —
   * `policy`, the decision's chain ({@link PolicyFacet}).
   */
  readonly facets: { readonly [key: string]: JsonValue } | null;
  readonly actionSha256: string | null;
  readonly executedSha256: string | null;
  readonly outcomeUnknown: boolean | null;
  readonly decidedVia: string | null;
  readonly commented: boolean;
  readonly traceId: string | null;
  readonly spanId: string | null;
  readonly rowSha256: string;
}

/**
 * The decision's chain a call's `received` row keeps as facet `policy` (step API; older rows don't have it): the
 * pair's mode, how the connector classified the call, the rule that matched, what was decided, the rate limits with
 * the calls counted at that moment, and the limits the call ran under. The connector's and the policy's words are
 * masked like the arguments.
 */
export interface PolicyFacet {
  /** `READ_ONLY`, `APPROVAL`, `OFF`… of the tool × connection pair. */
  readonly mode: string | null;
  readonly classification?: { readonly kind: string; readonly reason?: string };
  readonly rule?: { readonly id: string | null; readonly reason?: string };
  /** `ALLOW`, `APPROVAL`, `DENY`. */
  readonly outcome: string;
  readonly rate: { readonly perToken: number; readonly tokenCalls: number; readonly perTool: number; readonly toolCalls: number };
  readonly limits?: {
    readonly maxRows: number;
    readonly statementTimeoutMs?: number;
    readonly toolTimeoutMs?: number;
    readonly maxResponseKb: number;
  };
}

/** `GET /api/activity/events/{id}`: the event-detail drawer. */
export interface EventDetail {
  readonly event: ActivityEvent;
  readonly rows: AuditRow[];
}

export interface TraceRow {
  readonly traceId: string;
  readonly title: string | null;
  readonly titleSource: 'meta' | 'note' | 'client' | null;
  readonly client: string | null;
  readonly endpoint: string | null;
  readonly startedAt: Iso;
  readonly endedAt: Iso;
  readonly calls: number;
  readonly status: 'in-progress' | 'waiting' | 'ended';
  readonly decisions: { readonly [decision: string]: JsonValue };
  readonly waitedForPeopleMs: number;
}

export interface TracePhase {
  readonly name: string;
  readonly from: Iso;
  readonly to: Iso;
  readonly calls: number;
  readonly decisions: { readonly [decision: string]: JsonValue };
  readonly summary: string;
}

export interface TraceDetail {
  readonly trace: TraceRow;
  readonly phases: TracePhase[];
  readonly calls: ActivityEvent[];
}

/** A Setup change: Time | What | Change | By (always "You" on the desktop) | Reason. */
export interface SetupChange {
  readonly id: number;
  readonly at: Iso;
  readonly what: string;
  readonly subject: string;
  readonly from: string | null;
  readonly to: string | null;
  readonly by: string;
  readonly reason: string | null;
  readonly env: Env | null;
  readonly endpoint: string | null;
  readonly connection: string | null;
  readonly details: { readonly [key: string]: JsonValue } | null;
}

export interface SavedView {
  readonly name: string;
  readonly q: string;
  readonly view: string | null;
}

export interface ActivityRange {
  readonly q?: string | null;
  readonly from?: Iso | null;
  readonly to?: Iso | null;
}

@Injectable({ providedIn: 'root' })
export class ActivityApi {
  private readonly api = inject(ApiClient);

  /** `q`: `field:value` (decision tool client endpoint connection env token rule runbook trace duration). */
  events(range: ActivityRange & { readonly cursor?: string | null; readonly limit?: number } = {}): Promise<EventsPage> {
    return this.api.get('/api/activity/events', { ...range });
  }

  histogram(range: ActivityRange = {}): Promise<Histogram> {
    return this.api.get('/api/activity/histogram', { ...range });
  }

  facets(range: ActivityRange = {}): Promise<Facets> {
    return this.api.get('/api/activity/facets', { ...range });
  }

  event(id: number): Promise<EventDetail> {
    return this.api.get(ApiClient.path`/api/activity/events/${id}`);
  }

  /**
   * Tasks newest first. With `q`, those with a matching call; with `from`/`to` (step API), those with a call in the
   * range — both over the whole audit, so a page is full while older tasks match. A core before step API ignores the
   * range.
   */
  traces(
    q?: string | null,
    cursor?: string | null,
    limit = 20,
    range: { readonly from?: Iso | null; readonly to?: Iso | null } = {},
  ): Promise<{ readonly traces: TraceRow[]; readonly nextCursor: string | null }> {
    return this.api.get('/api/activity/traces', { q, cursor, limit, from: range.from, to: range.to });
  }

  trace(traceId: string): Promise<TraceDetail> {
    return this.api.get(ApiClient.path`/api/activity/traces/${traceId}`);
  }

  changes(q?: string | null, cursor?: string | null, limit = 100): Promise<{ readonly changes: SetupChange[]; readonly nextCursor: string | null }> {
    return this.api.get('/api/activity/changes', { q, cursor, limit });
  }

  /** `saveToken` from DesktopBridge.saveFile('audit-export') (`FileGrants.Purpose`); `target: 'siem'` needs an audit-export destination. */
  export(request: ActivityRange & {
    readonly target: 'file' | 'siem';
    readonly format: 'csv' | 'jsonl';
    readonly saveToken?: string | null;
    readonly destinationId?: string | null;
  }): Promise<{ readonly path: string; readonly rows: number; readonly bytes: number; readonly format: string }> {
    return this.api.post('/api/activity/export', request);
  }

  views(): Promise<{ readonly views: SavedView[]; readonly version: number }> {
    return this.api.get('/api/activity/views');
  }

  saveViews(views: SavedView[], version: number): Promise<{ readonly views: SavedView[]; readonly version: number }> {
    return this.api.put('/api/activity/views', { views, version });
  }
}
