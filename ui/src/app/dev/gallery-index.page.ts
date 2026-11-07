import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwIcon } from '../kit/icons';
import { GwDemoHeader } from './demo-header';
import { KIT_ELEMENTS } from './elements';

/** Dev only: the list of reusable elements, each rendered with demo data. */
@Component({
  selector: 'gw-gallery-index',
  imports: [GwDemoHeader, RouterLink, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader title="Elements" description="Reusable application components rendered with shared demo data. Open an element to inspect its states and interactions."></header>
    <section class="gw-section" aria-labelledby="g-list">
      <div class="gw-section__header">
        <h2 class="gw-section__title" id="g-list">Elements</h2>
      </div>
      <ul class="gw-grid gw-grid--3" role="list">
        @for (e of elements; track e.name) {
          <li class="gw-card gw-card--interactive">
            <div class="gw-card__header">
              <div class="gw-card__heading">
                <h3 class="gw-card__title"><span class="gw-icon-tile gw-icon-tile--neutral gw-icon-tile--sm"><svg lucideIcon="blocks" class="gw-icon gw-icon--sm"></svg></span><a class="gw-card__link" [routerLink]="['/dev/elements', e.name]">{{ e.name }}</a></h3>
              </div>
            </div>
          </li>
        }
      </ul>
    </section>
  `,
})
export class GalleryIndexPage {
  protected readonly elements = KIT_ELEMENTS;
}
