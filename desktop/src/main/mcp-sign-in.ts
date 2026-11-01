// The sign-in to a remote MCP server (ARCHITECTURE.md §7.5): the person signs in in their own browser, as in Claude
// Desktop, Cursor or Codex, and the redirect comes back to a listener of the core on this computer. The renderer only
// names the sign-in by its id; main asks its core for that sign-in's authorization page and opens it. The renderer
// never hands main a URL to open, so this is no way around the documentation allowlist of `openExternal`. Main opens
// the page this module vouches for (app.ts); nothing here needs Electron.
import { callCoreOk } from './core-client.ts';
import type { CoreEndpoint } from './proxy.ts';

/** A sign-in id as the core makes them (`Ids.next("msi_")`), or null. */
export function signInId(id: unknown): string | null {
  return typeof id === 'string' && /^msi_[a-z0-9]{12}$/.test(id) ? id : null;
}

/**
 * The authorization page to open, normalized, or null: `https` anywhere, plain `http` only to this computer (a
 * server under development), without credentials or a fragment, at most 8 KB.
 */
export function signInPage(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length > 8192) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.username !== '' || url.password !== '' || url.hash !== '') return null;
  const loopback = url.hostname === '127.0.0.1' || url.hostname === 'localhost' || url.hostname === '[::1]';
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) return null;
  return url.toString();
}

/** The authorization page of sign-in `id`, as the core names it and as this module allows it to be opened. */
export async function mcpSignInPage(core: CoreEndpoint | null, id: unknown): Promise<string> {
  const signIn = signInId(id);
  if (!signIn) throw new Error('Unknown sign-in');
  if (!core) throw new Error('The core isn’t running. Wait for it to start, then try again.');
  const { url } = await callCoreOk<{ url?: unknown }>(core, 'GET', `/internal/mcp-sign-ins/${signIn}/authorization`);
  const page = signInPage(url);
  if (!page) throw new Error('GateRook opens only an HTTPS sign-in page.');
  return page;
}

/** Whether a core event ends a sign-in the person may be waiting for in the browser: the window comes forward then. */
export function signInEnded(type: string, data: unknown): boolean {
  if (type !== 'mcp-server.sign-in' || typeof data !== 'object' || data === null) return false;
  const d = data as Record<string, unknown>;
  return typeof d['id'] === 'string' && (d['state'] === 'signed-in' || d['state'] === 'failed');
}
