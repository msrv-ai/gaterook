import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Insights, InsightsApi } from '../../core/api/insights.api';
import { EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { GwIcon } from '../../kit/icons';
import { rangeQuery } from '../insights/insights-view';
import { activityQuery, callsStat } from './connection-new-view';

/**
 * How soon a call of this connection is counted. A month's sums need not move with every call, so a busy agent
 * refreshes them every 15 s at most, as on Insights.
 */
const REFRESH_MS = 15_000;

/**
 * The edit view's stat "Calls, last 30 days": `GET /api/insights?q=connection:<name>` over the last 30 days on the
 * core's clock, drawn as the count, the calls per day as a sparkline, the change against the 30 days before, and the
 * errors, denials and waiting approvals, each opening its calls. Calls of this connection and a reopened event stream
 * read it again; a refresh that fails keeps the numbers on screen.
 */
@Component({
  selector: 'div[gwConnectionCalls]',
  imports: [RouterLink, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-stat', '[attr.aria-busy]': "state() === 'loading' ? 'true' : null" },
  templateUrl: './connection-calls.html',
})
export class GwConnectionCalls {
  private readonly api = inject(InsightsApi);
  private readonly events = inject(EventStream);
  private readonly time = inject(TimeService);

  readonly connection = input.required<string>();

  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  private readonly insights = signal<Insights | null>(null);
  protected readonly stat = computed(() => {
    const insights = this.insights();
    return insights ? callsStat(insights, this.connection(), this.time.minute()) : null;
  });

  private run = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      this.connection();
      untracked(() => {
        this.cancel();
        this.insights.set(null);
        void this.load();
      });
    });
    const offCalls = this.events.on('audit.call', (call) => {
      if (call.connection === untracked(this.connection)) this.schedule();
    });
    // A stream that was down missed calls: read the numbers again.
    let seen = this.events.reopened();
    effect(() => {
      const n = this.events.reopened();
      if (n === seen) return;
      seen = n;
      untracked(() => void this.load());
    });
    inject(DestroyRef).onDestroy(() => {
      offCalls();
      this.cancel();
      ++this.run;
    });
  }

  protected retry(): void {
    void this.load();
  }

  private schedule(): void {
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.load();
    }, REFRESH_MS);
  }

  private cancel(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private async load(): Promise<void> {
    const run = ++this.run;
    if (!this.insights()) this.state.set('loading');
    try {
      const answer = await this.api.get({ q: activityQuery(this.connection()), ...rangeQuery('30d', this.time.now()) });
      if (run !== this.run) return;
      this.insights.set(answer);
      this.state.set('ready');
    } catch {
      if (run !== this.run) return;
      if (!this.insights()) this.state.set('error');
    }
  }
}
