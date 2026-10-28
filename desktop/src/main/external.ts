// What main opens outside the app on the renderer's behalf (ARCHITECTURE.md §7.1 "openExternal (https + allowlist
// documentation domains)"): https only, on a short list of documentation hosts or exact project URLs. Everything
// else a page asks to open — a link with target=_blank included — goes nowhere: the renderer can't make main open
// a file, another scheme or a page that would hand data to someone.

const DOCUMENTATION_HOSTS: ReadonlySet<string> = new Set([
  'modelcontextprotocol.io',
  'claude.ai',
  'support.claude.com',
  'docs.anthropic.com',
  'docs.claude.com',
  'developers.openai.com',
  'cursor.com',
  'docs.cursor.com',
  'antigravity.google',
  'github.com',
  'developers.cloudflare.com',
  'ngrok.com',
  'api.slack.com',
  'core.telegram.org',
  'json-schema.org',
]);

const PROJECT_URLS: ReadonlySet<string> = new Set([
  'https://www.linkedin.com/in/dobroskok/',
  'https://provenbase.com/',
]);

/** The URL to open in the browser, normalized, or null when it isn't allowed. */
export function allowedExternalUrl(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length > 2048) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' || url.username !== '' || url.password !== '' || url.port !== '') return null;
  return DOCUMENTATION_HOSTS.has(url.hostname.toLowerCase()) || PROJECT_URLS.has(url.toString()) ? url.toString() : null;
}
