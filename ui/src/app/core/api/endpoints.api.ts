import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Env, Iso, JsonObject, JsonValue, ToolAnnotations, ToolMode, WithReason } from './types';

export type RiskLevel = 'present' | 'limited' | 'absent';

/** The risky combination of one endpoint (endpoints.RiskAnalyzer). */
export interface EndpointRisk {
  /** `present` (3 of 3), `partial`, `limited`, `contained`, `dismissed`, `none`. */
  readonly state: string;
  /** The state under a dismissal. */
  readonly underlying: string | null;
  readonly present: string[];
  readonly ingredients: { readonly privateData: RiskLevel; readonly untrustedContent: RiskLevel; readonly externalChannel: RiskLevel };
  readonly sources: { readonly ingredient: string; readonly tool: string; readonly connection: string; readonly text: string }[];
  readonly published: boolean;
  readonly dismissed: JsonValue;
}

/** One endpoint of the Endpoints list (admin.endpoints.EndpointDtos.Summary). */
export interface EndpointSummary {
  readonly slug: string;
  readonly description: string | null;
  readonly url: string;
  readonly env: Env;
  readonly access: 'standard' | 'read-only';
  readonly status: 'running' | 'disabled';
  readonly tools: { readonly read: number; readonly approval: number; readonly off: number };
  readonly connections: { readonly total: number; readonly byEnv: { readonly [env: string]: number } };
  readonly health: { readonly [state: string]: number };
  readonly approvalsWaiting: number;
  readonly clients: string[];
  readonly tokens: { readonly active: number; readonly expiringSoon: number };
  readonly published: boolean;
  readonly risk: EndpointRisk;
  /** Why the endpoint is off-limits (changed outside MCP Gateway), when it is. */
  readonly blockedReason: string | null;
  readonly version: number;
}

export interface WiredConnection {
  readonly name: string;
  readonly type: string;
  readonly typeName: string;
  readonly env: Env;
  readonly health: string;
  readonly problem: string | null;
  readonly tools: { readonly read: number; readonly approval: number; readonly off: number };
}

export interface EndpointSettings {
  readonly description: string | null;
  readonly access: 'standard' | 'read-only';
  readonly instructions: string | null;
  readonly instructionsMax: number;
  readonly limits: JsonObject;
  readonly protocol: JsonObject;
  readonly approvalWait: JsonObject;
  readonly oauthEnabled: boolean;
  readonly version: number;
  readonly usage: { readonly callsLastMinute: number; readonly rateLimitPerToken: number; readonly rateLimitPerTool: number };
  readonly revisions: { readonly rev: string; readonly served: boolean; readonly clients: string[]; readonly calls: number }[];
  /**
   * The instructions the gateway writes into `initialize` when `instructions` is empty (`mcp/internal/Instructions`):
   * what Settings › Instructions shows in the empty field (step API).
   */
  readonly defaultInstructions?: string | null;
}

/** The ways to start a new endpoint (`POST /api/endpoints` `startFrom`). */
export type StartFrom = 'empty' | 'read-only-dev' | 'incident-response';

/**
 * One "Start from" card of New endpoint (`GET /api/endpoint-starts`, step API): what the endpoint would start with
 * now, counted as its list row counts after Create ("Read-only dev · 6 dev connections · 12 tools").
 */
export interface EndpointStart {
  readonly startFrom: StartFrom;
  readonly env: Env;
  readonly access: 'standard' | 'read-only';
  /** The connections it would wire (external MCP servers never: the person adds those). */
  readonly connections: string[];
  /** Its pairs by mode, the core's own tools included (read + approval are on). */
  readonly tools: { readonly read: number; readonly approval: number; readonly off: number };
}

/** `GET /api/endpoints/{slug}`: header, wiring, risk, settings. */
export interface EndpointDetail {
  readonly endpoint: EndpointSummary;
  readonly connections: WiredConnection[];
  readonly settings: EndpointSettings;
}

/** One tool × connection pair of the tool-policy-list. */
export interface ToolRow {
  readonly toolKey: string;
  readonly name: string;
  readonly annotations: ToolAnnotations;
  /** External MCP server or plugin that declared the annotations ("Declared by github-mcp, not verified"). */
  readonly declaredBy: string | null;
  readonly mode: ToolMode;
  readonly storedMode: ToolMode;
  readonly readOnlyAllowed: boolean;
  /** Why Read only is unavailable or the mode is locked. */
  readonly reason: string | null;
  readonly readOnlyConfirmed: boolean;
  readonly definitionHash: string | null;
  readonly overrides: JsonObject | null;
  readonly version: number;
}

