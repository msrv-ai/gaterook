import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, booleanAttribute, forwardRef, input } from '@angular/core';
import { FieldTree, FormField } from '@angular/forms/signals';
import { LucideDynamicIcon } from '@lucide/angular';
import { GwCodeEditor } from '../code/code-editor';
import { JsonValue } from '../core/api/types';
import { GwSecret } from '../kit/secret';
import { literalParts } from '../kit/elements/shared/literals';
import { SchemaNode, getAt, tagNoun } from './compile';
import { lowerFirst } from './messages';
import { REFERENCE_SOURCES, SchemaForm } from './schema-form';
import { referencePlaceholder, sourceCopy } from './secret-sources';
import { GwTagsInput } from './tags-input';

const join = (...ids: (string | null | undefined)[]) => ids.filter(Boolean).join(' ') || null;

/**
 * One field of a schema form (ARCHITECTURE.md §8.3), in the kit's markup for its kind (design/elements/
 * connection-form.html): `gw-field` with its label, the control (mono input, `gw-input-group` with the `x-unit`
 * affix, `gw-select`, choice cards, `gw-segmented`, a tag list, `gw-textarea`, the code editor, a JSON textarea for
 * anything unknown, `gw-secret` for a secret), then `gw-field__error` before the hint. Text fields bind with
 * Signal Forms' `[formField]`; the others write the field's value themselves (a number typed as text stays text
 * until it parses, so the error can name it).
 */
@Component({
  selector: 'gw-schema-field',
  imports: [NgTemplateOutlet, FormField, LucideDynamicIcon, GwSecret, GwTagsInput, GwCodeEditor, forwardRef(() => GwSchemaFields)],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './field.html',
})
export class GwSchemaField {
  readonly form = input.required<SchemaForm>();
  readonly node = input.required<SchemaNode>();
  /** The field sits in an `x-gateway-secret: login` object: its text field is the user (hidden for short-lived). */
  readonly login = input(false, { transform: booleanAttribute });

  protected readonly join = join;
  protected readonly lowerFirst = lowerFirst;
  protected readonly String = String;
  protected readonly sourceCopy = sourceCopy;

  protected value(): JsonValue {
    return getAt(this.form().model(), this.node().path);
  }

  /** The text controls bind the field directly ([formField]); their model value is a string or empty. */
  protected textField(): FieldTree<string> {
    return this.form().field(this.node()) as unknown as FieldTree<string>;
  }

  protected hidden(): boolean {
    const n = this.node();
    const f = this.form();
    if (!f.visible(n)) return true;
    return this.login() && n.kind === 'text' && f.source() === 'dynamic';
  }

  /** The error sentence with the value it names set in mono ("<Dev DB> is not a slug…"). */
  protected errorParts(message: string) {
    const v = this.value();
    const literal = typeof v === 'string' || typeof v === 'number' ? String(v) : null;
    return literalParts(message, literal && literal.length <= 80 ? [literal] : []);
  }

  // ─── Numbers: typed as text (inputmode numeric), stored as a number once it is one ───

  protected numberText(): string {
    const v = this.value();
    return v === null ? '' : String(v);
  }

  protected setNumber(event: Event): void {
    const raw = (event.target as HTMLInputElement).value.trim();
    this.form().set(this.node(), raw === '' ? null : /^-?\d+(\.\d+)?$/.test(raw) ? Number(raw) : raw);
  }

  // ─── Choices ───

  protected setOption(event: Event): void {
    const raw = (event.target as HTMLSelectElement).value;
    const option = this.form()
      .optionsOf(this.node())
      .find((o) => String(o.value) === raw);
    this.form().set(this.node(), option ? option.value : null);
  }

  protected isChecked(option: JsonValue): boolean {
    return this.value() === option;
  }

  // ─── Tags ───

  protected tags(): string[] {
    const v = this.value();
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
  }

  protected noun(): string {
    return tagNoun(this.node());
  }

