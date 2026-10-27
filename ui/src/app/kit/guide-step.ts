import { ChangeDetectionStrategy, Component, computed, input, numberAttribute } from '@angular/core';
import { GwIcon } from './icons';

/** A guide step's state (`gw-stepper__step[data-state]`); a guide has no error step. */
export type GuideStepState = 'complete' | 'current' | 'upcoming';

/**
 * `<li gwGuideStep [n]="1" state="current" heading="Add your first connection">` — one step of a
 * `<ol class="gw-stepper gw-stepper--guide">` (COMPONENTS.md › gw-stepper `--guide`; design/pages/overview.html): a
 * short first-run guide on a page, always vertical, two or three steps that lead to the pages where the work is done
 * — never a wizard.
 *
 * The step draws its marker (a number, a check when complete; decorative) and its label as a heading one level below
 * the card's title (`<h3 class="gw-stepper__label">` under an h2); a complete step says so to screen readers
 * (", completed"), the current one carries `aria-current="step"`. What follows is the page's, projected in order as
 * the step's own children: `<p class="gw-stepper__description">` (reading text) and
 * `<div class="gw-stepper__content">` with the step's actions and cards. One `--primary` only in the current step;
 * an upcoming step shows its actions ahead, `aria-disabled` with a visible reason.
 */
@Component({
  selector: 'li[gwGuideStep]',
  imports: [GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-stepper__step',
    '[attr.data-state]': 'state()',
    '[attr.aria-current]': 'state() === "current" ? "step" : null',
  },
  template: `
    <span class="gw-stepper__marker" aria-hidden="true">@if (complete()) {<svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg>} @else {{{ n() }}}</span>
    @if (level() === 4) {
      <h4 class="gw-stepper__label">{{ heading() }}@if (complete()) {<span class="gw-sr-only">, completed</span>}</h4>
    } @else {
      <h3 class="gw-stepper__label">{{ heading() }}@if (complete()) {<span class="gw-sr-only">, completed</span>}</h3>
    }
    <ng-content />
  `,
})
export class GwGuideStep {
  /** The step's number in the guide (the marker shows it until the step is complete). */
  readonly n = input.required({ transform: numberAttribute });
  readonly state = input.required<GuideStepState>();
  readonly heading = input.required<string>();
  /** The label's heading level: 3 under a card's h2. */
  readonly level = input(3, { transform: numberAttribute });

  protected readonly complete = computed(() => this.state() === 'complete');
}
