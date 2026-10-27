import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, booleanAttribute, inject, input, model, output, signal } from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { LucideDynamicIcon } from '@lucide/angular';

/**
 * `<div gwTagsInput>`: an array of strings as tags over an add input (connection-form "Schema allowlist": the kit's
 * `gw-tag-list` of `gw-tag --mono` with a remove button each, and a `gw-input-group` whose addon adds). Enter adds
 * the typed value without submitting the form; after a removal focus goes to the next tag's remove button, else to
 * the input (gw-tag A11y).
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
        (input)="draft.set(entry.value)"
        (keydown.enter)="add($event)"
        (blur)="touch.emit()"
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
  readonly touch = output<void>();
  protected readonly draft = signal('');

  protected add(event: Event): void {
    event.preventDefault();
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
