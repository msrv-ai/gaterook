import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Env, Iso, JsonObject, JsonValue, ToolMode, WithReason } from './types';

/** The value of one secret field of a form: typed here, sent once, never kept by the UI. */
export type SecretInput =
  | { readonly source: 'local'; readonly value: string }
  | { readonly source: 'reference'; readonly ref: string };

/** Secrets of a draft by JSON Pointer of the field (`/credentials/password`). */
export type SecretInputs = { readonly [pointer: string]: SecretInput };

export interface ConnectionHealth {
  readonly state: 'healthy' | 'degraded' | 'unreachable' | 'unknown' | string;
  readonly latencyMs: number | null;
  readonly message: string | null;
  readonly checkedAt: Iso | null;
  /** Since when the checks say so ("unreachable since 11:42"); null before the first check (step API). */
  readonly since?: Iso | null;
}

/**
 * A connection's health after a `connection.health` event, which doesn't say since when: the same state keeps its
 * `since`, a new one starts at the check that found it.
 */
export function nextHealth(previous: ConnectionHealth | null | undefined, next: ConnectionHealth): ConnectionHealth {
  const same = (previous?.state ?? '').toLowerCase() === (next.state ?? '').toLowerCase();
  return { ...next, since: same ? (previous?.since ?? null) : next.checkedAt };
}

/** One row of Connections (admin.connections.ConnectionsController.Row). */
export interface ConnectionRow {
  readonly name: string;
  readonly type: string;
  readonly typeName: string;
  readonly env: Env;
  /**
   * Where it connects, in the kit's words: no scheme or credentials ("db.dev.internal:5432/app",
   * "k8s.dev.internal:6443 · ns app-dev", "eu-central-1 · acme-stage").
   */
  readonly host: string | null;
  /** "Read replica", "Runbooks only": from the config and the last test. */
  readonly badges: string[];
  /** `encrypted`, `vault`, `gcp-sm`, `aws-sm`, `short-lived`. */
  readonly secretSource: string | null;
  readonly health: ConnectionHealth;
  readonly usedBy: string[];
  /** False when the row was changed outside GateRook (it stays off until reviewed). */
  readonly verified: boolean;
  readonly version: number;
  /** The Type column in the kit's words: "PostgreSQL 16", "Loki", "MongoDB 7", "SSH" (step API). */
  readonly typeLabel?: string;
  /** The form variant (`loki`, `gcp-logging`), or null. */
  readonly variant?: string | null;
  /** What the last successful test called the server ("PostgreSQL 16.4"), or null. */
  readonly serverVersion?: string | null;
}

export interface ConnectionList {
  readonly items: ConnectionRow[];
  readonly facets: {
    readonly health: { readonly [state: string]: number };
    readonly env: { readonly [env: string]: number };
    readonly type: { readonly [type: string]: number };
  };
  readonly total: number;
  /** How often the core checks every connection (`gateway.connections.health-interval`), in seconds (step API). */
  readonly healthIntervalSeconds?: number;
}

export interface StoredSecret {
  readonly field: string;
  readonly secretId: string | null;
  readonly name: string | null;
  /** `stored` or `reference` (`needs-value` after a lost master key). */
  readonly state: string;
  readonly version: number | null;
  readonly source: string | null;
  /**
   * What `GET /api/secrets` adds to the row of the same secret (the connection's detail doesn't carry it): when the
   * current version was set, and whether Rotate can issue a new value at the source (else why not).
   */
  readonly updatedAt?: Iso | null;
  readonly canRotate?: boolean;
  readonly rotateReason?: string | null;
}

export interface ConnectionUsedBy {
  readonly tool: string;
  readonly endpoint: string;
  readonly mode: ToolMode | string;
}

/** `GET /api/connections/{name}` (connection-new `?edit=`). */
export interface ConnectionDetail {
  readonly name: string;
  readonly type: string;
  readonly typeName: string;
  readonly env: Env;
  readonly config: JsonObject;
  readonly version: number;
  readonly secrets: StoredSecret[];
  readonly health: ConnectionHealth;
  readonly spark24h: number[];
  readonly usedBy: ConnectionUsedBy[];
  readonly verified: boolean;
  /**
   * What the last successful test found that the form lists options from (`x-options-from`: Kubernetes
   * `kubeconfigContexts`); only those keys, never a secret; null without (step API).
   */
  readonly metadata?: JsonObject | null;
  /** As the row's. */
  readonly host?: string | null;
  readonly typeLabel?: string;
  readonly variant?: string | null;
  readonly serverVersion?: string | null;
  readonly healthIntervalSeconds?: number;
}

/** `GET /api/connections/{name}/duplicate`: the settings without any secret. */
export interface ConnectionDraft {
  readonly type: string;
  readonly env: Env;
  readonly config: JsonObject;
  readonly emptySecretFields: string[];
  readonly notice: string | null;
}

export interface TestPermissions {
  /** `read`, `write`, `admin` or `unknown` (the core writes them in lower case; compare without case). */
  readonly level: 'read' | 'write' | 'admin' | 'unknown' | string;
  readonly read: string | null;
  readonly write: string | null;
  readonly admin: string[];
  readonly details: JsonValue;
}

export interface TestHint {
  /** `info`, `warning`, `danger` or `error` (lower case from the core; compare without case). */
  readonly severity: 'info' | 'warning' | 'danger' | 'error' | string;
  readonly code: string;
  readonly field: string | null;
  readonly text: string;
  /** A copyable fix, e.g. SQL "Create a read-only user" (`gw_reader`). */
  readonly snippet: { readonly language: string; readonly text: string } | null;
}

