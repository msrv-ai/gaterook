import { JsonObject, JsonValue } from '../core/api/types';
import { CompiledForm, SchemaNode, cleanValue, compileSchema, initialValue } from './compile';

/**
 * A form as a person sees it, in a stable, comparable shape: every field with its widget, section, row, secret and
 * branch condition, and what the defaults would send. Used by the golden test of the connector schemas and by
 * ui/scripts/schema-goldens.mjs, which writes the goldens.
 */
export interface FieldGolden {
  readonly pointer: string;
  readonly kind: string;
  readonly title: string;
  readonly required: boolean;
  readonly secret: string | null;
  readonly mono: boolean;
  readonly unit: string | null;
  readonly options: readonly JsonValue[];
  readonly when: readonly string[];
  /** A secret typed over several lines: a textarea (kubeconfig, a JSON key, PEM). */
  readonly multiline: boolean;
  /** `x-env-default`: what an env starts with instead of `default`. */
  readonly envDefault: Readonly<Record<string, JsonValue>>;
  /** What prod locks out: `x-forbidden-env-values`, or `"all but default"` for `x-forbidden-env`. */
  readonly prodForbidden: readonly JsonValue[] | 'all but default' | null;
  readonly optionsFrom: string | null;
  readonly confirm: string | null;
}

export interface FormGolden {
  readonly sections: readonly string[];
  readonly loose: readonly string[];
  /** Each section's rows: pointers that share a `gw-form__row`. */
  readonly rows: Readonly<Record<string, readonly (readonly string[])[]>>;
  readonly fields: readonly FieldGolden[];
  /** What the defaults would send to the core (no secrets, no hidden branches, no empty values). */
  readonly defaults: JsonObject;
  /** The same for a new prod connection (`x-env-default` applied). */
  readonly prodDefaults: JsonObject;
}

const cond = (n: SchemaNode): string[] =>
  n.when.map((c) => `${c.negate ? 'not ' : ''}${c.path.join('.')}${c.minLength === undefined
    ? ` in ${JSON.stringify(c.values)}` : ` length >= ${c.minLength}`}`);

export function formGolden(form: CompiledForm, schema: JsonValue): FormGolden {
  const rows: Record<string, (readonly string[])[]> = {};
  for (const n of form.all) if (n.kind === 'object') rows[n.pointer] = n.rows.map((r) => r.map((x) => x.pointer));
  return {
    sections: form.sections.map((s) => s.pointer),
    loose: form.loose.map((s) => s.pointer),
    rows,
    fields: form.all.map((n) => ({
      pointer: n.pointer,
      kind: n.kind,
      title: n.title,
      required: n.required,
      secret: n.secret,
      mono: n.mono,
      unit: n.unit,
      options: n.options.map((o) => o.value),
      when: cond(n),
      multiline: n.multiline,
      envDefault: n.envDefault,
      prodForbidden: n.forbiddenEnv.includes('prod') ? 'all but default' : (n.forbiddenValues['prod'] ?? null),
      optionsFrom: n.optionsFrom ? `metadata.${n.optionsFrom.join('.')}` : null,
      confirm: n.confirm,
    })),
    defaults: cleanValue(form, initialValue(schema)),
    prodDefaults: cleanValue(form, initialValue(schema, null, 'prod')),
  };
}

export function golden(schema: JsonValue): FormGolden {
  return formGolden(compileSchema(schema), schema);
}