export interface ToolGroup {
  readonly group: string;
  readonly connection: string | null;
  readonly env: Env | null;
  readonly tools: ToolRow[];
}

export interface ConnectionPreview {
  readonly connection: string;
  readonly env: Env;
  readonly tools: { readonly toolKey: string; readonly tool: string; readonly mode: ToolMode; readonly locked: string | null; readonly declaredBy: string | null }[];
  readonly warnings: string[];
  /** "Accepts prod connections only" and the like: the connection can't be added. */
  readonly refusal: string | null;
}

export interface SplitPreview {
  readonly newSlug: string;
  readonly before: EndpointRisk;
  readonly remaining: EndpointRisk;
  readonly moved: EndpointRisk;
  readonly movedTools: string[];
}

export interface EndpointCheck {
  readonly tools: number;
  readonly revisions: number;
  readonly instructionsChars: number;
  readonly instructionsMax: number;
  readonly gatewayP95Ms: number | null;
  readonly problems: string[];
  /** "18 tools · 2 revisions · instructions 765 / 4,000 · p95 overhead 3 ms". */
  readonly text: string;
}

export interface EndpointSettingsUpdate extends WithReason {
  readonly description?: string | null;
  readonly access?: 'standard' | 'read-only';
  readonly instructions?: string | null;
  readonly limits?: JsonObject;
  readonly protocol?: JsonObject;
  readonly approvalWait?: JsonObject;
  readonly oauthEnabled?: boolean;
  readonly version: number;
  readonly confirm?: string | null;
}

@Injectable({ providedIn: 'root' })
export class EndpointsApi {
  private readonly api = inject(ApiClient);

  list(): Promise<EndpointSummary[]> {
    return this.api.get('/api/endpoints');
  }

  create(request: { readonly slug: string; readonly description?: string | null; readonly startFrom: StartFrom } & WithReason): Promise<EndpointSummary> {
    return this.api.post('/api/endpoints', request);
  }

  /** What each way to start would give now; 404 from a core before step API. */
  starts(): Promise<EndpointStart[]> {
    return this.api.get('/api/endpoint-starts');
  }