export interface TestCheck {
  readonly name: string;
  readonly ok: boolean;
  readonly ms: number;
  readonly detail: string | null;
}

/** A test result (admin.connections.TestDtos.Outcome). `needsConfirmation` stops before credentials leave. */
export interface TestOutcome {
  readonly ok: boolean;
  readonly latencyMs: number;
  readonly serverVersion: string | null;
  readonly identity: string | null;
  readonly permissions: TestPermissions | null;
  readonly hints: TestHint[];
  readonly checks: TestCheck[];
  readonly error: { readonly code: string; readonly message: string; readonly technical: string | null } | null;
  readonly needsConfirmation: { readonly kind: string; readonly text: string; readonly data: JsonValue } | null;
  readonly confirmationId: string | null;
  readonly suggestedPolicyChange: JsonValue;
  readonly details: JsonValue;
}

export interface TestConfirmed {
  readonly kind: string;
  readonly config: JsonObject;
  readonly retest: TestOutcome;
}

export interface TestDraftRequest {
  readonly type: string;
  readonly env: Env;
  readonly config: JsonObject;
  readonly secrets?: SecretInputs;
  /** The connection being edited, so its stored secrets take part in the test. */
  readonly existing?: string | null;
  readonly confirm?: string | null;
}

export interface CreateConnectionRequest extends WithReason {
  readonly name: string;
  readonly type: string;
  readonly env: Env;
  readonly config: JsonObject;
  readonly secrets?: SecretInputs;
  /** `danger` after the core asked (409 with `confirm`) about the rights of a prod connection's user. */
  readonly confirm?: string | null;
}

export interface UpdateConnectionRequest extends WithReason {
  readonly config: JsonObject;
  readonly env: Env;
  readonly version: number;
  /**
   * What the person confirmed after the core asked (409 with `confirm`): `send-secret-to-new-host`, `danger`, or both,
   * separated by a comma.
   */
  readonly confirm?: string | null;
}

export interface ConnectionDeleted {
  readonly stoppedTools: string[];
  readonly rejectedApprovals: number;
  readonly templates: string[];
  readonly clients: string[];
  readonly secretsDestroyed: string[];
}

export interface ConnectionQuery {
  readonly q?: string | null;
  readonly type?: string | null;
  readonly env?: string | null;
  readonly health?: string | null;
  /** `connection` (no external MCP servers: the Connections page) or `mcp-server`; both without (step API). */
  readonly kind?: 'connection' | 'mcp-server' | null;
}

@Injectable({ providedIn: 'root' })
export class ConnectionsApi {
  private readonly api = inject(ApiClient);

  list(query: ConnectionQuery = {}): Promise<ConnectionList> {
    return this.api.get('/api/connections', { ...query });
  }

  /** 202: results arrive as `connection.health` events. */
  testAll(): Promise<{ readonly connections: number }> {
    return this.api.post('/api/connections/test-all');
  }

  /** Tests a draft; persists nothing, secret values live only in this request. */
  testDraft(request: TestDraftRequest): Promise<TestOutcome> {
    return this.api.post('/api/connections/test', request);
  }

  /** Authenticated discovery for a draft; database choices are in details.databases. */
  databases(request: TestDraftRequest): Promise<TestOutcome> {
    return this.api.post('/api/connections/databases', request);
  }

  /** "Trust" after `needsConfirmation` (host key, certificate): the patched draft and a re-test. */
  confirmDraft(confirmationId: string, reason?: string | null): Promise<TestConfirmed> {
    return this.api.post('/api/connections/test/confirm', { confirmationId, reason });
  }

  /** 201 with the new connection's row (409 when the name is taken, 422 with the field at fault). */
  create(request: CreateConnectionRequest): Promise<ConnectionRow> {
    return this.api.post('/api/connections', request);
  }

  duplicate(name: string): Promise<ConnectionDraft> {
    return this.api.get(ApiClient.path`/api/connections/${name}/duplicate`);
  }

  get(name: string): Promise<ConnectionDetail> {
    return this.api.get(ApiClient.path`/api/connections/${name}`);
  }

  /** The connection's row after the edit; 409 `confirm: send-secret-to-new-host` or a stale `version`. */
  update(name: string, request: UpdateConnectionRequest): Promise<ConnectionRow> {
    return this.api.put(ApiClient.path`/api/connections/${name}`, request);
  }

  delete(name: string, confirmName: string, reason?: string | null): Promise<ConnectionDeleted> {
    return this.api.delete(ApiClient.path`/api/connections/${name}`, { confirmName, reason });
  }

  test(name: string): Promise<TestOutcome> {
    return this.api.post(ApiClient.path`/api/connections/${name}/test`);
  }

  confirmTest(name: string, confirmationId: string, reason?: string | null): Promise<TestConfirmed> {
    return this.api.post(ApiClient.path`/api/connections/${name}/test/confirm`, { confirmationId, reason });
  }

  confirmIntegrity(name: string, reason?: string | null): Promise<ConnectionRow> {
    return this.api.post(ApiClient.path`/api/connections/${name}/confirm-integrity`, { reason });
  }

  /** `allow-private` → the CIDR joins the policy's private ranges for this connection (Setup change). */
  policySuggestion(
    name: string,
    request: { readonly kind: 'allow-private'; readonly cidr: string } & WithReason,
  ): Promise<{ readonly env: Env; readonly version: number; readonly allowedPrivateRanges: string[] }> {
    return this.api.post(ApiClient.path`/api/connections/${name}/policy-suggestion`, request);
  }

  /** What an agent sees in `describe_connection` (without the connector's live part). */
  describe(name: string): Promise<JsonObject> {
    return this.api.get(ApiClient.path`/api/connections/${name}/describe`);
  }
}
