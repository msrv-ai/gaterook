// Chromium and Node switches the app refuses to start with (ARCHITECTURE.md §7.1 "Window and security", §9
// "Electron hardening"). Each one opens the process to another program of the same account — a debugger port, a
// proxy that sees every request, logs of the network, no sandbox, a wrapper around the renderer — and none has a
// legitimate use for a person running MCP Gateway. The check is the first thing main does: before the single-instance
// lock, before any module with side effects loads, before Chromium starts its DevTools server (it starts after the
// main script ran, checked by the smoke).

/** Exact switch names (Chromium parses `--name`, `--name=value`, `-name`, and `/name` on Windows). */
export const DANGEROUS_SWITCHES: readonly string[] = [
  // Remote debugging and inspection.
  'remote-debugging-port',
  'remote-debugging-pipe',
  'remote-debugging-address',
  'remote-allow-origins',
  'inspect',
  'inspect-brk',
  'inspect-port',
  'inspect-brk-node',
  'inspect-publish-uid',
  'inspect-wait',
  'js-flags',
  // Logs that would record what the app sends and receives.
  'log-net-log',
  'net-log-capture-mode',
  'enable-logging',
  'v',
  'vmodule',
  // Weaker isolation or none.
  'disable-web-security',
  'no-sandbox',
  'disable-gpu-sandbox',
  'single-process',
  'no-zygote',
  'disable-seccomp-filter-sandbox',
  'disable-namespace-sandbox',
  'disable-setuid-sandbox',
  // Turns off Blink features the CSP relies on (`--disable-blink-features=TrustedDOMTypes`).
  'disable-blink-features',
  'disable-site-isolation-trials',
  'allow-running-insecure-content',
  // Someone else between the app and the network.
  'proxy-server',
  'proxy-pac-url',
  'host-rules',
  'host-resolver-rules',
  'ignore-certificate-errors',
  'ignore-certificate-errors-spki-list',
  'ssl-key-log-file',
  // Code that isn't the app's.
  'load-extension',
  'renderer-cmd-prefix',
  'utility-cmd-prefix',
  'zygote-cmd-prefix',
  'gpu-launcher',
  'browser-subprocess-path',
  'user-data-dir',
];

/** Families matched by prefix: every `inspect…` and `remote-debugging…` variant a future version may add. */
const DANGEROUS_PREFIXES: readonly string[] = ['inspect', 'remote-debugging'];

/** `no-sandbox` is tolerated only in a development build that asked for it (Linux CI containers without userns). */
export interface SwitchPolicy {
  readonly allowNoSandbox: boolean;
}

/**
 * The dangerous switches present on a command line.
 *
 * @param argv      the process's arguments (`process.argv`, or the `commandLine` of `second-instance`)
 * @param hasSwitch Chromium's own parse (`app.commandLine.hasSwitch`), authoritative for exact names
 */
export function findDangerousSwitches(argv: readonly string[], hasSwitch: (name: string) => boolean,
    policy: SwitchPolicy = { allowNoSandbox: false }): string[] {
  const found = new Set<string>();
  for (const name of DANGEROUS_SWITCHES) {
    if (hasSwitch(name)) found.add(name);
  }
  for (const arg of argv) {
    if (arg === '--') break; // Chromium stops reading switches here.
    const name = switchName(arg);
    if (name === null) continue;
    if (DANGEROUS_SWITCHES.includes(name) || DANGEROUS_PREFIXES.some((p) => name.startsWith(p))) found.add(name);
  }
  if (policy.allowNoSandbox) found.delete('no-sandbox');
  return [...found].sort();
}

/**
 * The switch an argument names, lower-cased, or null for a plain argument (a path, a deep link). Windows also
 * accepts `/name`, but only for an argument that can't be a path.
 */
export function switchName(raw: string): string | null {
  // Chromium trims each argument before it parses it.
  const arg = raw.trim();
  let body: string;
  if (arg.startsWith('--')) body = arg.slice(2);
  else if (arg.startsWith('-') && arg.length > 1) body = arg.slice(1);
  else if (arg.startsWith('/') && process.platform === 'win32' && /^\/[a-z][a-z0-9-]*(=|$)/i.test(arg)) body = arg.slice(1);
  else return null;
  const name = body.split('=', 1)[0] ?? '';
  return /^[a-z0-9][a-z0-9-]*$/i.test(name) ? name.toLowerCase() : null;
}

/**
 * Environment variables that act like refused switches: Electron reads them before main runs (logging to stderr or
 * a file). `SSLKEYLOGFILE` is not refused but dropped (sanitizeEnvironment): it would log TLS keys of the processes
 * Chromium starts later, and a developer may have it set for something else.
 */
export const DANGEROUS_ENV: readonly string[] = ['ELECTRON_ENABLE_LOGGING', 'ELECTRON_LOG_FILE'];

export function findDangerousEnv(env: NodeJS.ProcessEnv): string[] {
  return DANGEROUS_ENV.filter((name) => env[name] !== undefined && env[name] !== '');
}

/** Drops variables the app must not hand to the processes Chromium starts after main ran. */
export function sanitizeEnvironment(env: NodeJS.ProcessEnv): string[] {
  const dropped: string[] = [];
  for (const name of ['SSLKEYLOGFILE']) {
    if (env[name] !== undefined) {
      delete env[name];
      dropped.push(name);
    }
  }
  return dropped;
}

/** The sentence main prints before it exits (stderr; no dialog: a launch by someone else would wait on it). */
export function refusalMessage(names: readonly string[]): string {
  const list = names.map((n) => (/^[A-Z_]+$/.test(n) ? n : `--${n}`)).join(', ');
  return `MCP Gateway refuses to start with ${list}: ${names.length === 1 ? 'this switch lets' : 'these switches let'} `
    + 'other programs watch or change what the app does. Start it without them.';
}

/** Exit code of a refused start. */
export const REFUSED_EXIT_CODE = 3;
