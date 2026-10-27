import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { OverviewApi } from '../../core/api/overview.api';
import { GwGettingStarted } from '../../kit/elements/getting-started/getting-started';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_GETTING_STARTED } from '../fixtures';
import { GwLiveNote, liveRead } from '../live';

/**
 * Dev only: design/elements/getting-started.html. "In progress" is the dev core's checklist (`GET /api/overview`
 * gettingStarted); a fresh install and a finished one are fixtures. The detected clients come with K1 (fixture).
 */
@Component({
  selector: 'gw-getting-started-demo',
  imports: [GwDemoHeader, GwGettingStarted, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="s-fresh">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-fresh">Fresh install</h2>
        <p class="gw-section__description">Nothing done yet. Step 1 is current and carries the only primary button; the others are links, so they can be done in any order.</p>
      </header>
      <section gwGettingStarted [level]="3" [checklist]="gs.fresh" [detected]="detected" playgroundTool="sql_query"></section>
    </section>

    <section class="gw-section" aria-labelledby="s-progress">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-progress">In progress</h2>
        <p class="gw-section__description">Three steps done, each folded to one line with what was done. Connect a client is next.</p>
      </header>
      @if (overview.value(); as o) {
        <section gwGettingStarted [level]="3" [checklist]="o.gettingStarted" [detected]="detected" playgroundTool="sql_query"></section>
      } @else {
        <p [gwLiveNote]="overview"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="s-complete">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-complete">Complete</h2>
        <p class="gw-section__description">All six done: a one-card summary with what now works and where to look next. The steps stay one click away.</p>
      </header>
      <section gwGettingStarted [level]="3" [checklist]="gs.complete" [clients]="['Claude Desktop', 'Codex']" endpoint="dev"></section>
    </section>
  `,
})
export class GettingStartedDemo {
  private readonly api = inject(OverviewApi);
  protected readonly el = kitElement('getting-started');
  protected readonly overview = liveRead(() => this.api.get());
  protected readonly gs = DEMO_GETTING_STARTED;
  protected readonly detected = ['Claude Desktop', 'Codex', 'Cursor'];
}
