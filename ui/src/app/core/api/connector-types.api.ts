import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Env, JsonObject, JsonValue, ToolAnnotations, WithReason } from './types';

/** `built-in`, `plugin`, `mcp-server`, `template`. */
export type ConnectorKind = 'built-in' | 'plugin' | 'mcp-server' | 'template' | string;

/**
 * The connector module that adds a card's type: one of GateRook's own connectors that isn't part of the installer.
 * Choosing the card installs it without a review; the type's form waits for it.
 */
export interface ConnectorModuleRef {
  readonly id: string;
  readonly version: string;
  /** `ready`, `installing`, `missing`, `available`, `failed`, `revoked` or `disabled`. */
  readonly state: string;
  /** The step of a running installation (`runtime`, `downloading`, `verifying`, `installing`), else null. */
  readonly step: string | null;
  /** The size of the download. */
  readonly sizeBytes: number;
  readonly installed: boolean;
  /** Why the last installation failed, in the core's words; else null. */
  readonly message: string | null;
}

/** A card of the Add connection wizard (one per type or `x-variants` variant). */
export interface ConnectorCard {
  readonly type: string;
  readonly variant: string | null;
  readonly name: string;
  /** A Lucide icon name (status vocabulary: database, leaf, layers, terminal…). */
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
  /** Why the card is unavailable ("Not installed yet. Continue reviews it and installs it from the catalog."). */
  readonly reason: string | null;
  readonly presetConfig: JsonValue;
  /** `id@version` of a plugin the signed catalog offers and this core hasn't installed: the wizard installs it. */
  readonly catalogRef?: string | null;
  /** The connector module that adds the type, when one does; absent for a type that is part of the installer. */
  readonly module?: ConnectorModuleRef | null;
  /** Set by the wizard, not the core: a compatible service's card names its base connector ("PostgreSQL"). */
  readonly compatibleWith?: string | null;
  /** Set by the wizard for an MCP server card: its mark, a path of the app's public files (`mcp-logos/linear.png`). */
  readonly markSrc?: string | null;
  /** Set by the wizard for an MCP server card: the line under its description ("MCP server · HTTP · v1.2.0"). */
  readonly metaText?: string | null;
  /** Set by the wizard for an MCP server card: GateRook's verified profile, an official registry entry, a deprecated one. */
  readonly badge?: 'verified' | 'official' | 'deprecated' | null;
  /** Set by the wizard for an MCP server card: what the search matches besides its name, in place of its type and variant. */
  readonly searchWords?: string | null;
}

export interface ConnectorGroup {
  readonly name: string;
  readonly cards: ConnectorCard[];
  /** Set by the wizard: a sentence under the category's title (the MCP section's groups). */
  readonly description?: string | null;
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
  /** The connected store it reads from: every Vault is a source of its own. Absent for the other kinds. */
  readonly store?: string | null;
  /** What tells that store apart, such as a Vault's address. */
  readonly detail?: string | null;
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
