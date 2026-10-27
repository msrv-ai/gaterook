import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Iso, JsonObject, WithReason } from './types';

/** One of the secrets table's rows; no value, ever (write-only). */
export interface SecretRow {
  readonly id: string;
  readonly name: string;
  readonly owner: string;
  readonly ownerKind: 'connection' | 'store' | 'mcp-server' | 'notification' | 'tunnel' | 'audit-export' | string;
  readonly field: string;
  readonly usedBy: string[];
  /** `local` (encrypted here), or the store of a reference: `vault`, `gcp-sm`, `aws-sm` (step S1). */
  readonly source: string;
  readonly version: number | null;
  readonly remoteVersion: string | null;
  readonly rotatedAt: Iso | null;
  /** `stored`, `needs-value`, `testing`; for references `reference`, `short-lived`, `not-connected`, `changed-outside`. */
  readonly state: string;
  readonly canRotate: boolean;
  /** Why Rotate is unavailable (shown with `aria-disabled`). */
  readonly rotateReason: string | null;
  /** A reference's URI (`vault://kv/stage/db#password`); absent for secrets stored here and before step S1. */
  readonly ref?: string | null;
  /** The external store a reference is bound to; null while that kind of store isn't connected (S1). */
  readonly store?: string | null;
}

export interface SecretReplaced {
  readonly id: string;
  readonly version: number;
  readonly state: string;
  readonly tested: boolean | null;
  readonly testMessage: string | null;
}

export interface ShortLivedCredential {
  readonly connection: string;
  readonly mechanism: string;
  readonly identity: string;
  readonly lastIssuedAt: Iso | null;
  readonly lifetime: number;
  /** The principal it was issued for (Vault `deploy`, a database user), null when the source doesn't say (step API). */
  readonly principal?: string | null;
  /** The ExternalId of an AWS role it assumes (not a secret: it guards against the confused deputy), else null. */
  readonly externalId?: string | null;
}

/** A hint of a store test or a reference test (S1 `SecretStores.Hint`); `severity`: info, warning, danger. */
export interface SecretStoreHint {
  readonly severity: 'info' | 'warning' | 'danger' | string;
  readonly code: string;
  readonly text: string;
}

/**
 * Result of `POST /api/secret-refs/test` (S1 `SecretStores.RefTest`): resolved now, nothing kept; no length, hash or
 * prefix of the value. `reason` when not found: `not-found`, `gone`, `denied`, `unavailable`, `invalid`,
 * `not-connected`.
 */
export interface SecretRefTest {
  readonly found: boolean;
  readonly uri?: string | null;
  readonly store?: string | null;
  readonly resolvedVersion: string | null;
  readonly created: Iso | null;
  readonly type: string | null;
  readonly keys: string[];
  readonly latencyMs?: number | null;
  readonly error?: string | null;
  readonly reason?: string | null;
  readonly hints?: SecretStoreHint[];
}

/** A stored reference after Replace reference (S1): where it points, never what it holds. */
export interface SecretReference {
  readonly id: string;
  readonly name: string;
  readonly owner: string | null;
  readonly field: string;
  readonly source: string;
  readonly ref: string | null;
  readonly store: string | null;
  readonly remoteVersion: string | null;
  readonly state: string;
}

export type SecretStoreKind = 'vault' | 'gcp-sm' | 'aws-sm';

/** A secret that points into a store: a reference or Vault dynamic credentials (`kind`). */
export interface SecretStoreReference {
  readonly secretId: string;
  readonly name: string;
  readonly ref: string;
  /** `reference` or `dynamic`. */
  readonly kind: string;
  /** The connection (or other owner) the secret belongs to. */
  readonly owner: string | null;
}

/** A secret field of the store's own sign-in (AppRole secret ID, token, key), never its value. */
export interface SecretStoreSignIn {
  readonly field: string;
  readonly secretId: string;
  readonly name: string;
  readonly state: string;
  readonly version: number | null;
}

/**
 * An external store (S1 `SecretStores.StoreInfo`): Vault/OpenBao, GCP Secret Manager, AWS Secrets Manager.
 * `state`: `connected` (its last test passed), `failing` or `not-checked`; `issues` — connections it issues
 * short-lived credentials for (dynamic users, SSH certificates); `verified` false — changed outside MCP Gateway.
 */
