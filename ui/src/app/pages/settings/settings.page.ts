import { ChangeDetectionStrategy, Component, DOCUMENT, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { CoreStore } from '../../core/stores/core.store';
import { GwCodeBlock } from '../../code/code-block';
import { GwToggle } from '../../kit/disclosure';
import { GwSettingsLayout, SETTINGS_GROUPS } from '../../kit/elements/settings-layout/settings-layout';
import { GwIcon } from '../../kit/icons';
import { jumpTo } from '../secrets/jump';
import { GwAppearanceSettings } from './appearance.section';
import { GwApprovalRulesSettings } from './approval-rules.section';
import { GwAuditSettings } from './audit.section';
import { GwAuthorizationSettings } from './authorization.section';
import { GwBackupSettings } from './backup.section';
import { GwGeneralSettings } from './general.section';
import { GwLocalApiSettings } from './local-api.section';
import { GwNotificationsSettings } from './notifications.section';
import { SettingsData } from './settings.data';
import { GwTunnelSettings } from './tunnel.section';
import { GwUsersSettings } from './users.section';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

/** The sections of the settings nav (`/settings/<id>` → `#settings-<id>`). */
export const SECTION_IDS: ReadonlySet<string> = new Set(SETTINGS_GROUPS.flatMap((g) => g.items.map((i) => i.id)));

/** Anchors inside a section a link may name: `/settings/tunnel#settings-tunnel-callers`, `#settings-signed-in`. */
const FRAGMENT = /^(settings|st)-[a-z0-9-]+$/;

/**
 * Settings (design/pages/settings.html; ARCHITECTURE.md §8.4 "settings"): one page, ten sections in the kit's
 * settings layout — a nav that marks the section on screen, each section with its own URL (`/settings/<section>`).
 * A topic that applies at once is a plain section confirmed by a toast; a topic with values to save is a form with
 * its own sticky Save bar (the kit's save model).
 *
 * `SettingsData` loads every part on its own; the page waits only for the sections (`GET /api/settings`), shows
 * placeholders after 300 ms, says what failed with Try again and the details when the sections don't load, and that
 * the page may be out of date when a reload fails with data on screen. Parts whose step isn't in this build
 * (notifications K2, tunnel and OAuth K3, audit export K5) say so in their sections.
 *
 * A link to `/settings/<section>` (and an anchor inside it, `#settings-tunnel-callers`) scrolls there once the
 * sections are in, and on every later navigation; an unknown section goes to General. The nav follows the scroll
 * (scroll spy); the URL follows the nav, not the scroll (the kit's hash changes on a click, not while scrolling).
 */
@Component({
  selector: 'gw-settings-page',
  imports: [
    GwIcon,
    GwToggle,
    GwCodeBlock,
    GwSettingsLayout,
    GwGeneralSettings,
    GwAppearanceSettings,
    GwAuthorizationSettings,
    GwUsersSettings,
    GwNotificationsSettings,
    GwApprovalRulesSettings,
    GwTunnelSettings,
    GwLocalApiSettings,
    GwAuditSettings,
    GwBackupSettings,
  ],
  providers: [SettingsData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './settings.page.html',
})
export class SettingsPage {
  protected readonly data = inject(SettingsData);
  private readonly core = inject(CoreStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly doc = inject(DOCUMENT);

  /**
   * Route parameter `section`, read from the route: input binding would hand over the route data's `section` (the
   * titlebar's "Settings"), which has the same name and wins over the parameter.
   */
  private readonly params = toSignal(this.route.paramMap, { requireSync: true });
  protected readonly section = computed(() => this.params().get('section'));
  private readonly fragment = toSignal(this.route.fragment, { initialValue: null });

  /** The section the nav marks: the route's at first, then the one on screen. */
  protected readonly current = signal<string | null>('general');
  /** Placeholders only when the sections are slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.loaded());
  /** The sections didn't load at all: the page has nothing to show. */
  protected readonly failed = computed(() => (this.data.settings().value ? null : this.data.settings().error));

  constructor() {
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });

    // The route names the section: mark it, and go there once the sections are on screen.
    effect(() => {
      const s = this.section();
      const f = this.fragment();
      const ready = !this.loading() && !this.failed();
      untracked(() => {
        if (s && !SECTION_IDS.has(s)) {
          void this.router.navigate(['/settings', 'general'], { replaceUrl: true });
          return;
        }
        if (s) this.current.set(s);
        if (!ready || !s) return;
        const target = f && FRAGMENT.test(f) ? f : `settings-${s}`;
        // General is the top of the page: a plain /settings shows the page header, as the kit does.
        if (target === 'settings-general') return;
        // After this change detection has put the sections on screen (a timer, not afterNextRender: when nothing
        // else changes no render follows, and a late one would jump the page away from where the person scrolled).
        setTimeout(() => this.go(target, `settings-${s}`));
      });
    });

    // The core came back (it was away, or another instance answers): everything may be stale.
    let wasRunning = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const running = this.core.running();
      const changes = this.core.instanceChanges();
      untracked(() => {
        if (running && (!wasRunning || changes !== instance)) void this.data.refresh();
        wasRunning = running;
        instance = changes;
      });
    });
  }

  /**
   * Brings the target into view. From the settings nav the focus stays on the item (the kit's in-page anchors do the
   * same); from anywhere else (a link on another page, "Change in Tunnel › Callers") the keyboard goes there too.
   */
  private go(target: string, fallback: string): void {
    const active = this.doc.activeElement;
    const fromNav = !!active?.closest('.gw-settings-layout__nav, .gw-settings-layout__jump');
    if (fromNav) {
      (this.doc.getElementById(target) ?? this.doc.getElementById(fallback))?.scrollIntoView?.({ block: 'start' });
      return;
    }
    if (!jumpTo(this.doc, target)) jumpTo(this.doc, fallback);
  }

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : 'The core didn’t answer. Try again.';
  }

  /** The technical side of a failed load, for the code block under Details. */
  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/settings → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }
}
