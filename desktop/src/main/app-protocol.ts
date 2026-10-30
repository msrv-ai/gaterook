// The `app://gw/` scheme (ARCHITECTURE.md §7.1, §9 "Electron hardening"): the built UI out of the app's own files,
// with a CSP whose style nonce is new on every load of index.html, and `/api/*` handed to the signing proxy. What a
// request may reach is decided here, before any file is touched:
//   - only the host `gw`;
//   - `/api/…` goes to the core, but only with a raw path the core itself would accept (no encoded dots, slashes,
//     backslashes, NUL, `;` or `%`, no empty or dot segments) — anything else is 404 without asking the core;
//   - `/internal/…` is 404: those routes are main's, never the renderer's;
//   - a file must resolve, after decoding, to a regular file inside the UI root — no `..`, no encoded slash, no
//     symlink, its real path inside the root's real path; a path without an extension is a route of the SPA and
//     gets index.html.
import { randomBytes } from 'node:crypto';
import { lstat, readFile, realpath } from 'node:fs/promises';
import { extname, join, sep } from 'node:path';

/** The app's origin with its root path: every page of the window starts with it. */
export const APP_URL = 'app://gw/';

/**
 * A page the window may show: the app's own, but never an answer of the core (`/api/…`) or a route main keeps to
 * itself (`/internal/…`) as a document — those would run with the preload and without the app's CSP.
 */
export function isAppPage(url: string): boolean {
  if (!url.startsWith(APP_URL)) return false;
  let path: string;
  try {
    path = new URL(url).pathname.toLowerCase();
  } catch {
    return false;
  }
  return !(path === '/api' || path.startsWith('/api/') || path === '/internal' || path.startsWith('/internal/'));
}

/** A URL for the log: scheme, host and path, never a query (it might carry something). */
export function safeUrl(url: string): string {
  try {
    const u = new URL(url);
    return `${u.protocol}//${u.host}${u.pathname}`.slice(0, 200);
  } catch {
    return '(not a URL)';
  }
}

export type AppRoute =
  | { readonly kind: 'api'; readonly target: string }
  | { readonly kind: 'index' }
  | { readonly kind: 'file'; readonly segments: readonly string[] }
  | { readonly kind: 'not-found' };

const FORBIDDEN_ESCAPE = /%(2e|2f|5c|00|25|3b)/i;

/**
 * Where an `app://` URL leads. `rawPath` is the URL's pathname exactly as parsed (percent-escapes kept), `search` its
 * query with the `?` (or empty).
 */
export function routeAppUrl(url: URL): AppRoute {
  if (url.host !== 'gw') return { kind: 'not-found' };
  const raw = url.pathname;
  if (raw.includes('\\') || raw.includes('\0')) return { kind: 'not-found' };
  if (raw === '/internal' || raw.startsWith('/internal/')) return { kind: 'not-found' };
  if (raw === '/api' || raw.startsWith('/api/')) {
    return wellFormedApiPath(raw) ? { kind: 'api', target: raw + (url.search.length > 1 ? url.search : '') }
      : { kind: 'not-found' };
  }
  if (raw === '/' || raw === '') return { kind: 'index' };
  const segments: string[] = [];
  const parts = raw.slice(1).split('/');
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i] ?? '';
    // A trailing slash is the folder itself: an SPA route, never a listing.
    if (part === '' && i === parts.length - 1) break;
    let decoded: string;
    try {
      decoded = decodeURIComponent(part);
    } catch {
      return { kind: 'not-found' };
    }
    if (decoded === '' || decoded === '.' || decoded === '..' || /[\\/\0]/.test(decoded)) return { kind: 'not-found' };
    segments.push(decoded);
  }
  if (segments.length === 0) return { kind: 'index' };
  const last = segments[segments.length - 1] ?? '';
  if (last === 'index.html' && segments.length === 1) return { kind: 'index' };
  return extname(last) === '' ? { kind: 'index' } : { kind: 'file', segments };
}

