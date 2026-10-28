import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, model, output } from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';
import { LucideDynamicIcon } from '@lucide/angular';
import { TimeService } from '../core/time/time.service';
import { absoluteTime, isoLocal } from '../core/time/time-format';
import { GwAriaDisabled } from './aria-disabled';
import { GwReveal } from './reveal';

export type SecretState = 'empty' | 'editing' | 'stored' | 'reference';

/** Providers of a reference, with the kit's icon and name. */
const PROVIDERS: Record<string, { readonly name: string; readonly icon: string }> = {
  vault: { name: 'Vault', icon: 'vault' },
  'gcp-sm': { name: 'GCP Secret Manager', icon: 'cloud' },
  'aws-sm': { name: 'AWS Secrets Manager', icon: 'cloud' },
  'short-lived': { name: 'Short-lived', icon: 'timer' },
};

/**
 * `<div gwSecret>`: the kit's write-only secret (`gw-secret`, DESIGN.md §8 "Secrets"). Its `data-state` picks what it
 * shows, inside a 32 px frame that never jumps:
 * - `empty` — a brand new value: a password field; the reveal toggle exists only here, while it is typed first;
 * - `editing` — replacing a stored value: a password field, Cancel, Save (no reveal; the stored version keeps
 *   working until Save);
 * - `stored` — "Encrypted · version 3 · updated 2 days ago" with Replace / Rotate, never "show";
 * - `reference` — provider + the reference URI (it is not a secret) + Test reference.
 * The typed value is a `FormValueControl<string>` model, so a form can bind it; it never enters the connection's
 * config (the renderer sends it as `secrets`, §8.3).
 */
@Component({
  selector: 'div[gwSecret]',
  imports: [LucideDynamicIcon, GwReveal, GwAriaDisabled],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-secret',
    '[attr.data-state]': 'state()',
    '[attr.role]': 'grouped() ? "group" : null',
    '[attr.aria-labelledby]': 'grouped() ? labelledBy() : null',
    '[attr.aria-describedby]': 'grouped() ? describedBy() : null',
  },
  template: `
    @switch (state()) {
      @case ('stored') {
        <span class="gw-secret__status"><svg lucideIcon="lock-keyhole" class="gw-icon"></svg>Encrypted</span>
        @if (version() !== null || updatedAt()) {
          <span class="gw-secret__meta"
            >@if (version() !== null) {version <span class="gw-mono">{{ version() }}</span>}@if (version() !== null && updatedAt()) { · }@if (updatedAt()) {updated <time [attr.datetime]="iso()" [attr.title]="absolute()">{{ relative() }}</time>}</span
          >
        }
        <span class="gw-secret__actions">
          <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" [attr.aria-label]="'Replace ' + object()" (click)="replace.emit()">Replace</button>
          <button
            class="gw-btn gw-btn--ghost gw-btn--sm"
            type="button"
            [attr.aria-label]="'Rotate ' + object()"
            [gwAriaDisabled]="!canRotate()"
            [attr.aria-describedby]="!canRotate() ? rotateReasonId() : null"
            (click)="rotate.emit()"
          >
            Rotate
          </button>
        </span>
      }
      @case ('reference') {
        <span class="gw-secret__status"><svg [lucideIcon]="providerInfo().icon" class="gw-icon"></svg>{{ providerInfo().name }}</span>
        <code class="gw-secret__ref">{{ ref() }}</code>
        <span class="gw-secret__actions">
          <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" [attr.aria-label]="'Test ' + refNoun() + ' for ' + subject()" (click)="testReference.emit()">
            Test {{ refNoun() }}
          </button>
          @if (changeable()) {
            <button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" [attr.aria-label]="'Change ' + refNoun() + ' for ' + subject()" (click)="changeRef.emit()">
              Change
            </button>
          }
        </span>
      }
      @case ('editing') {
        <input
          class="gw-input"
          [class.gw-input--mono]="mono()"
          type="password"
          [id]="inputId()"
          [value]="value()"
          autocomplete="new-password"
          [attr.aria-describedby]="describedBy()"
          [attr.aria-invalid]="invalid() ? 'true' : null"
          (input)="value.set($any($event.target).value)"
          (blur)="touch.emit()"
        />
        <span class="gw-secret__actions">
          <button class="gw-btn gw-btn--ghost" type="button" (click)="cancel.emit()">Cancel</button>
          <button class="gw-btn" type="button" (click)="save.emit(value())">Save {{ fieldName() }}@if (subject()) {<span class="gw-sr-only"> for {{ subject() }}</span>}</button>
        </span>
      }
      @default {
        @if (revealable()) {
          <div class="gw-input-group">
            <input
              #field
              class="gw-input"
              [class.gw-input--mono]="mono()"
              type="password"
              [id]="inputId()"
              [value]="value()"
              [attr.placeholder]="placeholder()"
              autocomplete="new-password"
              spellcheck="false"
              [attr.aria-describedby]="describedBy()"
              [attr.aria-invalid]="invalid() ? 'true' : null"
              [attr.required]="required() ? '' : null"
              (input)="value.set($any($event.target).value)"
              (blur)="touch.emit()"
            />
            <button
              class="gw-input-group__addon"
              type="button"
              [gwReveal]="field"
              [attr.aria-controls]="inputId()"
              [attr.aria-label]="'Show ' + fieldName()"
            >
              <svg lucideIcon="eye" class="gw-icon"></svg><svg lucideIcon="eye-off" class="gw-icon"></svg>
            </button>
          </div>
        } @else {
          <input
            class="gw-input"
            [class.gw-input--mono]="mono()"
            type="password"
            [id]="inputId()"
            [value]="value()"
            [attr.placeholder]="placeholder()"
            autocomplete="new-password"
            [attr.aria-describedby]="describedBy()"
            [attr.aria-invalid]="invalid() ? 'true' : null"
            [attr.required]="required() ? '' : null"
            (input)="value.set($any($event.target).value)"
            (blur)="touch.emit()"
          />
        }
      }
    }
  `,
})
export class GwSecret implements FormValueControl<string> {
  private readonly time = inject(TimeService);
  readonly state = input<SecretState>('empty');
  /** The new value while it is typed (empty, editing). */
  readonly value = model('');
  readonly invalid = input(false, { transform: booleanAttribute });
  readonly required = input(false, { transform: booleanAttribute });
  readonly touch = output<void>();

