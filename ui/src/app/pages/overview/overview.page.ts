import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../code/code-block';
import { isApiError } from '../../core/api/api-error';
import { ApprovalItem } from '../../core/api/approvals.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { CoreStore } from '../../core/stores/core.store';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwToggle } from '../../kit/disclosure';
import { GwApprovalRequestCompact } from '../../kit/elements/approval-request/approval-request-compact';
import { GwStatusTile } from '../../kit/elements/status-tile/status-tile';
import { GwGuideStep } from '../../kit/guide-step';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { OverviewData } from './overview.data';
import {
  AppTile,
  RetestResult,
  activityLine,
  appAnchor,
  checkPrompt,
  claudeAiTile,
  connectionTiles,
  guideAppTile,
  homeAppTiles,
  modesWords,
  overviewState,
  retestToast,
  secretLine,
  tileHealth,
} from './overview-view';

/** The first render waits this long before it shows placeholders (kit: a skeleton only past ~300 ms). */
export const SKELETON_DELAY_MS = 300;

/**
 * Overview (design/pages/overview.html; BLUEPRINT §5 overview): the home screen, with as little on it as possible, in
 * one of three states the core's own facts decide (`overviewState`):
 *
 * - **In use** — an AI app is connected. Waiting for your approval at the top, the quick-access zone (compact
 *   requests, soonest auto-deny first, Review opens the exact action in Approvals; a quiet line when none waits);
 *   Your connections as status tiles, problems first and one row in view (Test again on an unreachable one), the
 *   rest behind "N more connections"; AI apps as tiles (an app found and not connected first, with Connect;
 *   claude.ai last); one line about Activity.
 * - **First run** — no connection yet: a three-step guide (`gw-stepper --guide`): Add your first connection (the
 *   page's one primary), Connect your AI app (the apps of this computer; Connect waits, `aria-disabled` with the
 *   reason), Check that it works.
 * - **Setup** — a connection, no AI app yet: step 1 done with that connection, step 2 current, its Connect leading to
 *   the app's review on AI apps (`/clients#cl-<app>-t`), step 3 waiting for the first request.
 *
 * Data is the core's (OverviewData) and follows its events; the page is a loading skeleton, an error with Try again,
 * or one of the states.
 */
