import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  signal,
  untracked,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { CoreStore } from '../../core/stores/core.store';
import { formatCount } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { GwToggle } from '../../kit/disclosure';
import { GwLogExplorer } from '../../kit/elements/log-explorer/log-explorer';
import { LogRange } from '../../kit/elements/log-explorer/log-view';
import { GwClientMark } from '../../kit/elements/shared/client-mark';
import { GwIcon } from '../../kit/icons';
import { InsightsData } from './insights.data';
import {
  InsightsRangeKey,
  Segment,
  activityLink,
  approvalsView,
  bodyOf,
  callsChart,
  clientsChart,
  deniedView,
  errorsView,
  isRangeKey,
  latency,
  latencyView,
  rangeWords,
  statTiles,
  statusLine,
  toolsChart,
  unusualAlert,
  widerRange,
} from './insights-view';

/** The first render waits this long before it shows placeholders (kit: a skeleton only past ~300 ms). */
export const SKELETON_DELAY_MS = 300;

/**
 * Insights (design/pages/insights.html; ARCHITECTURE.md §8.4 "insights"): what agents did through the gateway in a
 * range, against the range before it, and a link to the calls behind every number —
 * 1. the filter bar (log-explorer, Insights variant): Activity's query language and a range from the last hour to
 *    the last 30 days; every number below follows both;
 * 2. six KPI tiles with their changes, the calls sparkline and the overhead meter against its target;
 * 3. Unusual activity (the core's detectors, compared with the last 7 days), each finding opening its calls;
 * 4. Volume (calls over time by decision, top tools, by client), Decisions (approvals, denied by rule), Latency and
 *    errors (gateway vs resource time, errors with each connection's health now).
 *
 * Charts are static (`role="img"` with the conclusion in words) and every one has its numbers in a screen-reader
 * table. Data is the core's (InsightsData): `GET /api/insights` (step K5 — until it is merged the page says "not
 * available yet"), clients without calls, health now and the approval windows from the merged areas. The page is a
 * loading skeleton, an error with Try again, first run, "no calls in this range", or the numbers.
 *
 * Route parameters (`/insights?q=&range=`, withComponentInputBinding) open a filtered view; the page writes the
 * query and range back to the URL, so a reload or a copied link shows the same numbers.
 */
@Component({
  selector: 'gw-insights-page',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwToggle, GwCodeBlock, GwLogExplorer, GwClientMark],
  providers: [InsightsData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './insights.page.html',
})
export class InsightsPage {
  /** Route parameter `q`: Activity's query language. */
  readonly q = input<string | null>(null);
  /** Route parameter `range`: `1h`, `24h` (the default), `7d` or `30d`. */
  readonly range = input<string | null>(null);

