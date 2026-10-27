import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { OverviewApi } from '../../core/api/overview.api';
import { GwSetupMap, GwSetupWiring, setupWiring } from '../../kit/elements/setup-map/setup-map';
import { GwIcon } from '../../kit/icons';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_SETUP_MAP } from '../fixtures';
import { GwLiveNote, liveRead } from '../live';

/**
 * Dev only: design/elements/setup-map.html. "Needs attention" is the dev core's world as Overview gets it
 * (`GET /api/overview` setupMap) and the wiring is endpoint dev (`GET /api/endpoints/dev`; its clients come with K1);
 * all set up and first run are fixtures, since the demo world is neither.
 */
@Component({
  selector: 'gw-setup-map-demo',
  imports: [GwDemoHeader, GwSetupMap, GwSetupWiring, GwIcon, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="s-done">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-done">All set up</h2>
        <p class="gw-section__description">Overview after setup, in setup order. Every link works: brand arrows between Done nodes. From lg the chain runs across; narrower windows stack it. The caption under the map says which way a call travels.</p>
      </header>
      <gw-setup-map [nodes]="map.done" />
    </section>

    <section class="gw-section" aria-labelledby="s-first">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-first">First run</h2>
        <p class="gw-section__description">Only the master key exists, so the first node is done. Dashed cards and dashed arrows show what is left; each status says what the node waits for, and Connections, the next one, says start here.</p>
      </header>
      <gw-setup-map [nodes]="map.first" />
    </section>

    <section class="gw-section" aria-labelledby="s-attention">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-attention">Needs attention</h2>
        <p class="gw-section__description"><code class="gw-code">billing-api</code> is unreachable and Cursor needs a restart. The badge says so, the status line names what, and the arrows beside those nodes turn neutral.</p>
      </header>
      @if (overview.value(); as o) {
        <gw-setup-map [nodes]="o.setupMap.nodes" />
      } @else {
        <p [gwLiveNote]="overview"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="s-wiring">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-wiring">Wiring on endpoint-detail</h2>
        <p class="gw-section__description">Endpoint dev in the middle: the clients that call it on the left, the connections its tools run on, with their environment and health, on the right.</p>
      </header>
      @if (wiring.value(); as w) {
        <gw-setup-wiring [wiring]="w" />
      } @else {
        <p [gwLiveNote]="wiring"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="s-vertical">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-vertical">Narrow column</h2>
        <p class="gw-section__description"><code class="gw-code">gw-setup-map--vertical</code> keeps the stacked chain at any window width, for the onboarding preview and side panels, so its sentence says top to bottom. Shown at half width: first run and needs attention.</p>
      </header>
      <div class="gw-grid gw-grid--2">
        <gw-setup-map vertical [caption]="false" [nodes]="map.first" />
        <gw-setup-map vertical [caption]="false" [nodes]="overview.value()?.setupMap?.nodes ?? map.attention" />
      </div>
      <p class="gw-text-caption gw-fg-muted" aria-hidden="true"><svg lucideIcon="arrow-up-down" class="gw-icon gw-icon--xs"></svg> Set it up top to bottom. An agent's call travels the other way: client, endpoint, policy, connection, where the secret is injected.</p>
    </section>
  `,
})
export class SetupMapDemo {
  private readonly overviewApi = inject(OverviewApi);
  private readonly endpoints = inject(EndpointsApi);
  protected readonly el = kitElement('setup-map');
  protected readonly map = DEMO_SETUP_MAP;
  protected readonly overview = liveRead(() => this.overviewApi.get());
  protected readonly wiring = liveRead(async () => setupWiring(await this.endpoints.get('dev')));
}
