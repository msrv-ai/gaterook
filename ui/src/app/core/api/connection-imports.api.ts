import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { SecretInputs } from './connections.api';
import { Env, JsonObject } from './types';
import { SecretStore, SecretStoreCreate } from './secrets.api';

export interface ImportCredentialHint {
  readonly field: string;
  readonly source: string;
  readonly reference?: string | null;
  readonly instructions: string;
}

export interface ImportStoreProposal {
  readonly id: string;
  readonly kind: 'vault';
  readonly name: string;
  readonly config: { readonly address?: string; readonly namespace?: string; readonly auth?: { readonly method?: 'token' | 'approle' | 'cli-token' | 'token-file'; readonly roleId?: string } };
  readonly evidence: readonly string[];
  readonly warnings: readonly string[];
  readonly credentialHints: readonly ImportCredentialHint[];
  readonly secretSources?: readonly { readonly field: string; readonly source: 'local' | 'file'; readonly path?: string; readonly jsonPointer?: string }[];
  readonly connectedStore?: string | null;
}

export type ImportStoreConnect = (Omit<SecretStoreCreate, 'kind'> & { readonly preparedFields?: readonly string[] }) | { readonly existingStore: string };

export interface ImportCandidate {
  readonly id: string;
  readonly name: string;
  readonly type: string;
  readonly env: Env;
  readonly config: JsonObject;
  readonly evidence: readonly string[];
  readonly credentialHints: readonly ImportCredentialHint[];
  readonly warnings: readonly string[];
  readonly supported: boolean;
  readonly unsupportedReason: string | null;
  /** Sources staged by the core. Credential values are never returned to the renderer. */
  readonly secretSources?: readonly { readonly field: string; readonly source: 'local' | 'file' | 'reference' | 'vault-dynamic'; readonly path?: string; readonly reference?: string; readonly jsonPointer?: string; readonly store?: string }[];
}

export interface ConnectionImportSession {
  readonly id: string;
  readonly repository: string;
  readonly endpoint: string;
  readonly endpointUrl: string;
  readonly submissionToken: string;
  readonly importFile: string;
  readonly expiresAt: string;
  readonly status: 'awaiting' | 'review' | 'imported';
  readonly manifest: {
    readonly version: 1;
    readonly connections: readonly ImportCandidate[];
    readonly stores?: readonly ImportStoreProposal[];
    readonly warnings: readonly string[];
  } | null;
}

export interface ImportSelection {
  readonly candidateId: string;
  readonly endpoint?: string;
  readonly name: string;
  readonly env: Env;
  readonly config: JsonObject;
  readonly secrets?: SecretInputs;
  readonly confirm?: string;
  readonly allowIncomplete?: boolean;
}

export interface ConnectionImportResult {
  readonly connections: readonly string[];
  readonly endpoint: string | null;
  readonly destinations?: readonly { readonly connection: string; readonly endpoint: string }[];
  readonly status: 'imported';
  readonly needsSetup?: readonly { readonly name: string; readonly missingFields: readonly string[] }[];
}

@Injectable({ providedIn: 'root' })
export class ConnectionImportsApi {
  private readonly api = inject(ApiClient);

  start(repository: string, endpoint: string): Promise<ConnectionImportSession> {
    return this.api.post('/api/connection-imports', { repository, endpoint });
  }

  get(id: string): Promise<ConnectionImportSession> {
    return this.api.get(ApiClient.path`/api/connection-imports/${id}`);
  }

  cancel(id: string): Promise<void> {
    return this.api.delete(ApiClient.path`/api/connection-imports/${id}`);
  }

  connectStore(id: string, proposal: string, request: ImportStoreConnect): Promise<SecretStore> {
    return this.api.post(ApiClient.path`/api/connection-imports/${id}/stores/${proposal}/connect`, request);
  }

  commit(id: string, selections: readonly ImportSelection[], reason?: string): Promise<ConnectionImportResult> {
    return this.api.post(ApiClient.path`/api/connection-imports/${id}/commit`, { selections, reason });
  }
}
