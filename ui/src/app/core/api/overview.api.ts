import { Injectable, inject } from '@angular/core';
import { ActivityEvent } from './activity.api';
import { ApiClient } from './api-client';
import { ApprovalItem } from './approvals.api';
import { Env, Iso, JsonValue } from './types';

export type GettingStartedStepId = 'master-key' | 'connection' | 'endpoint' | 'client' | 'playground' | 'notifications';

/** One step of getting-started: `current` is the first one not done. */
export interface GettingStartedStep {
  readonly id: GettingStartedStepId;
  readonly state: 'done' | 'current' | 'todo';
  /** Facts for the step's line (`lastBackupAt`, `name`, `tools`, `clients`). */
  readonly detail: { readonly [key: string]: JsonValue };
}

export interface GettingStarted {
  readonly done: number;
  readonly total: number;
  readonly dismissed: boolean;
  readonly steps: GettingStartedStep[];
}

export type SetupNodeId = 'secrets' | 'connections' | 'policies' | 'endpoints' | 'clients';

export interface SetupNode {
  readonly id: SetupNodeId;
  readonly count: number;
  readonly state: 'done' | 'attention' | 'todo';
  /**
   * The node's line in the kit's words (step API): "Master key backed up · 11 secrets", "12 connections · billing-api
   * unreachable", "3 environments · mutations need approval", "3 endpoints · 2 active tokens", "2 connected · Cursor
   * detected, not connected".
   */
  readonly status: string;
}

export interface SetupMap {
  readonly nodes: SetupNode[];
}

export interface OverviewStats {
  readonly calls24h: number;
  readonly pending: number;
  readonly denied24h: number;
  readonly gatewayP95Ms: number | null;
  readonly deltas: { readonly calls24h: number; readonly denied24h: number; readonly gatewayP95Ms: number | null };
  /** Calls per hour of the last 24 hours, oldest first. */
  readonly spark: number[];
  /**
   * The gateway's own p95 for each hour of `spark` (the overhead tile's sparkline), null for an hour without
   * completed calls (step API).
   */
  readonly gatewayP95Spark?: (number | null)[];
}

export interface HealthRow {
  readonly name: string;
  readonly type: string;
  readonly env: Env;
  readonly state: 'healthy' | 'degraded' | 'unreachable' | 'unknown' | string;
  readonly latencyMs: number | null;
  readonly message: string | null;
  readonly checkedAt: Iso | null;
  /** Since when its health is what it is ("unreachable since 11:42"), step API. */
  readonly since?: Iso | null;
  /** As the Connections row: "PostgreSQL 16", and where it connects without the scheme. */
  readonly typeLabel?: string;
  readonly host?: string | null;
}

/** `GET /api/overview`. */
export interface Overview {
  readonly gettingStarted: GettingStarted;
  readonly setupMap: SetupMap;
  readonly stats: OverviewStats;
  readonly waiting: ApprovalItem[];
  readonly recent: ActivityEvent[];
  /** The connections (not the external MCP servers), problems first. */
  readonly health: HealthRow[];
  /** How often the core checks every connection, in seconds ("Health is checked every minute"), step API. */
  readonly healthIntervalSeconds?: number;
}

/** One day of `GET /api/overview/summary`: a date in the person's zone and what went through GateRook that day. */
export interface SummaryDay {
  /** The date in the summary's zone, ISO (`2026-11-11`). */
  readonly date: string;
  /** The first clock hour of the day (its midnight wherever the zone is whole hours off UTC). */
  readonly from: Iso;
  /** The first hour of the next day: today's is still ahead. */
  readonly to: Iso;
  /** The day's calls by the decision of their latest row; a missing key is 0. */
  readonly decisions: { readonly [decision: string]: number };
  readonly calls: number;
  /** Bytes AI apps sent with the calls received that day: their arguments ("in"). */
  readonly requestBytes: number;
  /** Bytes that went back to AI apps with the calls completed that day: results and errors ("out"). */
  readonly resultBytes: number;
}

export interface SummaryTotals {
  readonly calls: number;
  readonly decisions: { readonly [decision: string]: number };
  readonly requestBytes: number;
  readonly resultBytes: number;
}

/** `GET /api/overview/summary`: the last 30 days, today included, oldest first. */
export interface OverviewSummary {
  readonly from: Iso;
  readonly to: Iso;
  /** The zone the dates are in: the one the app asked for, or the computer's own when the core doesn't know it. */
  readonly zone: string;
  /** When the oldest call the audit keeps reached the gateway; null before the first call. */
  readonly firstCallAt: Iso | null;
  readonly totals: SummaryTotals;
  /** Always 30, the last one today. */
  readonly days: readonly SummaryDay[];
}

/** `GET /api/onboarding`: `firstRun` comes from this start's ready line. */
export interface Onboarding {
  readonly firstRun: boolean;
  readonly skipped: boolean;
  readonly gettingStarted: GettingStarted;
  readonly setupMap: SetupMap;
}

@Injectable({ providedIn: 'root' })
export class OverviewApi {
  private readonly api = inject(ApiClient);

  get(): Promise<Overview> {
    return this.api.get('/api/overview');
  }

  /** The last 30 days by day, as dates in `zone` (the person's, `Europe/Berlin`; the core's own without it). */
  summary(zone?: string | null): Promise<OverviewSummary> {
    return this.api.get('/api/overview/summary', { zone });
  }

  /** Hide the checklist (the setup map keeps showing what is left); Undo is {@link restore}. */
  dismissGettingStarted(): Promise<GettingStarted> {
    return this.api.post('/api/overview/getting-started/dismiss');
  }

  restoreGettingStarted(): Promise<GettingStarted> {
    return this.api.post('/api/overview/getting-started/restore');
  }

  onboarding(): Promise<Onboarding> {
    return this.api.get('/api/onboarding');
  }

  skipOnboarding(): Promise<{ readonly skipped: boolean }> {
    return this.api.post('/api/onboarding/skip');
  }
}
