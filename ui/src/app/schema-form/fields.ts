import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, Injector, afterNextRender, forwardRef, inject, input, signal } from '@angular/core';
import { FieldTree, FormField } from '@angular/forms/signals';
import { LucideDynamicIcon } from '@lucide/angular';
import { GwCodeEditor } from '../code/code-editor';
import { JsonValue } from '../core/api/types';
import { DesktopBridge, desktopErrorText } from '../core/desktop/desktop-bridge';
import { GwMenu, GwMenuTrigger } from '../kit/menu';
import { GwReveal } from '../kit/reveal';
import { GwSecret } from '../kit/secret';
import { literalParts } from '../kit/elements/shared/literals';
import { SchemaNode, getAt, sameJson, tagNoun } from './compile';
import { lowerFirst } from './messages';
import { SchemaForm, StoreOption, kindTitle, storedReference } from './schema-form';
import { REFERENCE_SCHEMES, referencePlaceholder } from './secret-sources';
import { GwTagsInput } from './tags-input';
import { GwVaultPicker } from './vault-picker';

const join = (...ids: (string | null | undefined)[]) => ids.filter(Boolean).join(' ') || null;

/**
 * One field of a schema form (ARCHITECTURE.md §8.3), using the shared markup for its kind: `gw-field` with its label, the control (mono input, `gw-input-group` with the `x-unit`
 * affix, `gw-select`, choice cards, `gw-segmented`, a tag list, `gw-textarea`, the code editor, a JSON textarea for
 * anything unknown, `gw-secret` for a secret), then `gw-field__error` before the hint. Text fields bind with
 * Signal Forms' `[formField]`; the others write the field's value themselves (a number typed as text stays text
 * until it parses, so the error can name it).
 */
@Component({
  selector: 'gw-schema-field',
  imports: [NgTemplateOutlet, FormField, LucideDynamicIcon, GwMenu, GwMenuTrigger, GwReveal, GwSecret, GwTagsInput, GwCodeEditor,
    GwVaultPicker, forwardRef(() => GwSchemaFields)],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './field.html',
})
export class GwSchemaField {
  protected readonly desktop = inject(DesktopBridge);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly doc = inject(DOCUMENT);
  readonly form = input.required<SchemaForm>();
  readonly node = input.required<SchemaNode>();
  protected readonly join = join;
  protected readonly lowerFirst = lowerFirst;
  protected readonly String = String;
  protected readonly choosingFile = signal(false);
  protected readonly filePickerError = signal<string | null>(null);

  /** The desktop's native picker for this field: an SSH private key, or a SQLite, DuckDB or Access database file. */
  protected filePicker(): boolean {
    const kind = this.node().schema['x-file-picker'];
    return kind === 'ssh-private-key' || this.databaseKind() !== null;
  }

  /** The database engine of a database file picker, or null for an SSH key. */
  private databaseKind(): 'sqlite' | 'duckdb' | 'access' | null {
    const kind = this.node().schema['x-file-picker'];
    return kind === 'sqlite-database' ? 'sqlite' : kind === 'duckdb-database' ? 'duckdb'
      : kind === 'access-database' ? 'access' : null;
  }

  /** Whether this desktop shell has the field's picker (an older one may not). */
  protected pickerAvailable(): boolean {
    return this.databaseKind() ? this.desktop.databasePickerAvailable : this.desktop.privateKeyPickerAvailable;
  }

  protected async chooseFile(): Promise<void> {
    if (!this.filePicker() || !this.pickerAvailable() || this.choosingFile() || this.form().locked(this.node())) return;
    const form = this.form();
    const node = this.node();
    const previous = this.value();
    this.choosingFile.set(true);
    this.filePickerError.set(null);
    try {
      const database = this.databaseKind();
      const path = database ? await this.desktop.chooseDatabaseFile(database) : await this.desktop.choosePrivateKeyFile();
      // A dialog may outlive its form or an edit made while it was open.
      if (!this.destroyRef.destroyed && this.form() === form && this.node() === node
          && form.visible(node) && !form.locked(node) && this.value() === previous && path) {
        form.set(node, path);
        form.touch(node);
      }
    } catch (error) {
      if (!this.destroyRef.destroyed) this.filePickerError.set(desktopErrorText(error, 'Could not open the file picker. Enter the path manually.'));
    } finally {
      if (!this.destroyRef.destroyed) this.choosingFile.set(false);
    }
  }

  protected value(): JsonValue {
    return getAt(this.form().model(), this.node().path);
  }

  /** The text controls bind the field directly ([formField]); their model value is a string or empty. */
  protected textField(): FieldTree<string> {
    return this.form().field(this.node()) as unknown as FieldTree<string>;
  }

  protected hidden(): boolean {
    return ('const' in this.node().schema && sameJson(this.value(), this.node().schema['const']))
      || (this.node().kind === 'secret' && this.form().source() === 'dynamic')
      || (this.node().readOnly && !this.hasValue())
      || !this.form().visible(this.node());
  }

