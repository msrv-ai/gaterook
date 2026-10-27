// `mcp-gateway://` links (ARCHITECTURE.md §7.1 "deep links use a strict regex and allow navigation only"): a link from outside
// the app only ever opens one of a few screens — a request, an Activity event, a Settings section, the queue — and
// never acts. Main checks the shape here; the UI checks again against its own lists (ui/src/app/shell/deep-links.ts)
// before it navigates.

export const SCHEME = 'mcp-gateway';

const ROUTES: readonly RegExp[] = [
  /^approvals\/ap_[a-z0-9]{12}$/,
  /^activity\/events\/[0-9]{1,18}$/,
  /^settings\/[a-z][a-z-]{0,39}$/,
];

/**
 * The route a deep link names (`approvals/ap_…`, `activity/events/<n>`, `settings/<section>`), or null. Accepts the
 * link with or without its scheme, with at most one trailing slash; a query, a fragment, credentials, a port,
 * escapes or anything longer than 200 characters is refused.
 */
export function deepLinkRoute(link: unknown): string | null {
  if (typeof link !== 'string' || link.length > 200) return null;
  let path = link;
  if (path.toLowerCase().startsWith(`${SCHEME}:`)) {
    path = path.slice(SCHEME.length + 1);
    if (!path.startsWith('//')) return null;
    path = path.slice(2);
  }
  path = path.replace(/^\/+/, '').replace(/\/$/, '');
  return ROUTES.some((r) => r.test(path)) ? path : null;
}

/** The first deep link among command-line arguments (Windows and Linux hand it to the process, or to `second-instance`). */
export function deepLinkInArgv(argv: readonly string[]): string | null {
  for (const arg of argv) {
    if (arg.toLowerCase().startsWith(`${SCHEME}://`)) return deepLinkRoute(arg);
  }
  return null;
}

/**
 * Routes the core may ask main to show (a notification's Review, `ui.open` from Slack or Telegram): the deep-link
 * routes, and the queue itself for a summary notification.
 */
export function noticeRoute(route: unknown): string | null {
  if (route === '/approvals' || route === 'approvals') return 'approvals';
  return typeof route === 'string' ? deepLinkRoute(route) : null;
}

/**
 * Where the core's `ui.open {path}` ("Open in app" in Slack or Telegram, ARCHITECTURE.md §7.4; the channels send
 * `/approvals/ap_…` or `/approvals`) brings the window: the routes a notification's Review may open, or null.
 */
export function uiOpenRoute(data: unknown): string | null {
  if (typeof data !== 'object' || data === null) return null;
  return noticeRoute((data as Record<string, unknown>)['path']);
}
