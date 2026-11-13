import { ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, ElementRef, Injector, afterNextRender, booleanAttribute, inject, input, model, output, signal } from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { LucideDynamicIcon } from '@lucide/angular';

/**
 * `<div gwTagsInput>`: an array of strings as tags over an add input (connection-form "Schema allowlist": the kit's
 * `gw-tag-list` of `gw-tag --mono` with a remove button each, and a `gw-input-group` whose addon adds). Enter adds
 * the typed value without submitting the form; after a removal focus goes to the next tag's remove button, else to
 * the input (gw-tag A11y).
 *
 * A typed value is never lost: it is added when the input loses focus and when the form around it is submitted
 * (Save, also from the keyboard while the input still has focus), before the page reads the form's value.
 */
@Component({
  selector: 'div[gwTagsInput]',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-connection-form__tags' },
  template: `
    @if (value().length) {
      <ul class="gw-tag-list" [attr.aria-label]="listLabel()">
        @for (tag of value(); track tag; let i = $index) {
          <li class="gw-tag" [class.gw-tag--mono]="mono()">
            <span class="gw-tag__label" [attr.title]="tag">{{ tag }}</span>
            <button class="gw-tag__remove" type="button" [attr.aria-label]="'Remove ' + noun() + ' ' + tag" (click)="remove(i)">
              <svg lucideIcon="x" class="gw-icon"></svg>
            </button>
          </li>
        }
      </ul>
    }
    <div class="gw-input-group">
      <input
        #entry
        class="gw-input"
        [class.gw-input--mono]="mono()"
        [id]="inputId()"
        [attr.placeholder]="placeholder()"
        autocomplete="off"
        spellcheck="false"
        [attr.aria-describedby]="describedBy()"
        [attr.aria-invalid]="invalid() ? 'true' : null"
        [value]="draft()"
        [attr.list]="list()"
        (input)="typed(entry.value, $event)"
        (keydown.enter)="add($event)"
        (blur)="commit(); touch.emit()"
      />
      <button class="gw-input-group__addon" type="button" [attr.aria-label]="'Add ' + noun()" (click)="add($event)">
        <svg lucideIcon="plus" class="gw-icon"></svg>
      </button>
    </div>
  `,
})
export class GwTagsInput implements FormValueControl<string[]> {
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly injector = inject(Injector);
  readonly value = model<string[]>([]);
  readonly inputId = input.required<string>();
  /** "schema": the buttons say "Remove schema public", "Add schema". */
  readonly noun = input('value');
  readonly listLabel = input<string | null>(null);
  readonly placeholder = input<string | null>(null);
  readonly describedBy = input<string | null>(null);
  readonly invalid = input(false, { transform: booleanAttribute });
  readonly mono = input(true, { transform: booleanAttribute });
  /** The id of a `<datalist>` of values to pick (what the setup helpers found): a pick is added at once. */
  readonly list = input<string | null>(null);
  readonly touch = output<void>();
  protected readonly draft = signal('');

  constructor() {
    // Capture: this runs before the form's own submit handler reads the value.
    const doc = inject(DOCUMENT);
    const beforeSubmit = (e: Event) => {
      if (e.target instanceof Node && e.target.contains(this.host)) this.commit();
    };
    doc.addEventListener('submit', beforeSubmit, true);
    inject(DestroyRef).onDestroy(() => doc.removeEventListener('submit', beforeSubmit, true));
  }

  /** A value picked from the input's list is added at once; typed text waits for Enter, Add or leaving the field. */
  protected typed(text: string, event: Event): void {
    this.draft.set(text);
    if (this.list() && event instanceof InputEvent && event.inputType === 'insertReplacementText') {
      this.commit();
      // The binding still holds '' from before the pick, so it wouldn't clear the input by itself.
      if (event.target instanceof HTMLInputElement) event.target.value = '';
    }
  }

  protected add(event: Event): void {
    event.preventDefault();
    this.commit();
  }

  /** Adds the typed value (once: a value already in the list isn't added again) and empties the input. */
  protected commit(): void {
    const tag = this.draft().trim();
    if (!tag) return;
    if (!this.value().includes(tag)) this.value.set([...this.value(), tag]);
    this.draft.set('');
  }

  protected remove(index: number): void {
    const next = this.value().filter((_, i) => i !== index);
    this.value.set(next);
    // The list re-renders first; then focus the tag that took this place, or the add input.
    afterNextRender(
      () => {
        const buttons = this.host.querySelectorAll<HTMLButtonElement>('.gw-tag__remove');
        (buttons[Math.min(index, buttons.length - 1)] ?? this.host.querySelector<HTMLInputElement>('input'))?.focus();
      },
      { injector: this.injector },
    );
  }
}
