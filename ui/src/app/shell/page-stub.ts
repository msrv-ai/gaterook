import { ChangeDetectionStrategy, Component, Directive, contentChild, input } from '@angular/core';
import { GwIcon } from '../kit/icons';

/** Marks what a stub puts in its page header's actions (`gw-page-header__actions`), e.g. Welcome's Skip setup. */
@Directive({ selector: '[gwPageStubAction]' })
export class GwPageStubAction {}

/**
 * The content of a page whose step (U1–U16) hasn't built it yet: the kit's page header with the page's real title
 * and description, and an empty state that says so. No data, never invented numbers. The page component around it
 * is the `.gw-page` (with its width modifier); this element is `display: contents` (styles.scss) so the header and
 * the empty state are the page's own flex children, as in the kit.
 */
@Component({
  selector: 'gw-page-stub',
  imports: [GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="gw-page-header">
      <div class="gw-page-header__row">
        <div class="gw-page-header__heading">
          <h1 class="gw-page-header__title">{{ heading() }}</h1>
          @if (description()) {
            <p class="gw-page-header__description">{{ description() }}</p>
          }
        </div>
        @if (action()) {
          <div class="gw-page-header__actions"><ng-content select="[gwPageStubAction]" /></div>
        }
      </div>
    </header>
    <div class="gw-empty">
      <span class="gw-empty__icon"><svg [lucideIcon]="icon()" class="gw-icon"></svg></span>
      <h2 class="gw-empty__title">{{ heading() }} isn't built yet</h2>
      <p class="gw-empty__text">This screen arrives with a later step of this build. Everything else keeps working.</p>
    </div>
  `,
})
export class GwPageStub {
  readonly heading = input.required<string>();
  readonly description = input<string | null>(null);
  readonly icon = input('layout-dashboard');
  protected readonly action = contentChild(GwPageStubAction);
}
