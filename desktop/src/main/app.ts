// GateRook's main process after the switch check (main.ts): the window and its app:// scheme, the core it starts
// and supervises, the signing proxy, the event stream that drives notifications, badge and tray, the token broker,
// the IPC of `window.gwDesktop`, updates, deep links (ARCHITECTURE.md §7.1–§7.4, A20).
import { app, dialog, ipcMain, Menu, nativeTheme, protocol, safeStorage, session, shell, type IpcMainEvent, type IpcMainInvokeEvent } from 'electron';
import { existsSync, mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import type { Broker } from '@gaterook/broker';
import { IPC, type DesktopCoreState, type GeneralSettings } from '../shared/contract.ts';
import { routeAppUrl, serveUi } from './app-protocol.ts';
import { finishApplicationReset, hasPendingReset, requestApplicationReset, type ResetPaths } from './application-reset.ts';
import { startTokenBroker } from './broker.ts';
import { callCore } from './core-client.ts';
import { coreArgv, coreEnv } from './core-command.ts';
import { CoreSupervisor, type ReadyCore } from './core-supervisor.ts';
import { deepLinkInArgv, deepLinkRoute, noticeRoute, SCHEME, uiOpenRoute } from './deep-links.ts';
import { desktopConfigPath, ensureHome, portProblem, portRequest, publicPortProblem, readDesktopConfig, writeDesktopConfig,
  type DesktopConfig } from './desktop-config.ts';
import { CoreEventFeed, type CoreEvent } from './events.ts';
import { allowedExternalUrl } from './external.ts';
import { planFileKey } from './file-key.ts';
import { FileKeyUnlock } from './file-key-unlock.ts';
import { NativeUnlockClient } from './native-unlock.ts';
import { OsFileKey } from './os-file-key.ts';
import { chooseFile, choosePrivateKeyFile, openClientConfig, openMcpb, reveal, type FilesOptions } from './files.ts';
import { isTrustedSender } from './ipc-guard.ts';
import { GoogleSignIn } from './google-sign-in.ts';
import { resolveLayout, type Layout } from './layout.ts';
import { applyLoginItem, launchedHidden } from './login.ts';
import { createLogger, type Logger } from './log.ts';
import { DesktopNotifications } from './notifications.ts';
import { canBind, findFreePort } from './ports.ts';
import { mayUnlock, proxyToCore } from './proxy.ts';
import { ShutdownSequence } from './shutdown.ts';
import { createFullBackup, restoreFullBackup, type FullBackupHost, type RestoreContext } from './full-backup.ts';
import { recoverFullRestore } from './full-backup-restore.ts';
import { privateDirectory } from './full-backup-files.ts';
import { findDangerousSwitches } from './switches.ts';
import { AppTray, markImage, setBadge } from './tray.ts';
import { Updates } from './updater.ts';
import { autoUpdateWanted } from './updates.ts';
import { APP_URL, createAppWindow, hardenAllContents, safeUrl, type AppWindow } from './window.ts';

/** Replaced by esbuild: true in a development bundle, false in a release (scripts/build.mjs). */
declare const __GW_DEV_BUILD__: boolean;
const DEV_BUILD = typeof __GW_DEV_BUILD__ === 'boolean' ? __GW_DEV_BUILD__ : false;

/** The Windows AppUserModelID: electron-builder's appId, so notifications belong to the installed app. */
export const APP_ID = 'ai.msrv.gaterook.desktop';

export function main(): Desktop | null {
  const layout = resolveLayout({ devBuild: DEV_BUILD, packaged: app.isPackaged, appPath: app.getAppPath(),
    resourcesPath: process.resourcesPath, homedir: homedir(), platform: process.platform, env: process.env });
  const log = createLogger(layout.home, layout.dev);
  app.setName(layout.dev ? 'GateRook Dev' : 'GateRook');
  if (layout.dev) {
    // A development build is another app to the OS: its own Keychain entry ("GateRook Dev Safe Storage"), never
    // the installed app's; and a development home gets its own Chromium profile, with it its own single-instance
    // lock: the smoke and a development window run side by side, each with its own core.
    app.setPath('userData', join(layout.home, 'electron'));
  } else {
    app.setPath('userData', join(app.getPath('appData'), 'GateRook'));
  }
  if (!app.requestSingleInstanceLock()) {
    log('another GateRook is running: it gets the arguments, this one quits');
    app.quit();
    return null;
  }
  protocol.registerSchemesAsPrivileged([
    // No V8 code cache for app:// scripts: a cache in the profile folder is code any program of the account could
    // replace, outside the asar's integrity check.
    { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true } },
  ]);
  app.setAppUserModelId(APP_ID);
  app.enableSandbox();
  // A development build doesn't claim gaterook:// for the development Electron on this machine.
  if (!layout.dev) app.setAsDefaultProtocolClient(SCHEME);

  const desktop = new Desktop(layout, log);
  app.on('open-url', (event, url) => {
    event.preventDefault();
    desktop.deepLink(url);
  });
  app.on('second-instance', (_event, argv) => desktop.secondInstance(argv));
  app.on('web-contents-created', (_event, contents) => hardenAllContents(contents, log));
  app.on('window-all-closed', () => undefined); // the tray keeps the app (and the clients' gateway) running
  app.on('activate', () => desktop.showWindow());
  app.on('before-quit', (event) => desktop.beforeQuit(event));
  // A terminal's Ctrl+C or the system's SIGTERM quits like the menu does: the core is stopped, not orphaned.
  for (const signal of ['SIGINT', 'SIGTERM'] as const) process.on(signal, () => app.quit());
  // A bug in main is logged, not shown as Electron's modal error box (which would hold the app, and the core's
  // clients, behind a dialog nobody may be there to close).
  process.on('uncaughtException', (e) => log(`uncaught: ${e.stack ?? e.message}`));
  process.on('unhandledRejection', (e) => log(`unhandled rejection: ${e instanceof Error ? e.stack ?? e.message : String(e)}`));
  const first = deepLinkInArgv(process.argv);
  if (first) desktop.deepLink(first);
  app.whenReady().then(async () => {
    if (existsSync(layout.home)) recoverFullRestore(realpathSync(layout.home));
    if (hasPendingReset(layout.home)) await desktop.finishReset();
    else await desktop.start();
  }).catch((e: Error) => {
    log(`could not start: ${e.stack ?? e.message}`);
    app.exit(1);
  });
  return desktop;
}

