// The tray (ARCHITECTURE.md §7.1 "tray (Open · Approvals (3) · Tunnel: off · Quit; macOS setTitle)"): the app keeps
// running there when its window is closed — clients keep working, notifications keep coming. The badge counts the
// requests waiting.
import { app, Menu, nativeImage, Tray, type BrowserWindow, type NativeImage } from 'electron';
import { markPixels, toBgra } from './icons.ts';

export interface TrayState {
  readonly pending: number;
  /** `off`, `starting`, `on`, `error`, or null before the core answered. */
  readonly tunnel: string | null;
  /** What the core is doing, when it isn't simply running. */
  readonly core: string | null;
}

export interface TrayOptions {
  readonly open: () => void;
  readonly openApprovals: () => void;
  readonly quit: () => void;
}

/** The mark as a native image with 1× and 2× representations; the macOS menu bar gets the template version. */
export function markImage(size: number, variant: 'brand' | 'mono'): NativeImage {
  const image = nativeImage.createEmpty();
  for (const scaleFactor of [1, 2]) {
    const px = size * scaleFactor;
    image.addRepresentation({ scaleFactor, width: px, height: px, buffer: toBgra(markPixels(px, variant)) });
  }
  if (variant === 'mono') image.setTemplateImage(true);
  return image;
}

export class AppTray {
  private readonly tray: Tray;
  private readonly o: TrayOptions;
  private state: TrayState = { pending: 0, tunnel: null, core: null };

  constructor(o: TrayOptions) {
    this.o = o;
    const mac = process.platform === 'darwin';
    this.tray = new Tray(markImage(process.platform === 'linux' ? 22 : 16, mac ? 'mono' : 'brand'));
    this.tray.setToolTip('MCP Gateway');
    if (!mac) this.tray.on('click', () => o.open());
    this.render();
  }

  update(patch: Partial<TrayState>): void {
    this.state = { ...this.state, ...patch };
    this.render();
  }

  get current(): TrayState {
    return this.state;
  }

  /** The menu as it reads now (the smoke checks the labels). */
  labels(): string[] {
    return this.template().map((i) => i.label ?? '-');
  }

  destroy(): void {
    this.tray.destroy();
  }

  private template(): Electron.MenuItemConstructorOptions[] {
    const s = this.state;
    return [
      { label: 'Open MCP Gateway', click: () => this.o.open() },
      { label: s.pending > 0 ? `Approvals (${s.pending})` : 'Approvals', click: () => this.o.openApprovals() },
      { label: `Tunnel: ${s.tunnel ?? 'off'}`, enabled: false },
      ...(s.core ? [{ label: `Core: ${s.core}`, enabled: false }] : []),
      { type: 'separator' },
      { label: 'Quit MCP Gateway', click: () => this.o.quit() },
    ];
  }

  private render(): void {
    this.tray.setContextMenu(Menu.buildFromTemplate(this.template()));
    if (process.platform === 'darwin') this.tray.setTitle(this.state.pending > 0 ? String(this.state.pending) : '');
    this.tray.setToolTip(this.state.pending > 0 ? `MCP Gateway · ${this.state.pending} waiting` : 'MCP Gateway');
  }
}

/** The pending count on the dock / launcher (macOS, Linux Unity) or the taskbar overlay (Windows). */
export function setBadge(win: BrowserWindow | null, pending: number): void {
  if (process.platform === 'win32') {
    if (!win || win.isDestroyed()) return;
    win.setOverlayIcon(pending > 0 ? markImage(16, 'brand') : null, pending > 0 ? `${pending} approval requests waiting` : '');
    return;
  }
  app.setBadgeCount(Math.max(0, pending));
}
