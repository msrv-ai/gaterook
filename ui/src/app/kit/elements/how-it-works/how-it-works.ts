import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  contentChildren,
  forwardRef,
  inject,
  input,
  linkedSignal,
  numberAttribute,
} from '@angular/core';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';

let nextId = 0;

/**
 * `<section gwHowItWorks summary="…" stepsLabel="What happens to a secret" persist="secrets">` (elements/
 * how-it-works.html): three or four numbered steps that explain a mechanism before the user configures it, in a muted
 * card that folds away once the user knows it (the choice is remembered per `persist` key). Steps are
 * `<li gwHowItWorksStep icon="key-round" heading="Store or reference">text, <code class="gw-code">…</code></li>`;
 * an optional `<p class="gw-how-it-works__more" gwHowItWorksMore>` link goes after them. From lg the steps run across
 * with a chevron between them; `vertical` keeps the list in narrow columns (Settings).
 */
@Component({
  selector: 'section[gwHowItWorks]',
  imports: [GwIcon, GwToggle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-card gw-card--muted gw-how-it-works',
    '[class.gw-how-it-works--vertical]': 'vertical()',
    '[attr.aria-labelledby]': 'uid + "-title"',
  },
  template: `
    <div class="gw-card__body gw-how-it-works__body">
      <div class="gw-how-it-works__header">
        <div class="gw-card__heading">
          @switch (level()) {
            @case (2) { <h2 class="gw-card__title" [id]="uid + '-title'">{{ heading() }}</h2> }
            @case (4) { <h4 class="gw-card__title" [id]="uid + '-title'">{{ heading() }}</h4> }
            @default { <h3 class="gw-card__title" [id]="uid + '-title'">{{ heading() }}</h3> }
          }
          <p class="gw-card__description">{{ summary() }}</p>
        </div>
        <button class="gw-btn gw-btn--ghost gw-btn--sm gw-btn--icon gw-how-it-works__toggle" type="button" gwToggle [(expanded)]="open" [gwTogglePersist]="persist()" [attr.aria-controls]="uid + '-panel'" [attr.aria-labelledby]="uid + '-title'">
          <svg lucideIcon="chevron-up" class="gw-icon gw-how-it-works__toggle-icon gw-how-it-works__toggle-icon--collapse"></svg>
          <svg lucideIcon="chevron-down" class="gw-icon gw-how-it-works__toggle-icon gw-how-it-works__toggle-icon--expand"></svg>
        </button>
      </div>
      <div class="gw-how-it-works__panel" [id]="uid + '-panel'" [hidden]="!open()">
        <ol class="gw-how-it-works__steps" [attr.aria-label]="stepsLabel()"><ng-content select="[gwHowItWorksStep]" /></ol>
        <ng-content select="[gwHowItWorksMore]" />
      </div>
    </div>
  `,
})
export class GwHowItWorks {
  readonly heading = input('How it works');
  /** The one-line summary that stays when the card is folded. */
  readonly summary = input.required<string>();
  /** The list's accessible name: "What happens to a secret". */
  readonly stepsLabel = input.required<string>();
  readonly vertical = input(false, { transform: booleanAttribute });
  /** localStorage key (`gw-toggle-<key>`) that remembers folded / open. */
  readonly persist = input<string | null>(null);
  /** Open at first (before a remembered choice). */
  readonly expanded = input(true, { transform: booleanAttribute });
  /** The title's heading level (3 inside a page section, 2 at the top of a page). */
  readonly level = input(3, { transform: numberAttribute });

  protected readonly uid = `hiw-${++nextId}`;
  protected readonly open = linkedSignal(() => this.expanded());
  readonly steps = contentChildren(forwardRef(() => GwHowItWorksStep));
}

/** One step: icon tile, the chevron to the next step (not on the last), the number, the title and the text. */
@Component({
  selector: 'li[gwHowItWorksStep]',
  imports: [GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-how-it-works__step' },
  template: `
    <span class="gw-icon-tile gw-icon-tile--brand gw-how-it-works__icon"><svg [lucideIcon]="icon()" class="gw-icon"></svg></span>
    @if (!last()) {
      <span class="gw-how-it-works__arrow gw-fg-muted" aria-hidden="true"><svg lucideIcon="chevron-right" class="gw-icon gw-icon--sm"></svg></span>
    }
    <p class="gw-how-it-works__title gw-text-h3"><span class="gw-counter" aria-hidden="true">{{ number() }}</span>{{ heading() }}</p>
    <p class="gw-how-it-works__text gw-text-small gw-fg-secondary"><ng-content /></p>
  `,
})
export class GwHowItWorksStep {
  private readonly parent = inject(GwHowItWorks);
  readonly icon = input.required<string>();
  readonly heading = input.required<string>();

  private readonly index = computed(() => this.parent.steps().indexOf(this));
  protected readonly number = computed(() => this.index() + 1);
  protected readonly last = computed(() => this.index() === this.parent.steps().length - 1);
}
