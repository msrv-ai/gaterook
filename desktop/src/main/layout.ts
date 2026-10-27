// Where things are (ARCHITECTURE.md §7.1, §7.6 of report 14): the installed app — UI in the asar, the native core and
// its libraries in `<resources>/core`, the gateway home `~/.mcp-gateway` — or a development build run from the
// repository, where environment variables may point elsewhere (a throwaway home, the core jar, a fake core for the
// tests). A release build ignores every one of those variables: `dev` is false there whatever the environment says.
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { CoreCommand } from './core-command.ts';

export interface Layout {
  /** A development build run unpackaged: dev-only switches and variables apply. */
  readonly dev: boolean;
  readonly home: string;
  readonly uiRoot: string;
  /** `-Dgateway.resources` for the core: the bundle, or a staged folder in development, or null. */
  readonly resources: string | null;
  readonly core: CoreCommand;
  /** Development: the Java bridge the core's gw-bridge shim points at (`bridge-cli/target/gw-bridge.jar`). */
  readonly bridgeJar: string | null;
  readonly preload: string;
}

export interface LayoutInput {
  /** The bundle was built as a development build (scripts/build.mjs without --release). */
  readonly devBuild: boolean;
  readonly packaged: boolean;
  /** `app.getAppPath()`: `<repo>/desktop` in development, the asar when installed. */
  readonly appPath: string;
  readonly resourcesPath: string;
  readonly homedir: string;
  readonly platform: NodeJS.Platform;
  readonly env: NodeJS.ProcessEnv;
}

export function resolveLayout(i: LayoutInput): Layout {
  const dev = i.devBuild && !i.packaged;
  const env = dev ? i.env : {};
  const exe = i.platform === 'win32' ? '.exe' : '';
  const preload = join(i.appPath, 'dist', 'preload.cjs');
  if (!dev) {
    const resources = i.resourcesPath;
    const coreDir = join(resources, 'core');
    const binary = join(coreDir, `mcp-gateway-core${exe}`);
    // The JVM fallback is a CI artifact only (§7.1 "Packaging"); an installer has the native binary.
    const core: CoreCommand = existsSync(binary) || !existsSync(join(coreDir, 'app.jar'))
      ? { kind: 'native', binary, libDir: coreDir }
      : { kind: 'jar', java: join(resources, 'jre', 'bin', `java${exe}`), jar: join(coreDir, 'app.jar') };
    return {
      dev: false, home: join(i.homedir, '.mcp-gateway'), uiRoot: join(i.appPath, 'ui'), resources, core,
      bridgeJar: null, preload,
    };
  }
  const repo = resolve(i.appPath, '..');
  const home = env['GW_HOME'] ? resolve(env['GW_HOME']) : join(i.homedir, '.mcp-gateway');
  const uiRoot = env['GW_UI_DIST'] ? resolve(env['GW_UI_DIST']) : join(repo, 'ui', 'dist', 'mcp-gateway-ui', 'browser');
  let core: CoreCommand;
  if (env['GW_CORE_COMMAND']) {
    const argv = JSON.parse(env['GW_CORE_COMMAND']) as unknown;
    if (!Array.isArray(argv) || argv.length === 0 || !argv.every((a) => typeof a === 'string')) {
      throw new Error('GW_CORE_COMMAND must be a JSON array of strings');
    }
    core = { kind: 'custom', argv: argv as string[] };
  } else if (env['GW_CORE_BINARY']) {
    const binary = resolve(env['GW_CORE_BINARY']);
    core = { kind: 'native', binary, libDir: env['GW_CORE_LIBS'] ? resolve(env['GW_CORE_LIBS']) : join(binary, '..') };
  } else {
    const javaHome = env['GW_CORE_JAVA_HOME'] ?? env['JAVA_HOME'];
    core = {
      kind: 'jar',
      java: javaHome ? join(javaHome, 'bin', `java${exe}`) : `java${exe}`,
      jar: env['GW_CORE_JAR'] ? resolve(env['GW_CORE_JAR']) : join(repo, 'app', 'target', 'app.jar'),
    };
  }
  const bridgeJar = join(repo, 'bridge-cli', 'target', 'gw-bridge.jar');
  return {
    dev: true,
    home,
    uiRoot,
    resources: env['GW_RESOURCES'] ? resolve(env['GW_RESOURCES']) : null,
    core,
    bridgeJar: existsSync(bridgeJar) ? bridgeJar : null,
    preload,
  };
}