  /** A value set by Test connection (`readOnly`) shows as it is, never as a control. */
  protected hasValue(): boolean {
    const filled = (v: JsonValue): boolean => v !== null && v !== '' && (Array.isArray(v) ? v.length > 0
      : typeof v === 'object' ? Object.values(v).some(filled) : true);
    return filled(this.value());
  }

  protected readOnlyText(): string {
    const v = this.value();
    return typeof v === 'string' ? v : JSON.stringify(v);
  }

  // ─── Suggestions: what the setup helpers found, under the field ───

  /** How many found values the line under a field names; the rest are in the field's autocomplete. */
  private static readonly SHOWN = 8;

  protected suggestion() {
    return this.form().suggestionFor(this.node());
  }

  /** Found values worth a button: for text, numbers and lists (a choice lists them itself). */
  protected chips() {
    const kind = this.node().kind;
    return kind === 'text' || kind === 'tags' || kind === 'number' || kind === 'textarea'
      ? this.form().offered(this.node()).slice(0, GwSchemaField.SHOWN) : [];
  }

  protected moreChips(): number {
    const kind = this.node().kind;
    if (kind !== 'text' && kind !== 'tags' && kind !== 'number' && kind !== 'textarea') return 0;
    const extra = this.form().offered(this.node()).length - GwSchemaField.SHOWN;
    return Math.max(0, extra) + (this.suggestion()?.complete === false ? 1 : 0);
  }

  /** The id of the field's autocomplete list, while the helpers found values for a text field. */
  protected datalistId(): string | null {
    return this.node().kind === 'text' && this.suggestion()?.options.length ? `${this.form().id(this.node())}-found` : null;
  }

  /** "Found in your kubeconfig:" before the values; "Filled in from …" when one was put into the field. */
  protected suggestLead(): string {
    const s = this.suggestion();
    const source = s?.source ?? 'what GateRook found';
    const others = this.chips().length > 0;
    if (this.form().wasFilled(this.node())) return others ? `Filled in from ${source}. Also found:` : `Filled in from ${source}.`;
    return this.node().kind === 'tags' ? `Add from ${source}:` : `Found in ${source}:`;
  }

  protected showSuggestLine(): boolean {
    return this.chips().length > 0 || this.form().wasFilled(this.node());
  }

  protected chipLabel(option: { readonly value: string; readonly label: string | null }): string {
    const n = this.node();
    return n.kind === 'tags' ? `Add ${option.value} to ${lowerFirst(n.title)}` : `Use ${option.value} for ${lowerFirst(n.title)}`;
  }

