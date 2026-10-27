import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { GwIcon } from '../kit/icons';
import { GwThemeControl } from '../kit/theme';

/**
 * The header of a kit element page: the element's name (the page's h1), what it is for, and the Theme switcher
 * (System · Light · Dark) on the right.
 */
@Component({
  selector: 'header[gwDemoHeader]',
  imports: [GwIcon, GwThemeControl],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page-header' },
  template: `
    <div class="gw-page-header__row">
      <div class="gw-page-header__heading">
        <h1 class="gw-page-header__title">{{ title() }}</h1>
        <p class="gw-page-header__description">{{ description() }}</p>
      </div>
      <div class="gw-page-header__actions">
        <fieldset class="gw-segmented gw-segmented--sm">
          <legend class="gw-sr-only">Theme</legend>
          <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" name="gw-theme" value="system"><span class="gw-segmented__label"><svg lucideIcon="monitor" class="gw-icon gw-icon--sm"></svg>System</span></label>
          <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" name="gw-theme" value="light"><span class="gw-segmented__label"><svg lucideIcon="sun" class="gw-icon gw-icon--sm"></svg>Light</span></label>
          <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" name="gw-theme" value="dark"><span class="gw-segmented__label"><svg lucideIcon="moon" class="gw-icon gw-icon--sm"></svg>Dark</span></label>
        </fieldset>
      </div>
    </div>
  `,
})
export class GwDemoHeader {
  readonly title = input.required<string>();
  readonly description = input.required<string>();
}
