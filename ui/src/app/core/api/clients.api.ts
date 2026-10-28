import { Injectable, inject } from '@angular/core';
import { ApiClient } from './api-client';
import { isApiError } from './api-error';
import { Diff, DiffLine } from './approvals.api';
import { Env, Iso, WithReason } from './types';

/**
 * The Clients screen (ARCHITECTURE.md §6 clients; step K1: `admin.clients.ClientsController`, `clients.ClientViews`).
 * No answer ever carries a token: a named token goes from the core straight into the client's file, a bridge token
 * straight to the broker.
 */

export type ClientId = 'claude-desktop' | 'codex' | 'cursor' | 'antigravity';
export type ClientStatus = 'connected' | 'detected' | 'not-installed' | 'needs-restart' | 'error';
/** `.mcpb` bridge, stdio bridge entry, `http_headers_helper`, named token in the file, sign-in through the browser. */
export type ClientMethodId = 'mcpb' | 'config-bridge' | 'helper' | 'token' | 'oauth';
/** A link's own state; anything but `connected` and `needs-restart` puts the card in `error`. */
export type ClientLinkStatus = 'connected' | 'needs-restart' | 'removed-outside' | 'needs-repair' | 'revoked' | 'error';

/** One way to connect a client; `reason` says why it isn't offered on prod endpoints. */
export interface ClientMethod {
  readonly id: ClientMethodId;
  readonly label: string;
  readonly prodAllowed: boolean;
  readonly reason: string | null;
}

/**
 * One endpoint of one client. Usually an entry GateRook wrote; an OAuth link with a null configPath is an
 * observed browser sign-in instead. Its id is the OAuth grant id, and it is managed through OAuth grants, never
 * the managed-config disconnect API.
 */
export interface ClientLink {
  readonly id: string;
  readonly client: ClientId;
  readonly endpoint: string;
  readonly env: Env;
  readonly method: ClientMethodId;
  readonly entryName: string | null;
  readonly configPath: string | null;
  /** The path with `~` (`~/.codex/config.toml`). */
  readonly configDisplay: string | null;
  /** `gr_Cx7q…`: lists show a grant's prefix only. */
  readonly grantPrefix: string | null;
  /** The trusted label of the client's access (`codex-laptop`). */
  readonly grantLabel: string | null;
  /** A named token's name (`cursor-laptop`), for the token method. */
  readonly tokenName: string | null;
  readonly status: ClientLinkStatus;
  readonly statusText: string;
  readonly createdAt: Iso;
  readonly usedAt: Iso | null;
  readonly mcpbPath: string | null;
  /** From the audit: the last call through this entry, and the protocol revision it spoke. */
  readonly lastCallAt: Iso | null;
  readonly revision: string | null;
}

/** One client card. */
export interface ClientCard {
  readonly client: ClientId;
  readonly name: string;
  readonly status: ClientStatus;
  readonly statusText: string;
  /** The user-level config file (absolute); `configDisplay` is the same with `~`. */
  readonly configPath: string | null;
  readonly configDisplay: string | null;
  readonly detected: boolean;
  /** What showed the client on this computer (`~/.codex`, `/Applications/Cursor.app`). */
  readonly evidence: string[];
  readonly version: string | null;
  /** "Codex is older than 0.148.0: the helper is ignored" and the like; for an `error` card, what failed. */
  readonly warnings: string[];
  /** How it can be connected, in the order offered: the first one allowed for the endpoint is the default. */
  readonly methods: ClientMethod[];
  readonly links: ClientLink[];
}

/** `GET /api/clients`: when this computer was last scanned, and one card per client. */
export interface ClientsView {
  readonly scannedAt: Iso | null;
  readonly clients: ClientCard[];
}

/** One line of a config diff as the clients module sends it (`oldNo`/`newNo` are 1-based). */
export interface ClientDiffLine {
  readonly type: 'context' | 'added' | 'removed';
  readonly oldNo: number | null;
  readonly newNo: number | null;
  readonly text: string;
}

export interface ClientDiff {
  readonly added: number;
  readonly removed: number;
  readonly hunks: { readonly header: string; readonly lines: ClientDiffLine[] }[];
}

/** One file a preview would write. */
export interface ClientFileChange {
  readonly path: string;
  readonly display: string;
  readonly realPath: string;
  readonly exists: boolean;
  readonly kind: 'config' | 'mcpb' | 'restore';
  readonly diff: ClientDiff | null;
  /** Where the copy goes before the write. */
  readonly backupPath: string | null;
  /** "0644 → 0600: the file now holds a token". */
  readonly modeChange: string | null;
}

/** "What will change": the files and their exact diffs; nothing is written until Confirm. */
export interface ConnectPreview {
  readonly previewId: string;
  readonly client: ClientId;
  readonly endpoint: string;
  readonly env: Env;
  readonly method: ClientMethodId;
  /** The client already has an entry for this endpoint; Confirm writes it again. */
  readonly repair: boolean;
  readonly files: ClientFileChange[];
  readonly notes: string[];
  readonly warnings: string[];
  /** An "Add to Cursor" deep link carrying only the endpoint URL. */
  readonly link: string | null;
  readonly expiresAt: Iso | null;
}

export interface ConnectRequest {
  readonly endpoint: string;
  readonly method: ClientMethodId;
  /** The access label of a grant (`codex-laptop`). */
  readonly grantLabel?: string | null;
  readonly token?: { readonly name: string; readonly ttlDays: number | null } | null;
  /** Codex: `default_tools_approval_mode` (`writes` or `prompt`) and `tool_timeout_sec`. */
  readonly codex?: { readonly approvalMode?: string | null; readonly toolTimeoutSec?: number | null } | null;
}

