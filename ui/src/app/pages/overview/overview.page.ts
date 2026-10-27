import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ApprovalItem } from '../../core/api/approvals.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { CoreStore } from '../../core/stores/core.store';
import { duration } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwToggle } from '../../kit/disclosure';
import { GwApprovalRequestCompact } from '../../kit/elements/approval-request/approval-request-compact';
import { GwGettingStarted } from '../../kit/elements/getting-started/getting-started';
import { GwSetupMap } from '../../kit/elements/setup-map/setup-map';
import { clientName } from '../../kit/elements/shared/client-mark';
import { decisionLook, healthLook } from '../../kit/elements/shared/dictionaries';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { OverviewData } from './overview.data';
import {
  RetestResult,
  clientLabel,
  decidedCalls,
  healthAlert,
  healthCalm,
  healthDescription,
  healthGroups,
  healthText,
  retestToast,
  setupStatus,
  statTiles,
} from './overview-view';

/** The first render waits this long before it shows placeholders (kit: a skeleton only past ~300 ms). */
export const SKELETON_DELAY_MS = 300;

/**
 * Overview (design/pages/overview.html; ARCHITECTURE.md §8.4 "overview"): the home screen answers three questions in
 * this order —
 * 1. Is setup finished? The getting-started checklist (Dismiss with Undo, no confirmation) and the setup map, every
 *    link of the chain with its state, in the order it is set up.
 * 2. How are things going? Four `gw-stat` tiles for the last 24 hours with their changes and, where the core has an
 *    hourly series, a sparkline.
 * 3. What needs me right now? Waiting for you (compact requests, soonest auto-deny first, a countdown each; a
 *    decision needs the exact action, so Review opens Approvals), Connection health (problems first, the error where
 *    it happens with Test again, the rest folded) and Recent activity (the last decided calls, each opening its event).
 *
 * The page's one primary is "Review approvals", only while requests wait. Data is the core's (OverviewData) and
 * follows its events; the page is a loading skeleton, an error with Try again, or the data, with empty states inside.
 */
