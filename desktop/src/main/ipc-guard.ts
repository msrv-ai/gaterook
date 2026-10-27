// Who may call main over IPC (ARCHITECTURE.md §7.1 "IPC uses a fixed ipcMain.handle allowlist and validates
// event.senderFrame?.url.startsWith('app://gw/')", §9 "Electron hardening"): only the top frame of the app's own
// window, showing the app's own origin. A frame inside the page, another window, a page that navigated away (it
// can't: will-navigate stops it, but the check doesn't rely on that) — none of them reach a handler.

export const APP_ORIGIN = 'app://gw';

/** What main knows about the sender of an IPC message. */
export interface IpcSender {
  /** `event.senderFrame?.url`; undefined when the frame is gone. */
  readonly frameUrl: string | undefined;
  /** Whether the sending frame is its page's top frame (`senderFrame === sender.mainFrame`). */
  readonly isMainFrame: boolean;
  /** Whether the sender's webContents is the app window's own. */
  readonly isAppWindow: boolean;
}

export function isTrustedSender(sender: IpcSender): boolean {
  if (!sender.isAppWindow || !sender.isMainFrame || typeof sender.frameUrl !== 'string') return false;
  let url: URL;
  try {
    url = new URL(sender.frameUrl);
  } catch {
    return false;
  }
  // `app://gw/…` exactly: not `app://gw.evil/`, not `app://gw@x/`, not another scheme that happens to share a prefix.
  return url.protocol === 'app:' && url.host === 'gw' && url.username === '' && url.password === ''
    && sender.frameUrl.startsWith(`${APP_ORIGIN}/`);
}
