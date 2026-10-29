// `window.gwDesktop` (ARCHITECTURE.md §7.1 "Preload"; the UI's contract: ui/src/app/core/desktop/desktop-bridge.ts).
// A sandboxed preload: only `electron` can be required, and only `contextBridge` and `ipcRenderer` are used. The page
// gets plain functions and structured-cloned data — never `ipcRenderer`, an IPC event (its `sender` would be
// `ipcRenderer` itself) or a secret. The private-key picker returns only the explicitly chosen path. Arguments are
// coerced to the types main expects; main checks them again,
// and checks that the call comes from this window's top frame on app://gw/.
import { contextBridge, ipcRenderer } from 'electron';
import { IPC, type DesktopCoreState, type GeneralSettings, type RelayedEvent, type UpdateState } from '../shared/contract.ts';

type Off = () => void;

/** A main → renderer channel as a callback; the IPC event never reaches the page. */
function subscribe<T>(channel: string, cb: (payload: T) => void): Off {
  const listener = (_event: unknown, payload: T): void => {
    try {
      cb(payload);
    } catch (e) {
      console.error(`gwDesktop: a ${channel} callback failed`, e);
    }
  };
  ipcRenderer.on(channel, listener);
  return () => {
    ipcRenderer.removeListener(channel, listener);
  };
}

const platform = process.platform === 'darwin' || process.platform === 'win32' ? process.platform : 'linux';

const gwDesktop = {
  platform,
  setNativeTheme(theme: 'system' | 'light' | 'dark'): void {
    ipcRenderer.send(IPC.setNativeTheme, String(theme));
  },
  openExternal(url: string): Promise<void> {
    return ipcRenderer.invoke(IPC.openExternal, String(url));
  },
  saveFile(purpose: string): Promise<string | null> {
    return ipcRenderer.invoke(IPC.saveFile, String(purpose));
  },
  openFile(purpose: string): Promise<string | null> {
    return ipcRenderer.invoke(IPC.openFile, String(purpose));
  },
  choosePrivateKeyFile(): Promise<string | null> {
    return ipcRenderer.invoke(IPC.choosePrivateKeyFile);
  },
  signInToGoogle(): Promise<void> {
    return ipcRenderer.invoke(IPC.signInToGoogle);
  },
  openMcpb(linkId: string): Promise<void> {
    return ipcRenderer.invoke(IPC.openMcpb, String(linkId));
  },
  openClientConfig(client: string): Promise<void> {
    return ipcRenderer.invoke(IPC.openClientConfig, String(client));
  },
  revealInFolder(kind: 'backups' | 'logs'): Promise<void> {
    return ipcRenderer.invoke(IPC.reveal, String(kind));
  },
  onDeepLink(cb: (route: string) => void): Off {
    const off = subscribe<string>(IPC.deepLinkPush, (route) => cb(String(route)));
    // A link that opened the app arrived before the page could listen: main kept it.
    void ipcRenderer.invoke(IPC.pendingDeepLink).then((route: unknown) => {
      if (typeof route === 'string') cb(route);
    });
    return off;
  },
  onCoreState(cb: (state: DesktopCoreState) => void): Off {
    const off = subscribe<DesktopCoreState>(IPC.coreStatePush, cb);
    // What happened before the page subscribed: the steps of the start, then the state now.
    void ipcRenderer.invoke(IPC.coreState).then((states: unknown) => {
      if (Array.isArray(states)) for (const s of states) cb(s as DesktopCoreState);
    });
    return off;
  },
  onEvent(cb: (type: string, data: unknown, id: string | null) => void): Off {
    const off = subscribe<RelayedEvent>(IPC.eventPush, (e) => cb(String(e.type), e.data, e.id ?? null));
    // Main relays the core's events only while someone listens (the UI's fallback when streaming through app://
    // doesn't work).
    ipcRenderer.send(IPC.relayEvents, true);
    return () => {
      off();
      ipcRenderer.send(IPC.relayEvents, false);
    };
  },
  applyGeneral(settings: GeneralSettings): Promise<void> {
    const s = settings ?? {};
    return ipcRenderer.invoke(IPC.applyGeneral, {
      ...(typeof s.openAtLogin === 'boolean' ? { openAtLogin: s.openAtLogin } : {}),
      ...(typeof s.startHidden === 'boolean' ? { startHidden: s.startHidden } : {}),
    });
  },
  restartCore(): Promise<void> {
    return ipcRenderer.invoke(IPC.restartCore);
  },
  resetApplication(confirmation: string): Promise<void> {
    return ipcRenderer.invoke(IPC.resetApplication, String(confirmation));
  },
  createFullBackup(passphrase: string): Promise<{ cancelled: boolean; fileName?: string }> {
    return ipcRenderer.invoke(IPC.createFullBackup, passphrase);
  },
  restoreFullBackup(passphrase: string, confirmation: string): Promise<{ cancelled: boolean }> {
    return ipcRenderer.invoke(IPC.restoreFullBackup, passphrase, confirmation);
  },
  unlock(): Promise<void> {
    return ipcRenderer.invoke(IPC.unlock);
  },
  usePort(target: number | { readonly port?: number; readonly publicPort?: number }): Promise<void> {
    // A number moves the local API; {port?, publicPort?} may move the tunnel's public listener too.
    const t = typeof target === 'object' && target !== null
      ? { ...(target.port !== undefined ? { port: Number(target.port) } : {}),
        ...(target.publicPort !== undefined ? { publicPort: Number(target.publicPort) } : {}) }
      : Number(target);
    return ipcRenderer.invoke(IPC.usePort, t);
  },
  findFreePort(from: number): Promise<number | null> {
    return ipcRenderer.invoke(IPC.findFreePort, Number(from));
  },
  quit(): void {
    ipcRenderer.send(IPC.quit);
  },
  checkForUpdates(): Promise<void> {
    return ipcRenderer.invoke(IPC.checkForUpdates);
  },
  installUpdate(): Promise<void> {
    return ipcRenderer.invoke(IPC.installUpdate);
  },
  onUpdateState(cb: (state: UpdateState) => void): Off {
    let active = true;
    let pushed = false;
    const off = subscribe<UpdateState>(IPC.updateStatePush, (state) => {
      pushed = true;
      if (active) cb(state);
    });
    void ipcRenderer.invoke(IPC.updateState).then((s: unknown) => {
      // A push may overtake this initial snapshot, or the subscriber may already have gone away.
      if (active && !pushed && s && typeof s === 'object') cb(s as UpdateState);
    }).catch((e: unknown) => console.error('gwDesktop: could not read update state', e));
    return () => { active = false; off(); };
  },
};

contextBridge.exposeInMainWorld('gwDesktop', gwDesktop);
