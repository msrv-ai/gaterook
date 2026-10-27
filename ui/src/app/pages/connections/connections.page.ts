import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ConnectionRow } from '../../core/api/connections.api';
import { CoreStore } from '../../core/stores/core.store';
import { everyInterval } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { GwHowItWorks, GwHowItWorksStep } from '../../kit/elements/how-it-works/how-it-works';
import { connectorIcon } from '../../kit/elements/shared/connector-icons';
import { GwIcon } from '../../kit/icons';
import { GwMenu, GwMenuTrigger, MenuPick } from '../../kit/menu';
import { GwSearchClearButton } from '../../kit/search';
import { ToastService } from '../../kit/toast.service';
import { ConnectionsData, DeleteFacts } from './connections.data';
import {
  ENVS,
  EnvFilter,
  Filters,
  HealthFilter,
  NO_FILTERS,
  ToastText,
  countText,
  credentialsView,
  deleteLead,
  deletedToast,
  filtered,
  headerStatuses,
  healthCell,
  healthCounts,
  healthOptions,
  idOf,
  lastChecked,
  optionLabel,
  parseEnv,
  parseHealth,
  pendingTitle,
  plural,
  rowAlert,
  testAllToast,
  testRefusedToast,
  testToast,
  typeOptions,
  typeText,
} from './connections-view';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Connections (design/pages/connections.html; ARCHITECTURE.md §8.4 "connections"): every resource agents can reach
 * and whether it answers right now.
 *
 * - The header counts the connections by health and says when they were last checked; Add connection is the page's
 *   one primary.
 * - How connections work: folded and remembered; it opens on the first run, when there is nothing to list yet.
 * - The toolbar filters at once as the person types or picks (search by name, host or type, Type, environment,
 *   Health with live counts) and says how many rows show; Test all tests every connection in the background, and
 *   each row says "Checking" until its `connection.health` arrives.
 * - The table: name + env + host, type + badges, credentials (never a value), health + last check, the endpoints
 *   that use it, and a row menu (Test connection, Edit, View activity, Duplicate, Delete). An unreachable row has its
 *   error under it with Test again; a row changed outside MCP Gateway says so and can be kept after review.
 * - Delete asks in `gw-dialog--danger`, with what stops working, the requests the core denies and what happens to the
 *   secrets; Cancel has the focus.
 *
 * States: nothing for 300 ms, then a skeleton table; an error with Try again; first run (no connections); no rows
 * match the filters; out of date (a reload failed with rows on screen).
 */