/** Everything main runs once the app is ready; the smoke (development builds) reaches in through `smokeHost()`. */
export class Desktop {
  readonly layout: Layout;
  readonly log: Logger;
  readonly supervisor: CoreSupervisor;
  private readonly fileKeyUnlock: FileKeyUnlock;
  private readonly nativeUnlock: NativeUnlockClient;
  private readonly osFileKey: OsFileKey;
  private readonly events: CoreEventFeed;
  private readonly googleSignIn = new GoogleSignIn();
  private window: AppWindow | null = null;
  private windowReady = false;
  private tray: AppTray | null = null;
  private notifications: DesktopNotifications | null = null;
  private updates: Updates | null = null;
  private broker: Broker | null = null;
  private config: DesktopConfig;
  private pendingLink: string | null = null;
  /** The page asked for the core's state since it last loaded: pushes reach it. */
  private rendererSubscribed = false;
  /** The page listens to deep links since it last loaded (it asked for the pending one): a link can be pushed. */
  private deepLinksSubscribed = false;
  /** The instance of the core whose `ready` the page has: the proxy opens for that one only (§7.1). */
  private readyDeliveredFor: string | null = null;
  private relayEvents = false;
  private readonly consents = new Set<string>();
  private readonly requestedConsents = new Set<string>();
  private consentRevision = 0;
  private quitting = false;
  private stopped = false;
  private shutdown: ShutdownSequence | null = null;
  private resetting = false;
  private fullBackupBusy = false;
  /** A development smoke run (smoke.ts): no focus stealing, no notifications on screen. */
  private readonly smoke: boolean;
  private readonly smokeConsole: string[] = [];

  constructor(layout: Layout, log: Logger) {
    this.layout = layout;
    this.log = log;
    this.smoke = layout.dev && !!process.env['GW_SMOKE'];
    this.config = readDesktopConfig(layout.home).config;
    this.nativeUnlock = new NativeUnlockClient(join(app.isPackaged ? process.resourcesPath : join(app.getAppPath(), 'dist'),
      'native', process.platform === 'win32' ? 'gaterook-unlock.exe' : 'gaterook-unlock'));
    this.osFileKey = new OsFileKey({ home: layout.home, platform: process.platform, safe: safeStorage,
      native: this.nativeUnlock.call,
      windowHandle: () => {
        const handle = this.window?.win.getNativeWindowHandle();
        if (!handle) throw new Error('The unlock window is unavailable.');
        return handle.length === 8 ? handle.readBigUInt64LE().toString() : handle.readUInt32LE().toString();
      },
      keychainService: `${layout.dev ? 'GateRook Dev' : 'GateRook'} Safe Storage`,
    });
    this.fileKeyUnlock = new FileKeyUnlock({
      load: () => this.osFileKey.load(),
      changed: (state) => this.onCoreState(state ?? this.supervisor.state()),
      log: (line) => log(line),
      denied: () => app.quit(),
      details: () => this.osFileKey.details(),
    });
    this.supervisor = new CoreSupervisor({
      version: app.getVersion(),
      home: layout.home,
      ports: () => ({ port: this.config.port, publicPort: this.config.publicPort }),
      command: () => {
        const { file, args } = coreArgv(layout.core, { home: layout.home, resources: layout.resources, bridgeJar: layout.bridgeJar });
        return { file, args, env: coreEnv(process.env, layout.dev) };
      },
      fileKey: () => this.prepareFileKey(),
      log: (line) => log(line),
      ...(layout.dev && process.env['GW_DEV_CORE_STDERR'] === '1' ? { stderrLine: (l: string) => process.stderr.write(`[core] ${l}\n`) } : {}),
    });
    this.events = new CoreEventFeed({
      onEvent: (e) => this.onCoreEvent(e),
      // The stream (re)opened: what happened meanwhile may be missed; ask for the counts and the waiting sign-ins.
      onOpen: () => {
        void this.refreshCounters();
        const core = this.supervisor.endpoint();
        if (core && !core.locked) void this.syncConsents(core);
      },
      log: (line) => log(line),
    });
  }

