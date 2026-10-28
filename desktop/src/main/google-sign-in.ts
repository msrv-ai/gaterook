// A fixed local ADC login. Renderer input never becomes an executable, argument, environment value or shell text.
import { spawn, type ChildProcess, type SpawnOptions } from 'node:child_process';
import { constants } from 'node:fs';
import { access, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { posix, win32 } from 'node:path';
import { coreEnv } from './core-command.ts';

const LOGIN_ARGS = ['auth', 'application-default', 'login', '--launch-browser', '--quiet'];
const MISSING = 'Google Cloud CLI was not found. Install gcloud, then try again, or run gcloud auth application-default login in a terminal.';
const FAILED = 'Google sign-in did not finish. Try again, or run gcloud auth application-default login in a terminal to see the details.';
const CANCELLED = 'Google sign-in was cancelled.';

interface Options {
  readonly platform?: NodeJS.Platform;
  readonly home?: string;
  readonly env?: NodeJS.ProcessEnv;
  readonly timeoutMs?: number;
  readonly executable?: (path: string) => Promise<boolean>;
  readonly spawn?: (file: string, args: string[], options: SpawnOptions) => ChildProcess;
  readonly terminate?: (child: ChildProcess) => void;
}

/** Absolute search paths only: never use the application's working directory as executable search input. */
export function gcloudPaths(platform: NodeJS.Platform, home: string, env: NodeJS.ProcessEnv): string[] {
  const path = platform === 'win32' ? win32 : posix;
  const inherited = (env['PATH'] ?? env['Path'] ?? '').split(path.delimiter).filter((entry) => path.isAbsolute(entry));
  const defaults = platform === 'win32'
    ? [env['LOCALAPPDATA'], env['ProgramFiles'], env['ProgramFiles(x86)']]
      .filter((entry): entry is string => !!entry && path.isAbsolute(entry))
      .map((entry) => path.join(entry, 'Google', 'Cloud SDK', 'google-cloud-sdk', 'bin'))
    : [path.join(home, 'google-cloud-sdk', 'bin'), path.join(home, 'Downloads', 'google-cloud-sdk', 'bin'),
      path.join(home, '.local', 'google-cloud-sdk', 'bin'),
      '/opt/homebrew/bin', '/usr/local/bin', '/usr/bin', '/bin', '/snap/bin',
      '/opt/homebrew/share/google-cloud-sdk/bin', '/usr/local/share/google-cloud-sdk/bin'];
  return [...new Set([...inherited, ...defaults])];
}

/** Use the same ADC directory as the desktop core; ambient SDK and credential overrides must not redirect login. */
export function googleSignInEnv(platform: NodeJS.Platform, home: string, source: NodeJS.ProcessEnv): Record<string, string> {
  const path = platform === 'win32' ? win32 : posix;
  const env = coreEnv(source, false);
  env['PATH'] = gcloudPaths(platform, home, source).join(path.delimiter);
  env['CLOUDSDK_CONFIG'] = platform === 'win32'
    ? path.join(source['APPDATA'] || path.join(home, 'AppData', 'Roaming'), 'gcloud')
    : path.join(home, '.config', 'gcloud');
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY', 'XAUTHORITY', 'DBUS_SESSION_BUS_ADDRESS']) {
    if (source[name]) env[name] = source[name];
  }
  return env;
}

