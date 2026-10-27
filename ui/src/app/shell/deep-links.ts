import { SETTINGS_GROUPS } from '../kit/elements/settings-layout/settings-sections';

/** `ap_` + 12 lower-case letters and digits: the core's approval ids (`GET /approvals/{id}` checks the same). */
const APPROVAL_ID = /^ap_[a-z0-9]{12}$/;
/** Activity event ids are the audit row's number. */
const EVENT_ID = /^[0-9]{1,18}$/;
const SETTINGS_SECTIONS: ReadonlySet<string> = new Set(SETTINGS_GROUPS.flatMap((g) => g.items.map((i) => i.id)));

/**
 * Where a deep link may lead (ARCHITECTURE.md §7.1 "deep links allow navigation only", §7.4 "Review" and "Open in
 * app"): `mcp-gateway://approvals/ap_…`, `mcp-gateway://activity/events/<id>` and `mcp-gateway://settings/<section>`,
 * with or without the scheme. Anything else — another path, a query, a fragment, an unknown section, odd characters —
 * is ignored: a link from outside the app only ever opens one of these screens, it never acts.
 */
export function deepLinkUrl(link: string): string | null {
  if (typeof link !== 'string' || link.length > 200) return null;
  const path = link.replace(/^mcp-gateway:\/\//, '').replace(/^\/+/, '');
  const parts = path.split('/');
  if (parts[0] === 'approvals' && parts.length === 2 && APPROVAL_ID.test(parts[1])) return `/approvals/${parts[1]}`;
  if (parts[0] === 'activity' && parts.length === 3 && parts[1] === 'events' && EVENT_ID.test(parts[2])) {
    return `/activity#event-${parts[2]}`;
  }
  if (parts[0] === 'settings' && parts.length === 2 && SETTINGS_SECTIONS.has(parts[1])) return `/settings/${parts[1]}`;
  return null;
}

/** The core's `ui.open` event ("Open in app" in Slack or Telegram): an approval id or a route, through the same allowlist. */
export function uiOpenUrl(data: { readonly route?: unknown; readonly approvalId?: unknown } | null | undefined): string | null {
  if (!data || typeof data !== 'object') return null;
  if (typeof data.approvalId === 'string') return deepLinkUrl(`approvals/${data.approvalId}`);
  if (typeof data.route === 'string') return deepLinkUrl(data.route);
  return null;
}