  protected pick(value: string): void {
    this.form().applySuggestion(this.node(), value);
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

  /**
   * A choice that may stay empty says "Not set" and keeps that option once a value is picked, as the playground's do:
   * an optional field, or one an omittable object requires (Sign-in's type), so the object can be left out again.
   */
  protected clearable(): boolean {
    const n = this.node();
    return n.schema['default'] === undefined && (!this.form().needed(n) || n.within.length > 0);
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

  // ─── Where a value comes from ───

  /** The word on a field's menu button: Text, or the store its reference reads from (Vault; vault-stage of several). */
  protected sourceLabel(): string {
    const src = this.form().fieldSource(this.node());
    if (!src) return 'Text';
    return src.store && (this.form().storesOf(src.kind).length > 1 || !src.available) ? src.store : SHORT_KINDS[src.kind] ?? src.kind;
  }

  protected optionValue(option: StoreOption): string {
    return `${option.kind}/${option.store ?? ''}`;
  }

  protected schemeOf(kind: string): string {
    return REFERENCE_SCHEMES[kind] ?? '';
  }

  /** Why Text can't be picked: a stored secret read from a store stays there, as the core can't make it a value. */
  protected textReason(): string | null {
    const stored = this.storedSecret();
    return this.node().kind === 'secret' && stored && storedReference(stored)
      ? 'A secret read from a store stays there: change its reference instead.' : null;
  }

  protected textHint(): string {
    if (this.node().kind !== 'secret') return 'Typed into this form';
    return this.form().beginner() ? 'Encrypted on this computer' : 'Encrypted here with the master key';
  }

  /** A pick in a field's menu: Text, or a store, whose scheme goes in front of the value. */
  protected pickSource(value: string): void {
    const f = this.form();
    const option = value === 'text' ? null : f.stores().find((o) => this.optionValue(o) === value);
    if (option === undefined || (option && !option.available) || (!option && this.textReason())) return;
    f.chooseSource(this.node(), option);
    this.keepFocus(true);
  }

  /**
   * The keyboard stays in the field, after its value, when its control changed under it (a typed scheme turned a
   * password field into a reference) or a store was picked in its menu. An unchanged focused control keeps its selection.
   */
  protected keepFocus(always = false): void {
    afterNextRender(
      () => {
        const el = this.doc.getElementById(this.form().id(this.node()));
        if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) return;
        const active = this.doc.activeElement;
        if (!always && active === el) return;
        if (!always && active && active !== this.doc.body && active !== el) return;
        el.focus();
        try {
          el.setSelectionRange(el.value.length, el.value.length);
        } catch {
          // Not every input keeps a caret.
        }
      },
      { injector: this.injector },
    );
  }

  /** Where a typed reference is read from, and when. */
  protected referenceHint(): string {
    const f = this.form();
    const n = this.node();
    const kind = f.fieldKind(n);
    if (!kind) return '';
    const title = kindTitle(kind);
    const source = f.fieldSource(n);
    if (source && !source.available) return source.reason ?? `Connect ${source.title} in Secrets or choose another source.`;
    const all = f.storesOf(kind).filter(option => option.available);
    const store = f.fieldStore(n);
    if (!all.length) return `No ${title} is connected yet: connect one in Secrets, or the ${lowerFirst(n.title)} can't be read.`;
    if (store) return `Read from ${store} at call time.`;
    if (all.length > 1) return `Read at call time from the first ${title} that has it, asked in turn: ${all.map((s) => s.store ?? s.title).join(', then ')}.`;
    return `Read from ${title} at call time.`;
  }

  /** The hint of a text field next to a secret: its description, or where its reference is read from. */
  protected referenceFieldHint(): string | null {
    const f = this.form();
    const n = this.node();
    if (f.textState(n) === 'reference') return 'Read from its store at call time.';
    return f.fieldKind(n) ? this.referenceHint() : n.description ?? null;
  }

  protected referencePlaceholder(): string {
    const f = this.form();
    return referencePlaceholder(f.fieldKind(this.node()) ?? 'vault', f.env(), f.subject(), this.node().key);
  }

  // ─── Secrets ───

  protected secretTyped(): string {
    return this.form().secrets()[this.node().pointer] ?? '';
  }

  protected typeSecret(event: Event): void {
    this.form().setSecret(this.node(), (event.target as HTMLInputElement | HTMLTextAreaElement).value);
    this.keepFocus();
  }

  protected storedSecret() {
    return this.form().stored.get(this.node().pointer) ?? null;
  }

  protected secretLabel(state: string): string {
    const n = this.node();
    return state === 'editing' ? `New ${lowerFirst(n.title)}` : n.title;
  }

  protected secretHint(state: string): string {
    const n = this.node();
    const stored = this.storedSecret();
    if (stored?.staged && (state === 'stored' || state === 'reference')) return state === 'reference'
      ? 'Prepared reference. GateRook uses its configured secret store only after you confirm the import.'
      : 'Prepared for import. GateRook saves this credential encrypted only after you confirm the import.';
    if (state === 'stored') return 'Decrypted inside the connector only for the duration of a call. Nobody can view it, here or in the API.';
    if (state === 'editing') {
      return stored?.version != null
        ? `Saving creates version ${stored.version + 1}. There is no way to view either version.`
        : 'Saving replaces the stored value. There is no way to view either one.';
    }
    if (state === 'reference') return 'Resolved at call time.';
    if (this.form().fieldKind(n)) return this.referenceHint();
    // A first connection: where the value goes, in a beginner's words (kit connection-new ?state=first-run).
    if (this.form().beginner()) return 'Encrypted on this computer when you save.';
    return n.description ?? 'Encrypted with the master key on save. Nobody can view it afterwards: Replace or Rotate only. Or pick a store in its menu to read it from there.';
  }

  /** What a multi-line secret takes: a PEM block, base64, or the pasted document (kubeconfig, a JSON key). */
  protected multilinePlaceholder(): string {
    const n = this.node();
    if (n.secret === 'pem') return '-----BEGIN …-----';
    if (n.secret === 'binary') return 'Paste the value as base64, or a vault:// reference';
    return `Paste the ${lowerFirst(n.title)}, or a vault:// reference`;
  }

  /** A reference typed now, else the stored one's URI (its secret name only when the URI isn't known). */
  protected testReference(): void {
    const f = this.form();
    const typed = f.fieldReference(this.node());
    const stored = this.storedSecret();
    const store = typed ? f.fieldStore(this.node()) : stored?.store ?? undefined;
    f.emit({ kind: 'test', node: this.node(), ref: typed || stored?.ref || (stored?.name ?? ''), ...(store ? { store } : {}) });
  }

  protected saveSecret(value: string): void {
    this.form().emit({ kind: 'save', node: this.node(), secretId: this.storedSecret()?.secretId ?? null, value });
  }

  protected rotateSecret(): void {
    this.form().emit({ kind: 'rotate', node: this.node(), secretId: this.storedSecret()?.secretId ?? null });
  }
}

/** How a field's menu button names a kind of store. */
const SHORT_KINDS: Readonly<Record<string, string>> = { vault: 'Vault', 'gcp-sm': 'GCP', 'aws-sm': 'AWS', 'azure-kv': 'Azure' };

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
            <gw-schema-field [form]="form()" [node]="child" />
          }
        </div>
      } @else {
        <gw-schema-field [form]="form()" [node]="row[0]" />
      }
    }
  `,
})
export class GwSchemaFields {
  readonly form = input.required<SchemaForm>();
  readonly node = input.required<SchemaNode>();
}
