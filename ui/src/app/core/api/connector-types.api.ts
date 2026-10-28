import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Env, JsonObject, JsonValue, ToolAnnotations, WithReason } from './types';

/** `built-in`, `plugin`, `mcp-server`, `template`. */
export type ConnectorKind = 'built-in' | 'plugin' | 'mcp-server' | 'template' | string;

/** A card of the Add connection wizard (one per type or `x-variants` variant). */
export interface ConnectorCard {
  readonly type: string;
  readonly variant: string | null;
  readonly name: string;
  /** A Lucide icon name (BLUEPRINT §7: database, leaf, layers, terminal…). */
  readonly icon: string;
  readonly description: string;
  readonly kind: ConnectorKind;
  readonly version: string | null;
  /** `null` until signatures exist (K4); built-ins carry the single "Built-in" badge. */
  readonly signed: boolean | null;
  readonly tools: number | null;
  readonly toolsFrom: string | null;
  readonly connections: number;
  readonly available: boolean;
  /** Why the card is unavailable ("Not installed. Install it from the catalog…"). */
  readonly reason: string | null;
  readonly presetConfig: JsonValue;
}

export interface ConnectorGroup {
  readonly name: string;
  readonly cards: ConnectorCard[];
}

export interface ConnectorProcess {
  readonly state: string;
  readonly pid: number | null;
  readonly rssMb: number | null;
  readonly detail: string | null;
}

export interface ConnectorVariant {
  readonly id: string;
  readonly name: string;
  readonly icon: string;
  readonly description: string;
  readonly presetConfig: JsonValue;
}

/** Connectors › Installed (built-in + plugins + MCP servers). */
export interface InstalledConnector {
  readonly id: string;
  readonly kind: ConnectorKind;
  readonly name: string;
  readonly icon: string;
  readonly group: string;
  readonly description: string;
  readonly version: string;
  readonly signed: boolean | null;
  readonly tools: number | null;
  readonly connections: number;
  readonly enabled: boolean;
  readonly verified: boolean;
  readonly state: string;
  readonly process: ConnectorProcess | null;
  readonly variants: ConnectorVariant[];
}

/** `GET /api/connector-types`. */
export interface ConnectorCatalog {
  readonly groups: ConnectorGroup[];
  readonly installed: InstalledConnector[];
}

/**
 * A tool of a connector type as its manifest declares it. The core writes `nature` (`read`, `mutation`, `mixed`) and
 * the modes (`off`, `read-only`, `approval`) in lower case; compare without case.
 */
export interface ConnectorTool {
  readonly name: string;
  readonly title: string;
  readonly description: string;
  readonly nature: 'read' | 'mutation' | 'mixed' | string;
  /** The mode a pair starts in on a dev or stage endpoint. */
  readonly defaultMode: 'off' | 'read-only' | 'approval' | string;
  /** The mode a pair starts in on a prod endpoint. */
  readonly prodDefaultMode: 'off' | 'read-only' | 'approval' | string;
  readonly annotations: ToolAnnotations;
}

export interface ConnectorTypeDetail {
  readonly type: InstalledConnector;
  readonly tools: ConnectorTool[];
  readonly templateKinds: string[];
}

/** A secret field of a connector form: `kind` is the `x-gateway-secret` value (value, login, pem, binary). */
export interface SecretFieldInfo {
  readonly pointer: string;
  readonly kind: string;
  readonly title: string;
}

/** A secret source of the form: Encrypted here, Vault, GCP SM, AWS SM, Short-lived. */
export interface SecretSourceOption {
  readonly id: string;
  readonly label: string;
  readonly available: boolean;
  readonly reason: string | null;
}

/** What choosing prod (or stage) changes: shown under the environment cards. */
export interface EnvChange {
  readonly pointer: string;
  readonly title: string;
  readonly from: JsonValue;
  readonly to: JsonValue;
}

/** `GET /api/connector-types/{type}/form?env=`: the schema the JSON Schema renderer draws (§8.3). */
export interface ConnectionFormSpec {
  readonly type: string;
  readonly name: string;
  readonly env: Env;
  readonly schema: JsonObject;
  /** The common fields of every connection (name, env) as a schema. */
  readonly common: JsonObject;
  readonly secretFields: SecretFieldInfo[];
  readonly secretSources: SecretSourceOption[];
  readonly envDefaults: { readonly prod: EnvChange[]; readonly stage: EnvChange[] };
  readonly variants: ConnectorVariant[];
}

@Injectable({ providedIn: 'root' })
export class ConnectorTypesApi {
  private readonly api = inject(ApiClient);

  catalog(group?: string | null): Promise<ConnectorCatalog> {
    return this.api.get('/api/connector-types', { group });
  }

  get(type: string): Promise<ConnectorTypeDetail> {
    return this.api.get(ApiClient.path`/api/connector-types/${type}`);
  }

  /** The tools supported by one draft configuration, without connecting or resolving credentials. */
  previewTools(type: string, config: JsonObject, env: Env): Promise<{ readonly tools: ConnectorTool[] }> {
    return this.api.post(ApiClient.path`/api/connector-types/${type}/tools`, { config, env });
  }

  form(type: string, env?: Env | null): Promise<ConnectionFormSpec> {
    return this.api.get(ApiClient.path`/api/connector-types/${type}/form`, { env });
  }

  enable(type: string, body: WithReason = {}): Promise<InstalledConnector> {
    return this.api.post(ApiClient.path`/api/connector-types/${type}/enable`, body);
  }

  disable(type: string, body: WithReason = {}): Promise<InstalledConnector> {
    return this.api.post(ApiClient.path`/api/connector-types/${type}/disable`, body);
  }

  confirmIntegrity(type: string, body: WithReason = {}): Promise<InstalledConnector> {
    return this.api.post(ApiClient.path`/api/connector-types/${type}/confirm-integrity`, body);
  }
}