  duplicate(slug: string, newSlug: string, reason?: string | null): Promise<EndpointSummary> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/duplicate`, { slug: newSlug, reason });
  }

  /** Undo = {@link enable}. */
  disable(slug: string, body: WithReason = {}): Promise<EndpointSummary> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/disable`, body);
  }

  enable(slug: string, body: WithReason = {}): Promise<EndpointSummary> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/enable`, body);
  }

  delete(slug: string, confirmSlug: string, reason?: string | null): Promise<{ readonly clients: string[]; readonly tokensRevoked: string[]; readonly rejectedApprovals: number }> {
    return this.api.delete(ApiClient.path`/api/endpoints/${slug}`, { confirmSlug, reason });
  }

  confirmIntegrity(slug: string, body: { readonly confirm?: string | null } & WithReason = {}): Promise<EndpointSummary> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/confirm-integrity`, body);
  }

  get(slug: string): Promise<EndpointDetail> {
    return this.api.get(ApiClient.path`/api/endpoints/${slug}`);
  }

  settings(slug: string): Promise<EndpointSettings> {
    return this.api.get(ApiClient.path`/api/endpoints/${slug}/settings`);
  }

  updateSettings(slug: string, update: EndpointSettingsUpdate): Promise<EndpointSettings> {
    return this.api.put(ApiClient.path`/api/endpoints/${slug}/settings`, update);
  }

  check(slug: string): Promise<EndpointCheck> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/check`);
  }

  tools(slug: string): Promise<ToolGroup[]> {
    return this.api.get(ApiClient.path`/api/endpoints/${slug}/tools`);
  }

  /** The core answers with every group again (admin.endpoints `setMode`): one change can move other pairs' locks. */
  setMode(
    slug: string,
    request: { readonly toolKey: string; readonly connection: string | null; readonly mode: ToolMode; readonly overrides?: JsonObject | null; readonly version: number } & WithReason,
  ): Promise<ToolGroup[]> {
    return this.api.put(ApiClient.path`/api/endpoints/${slug}/tools`, request);
  }

  /** Answers with every group again, like {@link setMode}. */
  confirmReadOnly(
    slug: string,
    request: { readonly toolKey: string; readonly connection: string | null; readonly definitionHash: string } & WithReason,
  ): Promise<ToolGroup[]> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/tools/confirm-read-only`, request);
  }

  /** "Set all to…": `DEFAULT` restores defaults; mutating and "Declared by" tools are skipped for Read only. */
  bulk(slug: string, request: { readonly group: string; readonly mode: ToolMode | 'DEFAULT' } & WithReason): Promise<{ readonly changed: string[]; readonly skipped: { readonly toolKey: string; readonly reason: string }[] }> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/tools/bulk`, request);
  }

  previewConnection(slug: string, connection: string): Promise<ConnectionPreview> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/connections/preview`, { connection });
  }

  addConnection(slug: string, connection: string, body: WithReason = {}): Promise<EndpointDetail> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/connections/${connection}`, body);
  }

  removeConnection(slug: string, connection: string, body: WithReason = {}): Promise<{ readonly stoppedTools: string[] }> {
    return this.api.delete(ApiClient.path`/api/endpoints/${slug}/connections/${connection}`, body);
  }

  risk(slug: string): Promise<EndpointRisk> {
    return this.api.get(ApiClient.path`/api/endpoints/${slug}/risk`);
  }

  /** 422 when there is no risk to dismiss; the reason goes to Setup changes. */
  dismissRisk(slug: string, reason: string): Promise<EndpointRisk> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/risk/dismiss`, { reason });
  }

  restoreRisk(slug: string, body: WithReason = {}): Promise<EndpointRisk> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/risk/restore`, body);
  }

  splitPreview(slug: string, request: { readonly move: string[]; readonly newSlug: string; readonly clients?: string[] }): Promise<SplitPreview> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/split/preview`, request);
  }

  split(slug: string, request: { readonly move: string[]; readonly newSlug: string; readonly clients?: string[] } & WithReason): Promise<EndpointSummary> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/split`, request);
  }
}

/** `named`: a token the person issued; `grant`: a client's grant (bridge / helper) whose tokens are issued on the fly. */
export type TokenKind = 'named' | 'grant';

/**
 * A token row of `GET /api/endpoints/{slug}/tokens` (`endpoints.TokenService.list`): named tokens, then the "issued on
 * the fly" grants (bridge, helper). Never a value. Revoking a named token is `DELETE /api/tokens/{id}`, a grant
 * `DELETE /api/grants/{id}`.
 */
export interface TokenRow {
  readonly id: string;
  readonly kind: TokenKind;
  readonly name: string;
  /** `gwt_K9xP`; null for tokens issued on the fly. */
  readonly prefix: string | null;
  readonly client: string | null;
  readonly createdAt: Iso;
  readonly expiresAt: Iso | null;
  readonly lastUsedAt: Iso | null;
  readonly revokedAt: Iso | null;
  /**
   * Grants are only `active` or `revoked`. The core has no "expiring" state: the kit's warning for an expiry within
   * 7 days is derived from `expiresAt` on the core's clock (`tokenView`).
   */
  readonly state: 'active' | 'expired' | 'revoked';
}

/** The only answer that carries a token value: shown once, then only the prefix exists. */
export interface IssuedToken {
  readonly id: string;
  readonly name: string;
  readonly token: string;
  readonly prefix: string;
  readonly expiresAt: Iso;
}

@Injectable({ providedIn: 'root' })
export class TokensApi {
  private readonly api = inject(ApiClient);

  list(slug: string): Promise<TokenRow[]> {
    return this.api.get(ApiClient.path`/api/endpoints/${slug}/tokens`);
  }

  issue(slug: string, request: { readonly name: string; readonly ttlDays?: number | null } & WithReason): Promise<IssuedToken> {
    return this.api.post(ApiClient.path`/api/endpoints/${slug}/tokens`, request);
  }

  revoke(id: string, body: WithReason = {}): Promise<{ readonly id: string; readonly rejectedApprovals: number }> {
    return this.api.delete(ApiClient.path`/api/tokens/${id}`, body);
  }

  /** Revokes a client's grant (helper / bridge): the token it holds stops working and no new ones are issued. */
  revokeGrant(id: string, body: WithReason = {}): Promise<{ readonly id: string; readonly rejectedApprovals: number }> {
    return this.api.delete(ApiClient.path`/api/grants/${id}`, body);
  }
}