export interface SecretStore {
  readonly id: string;
  readonly kind: SecretStoreKind;
  readonly name: string;
  readonly config: JsonObject;
  readonly state: 'connected' | 'failing' | 'not-checked' | string;
  readonly lastCheckedAt: Iso | null;
  readonly latencyMs?: number | null;
  readonly lastError?: string | null;
  readonly references?: SecretStoreReference[];
  readonly signIn?: SecretStoreSignIn[];
  readonly issues?: number;
  readonly verified?: boolean;
  readonly version?: number;
}

/** `POST /api/secret-stores`: the kind's settings and its sign-in values by JSON Pointer (encrypted on arrival). */
export interface SecretStoreCreate extends WithReason {
  readonly kind: SecretStoreKind;
  readonly name: string;
  readonly config: JsonObject;
  readonly secrets?: { readonly [pointer: string]: string };
}

/** `POST /api/secret-stores/{id}/test` (S1 `SecretStores.TestResult`): no values, never a length, hash or prefix. */
export interface SecretStoreTest {
  readonly ok: boolean;
  readonly latencyMs: number;
  readonly server: JsonObject | null;
  readonly identity: JsonObject | null;
  readonly permissions: { readonly ref: string; readonly capabilities: string[] }[];
  readonly hints: SecretStoreHint[];
  readonly checks: { readonly name: string; readonly ok: boolean; readonly ms: number; readonly detail: string | null }[];
  readonly error: string | null;
  readonly reason: string | null;
}

/** What disconnecting a store left behind (S1 `SecretStores.Disconnected`). */
export interface SecretStoreDisconnected {
  readonly referencesNotConnected: string[];
  readonly leasesRevoked: number;
  readonly secretsDestroyed: string[];
}

export interface MasterKeyRotation {
  readonly state: 'idle' | 'running' | 'done' | 'failed';
  readonly done: number;
  readonly total: number;
  readonly targetKeyId: number | null;
  readonly error: string | null;
}

/** `GET /api/master-key`. `problems`: perms, synced-folder, backup-needed, no-os-protection, key-missing, key-invalid. */
export interface MasterKeyStatus {
  readonly path: string;
  readonly mode: string;
  readonly owner: string;
  readonly keys: { readonly id: number; readonly status: string; readonly primary: boolean }[];
  readonly osProtection: string;
  readonly protects: number;
  readonly lastBackupAt: Iso | null;
  readonly problems: string[];
  readonly warnings: string[];
  readonly locked: string | null;
  readonly rotation: MasterKeyRotation;
  /** When this key was made (first start, Create a new key) and last rotated; null when not known (step API). */
  readonly createdAt?: Iso | null;
  readonly rotatedAt?: Iso | null;
  /**
   * Whose local secrets the key protects, one kind of owner each — what a new key would destroy; answered while
   * locked too (step API).
   */
  readonly protectsBy?: MasterKeyProtects[];
  /**
   * The secret references, which don't need the key and keep working with a new one: how many, and their sources
   * (`vault`, `gcp-sm`, `aws-sm`) each once.
   */
  readonly references?: { readonly count: number; readonly sources: string[] };
}

/** One kind of owner of local secrets the master key protects. */
export interface MasterKeyProtects {
  /** `connection`, `mcp-server`, `store` (an external store's sign-in), `notification`, `tunnel`, `audit-export`. */
  readonly kind: string;
  /** How many local secrets of that kind have a value. */
  readonly secrets: number;
  /** Their owners' names, each once (`dev-db`, `github-mcp`, `vault`). */
  readonly owners: string[];
}

@Injectable({ providedIn: 'root' })
export class SecretsApi {
  private readonly api = inject(ApiClient);

  list(): Promise<SecretRow[]> {
    return this.api.get('/api/secrets');
  }

  /** Replace → version + 1; `reason` is required on prod; `testBefore` checks the new version before switching. */
  replace(id: string, request: { readonly value: string; readonly testBefore?: boolean } & WithReason): Promise<SecretReplaced> {
    return this.api.put(ApiClient.path`/api/secrets/${id}/value`, request);
  }

