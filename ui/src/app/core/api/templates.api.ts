import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { ApprovalAction, LabelledText } from './approvals.api';
import { Env, Iso, JsonObject, JsonValue, WithReason } from './types';

export type TemplateKind = 'sql-query' | 'ssh-runbook' | 'openapi';

export interface Template {
  readonly id: string;
  readonly kind: TemplateKind;
  readonly name: string;
  /** "Runs on"; null when its connection was removed (status invalid). */
  readonly connection: string | null;
  readonly version: number;
  readonly status: 'active' | 'disabled' | 'invalid';
  readonly catalogRef: string | null;
  /** `{"read": 9, "mutation": 5}`. */
  readonly natures: JsonValue;
  readonly updatedAt: Iso;
  readonly verified: boolean;
  /**
   * The current version's parameters as its definition declares them, in order (`{name, type, …}`): the list's
   * signature, "orders_by_status(status, since)". Null for OpenAPI and for a template that doesn't verify (step API).
   */
  readonly parameters?: TemplateParameter[] | null;
  /**
   * The current version's definition (the SQL and its parameters), on `GET /api/templates/{id}` only, so the editor
   * can open a template and send `PUT {definition, baseVersion}` (§6). A core that doesn't send it can't be edited.
   */
  readonly definition?: JsonObject | null;
}

/** One declared parameter of a template (`params[]` of its definition). */
export interface TemplateParameter {
  readonly name: string;
  readonly type?: string;
  readonly [key: string]: JsonValue | undefined;
}

export interface CompiledTool {
  readonly opKey: string;
  readonly name: string;
  readonly title: string;
  readonly nature: string;
}

export interface TemplateCompile {
  readonly ok: boolean;
  readonly tools: CompiledTool[];
  readonly errors: string[];
  readonly warnings: string[];
  readonly digest: string | null;
}

/** 201 with a saved template, or 200 `{template: null, compile: {ok: false, errors}}`. */
export interface TemplateSaved {
  readonly template: Template | null;
  readonly compile: TemplateCompile;
}

export interface TemplateVersion {
  readonly version: number;
  readonly digest: string;
  readonly natures: JsonValue;
  readonly note: string | null;
  readonly createdAt: Iso;
}

/** What a call with these arguments would run, as an approver sees it. */
export interface ActionPreviewView {
  readonly action: ApprovalAction;
  readonly target: string | null;
  readonly summary: string | null;
  readonly fields: LabelledText[];
  readonly impact: LabelledText[];
  readonly warnings: string[];
}

export interface CreateTemplateRequest extends WithReason {
  readonly kind: TemplateKind;
  readonly connection: string;
  readonly name?: string;
  readonly definition?: JsonObject;
  readonly note?: string | null;
  /** A catalog template (K6); 422 until the catalog exists. */
  readonly catalogRef?: string | null;
}

export interface OpenApiSource {
  readonly url?: string;
  readonly text?: string;
  readonly blob?: string;
}

export interface OpenApiInspection {
  readonly ok: boolean;
  readonly blob: string | null;
  readonly info: {
    readonly title: string;
    readonly openapi: string;
    readonly sha256: string;
    readonly servers: string[];
    readonly authentication: { readonly name: string; readonly type: string; readonly supported: boolean; readonly config?: JsonObject }[];
  } | null;
  readonly errors: string[];
}

export interface OpenApiOperation {
  readonly opKey: string;
  readonly name: string | null;
  readonly method: string;
  readonly path: string;
  readonly summary: string;
  readonly tags: string[];
  readonly nature: string;
  readonly selected: boolean;
  readonly skipped: string | null;
}

export interface OpenApiParseRequest extends OpenApiSource {
  readonly connection?: string;
  readonly draft?: { readonly name: string; readonly env: Env; readonly config: JsonObject };
  readonly name?: string;
  readonly prefix?: string;
  readonly server?: string;
  readonly select?: string[];
}

export interface OpenApiParsed {
  readonly ok: boolean;
  readonly definition: JsonObject | null;
  readonly operations: OpenApiOperation[];
  readonly info: JsonObject | null;
  readonly errors: string[];
  readonly warnings: string[];
  readonly source: { readonly kind: string; readonly url: string | null; readonly bytes: number; readonly sha256: string | null };
}

@Injectable({ providedIn: 'root' })
export class TemplatesApi {
  private readonly api = inject(ApiClient);

  list(kind?: TemplateKind | null): Promise<Template[]> {
    return this.api.get('/api/templates', { kind });
  }

  get(id: string): Promise<Template> {
    return this.api.get(ApiClient.path`/api/templates/${id}`);
  }

  create(request: CreateTemplateRequest): Promise<TemplateSaved> {
    return this.api.post('/api/templates', request);
  }

  update(
    id: string,
    request: { readonly definition: JsonObject; readonly baseVersion: number; readonly note?: string | null } & WithReason,
  ): Promise<TemplateSaved> {
    return this.api.put(ApiClient.path`/api/templates/${id}`, request);
  }

  preview(id: string, args: JsonObject): Promise<ActionPreviewView> {
    return this.api.post(ApiClient.path`/api/templates/${id}/preview`, { arguments: args });
  }

  versions(id: string): Promise<TemplateVersion[]> {
    return this.api.get(ApiClient.path`/api/templates/${id}/versions`);
  }

  enable(id: string, body: WithReason = {}): Promise<void> {
    return this.api.post(ApiClient.path`/api/templates/${id}/enable`, body);
  }

  disable(id: string, body: WithReason = {}): Promise<void> {
    return this.api.post(ApiClient.path`/api/templates/${id}/disable`, body);
  }

  confirmIntegrity(id: string, body: WithReason = {}): Promise<Template> {
    return this.api.post(ApiClient.path`/api/templates/${id}/confirm-integrity`, body);
  }

  delete(id: string, body: WithReason = {}): Promise<void> {
    return this.api.delete(ApiClient.path`/api/templates/${id}`, body);
  }

  /** Inspect one snapshot of a document before creating a connection. */
  inspectOpenApi(request: OpenApiSource): Promise<OpenApiInspection> {
    return this.api.post('/api/templates/openapi/inspect', request);
  }

  parseOpenApi(request: OpenApiParseRequest): Promise<OpenApiParsed> {
    return this.api.post('/api/templates/openapi/parse', request);
  }
}
