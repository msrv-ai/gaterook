import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, linkedSignal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ActivityApi, ActivityEvent, Facets, Histogram, SavedView } from '../../core/api/activity.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { Env } from '../../core/api/types';
import { TimeService } from '../../core/time/time.service';
import { GwLogExplorer } from '../../kit/elements/log-explorer/log-explorer';
import { LogRange } from '../../kit/elements/log-explorer/log-view';
import { GwClientMark, clientName } from '../../kit/elements/shared/client-mark';
import { decisionLook } from '../../kit/elements/shared/dictionaries';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { duration } from '../../core/time/time-format';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_VIEWS } from '../fixtures-activity';
import { GwLiveNote, liveRead } from '../live';

/** One state of the explorer as the core answers it for a query. */
interface Slice {
  readonly facets: Facets;
  readonly histogram: Histogram;
  readonly matched: number;
}

interface Loaded {
  readonly all: Slice;
  readonly denied: Slice;
  readonly none: Slice;
  readonly events: readonly ActivityEvent[];
  readonly views: readonly SavedView[];
  readonly envs: Readonly<Record<string, Env>>;
}

const DENIED_PROD = 'decision:denied env:prod';
const NOTHING = 'decision:expired env:prod client:cursor';

/**
 * Dev only: design/elements/log-explorer.html with the dev core's last 24 hours: facets, histogram and events of
 * `/api/activity/*` for each query, the connections' environments from `/api/connections`. Saved views are the
 * person's own (`activity.saved-views`): the kit's three when the demo world has none.
 */
