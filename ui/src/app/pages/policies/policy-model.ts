import { EnvPolicy } from '../../core/api/policies.api';
import { Env, JsonObject, JsonValue } from '../../core/api/types';

/**
 * The policy model of the Policies page, mirroring the core's `policy.PolicyService` (ARCHITECTURE.md §4 "Policies"):
 * each environment stores only what the person set; everything else is the section schema's default for that
 * environment (`x-env-default`, then `default`, then `const`) overridden by what its ancestors set (keys with
 * `x-inherit: false` excepted). A value equal to what the environment has without it is not stored. Pure functions,
 * so the page's arithmetic is tested without a core.
 */

export const ENVS: readonly Env[] = ['dev', 'stage', 'prod'];

/** One key of a section's JSON Schema, as far as the page reads it. */
export interface KeySchema {
  readonly type?: string;
  readonly title?: string;
  readonly description?: string;
  readonly default?: JsonValue;
  readonly const?: JsonValue;
  readonly readOnly?: boolean;
  readonly enum?: readonly JsonValue[];
  readonly minimum?: number;
  readonly maximum?: number;
  readonly maxItems?: number;
  readonly uniqueItems?: boolean;
  readonly items?: {
    readonly type?: string;
    readonly enum?: readonly JsonValue[];
    readonly pattern?: string;
    readonly maxLength?: number;
    readonly properties?: JsonObject;
  };
  readonly 'x-enumTitles'?: readonly string[];
  readonly 'x-env-default'?: { readonly [env: string]: JsonValue };
  readonly 'x-inherit'?: boolean;
  readonly 'x-unit'?: string;
  readonly 'x-confirm'?: string;
  readonly 'x-forbidden-env-values'?: { readonly [env: string]: readonly JsonValue[] };
}

/** `GET /api/policies/schema`: every section's JSON Schema. */
export interface PolicySchemas {
  readonly sections: { readonly [section: string]: { readonly title?: string; readonly properties?: { readonly [key: string]: KeySchema } } };
}

export type PoliciesByEnv = Readonly<Record<Env, EnvPolicy>>;

/** How the page edits one key: a number as text, a checkbox, a select, a list. */
export type Kind = 'number' | 'boolean' | 'select' | 'list';

/** The value of one key in the form: number and select as text (`''` follows the fallback), lists as arrays. */
export type DraftValue = string | boolean | JsonValue[];

/** The form of one environment: `"<section>.<key>"` → its value. */
export type Draft = Record<string, DraftValue>;

export function keyId(section: string, key: string): string {
  return `${section}.${key}`;
}

export function splitKeyId(id: string): [string, string] {
  const at = id.indexOf('.');
  return [id.slice(0, at), id.slice(at + 1)];
}

export function keySchema(schemas: PolicySchemas, section: string, key: string): KeySchema {
  return schemas.sections[section]?.properties?.[key] ?? {};
}

/** The keys of a section in schema order. */
export function sectionKeys(schemas: PolicySchemas, section: string): string[] {
  return Object.keys(schemas.sections[section]?.properties ?? {});
}

/** A key nobody can change: `const` (a lock: "Always on") or `readOnly`. */
export function isLocked(ks: KeySchema): boolean {
  return ks.const !== undefined || ks.readOnly === true;
}

export function inheritable(ks: KeySchema): boolean {
  return ks['x-inherit'] !== false;
}

export function kindOf(ks: KeySchema): Kind | null {
  if (ks.type === 'integer' || ks.type === 'number') return ks.enum ? 'select' : 'number';
  if (ks.type === 'boolean') return 'boolean';
  if (ks.type === 'string') return 'select';
  if (ks.type === 'array') return 'list';
  return null;
}

/** The default of a key on `env`: `x-env-default`, else `default`, else `const` (PolicySchemas.defaults). */
export function envDefault(ks: KeySchema, env: Env): JsonValue | undefined {
  const own = ks['x-env-default']?.[env];
  if (own !== undefined) return own;
  if (ks.default !== undefined) return ks.default;
  if (ks.const !== undefined) return ks.const;
  // A list without a default is empty: the core leaves the key out, the connector reads no entries.
  return ks.type === 'array' ? [] : undefined;
}

