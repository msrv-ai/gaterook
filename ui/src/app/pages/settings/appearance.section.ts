import { ChangeDetectionStrategy, Component } from '@angular/core';
import { GwIcon } from '../../kit/icons';
import { GwThemeControl } from '../../kit/theme';

/**
 * Settings › Appearance (design/pages/settings.html `#settings-appearance`): theme and contrast for this computer,
 * applied at once by the kit's theme boot (the same `gw-theme` / `gw-contrast` radios as the titlebar popover, kept in
 * sync by `GwThemeControl`), and the one density with a note about zoom. Nothing to save: the choice lives where it
 * must apply before the first paint (theme-boot's storage, `nativeTheme` in Electron), so the core's copy of the
 * section is not written from here (ARCHITECTURE.md §8.6, "Settings (U14)").
 */
@Component({
  selector: 'section[gwAppearanceSettings]',
  imports: [GwIcon, GwThemeControl],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-appearance', 'aria-labelledby': 'st-appearance-t' },
  template: `
    <header class="gw-section__header">
      <h2 class="gw-section__title" id="st-appearance-t">Appearance</h2>
      <p class="gw-section__description">How MCP Gateway looks on this computer. Changes apply at once; the titlebar has the same controls.</p>
    </header>
    <hr class="gw-divider">
    <!-- A <form> only to scope the gw-theme / gw-contrast radio groups apart from the titlebar popover's: nothing to
         submit, unnamed so it adds no landmark. GwThemeControl keeps both in sync. -->
    <form class="gw-form" method="dialog" (submit)="$event.preventDefault()">
      <section class="gw-form__section" aria-labelledby="st-theme-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-theme-t">Theme</h3>
          <p class="gw-form__description" id="st-theme-d">System follows your operating system and switches with it.</p>
        </header>
        <div class="gw-form__rows">
          <div class="gw-segmented" role="radiogroup" aria-labelledby="st-theme-t" aria-describedby="st-theme-d">
            <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" name="gw-theme" value="system"><span class="gw-segmented__label"><svg lucideIcon="monitor" class="gw-icon gw-icon--sm"></svg>System</span></label>
            <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" name="gw-theme" value="light"><span class="gw-segmented__label"><svg lucideIcon="sun" class="gw-icon gw-icon--sm"></svg>Light</span></label>
            <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" name="gw-theme" value="dark"><span class="gw-segmented__label"><svg lucideIcon="moon" class="gw-icon gw-icon--sm"></svg>Dark</span></label>
          </div>
        </div>
      </section>

      <section class="gw-form__section" aria-labelledby="st-contrast-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-contrast-t">Contrast</h3>
          <p class="gw-form__description">High contrast strengthens text, borders and focus rings. Backgrounds and the brand colour stay the same.</p>
        </header>
        <div class="gw-form__rows">
          <fieldset class="gw-field" aria-labelledby="st-contrast-t">
            <div class="gw-radio">
              <input class="gw-radio__input" type="radio" name="gw-contrast" id="st-contrast-system" value="system" aria-describedby="st-contrast-system-hint">
              <span class="gw-radio__control" aria-hidden="true"></span>
              <label class="gw-radio__label" for="st-contrast-system">System</label>
              <p class="gw-radio__hint" id="st-contrast-system-hint">Follows Increase contrast on macOS and contrast themes on Windows.</p>
            </div>
            <div class="gw-radio">
              <input class="gw-radio__input" type="radio" name="gw-contrast" id="st-contrast-normal" value="normal">
              <span class="gw-radio__control" aria-hidden="true"></span>
              <label class="gw-radio__label" for="st-contrast-normal">Normal</label>
            </div>
            <div class="gw-radio">
              <input class="gw-radio__input" type="radio" name="gw-contrast" id="st-contrast-more" value="more" aria-describedby="st-contrast-more-hint">
              <span class="gw-radio__control" aria-hidden="true"></span>
              <label class="gw-radio__label" for="st-contrast-more">High</label>
              <p class="gw-radio__hint" id="st-contrast-more-hint">Darker secondary text, stronger borders, a heavier focus ring.</p>
            </div>
          </fieldset>
        </div>
      </section>

      <section class="gw-form__section" aria-labelledby="st-density-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-density-t">Density</h3>
          <p class="gw-form__description">One compact density, tuned for long sessions: 32px controls and 40px table rows.</p>
        </header>
        <div class="gw-form__rows">
          <div class="gw-alert gw-alert--info gw-alert--compact">
            <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
            <div class="gw-alert__content"><p class="gw-alert__body">To make everything larger, zoom the window: View, then Zoom in, or <kbd class="gw-kbd">⌘</kbd><kbd class="gw-kbd">+</kbd> (<kbd class="gw-kbd">Ctrl</kbd><kbd class="gw-kbd">+</kbd> on Windows and Linux). The zoom level is remembered.</p></div>
          </div>
        </div>
      </section>
    </form>
  `,
})
export class GwAppearanceSettings {}
