import { Decision, Env, Iso } from '../api/types';

/**
 * The events of `GET /api/events` (ARCHITECTURE.md §6) with the payloads the core publishes today
 * (approvals.internal.ApprovalTimers, audit.AuditWriter, connections.ConnectionTester, platform.IntegrityAlerts,
 * secrets.MasterKeyService, admin.settings.SettingsController). Events of steps not merged yet are typed by the
 * contract; unknown fields pass through.
 */
export interface GatewayEvents {
  'approval.requested': {
    readonly id: string;
    readonly endpoint: string;
    readonly connection: string | null;
    readonly env: Env;
    readonly tool: string;
    readonly summary: string | null;
    readonly commentPolicy: string;
    readonly expiresAt: Iso;
  };
  'approval.resolved': { readonly id: string; readonly status: string; readonly via: string | null };
  /** One audit row of a call (Live in Activity). Note: the label is `token` here, `tokenLabel` in the REST API. */
  'audit.call': {
    readonly id: number;
    readonly callId: string;
    readonly at: Iso;
    readonly phase: string;
    readonly decision: Decision;
    readonly tool: string;
    readonly connection: string | null;
    readonly env: Env | null;
    readonly client: string | null;
    readonly endpoint: string;
    readonly token: string | null;
    readonly durationMs: number | null;
    readonly rule: string | null;
    readonly approvalId: string | null;
    readonly traceId: string | null;
  };
  'connection.health': {
    readonly name: string;
    readonly env: Env;
    readonly state: string;
    readonly latencyMs?: number;
    readonly message: string | null;
    readonly checkedAt: Iso;
  };
  'integrity.alert': { readonly kind: string; readonly id: string; readonly label: string; readonly detail: string };
  'master-key.rotation': { readonly state: string; readonly done: number; readonly total: number };
  'core.restart-required': { readonly port: number; readonly reason: string };
  'tunnel.state': { readonly state: string; readonly host?: string | null; readonly endpoint?: string | null };
  'oauth.consent.requested': { readonly id: string; readonly [key: string]: unknown };
  'oauth.consent.resolved': { readonly id: string; readonly decision?: string };
  /** A grant's token exchange, authenticated use or revocation changed the OAuth connection. */
  'oauth.grant.changed': { readonly id: string; readonly endpoint: string | null; readonly [key: string]: unknown };
  'notify.state': { readonly channel: string; readonly state: string; readonly [key: string]: unknown };
  /**
   * A desktop notification the core rendered (notifications.internal.DesktopNotices, ARCHITECTURE.md §7.4): Electron
   * main shows it as an OS notification while the window is in the background; in front, the window shows its own
   * toast (shell/desktop-notice-toasts.ts). Texts are the core's, already masked and cut.
   */
  'notify.desktop': {
    readonly kind: 'request' | 'summary' | 'test';
    readonly id: string;
    readonly env?: Env;
    readonly prod?: boolean;
    readonly title: string;
    readonly subtitle?: string | null;
    readonly body?: string | null;
    readonly route?: string;
    readonly expiresAt?: Iso | null;
    readonly [key: string]: unknown;
  };
  'notify.link.updated': { readonly id: string; readonly [key: string]: unknown };
  /**
   * "Open in app" from Slack or Telegram (notifications.internal Slack/TelegramChannel): the path to show,
   * `/approvals/ap_…` or `/approvals`. Electron's main opens it itself; the window acts on it only in a browser.
   */
  'ui.open': { readonly path?: string };
  'plugin.state': { readonly id: string; readonly state: string; readonly [key: string]: unknown };
  'mcp-server.state': { readonly name: string; readonly state: string; readonly [key: string]: unknown };
  'clients.drift': { readonly client: string; readonly [key: string]: unknown };
}

export type GatewayEventType = keyof GatewayEvents;
