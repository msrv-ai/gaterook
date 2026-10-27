// The app window (ARCHITECTURE.md §7.1 "Window and security", §9 "Electron hardening", "Self-approval through the GUI"):
// sandboxed renderer with context isolation and no Node, the UI from app://gw/ only, no new windows, no navigation
// away, no webviews; the kit's frameless titlebar; on the approval and consent screens content protection and the
// 700 ms input guard.
import { app, BrowserWindow, nativeTheme, shell, type WebContents } from 'electron';
import { ActivationGate, isDecisionRoute } from './activation-gate.ts';
import { APP_URL, isAppPage, safeUrl } from './app-protocol.ts';
import { allowedExternalUrl } from './external.ts';
import type { Logger } from './log.ts';
import { markImage } from './tray.ts';

export { APP_URL, safeUrl } from './app-protocol.ts';

/** The kit's canvas and text colors (design/scss/_tokens-css.scss), for the background and the Windows titlebar. */
const CANVAS = { light: '#F5F6F8', dark: '#0B0D12' };
const TEXT = { light: '#11141A', dark: '#E7E9EE' };
/** --gw-layout-titlebar-height. */
const TITLEBAR_HEIGHT = 40;

export interface AppWindowOptions {
  readonly preload: string;
  readonly dev: boolean;
  readonly log: Logger;
  /** Whether a sign-in waits for an answer here: the consent dialog is a decision screen wherever it shows. */
  readonly consentPending: () => boolean;
}

export interface AppWindow {
  readonly win: BrowserWindow;
  readonly gate: ActivationGate;
  /** The window shows a decision now (approvals route, or a pending sign-in). */
  decisionScreen(): boolean;
  /** Content protection and the guard follow a change of screen or of pending sign-ins. */
  refreshProtection(): void;
  /**
   * Whether main asked for content protection now. On macOS 27 with Electron 45 a window stays protected once it was
   * (`isContentProtected()` stays true after `setContentProtection(false)`): more protection, never less.
   */
  contentProtection(): boolean;
  show(): void;
}

export function createAppWindow(o: AppWindowOptions): AppWindow {
  const dark = nativeTheme.shouldUseDarkColors;
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 960,
    minHeight: 600,
    show: false,
    title: 'GateRook',
    icon: markImage(32, 'brand'),
    backgroundColor: dark ? CANVAS.dark : CANVAS.light,
    titleBarStyle: 'hidden',
    ...(process.platform === 'darwin'
      ? { trafficLightPosition: { x: 16, y: 13 } }
      : { titleBarOverlay: { color: dark ? CANVAS.dark : CANVAS.light, symbolColor: dark ? TEXT.dark : TEXT.light, height: TITLEBAR_HEIGHT } }),
    webPreferences: {
      preload: o.preload,
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
      nodeIntegrationInSubFrames: false,
      nodeIntegrationInWorker: false,
      webSecurity: true,
      allowRunningInsecureContent: false,
      webviewTag: false,
      spellcheck: false,
      navigateOnDragDrop: false,
      safeDialogs: true,
      enableWebSQL: false,
      experimentalFeatures: false,
      devTools: o.dev,
    },
  });

  const gate = new ActivationGate();
  let route = APP_URL;
  let protectedNow = false;
  const decisionScreen = (): boolean => isDecisionRoute(route) || o.consentPending();
  const refreshProtection = (): void => {
    const want = decisionScreen();
    if (want !== protectedNow) {
      protectedNow = want;
      win.setContentProtection(want);
      if (want) gate.rearm(); // a decision screen appeared
      o.log(`content protection ${want ? 'on' : 'off'}`);
    }
  };

  const contents = win.webContents;
  contents.setWindowOpenHandler(({ url }) => {
    const external = allowedExternalUrl(url);
    if (external) void shell.openExternal(external);
    else o.log(`refused to open a window for ${safeUrl(url)}`);
    return { action: 'deny' };
  });
  const stayInApp = (event: { preventDefault(): void }, url: string): void => {
    if (isAppPage(url)) return;
    event.preventDefault();
    o.log(`refused to navigate to ${safeUrl(url)}`);
  };
  contents.on('will-navigate', stayInApp);
  contents.on('will-redirect', stayInApp);
  contents.on('will-frame-navigate', (event) => {
    if (!isAppPage(event.url) && event.url !== 'about:blank') event.preventDefault();
  });
  contents.on('will-attach-webview', (event) => event.preventDefault());
  const onRoute = (_e: unknown, url: string, isMainFrame = true): void => {
    if (!isMainFrame) return; // only the window's own document says which screen shows
    route = url;
    const was = protectedNow;
    refreshProtection();
    // Another request on the same screen counts as the screen appearing (the UI rearms its own guard too).
    if (was && isDecisionRoute(url)) gate.rearm();
  };
  contents.on('did-navigate', (e, url) => onRoute(e, url, true));
  contents.on('did-navigate-in-page', (e, url, isMainFrame) => onRoute(e, url, isMainFrame));
  // No peer-to-peer connections: WebRTC could reach the network past the CSP and the request filter.
  contents.setWebRTCIPHandlingPolicy('disable_non_proxied_udp');

  let dropped = 0;
  contents.on('before-mouse-event', (event, mouse) => {
    if (!gate.admits({ kind: 'mouse', type: mouse.type }, decisionScreen())) {
      event.preventDefault();
      if (mouse.type === 'mouseDown' && dropped++ % 20 === 0) {
        o.log(`input guard: a click ${Math.round(700 - gate.remaining())} ms after the screen came forward was ignored`);
      }
    }
  });
  contents.on('before-input-event', (event, input) => {
    if (!gate.admits({ kind: 'key', type: input.type, key: input.key }, decisionScreen())) event.preventDefault();
  });
  win.on('focus', () => gate.rearm());
  win.on('show', () => gate.rearm());
  win.on('restore', () => gate.rearm());

  nativeTheme.on('updated', () => {
    if (process.platform === 'darwin' || win.isDestroyed()) return;
    const isDark = nativeTheme.shouldUseDarkColors;
    win.setTitleBarOverlay({ color: isDark ? CANVAS.dark : CANVAS.light, symbolColor: isDark ? TEXT.dark : TEXT.light,
      height: TITLEBAR_HEIGHT });
  });

  return {
    win,
    gate,
    decisionScreen,
    refreshProtection,
    contentProtection: () => protectedNow,
    show() {
      if (win.isMinimized()) win.restore();
      // A Review click, a deep link, "Open in app" in a chat: the person asked for the window, bring it forward.
      if (process.platform === 'darwin') app.focus({ steal: true });
      win.show();
      win.focus();
    },
  };
}

/** Rules for every web contents the app ever creates (a devtools window included): no new windows, no webviews. */
export function hardenAllContents(contents: WebContents, log: Logger): void {
  contents.setWindowOpenHandler(() => ({ action: 'deny' }));
  contents.on('will-attach-webview', (event) => {
    event.preventDefault();
    log('refused a webview');
  });
}