  async start(): Promise<void> {
    // Use the macOS export's transparent margins so the Dock matches the visual size of neighboring apps.
    if (process.platform === 'darwin') app.dock?.setIcon(markImage(512, 'macos'));
    ensureHome(this.layout.home);
    const { config, problem } = readDesktopConfig(this.layout.home);
    if (problem) this.log(problem);
    this.config = config;
    if (!existsSync(desktopConfigPath(this.layout.home))) writeDesktopConfig(this.layout.home, config);
    this.log(`GateRook ${app.getVersion()} starting (${this.layout.dev ? 'development build' : 'release'}), `
      + `home ${this.layout.home}, UI ${this.layout.uiRoot}, core ${this.layout.core.kind}`);

    this.hardenSession();
    this.registerProtocol();
    this.registerIpc();
    app.setAboutPanelOptions({ applicationName: 'GateRook', applicationVersion: app.getVersion(),
      copyright: 'Copyright © 2026 GateRook' });
    Menu.setApplicationMenu(appMenu(this.layout.dev));

    const window = createAppWindow({ preload: this.layout.preload, dev: this.layout.dev, log: this.log,
      consentPending: () => this.consents.size > 0 });
    this.window = window;
    // Behind the CSP, a second wall: nothing the window asks for leaves the app (main's own requests to the core and
    // the updater's are not the window's and pass).
    const appContents = window.win.webContents.id;
    session.defaultSession.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*', 'ws://*/*', 'wss://*/*', 'file://*/*'] },
      (details, callback) => {
        const refuse = details.webContentsId === appContents;
        if (refuse) this.log(`refused a ${details.resourceType} request of the window to ${safeUrl(details.url)}`);
        callback({ cancel: refuse });
      });
    window.win.on('close', (event) => {
      if (this.quitting) return;
      event.preventDefault();
      window.win.hide();
    });
    window.win.webContents.on('did-navigate', (_event, url) => {
      // A new document in the window (a reload, a crash recovery): it subscribes again, and the proxy waits until it
      // has `ready` again. Not `did-start-loading`: that also fires for loads inside the page that keep the document.
      this.log(`page loaded: ${safeUrl(url)}`);
      this.rendererSubscribed = false;
      this.deepLinksSubscribed = false;
      this.readyDeliveredFor = null;
      this.relayEvents = false;
    });
    window.win.webContents.on('render-process-gone', (_event, details) => {
      this.log(`renderer gone (${details.reason}); reloading`);
      if (!this.quitting) setTimeout(() => window.win.loadURL(APP_URL), 1000).unref();
    });
    if (this.smoke) {
      // The smoke reads what the page said: a CSP or Trusted Types refusal in normal use is a finding.
      window.win.webContents.on('console-message', (event) => {
        if (event.level === 'error' || event.level === 'warning') this.smokeConsole.push(`${event.level}: ${event.message.slice(0, 500)}`);
      });
    }
    const hidden = launchedHidden(this.config.startHidden) || (this.smoke && process.env['GW_SMOKE_HIDDEN'] === '1');
    window.win.once('ready-to-show', () => {
      this.windowReady = true;
      if (!hidden || (this.fileKeyUnlock.state() && !this.smoke)) this.showWindow();
    });
    void window.win.loadURL(APP_URL);

    this.tray = new AppTray({
      open: () => this.showWindow(),
      openApprovals: () => this.openRoute('approvals'),
      quit: () => app.quit(),
    });
    this.notifications = new DesktopNotifications({
      log: this.log,
      windowFocused: () => !!this.window && this.window.win.isVisible() && this.window.win.isFocused(),
      review: (route) => this.openRoute(noticeRoute(route) ?? 'approvals'),
      focus: () => this.showWindow(),
      deny: async (path, body) => {
        const core = this.supervisor.endpoint();
        if (!core) throw new Error('The core is not running');
        return (await callCore(core, 'POST', path, body)).status;
      },
      report: (event, detail) => {
        const core = this.supervisor.endpoint();
        if (core) void callCore(core, 'POST', '/internal/notifications/desktop', { event, ...(detail ? { detail } : {}) })
          .catch((e: Error) => this.log(`notification report failed: ${e.message}`));
      },
      badge: (pending) => this.setPending(pending),
      display: !this.smoke,
    });
    this.updates = new Updates({
      packaged: app.isPackaged,
      resourcesPath: process.resourcesPath,
      log: this.log,
      push: (state) => this.send(IPC.updateStatePush, state),
      autoUpdate: async () => {
        const general = await this.generalSettings();
        if (general) this.rememberAutoUpdate(general.autoUpdate);
        return autoUpdateWanted(general?.autoUpdate, this.config.autoUpdate);
      },
      prepareInstall: async () => {
        if (this.resetting || this.quitting || this.fullBackupBusy) throw new Error('GateRook is busy or closing.');
        // Squirrel closes windows before before-quit; let our close handler release them.
        this.quitting = true;
        await this.stopForQuit();
      },
      installFailed: (message) => {
        this.log(`update install failed: ${message}`);
        dialog.showErrorBox('GateRook could not install the update',
          `${message}\n\nGateRook will reopen. Check for updates and try again.`);
        app.relaunch();
        app.quit();
      },
    });
    this.updates.schedule();