@Component({
  selector: 'gw-overview-page',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwToggle, GwCodeBlock, GwApprovalRequestCompact, GwStatusTile, GwGuideStep],
  providers: [OverviewData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './overview.page.html',
})
export class OverviewPage {
  protected readonly data = inject(OverviewData);
  protected readonly time = inject(TimeService);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly core = inject(CoreStore);
  private readonly tunnel = inject(TunnelStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  private readonly connectionsTitle = viewChild<ElementRef<HTMLElement>>('connectionsTitle');

  protected readonly appAnchor = appAnchor;

  /** Placeholders only when the first answer is slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.overview() && !this.data.error());

  protected readonly overview = this.data.overview;
  protected readonly state = computed(() => {
    const o = this.overview();
    return o ? overviewState(o) : null;
  });

  // ── In use ──

  protected readonly pending = computed(() => this.overview()?.stats.pending ?? 0);
  protected readonly waiting = computed(() => this.overview()?.waiting ?? []);
  /** The counter next to the title: every request waiting, also those the list doesn't show. */
  protected readonly waitingCount = computed(() => Math.max(this.waiting().length, this.pending()));
  protected readonly waitingLabel = computed(() => {
    const shown = this.waiting().length;
    const all = this.waitingCount();
    const n = (k: number) => `${k} ${k === 1 ? 'request' : 'requests'}`;
    return shown < all ? `Waiting for approval, ${shown} of ${n(all)}` : `Waiting for approval, ${n(shown)}`;
  });

  protected readonly tiles = computed(() => connectionTiles(this.overview()?.health ?? []));
  protected readonly moreOpen = signal(false);
  protected readonly moreLabel = computed(() => {
    const more = this.tiles().more;
    return more.every((t) => t.state.tone === 'success') ? 'More connections, all healthy' : 'More connections';
  });
  /** The connections Test again is testing now. */
  protected readonly testing = signal<readonly string[]>([]);

  private readonly claudeAi = computed(() => {
    this.time.minute();
    return claudeAiTile(this.tunnel.available() ? this.tunnel.state() : null, (at) => this.time.clock(at));
  });
  protected readonly homeApps = computed(() => homeAppTiles(this.data.clients()?.clients ?? [], this.data.connections(), this.claudeAi()));
  protected readonly homeAppsLabel = computed(() => (this.homeApps().some((a) => a.action === 'connect') ? 'AI apps, the one to connect first' : 'AI apps'));
  protected readonly activity = computed(() => activityLine(this.overview()?.stats ?? { calls24h: 0, denied24h: 0 }));

  // ── First run and setup ──

  /** The four AI apps of this computer, as the guide shows them (the core's order: Claude Desktop, Codex, Cursor, Antigravity). */
  protected readonly guideApps = computed<AppTile[]>(() => (this.data.clients()?.clients ?? []).map(guideAppTile));
  protected readonly anyConnect = computed(() => this.guideApps().some((a) => a.action === 'connect'));

  /** Setup: the connection step 1 names (the core's first connection), with its live health. */
  protected readonly first = computed(() => {
    const o = this.overview();
    const step = o?.gettingStarted.steps.find((s) => s.id === 'connection');
    const name = typeof step?.detail['name'] === 'string' ? step.detail['name'] : null;
    if (!o || !name) return null;
    const row = o.health.find((h) => h.name === name) ?? null;
    const detail = this.data.first()?.name === name ? this.data.first() : null;
    const typeName = row?.typeLabel || detail?.typeLabel || (typeof step?.detail['typeName'] === 'string' ? step.detail['typeName'] : null);
    const count = typeof step?.detail['count'] === 'number' ? step.detail['count'] : 1;
    return {
      name,
      type: row?.type ?? detail?.type ?? null,
      typeName,
      health: row ? tileHealth(row) : null,
      modes: modesWords(detail ? detail.usedBy : null),
      secret: detail ? secretLine(detail.secrets) : null,
      others: Math.max(0, count - 1),
    };
  });
  /** "Your connection works." only when the connection answered its last check. */
  protected readonly firstWorks = computed(() => this.first()?.health?.tone === 'success');
  protected readonly keyBackedUp = computed(() => this.overview()?.gettingStarted.steps.find((s) => s.id === 'master-key')?.state === 'done');
  protected readonly prompt = computed(() => {
    const f = this.state() === 'setup' ? this.first() : null;
    return checkPrompt(f?.type ?? null, f?.name ?? null);
  });

  constructor() {
    this.tunnel.start();
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    // The core came back (it was away, or another instance answers): what the page shows may be stale.
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

  protected retry(): void {
    void this.data.refresh();
  }

  /** What a failed call says to do: the core's words when it sent them. */
  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : 'See the core log for details, then try again.';
  }

  /** The technical side of a failed load, for the code block under Details. */
  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/overview → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  /**
   * Test again on an unreachable connection (kit status-tile, busy): the button keeps its label with a spinner and
   * aria-busy, the tile's state says "Checking…", then the result comes as a toast; the tile stays until it answers.
   */
  protected async testAgain(name: string): Promise<void> {
    if (this.testing().includes(name)) return;
    this.testing.update((t) => [...t, name]);
    this.announcer.announce(`Testing ${name}`);
    const result = await this.retest(name);
    this.data.patchHealth({
      name,
      state: result.ok ? 'healthy' : 'unreachable',
      latencyMs: result.latencyMs,
      message: result.message,
      checkedAt: new Date(this.time.now()).toISOString(),
    });
    void this.data.refresh();
    this.testing.update((t) => t.filter((n) => n !== name));
    const t = retestToast(result);
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
    // A tile that answered loses its button: keep the keyboard in the section.
    this.focusAfterRender(() => this.connectionsTitle()?.nativeElement, true);
  }

  private async retest(name: string): Promise<RetestResult> {
    try {
      const o = await this.connectionsApi.test(name);
      return { name, ok: o.ok, latencyMs: o.ok ? o.latencyMs : null, message: o.error?.message ?? o.needsConfirmation?.text ?? null };
    } catch (e) {
      return { name, ok: false, latencyMs: null, message: isApiError(e) ? e.detail : null };
    }
  }

  /** A request ran out of time here: it leaves the list at once (the core denies it and says so); the numbers follow. */
  protected onExpired(w: ApprovalItem): void {
    this.data.dropWaiting(w.id);
    const n = this.waiting().length;
    this.announcer.announce(n ? `${n} ${n === 1 ? 'request' : 'requests'} waiting for your approval` : 'Nothing waits for your approval');
    this.data.schedule(1_000);
  }

  /** Read out once when a request's last minute starts (kit `data-gw-countdown-announce`). */
  protected announceOf(w: ApprovalItem): string {
    return `${w.tool} on ${w.connection ?? w.endpoint} is denied automatically in less than a minute`;
  }

  /** Moves focus once the view shows the change; `onlyIfLost` leaves it alone unless it fell back to the body. */
  private focusAfterRender(target: () => HTMLElement | null | undefined, onlyIfLost = false): void {
    afterNextRender(
      () => {
        const doc = this.host.ownerDocument;
        const active = doc.activeElement;
        if (onlyIfLost && active && active !== doc.body && doc.contains(active)) return;
        target()?.focus();
      },
      { injector: this.injector },
    );
  }
}
