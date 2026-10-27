import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { Iso, WithReason } from './types';

export type TunnelProviderId = 'cloudflare-quick' | 'cloudflare-named' | 'ngrok';

/** `GET /api/tunnel` (step K3). Until K3 the core reports `off` in health and counters, and this route is 404. */
export interface TunnelState {
  readonly provider: TunnelProviderId | null;
  readonly state: 'off' | 'starting' | 'on' | 'error';
  readonly host: string | null;
  readonly publicUrl: string | null;
  readonly endpoint: string | null;
  readonly since: Iso | null;
  /** Anthropic IP ranges only (Settings › Tunnel › Callers, the single switch). */
  readonly callers: { readonly anthropicOnly: boolean } | null;
  readonly cloudflared: { readonly version: string; readonly sha256Ok: boolean } | null;
  readonly error: string | null;
  readonly history: { readonly from: Iso; readonly to: Iso | null; readonly endpoint: string; readonly calls: number }[];
  /** The reserved ngrok domain of Settings › Tunnel while the provider is ngrok, else null (step API). */
  readonly ngrokDomain?: string | null;
  /**
   * Whether the public address stays the same across starts (a named Cloudflare tunnel, ngrok with a reserved domain):
   * a new public port or a restart leaves claude.ai's URL unchanged (step API).
   */
  readonly stableAddress?: boolean;
}

/**
 * What publishing would make public, and what stays local (`admin.tunnel.TunnelController.Preview`). `local` is in
 * the core's words: "The app and its admin API (127.0.0.1:7465)", then `/mcp/<slug>` of every other endpoint.
 */
export interface PublishPreview {
  readonly endpoint: string;
  readonly publicPaths: string[];
  readonly local: string[];
  readonly connections: { readonly name: string; readonly env: string }[];
  readonly risk: string | null;
  readonly refusal: string | null;
  /** The core's cautions before publishing: changes can be asked for, any caller, DCR on, a quick tunnel's address. */
  readonly warnings?: readonly string[];
}

@Injectable({ providedIn: 'root' })
export class TunnelApi {
  private readonly api = inject(ApiClient);

  get(): Promise<TunnelState> {
    return this.api.get('/api/tunnel');
  }

  publishPreview(endpoint: string): Promise<PublishPreview> {
    return this.api.post('/api/tunnel/publish/preview', { endpoint });
  }

  /** 202: progress arrives as `tunnel.state` events. Undo never publishes again: it always asks first. */
  publish(endpoint: string, body: WithReason = {}): Promise<TunnelState> {
    return this.api.post('/api/tunnel/publish', { endpoint, ...body });
  }

  stop(body: WithReason = {}): Promise<TunnelState> {
    return this.api.post('/api/tunnel/stop', body);
  }

  /** "Reachable · 212 ms via Cloudflare (FRA)" plus warnings. */
  check(): Promise<{ readonly ok: boolean; readonly text: string; readonly warnings: string[] }> {
    return this.api.post('/api/tunnel/check');
  }
}

/** A sign-in waiting for an answer on this computer (OAuth consent, code to compare). */
export interface OAuthConsent {
  readonly id: string;
  readonly code: string;
  readonly clientHost: string;
  readonly redirectHost: string;
  readonly endpoint: string;
  readonly env: string;
  readonly tools: { readonly read: number; readonly approval: number; readonly off: number; readonly bypass?: number };
  readonly ip: string | null;
  readonly expiresAt: Iso;
}

/** "Signed in": an allowed OAuth grant (survives turning the tunnel off). */
export interface OAuthGrant {
  readonly id: string;
  readonly client: string;
  /** The endpoint's slug; null once that endpoint was deleted (`oauth.internal.Grants.list`). */
  readonly endpoint: string | null;
  readonly ingress: 'LOCAL' | 'TUNNEL';
  readonly createdAt: Iso;
  readonly expiresAt: Iso;
  readonly lastUsedAt: Iso | null;
  readonly state: 'active' | 'address-changed' | 'expired' | 'revoked';
}

@Injectable({ providedIn: 'root' })
export class OAuthApi {
  private readonly api = inject(ApiClient);

  consents(): Promise<OAuthConsent[]> {
    return this.api.get('/api/oauth/consents');
  }

  /** 204, or 409 when the consent was already answered or expired. */
  decide(id: string, decision: 'allow' | 'deny'): Promise<void> {
    return this.api.post(ApiClient.path`/api/oauth/consents/${id}`, { decision });
  }

  grants(): Promise<OAuthGrant[]> {
    return this.api.get('/api/oauth/grants');
  }

  revoke(id: string, body: WithReason = {}): Promise<void> {
    return this.api.delete(ApiClient.path`/api/oauth/grants/${id}`, body);
  }
}
