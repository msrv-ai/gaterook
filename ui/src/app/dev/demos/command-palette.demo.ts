import { ChangeDetectionStrategy, Component, inject, viewChild } from '@angular/core';
import { CoreApi, SearchResult } from '../../core/api/core.api';
import { GwCommandPalette } from '../../kit/elements/command-palette/command-palette';
import { GwIcon } from '../../kit/icons';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { GwLiveNote, liveRead } from '../live';

/** Dev only: design/elements/command-palette.html with the dev core's `GET /api/search` (the whole demo world). */
@Component({
  selector: 'gw-command-palette-demo',
  imports: [GwDemoHeader, GwCommandPalette, GwIcon, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="s-open">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-open">Open with ⌘K</h2>
        <p class="gw-section__description">The real modal: press ⌘K or Ctrl+K, or use the button. Type to filter every group at once; Up and Down (wrapping), Home and End move the active option (a wash, an inset ring and the return-key icon), Enter opens it or runs its action, Esc closes and returns focus here. Focus never leaves the search field. Go to lists every page, with its sidebar group as the hint.</p>
      </header>
      <div>
        <button class="gw-btn" type="button" aria-haspopup="dialog" aria-keyshortcuts="Meta+K Control+K" (click)="open($event)"><svg lucideIcon="search" class="gw-icon"></svg> Search <kbd class="gw-btn__kbd" aria-hidden="true">⌘K</kbd></button>
      </div>
      <gw-command-palette [result]="search.value() ?? null" [pending]="3" />
    </section>

    <section class="gw-section" aria-labelledby="s-suggestions">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-suggestions">Empty query: everything, grouped</h2>
        <p class="gw-section__description">Go to, Actions, Connections, Endpoints (a few rows of each here; the modal above has them all). The first option is active: the return-key icon marks what Enter opens. Pages show their sidebar group, literals are mono, a connection carries its environment.</p>
      </header>
      @if (search.value(); as r) {
        <gw-command-palette preview previewLabel="Command palette preview, empty query" idPrefix="cmdk1" [result]="few(r)" [pending]="3" />
      } @else {
        <p [gwLiveNote]="search"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="s-filtered">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-filtered">Filtered: “dev”</h2>
        <p class="gw-section__description">Groups without a match disappear; the status line says “4 results”. Down was pressed once, so the active option moved from the first match to <code class="gw-code">dev-db</code>.</p>
      </header>
      @if (search.value(); as r) {
        <gw-command-palette preview previewLabel="Command palette preview, filtered by dev" idPrefix="cmdk2" [result]="r" initialQuery="dev" initialActive="dev-db" />
      } @else {
        <p [gwLiveNote]="search"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="s-empty">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-empty">No results</h2>
        <p class="gw-section__description">The list closes (<code class="gw-code">aria-expanded="false"</code>), the empty state repeats the query and offers one way on.</p>
      </header>
      @if (search.value(); as r) {
        <gw-command-palette preview previewLabel="Command palette preview, no results" idPrefix="cmdk3" [result]="r" initialQuery="kafka" />
      } @else {
        <p [gwLiveNote]="search"></p>
      }
    </section>
  `,
})
export class CommandPaletteDemo {
  private readonly core = inject(CoreApi);
  protected readonly el = kitElement('command-palette');
  protected readonly search = liveRead(() => this.core.search(''));
  /** The preview's "a few rows of each". */
  protected few(r: SearchResult): SearchResult {
    return {
      ...r,
      goTo: r.goTo.filter((g) => ['overview', 'approvals', 'activity'].includes(g.id)),
      connections: r.connections.filter((c) => c.name === 'dev-db' || c.name === 'prod-replica'),
      endpoints: r.endpoints.slice(0, 2),
    };
  }
  private readonly palette = viewChild.required(GwCommandPalette);

  protected open(e: Event): void {
    this.palette().open(e.currentTarget as HTMLElement);
  }
}
