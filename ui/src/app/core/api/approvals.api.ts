import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Env, Iso, JsonValue } from './types';

export type ApprovalStatus = 'pending' | 'running' | 'completed' | 'rejected' | 'expired' | 'failed' | 'cancelled';
export type CommentPolicy = 'optional' | 'recommended' | 'required';

/** One row of Approvals › Pending / History and of Overview's "Waiting for you" (admin.approvals.ApprovalDtos.Item). */
export interface ApprovalItem {
  readonly id: string;
  readonly tool: string;
  readonly connection: string | null;
  readonly env: Env;
  readonly endpoint: string;
  /** The client family Activity shows: `codex`, `claude-desktop`, `playground`. */
  readonly client: string | null;
  /** The trusted label of who asked: token name or grant label (`codex-laptop`). */
  readonly tokenLabel: string | null;
  readonly requestedAt: Iso;
  readonly expiresAt: Iso;
  readonly status: ApprovalStatus;
  /** ≤ 120 characters, for lists and channels. */
  readonly summary: string | null;
  readonly decidedVia: string | null;
  readonly decidedAt: Iso | null;
  readonly commentPolicy: CommentPolicy;
}

/**
 * A run of the exact action as the server's ActionInspector cut it: `text`, or an `invisible` / `confusable`
 * character the UI draws as a visible escape (`gw-tok-invisible`, `gw-tok-confusable`). The UI never re-parses the
 * action (ARCHITECTURE.md §8.5).
 */
export interface ActionSegment {
  readonly t: 'text' | 'invisible' | 'confusable';
  readonly v: string;
}

export interface DiffLine {
  readonly t: 'context' | 'add' | 'del';
  readonly v: string;
}

/** A unified diff hunk; `header` is the `@@ … @@` line. */
export interface DiffHunk {
  readonly header: string;
  readonly lines: DiffLine[];
}

export interface Diff {
  readonly added: number;
  readonly removed: number;
  readonly hunks: DiffHunk[];
}

/** The exact action: what runs, byte for byte (`kind`: sql, command, http, diff, runbook, json, text). */
export interface ApprovalAction {
  readonly kind: string | null;
  readonly language: string | null;
  readonly text: string;
  readonly segments: ActionSegment[];
  readonly hiddenChars: number;
  readonly confusables: number;
  /** "Contains 2 hidden characters…", when there are any. */
  readonly warning: string | null;
  readonly diff: Diff | null;
}

export interface LabelledText {
  readonly label: string;
  readonly text: string;
}

export interface ApprovalRule {
  readonly id: string;
  /** What the rule means for the person ("Every change waits for a person on dev."). */
  readonly text: string;
}

export interface ApprovalResult {
  readonly text: string | null;
  readonly structured: JsonValue;
  readonly isError: boolean;
  readonly truncated: boolean;
}

/** One `approval_event`: requested, notified, delivered, decided, running, completed, failed, expired. */
export interface ApprovalTimelineEntry {
  readonly id: number;
  readonly at: Iso;
  readonly kind: string;
  readonly detail: JsonValue;
}

/** Where the request was announced: `sent`, `failed` or `off` per channel. */
export interface ApprovalChannels {
  readonly desktop: string | null;
  readonly slack: string | null;
  readonly telegram: string | null;
}

/** `GET /api/approvals/{id}`. */
export interface ApprovalDetail {
  readonly id: string;
  readonly callId: string;
  readonly tool: string;
  readonly toolKey: string;
  readonly connection: string | null;
  readonly env: Env;
  readonly endpoint: string;
  readonly client: string | null;
  readonly tokenLabel: string | null;
  readonly requestedAt: Iso;
  readonly expiresAt: Iso;
  readonly status: ApprovalStatus;
  readonly action: ApprovalAction | null;
  readonly target: string | null;
  readonly summary: string | null;
  readonly fields: LabelledText[];
  readonly impact: LabelledText[];
  readonly warnings: string[];
  readonly rule: ApprovalRule | null;
  /** Written by the agent, not checked by the gateway: shown last, as "unverified". */
  readonly agentNote: string | null;
  readonly commentPolicy: CommentPolicy;
  readonly comment: string | null;
  /** Echoed (≥ 8 characters) with Approve, so the decision binds to exactly this action. */
  readonly digest: string;
  readonly decidedVia: string | null;
  readonly decidedAt: Iso | null;
  readonly executedAt: Iso | null;
  readonly error: string | null;
  readonly result: ApprovalResult | null;
  readonly resultExpired: boolean;
  readonly timeline: ApprovalTimelineEntry[];
  readonly channels: ApprovalChannels | null;
  /**
   * Where the request's call is in Activity (step API): its event (`/activity#event-<id>`, the latest row) and its
   * trace; `traceIsTask` when the trace is a task of Traces (the client's own trace id), else a lone call's id.
   */
  readonly eventId?: number | null;
  readonly traceId?: string | null;
  readonly traceIsTask?: boolean;
}

/** `GET /api/approvals?status=pending|history`. */
export interface ApprovalList {
  readonly status: 'pending' | 'history';
  readonly items: ApprovalItem[];
  readonly nextCursor: string | null;
  /** How many wait now (the tab's count), whatever the list shows. */
  readonly pending: number;
  /** Decisions of the last 24 hours: approved, rejected, expired, failed… */
  readonly counts: { readonly [status: string]: number };
}

export interface ApprovalDecision {
  readonly decision: 'approve' | 'deny';
  readonly comment?: string | null;
  /** Required for approve: a prefix (≥ 8) of {@link ApprovalDetail.digest}. */
  readonly digest?: string | null;
  readonly via?: 'app';
}

@Injectable({ providedIn: 'root' })
export class ApprovalsApi {
  private readonly api = inject(ApiClient);

  list(status: 'pending' | 'history', cursor?: string | null, limit = 50): Promise<ApprovalList> {
    return this.api.get('/api/approvals', { status, cursor, limit });
  }

  get(id: string): Promise<ApprovalDetail> {
    return this.api.get(ApiClient.path`/api/approvals/${id}`);
  }

  /** 409: already decided or expired; 422: comment required on prod, or digest mismatch. */
  decide(id: string, decision: ApprovalDecision): Promise<{ readonly id: string; readonly status: ApprovalStatus }> {
    return this.api.post(ApiClient.path`/api/approvals/${id}/decision`, { via: 'app', ...decision });
  }
}