  /** Id of the field's visible label (`gw-field__label`) for the stored / reference group. */
  readonly labelledBy = input<string | null>(null);
  readonly describedBy = input<string | null>(null);
  readonly inputId = input<string>('');
  /** "password" (button labels: "Replace password for dev-db", "Save password"). */
  readonly fieldName = input('password');
  /** "dev-db": the object the buttons name. */
  readonly subject = input('');
  readonly placeholder = input<string | null>(null);
  readonly mono = input(false, { transform: booleanAttribute });
  /** A reveal toggle while a brand new value is typed (empty only). */
  readonly revealable = input(true, { transform: booleanAttribute });

  readonly version = input<number | null>(null);
  readonly updatedAt = input<string | null>(null);
  readonly canRotate = input(true, { transform: booleanAttribute });
  /** Id of the visible reason Rotate is unavailable (`aria-describedby`). */
  readonly rotateReasonId = input<string | null>(null);

  /** `vault`, `gcp-sm`, `aws-sm`, `short-lived`. */
  readonly provider = input<string>('vault');
  readonly ref = input<string>('');
  /** The reference noun used by its Test action. */
  readonly refNoun = input('reference');
  /** A Change button next to Test (the form swaps the well for an input). */
  readonly changeable = input(false, { transform: booleanAttribute });

  readonly replace = output<void>();
  readonly rotate = output<void>();
  readonly testReference = output<void>();
  /** Change the reference (named so it never catches a bubbling DOM `change`). */
  readonly changeRef = output<void>();
  readonly cancel = output<void>();
  readonly save = output<string>();

  protected readonly grouped = computed(() => this.state() === 'stored' || this.state() === 'reference');
  protected readonly object = computed(() => `${this.fieldName()}${this.subject() ? ` for ${this.subject()}` : ''}`);
  protected readonly providerInfo = computed(() => PROVIDERS[this.provider()] ?? { name: this.provider(), icon: 'link' });
  protected readonly iso = computed(() => (this.updatedAt() ? isoLocal(this.updatedAt()!) : null));
  protected readonly absolute = computed(() => (this.updatedAt() ? absoluteTime(this.updatedAt()!) : null));
  protected readonly relative = computed(() => this.time.relative(this.updatedAt()));
}
