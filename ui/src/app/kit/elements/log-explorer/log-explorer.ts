import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, model, output, signal, viewChild } from '@angular/core';
import { Facets, Histogram, SavedView } from '../../../core/api/activity.api';
import { Env } from '../../../core/api/types';
import { formatCount } from '../../../core/time/time-format';
import { TimeService } from '../../../core/time/time.service';
import { GwChart } from '../../chart';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { GwMenu, GwMenuTrigger, MenuPick } from '../../menu';
import { GwPressed } from '../../pressed';
import { GwSearchClearButton } from '../../search';
import {
  FacetOption,
  INSIGHTS_RANGES,
  LogRange,
  RANGES,
  RangeKey,
  SERIES,
  commitInput,
  countText,
  facetGroups,
  histogramView,
  legendCounts,
  localInput,
  pageCountText,
  parseQuery,
  rangeBounds,
  rangeLabel,
  rangeTitle,
  tagTone,
  toggleTag,
} from './log-view';

let nextId = 0;

export type ExportKind = 'csv' | 'jsonl' | 'siem';

/**
 * `<section gwLogExplorer [(query)]="q" [(range)]="r" [(live)]="live" [facets]="f" [histogram]="h" …>`
 * (elements/log-explorer.html): the query and filter area above the events table. Row 1: the structured query
 * (`field:value`, Enter makes tags; `/` focuses it through the shared shortcut), its syntax popover, the time range,
 * Live, saved views and export. Row 2: Filters, the active filters as tags, Clear all and the count (`role=status`).
 * Then the facets (checkboxes counted within the other filters) and the histogram by decision; a bar zooms into its
 * hour, a second click returns. The page puts the events table inside (`<ng-content>`); with no match the element
 * shows the empty state. `variant="compact"` is rows 1 and 2 only (the element demo's Live state).
 * `variant="insights"` is rows 1 and 2 as design/pages/insights.html draws them: the query, its syntax (the fields
 * that narrow a chart), the range (Last hour to Last 30 days, no custom range: every number compares with the range
 * before it) and the tags; no Live, Saved views or Export, and the count line is the page's (`status`).
 *
 * The element fetches nothing: the page reacts to `query`, `range`, `zoom` and `live`.
 *
 * `page`: the Activity page's own variant (pages/activity.html), where Export sits in the page header, the empty
 * states live in the Events tab under the histogram (which stays, so another hour is one click away), the count line
 * says how many calls wait for approval, the histogram has four gridlines and the syntax popover is headed at level 2.
 */
@Component({
  selector: 'section[gwLogExplorer]',
  imports: [NgTemplateOutlet, GwIcon, GwMenu, GwMenuTrigger, GwPressed, GwToggle, GwChart, GwDialog, GwDialogClose, GwSearchClearButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-log-explorer',
    // Activity's page variant names the region as its page does ("Call log"); a host binding would otherwise win.
    '[attr.aria-label]': "variant() === 'insights' ? 'Filter insights' : page() ? 'Call log' : 'Filter calls'",
  },
  templateUrl: './log-explorer.html',
})
export class GwLogExplorer {
  protected readonly time = inject(TimeService);

  /** The query: filter tags and free text (`decision:denied env:prod`). */
  readonly query = model('');
  readonly range = model<LogRange>({ kind: 'relative', key: '24h' });
  readonly live = model(false);
  /** The zoomed bar's start (ISO), or null. */
  readonly zoom = model<string | null>(null);
  readonly facetsOpen = model(true);

  readonly facets = input<Facets | null>(null);
  readonly histogram = input<Histogram | null>(null);
  /** Calls in the range matching the query, and in the range without filters. */
  readonly matched = input<number | null>(null);
  readonly total = input<number | null>(null);
  readonly savedViews = input<readonly SavedView[]>([]);
  /** The environment of each connection, for its facet rows. */
  readonly connectionEnvs = input<Readonly<Record<string, Env>>>({});
  readonly variant = input<'full' | 'compact' | 'insights'>('full');
  /** The count line, when the page words it itself (Insights: "1,284 calls · compared with the previous 24 hours"). */
  readonly status = input<string | null>(null);
  /** One more sentence for the empty state ("Cursor is detected but not connected to an endpoint yet."). */
  readonly emptyHint = input<string | null>(null);
  /** The audit retention for the custom range's note ("1 year"). */
  readonly retention = input<string | null>(null);
  /** The Activity page's variant (see above). */
  readonly page = input(false, { transform: booleanAttribute });
  /** Calls waiting for approval in the range (`page`: "1,287 calls · 3 waiting for approval"). */
  readonly waiting = input<number | null>(null);
  /** `page`: the custom range dialog's sentence about retention (the page knows the setting and its default). */
  readonly customNote = input<string | null>(null);

  readonly export = output<ExportKind>();
  /** Save current view…: the page asks for a name and saves `{name, q, view}`. */
  readonly saveView = output<string>();

  protected readonly uid = `le-${++nextId}`;
  protected readonly insights = computed(() => this.variant() === 'insights');
  protected readonly ranges = computed(() => (this.variant() === 'insights' ? INSIGHTS_RANGES : RANGES));
  protected readonly series = SERIES;
  private readonly customDialog = viewChild<GwDialog>('custom');