  protected readonly data = inject(InsightsData);
  private readonly time = inject(TimeService);
  private readonly core = inject(CoreStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  /** Two columns only when each gets 480 px (12 × control-lg), as the kit's Decisions and Latency grids. */
  protected readonly twoColumnMin = 'calc(var(--gw-size-control-lg) * 12)';
  /** Truncated cells of the kit's tables: the example of a denial, the most common error. */
  protected readonly exampleMax = 'calc(var(--gw-space-16) * 3)';
  protected readonly messageMax = 'calc(var(--gw-space-12) * 3)';

  protected readonly formatCount = formatCount;
  protected readonly latencyText = latency;

  /** The query and range the person is looking at: from the URL, then from the filter bar. */
  protected readonly query = linkedSignal(() => this.q() ?? '');
  protected readonly logRange = linkedSignal<LogRange>(() => {
    const r = this.range();
    return { kind: 'relative', key: isRangeKey(r) ? r : '24h' };
  });
  protected readonly key = computed<InsightsRangeKey>(() => {
    const r = this.logRange();
    return r.kind === 'relative' && isRangeKey(r.key) ? r.key : '24h';
  });

  /** Placeholders only when the first answer is slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.insights() && !this.data.error() && !this.data.notAvailable());

  protected readonly ins = this.data.insights;
  /** The words and links follow the filter of the numbers on screen, not one still loading. */
  protected readonly shownKey = computed(() => this.data.loaded()?.key ?? this.key());
  protected readonly shownQuery = computed(() => this.data.loaded()?.q ?? this.query());
  protected readonly shownFiltered = computed(() => this.shownQuery().trim().length > 0);
  protected readonly words = computed(() => rangeWords(this.shownKey()));
  protected readonly wider = computed(() => {
    const w = widerRange(this.shownKey());
    return w ? { key: w, span: rangeWords(w).span } : null;
  });

  /** The filter bar's count line; empty until numbers came (never "0 calls" for a read still on its way). */
  protected readonly status = computed(() => {
    const i = this.ins();
    return i ? statusLine(i, this.shownFiltered(), this.shownKey()) : '';
  });
  protected readonly body = computed(() => {
    const i = this.ins();
    return i ? bodyOf(i) : null;
  });

  protected readonly openInActivity = computed(() => activityLink(this.query(), this.key()));
  protected readonly allCalls = computed(() => activityLink(this.shownQuery(), this.shownKey()));
  /** The query parameters of this page's own URL (links to a section of it keep the filter). */
  protected readonly pageParams = computed(() => urlParams(this.query(), this.key()));

  protected readonly tiles = computed(() => {
    const i = this.ins();
    return i ? statTiles({ insights: i, key: this.shownKey(), pageQuery: this.shownQuery(), now: this.time.minute() }) : [];
  });
  protected readonly unusual = computed(() => (this.ins()?.unusual ?? []).map((u) => unusualAlert(u, this.shownKey(), this.time.minute())));
  protected readonly unusualCount = computed(() => {
    const n = this.unusual().length;
    return `${n} ${n === 1 ? 'item' : 'items'}`;
  });
  protected readonly calls = computed(() => {
    const i = this.ins();
    return i ? callsChart(i.callsOverTime, this.shownKey(), this.time.minute()) : null;
  });
  protected readonly tools = computed(() => {
    const i = this.ins();
    return i ? toolsChart(i.topTools, this.shownKey(), this.shownQuery()) : null;
  });
  protected readonly clients = computed(() => {
    const i = this.ins();
    return i ? clientsChart(i.byClient, this.data.cards(), this.shownFiltered(), this.shownKey(), this.shownQuery()) : null;
  });
  protected readonly approvals = computed(() => {
    const i = this.ins();
    return i ? approvalsView(i.approvals, this.data.windows(), this.shownKey(), this.shownQuery(), this.time.minute()) : null;
  });
  protected readonly denied = computed(() => {
    const i = this.ins();
    return i ? deniedView(i.deniedByRule, this.shownKey(), this.shownQuery()) : null;
  });
  protected readonly latency = computed(() => {
    const i = this.ins();
    return i ? latencyView(i.latency.byConnection, this.shownKey(), this.shownQuery()) : null;
  });
  protected readonly errors = computed(() => {
    const i = this.ins();
    return i ? errorsView(i.errorsByConnection, this.data.health(), this.shownKey(), this.shownQuery()) : null;
  });

  constructor() {
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });

    // The filter drives the reads; the first one starts the data and its events.
    let started = false;
    effect(() => {
      const filter = { q: this.query().trim(), key: this.key() };
      untracked(() => {
        if (!started) {
          started = true;
          void this.data.start(filter);
        } else {
          void this.data.setFilter(filter);
        }
        this.writeUrl(filter.q, filter.key);
      });
    });

    // The core came back (it was away, or another instance answers): what the page shows may be stale.
    let wasRunning = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const running = this.core.running();
      const changes = this.core.instanceChanges();
      untracked(() => {
        if (running && (!wasRunning || changes !== instance)) void this.data.reconnect();
        wasRunning = running;
        instance = changes;
      });
    });
  }

  protected retry(): void {
    void this.data.refresh();
  }

  protected clearFilters(): void {
    this.query.set('');
  }

  protected widen(key: InsightsRangeKey): void {
    this.logRange.set({ kind: 'relative', key });
  }

  /** "See by rule": the card's heading gets focus and scrolls into view (the router doesn't scroll to anchors here). */
  protected jump(id: string): void {
    const target = this.host.querySelector<HTMLElement>(`#${id}`);
    if (!target) return;
    target.scrollIntoView?.({ block: 'start' });
    target.focus({ preventScroll: true });
  }

  /** `gw-chart__segment` with its tone; the kit's default segment (chart.1) has no modifier. */
  protected segmentClass(s: Segment): string {
    return s.key === 'default' ? 'gw-chart__segment' : `gw-chart__segment gw-chart__segment--${s.key}`;
  }

  /** What a failed read says to do: the core's words when it sent them. */
  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : 'See the core log for details, then try again.';
  }

  /** The technical side of a failed read, for the code block under Details. */
  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/insights → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  /** Keeps `?q=&range=` in step with the filter without a history entry per keystroke. */
  private writeUrl(q: string, key: InsightsRangeKey): void {
    const want = urlParams(q, key);
    const have = this.route.snapshot.queryParamMap;
    if ((have.get('q') ?? undefined) === want['q'] && (have.get('range') ?? undefined) === want['range']) return;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q: want['q'] ?? null, range: want['range'] ?? null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
      preserveFragment: true,
    });
  }
}

/** The page's own query parameters: nothing for the default view (no query, last 24 hours). */
function urlParams(q: string, key: InsightsRangeKey): { [key: string]: string } {
  const out: { [key: string]: string } = {};
  if (q.trim()) out['q'] = q.trim();
  if (key !== '24h') out['range'] = key;
  return out;
}
