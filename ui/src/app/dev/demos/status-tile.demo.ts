import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { GwStatusTile } from '../../kit/elements/status-tile/status-tile';
import { GwIcon } from '../../kit/icons';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';

/**
 * Dev only: design/elements/status-tile.html — the tile on its own, in the kit's sets: connections (problems first),
 * AI apps (the action fits the state), an action unavailable with its reason (first run), busy (Test again running)
 * and a narrow tile. The data are the kit's (BLUEPRINT §9); Test again here only runs the busy state.
 */
@Component({
  selector: 'gw-status-tile-demo',
  imports: [GwDemoHeader, GwStatusTile, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="st-conn">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="st-conn">Connections: unreachable, slow, healthy</h2>
        <p class="gw-section__description">The whole tile opens the connection; Test again is its own target above that link.</p>
      </header>
      <ul class="gw-grid gw-grid--3" role="list" aria-label="Connections, problems first">
        <li gwStatusTile #billing="gwStatusTile" name="billing-api" mono env="stage" icon="globe" [link]="['/connections', 'billing-api']" [meta]="[{ text: 'HTTP API · ' }, { text: 'billing.stage.acme.dev', mono: true }]" [state]="{ tone: 'danger', label: 'Unreachable' }" [checking]="testing()">
          <button class="gw-btn gw-btn--sm" type="button" [attr.aria-busy]="testing() ? 'true' : null" [attr.aria-disabled]="testing() ? 'true' : null" [attr.aria-describedby]="testing() ? billing.stateId : null" (click)="testAgain()">@if (testing()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span> }Test again<span class="gw-sr-only"> billing-api</span></button>
        </li>
        <li gwStatusTile name="cache-redis" mono env="dev" icon="layers" [link]="['/connections', 'cache-redis']" meta="Redis 7" [state]="{ tone: 'warning', label: 'Slow · 1.8 s' }"></li>
        <li gwStatusTile name="prod-replica" mono env="prod" icon="database" [link]="['/connections', 'prod-replica']" meta="Read replica · replica.prod.internal" [state]="{ tone: 'success', label: 'Healthy' }"></li>
      </ul>
    </section>

    <section class="gw-section" aria-labelledby="st-apps">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="st-apps">AI apps: connected, found, not installed</h2>
        <p class="gw-section__description">The action fits the state: Connect for an app found on this computer, How to install for one that is not. When the action is the tile's only destination, the tile has no link of its own.</p>
      </header>
      <ul class="gw-grid gw-grid--3" role="list" aria-label="AI apps">
        <li gwStatusTile name="Cursor" client="cursor" meta="Found on this computer" [state]="{ tone: 'info', label: 'Not connected' }">
          <a class="gw-btn gw-btn--sm" href="/clients#cl-cu-t">Connect<span class="gw-sr-only"> Cursor</span></a>
        </li>
        <li gwStatusTile name="Codex" client="codex" link="/clients" fragment="cl-cx-t" [meta]="[{ text: 'Endpoint ' }, { text: 'dev', mono: true }]" [state]="{ tone: 'success', label: 'Connected' }"></li>
        <li gwStatusTile name="Antigravity" client="antigravity" meta="Not found on this computer" [state]="{ tone: 'neutral', label: 'Not installed' }">
          <a class="gw-btn gw-btn--sm gw-btn--link" href="/clients#cl-ag-t">How to install<span class="gw-sr-only"> Antigravity</span></a>
        </li>
      </ul>
    </section>

    <section class="gw-section" aria-labelledby="st-off">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="st-off">Action unavailable, with the reason</h2>
        <p class="gw-section__description">First run: Connect is a button (not a link) with aria-disabled, stays in the Tab order, says why it waits, and does nothing until a connection exists.</p>
      </header>
      <ul class="gw-grid gw-grid--3" role="list" aria-label="AI apps on this computer">
        <li gwStatusTile name="Claude Desktop" client="claude-desktop" meta="Found on this computer" [state]="{ tone: 'info', label: 'Not connected' }">
          <button class="gw-btn gw-btn--sm" type="button" aria-disabled="true" aria-describedby="st-why">Connect<span class="gw-sr-only"> Claude Desktop</span></button>
        </li>
      </ul>
      <p class="gw-text-caption gw-fg-muted" id="st-why"><svg lucideIcon="lock" class="gw-icon gw-icon--xs"></svg> Add a connection first: your AI app needs something to work with.</p>
    </section>

    <section class="gw-section" aria-labelledby="st-busy">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="st-busy">Busy: Test again is running</h2>
        <p class="gw-section__description">The button keeps its label, gets a spinner and aria-busy; the state turns into gw-status--checking (role=status) until the result arrives.</p>
      </header>
      <ul class="gw-grid gw-grid--3" role="list" aria-label="Connection being checked">
        <li gwStatusTile #busy="gwStatusTile" name="billing-api" mono env="stage" icon="globe" [link]="['/connections', 'billing-api']" [meta]="[{ text: 'HTTP API · ' }, { text: 'billing.stage.acme.dev', mono: true }]" checking>
          <button class="gw-btn gw-btn--sm" type="button" aria-busy="true" aria-disabled="true" [attr.aria-describedby]="busy.stateId"><span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span> Test again<span class="gw-sr-only"> billing-api, busy example</span></button>
        </li>
      </ul>
    </section>

    <section class="gw-section" aria-labelledby="st-narrow">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="st-narrow">Narrow tile</h2>
        <p class="gw-section__description">When the tile's content box is under 280px (about 314px outer), the state and its action move under the text on one row as tall as the button.</p>
      </header>
      <!-- The kit's narrow example: one sidebar-wide column (set through the CSSOM: the app ships no inline style). -->
      <ul class="gw-grid gw-grid--auto" role="list" aria-label="Narrow example" [style.--gw-grid-min]="'var(--gw-layout-dialog-sm)'" [style.max-width]="'var(--gw-layout-sidebar-width)'">
        <li gwStatusTile name="billing-api" mono env="stage" icon="globe" [link]="['/connections', 'billing-api']" meta="HTTP API" [state]="{ tone: 'danger', label: 'Unreachable' }">
          <button class="gw-btn gw-btn--sm" type="button">Test again<span class="gw-sr-only"> billing-api, narrow example</span></button>
        </li>
      </ul>
    </section>
  `,
})
export class StatusTileDemo {
  protected readonly el = kitElement('status-tile');
  /** Test again of the first tile: busy for a moment, as the page is while the core tests. */
  protected readonly testing = signal(false);

  protected testAgain(): void {
    if (this.testing()) return;
    this.testing.set(true);
    setTimeout(() => this.testing.set(false), 1200);
  }
}
