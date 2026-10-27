import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  booleanAttribute,
  computed,
  inject,
  input,
  model,
  output,
  signal,
} from '@angular/core';
import { FieldTree, FormField, FormValueControl } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Env, JsonValue } from '../../core/api/types';
import { GwIcon } from '../../kit/icons';
import { Hint, TagSpec } from './policy-catalog';
import { KeySchema, same } from './policy-model';
import { OptionView } from './policies-view';

/**
 * The fields of a policy form, each one kit field 1:1 (design/pages/policies.html, COMPONENTS.md "C · Forms"): the
 * host element is the kit's own wrapper (`div.gw-field`, `div.gw-checkbox`, `fieldset.gw-field`), so a field sits in
 * `gw-form__rows` and `gw-form__row` exactly as in the kit. Values are Signal Forms fields of the panel's form.
 */

/** What every field shows besides its value. */
export interface FieldView {
  readonly id: string;
  readonly label: string;
  readonly labelCode?: string;
  readonly optional?: boolean;
  readonly hint: Hint;
  /** The message at the field: a rule it breaks after Save, or what the core refused. */
  readonly error: string | null;
}

/** A hint's runs: text, literals in mono, links inside the app. */
@Component({
  selector: 'span[gwHintParts]',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@for (p of parts(); track $index) {@if (p.link; as l) {<a class="gw-link" [routerLink]="l.route" [queryParams]="l.query ?? null" [fragment]="l.fragment">{{ p.text }}</a>} @else if (p.chip) {<code class="gw-code" [class.gw-code--secret]="p.chip === 'secret'">{{ p.text }}</code>} @else if (p.code) {<span class="gw-mono">{{ p.text }}</span>} @else {{{ p.text }}}}`,
})
export class GwHintParts {
  readonly parts = input.required<Hint>({ alias: 'gwHintParts' });
}

/** `div.gw-field` with a number typed as text (`inputmode="numeric"`, COMPONENTS.md "Ports and numbers") and its unit. */
@Component({
  selector: 'div[gwPolicyNumber]',
  imports: [FormField, GwIcon, GwHintParts],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-field' },
  template: `
    @let v = view();
    <label class="gw-field__label" [for]="v.id">{{ v.label }}@if (v.optional) { <span class="gw-field__optional">Optional</span>}</label>
    @if (unit()) {
      <div class="gw-input-group">
        <input class="gw-input" [id]="v.id" type="text" inputmode="numeric" autocomplete="off" [attr.placeholder]="placeholder() || null" [formField]="field()" [attr.aria-invalid]="v.error ? 'true' : null" [attr.aria-describedby]="describedBy()" />
        <span class="gw-input-group__affix">{{ unit() }}</span>
      </div>
    } @else {
      <input class="gw-input" [id]="v.id" type="text" inputmode="numeric" autocomplete="off" [attr.placeholder]="placeholder() || null" [formField]="field()" [attr.aria-invalid]="v.error ? 'true' : null" [attr.aria-describedby]="describedBy()" />
    }
    @if (v.error) {
      <p class="gw-field__error" [id]="v.id + '-error'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ v.error }}</p>
    }
    @if (v.hint.length) {
      <p class="gw-field__hint" [id]="v.id + '-hint'"><span [gwHintParts]="v.hint"></span></p>
    }
  `,
})
export class GwPolicyNumber {
  readonly view = input.required<FieldView>();
  readonly field = input.required<FieldTree<string>>();
  readonly unit = input<string | null>(null);
  readonly placeholder = input<string>('');
  protected readonly describedBy = computed(() => {
    const v = this.view();
    return [v.error ? `${v.id}-error` : '', v.hint.length ? `${v.id}-hint` : ''].filter(Boolean).join(' ') || null;
  });
}

/**
 * `div.gw-checkbox` with its hint outside the name. `confirmFirst`: turning it on asks first (prod `ssh_exec`, the
 * kit's `#pol-dlg-prod-exec`): the click is cancelled and `confirm` asks the page, which sets the value itself.
 */
@Component({
  selector: 'div[gwPolicyCheckbox]',
  imports: [FormField, GwHintParts],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-checkbox' },
  template: `
    @let v = view();
    <input class="gw-checkbox__input" type="checkbox" [id]="v.id" [formField]="field()" [attr.aria-describedby]="v.hint.length ? v.id + '-hint' : null" (click)="onClick($event)" />
    <span class="gw-checkbox__control" aria-hidden="true"></span>
    <label class="gw-checkbox__label" [for]="v.id">{{ v.label }}@if (v.labelCode) { <span class="gw-mono">{{ v.labelCode }}</span>}</label>
    @if (v.hint.length) {
      <p class="gw-checkbox__hint" [id]="v.id + '-hint'"><span [gwHintParts]="v.hint"></span></p>
    }
  `,
})
export class GwPolicyCheckbox {
  readonly view = input.required<FieldView>();
  readonly field = input.required<FieldTree<boolean>>();
  readonly confirmFirst = input(false, { transform: booleanAttribute });
  readonly confirm = output<HTMLInputElement>();

  protected onClick(e: MouseEvent): void {
    const box = e.target as HTMLInputElement;
    // The click has already checked the box: cancelling it unchecks it again and no change reaches the form.
    if (this.confirmFirst() && box.checked) {
      e.preventDefault();
      this.confirm.emit(box);
    }
  }
}

/** `div.gw-field` with a native `gw-select`; `follow` is the empty option ("Same as dev: 5 min"). */
@Component({
  selector: 'div[gwPolicySelect]',
  imports: [FormField, GwHintParts],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-field' },
  template: `
    @let v = view();
    <label class="gw-field__label" [for]="v.id">{{ v.label }}</label>
    <select class="gw-select" [id]="v.id" [formField]="field()" [attr.aria-describedby]="v.hint.length ? v.id + '-hint' : null">
      @if (follow(); as f) {
        <option value="">{{ f }}</option>
      }
      @for (o of options(); track o.value) {
        <option [value]="o.value" [disabled]="o.disabled">{{ o.label }}</option>
      }
    </select>
    @if (v.hint.length) {
      <p class="gw-field__hint" [id]="v.id + '-hint'"><span [gwHintParts]="v.hint"></span></p>
    }
  `,
})
export class GwPolicySelect {
  readonly view = input.required<FieldView>();
  readonly field = input.required<FieldTree<string>>();
  readonly options = input.required<readonly OptionView[]>();
  readonly follow = input<string | null>(null);
}

/**
 * `fieldset.gw-field` of checkboxes over one list (masking's personal data, Announce requests in); the group's hint
 * describes the fieldset. Content between the boxes and the hint is projected ("Telegram is not set up …").
 */
@Component({
  selector: 'fieldset[gwPolicyChecks]',
  imports: [GwHintParts],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-field', '[attr.aria-describedby]': 'view().hint.length ? view().id + "-hint" : null' },
  template: `
    @let v = view();
    <legend class="gw-field__label">{{ v.label }}</legend>
    @for (o of shown(); track o.value) {
      <label class="gw-checkbox">
        <input class="gw-checkbox__input" type="checkbox" [attr.name]="v.id" [value]="o.value" [checked]="has(o.value)" [disabled]="o.disabled && !has(o.value)" (change)="toggle(o.value, $any($event.target).checked)" />
        <span class="gw-checkbox__control" aria-hidden="true"></span>
        <span class="gw-checkbox__label">{{ o.label }}</span>
      </label>
    }
    <ng-content />
    @if (v.hint.length) {
      <p class="gw-field__hint" [id]="v.id + '-hint'"><span [gwHintParts]="v.hint"></span></p>
    }
  `,
})
export class GwPolicyChecks implements FormValueControl<JsonValue[]> {
  readonly value = model<JsonValue[]>([]);
  readonly view = input.required<FieldView>();
  readonly options = input.required<readonly OptionView[]>();
  /** Options left out (a channel that isn't set up and isn't chosen): the page says why in projected content. */
  readonly hide = input<readonly string[]>([]);
  protected readonly shown = computed(() => this.options().filter((o) => !this.hide().includes(o.value) || this.has(o.value)));

  protected has(v: string): boolean {
    return this.value().some((x) => String(x) === v);
  }

  protected toggle(v: string, on: boolean): void {
    const order = this.options().map((o) => o.value);
    const next = new Set(this.value().map(String));
    if (on) next.add(v);
    else next.delete(v);
    // The schema's order, so a list ticked in any order compares equal to the stored one.
    this.value.set(order.filter((o) => next.has(o)));
  }
}

/**
 * `div.gw-field` over a list as `gw-tag`s (`--mono`, a remove button each) and a `gw-input-group` whose addon adds
 * (kit allowlists). Enter adds without submitting; what can't be added is said at the field ("169.254.169.254 is a
 * cloud metadata address …") and the input keeps the text. After a removal focus goes to the next remove button,
 * else to the input (gw-tag A11y).
 */
@Component({
  selector: 'div[gwPolicyTags]',
  imports: [GwIcon, GwHintParts],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-field' },
  template: `
    @let v = view();
    @let s = spec();
    <label class="gw-field__label" [for]="v.id">{{ v.label }}@if (v.optional) { <span class="gw-field__optional">Optional</span>}</label>
    <div class="gw-stack gw-stack--sm">
      @if (value().length) {
        <ul class="gw-tag-list" [attr.aria-label]="s.list(env())">
          @for (item of value(); track $index) {
            @let label = labelOf(item);
            @if (s.approval?.(item)) {
              <li class="gw-tag gw-tag--mono gw-tag--warning"><svg lucideIcon="user-check" class="gw-icon"></svg><span class="gw-tag__label" [attr.title]="label">{{ label }}<span class="gw-sr-only">, needs approval</span></span><button class="gw-tag__remove" type="button" [attr.aria-label]="s.remove(label, env())" (click)="remove($index)"><svg lucideIcon="x" class="gw-icon"></svg></button></li>
            } @else {
              <li class="gw-tag gw-tag--mono"><span class="gw-tag__label" [attr.title]="label">{{ label }}</span><button class="gw-tag__remove" type="button" [attr.aria-label]="s.remove(label, env())" (click)="remove($index)"><svg lucideIcon="x" class="gw-icon"></svg></button></li>
            }
          }
        </ul>
      } @else if (empty().length) {
        <p class="gw-text-small gw-fg-muted"><span [gwHintParts]="empty()"></span></p>
      }
      <div class="gw-input-group">
        <input
          #entry
          class="gw-input gw-input--mono"
          [id]="v.id"
          [attr.type]="s.inputType ?? null"
          autocomplete="off"
          spellcheck="false"
          [attr.placeholder]="s.placeholder(env())"
          [value]="draft()"
          [attr.aria-invalid]="message() ? 'true' : null"
          [attr.aria-describedby]="describedBy()"
          (input)="onType(entry.value)"
          (keydown.enter)="add($event)"
        />
        <button class="gw-input-group__addon" type="button" [attr.aria-label]="s.add(env())" (click)="add($event)"><svg lucideIcon="plus" class="gw-icon"></svg></button>
      </div>
    </div>
    @if (message(); as m) {
      <p class="gw-field__error" [id]="v.id + '-error'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ m }}</p>
    }
    @if (v.hint.length) {
      <p class="gw-field__hint" [id]="v.id + '-hint'"><span [gwHintParts]="v.hint"></span></p>
    }
  `,
})
export class GwPolicyTags implements FormValueControl<JsonValue[]> {
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly injector = inject(Injector);

  readonly value = model<JsonValue[]>([]);
  readonly view = input.required<FieldView>();
  readonly spec = input.required<TagSpec>();
  readonly schema = input.required<KeySchema>();
  readonly env = input.required<Env>();
  /** What an empty list means, in place of the tags. */
  readonly empty = input<Hint>([]);

  protected readonly draft = signal('');
  /** Why the typed value wasn't added (cleared as soon as the text changes). */
  protected readonly addError = signal<string | null>(null);
  protected readonly message = computed(() => this.addError() ?? this.view().error);
  protected readonly describedBy = computed(() => {
    const v = this.view();
    return [this.message() ? `${v.id}-error` : '', v.hint.length ? `${v.id}-hint` : ''].filter(Boolean).join(' ') || null;
  });

  protected labelOf(item: JsonValue): string {
    return this.spec().label?.(item) ?? String(item);
  }

  protected onType(text: string): void {
    this.draft.set(text);
    this.addError.set(null);
  }

  protected add(event: Event): void {
    event.preventDefault();
    const text = this.draft().trim();
    if (!text) return;
    const ks = this.schema();
    const parsed = this.spec().parse ? this.spec().parse!(text, ks) : { value: text };
    if ('error' in parsed) {
      this.addError.set(parsed.error);
      return;
    }
    const max = ks.maxItems;
    if (max && this.value().length >= max) {
      this.addError.set(`The list holds at most ${max} values. Remove one first.`);
      return;
    }
    if (!this.value().some((v) => same(v, parsed.value) || this.labelOf(v) === this.labelOf(parsed.value))) {
      this.value.set([...this.value(), parsed.value]);
    }
    this.draft.set('');
    this.addError.set(null);
  }

  protected remove(index: number): void {
    this.value.set(this.value().filter((_, i) => i !== index));
    afterNextRender(
      () => {
        const buttons = this.host.querySelectorAll<HTMLButtonElement>('.gw-tag__remove');
        (buttons[Math.min(index, buttons.length - 1)] ?? this.host.querySelector<HTMLInputElement>('input'))?.focus();
      },
      { injector: this.injector },
    );
  }
}