  protected tagPlaceholder(): string {
    const noun = this.noun();
    const items = this.node().schema['items'];
    const examples = items && typeof items === 'object' && !Array.isArray(items) ? items['examples'] : null;
    const example = Array.isArray(examples) && typeof examples[0] === 'string' ? `, for example ${examples[0]}` : '';
    return `Add ${/^[aeiou]/i.test(noun) ? 'an' : 'a'} ${noun}${example}`;
  }

  // ─── Code ───

  protected language(): 'sql' | 'yaml' | 'json' | 'text' {
    const l = this.node().language;
    return l === 'sql' || l === 'yaml' || l === 'json' ? l : 'text';
  }

  protected codeValue(): string {
    const v = this.value();
    return typeof v === 'string' ? v : '';
  }

  /** PEM blocks, certificates, keys: code face, no wrapping (gw-textarea --code). */
  protected codeLike(): boolean {
    const n = this.node();
    return /pem|certificate|private key|public key/i.test(`${n.title} ${n.description ?? ''}`);
  }

  // ─── Secrets ───

  protected reference(): boolean {
    return REFERENCE_SOURCES.has(this.form().source());
  }

  protected secretTyped(): string {
    return this.form().secrets()[this.node().pointer] ?? '';
  }

  protected storedSecret() {
    return this.form().stored.get(this.node().pointer) ?? null;
  }

  protected secretLabel(state: string): string {
    const n = this.node();
    if (this.reference()) return sourceCopy(this.form().source()).label ?? 'Secret reference';
    return state === 'editing' ? `New ${lowerFirst(n.title)}` : n.title;
  }

  protected secretHint(state: string): string {
    const n = this.node();
    const stored = this.storedSecret();
    if (state === 'stored') return 'Decrypted inside the connector only for the duration of a call. Nobody can view it, here or in the API.';
    if (state === 'editing') {
      return stored?.version != null
        ? `Saving creates version ${stored.version + 1}. There is no way to view either version.`
        : 'Saving replaces the stored value. There is no way to view either one.';
    }
    if (state === 'reference') {
      return this.form().source() === 'dynamic'
        ? 'A new database user for each call. No user or password is stored here.'
        : 'Resolved at call time.';
    }
    if (this.reference()) return sourceCopy(this.form().source()).hint;
    return n.description ?? sourceCopy('local').hint;
  }

  /** What a multi-line secret takes: a PEM block, base64, or the pasted document (kubeconfig, a JSON key). */
  protected multilinePlaceholder(): string {
    const n = this.node();
    if (n.secret === 'pem') return '-----BEGIN …-----';
    if (n.secret === 'binary') return 'Paste the value as base64';
    return `Paste the ${lowerFirst(n.title)}`;
  }

  protected referencePlaceholder(): string {
    const f = this.form();
    return referencePlaceholder(f.source(), f.env(), f.subject(), this.node().key);
  }

  protected testReference(): void {
    this.form().emit({ kind: 'test', node: this.node(), ref: this.secretTyped().trim() || (this.storedSecret()?.name ?? '') });
  }

  protected saveSecret(value: string): void {
    this.form().emit({ kind: 'save', node: this.node(), secretId: this.storedSecret()?.secretId ?? null, value });
  }

  protected rotateSecret(): void {
    this.form().emit({ kind: 'rotate', node: this.node(), secretId: this.storedSecret()?.secretId ?? null });
  }
}

/**
 * The fields of an object node in rows: a run of short fields shares one `gw-form__row` (Host · Port · Database),
 * every other field stands alone in the parent's `gw-form__rows`.
 */
@Component({
  selector: 'gw-schema-fields',
  imports: [GwSchemaField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (row of node().rows; track $index) {
      @if (row.length > 1) {
        <div class="gw-form__row">
          @for (child of row; track child.pointer) {
            <gw-schema-field [form]="form()" [node]="child" [login]="node().secret === 'login'" />
          }
        </div>
      } @else {
        <gw-schema-field [form]="form()" [node]="row[0]" [login]="node().secret === 'login'" />
      }
    }
  `,
})
export class GwSchemaFields {
  readonly form = input.required<SchemaForm>();
  readonly node = input.required<SchemaNode>();
}
