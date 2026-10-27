import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { FieldTree, FormField } from '@angular/forms/signals';
import { ConnectionRow } from '../../core/api/connections.api';
import { Env, JsonValue } from '../../core/api/types';
import { GwIcon } from '../../kit/icons';
import { SchemaNode, getAt, layoutRows } from '../../schema-form/compile';
import { GwSchemaFields } from '../../schema-form/fields';
import { SchemaForm } from '../../schema-form/schema-form';
import { GwTagsInput } from '../../schema-form/tags-input';
import { tagNoun } from '../../schema-form/compile';
import { fieldHint, isLongText } from './playground-view';

const join = (...ids: (string | null | undefined)[]) => ids.filter(Boolean).join(' ') || null;

/**
 * `<div gwPlaygroundFields [form]="args">` — the Form panel of Arguments (kit playground.html `#pg-panel-form`): one
 * kit field per property of the tool's inputSchema, named by the property itself in mono (`connection`, `sql`,
 * `limit`) with "Optional" when the agent may leave it out, in the schema's order and the renderer's rows.
 *
 * State, rules and messages are the schema renderer's (`SchemaForm` over Signal Forms, ARCHITECTURE.md §8.3), so the
 * values are checked the way a connection form checks them; the markup is the Playground page's: `connection` is a
 * select of the picked row's connection with its environment and host under it, long strings (SQL, a manifest, a
 * query) a wrapping code textarea, objects and anything unknown JSON, lists of strings tags.
 */
@Component({
  selector: 'div[gwPlaygroundFields]',
  imports: [NgTemplateOutlet, FormField, GwIcon, GwTagsInput, GwSchemaFields],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './playground-fields.html',
})
export class GwPlaygroundFields {
  readonly form = input.required<SchemaForm>();
  /** The tool the arguments are for ("sql_list_schemas takes no arguments"). */
  readonly tool = input.required<string>();
  /** The choices of the `connection` argument (the picked row's connection, or what the schema lists). */
  readonly connectionChoices = input<readonly string[]>([]);
  /** The connections of the gateway: type name and host under the select. */
  readonly connections = input<readonly ConnectionRow[]>([]);

  protected readonly join = join;
  protected readonly String = String;
  protected readonly hintOf = fieldHint;
  protected readonly longText = isLongText;

  /** The root's own fields in the renderer's rows (a run of short fields shares a `gw-form__row`). */
  protected readonly rows = computed(() => layoutRows(this.form().compiled.loose));
  /** Object properties with fields of their own: a fieldset of the renderer's fields. */
  protected readonly sections = computed(() => this.form().compiled.sections);
  protected readonly empty = computed(() => !this.form().compiled.loose.length && !this.form().compiled.sections.length);

  protected value(n: SchemaNode): JsonValue {
    return getAt(this.form().model(), n.path);
  }

  protected textField(n: SchemaNode): FieldTree<string> {
    return this.form().field(n) as unknown as FieldTree<string>;
  }

  protected id(n: SchemaNode): string {
    return this.form().id(n);
  }

  protected isConnection(n: SchemaNode): boolean {
    return n.key === 'connection' && n.path.length === 1 && (n.kind === 'select' || n.kind === 'text' || n.kind === 'choice-cards');
  }

  protected connectionRow(name: JsonValue): ConnectionRow | null {
    return typeof name === 'string' ? (this.connections().find((c) => c.name === name) ?? null) : null;
  }

  protected envOf(name: JsonValue): Env | null {
    return this.connectionRow(name)?.env ?? null;
  }

  protected setConnection(n: SchemaNode, event: Event): void {
    const raw = (event.target as HTMLSelectElement).value;
    this.form().set(n, raw === '' ? null : raw);
  }

  protected setOption(n: SchemaNode, event: Event): void {
    const raw = (event.target as HTMLSelectElement).value;
    const option = this.form()
      .optionsOf(n)
      .find((o) => String(o.value) === raw);
    this.form().set(n, option ? option.value : null);
  }

  protected numberText(n: SchemaNode): string {
    const v = this.value(n);
    return v === null || v === undefined ? '' : String(v);
  }

  /** A number typed as text: stored as a number once it is one, so the rule can name what was typed otherwise. */
  protected setNumber(n: SchemaNode, event: Event): void {
    const raw = (event.target as HTMLInputElement).value.trim();
    this.form().set(n, raw === '' ? null : /^-?\d+(\.\d+)?$/.test(raw) ? Number(raw) : raw);
  }

  protected setChecked(n: SchemaNode, event: Event): void {
    this.form().set(n, (event.target as HTMLInputElement).checked);
  }

  protected tags(n: SchemaNode): string[] {
    const v = this.value(n);
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
  }

  protected noun(n: SchemaNode): string {
    return tagNoun(n);
  }
}