export function googleSignInCommand(file: string, platform: NodeJS.Platform, env: NodeJS.ProcessEnv): {
  file: string; args: string[]; windowsVerbatimArguments: boolean;
} {
  if (platform !== 'win32') return { file, args: [...LOGIN_ARGS], windowsVerbatimArguments: false };
  // cmd.exe is needed for the SDK's Windows launcher. Reject expansion and shell metacharacters even in paths;
  // /d disables AutoRun, /v:off disables delayed expansion, and every other word is a constant.
  if (!win32.isAbsolute(file) || /[\x00-\x1f"%!^&|<>]/.test(file)) throw new Error(FAILED);
  if (!file.toLowerCase().endsWith('.cmd')) return { file, args: [...LOGIN_ARGS], windowsVerbatimArguments: false };
  const root = env['SystemRoot'] || env['windir'];
  if (!root || !win32.isAbsolute(root) || /[\x00-\x1f"%!^&|<>]/.test(root)) throw new Error(FAILED);
  return { file: win32.join(root, 'System32', 'cmd.exe'),
    args: ['/d', '/s', '/v:off', '/c', `""${file}" ${LOGIN_ARGS.join(' ')}"`], windowsVerbatimArguments: true };
}

/** One flow at a time. Output is discarded: gcloud may print authorization URLs and other credential material. */
export class GoogleSignIn {
  private readonly o: Options;
  private pending: Promise<void> | null = null;
  private cancel: (() => void) | null = null;
  private closed = false;

  constructor(options: Options = {}) { this.o = options; }

  signIn(): Promise<void> {
    if (this.closed) return Promise.reject(new Error(CANCELLED));
    if (this.pending) return this.pending;
    const pending = this.run().finally(() => { if (this.pending === pending) this.pending = null; });
    this.pending = pending;
    return pending;
  }

  close(): void {
    this.closed = true;
    this.cancel?.();
  }

  private async run(): Promise<void> {
    const platform = this.o.platform ?? process.platform;
    const home = this.o.home ?? homedir();
    const source = this.o.env ?? process.env;
    const path = platform === 'win32' ? win32 : posix;
    const names = platform === 'win32' ? ['gcloud.cmd', 'gcloud.exe'] : ['gcloud'];
    const executable = this.o.executable ?? (async (file: string) => {
      try {
        await access(file, platform === 'win32' ? constants.F_OK : constants.X_OK);
        return (await stat(file)).isFile();
      } catch { return false; }
    });
    let file: string | null = null;
    for (const dir of gcloudPaths(platform, home, source)) {
      for (const name of names) {
        const candidate = path.join(dir, name);
        if (await executable(candidate)) { file = candidate; break; }
      }
      if (file) break;
    }
    if (this.closed) throw new Error(CANCELLED);
    if (!file) throw new Error(MISSING);
    const env = googleSignInEnv(platform, home, source);
    const command = googleSignInCommand(file, platform, env);
    return new Promise<void>((resolve, reject) => {
      let child: ChildProcess;
      try {
        child = (this.o.spawn ?? spawn)(command.file, command.args, { env, cwd: home, shell: false,
          stdio: 'ignore', windowsHide: true, windowsVerbatimArguments: command.windowsVerbatimArguments,
          detached: platform !== 'win32' });
      } catch { reject(new Error(FAILED)); return; }
      let done = false;
      const terminate = () => {
        if (this.o.terminate) { this.o.terminate(child); return; }
        if (child.pid && platform === 'win32') {
          const root = env['SystemRoot'] || env['windir'];
          if (root) {
            const killer = spawn(win32.join(root, 'System32', 'taskkill.exe'), ['/pid', String(child.pid), '/t', '/f'],
              { shell: false, stdio: 'ignore', windowsHide: true });
            killer.on('error', () => child.kill());
            return;
          }
        }
        if (child.pid && platform !== 'win32') {
          try { process.kill(-child.pid, 'SIGKILL'); return; } catch { /* The process may have already exited. */ }
        }
        child.kill();
      };
      const finish = (error?: Error, stop = false) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        this.cancel = null;
        if (stop) terminate();
        if (error) reject(error); else resolve();
      };
      const timer = setTimeout(() => finish(new Error(
        'Google sign-in timed out. Try again and finish signing in in your browser within five minutes.'), true), this.o.timeoutMs ?? 300_000);
      this.cancel = () => finish(new Error(CANCELLED), true);
      child.once('error', (error: NodeJS.ErrnoException) => finish(new Error(error.code === 'ENOENT' ? MISSING : FAILED)));
      child.once('exit', (code, signal) => finish(code === 0 ? undefined : new Error(signal ? CANCELLED : FAILED)));
    });
  }
}