export interface ConnectResult {
  readonly link: ClientLink;
  /** The extension Electron opens in Claude Desktop. */
  readonly mcpbPath: string | null;
  readonly notes: string[];
}

/** Enables endpoint OAuth and returns the install link without editing any client configuration. */
export interface BrowserSignIn {
  readonly link: string;
}

export interface DisconnectResult {
  readonly linkId: string;
  readonly entryRemoved: boolean;
  readonly rejectedApprovals: number;
  readonly notes: string[];
}

export interface ClientCheckStep {
  readonly id: string;
  readonly title: string;
  readonly state: 'ok' | 'warning' | 'error' | 'skipped';
  readonly text: string;
  readonly ms: number | null;
}

/** The steps of Check (report 03 §5). */
export interface ClientCheck {
  readonly linkId: string;
  readonly summary: string;
  readonly steps: ClientCheckStep[];
}

export interface ClientBackup {
  readonly id: string;
  readonly file: string;
  readonly createdAt: Iso;
  readonly sizeBytes: number;
}

export interface BridgeRemoved {
  readonly revoked: string[];
  readonly removedFiles: string[];
  readonly rejectedApprovals: number;
  readonly notes: string[];
}

/** Manual setup: the endpoint URL and snippets with `‹token›` in place of a token. */
export interface ManualSetup {
  readonly endpoint: string;
  readonly url: string;
  readonly transport: string;
  readonly others: { readonly slug: string; readonly url: string }[];
  readonly snippets: { readonly id: string; readonly title: string; readonly language: string; readonly text: string }[];
}

/**
 * The 422 problem+json of a config file the core won't edit (K1 `ClientsController.EditProblem`): `reason` is a code
 * (`invalid`, `jsonc`, `unexpected-shape`, `inline-table`…), `detail` the sentence to show, `line`/`column` where
 * (0 when unknown), `fragment` the entry to add by hand (null when the core can't give one). Read it from a failed
 * call with {@link configEditProblem}.
 */
export interface ConfigEditProblem {
  readonly reason: string;
  readonly detail: string | null;
  readonly line: number;
  readonly column: number;
  readonly fragment: string | null;
}

/** The {@link ConfigEditProblem} of a failed connect/disconnect preview, or null for any other failure. */
export function configEditProblem(e: unknown): ConfigEditProblem | null {
  if (!isApiError(e) || e.status !== 422 || !e.problem) return null;
  const p = e.problem;
  if (typeof p['reason'] !== 'string') return null;
  const text = (v: unknown) => (typeof v === 'string' && v !== '' ? v : null);
  const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
  return { reason: p['reason'], detail: text(p['detail']), line: num(p['line']), column: num(p['column']), fragment: text(p['fragment']) };
}

/** The clients module's diff as `gw-diff` draws it (the approval diff's shape). */
export function toKitDiff(d: ClientDiff): Diff {
  const t = (type: ClientDiffLine['type']): DiffLine['t'] => (type === 'added' ? 'add' : type === 'removed' ? 'del' : 'context');
  return {
    added: d.added,
    removed: d.removed,
    hunks: d.hunks.map((h) => ({ header: h.header, lines: h.lines.map((l) => ({ t: t(l.type), v: l.text })) })),
  };
}

@Injectable({ providedIn: 'root' })
export class ClientsApi {
  private readonly api = inject(ApiClient);

  list(): Promise<ClientsView> {
    return this.api.get('/api/clients');
  }

  scan(): Promise<ClientsView> {
    return this.api.post('/api/clients/scan');
  }

  /** 422 with {@link ConfigEditProblem} fields when the file can't be edited safely. */
  connectPreview(client: ClientId, request: ConnectRequest): Promise<ConnectPreview> {
    return this.api.post(ApiClient.path`/api/clients/${client}/connect/preview`, request);
  }

  browserSignIn(client: ClientId, endpoint: string): Promise<BrowserSignIn> {
    return this.api.post(ApiClient.path`/api/clients/${client}/browser-sign-in`, { endpoint });
  }

  /** 409 when the file changed after the preview: preview again. Claude Desktop answers the `.mcpb` path. */
  connect(client: ClientId, previewId: string, body: WithReason = {}): Promise<ConnectResult> {
    return this.api.post(ApiClient.path`/api/clients/${client}/connect`, { previewId, ...body });
  }

  disconnectPreview(linkId: string): Promise<ConnectPreview> {
    return this.api.post(ApiClient.path`/api/clients/links/${linkId}/disconnect/preview`);
  }

  disconnect(linkId: string, previewId: string, body: WithReason = {}): Promise<DisconnectResult> {
    return this.api.post(ApiClient.path`/api/clients/links/${linkId}/disconnect`, { previewId, ...body });
  }

  check(linkId: string): Promise<ClientCheck> {
    return this.api.post(ApiClient.path`/api/clients/links/${linkId}/check`);
  }

  backups(client: ClientId): Promise<ClientBackup[]> {
    return this.api.get(ApiClient.path`/api/clients/${client}/backups`);
  }

  restorePreview(client: ClientId, backupId: string): Promise<ConnectPreview> {
    return this.api.post(ApiClient.path`/api/clients/${client}/restore/preview`, { backupId });
  }

  restore(client: ClientId, previewId: string, body: WithReason = {}): Promise<{ readonly client: ClientId; readonly path: string; readonly backupId: string }> {
    return this.api.post(ApiClient.path`/api/clients/${client}/restore`, { previewId, ...body });
  }

  removeClaudeBridge(): Promise<BridgeRemoved> {
    return this.api.post('/api/clients/claude-desktop/remove-bridge');
  }

  manual(endpoint: string): Promise<ManualSetup> {
    return this.api.get('/api/clients/manual', { endpoint });
  }
}
