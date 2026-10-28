import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRouteSnapshot, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { GwRouteData } from './app.routes';
import { isApiError } from './core/api/api-error';
import { DesktopBridge } from './core/desktop/desktop-bridge';
import { EventStream } from './core/events/event-stream';
import { ApprovalsStore } from './core/stores/approvals.store';
import { ConsentStore } from './core/stores/consent.store';
import { CoreStore } from './core/stores/core.store';
import { SettingsStore } from './core/stores/settings.store';
import { ActivationGuard } from './kit/activation-guard';
import { GwOAuthConsent } from './kit/elements/client-card/oauth-consent';
import { GwCommandPalette } from './kit/elements/command-palette/command-palette';
import { GwIcon } from './kit/icons';
import { GwMenu, GwMenuTrigger } from './kit/menu';
import { GwSidebarToggle } from './kit/sidebar';
import { GwFragmentTabs } from './kit/tabs';
import { GwThemeControl } from './kit/theme';
import { GwToastRegion } from './kit/toast-region';
import { ToastService } from './kit/toast.service';
import { CoreStartingPage } from './pages/core-starting/core-starting.page';
import { deepLinkUrl, uiOpenUrl } from './shell/deep-links';
import { DesktopNoticeToasts } from './shell/desktop-notice-toasts';
import { NavigationHistory } from './shell/navigation-history';
import { GwTitleStrategy } from './shell/title.strategy';

/** One link of the sidebar (BLUEPRINT §2, pages/_shell.html): label, route and section icon. */
interface NavLink {
  readonly label: string;
  readonly route: string;
  readonly icon: string;
}

interface NavGroup {
  readonly id: string | null;
  readonly label: string | null;
  readonly end?: boolean;
  readonly links: readonly NavLink[];
}

/** The app's information architecture, exactly as the kit's `<nav data-gw-nav>`. */
const NAV: readonly NavGroup[] = [
  {
    id: null,
    label: null,
    links: [
      { label: 'Overview', route: '/overview', icon: 'layout-dashboard' },
      { label: 'Approvals', route: '/approvals', icon: 'user-check' },
    ],
  },
  {
    id: 'gw-nav-resources',
    label: 'Resources',
    links: [
      { label: 'Connections', route: '/connections', icon: 'database' },
      { label: 'Connectors', route: '/connectors', icon: 'puzzle' },
      { label: 'Secrets', route: '/secrets', icon: 'key-round' },
    ],
  },
  {
    id: 'gw-nav-access',
    label: 'Access',
    links: [
      { label: 'Endpoints', route: '/endpoints', icon: 'waypoints' },
      { label: 'AI apps', route: '/clients', icon: 'app-window' },
      { label: 'Policies', route: '/policies', icon: 'shield' },
      { label: 'Playground', route: '/playground', icon: 'flask-conical' },
    ],
  },
  {
    id: 'gw-nav-observe',
    label: 'Observe',
    links: [
      { label: 'Activity', route: '/activity', icon: 'activity' },
      { label: 'Insights', route: '/insights', icon: 'chart-column' },
    ],
  },
  { id: null, label: null, end: true, links: [{ label: 'Settings', route: '/settings', icon: 'settings' }] },
];

/** Icons of the health warnings the frame shows as app-wide banners (`/api/health` `warnings[]`). */
const WARNING_ICONS: Record<string, string> = {
  'new-key-created': 'key-round',
  integrity: 'shield-alert',
  'audit-catching-up': 'hourglass',
  backup: 'database',
};

/**
 * The window (pages/_shell.html, copied 1:1): skip link, titlebar (Back, section name, Search ⌘K, the approvals bell,
 * Appearance), the sidebar with the live approvals counter and core status, and main with the app-wide
 * banners, the page and the command palette. While the core isn't running the status screen is the whole window
 * (core-starting); Welcome uses the no-sidebar variant; the dev gallery has no frame at all.
 *
 * The app also routes what comes from outside, strictly by allowlist (`shell/deep-links.ts`): `gaterook://` deep
 * links from Electron (Review in an OS notification, Copy link in Activity) and the core's `ui.open` ("Open in app"
 * in Slack or Telegram) only navigate, and re-arm the decision buttons' 700 ms activation delay. `#fragment`s select
 * tabs on every navigation, the same-route ones included (GwFragmentTabs, kit.js `hashchange`).
 *
 * Two things wait for a person on any page, so the window holds them, not a page: a local app sign-in (the
 * OAuth consent dialog, ARCHITECTURE.md §7.3 — the oldest waiting request, Deny focused, "Allow access" after 700 ms
 * and never on Enter) and, while the window is in front, the toast of a desktop notification (§7.4, kit
 * approval-notification "In-app toast"). Until the first navigation is decided (Welcome or a page) no frame is drawn:
 * a sidebar there would lead past Welcome on a first run.
 */
