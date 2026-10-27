// How main starts the core (ARCHITECTURE.md §7.1 "Starting the core"): the native binary from the bundle, or — in a
// development build — `java -jar app/target/app.jar` (or any command a test names). The native binary gets `-X`/`-D`
// flags only (a native image parses them itself; no `-XX:`), the JVM also the flags that close it to attaching
// programs; the environment is a short allowlist, and nothing secret is ever on the command line: the secrets go
// through stdin (bootstrap.ts).
import { join } from 'node:path';

export type CoreCommand =
  | { readonly kind: 'native'; readonly binary: string; readonly libDir: string }
  | { readonly kind: 'jar'; readonly java: string; readonly jar: string }
  | { readonly kind: 'custom'; readonly argv: readonly string[] };

export interface CoreArgsOptions {
  readonly home: string;
  /** The app's bundle (`process.resourcesPath`), or null in a development run without one. */
  readonly resources: string | null;
  /** Development only: the Java bridge jar the core's `gw-bridge` shim points at. */
  readonly bridgeJar?: string | null;
}

export function coreArgv(cmd: CoreCommand, o: CoreArgsOptions): { file: string; args: string[] } {
  const common = [
    `-Djava.io.tmpdir=${join(o.home, 'tmp')}`,
    `-Dgateway.home=${o.home}`,
    ...(o.resources ? [`-Dgateway.resources=${o.resources}`] : []),
  ];
  switch (cmd.kind) {
    case 'native':
      return { file: cmd.binary, args: ['-Xmx384m', `-Dorg.sqlite.lib.path=${cmd.libDir}`, ...common] };
    case 'jar':
      return {
        file: cmd.java,
        args: [
          '-Xmx384m',
          // A JVM, unlike the native image, opens itself to every program of the account: jcmd / jmap attach and dump
          // the heap — the master key with it (report 12 §6, report 14 §6.4). The native binary needs none of these.
          '-XX:+DisableAttachMechanism',
          '-XX:-UsePerfData',
          '-XX:+ExitOnOutOfMemoryError',
          // sqlite-jdbc and the clients' sandbox use the FFM API; without it the JVM warns on every start.
          '--enable-native-access=ALL-UNNAMED',
          ...common,
          ...(o.bridgeJar ? [`-Dgateway.clients.bridge-jar=${o.bridgeJar}`] : []),
          '-jar',
          cmd.jar,
        ],
      };
    case 'custom': {
      const [file, ...args] = cmd.argv;
      if (!file) throw new Error('An empty core command');
      return { file, args: [...args, ...common] };
    }
  }
}

/**
 * Environment variables the core gets, by name: the system's basics, where things live (the clients' config files,
 * the shell whose PATH MCP servers run with), the locale, and the proxy and CA settings MCP servers inherit (C10).
 * No credentials of other tools, no NODE_OPTIONS, no ELECTRON_*, no JAVA_TOOL_OPTIONS — none of them reach the core.
 */
export const CORE_ENV_ALLOWLIST: readonly string[] = [
  // ARCHITECTURE.md §7.1.
  'PATH', 'HOME', 'USERPROFILE', 'SystemRoot', 'TEMP', 'TMP', 'LANG',
  // Locale and time.
  'LC_ALL', 'LC_CTYPE', 'TZ',
  // Who and where: client configs (CODEX_HOME, APPDATA), the shell's PATH for MCP servers, synced folders.
  'USER', 'LOGNAME', 'USERNAME', 'SHELL', 'TMPDIR', 'HOSTNAME', 'COMPUTERNAME', 'APPDATA', 'LOCALAPPDATA', 'OneDrive',
  'CODEX_HOME', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_CACHE_HOME', 'XDG_RUNTIME_DIR',
  // Starting processes on Windows.
  'ComSpec', 'PATHEXT', 'windir', 'ProgramFiles', 'ProgramFiles(x86)', 'ProgramData',
  // Network settings MCP servers inherit (the core itself never uses a proxy for its own egress).
  'HTTPS_PROXY', 'HTTP_PROXY', 'NO_PROXY', 'https_proxy', 'http_proxy', 'no_proxy', 'NODE_EXTRA_CA_CERTS', 'SSL_CERT_FILE',
];

/** Development builds pass these on too (the demo world of BLUEPRINT §9). */
export const DEV_ENV_PASSTHROUGH: readonly string[] = ['GW_DEMO'];

export function coreEnv(source: NodeJS.ProcessEnv, dev: boolean): Record<string, string> {
  const env: Record<string, string> = {};
  for (const name of [...CORE_ENV_ALLOWLIST, ...(dev ? DEV_ENV_PASSTHROUGH : [])]) {
    const value = source[name];
    if (typeof value === 'string' && value !== '') env[name] = value;
  }
  // A GUI app on macOS starts with a bare PATH; the core finds node/java for MCP servers through $SHELL itself.
  if (!env['PATH']) env['PATH'] = process.platform === 'win32' ? 'C:\\Windows\\System32' : '/usr/bin:/bin:/usr/sbin:/sbin';
  if (!env['LANG'] && process.platform !== 'win32') env['LANG'] = 'en_US.UTF-8';
  return env;
}