@Component({
  selector: 'gw-connections-page',
  imports: [RouterLink, GwIcon, GwMenu, GwMenuTrigger, GwSearchClearButton, GwDialog, GwDialogClose, GwToggle, GwCodeBlock, GwHowItWorks, GwHowItWorksStep],
  providers: [ConnectionsData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './connections.page.html',
})
export class ConnectionsPage {
  protected readonly data = inject(ConnectionsData);
  protected readonly time = inject(TimeService);
  private readonly core = inject(CoreStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  /** Links pre-fill the filters (kit: `connections.html?q=billing-api`, `?type=postgres` from Connectors). */
  readonly q = input<string | undefined>();
  readonly type = input<string | undefined>();
  readonly env = input<string | undefined>();
  readonly health = input<string | undefined>();

  private readonly deleteDialog = viewChild.required<GwDialog>('deleteDialog');
  private readonly keepDialog = viewChild.required<GwDialog>('keepDialog');
  private readonly search = viewChild<ElementRef<HTMLInputElement>>('search');

  protected readonly envs = ENVS;
  protected readonly connectorIcon = connectorIcon;
  protected readonly typeText = typeText;
  protected readonly idOf = idOf;
  protected readonly deleteLead = deleteLead;
  protected readonly pendingTitle = pendingTitle;

  protected readonly filters = signal<Filters>(NO_FILTERS);

  /** Placeholders only when the first answer is slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.rows() && !this.data.error());
  protected readonly rows = computed(() => this.data.rows() ?? []);
  /** Loaded, and the gateway has no connection at all: the first-run empty state replaces toolbar and table. */
  protected readonly firstRun = computed(() => this.data.rows()?.length === 0);

  protected readonly typeOptions = computed(() => typeOptions(this.rows(), this.filters().type));
  protected readonly healthOptions = computed(() => healthOptions(this.rows(), this.filters().health));
  protected readonly typeLabel = computed(() => optionLabel(this.typeOptions(), this.filters().type));
  protected readonly healthLabel = computed(() => optionLabel(this.healthOptions(), this.filters().health));

  protected readonly statuses = computed(() => headerStatuses(healthCounts(this.rows())));
  /** "Health is checked every minute" (kit) from the core's interval; a core that doesn't say it: "Last checked 1 min ago". */
  protected readonly checkedNote = computed(() => {
    const interval = this.data.healthInterval();
    if (interval) return `Health is checked ${everyInterval(interval)}`;
    const at = lastChecked(this.rows());
    return at ? `Last checked ${this.time.relative(at)}` : null;
  });

  protected readonly shown = computed(() => filtered(this.rows(), this.filters()));
  protected readonly countLabel = computed(() => {
    const run = this.data.testingAll();
    if (run) return `Testing ${plural(run.total ?? this.data.checking().size, 'connection')}`;
    return countText(this.shown().length, this.rows().length);
  });

  /** One view model per shown row; the relative times move with the clock. */
  protected readonly view = computed(() => {
    const checking = this.data.checking();
    const shortLived = this.data.shortLived();
    this.data.secrets();
    return this.shown().map((row) => {
      const at = row.health?.checkedAt ?? null;
      return {
        row,
        health: healthCell(row),
        checking: checking.has(row.name),
        checkedAt: at,
        checkedText: at ? this.time.relative(at) : '',
        credentials: credentialsView(row, this.data.secretsOf(row.name), shortLived),
        alert: rowAlert(row, (at) => this.time.clock(at)),
        /** Why Test connection is unavailable (aria-disabled, said in the item's hint). */
        testBlocked: !row.verified ? 'Review the change first' : checking.has(row.name) ? 'Testing now' : null,
      };
    });
  });

  /** The row the delete confirmation is about, and what it found out about it (null while loading). */
  protected readonly deleting = signal<ConnectionRow | null>(null);
  protected readonly deleteFacts = signal<DeleteFacts | null>(null);
  protected readonly deleteBusy = signal(false);
  protected readonly deleteError = signal<string | null>(null);

  protected readonly keeping = signal<ConnectionRow | null>(null);
  protected readonly keepBusy = signal(false);
  protected readonly keepError = signal<string | null>(null);

  private deleted = false;

  constructor() {
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    // A link with filters (also while the page is open: the palette or another page navigates here again).
    effect(() => {
      const preset = { q: this.q(), type: this.type(), env: this.env(), health: this.health() };
      untracked(() =>
        this.filters.set({
          q: preset.q ?? '',
          type: preset.type?.trim() || 'all',
          env: parseEnv(preset.env),
          health: parseHealth(preset.health),
        }),
      );
    });
    // The core came back (it was away, or another instance answers): the list may be stale.
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

  // ── Filters ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected onSearch(value: string): void {
    this.filters.update((f) => ({ ...f, q: value }));
  }

  protected pickType(p: MenuPick): void {
    this.filters.update((f) => ({ ...f, type: p.value }));
  }

  protected pickHealth(p: MenuPick): void {
    this.filters.update((f) => ({ ...f, health: p.value as HealthFilter }));
  }

  protected setEnv(env: EnvFilter): void {
    this.filters.update((f) => ({ ...f, env }));
  }

  /** "Clear filters" (no rows match): everything back to all, the keyboard back in the search. */
  protected clearFilters(): void {
    this.filters.set(NO_FILTERS);
    this.focusAfterRender(() => this.search()?.nativeElement);
  }

  // ── Test ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Test connection (row menu, or Test again under an unreachable row): the row says "Checking" in place, the
   * result is a toast. When the row's alert went away with the problem, the keyboard stays on the row.
   */
  protected async test(row: ConnectionRow): Promise<void> {
    if (!row.verified || this.data.checking().has(row.name)) return;
    this.announcer.announce(`Testing ${row.name}`);
    const result = await this.data.test(row.name);
    this.toast(result.ok ? testToast(row.name, result.outcome) : testRefusedToast(row.name, this.errorText(result.error, NO_ANSWER)));
    this.focusAfterRender(() => this.host.querySelector<HTMLElement>(`#${idOf('conn-link', row.name)}`), true);
  }

  /**
   * Test all (kit: busy in place, the count line — a live region — says what runs, every row checks, then one
   * toast). No announcement of its own: the count line already says "Testing 12 connections".
   */
  protected async testAll(): Promise<void> {
    if (this.data.testingAll()) return;
    try {
      const end = await this.data.testAll();
      this.toast(testAllToast(end.results, end.total));
    } catch (e) {
      this.toast({ tone: 'warning', title: "Test all didn't start", body: this.errorText(e, NO_ANSWER) });
    }
  }

  private toast(t: ToastText): void {
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
  }

  // ── Delete ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Opens the confirmation for this row at once; what stops working fills in as the core answers. */
  protected askDelete(row: ConnectionRow, e: Event): void {
    this.deleting.set(row);
    this.deleteFacts.set(null);
    this.deleteError.set(null);
    this.deleteBusy.set(false);
    this.deleted = false;
    // The menu that held the item closes: focus goes back to its trigger (kit), not to the hidden item.
    const trigger = (e.target as Element | null)?.closest('.gw-menu-host')?.querySelector<HTMLElement>('[aria-haspopup="menu"]') ?? null;
    this.deleteDialog().openAfterRender(trigger);
    void this.data.deleteFacts(row).then((facts) => {
      if (this.deleting() === row) this.deleteFacts.set(facts);
    });
  }

  protected async confirmDelete(): Promise<void> {
    const row = this.deleting();
    if (!row || this.deleteBusy() || !this.deleteFacts()) return;
    this.deleteBusy.set(true);
    this.deleteError.set(null);
    try {
      const result = await this.data.remove(row.name);
      this.deleted = true;
      this.deleteDialog().close('deleted');
      const t = deletedToast(row.name, row, result);
      this.toasts.show({ tone: 'success', title: t.title, body: t.body });
    } catch (e) {
      this.deleteError.set(this.errorText(e, NO_ANSWER));
    } finally {
      this.deleteBusy.set(false);
    }
  }

  /** After a delete the row is gone: the keyboard goes to the search (kit), or to the empty state's action. */
  protected onDeleteClosed(): void {
    if (this.deleted) {
      this.focusAfterRender(() => this.search()?.nativeElement ?? this.host.querySelector<HTMLElement>('.gw-empty__actions .gw-btn'));
    }
    this.deleting.set(null);
    this.deleteFacts.set(null);
  }

  // ── Keep a row changed outside MCP Gateway ────────────────────────────────────────────────────────────────────

  protected askKeep(row: ConnectionRow, e: Event): void {
    this.keeping.set(row);
    this.keepError.set(null);
    this.keepBusy.set(false);
    this.keepDialog().openAfterRender(e.currentTarget as HTMLElement);
  }

  protected async confirmKeep(): Promise<void> {
    const row = this.keeping();
    if (!row || this.keepBusy()) return;
    this.keepBusy.set(true);
    this.keepError.set(null);
    try {
      await this.data.keep(row.name);
      this.keepDialog().close('kept');
      this.toasts.show({ tone: 'success', title: `${row.name} is in use again`, body: 'Agents can use it from their next call; its health is checked again.' });
      // The alert and its button are gone: the keyboard stays on the row.
      this.focusAfterRender(() => this.host.querySelector<HTMLElement>(`#${idOf('conn-link', row.name)}`));
    } catch (e) {
      this.keepError.set(this.errorText(e, NO_ANSWER));
    } finally {
      this.keepBusy.set(false);
    }
  }

  protected onKeepClosed(): void {
    this.keeping.set(null);
  }

  // ── Errors ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected retry(): void {
    void this.data.refresh();
  }

  /** What a failed call says to do: the core's words when it sent them. */
  protected errorText(e: unknown, fallback = 'See the core log for details, then try again.'): string {
    return isApiError(e) ? e.detail : fallback;
  }

  /** The technical side of a failed load, for the code block under Details. */
  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/connections → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
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