@Component({
  selector: 'gw-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    GwIcon,
    GwMenu,
    GwMenuTrigger,
    GwThemeControl,
    GwSidebarToggle,
    GwCommandPalette,
    GwToastRegion,
    GwOAuthConsent,
    CoreStartingPage,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  hostDirectives: [GwFragmentTabs],
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  private readonly doc = inject(DOCUMENT);
  private readonly events = inject(EventStream);
  private readonly settings = inject(SettingsStore);
  private readonly activation = inject(ActivationGuard);
  private readonly consents = inject(ConsentStore);
  private readonly noticeToasts = inject(DesktopNoticeToasts);
  private readonly toasts = inject(ToastService);
  protected readonly core = inject(CoreStore);
  protected readonly approvals = inject(ApprovalsStore);
  protected readonly desktop = inject(DesktopBridge);
  protected readonly history = inject(NavigationHistory);

  protected readonly nav = NAV;
  protected readonly mac = this.desktop.platform === 'darwin';

  /** The deepest route's data after each navigation: the titlebar title and the frame (null before the first). */
  private readonly routeData = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => deepest(this.router.routerState.snapshot.root).data as Partial<GwRouteData>),
    ),
    { initialValue: null },
  );
  protected readonly section = computed(() => this.routeData()?.section ?? '');
  /**
   * `app`, `welcome` or `none`; null until the first navigation ends — on a first run it may still turn out to be
   * Welcome, and a sidebar drawn meanwhile would lead past it.
   */
  protected readonly frame = computed(() => {
    const data = this.routeData();
    return data ? (data.shell ?? 'app') : null;
  });

  /** The sign-in waiting longest, while the core can take an answer (a restart denies every one anyway). */
  protected readonly consent = computed(() => (this.core.running() ? (this.consents.pending().find((c) => c.ingress === 'LOCAL') ?? null) : null));

  /** The frame waits for the core; the frameless gallery never does (it renders demo data only). */
  protected readonly showStatus = computed(() => this.frame() !== 'none' && !this.core.running());

  protected readonly pending = this.approvals.pendingCount;
  protected readonly approvalsLabel = computed(() => {
    const n = this.pending();
    return n > 0 ? `Approvals, ${n} waiting` : 'Approvals';
  });
  protected readonly warnings = computed(() =>
    this.core.warnings().map((w) => ({ ...w, icon: WARNING_ICONS[w.kind] ?? 'triangle-alert' })),
  );
  private readonly palette = viewChild(GwCommandPalette);
  /** Whether the core has run once in this window: from then on the frame stays under the status screen. */
  protected readonly mounted = signal(false);

  constructor() {
    this.core.start();
    // Live data starts once the core answers. start() only subscribes once; every later return to running (the core
    // was away or restarted) reloads what the frame keeps, so the counter and the banner aren't stale until the
    // next event.
    effect(() => {
      if (!this.core.running()) return;
      untracked(() => {
        const again = this.mounted();
        this.mounted.set(true);
        this.events.start();
        this.approvals.start();
        this.consents.start();
        this.noticeToasts.start();
        if (again) this.reload();
        else void this.settings.load();
      });
    });
    // Another core instance answers: its event ids start over, so the stream starts fresh (no old Last-Event-ID).
    effect(() => {
      if (this.core.instanceChanges() === 0) return;
      untracked(() => {
        this.events.restart();
        this.reload();
      });
    });
    // The stream dropped and came back: maybe the core restarted without the window noticing; ask /api/health.
    effect(() => {
      if (this.events.reopened() === 0) return;
      untracked(() => void this.core.refresh());
    });
    // The status screen is the window: its title, as the kit's core-starting page; the page's comes back after.
    const titles = inject(GwTitleStrategy);
    effect(() => titles.cover(this.showStatus() ? 'Core starting' : null));
    // The stream can't reconnect: maybe the core is gone (a browser has no Electron main to say so).
    effect(() => {
      if (this.events.failing() === 0) return;
      untracked(() => this.core.noteStreamFailure());
    });

    const destroy = inject(DestroyRef);
    destroy.onDestroy(this.desktop.onDeepLink((link) => this.openFromOutside(deepLinkUrl(link))));
    // "Open in app" from a chat: in the desktop app main brings the window forward on the path and hands it over as a
    // deep link (the queue as its first waiting request); a browser has no main, so the window opens it itself.
    destroy.onDestroy(
      this.events.on('ui.open', (data) => {
        if (!this.desktop.available) this.openFromOutside(uiOpenUrl(data));
      }),
    );
  }

  private reload(): void {
    void this.approvals.refresh();
    void this.consents.refresh();
    void this.settings.load();
  }

  /**
   * The consent dialog's answer (only "Allow access" allows; Esc, close and Deny deny). The dialog has moved on to the
   * next request by now; the toast says when the answer didn't count.
   */
  protected async answerConsent(e: { readonly id: string; readonly decision: 'allow' | 'deny' }): Promise<void> {
    try {
      const outcome = await this.consents.decide(e.id, e.decision);
      if (outcome === 'gone') {
        this.toasts.show({
          tone: 'info',
          title: 'The sign-in request was closed',
          body: 'It expired or was answered elsewhere before your answer arrived.',
        });
      }
    } catch (err) {
      this.toasts.show({
        tone: 'danger',
        title: 'Your answer didn’t reach GateRook',
        body: `${isApiError(err) ? err.detail : 'The core didn’t answer.'} A request still waiting shows again.`,
      });
    }
  }

  /** Navigation only, and only to an allowlisted screen; the decision buttons there wait 700 ms again. */
  private openFromOutside(url: string | null): void {
    if (!url) return;
    this.activation.rearm();
    void this.router.navigateByUrl(url);
  }

  /**
   * The skip link's `href="#main"` would resolve against `<base href="/">` and leave the page; move focus instead
   * (main has `tabindex="-1"`).
   */
  protected skipToMain(e: Event): void {
    e.preventDefault();
    this.doc.getElementById('main')?.focus();
  }

  /** The titlebar's Search button (kit: `data-gw-dialog-open="cmdk"`): focus comes back to it on close. */
  protected openPalette(e: Event): void {
    this.palette()?.open(e.currentTarget instanceof HTMLElement ? e.currentTarget : null);
  }

}

function deepest(route: ActivatedRouteSnapshot): ActivatedRouteSnapshot {
  let r = route;
  while (r.firstChild) r = r.firstChild;
  return r;
}
