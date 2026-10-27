import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Env, Iso, JsonObject, JsonValue, WithReason } from './types';

/** A policy rule lock: approval rules are bypassed only by an explicit endpoint tool mode, not a policy switch. */
export interface PolicyLock {
  readonly section: string;
  readonly key: string;
  readonly title: string;
  readonly kind: string;
  readonly values: JsonValue;
  readonly text: string;
}

export interface PolicyRunbook {
  readonly connection: string;
  readonly name: string;
  readonly template: string;
  readonly version: number;
  readonly status: string;
  readonly lastRunAt: Iso | null;
}

/** One environment's policy: effective `sections`, what this env sets itself (`explicit`), what it inherits. */
export interface EnvPolicy {
  readonly env: Env;
  readonly inherits: Env | null;
  readonly sections: JsonObject;
  readonly explicit: JsonObject;
  readonly inherited: { readonly [section: string]: boolean };
  readonly locks: PolicyLock[];
  readonly runbooks: PolicyRunbook[];
  readonly version: number;
  readonly verified: boolean;
  readonly serving: boolean;
}

export interface Policies {
  readonly dev: EnvPolicy;
  readonly stage: EnvPolicy;
  readonly prod: EnvPolicy;
  /** Rate-limiter use in the last minute: Limits' meters. */
  readonly usage: {
    readonly busiestToken: { readonly name: string; readonly calls: number } | null;
    readonly busiestTool: { readonly name: string; readonly calls: number } | null;
  };
}

@Injectable({ providedIn: 'root' })
export class PoliciesApi {
  private readonly api = inject(ApiClient);

  all(): Promise<Policies> {
    return this.api.get('/api/policies');
  }

  /** Sections of the connectors (SQL, SSH, Kubernetes, Cloud, HTTP egress) and the core (Masking, Limits, Approvals). */
  schema(): Promise<JsonObject> {
    return this.api.get('/api/policies/schema');
  }

  get(env: Env): Promise<EnvPolicy> {
    return this.api.get(ApiClient.path`/api/policies/${env}`);
  }

  /** `confirm: 'danger'` for `allowExec` on prod; locks answer 422. */
  update(env: Env, request: { readonly sections: JsonObject; readonly version: number; readonly confirm?: string | null } & WithReason): Promise<EnvPolicy> {
    return this.api.put(ApiClient.path`/api/policies/${env}`, request);
  }

  reset(env: Env, body: { readonly confirm?: string | null } & WithReason = {}): Promise<EnvPolicy> {
    return this.api.post(ApiClient.path`/api/policies/${env}/reset`, body);
  }

  confirmIntegrity(env: Env, body: { readonly confirm?: string | null } & WithReason = {}): Promise<EnvPolicy> {
    return this.api.post(ApiClient.path`/api/policies/${env}/confirm-integrity`, body);
  }
}
