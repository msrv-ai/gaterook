import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Diff } from './approvals.api';
import { SecretInputs } from './connections.api';
import { Iso, JsonObject, JsonValue, WithReason } from './types';

/** The signed catalog (steps K4/K6): plugins, templates, MCP servers. Offline: the last good index. */
export interface CatalogIndex {
  readonly checkedAt: Iso | null;
  readonly source: string;
  readonly offline: boolean;
  readonly signature: { readonly keyId: string; readonly sequence: number; readonly expiresAt: Iso; readonly ok: boolean } | null;
  readonly entries: CatalogEntry[];
  /**
   * Why the last check failed, or why nothing installs from the catalog now, in the core's words ("No catalog is
   * configured for this build.", "The catalog can't be reached: …", an expired signature); null when all is well.
   */
  readonly problem?: string | null;
}

export interface CatalogEntry {
  readonly id: string;
  readonly kind: 'plugin' | 'template' | 'mcp-server';
  readonly name: string;
  readonly version: string;
  readonly signed: boolean;
  readonly description: string;
  readonly tools: number | null;
  /** The installed version of a plugin, the installed server's name of an MCP server; null when not installed. */
  readonly installed: string | null;
  /** The Lucide icon of the resource type (ClickHouse `database`); by kind when absent (puzzle, file-code, plug). */
  readonly icon?: string | null;
  /** A template's connection types ("Runs on": `postgres`); absent: any connection may be offered. */
  readonly connectionTypes?: string[] | null;
}

/** One tool of a staged plugin and the annotations its manifest declares (the core can't verify them). */
export interface PluginCheckTool {
  readonly name: string;
  readonly declared: JsonValue;
}

/** The report of a staged plugin before Install: signature, hash, what it asks for. */
export interface PluginCheck {
  readonly stagingId: string;
  readonly report: {
    readonly signed: boolean;
    readonly sha256: string;
    /** Bytes of the JAR. */
    readonly size: number;
    readonly id: string;
    readonly version: string;
    /** The manifest's display name (RabbitMQ); the id when absent. */
    readonly name?: string | null;
    /** The JAR's file name as checked (`gw-rabbitmq-0.3.0.jar`). */
    readonly file?: string | null;
    readonly requires: JsonValue;
    readonly tools: PluginCheckTool[];
    /** `RequiredAccess` of the manifest: `{hosts[], commands[], files[]}`. */
    readonly access: JsonValue;
    /** The connection form in words ("Host, port, virtual host, user and password") or its field titles. */
    readonly configPreview: JsonValue;
    /**
     * The OS sandbox the plugin's process will run in here (`landlock`, `sandbox-exec`), null where this system has
     * none ("Not sandboxed on this system", ARCHITECTURE §7.5); absent from a core before the K4 review.
     */
    readonly sandbox?: string | null;
  };
}

/**
 * `GET /api/plugins/{id}/update-preview?to=`: the same checks as an install plus what changes in the manifest, as a
 * diff of hunks the kit's `gw-diff` draws (§7.5: side-by-side update, new tools start Off).
 */
export interface PluginUpdatePreview {
  readonly id: string;
  readonly name: string;
  readonly from: string;
  readonly to: string;
  readonly signed: boolean;
  readonly package: { readonly file: string; readonly size: number; readonly sha256: string } | null;
  /** "1 tool added · annotations, hosts and commands unchanged". */
  readonly summary: string | null;
  readonly diff: Diff;
  readonly toolsAdded: string[];
  readonly toolsRemoved: string[];
}

@Injectable({ providedIn: 'root' })
export class PluginsApi {
  private readonly api = inject(ApiClient);

  catalog(): Promise<CatalogIndex> {
    return this.api.get('/api/catalog');
  }

  refreshCatalog(): Promise<CatalogIndex> {
    return this.api.post('/api/catalog/refresh');
  }

  /** From a URL or a catalog entry (`id@version`). */
  check(source: { readonly url: string } | { readonly catalog: string }): Promise<PluginCheck> {
    return this.api.post('/api/plugins/check', source);
  }