  /** 202 `{jobId}`, or 409 with the reason the source can't rotate it. */
  rotate(id: string, body: WithReason = {}): Promise<{ readonly jobId: string }> {
    return this.api.post(ApiClient.path`/api/secrets/${id}/rotate`, body);
  }

  /** Points a stored reference elsewhere (S1); `store` when more than one store of its kind is connected. */
  setReference(id: string, uri: string, store?: string | null, reason?: string | null): Promise<SecretReference> {
    return this.api.put(ApiClient.path`/api/secrets/${id}/reference`, { uri, store: store ?? null, reason: reason ?? null });
  }

  /** Test reference: the core resolves it now and keeps nothing. */
  testReference(uri: string, store?: string | null): Promise<SecretRefTest> {
    return this.api.post('/api/secret-refs/test', { uri, store: store ?? null });
  }

  shortLived(): Promise<ShortLivedCredential[]> {
    return this.api.get('/api/secrets/short-lived');
  }

  stores(): Promise<SecretStore[]> {
    return this.api.get('/api/secret-stores');
  }

  /** Connects a store; references of its kind without a store are bound to it (S1). */
  createStore(store: SecretStoreCreate): Promise<SecretStore> {
    return this.api.post('/api/secret-stores', store);
  }

  /** New settings; sign-in fields left out keep their stored values. `version` is the one edited. */
  updateStore(
    id: string,
    store: { readonly name?: string; readonly config: JsonObject; readonly secrets?: { readonly [pointer: string]: string }; readonly version: number } & WithReason,
  ): Promise<SecretStore> {
    return this.api.put(ApiClient.path`/api/secret-stores/${id}`, store);
  }

  /** Disconnect names the store again (`confirmName`), like every danger confirmation. */
  deleteStore(id: string, confirmName: string, reason?: string | null): Promise<SecretStoreDisconnected> {
    return this.api.delete(ApiClient.path`/api/secret-stores/${id}`, { confirmName, reason: reason ?? null });
  }

  testStore(id: string): Promise<SecretStoreTest> {
    return this.api.post(ApiClient.path`/api/secret-stores/${id}/test`);
  }

  /** The person reviewed a store changed outside MCP Gateway and keeps it as stored. */
  confirmStoreIntegrity(id: string, reason?: string | null): Promise<SecretStore> {
    return this.api.post(ApiClient.path`/api/secret-stores/${id}/confirm-integrity`, { reason: reason ?? null });
  }
}

@Injectable({ providedIn: 'root' })
export class MasterKeyApi {
  private readonly api = inject(ApiClient);

  status(): Promise<MasterKeyStatus> {
    return this.api.get('/api/master-key');
  }

  /** 202: progress arrives as `master-key.rotation` events. */
  rotate(body: WithReason = {}): Promise<MasterKeyRotation> {
    return this.api.post('/api/master-key/rotate', body);
  }

  rotation(): Promise<MasterKeyRotation> {
    return this.api.get('/api/master-key/rotation');
  }

  /** `saveToken` comes from DesktopBridge.saveFile('master-key-backup'): the UI never sends a path. */
  backup(saveToken: string, passphrase?: string | null): Promise<{
    readonly path: string;
    readonly at: Iso;
    readonly keyIds: number[];
    readonly passphrase: boolean;
    readonly warnings: string[];
  }> {
    return this.api.post('/api/master-key/backup', { saveToken, passphrase: passphrase || null });
  }

  fixPermissions(): Promise<MasterKeyStatus> {
    return this.api.post('/api/master-key/fix-permissions');
  }

  /** Locked mode: restore from a backup chosen with DesktopBridge.openFile('master-key-restore'). */
  restore(openToken: string, passphrase?: string | null, reason?: string | null): Promise<{ readonly secretsReadable: number }> {
    return this.api.post('/api/master-key/restore', { openToken, passphrase: passphrase || null, reason });
  }

  /** Locked mode, danger: local secrets become "needs value". */
  create(reason?: string | null): Promise<{ readonly needValue: string[] }> {
    return this.api.post('/api/master-key/create', { confirm: 'danger', reason });
  }
}