/** The core's own rule for raw admin paths (platform.web.LocalGuardFilter.wellFormed). */
export function wellFormedApiPath(raw: string): boolean {
  if (raw.length < 2 || !raw.startsWith('/') || raw.endsWith('/') || raw.includes(';') || raw.includes('\\')
      || FORBIDDEN_ESCAPE.test(raw)) {
    return false;
  }
  return raw.slice(1).split('/').every((s) => s !== '' && s !== '.' && s !== '..');
}

/** A fresh nonce for one load of index.html. */
export function newNonce(random: (n: number) => Buffer = randomBytes): string {
  return random(18).toString('base64');
}

/**
 * The window's CSP (ARCHITECTURE.md §7.1): scripts only from the app, styles from the app or with this load's nonce
 * (Angular puts it on the styles it adds, read from `ngCspNonce`), no plugins, no frames of us anywhere, no forms
 * leaving, connections only to the app itself — `/api` included, so the renderer can't reach the core's port directly
 * — and Trusted Types for every DOM sink, with only Angular's policies.
 */
export function contentSecurityPolicy(nonce: string | null): string {
  return [
    "default-src 'self'",
    "script-src 'self'",
    `style-src 'self'${nonce ? ` 'nonce-${nonce}'` : ''}`,
    "font-src 'self'",
    "img-src 'self' data:",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
    "form-action 'none'",
    "require-trusted-types-for 'script'",
    'trusted-types angular angular#bundler',
  ].join('; ');
}

/** Puts the nonce where Angular looks for it (`<gw-root ngCspNonce>`), the first root element only. */
export function injectNonce(html: string, nonce: string): string {
  return html.replace(/<gw-root(?=[\s>])/, `<gw-root ngCspNonce="${nonce}"`);
}

const TYPES: Readonly<Record<string, string>> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.map': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
  '.txt': 'text/plain; charset=utf-8',
};

export function contentType(file: string): string {
  return TYPES[extname(file).toLowerCase()] ?? 'application/octet-stream';
}

function headers(type: string, csp: string, extra: Record<string, string> = {}): Headers {
  return new Headers({
    'Content-Type': type,
    'Content-Security-Policy': csp,
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Cross-Origin-Opener-Policy': 'same-origin',
    'Cross-Origin-Resource-Policy': 'same-origin',
    ...extra,
  });
}

export function notFound(): Response {
  return new Response(null, { status: 404, headers: headers('text/plain; charset=utf-8', contentSecurityPolicy(null)) });
}

/**
 * The file a route names inside `root`, or null. The root's real path is compared after resolving: a symlink — the
 * file itself or a folder on the way — never leads out, and a symlinked file isn't served even when it stays inside.
 */
export async function resolveUiFile(root: string, segments: readonly string[]): Promise<string | null> {
  let rootReal: string;
  try {
    rootReal = await realpath(root);
  } catch {
    return null;
  }
  const candidate = join(rootReal, ...segments);
  if (!candidate.startsWith(rootReal + sep)) return null;
  try {
    const stat = await lstat(candidate);
    if (stat.isSymbolicLink() || !stat.isFile()) return null;
    const real = await realpath(candidate);
    return real === candidate && real.startsWith(rootReal + sep) ? real : null;
  } catch {
    return null;
  }
}

/** Serves a UI route out of `root` (index.html with a fresh nonce, or a static file). */
export async function serveUi(root: string, route: AppRoute, nonce: () => string = newNonce): Promise<Response> {
  if (route.kind === 'index') {
    const file = await resolveUiFile(root, ['index.html']);
    if (!file) return notFound();
    const n = nonce();
    const html = injectNonce(await readFile(file, 'utf8'), n);
    return new Response(html, {
      status: 200,
      headers: headers('text/html; charset=utf-8', contentSecurityPolicy(n), { 'Cache-Control': 'no-store' }),
    });
  }
  if (route.kind !== 'file') return notFound();
  const file = await resolveUiFile(root, route.segments);
  if (!file) return notFound();
  const body = await readFile(file);
  return new Response(new Uint8Array(body), { status: 200, headers: headers(contentType(file), contentSecurityPolicy(null)) });
}