  /** A JAR from this computer, as multipart (`file`, ≤ 50 MB): the core stages and checks it before anything loads. */
  checkFile(file: File): Promise<PluginCheck> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.api.post('/api/plugins/check', form);
  }

  /** 422 for an unsigned plugin without `acknowledgeUnsigned`. */
  install(stagingId: string, acknowledgeUnsigned = false, body: WithReason = {}): Promise<JsonObject> {
    return this.api.post('/api/plugins/install', { stagingId, acknowledgeUnsigned, ...body });
  }

  updatePreview(id: string, to: string): Promise<PluginUpdatePreview> {
    return this.api.get(ApiClient.path`/api/plugins/${id}/update-preview`, { to });
  }

  action(id: string, action: 'update' | 'enable' | 'disable' | 'restart' | 'rollback', body: JsonObject & WithReason = {}): Promise<JsonObject> {
    return this.api.post(`${ApiClient.path`/api/plugins/${id}`}/${action}`, body);
  }

  uninstall(id: string, body: WithReason = {}): Promise<void> {
    return this.api.delete(ApiClient.path`/api/plugins/${id}`, body);
  }
}

/** The review of an external MCP server before it first starts (step C10): the command as it will run. */
export interface McpServerReview {
  /** The command as it will run (resolved: `node …/dist/index.js` for a catalog server), shown as Command. */
  readonly argv: string[];
  readonly resolvedCommand: string;
  readonly node: string | null;
  /** Names of the variables (or headers) that reach the process; never values. */
  readonly env: string[];
  readonly notPinned: boolean;
  readonly catalogEntry: string | null;
  readonly protocol: string | null;
  /** The OS sandbox the process runs in (`landlock`, `sandbox-exec`), or null where this system has none (§7.5). */
  readonly sandbox: string | null;
  /** The server's name (a catalog review names it: `github-mcp`), its transport and, over HTTP, its URL. */
  readonly name?: string | null;
  readonly transport?: 'stdio' | 'http' | null;
  readonly url?: string | null;
}

/**
 * A variable (stdio) or header (HTTP) whose value is a secret of the server. The core takes no existing secret by name
 * (C10: a server's values are its own secrets, stored with it): every value travels in `secrets` by JSON Pointer.
 */
export interface McpSecretBinding {
  readonly name: string;
}

/**
 * What "Add MCP server" sends (the `mcp-server` connection type's config, §7.5): the command as argv, never a shell
 * line; the values travel beside it in `secrets` by JSON Pointer (`/env/0/secretRef`), as a connection's do — only
 * when the server is added (`POST /api/mcp-servers`), never to its review.
 */
export interface McpServerConfig {
  readonly name: string;
  readonly transport: 'stdio' | 'http';
  readonly command?: string[];
  readonly url?: string;
  readonly env?: McpSecretBinding[];
  readonly headers?: McpSecretBinding[];
}

/** A server typed in Add MCP server, or a signed catalog entry (`github-mcp@0.6.2`); `secrets` only to add it. */
export type McpServerSource =
  | { readonly config: McpServerConfig; readonly secrets?: SecretInputs }
  | { readonly catalog: string; readonly secrets?: SecretInputs };

/** What starting, restarting or adding a server answers: its tool count, before and after a restart. */
export interface McpServerStarted {
  readonly name: string;
  readonly state?: string | null;
  readonly tools?: number | null;
  readonly previousTools?: number | null;
}

@Injectable({ providedIn: 'root' })
export class McpServersApi {
  private readonly api = inject(ApiClient);

  review(source: McpServerSource): Promise<McpServerReview> {
    return this.api.post('/api/mcp-servers/review', source);
  }

  create(source: McpServerSource & { readonly acknowledge: true } & WithReason): Promise<McpServerStarted> {
    return this.api.post('/api/mcp-servers', source);
  }

  action(name: string, action: 'test' | 'start' | 'restart' | 'stop'): Promise<McpServerStarted> {
    return this.api.post(`${ApiClient.path`/api/mcp-servers/${name}`}/${action}`);
  }

  /** The snapshot of the server's tools: "Declared by <server>, not verified". */
  tools(name: string): Promise<JsonObject[]> {
    return this.api.get(ApiClient.path`/api/mcp-servers/${name}/tools`);
  }

  delete(name: string, body: WithReason = {}): Promise<void> {
    return this.api.delete(ApiClient.path`/api/mcp-servers/${name}`, body);
  }
}
