// Open at login and start hidden (ARCHITECTURE.md §7.1 "startup setLoginItemSettings (args:['--hidden'] on
// Windows, wasOpenedAtLogin on macOS, .desktop in ~/.config/autostart)"; Settings › General). The core stores the
// switches; main applies them to the OS and keeps a copy in desktop.json for the moment of launch, before the core
// answers. A development build never touches the OS: it would register the development Electron at login.
import { app } from 'electron';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { desktopExecArg } from './autostart.ts';
import type { Logger } from './log.ts';

export const HIDDEN_ARG = '--hidden';

export interface LoginSettings {
  readonly openAtLogin: boolean;
  readonly startHidden: boolean;
}

/** Whether this launch should stay in the tray: opened at login with "Start minimized" on. */
export function launchedHidden(startHidden: boolean | undefined): boolean {
  if (process.argv.includes(HIDDEN_ARG)) return true;
  if (process.platform === 'darwin') {
    try {
      return startHidden !== false && app.getLoginItemSettings().wasOpenedAtLogin === true;
    } catch {
      return false;
    }
  }
  return false;
}

export function applyLoginItem(s: LoginSettings, dev: boolean, log: Logger): void {
  if (dev) {
    log(`open at login ${s.openAtLogin ? 'on' : 'off'}, start hidden ${s.startHidden ? 'on' : 'off'}: not applied in a development build`);
    return;
  }
  if (process.platform === 'linux') {
    linuxAutostart(s);
    return;
  }
  app.setLoginItemSettings({
    openAtLogin: s.openAtLogin,
    ...(process.platform === 'win32' ? { args: s.startHidden ? [HIDDEN_ARG] : [] } : {}),
  });
}

/** `~/.config/autostart/mcp-gateway.desktop`, pointing at the AppImage when there is one. */
function linuxAutostart(s: LoginSettings): void {
  const dir = join(process.env['XDG_CONFIG_HOME'] ?? join(homedir(), '.config'), 'autostart');
  const file = join(dir, 'mcp-gateway.desktop');
  if (!s.openAtLogin) {
    rmSync(file, { force: true });
    return;
  }
  const target = process.env['APPIMAGE'] ?? process.execPath;
  if (/[\r\n]/.test(target)) return; // a path with a line break can't be written into a .desktop file
  mkdirSync(dir, { recursive: true });
  writeFileSync(file, ['[Desktop Entry]', 'Type=Application', 'Name=GateRook',
    `Exec=${desktopExecArg(target)}${s.startHidden ? ` ${HIDDEN_ARG}` : ''}`, 'X-GNOME-Autostart-enabled=true', 'NoDisplay=false', '']
    .join('\n'), { mode: 0o644 });
}