/** `env` first, then the environments it inherits from (cycles cut), as the core walks them. */
export function chain(policies: PoliciesByEnv, env: Env): Env[] {
  const out: Env[] = [];
  let current: Env | null = env;
  while (current && !out.includes(current)) {
    out.push(current);
    current = policies[current]?.inherits ?? null;
  }
  return out;
}

/** Environments whose policy inherits from `env` (stage, for dev). */
export function inheritors(policies: PoliciesByEnv, env: Env): Env[] {
  return ENVS.filter((e) => e !== env && chain(policies, e).includes(env));
}

/** The value an environment stores for a key, or undefined. */
export function explicitValue(p: EnvPolicy, section: string, key: string): JsonValue | undefined {
  const s = p.explicit[section];
  if (!s || typeof s !== 'object' || Array.isArray(s)) return undefined;
  const v = (s as JsonObject)[key];
  return v === null ? undefined : v;
}

export function effectiveValue(p: EnvPolicy, section: string, key: string): JsonValue | undefined {
  const s = p.sections[section];
  if (!s || typeof s !== 'object' || Array.isArray(s)) return undefined;
  return (s as JsonObject)[key];
}

/**
 * Where a value of one environment comes from:
 * - `set`: the person set it on this environment;
 * - `inherited`: it follows `from` (an ancestor sets it, or the child takes what its parent has by default);
 * - `env-default`: this environment's own default (`x-env-default`: prod's 100 rows);
 * - `own`: a key that is never inherited (`x-inherit: false`) on an environment with a parent: its own default;
 * - `default`: the schema's default on an environment without a parent.
 */
export type Origin =
  | { readonly kind: 'set' }
  | { readonly kind: 'inherited'; readonly from: Env }
  | { readonly kind: 'env-default' }
  | { readonly kind: 'own' }
  | { readonly kind: 'default' };

/** What `env` would have for a key if it set nothing itself, and where that comes from. */
export function fallback(
  policies: PoliciesByEnv,
  schemas: PolicySchemas,
  env: Env,
  section: string,
  key: string,
): { readonly value: JsonValue | undefined; readonly origin: Origin } {
  const ks = keySchema(schemas, section, key);
  const ancestors = chain(policies, env).slice(1);
  if (inheritable(ks)) {
    // The nearest ancestor that sets it wins; an unverified row counts as setting nothing (PolicyService.explicitOf).
    for (const a of ancestors) {
      const p = policies[a];
      const v = p?.verified ? explicitValue(p, section, key) : undefined;
      if (v !== undefined) return { value: v, origin: { kind: 'inherited', from: a } };
    }
  }
  const value = envDefault(ks, env);
  if (ks['x-env-default']?.[env] !== undefined) return { value, origin: { kind: 'env-default' } };
  const parent = ancestors[0];
  if (!parent) return { value, origin: { kind: 'default' } };
  if (!inheritable(ks)) return { value, origin: { kind: 'own' } };
  // The child takes the schema's default; it "follows" its parent only while the parent has that same value.
  const parentValue = policies[parent] ? (effectiveValue(policies[parent], section, key) ?? envDefault(ks, parent)) : undefined;
  return same(parentValue, value) ? { value, origin: { kind: 'inherited', from: parent } } : { value, origin: { kind: 'default' } };
}

export function origin(policies: PoliciesByEnv, schemas: PolicySchemas, env: Env, section: string, key: string): Origin {
  const p = policies[env];
  if (p && explicitValue(p, section, key) !== undefined) return { kind: 'set' };
  return fallback(policies, schemas, env, section, key).origin;
}