  protected readonly parsed = computed(() => parseQuery(this.query()));
  protected readonly tags = computed(() => this.parsed().tags);
  protected readonly filtered = computed(() => this.tags().length > 0 || this.parsed().text.length > 0);
  protected readonly bounds = computed(() => rangeBounds(this.range(), this.time.minute()));
  protected readonly zoomed = computed(() => {
    const z = this.zoom();
    const b = z ? this.histogram()?.buckets.find((x) => x.from === z) : null;
    return b ?? null;
  });
  protected readonly label = computed(() => {
    const z = this.zoomed();
    if (z) return rangeLabel({ kind: 'custom', from: z.from, to: z.to }, this.time.minute());
    return rangeLabel(this.range(), this.time.minute());
  });
  protected readonly title = computed(() => {
    const z = this.zoomed();
    if (z) return rangeTitle({ kind: 'custom', from: z.from, to: z.to }, this.time.minute(), false);
    return rangeTitle(this.range(), this.time.minute(), this.live());
  });
  protected readonly rangeWords = computed(() => (this.range().kind === 'relative' && !this.zoomed() ? this.label().toLowerCase() : `range ${this.label()}`));
  protected readonly groups = computed(() => facetGroups(this.facets(), this.query(), this.connectionEnvs()));
  protected readonly count = computed(() => {
    const status = this.status();
    if (status !== null) return status;
    const z = this.zoomed();
    if (this.page()) {
      const zoom = z ? { from: this.time.clock(z.from), to: this.time.clock(z.to) } : null;
      return pageCountText({ matched: this.matched(), total: this.total(), waiting: this.waiting(), filtered: this.filtered(), live: this.live(), zoom });
    }
    const text = countText(this.matched(), this.total(), this.filtered(), this.live());
    return z && !this.filtered() ? `${text} from ${this.time.clock(z.from)} to ${this.time.clock(z.to)}` : text;
  });
  /** What a second click on the zoomed bar returns to: "last 24 hours", "whole range". */
  protected readonly rangeBack = computed(() => {
    const r = this.range();
    return r.kind === 'relative' ? rangeLabel(r, this.time.minute()).toLowerCase() : 'whole range';
  });
  protected readonly chart = computed(() => {
    const h = this.histogram();
    return h ? histogramView(h, this.bounds().toNow, this.time.minute(), this.page()) : null;
  });
  protected readonly legend = computed(() => legendCounts(this.histogram(), this.zoom()));
  /** The element's own empty state; the page variant shows its own in the Events tab instead. */
  protected readonly empty = computed(() => this.matched() === 0 && !this.page());
  /** The local time zone's short name for the custom range ("CEST", "GMT+2"), when the platform knows one. */
  protected readonly zone = timeZoneName();
  protected readonly inputText = signal<string | null>(null);
  protected readonly searchValue = computed(() => this.inputText() ?? this.parsed().text);
  protected readonly selectedView = computed(() => this.savedViews().find((v) => v.q === this.query())?.name ?? (this.query() ? null : 'All calls'));

  protected readonly customFrom = signal('');
  protected readonly customTo = signal('');

  protected readonly formatCount = formatCount;

  protected onInput(e: Event): void {
    const value = (e.target as HTMLInputElement).value;
    this.inputText.set(value);
    // Clear (the × button or an emptied field) drops the free text at once; tags stay.
    if (!value && this.parsed().text) this.query.set(this.tags().join(' '));
  }

  /** Enter: every `field:value` becomes a tag, the rest stays as text. */
  protected onEnter(e: Event): void {
    e.preventDefault();
    const typed = (e.target as HTMLInputElement).value;
    this.query.set(commitInput(this.query(), typed));
    this.inputText.set(null);
  }

  protected onFacet(o: FacetOption, e: Event): void {
    this.query.set(toggleTag(this.query(), o.tag, (e.target as HTMLInputElement).checked));
  }

  protected removeTag(tag: string): void {
    this.query.set(toggleTag(this.query(), tag, false));
  }

  protected clearAll(): void {
    this.query.set('');
    this.inputText.set(null);
  }

  protected onRange(p: MenuPick): void {
    const key = p.value as RangeKey;
    if (this.ranges().some((r) => r.key === key)) {
      this.zoom.set(null);
      this.range.set({ kind: 'relative', key });
    }
  }

  protected openCustom(): void {
    const b = this.bounds();
    this.customFrom.set(localInput(b.from));
    this.customTo.set(localInput(b.to));
    this.customDialog()?.openAfterRender();
  }

  protected applyCustom(e: Event): void {
    e.preventDefault();
    const from = new Date(this.customFrom());
    const to = new Date(this.customTo());
    if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime()) || to <= from) return;
    this.zoom.set(null);
    this.live.set(false);
    this.range.set({ kind: 'custom', from: from.toISOString(), to: to.toISOString() });
    this.customDialog()?.close('apply');
  }

  protected customInvalid(): boolean {
    const from = Date.parse(this.customFrom());
    const to = Date.parse(this.customTo());
    return Number.isNaN(from) || Number.isNaN(to) || to <= from;
  }

  protected onView(p: MenuPick): void {
    if (p.value === '__save') this.saveView.emit(this.query());
    else if (p.value === '__all') this.query.set('');
    else {
      const v = this.savedViews().find((x) => x.name === p.value);
      if (v) this.query.set(v.q);
    }
  }

  protected onExport(p: MenuPick): void {
    if (p.value === 'csv' || p.value === 'jsonl' || p.value === 'siem') this.export.emit(p.value);
  }

  /** A bar click: zoom into it, or back out when it was the zoomed one (the chart keeps one pressed). */
  protected onBar(index: number | null): void {
    const b = index == null ? null : (this.histogram()?.buckets[index] ?? null);
    this.zoom.set(b ? b.from : null);
  }

  protected tone(tag: string): string | null {
    return tagTone(tag);
  }
}

function timeZoneName(): string | null {
  try {
    return new Intl.DateTimeFormat('en-US', { timeZoneName: 'short' }).formatToParts(new Date()).find((p) => p.type === 'timeZoneName')?.value ?? null;
  } catch {
    return null;
  }
}
