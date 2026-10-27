import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivityApi, EventDetail } from '../../core/api/activity.api';
import { GwEventDetail } from '../../kit/elements/event-detail/event-detail';
import { GwEventDrawer } from '../../kit/elements/event-detail/event-drawer';
import { GwTraceView } from '../../kit/elements/event-detail/trace-view';
import { GwIcon } from '../../kit/icons';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_EVENTS, DEMO_EVENT_APPROVAL, DEMO_TRACE } from '../fixtures-events';
import { GwLiveNote, demoFixture, liveRead } from '../live';

type EventKey = keyof typeof DEMO_EVENTS;

/** The decision of the demo world's call that shows a section's state. */
const LIVE_DECISION: Partial<Record<EventKey, string>> = { allowed: 'allowed', denied: 'denied', expired: 'expired', error: 'error' };

/**
 * Dev only: design/elements/event-detail.html, each state of one logged call and a trace. Allowed, denied, expired
 * and error are the dev core's calls (`GET /api/activity/events/{id}`) when the GW_DEMO world has one with that
 * decision; the approved mutation (the world has none that ran) and the trace (the world logs no multi-call task)
 * are fixtures on the same clock.
 */
@Component({
  selector: 'gw-event-detail-demo',
  imports: [GwDemoHeader, GwEventDetail, GwEventDrawer, GwTraceView, GwIcon, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    @if (live.value(); as events) {
    <section class="gw-section" aria-labelledby="s-approved">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-approved">Approved mutation, every section</h2>
        <p class="gw-section__description">A <code class="gw-code">sql_execute</code> that waited for a human: the rule that asked for approval, the approval path and the 47 s wait in the timing.</p>
      </div>
      <div class="gw-card"><div class="gw-card__body"><article gwEventDetail [detail]="events.approved" [approval]="approval"></article></div></div>
    </section>

    <section class="gw-section" aria-labelledby="s-drawer">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-drawer">In the Activity drawer</h2>
        <p class="gw-section__description">The same element in a <code class="gw-code">gw-drawer--lg</code>, the way Activity opens it from an event row. The drawer title names the event; Esc or Close returns focus to the button.</p>
      </div>
      <div>
        <button class="gw-btn" type="button" aria-haspopup="dialog" (click)="drawer.open($any($event.currentTarget))"><svg lucideIcon="panel-right-open" class="gw-icon"></svg> Open event 7c41e2d0</button>
      </div>
      <gw-event-drawer #drawer [detail]="events.approved" [approval]="approval" description="Call 12 of 23 in trace 4f2a9c" />
    </section>

    <section class="gw-section" aria-labelledby="s-allowed">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-allowed">Allowed read</h2>
        <p class="gw-section__description">A read only <code class="gw-code">sql_query</code>: no approval section, 38 ms split between the gateway and the database.</p>
      </div>
      <div class="gw-card"><div class="gw-card__body"><article gwEventDetail [detail]="events.allowed"></article></div></div>
    </section>

    <section class="gw-section" aria-labelledby="s-denied">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-denied">Denied by policy</h2>
        <p class="gw-section__description"><code class="gw-code">DROP TABLE</code> on a prod connection: the rule that denied it is marked, nothing was executed and nobody was asked.</p>
      </div>
      <div class="gw-card"><div class="gw-card__body"><article gwEventDetail [detail]="events.denied"></article></div></div>
    </section>

    <section class="gw-section" aria-labelledby="s-expired">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-expired">Expired</h2>
        <p class="gw-section__description">An <code class="gw-code">http_request</code> on stage that nobody decided within its 10 minutes: the path ends in “Expired, denied automatically” and the request never left the gateway.</p>
      </div>
      <div class="gw-card"><div class="gw-card__body"><article gwEventDetail [detail]="events.expired"></article></div></div>
    </section>

    <section class="gw-section" aria-labelledby="s-error">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-error">Error</h2>
        <p class="gw-section__description">The policy allowed the call but <code class="gw-code">cache-redis</code> timed out: an alert under the summary says what happened and what to do; the timing shows the time went into the resource.</p>
      </div>
      <div class="gw-card"><div class="gw-card__body"><article gwEventDetail [detail]="events.error"></article></div></div>
    </section>

    <section class="gw-section" aria-labelledby="s-trace">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="s-trace">Trace view</h2>
        <p class="gw-section__description">One agent task: its 23 calls grouped into phases, with the decisions in each. Activity's Traces tab shows one block per trace; Open trace in an event leads here.</p>
      </div>
      <div class="gw-card"><div class="gw-card__body"><section gwTraceView [trace]="trace"></section></div></div>
    </section>
    } @else {
      <p [gwLiveNote]="live"></p>
    }
  `,
})
export class EventDetailDemo {
  private readonly activity = inject(ActivityApi);
  protected readonly el = kitElement('event-detail');
  private readonly fixtures = demoFixture(DEMO_EVENTS);
  protected readonly approval = demoFixture(DEMO_EVENT_APPROVAL);
  protected readonly trace = demoFixture(DEMO_TRACE);
  protected readonly live = liveRead(() => this.load());

  /** Each section's call: the newest of the world's calls with that decision, else the fixture. */
  private async load(): Promise<Record<EventKey, EventDetail>> {
    const page = await this.activity.events({ limit: 100 });
    const events = { ...this.fixtures };
    await Promise.all(
      (Object.entries(LIVE_DECISION) as [EventKey, string][]).map(async ([key, decision]) => {
        const hit = page.events.find((e) => e.decision === decision);
        if (hit) events[key] = await this.activity.event(hit.id);
      }),
    );
    return events;
  }
}
