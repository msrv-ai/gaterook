import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';

/** `GET /api/health` (admin.core.CoreController.Health). */
export interface Health {
  /** `ok`, `locked` (master key) or `degraded` (the audit is catching up). */
  readonly status: 'ok' | 'locked' | 'degraded';
  readonly locked: 'MASTER_KEY_MISSING' | 'MASTER_KEY_INVALID' | null;
  readonly version: string;
  readonly uptimeMs: number;
  readonly ports: { readonly local: number; readonly public: number };
  readonly runtime: 'native' | 'jvm';
  readonly db: {
    readonly path: string;
    readonly sqlite: string;
    readonly journal: string;
    readonly schema: string;
    readonly sizeBytes: number;
  };
  readonly masterKey: { readonly protection: string; readonly problems: string[]; readonly warnings: string[] };
  readonly tunnel: { readonly state: string; readonly host: string | null };
  readonly warnings: HealthWarning[];
}

/** A banner of the frame: `new-key-created`, `integrity`, `audit-catching-up`, `backup`. */
export interface HealthWarning {
  readonly kind: 'new-key-created' | 'integrity' | 'audit-catching-up' | 'backup' | string;
  readonly text: string;
  readonly subject: string | null;
}

/** `GET /api/core/info` (settings.CoreInfo.Info): Settings › General › Core. */
export interface CoreInfo {
  readonly version: string;
  readonly runtime: string;
  readonly vm: string;
  readonly port: number;
  readonly logFile: string;
  readonly pluginRuntime: {
    readonly bundledJre: boolean;
    readonly home: string;
    readonly version: string | null;
    readonly present: boolean;
  };
}

/** `GET /api/nav/counters`: the sidebar's approvals counter and tunnel state. */
export interface NavCounters {
  readonly pendingApprovals: number;
  /** `off`, `starting`, `on` or `error` (K3); `off` until the tunnel exists. */
  readonly tunnel: string;
}

/** `GET /api/search?q=`: the command palette. */
export interface SearchResult {
  readonly goTo: SearchGoTo[];
  readonly actions: SearchGoTo[];
  readonly connections: SearchConnection[];
  readonly endpoints: SearchEndpoint[];
}

export interface SearchGoTo {
  readonly id: string;
  readonly label: string;
  /** An app route: `/approvals`, `/connections/new`, `/endpoints#create`. */
  readonly route: string;
}

export interface SearchConnection {
  readonly name: string;
  readonly type: string;
  readonly env: string;
  readonly route: string;
}

export interface SearchEndpoint {
  readonly slug: string;
  readonly env: string;
  readonly route: string;
}

@Injectable({ providedIn: 'root' })
export class CoreApi {
  private readonly api = inject(ApiClient);

  health(): Promise<Health> {
    return this.api.get('/api/health');
  }

  info(): Promise<CoreInfo> {
    return this.api.get('/api/core/info');
  }

  counters(): Promise<NavCounters> {
    return this.api.get('/api/nav/counters');
  }

  search(q: string): Promise<SearchResult> {
    return this.api.get('/api/search', { q });
  }
}
