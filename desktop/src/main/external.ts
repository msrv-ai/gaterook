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
  'code.visualstudio.com',
  'github.com',
  'developers.cloudflare.com',
  'ngrok.com',
  'api.slack.com',
  'core.telegram.org',
  'json-schema.org',
]);

const PROJECT_URLS: ReadonlySet<string> = new Set([
  'https://msrv.dev/',
  'https://www.linkedin.com/in/dobroskok/',
  'https://provenbase.com/',
  'https://apps.microsoft.com/detail/9nht9rb2f4hd',
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
  if (url.hostname === 'vscode.dev') return allowedVsCodeInstall(url) ? url.toString() : null;
  return DOCUMENTATION_HOSTS.has(url.hostname.toLowerCase()) || PROJECT_URLS.has(url.toString()) ? url.toString() : null;
}

/** Permit only GateRook's loopback HTTP server, never commands, headers or other VS Code URL handlers. */
function allowedVsCodeInstall(url: URL): boolean {
  if (url.pathname !== '/redirect' || url.hash || [...url.searchParams.keys()].join(',') !== 'url') return false;
  try {
    const inner = new URL(url.searchParams.get('url')!);
    if (inner.protocol !== 'vscode:' || inner.host || inner.pathname !== 'mcp/install' || inner.hash) return false;
    const config = JSON.parse(decodeURIComponent(inner.search.slice(1)));
    if (!config || Object.keys(config).sort().join(',') !== 'name,type,url' || config.type !== 'http'
        || typeof config.name !== 'string' || !/^gaterook-[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(config.name)
        || typeof config.url !== 'string' || !/^http:\/\/127\.0\.0\.1:[0-9]+\/mcp\/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(config.url)) return false;
    const endpoint = new URL(config.url);
    return endpoint.port !== '0' && config.name === `gaterook-${endpoint.pathname.slice(5)}`;
  } catch {
    return false;
  }
}
