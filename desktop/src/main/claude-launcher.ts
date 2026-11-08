// Claude Desktop on Windows opens a `.mcpb` passed on its command line, not one opened through the shell: its MSIX
// package declares no file type, so `shell.openPath` gets only Windows' "Select an app" dialog, which doesn't list
// Claude. Main starts Claude Desktop's own launcher with the extension instead: the package's app execution alias,
// or the stub of the older per-user install. A fresh start opens the extension's install dialog, and so does a
// Claude Desktop that already runs: the second start hands its arguments to it and exits.
import { spawn as nodeSpawn, type ChildProcess, type SpawnOptions } from 'node:child_process';
import { access } from 'node:fs/promises';
import { win32 } from 'node:path';

export type Spawn = (file: string, args: readonly string[], options: SpawnOptions) => ChildProcess;

export interface ClaudeLaunchDeps {
  /** Whether the launcher is there; by default `access`, which reads an app execution alias's attributes without following it. */
  readonly exists?: (file: string) => Promise<boolean>;
  readonly spawn?: Spawn;
}

/** Claude Desktop's launchers, the newer kind of install first; none without an absolute %LOCALAPPDATA%. */
export function claudeDesktopLaunchers(env: Readonly<Record<string, string | undefined>>): string[] {
  const local = env['LOCALAPPDATA'];
  if (!local || !win32.isAbsolute(local)) return [];
  return [
    // The app execution alias that the MSIX package (Claude_pzs8sxrjxfjjc) declares.
    win32.join(local, 'Microsoft', 'WindowsApps', 'claude-desktop.exe'),
    // The per-user install's stub, which starts the current version with the same arguments.
    win32.join(local, 'AnthropicClaude', 'claude.exe'),
  ];
}

/**
 * `launcher file`, detached so that quitting GateRook leaves a Claude Desktop it started running, and with its window
 * shown. True once the process runs; false when it can't start.
 */
export function startLauncher(launcher: string, file: string, spawn: Spawn = nodeSpawn): Promise<boolean> {
  return new Promise((resolve) => {
    let child: ChildProcess;
    try {
      child = spawn(launcher, [file], { detached: true, stdio: 'ignore', shell: false, windowsHide: false });
    } catch {
      resolve(false);
      return;
    }
    child.once('error', () => resolve(false));
    child.once('spawn', () => {
      child.unref();
      resolve(true);
    });
  });
}

async function present(file: string): Promise<boolean> {
  try {
    await access(file);
    return true;
  } catch {
    return false;
  }
}

/** Opens the extension in Claude Desktop: the launcher that started, or null when none is there or none starts. */
export async function openInClaudeDesktop(file: string, env: Readonly<Record<string, string | undefined>> = process.env,
  deps: ClaudeLaunchDeps = {}): Promise<string | null> {
  const exists = deps.exists ?? present;
  for (const launcher of claudeDesktopLaunchers(env)) {
    if (!(await exists(launcher))) continue;
    if (await startLauncher(launcher, file, deps.spawn)) return launcher;
  }
  return null;
}