    this.supervisor.onState((state) => this.onCoreState(state));
    this.supervisor.onReady((core) => this.onCoreReady(core));
    this.supervisor.onDown(() => this.onCoreDown());
    this.broker = await startTokenBroker({ home: this.layout.home, core: () => this.supervisor.endpoint(), log: this.log });
    await this.supervisor.start();
  }

  // ── The core ────────────────────────────────────────────────────────────────────────────────────────────────

  private async prepareFileKey(): Promise<{ fileKey: Buffer | null; protection: string }> {
    const off = this.layout.dev && process.env['GW_DESKTOP_PROTECTION'] === 'off';
    if (!off) await this.osFileKey.initialize();
    const plan = await (off ? planFileKey(this.layout.home, null) : this.fileKeyUnlock.prepare());
    if (plan.note) this.log(`OS protection: ${plan.note}`);
    return { fileKey: plan.fileKey, protection: plan.protection };
  }

  private onCoreState(state: DesktopCoreState): void {
    if (this.resetting) return;
    if (!(this.fullBackupBusy && state.kind === 'starting')) this.send(IPC.coreStatePush, state);
    // A hidden login launch may finish loading before the broker starts and the unlock gate is published.
    if (state.kind === 'unlock-required' && !state.unlocking && !this.smoke && this.windowReady) this.showWindow();
    if (state.kind === 'ready' && this.rendererSubscribed) this.readyDeliveredFor = this.supervisor.endpoint()?.instanceNonce ?? null;
    this.tray?.update({ core: state.kind === 'ready' ? (state.locked ? 'locked' : null)
      : state.kind === 'unlock-required' ? 'locked' : state.kind === 'starting' ? 'starting' : state.kind === 'failed' ? 'didn’t start' : 'stopped' });
  }

  private onCoreReady(core: ReadyCore): void {
    if (this.resetting) return;
    this.events.start(core);
    this.notifications?.coreRestarted();
    void this.afterReady(core);
  }

  private async afterReady(core: ReadyCore): Promise<void> {
    if (core.locked) return; // a locked core answers only the master key's routes
    await this.refreshCounters();
    await this.syncConsents(core);
    const general = await this.generalSettings();
    if (general) {
      this.storeLoginSettings({ openAtLogin: general.openAtLogin, startHidden: general.startHidden });
      this.rememberAutoUpdate(general.autoUpdate);
    }
  }

  /** Main's copy of "Check for updates automatically", for the hours the core doesn't answer. */
  private rememberAutoUpdate(value: boolean): void {
    if (this.resetting || this.quitting) return;
    if (this.config.autoUpdate === value) return;
    this.config = { ...this.config, autoUpdate: value };
    try {
      writeDesktopConfig(this.layout.home, this.config);
    } catch (e) {
      this.log(`desktop.json: ${(e as Error).message}`);
    }
  }

  private onCoreDown(): void {
    this.events.stop();
    this.readyDeliveredFor = null;
    this.consents.clear();
    this.requestedConsents.clear();
    this.consentRevision++;
    this.window?.refreshProtection();
  }

  private onCoreEvent(e: CoreEvent): void {
    if (this.resetting || this.fullBackupBusy) return;
    if (this.relayEvents) this.send(IPC.eventPush, { type: e.type, data: e.data, id: e.id });
    const data = (typeof e.data === 'object' && e.data !== null ? e.data : {}) as Record<string, unknown>;
    switch (e.type) {
      case 'notify.desktop':
        this.notifications?.onNotice(e.data);
        break;
      case 'approval.resolved':
        this.notifications?.resolved(data['id']);
        void this.refreshCounters();
        break;
      case 'approval.requested':
        void this.refreshCounters();
        break;
      case 'oauth.consent.requested': {
        const core = this.supervisor.endpoint();
        if (core && !core.locked && typeof data['id'] === 'string') void this.syncConsents(core, data['id']);
        break;
      }
      case 'oauth.consent.resolved': {
        this.consentRevision++;
        if (typeof data['id'] === 'string') {
          this.consents.delete(data['id']);
          this.requestedConsents.delete(data['id']);
        }
        this.window?.refreshProtection();
        this.notifications?.consentResolved(data['id']);
        const core = this.supervisor.endpoint();
        if (core && !core.locked) void this.syncConsents(core);
        break;
      }
      case 'ui.open': {
        // "Open in app" in Slack or Telegram (§7.4 `ui.open {path}`): the window comes forward on that path, through
        // the allowlist a notification's Review uses; any other path only brings the window forward.
        const route = uiOpenRoute(e.data);
        if (route) this.openRoute(route);
        else {
          this.showWindow();
          this.window?.gate.rearm();
        }
        break;
      }
      case 'tunnel.state':
        if (typeof data['state'] === 'string') this.tray?.update({ tunnel: data['state'] });
        break;
      case 'core.restart-required':
        void this.onRestartRequired(data);
        break;
      default:
        break;
    }
  }

  /** Settings › Local API or Tunnel changed what the core starts with: new ports into desktop.json, then a restart. */
  private async onRestartRequired(data: Record<string, unknown>): Promise<void> {
    const port = Number.isInteger(data['port']) ? (data['port'] as number) : this.config.port;
    const publicPort = Number.isInteger(data['publicPort']) ? (data['publicPort'] as number) : this.config.publicPort;
    const problem = portProblem(port, publicPort) ?? publicPortProblem(publicPort, port);
    if (problem) {
      this.log(`core.restart-required ignored: ${problem}`);
      return;
    }
    if (port !== this.config.port || publicPort !== this.config.publicPort) {
      this.config = { ...this.config, port, publicPort };
      writeDesktopConfig(this.layout.home, this.config);
    }
    this.log(`restarting the core (${String(data['reason'] ?? 'settings changed').slice(0, 40)}) on 127.0.0.1:${port}`);
    await this.supervisor.restart();
  }

  private async refreshCounters(): Promise<void> {
    const core = this.supervisor.endpoint();
    if (!core || core.locked) return;
    try {
      const answer = await callCore<{ pendingApprovals?: unknown; tunnel?: unknown }>(core, 'GET', '/api/nav/counters');
      if (answer.status !== 200) return;
      if (Number.isInteger(answer.body.pendingApprovals)) this.setPending(answer.body.pendingApprovals as number);
      if (typeof answer.body.tunnel === 'string') this.tray?.update({ tunnel: answer.body.tunnel });
    } catch (e) {
      this.log(`counters: ${(e as Error).message}`);
    }
  }

  private async syncConsents(core: ReadyCore, requestedId?: string): Promise<void> {
    if (requestedId) this.requestedConsents.add(requestedId);
    const revision = ++this.consentRevision;
    try {
      const answer = await callCore<unknown>(core, 'GET', '/api/oauth/consents');
      if (revision !== this.consentRevision || core.instanceNonce !== this.supervisor.endpoint()?.instanceNonce) return;
      if (answer.status !== 200 || !Array.isArray(answer.body)) return;
      this.consents.clear();
      for (const c of answer.body as { id?: unknown; ingress?: unknown }[]) {
        if (c.ingress !== 'LOCAL' || typeof c.id !== 'string') continue;
        this.consents.add(c.id);
        if (this.requestedConsents.has(c.id)) {
          this.window?.gate.rearm();
          this.notifications?.onConsent(c);
        }
      }
      this.requestedConsents.clear();
      this.window?.refreshProtection();
    } catch {
      // A core without K3's routes: no sign-ins to wait for.
    }
  }

  private setPending(pending: number): void {
    this.tray?.update({ pending });
    setBadge(this.window?.win ?? null, pending);
  }

  private async generalSettings(): Promise<{ openAtLogin: boolean; startHidden: boolean; autoUpdate: boolean } | null> {
    const core = this.supervisor.endpoint();
    if (!core || core.locked) return null;
    try {
      const answer = await callCore<{ values?: Record<string, unknown> }>(core, 'GET', '/api/settings/general');
      const v = answer.status === 200 ? answer.body.values : undefined;
      if (!v) return null;
      return { openAtLogin: v['openAtLogin'] !== false, startHidden: v['startHidden'] !== false, autoUpdate: v['autoUpdate'] !== false };
    } catch {
      return null;
    }
  }

  private storeLoginSettings(s: { openAtLogin: boolean; startHidden: boolean }): void {
    if (this.resetting || this.quitting) return;
    if (this.config.openAtLogin !== s.openAtLogin || this.config.startHidden !== s.startHidden) {
      this.config = { ...this.config, ...s };
      writeDesktopConfig(this.layout.home, this.config);
    }
    applyLoginItem(s, this.layout.dev, this.log);
  }

  // ── The window, deep links ──────────────────────────────────────────────────────────────────────────────────

  showWindow(): void {
    // The development smoke shows the window without taking the person's focus.
    if (this.smoke) this.window?.win.showInactive();
    else this.window?.show();
  }

  /** Brings the window forward on an allowlisted route (navigation only, §7.1). */
  openRoute(route: string): void {
    if (route === 'approvals') {
      void this.openQueue();
      return;
    }
    this.showWindow();
    this.window?.gate.rearm();
    this.pendingLink = route;
    // Pushed once the page listens to deep links (it asked for the pending one), else kept until it asks.
    const contents = this.window?.win.webContents;
    if (this.deepLinksSubscribed && contents && !contents.isDestroyed()) {
      contents.send(IPC.deepLinkPush, route);
      this.pendingLink = null;
    }
  }

  /**
   * The queue (a burst summary's Review, the tray's Approvals): the UI takes a request, not the bare queue, as a link
   * from outside (ui/src/app/shell/deep-links.ts), so main opens the first waiting one — the queue is beside it.
   */
  private async openQueue(): Promise<void> {
    const core = this.supervisor.endpoint();
    let first: string | null = null;
    if (core && !core.locked) {
      try {
        const answer = await callCore<{ items?: { id?: unknown }[] }>(core, 'GET', '/api/approvals?status=pending&limit=1');
        const id = answer.status === 200 ? answer.body.items?.[0]?.id : undefined;
        first = typeof id === 'string' ? deepLinkRoute(`approvals/${id}`) : null;
      } catch (e) {
        this.log(`approvals queue: ${(e as Error).message}`);
      }
    }
    if (first) this.openRoute(first);
    else this.showWindow();
  }

  deepLink(link: string): void {
    const route = deepLinkRoute(link);
    if (!route) {
      this.log('ignored a deep link that leads nowhere allowed');
      return;
    }
    if (!this.window) {
      this.pendingLink = route; // before the window exists: delivered once the page asks
      return;
    }
    this.openRoute(route);
  }

  secondInstance(argv: string[]): void {
    // The other process checked its own switches before it got here; a launch that carries dangerous ones is
    // ignored all the same (only the deep link would be used, never the switches).
    if (findDangerousSwitches(argv, () => false, { allowNoSandbox: false }).length > 0) {
      this.log('ignored a second launch with dangerous switches');
      return;
    }
    const link = deepLinkInArgv(argv);
    if (link) this.deepLink(link);
    else this.showWindow();
  }

  private send(channel: string, payload: unknown): void {
    const contents = this.window?.win.webContents;
    if (!contents || contents.isDestroyed() || !this.rendererSubscribed) return;
    contents.send(channel, payload);
  }

  // ── app:// and IPC ──────────────────────────────────────────────────────────────────────────────────────────

  private hardenSession(): void {
    const s = session.defaultSession;
    // The only permission the UI uses: writing (sanitized) text to the clipboard.
    s.setPermissionRequestHandler((_contents, permission, callback) => callback(permission === 'clipboard-sanitized-write'));
    s.setPermissionCheckHandler((_contents, permission) => permission === 'clipboard-sanitized-write');
    s.setDevicePermissionHandler(() => false);
    s.setDisplayMediaRequestHandler((_request, callback) => callback({}));
    s.on('will-download', (event) => event.preventDefault());
  }

  private registerProtocol(): void {
    protocol.handle('app', async (request) => {
      let url: URL;
      try {
        url = new URL(request.url);
      } catch {
        return new Response(null, { status: 404 });
      }
      const route = routeAppUrl(url);
      if (route.kind === 'api') {
        const core = this.supervisor.endpoint();
        // Only once the page got `ready` from this core: before that, the status screen owns the window.
        const open = !this.resetting && !this.fullBackupBusy && core && this.readyDeliveredFor === core.instanceNonce ? core : null;
        const answer = await proxyToCore(request, route.target, open);
        // Restore or Create of the master key unlocks the core without a restart: main asks it again.
        if (open?.locked && mayUnlock(request.method, route.target, answer.status)) void this.recheckLock(open);
        return answer;
      }
      return serveUi(this.layout.uiRoot, route);
    });
  }

  /**
   * After a Restore or Create through the window: whether the locked core is unlocked now (`GET /api/health`). Then
   * main does what it skipped while the core was locked — the tray, the counters, the waiting sign-ins, General.
   */
  private async recheckLock(core: ReadyCore): Promise<void> {
    try {
      const answer = await callCore<{ locked?: unknown } | null>(core, 'GET', '/api/health');
      const health = answer.body;
      if (answer.status !== 200 || typeof health !== 'object' || health === null || health.locked) return;
      const unlocked = this.supervisor.unlocked(core.instanceNonce);
      if (unlocked) await this.afterReady(unlocked);
    } catch (e) {
      this.log(`health after a master key change: ${(e as Error).message}`);
    }
  }

  private trusted(event: IpcMainEvent | IpcMainInvokeEvent): boolean {
    const frame = event.senderFrame;
    return isTrustedSender({
      frameUrl: frame?.url,
      isMainFrame: !!frame && frame.parent === null,
      isAppWindow: !!this.window && event.sender === this.window.win.webContents,
    });
  }

  private registerIpc(): void {
    const files: FilesOptions = { home: this.layout.home, core: () => this.supervisor.endpoint(),
      window: () => this.window?.win ?? null, log: this.log };
    const handle = (channel: string, fn: (...args: unknown[]) => unknown): void => {
      ipcMain.handle(channel, async (event, ...args: unknown[]) => {
        if (!this.trusted(event)) {
          this.log(`IPC ${channel} refused: not the app's window`);
          throw new Error('Not allowed');
        }
        if (this.resetting || this.quitting || (this.fullBackupBusy
            && channel !== IPC.coreState && channel !== IPC.updateState && channel !== IPC.pendingDeepLink)) {
          throw new Error('GateRook is busy or closing.');
        }
        return fn(...args);
      });
    };
    const on = (channel: string, fn: (...args: unknown[]) => void): void => {
      ipcMain.on(channel, (event, ...args: unknown[]) => {
        if (!this.trusted(event)) {
          this.log(`IPC ${channel} refused: not the app's window`);
          return;
        }
        if (this.resetting || this.quitting || this.fullBackupBusy) return;
        fn(...args);
      });
    };

    handle(IPC.coreState, () => {
      this.rendererSubscribed = true;
      const unlock = this.fileKeyUnlock.state();
      if (unlock) return [unlock];
      const core = this.supervisor.endpoint();
      if (this.supervisor.state().kind === 'ready' && core) this.readyDeliveredFor = core.instanceNonce;
      return this.supervisor.replay();
    });
    handle(IPC.pendingDeepLink, () => {
      // The page subscribes to deep links (preload onDeepLink): from now on they are pushed.
      this.deepLinksSubscribed = true;
      const link = this.pendingLink;
      this.pendingLink = null;
      return link;
    });
    handle(IPC.updateState, () => this.updates?.current() ?? null);
    handle(IPC.openExternal, async (url) => {
      const allowed = allowedExternalUrl(url);
      if (!allowed) throw new Error('GateRook opens only https links to documentation.');
      await shell.openExternal(allowed);
    });
    handle(IPC.saveFile, (purpose) => chooseFile(files, purpose, true));
    handle(IPC.openFile, (purpose) => chooseFile(files, purpose, false));
    handle(IPC.choosePrivateKeyFile, () => choosePrivateKeyFile(files));
    handle(IPC.signInToGoogle, (...args) => {
      if (args.length) throw new Error('Google sign-in does not accept arguments.');
      return this.googleSignIn.signIn();
    });
    handle(IPC.openMcpb, (id) => openMcpb(files, id));
    handle(IPC.openClientConfig, (id) => openClientConfig(files, id));
    handle(IPC.reveal, (kind) => reveal(files, kind));
    handle(IPC.applyGeneral, (raw) => {
      const s = (typeof raw === 'object' && raw !== null ? raw : {}) as GeneralSettings;
      this.storeLoginSettings({
        openAtLogin: typeof s.openAtLogin === 'boolean' ? s.openAtLogin : this.config.openAtLogin !== false,
        startHidden: typeof s.startHidden === 'boolean' ? s.startHidden : this.config.startHidden !== false,
      });
    });
    handle(IPC.restartCore, async () => {
      void this.supervisor.restart();
    });
    handle(IPC.createFullBackup, (passphrase) => this.fullBackup(false, passphrase));
    handle(IPC.restoreFullBackup, (passphrase, confirmation) => this.fullBackup(true, passphrase, confirmation));
    handle(IPC.resetApplication, (confirmation) => {
      if (this.quitting) throw new Error('GateRook is closing.');
      requestApplicationReset(this.resetPaths(), confirmation, this.supervisor.pid());
      return this.finishReset();
    });
    handle(IPC.unlock, () => {
      if (this.quitting) return;
      return this.fileKeyUnlock.unlock();
    });
    handle(IPC.usePort, async (raw) => {
      // A number moves the local API; {port?, publicPort?} also moves the tunnel's public listener, which the core
      // binds at start too (a port someone holds there stops it the same way).
      const asked = portRequest(raw, this.config);
      if ('problem' in asked) throw new Error(asked.problem);
      const running = this.supervisor.endpoint();
      for (const which of asked.named) {
        const port = asked[which];
        const ours = !!running && (running.port === port || running.publicPort === port);
        if (!ours && !(await canBind(port))) throw new Error(`Port ${port} is in use. Choose another one.`);
      }
      if (this.resetting || this.quitting) throw new Error('GateRook is closing.');
      this.config = { ...this.config, port: asked.port, publicPort: asked.publicPort };
      writeDesktopConfig(this.layout.home, this.config);
      this.log(`Use another port: ${asked.port} (public ${asked.publicPort})`);
      void this.supervisor.restart();
    });
    // Never a port the core is configured with: neither the tunnel's nor the local API's.
    handle(IPC.findFreePort, (from) => findFreePort(Number(from), [this.config.port, this.config.publicPort]));
    handle(IPC.checkForUpdates, async () => {
      await this.updates?.check();
    });
    handle(IPC.installUpdate, async () => {
      await this.updates?.install();
    });
    on(IPC.setNativeTheme, (theme) => {
      if (theme === 'system' || theme === 'light' || theme === 'dark') nativeTheme.themeSource = theme;
    });
    on(IPC.quit, () => app.quit());
    on(IPC.relayEvents, (enabled) => {
      this.relayEvents = enabled === true;
    });
  }

  // ── Quit ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Imported SQLite data is validated in an isolated, empty home, including when the main database cannot start. */
  private async fullRestoreContext(): Promise<RestoreContext> {
    const paused = !this.supervisor.endpoint();
    if (paused) await this.supervisor.pause();
    let home: string | null = null;
    let helper: CoreSupervisor | null = null;
    try {
      const temporary = join(realpathSync(this.layout.home), 'tmp');
      privateDirectory(temporary);
      home = mkdtempSync(join(temporary, 'full-validator-'));
      const validatorHome = home;
      const port = await findFreePort(17465, [this.config.port, this.config.publicPort]);
      if (port === null) throw new Error('No local port is available for backup validation.');
      const publicPort = await findFreePort(port + 1, [port, this.config.port, this.config.publicPort]);
      if (publicPort === null) throw new Error('No second local port is available for backup validation.');
      helper = new CoreSupervisor({ version: app.getVersion(), home: validatorHome, ports: () => ({ port, publicPort }),
        command: () => {
          const command = coreArgv(this.layout.core, { home: validatorHome, resources: this.layout.resources, bridgeJar: this.layout.bridgeJar });
          return { ...command, env: coreEnv(process.env, false) };
        },
        fileKey: () => this.prepareFileKey(), log: () => undefined, restartDelaysMs: [],
      });
      const validator = helper;
      const prepared = new Promise<ReadyCore>((resolve, reject) => {
        validator.onReady((core) => resolve(core));
        validator.onState((state) => {
          if (state.kind === 'failed' || state.kind === 'stopped') reject(new Error('The recovery validator could not start. Check the core installation and try again.'));
        });
      });
      await validator.start();
      const core = await prepared;
      return { home: validatorHome, core, current: () => validator.endpoint(), close: async () => {
        try {
          await validator.stop();
          rmSync(validatorHome, { recursive: true, force: true });
        } finally { if (paused) await this.supervisor.resume(false); }
      } };
    } catch (error) {
      try {
        await helper?.stop();
        if (home) rmSync(home, { recursive: true, force: true });
      } finally { if (paused) await this.supervisor.resume(false); }
      throw error;
    }
  }

  private async fullBackup(restore: boolean, passphrase: unknown, confirmation?: unknown): Promise<{ cancelled: boolean; fileName?: string }> {
    if (this.fullBackupBusy) throw new Error('A full backup or restore is already running.');
    this.fullBackupBusy = true;
    let stopped = false;
    const host: FullBackupHost = {
      home: this.layout.home, version: app.getVersion(),
      saveDialog: (options) => this.window ? dialog.showSaveDialog(this.window.win, options) : dialog.showSaveDialog(options),
      openDialog: (options) => this.window ? dialog.showOpenDialog(this.window.win, options) : dialog.showOpenDialog(options),
      confirmRestore: (options) => this.window ? dialog.showMessageBox(this.window.win, options) : dialog.showMessageBox(options),
      core: () => this.supervisor.endpoint(),
      restoreContext: () => this.fullRestoreContext(),
      pause: async () => { await this.supervisor.pause(); },
      resume: async () => { await this.supervisor.resume(); },
      stop: async () => {
        this.updates?.stop();
        this.events.stop();
        this.googleSignIn.close();
        await this.supervisor.stop();
        stopped = true;
        await this.broker?.close();
        this.broker = null;
      },
      restored: async () => {
        applyLoginItem({ openAtLogin: false, startHidden: false }, this.layout.dev, this.log);
        this.window?.win.destroy();
        this.window = null;
        await session.defaultSession.closeAllConnections();
        await session.defaultSession.clearStorageData();
        await session.defaultSession.clearAuthCache();
        session.defaultSession.flushStorageData();
        await dialog.showMessageBox({ type: 'info', title: 'Full backup restored',
          message: 'Open GateRook again to use the restored data.',
          detail: 'Reconnect AI apps and review restored plugins, MCP servers, and external file references.', buttons: ['Close'] });
      },
    };
    try {
      return restore ? await restoreFullBackup(host, passphrase, confirmation) : await createFullBackup(host, passphrase);
    } catch (error) {
      if (stopped) dialog.showErrorBox('GateRook restore needs attention',
        'GateRook will close. Reopen it to complete recovery before using this gateway.\n\n'
        + (error instanceof Error ? error.message : 'The restore did not finish.'));
      throw error;
    } finally {
      this.fullBackupBusy = false;
      if (stopped) app.quit();
    }
  }

  private resetPaths(): ResetPaths {
    return { home: this.layout.home, userData: app.getPath('userData'),
      protectedPaths: [homedir(), app.getAppPath(), process.resourcesPath, this.layout.uiRoot,
        ...(this.layout.resources ? [this.layout.resources] : [])] };
  }

  /** Also runs before normal startup when the last confirmed reset was interrupted. */
  async finishReset(): Promise<void> {
    if (this.resetting) return;
    this.resetting = true;
    this.readyDeliveredFor = null;
    this.relayEvents = false;
    try {
      await finishApplicationReset(this.resetPaths(), {
        stop: async () => {
          this.updates?.stop();
          this.events.stop();
          this.fileKeyUnlock.close();
          this.googleSignIn.close();
          await this.supervisor.stop();
          await this.broker?.close();
          this.broker = null;
        },
        disableLogin: () => applyLoginItem({ openAtLogin: false, startHidden: false }, this.layout.dev, this.log),
        removeKey: () => this.osFileKey.reset(),
        clearProfile: async () => {
          // No renderer may write preferences back after storage was cleared. Keep Chromium's profile files
          // open and let its APIs clear them, including on Windows where unlinking an active profile fails.
          this.window?.win.destroy();
          this.window = null;
          await session.defaultSession.closeAllConnections();
          await session.defaultSession.clearData();
          await session.defaultSession.clearStorageData();
          await session.defaultSession.clearAuthCache();
          await session.defaultSession.clearCache();
          session.defaultSession.flushStorageData();
        },
      });
      this.stopped = true;
    } catch (error) {
      // The marker remains; never start a core against a partially reset home. A native dialog remains usable
      // even when the renderer has already been destroyed or the reset was resumed before creating a window.
      this.log(`application reset could not finish: ${(error as Error).message}`);
      dialog.showErrorBox('GateRook reset could not finish',
        'GateRook will close. The reset will be retried the next time you open the app.\n\n'
        + ((error as Error).message || 'Some local data could not be removed.'));
    } finally {
      this.nativeUnlock.close();
      this.tray?.destroy();
      this.tray = null;
      this.quitting = true;
      this.resetting = false;
      app.quit();
    }
  }

  /** Quit stops the core first (stdin EOF), then lets Electron go. */
  beforeQuit(event: { preventDefault(): void }): void {
    if (this.resetting || this.fullBackupBusy) { event.preventDefault(); return; }
    this.quitting = true;
    if (this.stopped || this.shutdown?.completed) return;
    event.preventDefault();
    // Repeated quit events must not bypass an in-flight shutdown, including an update's shutdown.
    if (this.shutdown) return;
    // A cleanup failure aborts an update, but must not trap an ordinary quit after all resources were attempted.
    void this.stopForQuit().then(() => app.quit(), () => app.quit());
  }

  private stopForQuit(): Promise<void> {
    this.shutdown ??= new ShutdownSequence([
      { name: 'updates', run: () => this.updates?.stop() },
      { name: 'events', run: () => this.events.stop() },
      { name: 'unlock', run: () => this.fileKeyUnlock.close() },
      { name: 'native unlock', run: () => this.nativeUnlock.close() },
      { name: 'Google sign-in', run: () => this.googleSignIn.close() },
      { name: 'core', run: () => this.supervisor.stop() },
      { name: 'broker', run: async () => {
        try { await this.broker?.close(); } finally { this.broker = null; }
      } },
      { name: 'tray', run: () => {
        try { this.tray?.destroy(); } finally { this.tray = null; }
      } },
      { name: 'complete', run: () => { this.stopped = true; this.log('quit'); } },
    ], (step, error) => this.log(`quit ${step}: ${error.message}`));
    return this.shutdown.run();
  }

  /** What the development smoke may reach (smoke.ts). */
  smokeHost() {
    return {
      layout: this.layout,
      log: this.log,
      supervisor: this.supervisor,
      unlockState: () => this.fileKeyUnlock.state(),
      cancelUnlock: () => this.fileKeyUnlock.close(),
      window: () => this.window,
      tray: () => this.tray,
      notifications: () => this.notifications,
      openRoute: (route: string) => this.openRoute(route),
      deepLink: (link: string) => this.deepLink(link),
      readyDelivered: () => this.readyDeliveredFor,
      relaying: () => this.relayEvents,
      config: () => this.config,
      consents: () => [...this.consents],
      console: () => [...this.smokeConsole],
    };
  }
}

export type SmokeHost = ReturnType<Desktop['smokeHost']>;

/** A minimal menu: no reload, no developer tools in a release; Edit for the clipboard shortcuts. */
function appMenu(dev: boolean): Menu {
  const template: Electron.MenuItemConstructorOptions[] = [
    ...(process.platform === 'darwin' ? [{ label: 'GateRook', submenu: [
      { role: 'about' as const, label: 'About GateRook' }, { type: 'separator' as const },
      { role: 'hide' as const, label: 'Hide GateRook' }, { role: 'hideOthers' as const },
      { role: 'unhide' as const }, { type: 'separator' as const }, { role: 'quit' as const, label: 'Quit GateRook' }] }] : []),
    { label: 'Edit', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' },
      { role: 'paste' }, { role: 'selectAll' }] },
    { label: 'Window', submenu: [{ role: 'minimize' }, { role: 'close' }] },
    ...(dev ? [{ label: 'Develop', submenu: [{ role: 'reload' as const }, { role: 'toggleDevTools' as const }] }] : []),
  ];
  return Menu.buildFromTemplate(template);
}
