import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ConnectorGroup, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { GwConnectorCard, GwConnectorPicker } from '../../kit/elements/connector-card/connector-card';
import { GwIcon } from '../../kit/icons';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_CONNECTOR_CARDS } from '../fixtures';
import { GwLiveNote, liveRead } from '../live';

/**
 * Dev only: design/elements/connector-card.html. The pickers are the dev core's types (`GET /api/connector-types`,
 * as step 1 of Add connection gets them); the catalog grid is a fixture until the catalog, plugins and MCP servers
 * (K4, K6) are merged: the core lists only its built-in connectors and templates so far.
 */
@Component({
  selector: 'gw-connector-card-demo',
  imports: [GwDemoHeader, GwConnectorCard, GwConnectorPicker, GwIcon, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="s-catalog">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-catalog">Catalog cards</h2>
        <p class="gw-section__description">As on Connectors: every action state in one grid. Actions are small secondary buttons, so no card outshouts the others; the footer lines up across a row. Install on the unsigned plugin asks first, in a <code class="gw-code">gw-dialog--danger</code> (unverified code runs on this computer as your user), with an acknowledgement before the install button works and focus on Cancel.</p>
      </header>
      <ul class="gw-grid gw-grid--3" aria-label="Connectors">
        @for (c of cards; track c.name) {
          <li><article gwConnectorCard [card]="c"></article></li>
        }
      </ul>
    </section>

    <section class="gw-section" aria-labelledby="s-badges">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-badges">Kind and trust</h2>
        <p class="gw-section__description">The same words, tones and icons on every card. Built-in connectors carry one badge: kind and trust are the same fact.</p>
      </header>
      <div class="gw-card">
        <div class="gw-card__body">
          <dl class="gw-kv">
            <div class="gw-kv__row"><dt class="gw-kv__key"><span class="gw-badge gw-badge--neutral"><svg lucideIcon="package" class="gw-icon gw-badge__icon"></svg>Built-in</span></dt><dd class="gw-kv__value">Ships with the core and updates with it. No separate trust badge.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key"><span class="gw-badge gw-badge--outline"><svg lucideIcon="file-code" class="gw-icon gw-badge__icon"></svg>Template</span></dt><dd class="gw-kv__value">Declarative tools (SQL or a command with typed parameters). No code runs.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key"><span class="gw-badge gw-badge--outline"><svg lucideIcon="puzzle" class="gw-icon gw-badge__icon"></svg>Plugin</span></dt><dd class="gw-kv__value">A Java plugin, run by the gateway in its own process. Runs code: check the signature.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key"><span class="gw-badge gw-badge--outline"><svg lucideIcon="plug" class="gw-icon gw-badge__icon"></svg>MCP server</span></dt><dd class="gw-kv__value">An external MCP server behind the gateway: its tools get modes, approvals and audit.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key"><span class="gw-badge gw-badge--success"><svg lucideIcon="badge-check" class="gw-icon gw-badge__icon"></svg>Signed</span></dt><dd class="gw-kv__value">The catalog key verified the package. Install without extra steps.</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key"><span class="gw-badge gw-badge--warning"><svg lucideIcon="shield-alert" class="gw-icon gw-badge__icon"></svg>Unsigned</span></dt><dd class="gw-kv__value">No valid signature. Install asks in a danger confirmation that names the risk and needs an explicit acknowledgement. Tools of plugins and MCP servers say “Declared by &lt;server&gt;, not verified” next to their annotations.</dd></div>
          </dl>
        </div>
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-picker">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-picker">Picker: Add connection, step 1</h2>
        <p class="gw-section__description">Radio cards grouped by category, as in step 1 of Add connection. One radio group across all categories, so arrow keys move through every type. Built-in types say so with their tool count (Loki, GCP Cloud Logging, AWS CloudWatch, OpenSearch and Elasticsearch are separate built-in connectors with their own tools); installed plugins and templates carry their kind and trust badges. A catalog plugin that is not installed yet (ClickHouse) stays visible and focusable but unavailable: <code class="gw-code">aria-disabled</code>, its reason in the description.</p>
      </header>
      @if (catalog.value(); as c) {
        <fieldset gwConnectorPicker [groups]="c.groups" [(value)]="picked"></fieldset>
      } @else {
        <p [gwLiveNote]="catalog"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="s-picker-error">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-picker-error">Picker: nothing chosen</h2>
        <p class="gw-section__description">After Continue with no type picked: every card gets the danger edge and the group says what to do.</p>
      </header>
      @if (catalog.value(); as c) {
        <fieldset gwConnectorPicker required [groups]="dbOnly(c.groups)" error="Choose a connector type to continue."></fieldset>
      } @else {
        <p [gwLiveNote]="catalog"></p>
      }
    </section>
  `,
})
export class ConnectorCardDemo {
  private readonly types = inject(ConnectorTypesApi);
  protected readonly el = kitElement('connector-card');
  protected readonly cards = DEMO_CONNECTOR_CARDS;
  protected readonly catalog = liveRead(() => this.types.catalog());
  protected readonly picked = signal<string | null>('postgres');

  /** "Nothing chosen" shows three of the databases, as the kit does. */
  protected dbOnly(groups: readonly ConnectorGroup[]): ConnectorGroup[] {
    const db = groups.find((g) => g.name === 'Databases');
    return db ? [{ ...db, cards: db.cards.filter((c) => ['postgres', 'mongodb', 'redis'].includes(c.type)) }] : [];
  }
}
