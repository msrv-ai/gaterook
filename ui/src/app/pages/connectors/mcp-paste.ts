import { McpServerAuthConfig } from '../../core/api/plugins.api';
import { formatArgv, tokenizeCommand } from './connectors-view';
import { awsTarget } from './mcp-cloud';

/**
 * A server read from a snippet the person pasted from a server's documentation (ARCHITECTURE.md §7.5): what fills Add
 * MCP server. Values of variables and headers are never kept — only their names; the review asks for each value and
 * GateRook stores it encrypted.
 */
export interface PastedServer {
  /** A suggested name, or '' when the snippet has none. */
  readonly name: string;
  readonly transport: 'stdio' | 'http';
  readonly url: string;
  /** The command as one line for the Command field (quoted where needed). */
  readonly command: string;
  /** Names of the variables (stdio) or headers (HTTP) whose values the review asks for. */
  readonly variables: readonly string[];
  /** What GateRook changed or left out, in words for the dialog. */
  readonly notes: readonly string[];
  /** How many other servers the snippet named (only the first one is taken). */
  readonly others: number;
  /**
   * A sign-in the snippet implies (§7.6): the MCP Proxy for AWS becomes AWS SigV4 with its profile, a Google token in a
   * header becomes Google Cloud credentials. Absent otherwise.
   */
  readonly auth?: McpServerAuthConfig;
}

/**
 * The first server of a snippet in any of the forms MCP servers document for the AI apps: Claude Desktop, Cursor and
 * Windsurf JSON (`mcpServers`), VS Code JSON (`servers`, also under `mcp`), one server's object or a `"name": {…}`
 * fragment, Codex TOML (`[mcp_servers.<name>]`), a `claude mcp add` or `codex mcp add` command, or just a URL. A
 * command that runs `mcp-remote` for a URL becomes that URL: GateRook signs in to the server itself.
 */
export function parsePastedServer(text: string): PastedServer | { readonly error: string } {
  const t = text.trim();
  if (!t) return { error: 'Paste the configuration from the server’s documentation.' };
  if (t.length > 64 * 1024) return { error: 'That is too long for one server’s configuration.' };
  if (/^https?:\/\/\S+$/i.test(t)) return fromUrl('', t, [], []);
  if (/^(\S*\/)?(npx|bunx|pnpm|node|deno|uvx|uv|docker|python3?|claude|codex)\s/.test(t)) {
    const fromCli = cliServer(t);
    if (fromCli) return fromCli;
  }
  if (/^\s*\[/.test(t) && /\[\s*mcp_servers\s*\./.test(t)) return tomlServer(t);
  const json = parseJson(t);
  if (json === undefined) return { error: 'This isn’t JSON, TOML, a URL or a command GateRook can read. Paste one server’s configuration.' };
  return jsonServer(json);
}

// ── JSON ──────────────────────────────────────────────────────────────────────────────────────────────────────────

type Json = null | boolean | number | string | Json[] | { readonly [key: string]: Json };

function parseJson(t: string): Json | undefined {
  for (const candidate of [t, `{${t}}`, `{${t.replace(/,\s*$/, '')}}`]) {
    try {
      return JSON.parse(stripJsonComments(candidate)) as Json;
    } catch {
      // The next form: a "name": {…} fragment copied without its braces, with or without a trailing comma.
    }
  }
  return undefined;
}

/** `//` and `/* *\/` comments outside strings, as VS Code's settings allow, and trailing commas before `}` or `]`. */
function stripJsonComments(t: string): string {
  let out = '';
  let inString = false;
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (inString) {
      out += c;
      if (c === '\\') out += t[++i] ?? '';
      else if (c === '"') inString = false;
    } else if (c === '"') {
      inString = true;
      out += c;
    } else if (c === '/' && t[i + 1] === '/') {
      while (i < t.length && t[i] !== '\n') i++;
      out += '\n';
    } else if (c === '/' && t[i + 1] === '*') {
      i = t.indexOf('*/', i + 2);
      if (i < 0) break;
      i++;
    } else {
      out += c;
    }
  }
  return out.replace(/,(\s*[}\]])/g, '$1');
}

