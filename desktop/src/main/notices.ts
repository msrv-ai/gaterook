// Desktop notifications of approval requests (ARCHITECTURE.md §7.4 "Notification implementation", A16; report 13 §7). The core renders
// them (`notify.desktop` on its event stream: text already cut to macOS's 256 bytes, masked, urgency, sound) so every
// channel has one renderer; main decides only where one goes and what its buttons do:
//   - the window has focus → no OS notification (the window shows the queue and its counter);
//   - otherwise an OS notification with Review and Deny — never Approve: a decision needs the exact action on
//     screen, and the core refuses an Approve that claims to come from a notification anyway;
//   - Review opens the request (or the queue for a summary); Deny is `POST /api/approvals/{id}/decision
//     {decision:"deny", via:"desktop-notification"}`.

export interface NoticeAction {
  readonly id: 'review' | 'deny';
  readonly text: string;
}

export interface DesktopNotice {
  readonly kind: 'request' | 'summary' | 'test';
  readonly id: string;
  readonly group: string | null;
  readonly prod: boolean;
  readonly title: string;
  readonly subtitle: string | null;
  readonly body: string | null;
  readonly footer: string | null;
  readonly urgency: 'normal' | 'critical';
  readonly silent: boolean;
  readonly route: string;
  readonly actions: readonly NoticeAction[];
  readonly pendingCount: number | null;
}

/** The core's approval ids: the only ones Deny ever sends. */
export const APPROVAL_ID = /^ap_[a-z0-9]{12}$/;

function text(v: unknown, max: number): string | null {
  return typeof v === 'string' && v.length > 0 ? v.slice(0, max) : null;
}

/**
 * The event as main trusts it: known kinds, bounded texts, only Review and Deny among the buttons (an `approve`
 * the core would never send is dropped all the same), Deny only for a real request id.
 */
export function parseNotice(data: unknown): DesktopNotice | null {
  if (typeof data !== 'object' || data === null) return null;
  const d = data as Record<string, unknown>;
  const kind = d['kind'];
  if (kind !== 'request' && kind !== 'summary' && kind !== 'test') return null;
  const id = text(d['id'], 64);
  const title = text(d['title'], 256);
  if (!id || !title || !/^[A-Za-z0-9_.-]+$/.test(id)) return null;
  const actions: NoticeAction[] = [];
  for (const a of Array.isArray(d['actions']) ? d['actions'] : []) {
    const action = a as Record<string, unknown> | null;
    const actionId = action?.['id'];
    if ((actionId === 'review' || actionId === 'deny') && !actions.some((x) => x.id === actionId)) {
      if (actionId === 'deny' && (kind !== 'request' || !APPROVAL_ID.test(id))) continue;
      actions.push({ id: actionId, text: text(action?.['text'], 32) ?? (actionId === 'review' ? 'Review' : 'Deny') });
    }
  }
  if (!actions.some((a) => a.id === 'review')) actions.unshift({ id: 'review', text: 'Review' });
  // Review first: it is the notification's main button on macOS, Deny the one under "Options".
  actions.sort((a, b) => (a.id === 'review' ? -1 : b.id === 'review' ? 1 : 0));
  const pending = d['pendingCount'];
  return {
    kind,
    id,
    group: text(d['group'], 64),
    prod: d['prod'] === true,
    title,
    subtitle: text(d['subtitle'], 256),
    body: text(d['body'], 1024),
    footer: text(d['footer'], 256),
    urgency: d['urgency'] === 'critical' ? 'critical' : 'normal',
    silent: d['silent'] !== false,
    route: typeof d['route'] === 'string' ? d['route'] : '/approvals',
    actions,
    pendingCount: Number.isSafeInteger(pending) && (pending as number) >= 0 ? (pending as number) : null,
  };
}

/** Where a notice goes now. */
export function placement(windowFocused: boolean, supported: boolean): 'window' | 'os' | 'unsupported' {
  if (windowFocused) return 'window';
  return supported ? 'os' : 'unsupported';
}

/** The options of Electron's Notification for a notice on this platform (pure: the glue passes them on). */
export interface NotificationSpec {
  readonly title: string;
  readonly subtitle?: string;
  readonly body: string;
  readonly silent: boolean;
  readonly urgency: 'normal' | 'critical';
  readonly timeoutType: 'never' | 'default';
  readonly actions: readonly { readonly type: 'button'; readonly text: string }[];
  readonly closeButtonText?: string;
  readonly groupId?: string;
}

export function notificationSpec(n: DesktopNotice, platform: NodeJS.Platform): NotificationSpec {
  const body = n.body ?? '';
  // macOS shows the subtitle on its own line and has no room for a footer; elsewhere both go into the body.
  const lines = platform === 'darwin' ? [body] : [n.subtitle, body, n.footer];
  return {
    title: n.title,
    ...(platform === 'darwin' && n.subtitle ? { subtitle: n.subtitle } : {}),
    body: lines.filter((l): l is string => !!l).join('\n'),
    silent: n.silent,
    urgency: n.urgency,
    timeoutType: 'never',
    actions: n.actions.map((a) => ({ type: 'button' as const, text: a.text })),
    ...(platform === 'darwin' ? { closeButtonText: 'Close' } : {}),
    ...(n.group ? { groupId: n.group } : {}),
  };
}

/**
 * The button an `action` event names: Electron 45 passes it as `details.actionIndex`; the second argument, the index
 * itself, is deprecated there. Null when neither says — a click on the notification, Review.
 */
export function actionIndexOf(details: unknown, legacyIndex: unknown): number | null {
  const given = typeof details === 'object' && details !== null ? (details as { actionIndex?: unknown }).actionIndex : undefined;
  for (const i of [given, legacyIndex]) {
    if (Number.isInteger(i) && (i as number) >= 0) return i as number;
  }
  return null;
}

/** What a click on the notification or one of its buttons means. */
export function actionAt(n: DesktopNotice, index: number | null): 'review' | 'deny' {
  if (index === null) return 'review';
  return n.actions[index]?.id === 'deny' ? 'deny' : 'review';
}

/** The deny request of a notice, or null when this notice can't deny anything. */
export function denyRequest(n: DesktopNotice): { path: string; body: { decision: 'deny'; via: 'desktop-notification' } } | null {
  if (n.kind !== 'request' || !APPROVAL_ID.test(n.id) || !n.actions.some((a) => a.id === 'deny')) return null;
  return { path: `/api/approvals/${n.id}/decision`, body: { decision: 'deny', via: 'desktop-notification' } };
}

/** The OS notification of a local app's OAuth consent request. */
export function consentNotice(data: unknown): { id: string; title: string; body: string } | null {
  if (typeof data !== 'object' || data === null) return null;
  const d = data as Record<string, unknown>;
  if (d['ingress'] !== 'LOCAL') return null;
  const id = text(d['id'], 64);
  const code = typeof d['code'] === 'string' && /^[A-Z0-9]{3}-[A-Z0-9]{3}$/.test(d['code']) ? d['code'] : null;
  if (!id || !code) return null;
  const host = text(d['clientHost'], 128) ?? 'An app';
  const endpoint = text(d['endpoint'], 64);
  return {
    id,
    title: `${host} wants to sign in · code ${code}`,
    body: `Check the code, then allow or deny it in GateRook${endpoint ? ` (endpoint ${endpoint})` : ''}.`,
  };
}