@Component({
  selector: 'gw-overview-page',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwToggle, GwCodeBlock, GwGettingStarted, GwSetupMap, GwApprovalRequestCompact],
  providers: [OverviewData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './overview.page.html',
})
export class OverviewPage {
  protected readonly data = inject(OverviewData);
  protected readonly time = inject(TimeService);
  private readonly connections = inject(ConnectionsApi);
  private readonly core = inject(CoreStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  private readonly setupTitle = viewChild<ElementRef<HTMLElement>>('setupTitle');
  private readonly healthTitle = viewChild<ElementRef<HTMLElement>>('healthTitle');

  /** Two columns only when each gets 480 px (12 × control-lg), as the kit's "What needs you" grid. */
  protected readonly twoColumnMin = 'calc(var(--gw-size-control-lg) * 12)';

  protected readonly decisionLook = decisionLook;
  protected readonly healthLook = healthLook;
  protected readonly healthText = healthText;
  protected readonly clientLabel = clientLabel;
  protected readonly duration = duration;

  /** Placeholders only when the first answer is slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.overview() && !this.data.error());

  protected readonly overview = this.data.overview;
  protected readonly pending = computed(() => this.overview()?.stats.pending ?? 0);
  protected readonly checklist = computed(() => this.overview()?.gettingStarted ?? null);

  /** "Setup complete": the clients and the endpoint the checklist says were set up. */
  protected readonly completeClients = computed(() => {
    const ids = this.checklist()?.steps.find((s) => s.id === 'client')?.detail['clients'];
    return Array.isArray(ids) ? ids.filter((c): c is string => typeof c === 'string').map(clientName) : [];
  });
  protected readonly firstEndpoint = computed(() => {
    const name = this.checklist()?.steps.find((s) => s.id === 'endpoint')?.detail['name'];
    return typeof name === 'string' ? name : null;
  });

  protected readonly setupNodes = computed(() => (this.overview()?.setupMap.nodes ?? []).map((n) => ({ ...n, status: setupStatus(n) })));

  protected readonly tiles = computed(() => {
    const o = this.overview();
    if (!o) return [];
    return statTiles({ stats: o.stats, histogram: this.data.histogram(), approvals: this.data.approvalsDay(), now: this.time.minute() });
  });

  protected readonly waiting = computed(() => this.overview()?.waiting ?? []);
  protected readonly waitingLabel = computed(() => {
    const shown = this.waiting().length;
    const all = Math.max(shown, this.pending());
    const n = (k: number) => `${k} ${k === 1 ? 'request' : 'requests'}`;
    return shown < all ? `Waiting for approval, ${shown} of ${n(all)}` : `Waiting for approval, ${n(shown)}`;
  });

  protected readonly health = computed(() => this.overview()?.health ?? []);
  protected readonly groups = computed(() => healthGroups(this.health()));
  protected readonly healthDescription = computed(() => {
    // "last checked 2 min ago" moves on with the clock.
    this.time.minute();
    return healthDescription(this.health(), (at) => this.time.relative(at), this.overview()?.healthIntervalSeconds ?? null);
  });
  protected readonly alert = computed(() => healthAlert(this.health(), this.waiting()));
  protected readonly healthCalm = computed(() => healthCalm(this.health()));
  protected readonly healthOpen = signal(false);
  /** The connections Test again is testing now; null while idle. */
  protected readonly testing = signal<readonly string[] | null>(null);
  protected readonly testingText = computed(() => {
    const names = this.testing();
    if (!names) return '';
    return names.length === 1
      ? `Testing ${names[0]} with its stored credentials.`
      : `Testing ${names.length} connections with their stored credentials.`;
  });

  /** The last decided calls: Activity's answer, else the overview's own list without the pending calls. */
  protected readonly recent = computed(() => this.data.recent() ?? decidedCalls(this.overview()?.recent ?? []));
  protected readonly recentDescription = computed(() => {
    const n = this.recent().length;
    return n
      ? `The last ${n === 1 ? 'call' : `${n} calls`} with a decision. Open one to see its arguments, policy, timing and result.`
      : 'Calls with a decision show up here. Open one to see its arguments, policy, timing and result.';
  });

  constructor() {
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
   * Dismiss checklist (kit: undoable, so no confirmation): the card goes, focus moves to the next section, and a
   * toast offers Undo, which brings the card back and puts focus on its Dismiss button again.
   */
  protected dismissChecklist(): void {
    const done = this.data.setChecklistDismissed(true);
    this.focusAfterRender(() => this.setupTitle()?.nativeElement);
    const toast = this.toasts.show({
      title: 'Checklist dismissed',
      body: 'Your setup map still shows what is left.',
      actions: [{ label: 'Undo', onClick: () => this.restoreChecklist() }],
    });
    done.catch((e: unknown) => {
      this.toasts.dismiss(toast.id);
      this.toasts.show({ tone: 'danger', title: "The checklist wasn't dismissed", body: this.errorText(e) });
    });
  }

  private restoreChecklist(): void {
    const done = this.data.setChecklistDismissed(false);
    this.focusAfterRender(() => this.host.querySelector<HTMLElement>('#getting-started .gw-card__actions button'));
    done.catch((e: unknown) => {
      this.toasts.show({ tone: 'danger', title: "The checklist didn't come back", body: this.errorText(e) });
    });
  }

  /**
   * Test again (kit: the in-place loading state — aria-busy, aria-disabled, a spinner, the label kept, the status line
   * saying what runs — then the result as a toast; the alert stays until the connection answers). Each unreachable
   * connection is tested with its stored credentials; the core records the health and publishes it.
   */
  protected async testAgain(names: readonly string[]): Promise<void> {
    if (this.testing() || !names.length) return;
    this.testing.set(names);
    this.announcer.announce(names.length === 1 ? `Testing ${names[0]}` : `Testing ${names.length} connections`);
    const results = await Promise.all(names.map((name) => this.retest(name)));
    await this.data.refresh();
    this.testing.set(null);
    const t = retestToast(results);
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
    // When every connection answered the alert and its button are gone: keep the keyboard in the section.
    this.focusAfterRender(() => this.healthTitle()?.nativeElement, true);
  }

  private async retest(name: string): Promise<RetestResult> {
    try {
      const o = await this.connections.test(name);
      return { name, ok: o.ok, latencyMs: o.ok ? o.latencyMs : null, message: o.error?.message ?? o.needsConfirmation?.text ?? null };
    } catch (e) {
      return { name, ok: false, latencyMs: null, message: isApiError(e) ? e.detail : null };
    }
  }

  /** A request ran out of time here: the core denies it and says so; the lists follow. */
  protected onExpired(): void {
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