function isObject(v: Json | undefined): v is { readonly [key: string]: Json } {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function jsonServer(root: Json): PastedServer | { readonly error: string } {
  if (!isObject(root)) return { error: 'Paste an object with the server’s settings.' };
  const mcp = isObject(root['mcp']) ? root['mcp'] : root;
  const group = isObject(mcp['mcpServers']) ? mcp['mcpServers'] : isObject(mcp['servers']) ? mcp['servers'] : null;
  if (group) {
    const names = Object.keys(group).filter((k) => isObject(group[k]));
    if (!names.length) return { error: 'The configuration names no server.' };
    const first = serverObject(names[0], group[names[0]] as { readonly [key: string]: Json });
    return 'error' in first ? first : { ...first, others: names.length - 1 };
  }
  if (looksLikeServer(root)) return serverObject('', root);
  // A "name": {…} fragment.
  const names = Object.keys(root).filter((k) => isObject(root[k]) && looksLikeServer(root[k] as { readonly [key: string]: Json }));
  if (names.length) {
    const first = serverObject(names[0], root[names[0]] as { readonly [key: string]: Json });
    return 'error' in first ? first : { ...first, others: names.length - 1 };
  }
  return { error: 'The configuration has no command or URL of a server.' };
}

function looksLikeServer(o: { readonly [key: string]: Json }): boolean {
  return typeof o['command'] === 'string' || typeof o['url'] === 'string' || typeof o['serverUrl'] === 'string'
    || typeof o['httpUrl'] === 'string';
}

function serverObject(name: string, o: { readonly [key: string]: Json }): PastedServer | { readonly error: string } {
  const url = [o['url'], o['serverUrl'], o['httpUrl']].find((v): v is string => typeof v === 'string');
  if (url) {
    const headers = isObject(o['headers']) ? Object.keys(o['headers']) : [];
    const result = fromUrl(name, url, headers, headers.length ? [valuesNote(headers.length)] : []);
    return 'error' in result ? result : googleToken(result, isObject(o['headers']) ? o['headers'] : {});
  }
  if (typeof o['command'] === 'string') {
    const args = Array.isArray(o['args']) ? o['args'].filter((a): a is string => typeof a === 'string') : [];
    const env = isObject(o['env']) ? o['env'] : {};
    return fromArgv(name, [o['command'], ...args], Object.keys(env), env);
  }
  return { error: 'The configuration has no command or URL of a server.' };
}

// ── TOML (Codex) ──────────────────────────────────────────────────────────────────────────────────────────────────

/** `[mcp_servers.<name>]` with `url`, `command`, `args`, `env`, `http_headers` and `env_http_headers`. */
function tomlServer(t: string): PastedServer | { readonly error: string } {
  const tables: { name: string; values: Map<string, TomlValue> }[] = [];
  let current: { name: string; values: Map<string, TomlValue> } | null = null;
  for (const raw of t.split(/\r?\n/)) {
    const line = raw.replace(/\s+#.*$/, '').trim();
    if (!line || line.startsWith('#')) continue;
    const header = /^\[\s*mcp_servers\s*\.\s*("([^"]+)"|'([^']+)'|([A-Za-z0-9_-]+))\s*(\.[^\]]*)?\]$/.exec(line);
    if (header) {
      const name = header[2] ?? header[3] ?? header[4];
      if (header[5]) {
        // [mcp_servers.x.env]: a sub-table of the current server.
        const sub = header[5].replace(/^\.\s*/, '').trim();
        current = { name: `${name}\u0000${sub}`, values: new Map() };
      } else {
        current = { name, values: new Map() };
      }
      tables.push(current);
      continue;
    }
    if (line.startsWith('[')) {
      current = null;
      continue;
    }
    const kv = /^([A-Za-z0-9_-]+|"[^"]+")\s*=\s*(.+)$/.exec(line);
    if (current && kv) current.values.set(kv[1].replace(/^"|"$/g, ''), tomlValue(kv[2]));
  }
  const servers = tables.filter((x) => !x.name.includes('\u0000'));
  if (!servers.length) return { error: 'The TOML names no [mcp_servers.<name>] table.' };
  const s = servers[0];
  const sub = (key: string): string[] => {
    const table = tables.find((x) => x.name === `${s.name}\u0000${key}`);
    const inline = s.values.get(key);
    return [...(table ? [...table.values.keys()] : []), ...(inline && typeof inline === 'object' && !Array.isArray(inline) ? Object.keys(inline) : [])];
  };
  const url = s.values.get('url');
  let result: PastedServer | { readonly error: string };
  if (typeof url === 'string') {
    const headers = [...sub('http_headers'), ...sub('env_http_headers')];
    const bearer = s.values.get('bearer_token_env_var');
    if (typeof bearer === 'string' && !headers.some((h) => h.toLowerCase() === 'authorization')) headers.push('Authorization');
    result = fromUrl(s.name, url, headers, headers.length ? [valuesNote(headers.length)] : []);
  } else {
    const command = s.values.get('command');
    if (typeof command !== 'string') return { error: `[mcp_servers.${s.name}] has no command or url.` };
    const args = s.values.get('args');
    result = fromArgv(s.name, [command, ...(Array.isArray(args) ? args : [])], sub('env'));
  }
  return 'error' in result ? result : { ...result, others: servers.length - 1 };
}

type TomlValue = string | string[] | Record<string, string> | null;

function tomlValue(raw: string): TomlValue {
  const v = raw.trim();
  if (v.startsWith('"') || v.startsWith("'")) return unquote(v);
  if (v.startsWith('[')) return [...v.matchAll(/"((?:[^"\\]|\\.)*)"|'([^']*)'/g)].map((m) => (m[1] !== undefined ? unescape(m[1]) : m[2]));
  if (v.startsWith('{')) {
    const out: Record<string, string> = {};
    for (const m of v.slice(1, -1).matchAll(/([A-Za-z0-9_-]+|"[^"]+")\s*=\s*("(?:[^"\\]|\\.)*"|'[^']*')/g)) {
      out[m[1].replace(/^"|"$/g, '')] = unquote(m[2]);
    }
    return out;
  }
  return null;
}

function unquote(v: string): string {
  if (v.startsWith("'")) return v.slice(1, v.indexOf("'", 1));
  const m = /^"((?:[^"\\]|\\.)*)"/.exec(v);
  return m ? unescape(m[1]) : v;
}