/** The same JSON value however it was typed (`5` and `5.0`); a missing value equals `null`. */
export function same(a: JsonValue | undefined, b: JsonValue | undefined): boolean {
  return canonical(a ?? null) === canonical(b ?? null);
}

function canonical(v: JsonValue): string {
  if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`;
  if (v && typeof v === 'object') {
    return `{${Object.keys(v)
      .sort()
      .map((k) => `${JSON.stringify(k)}:${canonical(v[k])}`)
      .join(',')}}`;
  }
  return JSON.stringify(v);
}

// ── Numbers ─────────────────────────────────────────────────────────────────────────────────────────────────────

const NUMBER = new Intl.NumberFormat('en-US', { maximumFractionDigits: 3 });

/** "1,000", "0.5": the kit's numbers (BLUEPRINT §8 "Numbers with units and separators"). */
export function formatNumber(n: number): string {
  return NUMBER.format(n);
}

/** "1,000" or "1 000" or "2.5" → the number; anything else → NaN. */
export function parseNumber(text: string): number {
  const t = text.trim().replace(/[,\s  ]/g, '');
  return /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : Number.NaN;
}

// ── The form ────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * How a key is shown: its unit and the factor between the stored value and what is typed (the core keeps the SQL
 * statement timeout in ms, the kit asks for seconds).
 */
export interface Display {
  readonly unit: string | null;
  readonly scale: number;
}

export const PLAIN: Display = { unit: null, scale: 1 };

/** A stored value as the form holds it. */
export function toDraft(kind: Kind, value: JsonValue | undefined, display: Display = PLAIN): DraftValue {
  switch (kind) {
    case 'number':
      return typeof value === 'number' ? formatNumber(value / display.scale) : '';
    case 'boolean':
      return value === true;
    case 'select':
      return value === undefined || value === null ? '' : String(value);
    case 'list':
      return Array.isArray(value) ? [...value] : [];
  }
}

/** What the form's value means as a stored value; `undefined` for an empty number or select (follow the fallback). */
export function fromDraft(kind: Kind, draft: DraftValue | undefined, ks: KeySchema, display: Display = PLAIN): JsonValue | undefined {
  switch (kind) {
    case 'number': {
      if (typeof draft !== 'string' || !draft.trim()) return undefined;
      const n = parseNumber(draft);
      if (Number.isNaN(n)) return undefined;
      const v = n * display.scale;
      return ks.type === 'integer' ? Math.round(v) : v;
    }
    case 'boolean':
      return draft === true;
    case 'select': {
      if (typeof draft !== 'string' || draft === '') return undefined;
      const option = (ks.enum ?? []).find((o) => String(o) === draft);
      return option !== undefined ? option : draft;
    }
    case 'list':
      return Array.isArray(draft) ? draft : [];
  }
}

/**
 * The form's starting value of a key: what the environment stores; else, for a number or select that follows its
 * parent, empty (the kit's "An empty field follows the dev value shown in it"); else the value it has.
 */
export function initialDraft(
  policies: PoliciesByEnv,
  schemas: PolicySchemas,
  env: Env,
  section: string,
  key: string,
  display: Display = PLAIN,
): DraftValue | null {
  const ks = keySchema(schemas, section, key);
  const kind = kindOf(ks);
  if (!kind || isLocked(ks)) return null;
  const p = policies[env];
  const explicit = explicitValue(p, section, key);
  if (explicit !== undefined) return toDraft(kind, explicit, display);
  const fb = fallback(policies, schemas, env, section, key);
  if ((kind === 'number' || kind === 'select') && fb.origin.kind === 'inherited') return '';
  return toDraft(kind, effectiveValue(p, section, key) ?? fb.value, display);
}

/** Every editable key of every section: `"<section>.<key>"` in schema order. */
export function editableKeys(schemas: PolicySchemas): string[] {
  const out: string[] = [];
  for (const section of Object.keys(schemas.sections)) {
    for (const key of sectionKeys(schemas, section)) {
      const ks = keySchema(schemas, section, key);
      if (kindOf(ks) && !isLocked(ks)) out.push(keyId(section, key));
    }
  }
  return out;
}

export function initialForm(
  policies: PoliciesByEnv,
  schemas: PolicySchemas,
  env: Env,
  display: (section: string, key: string) => Display = () => PLAIN,
): Draft {
  const out: Draft = {};
  for (const id of editableKeys(schemas)) {
    const [section, key] = splitKeyId(id);
    const d = initialDraft(policies, schemas, env, section, key, display(section, key));
    if (d !== null) out[id] = d;
  }
  return out;
}

/** One key the person changed: what it is stored as now and what the form asks for (`undefined`: unset it). */
export interface Change {
  readonly section: string;
  readonly key: string;
  readonly from: JsonValue | undefined;
  readonly to: JsonValue | undefined;
  /** What the environment has now and what it will have once saved. */
  readonly before: JsonValue | undefined;
  readonly after: JsonValue | undefined;
}

/**
 * What the form changes against what the environment stores. A value equal to the fallback is "not set" (the core
 * drops it too), so saving a whole form pins nothing and typing the inherited value back is no change.
 */
export function changes(
  policies: PoliciesByEnv,
  schemas: PolicySchemas,
  env: Env,
  draft: Draft,
  display: (section: string, key: string) => Display = () => PLAIN,
): Change[] {
  const out: Change[] = [];
  const p = policies[env];
  for (const id of editableKeys(schemas)) {
    if (!(id in draft)) continue;
    const [section, key] = splitKeyId(id);
    const ks = keySchema(schemas, section, key);
    const kind = kindOf(ks)!;
    const fb = fallback(policies, schemas, env, section, key);
    let to = fromDraft(kind, draft[id], ks, display(section, key));
    if (to !== undefined && same(to, fb.value)) to = undefined;
    const from = explicitValue(p, section, key);
    if (same(from, to)) continue;
    out.push({ section, key, from, to, before: effectiveValue(p, section, key) ?? fb.value, after: to ?? fb.value });
  }
  return out;
}

/** The body of `PUT /api/policies/{env}`: only the keys that change; `null` unsets one. */
export function patch(changes: readonly Change[]): JsonObject {
  const out: { [section: string]: JsonObject } = {};
  for (const c of changes) {
    (out[c.section] ??= {})[c.key] = c.to === undefined ? null : c.to;
  }
  return out;
}

/** How many keys of an environment come from where (the toolbar's "4 set on stage · 17 inherited from dev"). */
export function originCounts(policies: PoliciesByEnv, schemas: PolicySchemas, env: Env): { set: number; envDefault: number; inherited: number; from: Env | null } {
  let set = 0;
  let envDefaultCount = 0;
  let inherited = 0;
  let from: Env | null = null;
  for (const id of editableKeys(schemas)) {
    const [section, key] = splitKeyId(id);
    const o = origin(policies, schemas, env, section, key);
    if (o.kind === 'set') set++;
    else if (o.kind === 'env-default') envDefaultCount++;
    else if (o.kind === 'inherited') {
      inherited++;
      from ??= o.from;
    }
  }
  return { set, envDefault: envDefaultCount, inherited, from: from ?? policies[env]?.inherits ?? null };
}

/** The keys an environment stores (Reset names them). */
export function explicitKeys(p: EnvPolicy): { section: string; key: string; value: JsonValue }[] {
  const out: { section: string; key: string; value: JsonValue }[] = [];
  for (const [section, values] of Object.entries(p.explicit)) {
    if (!values || typeof values !== 'object' || Array.isArray(values)) continue;
    for (const [key, value] of Object.entries(values)) if (value !== null) out.push({ section, key, value });
  }
  return out;
}

/** The value an option of a select or a check stands for may be refused on an environment (`x-forbidden-env-values`). */
export function forbidden(ks: KeySchema, env: Env, value: JsonValue): boolean {
  return (ks['x-forbidden-env-values']?.[env] ?? []).some((f) => same(f, value));
}
