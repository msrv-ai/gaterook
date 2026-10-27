import { ChangeDetectionStrategy, Component, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EndpointsApi, SplitPreview } from '../../core/api/endpoints.api';
import { GwRiskCallout } from '../../kit/elements/risk-callout/risk-callout';
import { GwSplitDialog, SplitRequest } from '../../kit/elements/risk-callout/split-dialog';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_RISK, DEMO_SPLIT } from '../fixtures';
import { GwLiveNote, demoFixture, liveRead } from '../live';

/**
 * Dev only: design/elements/risk-callout.html. dev, stage-readonly and incident-prod are the dev core's
 * (`GET /api/endpoints/{slug}/risk`, the endpoint list for the compact rows, the split preview); the partial
 * combination (an endpoint being built) and a dismissal are fixtures: the demo world has neither. The split options
 * are the page's suggestions, also fixtures.
 */
@Component({
  selector: 'gw-risk-callout-demo',
  imports: [GwDemoHeader, GwRiskCallout, GwSplitDialog, RouterLink, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="rc-full">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="rc-full">All three ingredients</h2>
        <p class="gw-section__description">Endpoint <code class="gw-code">dev</code> reads private data, takes in untrusted text and can send data out. Present ingredients are red; the alert stays amber because a person decides what to change. Split endpoint opens the split dialog: the move is pre-selected (the external channel), before / after shows both endpoints' ingredients, then the new slug, the clients to connect and “Create dev-billing”.</p>
      </header>
      @if (dev.value(); as r) {
        <div gwRiskCallout [risk]="r" slug="dev" (split)="split().open()"></div>
      } @else {
        <p [gwLiveNote]="dev"></p>
      }
      <gw-split-dialog slug="dev" [options]="splitData.options" [clients]="splitData.clients" [preview]="splitPreview()" (changed)="previewSplit($event)" />
    </section>

    <section class="gw-section" aria-labelledby="rc-public">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="rc-public">All three, on the endpoint published to the internet</h2>
        <p class="gw-section__description">Database rows and HTTP responses are text someone else wrote, so <code class="gw-code">stage-readonly</code> has all three ingredients even though it only reads: <code class="gw-code">http_get</code> can carry data out in a URL. It is the endpoint claude.ai reaches through the tunnel, so the callout says so.</p>
      </header>
      @if (stage.value(); as r) {
        <div gwRiskCallout [risk]="r" slug="stage-readonly"></div>
      } @else {
        <p [gwLiveNote]="stage"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="rc-partial">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="rc-partial">Two of three, while building an endpoint</h2>
        <p class="gw-section__description">The Tools step of Create endpoint, with <code class="gw-code">aws_describe</code> on <code class="gw-code">aws-stage</code> and <code class="gw-code">http_request</code> on <code class="gw-code">billing-api</code> picked so far. Private data and an external channel are present (amber); untrusted content is absent (neutral, with a check and the word “No”), because resource settings are written by your team. The warning shows as soon as private data has a way out.</p>
      </header>
      <div gwRiskCallout [risk]="risk.partial"></div>
    </section>

    <section class="gw-section" aria-labelledby="rc-limited">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="rc-limited">Contained: the only way out is fixed runbooks</h2>
        <p class="gw-section__description"><code class="gw-code">incident-prod</code> reads prod data and prod logs, and SSH reaches <code class="gw-code">prod-vm</code>. SSH is a channel, but here the agent can only run two fixed runbooks with no free-text parameters, so it can't put data into it. An info alert explains the mitigation and what would break it; it never says nothing can send data out.</p>
      </header>
      @if (prod.value(); as r) {
        <div gwRiskCallout [risk]="r" slug="incident-prod"></div>
      } @else {
        <p [gwLiveNote]="prod"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="rc-compact">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="rc-compact">Compact, in the endpoint list</h2>
        <p class="gw-section__description">One line per endpoint row: <code class="gw-code">--compact</code> names the ingredients that are present and wraps inside the cell instead of widening the table. A contained endpoint says what limits its channel.</p>
      </header>
      <div class="gw-table-wrap" role="region" aria-labelledby="rc-endpoints-cap" tabindex="0">
        <table class="gw-table">
          <caption class="gw-sr-only" id="rc-endpoints-cap">Endpoints with their risk</caption>
          <thead><tr><th scope="col">Endpoint</th><th scope="col" class="gw-table__cell--num">Connections</th><th scope="col" class="gw-table__cell--num">Tools</th><th scope="col">Risk</th></tr></thead>
          <tbody>
            @for (e of endpoints.value() ?? []; track e.slug) {
              <tr>
                <th scope="row" class="gw-table__cell--mono"><a class="gw-link" [routerLink]="['/endpoints', e.slug]">{{ e.slug }}</a></th>
                <td class="gw-table__cell--num">{{ e.connections.total }}</td><td class="gw-table__cell--num">{{ e.tools.read + e.tools.approval + e.tools.off }}</td>
                <td><div gwRiskCallout compact [risk]="e.risk" [slug]="e.slug"></div></td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (!endpoints.value()) {
        <p [gwLiveNote]="endpoints"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="rc-dismissed">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="rc-dismissed">Dismissed</h2>
        <p class="gw-section__description">After “Dismiss for this endpoint” the callout shrinks to one neutral line with the endpoint, who, when and the reason, and one action to bring it back. It returns by itself when a tool adds to the combination.</p>
      </header>
      <div gwRiskCallout [risk]="risk.dismissed" slug="dev"></div>
    </section>
  `,
})
export class RiskCalloutDemo {
  private readonly api = inject(EndpointsApi);
  protected readonly el = kitElement('risk-callout');
  protected readonly dev = liveRead(() => this.api.risk('dev'));
  protected readonly stage = liveRead(() => this.api.risk('stage-readonly'));
  protected readonly prod = liveRead(() => this.api.risk('incident-prod'));
  protected readonly endpoints = liveRead(() => this.api.list());
  protected readonly risk = demoFixture(DEMO_RISK);
  protected readonly splitData = DEMO_SPLIT;
  protected readonly splitPreview = signal<SplitPreview | null>(null);
  protected readonly split = viewChild.required(GwSplitDialog);

  /** As the endpoint page does: every change of the move asks the core for a new before / after (nothing is saved). */
  protected previewSplit(r: SplitRequest): void {
    this.api.splitPreview('dev', r).then(
      (p) => this.splitPreview.set(p),
      () => this.splitPreview.set(null),
    );
  }
}