function unescape(s: string): string {
  return s.replace(/\\(["\\nt])/g, (_, c: string) => (c === 'n' ? '\n' : c === 't' ? '\t' : c));
}

// ── Commands ──────────────────────────────────────────────────────────────────────────────────────────────────────

/** `claude mcp add …`, `codex mcp add …`, or a server's own command line. */
function cliServer(t: string): PastedServer | { readonly error: string } | null {
  const { argv, error } = tokenizeCommand(t.replace(/\\\r?\n/g, ' '));
  if (error || !argv.length) return null;
  if ((argv[0] === 'claude' || argv[0] === 'codex') && argv[1] === 'mcp' && argv[2] === 'add') {
    return agentCli(argv[0], argv.slice(3));
  }
  return fromArgv('', argv, []);
}

/** Options of `claude mcp add` and `codex mcp add` that take a value GateRook doesn't use from the command. */
const VALUE_OPTIONS: ReadonlySet<string> = new Set(['--callback-port', '--client-id', '--oauth-client-id', '--oauth-client-secret',
  '--oauth-client-registration', '--oauth-resource', '--oauth-callback-port']);

/** The options of `claude mcp add` and `codex mcp add` that say how the server runs; the rest is its command. */
function agentCli(tool: 'claude' | 'codex', args: string[]): PastedServer | { readonly error: string } {
  const headers: string[] = [];
  const env: string[] = [];
  let url = '';
  const rest: string[] = [];
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--') {
      rest.push(...args.slice(i + 1));
      break;
    }
    const [flag, inline] = a.includes('=') && a.startsWith('--') ? [a.slice(0, a.indexOf('=')), a.slice(a.indexOf('=') + 1)] : [a, null];
    const next = (): string => inline ?? args[++i] ?? '';
    if (flag === '--transport' || flag === '-t' || flag === '--scope' || flag === '-s') next();
    else if (flag === '--header' || flag === '-H') headers.push(next().split(':')[0].trim());
    else if (flag === '--env' || flag === '-e') env.push(next().split('=')[0].trim());
    else if (flag === '--url') url = next();
    else if (flag === '--bearer-token-env-var') {
      next();
      headers.push('Authorization');
    } else if (VALUE_OPTIONS.has(flag)) {
      // An option whose value GateRook doesn't take from here: it asks for its own sign-in settings.
      next();
    } else if (!flag.startsWith('-')) rest.push(a);
  }
  const name = rest.shift() ?? '';
  if (!url && rest.length === 1 && /^https?:\/\//i.test(rest[0])) url = rest[0];
  if (url) return fromUrl(name, url, headers.filter(Boolean), headers.length ? [valuesNote(headers.length)] : []);
  if (!rest.length) return { error: `The ${tool} command names no URL or command of the server.` };
  return fromArgv(name, rest, env.filter(Boolean));
}

/**
 * A server's command; one that runs `mcp-remote` (or a like bridge) for a URL becomes that URL, and one that runs the
 * MCP Proxy for AWS becomes its URL signed with SigV4 by GateRook, with the proxy's profile, region and service.
 *
 * @param envValues the snippet's environment, read only for `AWS_PROFILE` and `AWS_REGION` (never a key)
 */
export function fromArgv(name: string, argv: readonly string[], env: readonly string[], envValues: { readonly [key: string]: Json } = {}): PastedServer | { readonly error: string } {
  const proxy = argv.findIndex((a) => /^(mcp-proxy-for-aws(-cli)?(@[\w.-]+)?|.*mcp_proxy_for_aws\/server\.py)$/.test(a));
  if (proxy >= 0) {
    const rest = argv.slice(proxy + 1);
    const url = rest.find((a) => /^https?:\/\//i.test(a));
    if (url) {
      const option = (flag: string): string => {
        const i = rest.indexOf(flag);
        return i >= 0 && rest[i + 1] && !rest[i + 1].startsWith('-') ? rest[i + 1] : '';
      };
      const fromEnv = (key: string): string => (typeof envValues[key] === 'string' ? (envValues[key] as string) : '');
      const profile = option('--profile') || fromEnv('AWS_PROFILE');
      const region = option('--region') || fromEnv('AWS_REGION');
      const service = option('--service');
      const result = fromUrl(name, url, [], ['GateRook signs the requests with SigV4 itself, so the MCP Proxy for AWS isn’t needed.']);
      if ('error' in result) return result;
      return {
        ...result,
        auth: {
          type: 'aws',
          credentials: { source: 'profile', profile: profile || 'default' },
          ...(service ? { service } : {}),
          ...(region ? { region } : {}),
        },
      };
    }
  }
  const bridge = argv.findIndex((a) => /^(mcp-remote|@[^/]+\/mcp-remote|mcp-remote@[\w.-]+)$/.test(a));
  if (bridge >= 0) {
    const url = argv.slice(bridge + 1).find((a) => /^https?:\/\//i.test(a));
    if (url) {
      const headers: string[] = [];
      argv.forEach((a, i) => {
        if (a === '--header' && argv[i + 1]) headers.push(argv[i + 1].split(':')[0].trim());
      });
      return fromUrl(name, url, headers.filter(Boolean), ['GateRook connects to the URL itself and signs in for you, so mcp-remote isn’t needed.',
        ...(headers.length ? [valuesNote(headers.length)] : [])]);
    }
  }
  const notes = env.length ? [valuesNote(env.length)] : [];
  return { name: serverName(name, null), transport: 'stdio', url: '', command: formatArgv(argv), variables: unique(env), notes, others: 0 };
}

/**
 * A Google MCP server documented with a token in a header (`Authorization: Bearer …` from gcloud, which ends after an
 * hour, and `x-goog-user-project`): it signs in with Google Cloud credentials instead, billed to that project.
 */
function googleToken(server: PastedServer, headers: { readonly [key: string]: Json }): PastedServer {
  let host = '';
  try {
    host = new URL(server.url).hostname.toLowerCase();
  } catch {
    return server;
  }
  const google = (h: string): boolean => ['authorization', 'x-goog-user-project'].includes(h.toLowerCase());
  if (!host.endsWith('.googleapis.com') || !server.variables.some(google)) return server;
  const billed = Object.entries(headers).find(([k]) => k.toLowerCase() === 'x-goog-user-project')?.[1];
  const quotaProject = typeof billed === 'string' && /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(billed) ? billed : '';
  const variables = server.variables.filter((h) => !google(h));
  return {
    ...server,
    variables,
    notes: [
      ...server.notes.filter((n) => n !== valuesNote(server.variables.length)),
      ...(variables.length ? [valuesNote(variables.length)] : []),
      'Signs in with your Google Cloud credentials instead of the pasted token: GateRook gets a new one before it expires.',
    ],
    auth: { type: 'google', credentials: { source: 'adc', ...(quotaProject ? { quotaProject } : {}) } },
  };
}

// ── URLs and names ────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A server at a URL. An address of the older SSE transport (`…/sse`) stays as given: GateRook tries Streamable HTTP
 * there first and falls back to that transport (§7.5).
 */
/** What the review says about an address of the older SSE transport. */
export const SSE_NOTE = 'An SSE address: GateRook speaks the older SSE transport to it when the server takes no Streamable HTTP there. If the server also lists an address ending in /mcp, use that one.';

export function fromUrl(name: string, raw: string, headers: readonly string[], notes: readonly string[]): PastedServer | { readonly error: string } {
  let url: URL;
  try {
    url = new URL(raw.trim());
  } catch {
    return { error: `${raw.slice(0, 80)} isn’t a URL.` };
  }
  const out = [...notes];
  if (/\/sse\/?$/.test(url.pathname)) {
    out.unshift(SSE_NOTE);
  }
  const value = url.toString().replace(/\/$/, url.pathname === '/' ? '' : '/');
  return { name: serverName(name, url), transport: 'http', url: value, command: '', variables: unique(headers), notes: out, others: 0 };
}

/**
 * A server name GateRook accepts (lower-case letters, digits and `-`, from a letter): the snippet's own, else one from
 * the URL's host (`mcp.linear.app` → `linear-mcp`).
 */
export function serverName(given: string, url: URL | null): string {
  const clean = (s: string): string => s.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^[^a-z]+/, '').replace(/-+/g, '-').replace(/-$/, '').slice(0, 64);
  const named = clean(given);
  if (named) return named;
  if (!url) return '';
  const host = url.hostname.toLowerCase();
  if (host === 'localhost' || host === '127.0.0.1' || host === '[::1]') return 'local-mcp';
  // One domain, many products: the product names the server (bigtableadmin.googleapis.com → bigtableadmin-mcp).
  const withMcp = (s: string): string => (s === 'mcp' || s.endsWith('-mcp') ? s : `${s.slice(0, 60)}-mcp`);
  const product = host.endsWith('.googleapis.com') ? clean(host.split('.')[0]) : clean(awsTarget(host)?.service ?? '');
  if (product) return withMcp(product);
  const generic = new Set(['mcp', 'api', 'www', 'app', 'apps', 'server', 'servers', 'remote', 'gateway', 'docs']);
  const labels = host.split('.').slice(0, -1).filter((l) => l && !generic.has(l));
  const base = clean(labels.at(-1) ?? '');
  return base ? `${base.slice(0, 60)}-mcp` : '';
}

export function valuesNote(n: number): string {
  return n === 1 ? 'Its value isn’t copied: the review asks for it and stores it encrypted.' : 'Their values aren’t copied: the review asks for each and stores it encrypted.';
}

function unique(names: readonly string[]): string[] {
  const seen = new Set<string>();
  return names.filter((n) => {
    const k = n.toLowerCase();
    if (!n || seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}