@Component({
  selector: 'gw-log-explorer-demo',
  imports: [NgTemplateOutlet, GwDemoHeader, GwLogExplorer, GwClientMark, GwIcon, RouterLink, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <ng-template #rows>
      <div class="gw-table-wrap" role="region" aria-labelledby="le-table-cap" tabindex="0">
        <table class="gw-table gw-table--dense gw-table--interactive">
          <caption class="gw-sr-only" id="le-table-cap">Calls in the last 24 hours, newest first (page content, first 4 rows)</caption>
          <thead><tr><th scope="col">Time</th><th scope="col">Decision</th><th scope="col">Tool · connection</th><th scope="col">Client · endpoint</th><th scope="col" class="gw-table__cell--num">Duration</th></tr></thead>
          <tbody>
            @for (e of live.value()?.events ?? []; track e.id) {
              <tr>
                <td><a class="gw-table__row-link" routerLink="/dev/elements/event-detail"><time [attr.datetime]="time.iso(e.at)" [title]="time.iso(e.at)">{{ time.relative(e.at) }}</time><span class="gw-sr-only"> at {{ time.clock(e.at, true) }}, open call {{ e.tool }} on {{ e.connection }}</span></a></td>
                <td><span class="gw-badge gw-badge--{{ look(e.decision).tone }} gw-badge--sm"><svg [lucideIcon]="look(e.decision).icon" class="gw-icon gw-badge__icon"></svg>{{ look(e.decision).text }}</span></td>
                <td class="gw-table__cell--truncate"><div class="gw-text-code-sm gw-truncate" [title]="e.tool">{{ e.tool }}</div><div class="gw-text-caption gw-truncate"><span class="gw-mono" [title]="e.connection">{{ e.connection }}</span> <span class="gw-sr-only">environment</span> <span class="gw-env gw-env--{{ e.env }} gw-env--sm">{{ e.env }}</span></div></td>
                <td class="gw-table__cell--truncate"><div class="gw-truncate"><span [gwClientMark]="e.client ?? ''" size="sm"></span> {{ name(e.client) }}</div><div class="gw-text-caption gw-fg-muted gw-truncate"><svg lucideIcon="waypoints" class="gw-icon gw-icon--xs"></svg><span class="gw-sr-only">, endpoint</span> <span class="gw-mono" [title]="e.endpoint">{{ e.endpoint }}</span></div></td>
                <td class="gw-table__cell--num">{{ e.durationMs != null ? dur(e.durationMs) : e.decision === 'pending' ? dur(now() - parse(e.at)) + ' so far' : 'Not run' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </ng-template>

    @if (live.value(); as l) {
    <section class="gw-section" aria-labelledby="s-default">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-default">Last 24 hours, no filters</h2>
        <p class="gw-section__description">The full layout: query row, filter row, facets on the left (240px from 1280px), histogram and then the page's events table, shown here with its first four rows. Below 1280px the facets fold into the Filters button.</p>
      </div>
      <section gwLogExplorer [(query)]="q1" [facets]="l.all.facets" [histogram]="l.all.histogram" [total]="l.all.matched" [savedViews]="l.views" [connectionEnvs]="l.envs" (export)="say('Export ' + $event)" (saveView)="say('Save view: ' + ($event || 'all calls'))">
        <ng-container [ngTemplateOutlet]="rows" />
      </section>
    </section>

    <section class="gw-section" aria-labelledby="s-filtered">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-filtered">Filters applied</h2>
        <p class="gw-section__description">Two checked facets become two tags; each facet counts within the other filters, and the histogram and the count show only matching calls. Click a bar: it stays pressed (one per chart) and the app narrows the range to that hour.</p>
      </div>
      <section gwLogExplorer [(query)]="q2" [facets]="l.denied.facets" [histogram]="l.denied.histogram" [matched]="l.denied.matched" [total]="l.all.matched" [savedViews]="l.views" [connectionEnvs]="l.envs"></section>
    </section>

    <section class="gw-section" aria-labelledby="s-zoomed">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-zoomed">Zoomed into one hour</h2>
        <p class="gw-section__description">After a click on the 10:00 bar: the bar stays pressed (one per chart), the range trigger names the hour and the table below lists only its 150 calls. The histogram keeps the last 24 hours around it, so the next hour is one click away. Facets are collapsed here.</p>
      </div>
      <section gwLogExplorer [(zoom)]="zoom" [facetsOpen]="false" [facets]="l.all.facets" [histogram]="l.all.histogram" [matched]="zoomed()" [savedViews]="l.views" [connectionEnvs]="l.envs"></section>
    </section>

    <section class="gw-section" aria-labelledby="s-live">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-live">Live</h2>
        <p class="gw-section__description">Live is a pressed toggle with a pulsing dot; the range ends at now and new calls stream in at the top of the table. Rows 1 and 2 alone are also what Insights uses.</p>
      </div>
      <section gwLogExplorer variant="compact" query="connection:cache-redis" [range]="lastHour" [live]="true" [matched]="4"></section>
    </section>

    <section class="gw-section" aria-labelledby="s-empty">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-empty">No results, filters collapsed</h2>
        <p class="gw-section__description">Nothing matches: the main column says so and offers one way out. The Filters button is collapsed (the default below 1280px), so the main column takes the full width.</p>
      </div>
      <section gwLogExplorer [(query)]="q3" [facetsOpen]="false" [facets]="l.none.facets" [matched]="l.none.matched" [total]="l.all.matched" [savedViews]="l.views" [connectionEnvs]="l.envs" emptyHint="Cursor is detected but not connected to an endpoint yet." retention="1 year"></section>
    </section>
    } @else {
      <p [gwLiveNote]="live"></p>
    }
  `,
})
export class LogExplorerDemo {
  protected readonly time = inject(TimeService);
  private readonly toasts = inject(ToastService);
  private readonly activity = inject(ActivityApi);
  private readonly connections = inject(ConnectionsApi);
  protected readonly el = kitElement('log-explorer');
  protected readonly live = liveRead(() => this.load());
  protected readonly lastHour: LogRange = { kind: 'relative', key: '1h' };
  protected readonly q1 = signal('');
  protected readonly q2 = signal(DENIED_PROD);
  protected readonly q3 = signal(NOTHING);
  /** Zoomed into the busiest hour of the demo world. */
  protected readonly zoom = linkedSignal<string | null>(() => {
    const buckets = this.live.value()?.all.histogram.buckets ?? [];
    return buckets.reduce<(typeof buckets)[number] | null>((best, b) => (b.total > (best?.total ?? 0) ? b : best), null)?.from ?? null;
  });
  protected readonly zoomed = () => this.live.value()?.all.histogram.buckets.find((b) => b.from === this.zoom())?.total ?? 0;
  protected readonly look = decisionLook;
  protected readonly name = (c: string | null) => (c ? clientName(c) : 'Unknown');
  protected readonly dur = duration;
  protected readonly parse = Date.parse;
  protected readonly now = () => this.time.now();

  private async slice(q: string): Promise<Slice> {
    const [facets, histogram] = await Promise.all([this.activity.facets({ q }), this.activity.histogram({ q })]);
    return { facets, histogram, matched: histogram.buckets.reduce((n, b) => n + b.total, 0) };
  }

  private async load(): Promise<Loaded> {
    const [all, denied, none, page, views, conns] = await Promise.all([
      this.slice(''),
      this.slice(DENIED_PROD),
      this.slice(NOTHING),
      this.activity.events({ limit: 4 }),
      this.activity.views(),
      this.connections.list(),
    ]);
    const envs: Record<string, Env> = {};
    for (const c of conns.items) envs[c.name] = c.env;
    return { all, denied, none, events: page.events, views: views.views.length ? views.views : DEMO_VIEWS, envs };
  }

  protected say(text: string): void {
    this.toasts.show({ tone: 'info', title: 'Demo', body: `${text} (the page calls the core).` });
  }
}
