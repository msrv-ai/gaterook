import { JsonObject, JsonValue } from '../core/api/types';

/**
 * JSON Schema → the fields of a kit form (ARCHITECTURE.md §8.3 and the `x-…` vocabulary of research/14 §4.3). Pure
 * functions, so every decision of the renderer is tested without a DOM: which widget, which section, which row,
 * which field is a secret, which branch of a `oneOf` / `if-then` shows, what is sent to the core.
 */

export type FieldKind =
  | 'text'
  | 'number'
  | 'select'
  | 'segmented'
  | 'choice-cards'
  | 'checkbox'
  | 'tags'
  | 'textarea'
  | 'code'
  | 'secret'
  | 'json'
  | 'object';

/** A field shows only while another field (by path) has one of these values (`oneOf` / `if-then` branches). */
export interface Condition {
  readonly path: readonly string[];
  readonly values: readonly JsonValue[];
  readonly negate: boolean;
  /** A required string activates optional settings after it reaches this length (for example a bastion host). */
  readonly minLength?: number;
}

export interface FieldOption {
  readonly value: JsonValue;
  readonly title: string;
  readonly description: string | null;
}

export interface SchemaNode {
  readonly key: string;
  /** Keys from the model's root: `['connection', 'host']`. */
  readonly path: readonly string[];
  /** JSON Pointer of the field (`/connection/host`): server errors and secrets are keyed by it. */
  readonly pointer: string;
  readonly kind: FieldKind;
  readonly title: string;
  readonly description: string | null;
  readonly required: boolean;
  /** "Optional" after the label: not required and nothing filled in for the user. */
  readonly optional: boolean;
  /** Identifiers, hosts, paths: the kit sets config values in the mono face. */
  readonly mono: boolean;
  readonly schema: JsonObject;
  readonly unit: string | null;
  readonly options: readonly FieldOption[];
  /** `x-gateway-secret`: `value`, `login`, `pem`, `binary` (on the secret field or the object holding it). */
  readonly secret: string | null;
  readonly language: string | null;
  /** `x-stricter`: a policy may tighten this value ("Policy may tighten"). */
  readonly stricter: string | null;
  /** `x-confirm`: ask before saving a change of this field. */
  readonly confirm: 'warning' | 'danger' | null;
  /** `x-env-default`: the value an environment starts with instead of `default` (`{prod: true}`: TLS on prod). */
  readonly envDefault: Readonly<Record<string, JsonValue>>;
  /** `x-forbidden-env`: environments where the option stays at its `default` (Skip TLS verification on prod). */
  readonly forbiddenEnv: readonly string[];
  /** `x-forbidden-env-values`: values that can't be chosen on an environment (`{prod: ['apiKeyQuery']}`). */
  readonly forbiddenValues: Readonly<Record<string, readonly JsonValue[]>>;
  /** `x-options-from: metadata.<path>`: the path in the connection's metadata that lists the options. */
  readonly optionsFrom: readonly string[] | null;
  /** A secret typed over several lines (kubeconfig, a service account key): a textarea, never a password input. */
  readonly multiline: boolean;
  readonly collapsed: boolean;
  readonly when: readonly Condition[];
  readonly children: readonly SchemaNode[];
  /** The children laid out: a run of short fields shares one `gw-form__row`. */
  readonly rows: readonly (readonly SchemaNode[])[];
}

/** The compiled form: one section per top-level object, the root's own scalar fields apart. */
export interface CompiledForm {
  readonly sections: readonly SchemaNode[];
  readonly loose: readonly SchemaNode[];
  /** Every node, depth first in display order (the order focus goes to the first invalid one). */
  readonly all: readonly SchemaNode[];
}

const isObject = (v: unknown): v is JsonObject => !!v && typeof v === 'object' && !Array.isArray(v);
const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const escapeKey = (k: string) => k.replace(/~/g, '~0').replace(/\//g, '~1');
const pointerOf = (path: readonly string[]) => path.map((p) => '/' + escapeKey(p)).join('');

/**
 * Brings other spellings of the same keywords to the one vocabulary of §4.3 when a schema is received
 * (`x-gateway-credential`, `x-secret`, `x-widget: monaco-yaml`), so the renderer reads only one.
 */
export function normalizeSchema(schema: JsonValue): JsonValue {
  if (Array.isArray(schema)) return schema.map(normalizeSchema);
  if (!isObject(schema)) return schema;
  const out: { [key: string]: JsonValue } = {};
  for (const [k, v] of Object.entries(schema)) out[k] = normalizeSchema(v);
  const credential = out['x-gateway-credential'];
  if (credential !== undefined && out['x-gateway-secret'] === undefined) {
    out['x-gateway-secret'] = credential === true ? 'value' : credential;
  }
  if (out['x-secret'] === true && out['x-gateway-secret'] === undefined) out['x-gateway-secret'] = 'value';
  if (out['x-widget'] === 'monaco-yaml') {
    out['x-widget'] = 'code';
    out['x-language'] ??= 'yaml';
  }
  delete out['x-gateway-credential'];
  delete out['x-secret'];
  return out;
}

/** Resolves a local `$ref` (`#/properties/credentials`) once per path; anything else stays as it is. */
function deref(node: JsonObject, root: JsonObject, seen: ReadonlySet<string>): { schema: JsonObject; seen: ReadonlySet<string> } {
  const ref = str(node['$ref']);
  if (!ref || !ref.startsWith('#/') || seen.has(ref)) return { schema: node, seen };
  let target: JsonValue | undefined = root;
  for (const part of ref.slice(2).split('/')) {
    const key = part.replace(/~1/g, '/').replace(/~0/g, '~');
    target = isObject(target) ? target[key] : undefined;
  }
  if (!isObject(target)) return { schema: node, seen };
  const rest = { ...node };
  delete rest['$ref'];
  return { schema: { ...target, ...rest }, seen: new Set([...seen, ref]) };
}

function typeOf(s: JsonObject): string | null {
  const t = s['type'];
  if (Array.isArray(t)) return t.find((x): x is string => typeof x === 'string' && x !== 'null') ?? null;
  return str(t);
}

function isConstBranches(v: JsonValue | undefined): v is JsonValue[] {
  return Array.isArray(v) && v.length > 0 && v.every((o) => isObject(o) && 'const' in o);
}

/** The widget of a field: an explicit `x-widget` first, then the type. Anything unknown → a JSON field. */
export function kindOf(s: JsonObject): FieldKind {
  const type = typeOf(s);
  if (s['x-gateway-secret'] !== undefined && type !== 'object') return 'secret';
  if (s['writeOnly'] === true || s['format'] === 'password') return 'secret';
  // Options listed by the connection's metadata (kubeconfig contexts): a select, or a text field until there are any.
  if (typeof s['x-options-from'] === 'string' && (type === 'string' || type === null)) return 'select';
  switch (str(s['x-widget'])) {
    case 'textarea':
      return 'textarea';
    case 'code':
      return 'code';
    case 'segmented':
      return 'segmented';
    case 'choice-cards':
    case 'env-cards':
      return 'choice-cards';
    case 'tags':
      return 'tags';
  }
  if (Array.isArray(s['enum']) || isConstBranches(s['oneOf']) || isConstBranches(s['anyOf'])) {
    // Five options or fewer, each with a sentence, become choice cards (kit "From JSON Schema to fields").
    const options = optionsOf(s);
    return options.length <= 5 && options.every((o) => o.description) ? 'choice-cards' : 'select';
  }
  switch (type) {
    case 'object':
      return isObject(s['properties']) || Array.isArray(s['oneOf']) || Array.isArray(s['allOf']) ? 'object' : 'json';
    case 'string':
      return 'text';
    case 'integer':
    case 'number':
      return 'number';
    case 'boolean':
      return 'checkbox';
    case 'array': {
      const items = isObject(s['items']) ? s['items'] : null;
      return items && typeOf(items) === 'string' && !Array.isArray(items['enum']) ? 'tags' : 'json';
    }
    default:
      return 'json';
  }
}

function optionsOf(s: JsonObject): FieldOption[] {
  const values = s['enum'];
  if (Array.isArray(values)) {
    const titles = Array.isArray(s['x-enumTitles']) ? s['x-enumTitles'] : [];
    const descriptions = Array.isArray(s['x-enumDescriptions']) ? s['x-enumDescriptions'] : [];
    return values.map((value, i) => ({ value, title: str(titles[i]) ?? String(value), description: str(descriptions[i]) }));
  }
  const branches = isConstBranches(s['oneOf']) ? s['oneOf'] : isConstBranches(s['anyOf']) ? s['anyOf'] : [];
  return branches.filter(isObject).map((o) => ({
    value: o['const'] ?? null,
    title: str(o['title']) ?? String(o['const']),
    description: str(o['description']),
  }));
}

/** The order of an object's properties: `x-order` first, then the schema's own order. */
function orderedKeys(s: JsonObject, props: JsonObject): string[] {
  const keys = Object.keys(props);
  const declared = s['x-order'];
  const order = Array.isArray(declared) ? declared.filter((k): k is string => typeof k === 'string') : [];
  return [...order.filter((k) => keys.includes(k)), ...keys.filter((k) => !order.includes(k))];
}

/** A branch condition on a discriminator: `{properties: {kind: {const: 'a'}}}` → kind is 'a'. */
function conditionOf(test: JsonValue | undefined, parent: readonly string[], negate: boolean): Condition | null {
  if (!isObject(test) || !isObject(test['properties'])) return null;
  for (const [key, cond] of Object.entries(test['properties'])) {
    if (!isObject(cond)) continue;
    const values = 'const' in cond ? [cond['const']] : Array.isArray(cond['enum']) ? cond['enum'] : null;
    if (values) return { path: [...parent, key], values, negate };
    if (cond['type'] === 'string' && typeof cond['minLength'] === 'number'
      && Array.isArray(test['required']) && test['required'].includes(key)) {
      return { path: [...parent, key], values: [], negate, minLength: cond['minLength'] };
    }
  }
  return null;
}

/** `metadata.kubeconfigContexts` → `['kubeconfigContexts']`; anything not under `metadata.` is ignored. */
function optionsPath(v: JsonValue | undefined): string[] | null {
  const path = str(v);
  if (!path?.startsWith('metadata.')) return null;
  const parts = path.slice('metadata.'.length).split('.').filter(Boolean);
  return parts.length ? parts : null;
}

/** A multi-line secret (`x-widget: textarea | code`, or with an `x-language`): a kubeconfig, a JSON key, a PEM. */
function isMultiline(s: JsonObject): boolean {
  const widget = str(s['x-widget']);
  return widget === 'textarea' || widget === 'code' || str(s['x-language']) !== null;
}

function envMap(v: JsonValue | undefined): Record<string, JsonValue> {
  return isObject(v) ? { ...v } : {};
}

function envLists(v: JsonValue | undefined): Record<string, readonly JsonValue[]> {
  const out: Record<string, readonly JsonValue[]> = {};
  if (isObject(v)) for (const [env, values] of Object.entries(v)) if (Array.isArray(values)) out[env] = values;
  return out;
}

function requiredOf(s: JsonObject): Set<string> {
  const r = s['required'];
  return new Set(Array.isArray(r) ? r.filter((k): k is string => typeof k === 'string') : []);
}

function branchesOf(s: JsonObject): JsonObject[] {
  const b = Array.isArray(s['oneOf']) ? s['oneOf'] : Array.isArray(s['anyOf']) ? s['anyOf'] : [];
  return b.filter(isObject);
}

/**
 * Short scalar fields share a row, up to three (Host · Port · Database; Statement timeout · Pool size): numbers and
 * text fields without a hint; everything with its own block (select with a hint, tags, textarea, cards) stands alone.
 */
export function layoutRows(children: readonly SchemaNode[]): SchemaNode[][] {
  const rows: SchemaNode[][] = [];
  let run: SchemaNode[] = [];
  const short = (n: SchemaNode) => (n.kind === 'number' || (n.kind === 'text' && n.description === null)) && n.when.length === 0;
  for (const n of children) {
    if (!short(n)) {
      if (run.length) rows.push(run);
      run = [];
      rows.push([n]);
      continue;
    }
    if (run.length === 3) {
      rows.push(run);
      run = [];
    }
    run.push(n);
  }
  if (run.length) rows.push(run);
  return rows;
}

function compileNode(
  key: string | null,
  raw: JsonObject,
  parent: readonly string[],
  required: boolean,
  root: JsonObject,
  seen: ReadonlySet<string>,
  when: readonly Condition[],
  inheritedSecret: string | null,
): SchemaNode {
  const { schema: s, seen: next } = deref(raw, root, seen);
  const path = key === null ? [...parent] : [...parent, key];
  const kind = kindOf(s);
  const ownSecret = str(s['x-gateway-secret']);
  const children: SchemaNode[] = [];
  const has = (k: string, depth: number) => children.some((c) => c.key === k && c.when.length === depth);
  const add = (k: string, child: JsonValue | undefined, req: boolean, cond: readonly Condition[]) => {
    if (isObject(child)) children.push(compileNode(k, child, path, req, root, next, cond, ownSecret ?? inheritedSecret));
  };
  if (kind === 'object') {
    const props = isObject(s['properties']) ? s['properties'] : {};
    const req = requiredOf(s);
    for (const k of orderedKeys(s, props)) add(k, props[k], req.has(k), when);
    // allOf parts merge their fields in.
    for (const part of Array.isArray(s['allOf']) ? s['allOf'] : []) {
      if (!isObject(part) || !isObject(part['properties'])) continue;
      const preq = requiredOf(part);
      for (const [k, child] of Object.entries(part['properties'])) if (!has(k, when.length)) add(k, child, preq.has(k), when);
    }
    // if / then / else on a discriminator of this object: on the object itself, or one per allOf item (the usual way
    // to write several conditionals). Fields a branch declares show only in that branch, right after the field that
    // chooses it and the branch fields before them (Kubernetes: Sign in with, then its server, token or kubeconfig).
    const chosenBy = (disc: readonly string[]): number => {
      let at = -1;
      children.forEach((c, i) => {
        if (samePath(c.path, disc) || c.when.some((w) => samePath(w.path, disc))) at = i;
      });
      return at < 0 ? children.length : at + 1;
    };
    const addConditionals = (source: JsonObject, inherited: readonly Condition[]) => {
      for (const c of [source, ...(Array.isArray(source['allOf']) ? source['allOf'] : [])].filter(isObject)) {
        const ifCond = conditionOf(c['if'], path, false);
        if (!ifCond) continue;
        for (const [branch, negate] of [
          ['then', false],
          ['else', true],
        ] as const) {
          const b = c[branch];
          if (!isObject(b) || !isObject(b['properties'])) continue;
          const breq = requiredOf(b);
          for (const [k, child] of Object.entries(b['properties'])) {
            if (has(k, inherited.length) || !isObject(child)) continue;
            const node = compileNode(k, child, path, breq.has(k), root, next, [...inherited, { ...ifCond, negate }],
              ownSecret ?? inheritedSecret);
            children.splice(chosenBy(ifCond.path), 0, node);
          }
        }
      }
    };
    addConditionals(s, when);
    // oneOf / anyOf branches keyed by a const discriminator: each branch's own fields show for its value; the
    // discriminator becomes a choice of the branches when the object doesn't declare it itself.
    const branches = branchesOf(s);
    const conds = branches.map((b) => conditionOf(b, path, false));
    const disc = conds.find((c) => c)?.path.at(-1);
    if (disc && !has(disc, when.length)) {
      const values = conds.map((c) => c?.values[0] ?? null);
      const firstProps = branches[0]['properties'];
      const first = isObject(firstProps) && isObject(firstProps[disc]) ? firstProps[disc] : {};
      const described = branches.every((b) => str(b['description']));
      children.unshift(
        compileNode(
          disc,
          {
            type: typeof values[0] === 'number' ? 'integer' : 'string',
            title: str(first['title']) ?? disc,
            enum: values,
            'x-enumTitles': branches.map((b, i) => str(b['title']) ?? String(values[i])),
            ...(described ? { 'x-enumDescriptions': branches.map((b) => b['description'] ?? null) } : {}),
          },
          path,
          true,
          root,
          next,
          when,
          inheritedSecret,
        ),
      );
    }
    branches.forEach((b, i) => {
      const cond = conds[i];
      const bprops = b['properties'];
      if (!isObject(bprops)) return;
      const breq = requiredOf(b);
      for (const [k, child] of Object.entries(bprops)) {
        if (k === disc || has(k, when.length)) continue;
        add(k, child, breq.has(k), cond ? [...when, cond] : when);
      }
      // A backend branch can contain a second choice whose fields retain both visibility conditions.
      addConditionals(b, cond ? [...when, cond] : when);
    });
  }
  const optional = !required && s['default'] === undefined && kind !== 'checkbox' && kind !== 'object' && kind !== 'number';
  const secret = kind === 'secret' ? (ownSecret ?? (inheritedSecret === 'login' ? 'value' : inheritedSecret) ?? 'value') : ownSecret;
  return {
    key: key ?? '',
    path,
    pointer: pointerOf(path),
    kind,
    title: str(s['title']) ?? key ?? '',
    description: str(s['description']),
    required,
    optional,
    mono: kind === 'text' || kind === 'number' || kind === 'tags',
    schema: s,
    unit: str(s['x-unit']),
    options: optionsOf(s),
    secret,
    language: str(s['x-language']),
    stricter: str(s['x-stricter']),
    confirm: s['x-confirm'] === 'warning' || s['x-confirm'] === 'danger' ? s['x-confirm'] : null,
    envDefault: envMap(s['x-env-default']),
    forbiddenEnv: Array.isArray(s['x-forbidden-env']) ? s['x-forbidden-env'].filter((e): e is string => typeof e === 'string') : [],
    forbiddenValues: envLists(s['x-forbidden-env-values']),
    optionsFrom: optionsPath(s['x-options-from']),
    multiline: kind === 'secret' && (isMultiline(s) || secret === 'pem' || secret === 'binary'),
    collapsed: s['x-collapsed'] === true,
    when,
    children,
    rows: kind === 'object' ? layoutRows(children) : [],
  };
}

/**
 * The root's own fields (a flat schema: Kubernetes, an MCP server; or the loose fields before the sections) as one
 * pseudo-section with the root's pointer `''`, so they get a legend and rows like the others.
 */
export function rootSection(fields: readonly SchemaNode[], title: string, rows: readonly (readonly SchemaNode[])[] = layoutRows(fields)): SchemaNode {
  return {
    key: '',
    path: [],
    pointer: '',
    kind: 'object',
    title,
    description: null,
    required: true,
    optional: false,
    mono: false,
    schema: {},
    unit: null,
    options: [],
    secret: null,
    language: null,
    stricter: null,
    confirm: null,
    envDefault: {},
    forbiddenEnv: [],
    forbiddenValues: {},
    optionsFrom: null,
    multiline: false,
    collapsed: false,
    when: [],
    children: [...fields],
    rows,
  };
}

/** Compiles a connector (or settings, MCP server, template) schema into the form's sections and fields. */
export function compileSchema(input: JsonValue): CompiledForm {
  const schema = normalizeSchema(input);
  if (!isObject(schema)) return { sections: [], loose: [], all: [] };
  const root = compileNode(null, { ...schema, type: 'object' }, [], true, schema, new Set(), [], null);
  const { sections, loose } = displaySections(root.children);
  const all: SchemaNode[] = [];
  const walk = (n: SchemaNode) => {
    all.push(n);
    n.children.forEach(walk);
  };
  [...loose, ...sections].forEach(walk);
  return { sections, loose, all };
}

/** `x-section` groups direct fields, including root fields, without changing their saved config paths. */
function displaySections(nodes: readonly SchemaNode[]): Pick<CompiledForm, 'sections' | 'loose'> {
  const sections = nodes.filter((n) => n.kind === 'object');
  const loose: SchemaNode[] = [];
  const children = new Map(sections.map((section) => [section, [] as SchemaNode[]]));
  const destinations = new Map<string, SchemaNode | null>();
  for (const section of sections) {
    destinations.set(section.pointer, destinations.has(section.pointer) ? null : section);
  }
  for (const section of sections) {
    for (const node of section.children) {
      const target = node.kind !== 'object' ? str(node.schema['x-section']) : null;
      children.get(destinations.get(target ?? '') ?? section)!.push(node);
    }
  }
  for (const node of nodes.filter((n) => n.kind !== 'object')) {
    const target = destinations.get(str(node.schema['x-section']) ?? '');
    if (target) children.get(target)!.push(node);
    else loose.push(node);
  }
  return {
    loose,
    sections: sections.map((section) => {
      const fields = children.get(section)!;
      if (fields.length === section.children.length && fields.every((node, i) => node === section.children[i])) return section;
      return { ...section, children: fields, rows: layoutRows(fields) };
    }),
  };
}

/** Display ancestry can differ from config paths when a field uses `x-section`. */
export function containsNode(parent: SchemaNode, node: SchemaNode): boolean {
  return parent === node || parent.children.some((child) => containsNode(child, node));
}

/**
 * The model a form starts from: the current values (edit, duplicate) or the `default`s, every key present (`null`
 * when there's nothing) so each field has its place in the form tree, and never a secret: those live apart. With an
 * `env`, a field's `x-env-default` for it replaces its `default` (a new prod connection starts with TLS on).
 */
export function initialValue(input: JsonValue, current: JsonValue = null, env: string | null = null): JsonObject {
  const schema = normalizeSchema(input);
  const root = isObject(schema) ? schema : {};
  const build = (s: JsonValue, cur: JsonValue | undefined, seen: ReadonlySet<string>): JsonValue => {
    if (!isObject(s)) return cur ?? null;
    const { schema: d, seen: next } = deref(s, root, seen);
    const kind = kindOf(d);
    if (kind === 'secret') return null;
    if (kind === 'object') {
      const c = isObject(cur) ? cur : {};
      const out: { [key: string]: JsonValue } = {};
      // The conditionals of allOf items too: a field only its branch declares (SSH vault, k8s token) has its default
      // there; cleanValue drops it again while its branch is hidden.
      const allOf = (Array.isArray(d['allOf']) ? d['allOf'] : []).filter(isObject);
      const declaredBranches = branchesOf(d);
      const firstCondition = declaredBranches[0] ? conditionOf(declaredBranches[0], [], false) : null;
      const selector = firstCondition?.path[0];
      const property = selector && isObject(d['properties']) ? d['properties'][selector] : null;
      const selected = selector ? (c[selector] ?? (isObject(property) ? property['default'] : null)
        ?? firstCondition?.values[0]) : null;
      // Shared objects (Logs auth/credentials) take defaults from the selected backend, then gain the other
      // branches' model paths. This keeps every control addressable without leaking an inactive branch's defaults.
      const branches = [...declaredBranches].sort((a, b) =>
        Number(conditionOf(b, [], false)?.values.includes(selected ?? null) ?? false)
        - Number(conditionOf(a, [], false)?.values.includes(selected ?? null) ?? false));
      const branchConditions = branches.flatMap((b) => [b, ...(Array.isArray(b['allOf']) ? b['allOf'] : [])])
        .filter(isObject).flatMap((p) => [p['then'], p['else']]);
      const parts = [d, ...allOf, ...allOf.flatMap((p) => [p['then'], p['else']]), ...branches,
        ...branchConditions, d['then'], d['else']];
      for (const part of parts) {
        if (!isObject(part) || !isObject(part['properties'])) continue;
        for (const [k, child] of Object.entries(part['properties'])) {
          if (out[k] === undefined || out[k] === null) out[k] = build(child, c[k], next);
          else if (isObject(out[k]) && isObject(child) && kindOf(deref(child, root, next).schema) === 'object') {
            out[k] = build(child, out[k], next);
          }
        }
      }
      // A discriminator only the branches declare starts at the first branch, as its select shows it.
      const firstBranch = branchesOf(d)[0];
      const cond = firstBranch ? conditionOf(firstBranch, [], false) : null;
      if (cond && (out[cond.path[0]] === null || out[cond.path[0]] === undefined)) out[cond.path[0]] = cond.values[0];
      // Values the schema doesn't list (an older config) are kept as they are: the core decides about them.
      for (const [k, v] of Object.entries(c)) if (!(k in out)) out[k] = v;
      return out;
    }
    if (cur !== undefined && cur !== null) return cur;
    const envDefault = env !== null && isObject(d['x-env-default']) ? d['x-env-default'][env] : undefined;
    if (envDefault !== undefined) return envDefault;
    if (d['default'] !== undefined) return d['default'];
    return kind === 'tags' ? [] : null;
  };
  const v = build({ ...root, type: 'object' }, current ?? {}, new Set());
  return isObject(v) ? v : {};
}

const samePath = (a: readonly string[], b: readonly string[]): boolean => a.length === b.length && a.every((x, i) => x === b[i]);

/** Reads the value at a path of the model (`null` when the path doesn't exist). */
export function getAt(model: JsonValue, path: readonly string[]): JsonValue {
  let v: JsonValue = model;
  for (const k of path) v = isObject(v) ? (v[k] ?? null) : null;
  return v;
}

/** A copy of the model with one value set; objects on the way are created. */
export function setAt(model: JsonObject, path: readonly string[], value: JsonValue): JsonObject {
  if (!path.length) return model;
  const [head, ...rest] = path;
  const child = model[head];
  return { ...model, [head]: rest.length ? setAt(isObject(child) ? child : {}, rest, value) : value };
}

/** Whether a node shows for this model: all of its branch conditions hold. */
export function isVisible(node: SchemaNode, model: JsonValue): boolean {
  return node.when.every((c) => {
    const v = getAt(model, c.path);
    const hit = c.minLength === undefined ? c.values.some((x) => x === v)
      : typeof v === 'string' && [...v].length >= c.minLength;
    return c.negate ? !hit : hit;
  });
}

/** The same JSON value however its keys are ordered (`5` and `5.0` are one number in JS already). */
export function sameJson(a: JsonValue | undefined, b: JsonValue | undefined): boolean {
  const canon = (v: JsonValue | undefined): string =>
    v === undefined
      ? 'null'
      : Array.isArray(v)
        ? `[${v.map(canon).join(',')}]`
        : isObject(v)
          ? `{${Object.keys(v)
              .sort()
              .map((k) => `${JSON.stringify(k)}:${canon(v[k])}`)
              .join(',')}}`
          : JSON.stringify(v);
  return canon(a) === canon(b);
}

/** The value a field starts with on `env`: its `x-env-default` there, else its `default` (`null` without one). */
export function defaultOn(node: SchemaNode, env: string): JsonValue {
  const d = node.envDefault[env];
  if (d !== undefined) return d;
  return node.schema['default'] ?? (node.kind === 'tags' ? [] : null);
}

/**
 * Whether `value` is locked out on `env`, as the core's `EnvLocks` decides: one of the field's
 * `x-forbidden-env-values` there, or anything but its `default` where `x-forbidden-env` names the env (an empty
 * field is always allowed).
 */
export function isForbidden(node: SchemaNode, env: string, value: JsonValue): boolean {
  if (value === null) return false;
  if ((node.forbiddenValues[env] ?? []).some((v) => sameJson(v, value))) return true;
  return node.forbiddenEnv.includes(env) && !sameJson(node.schema['default'] ?? null, value);
}

/** What a locked-out value goes back to on `env`: the env's default when that is allowed, else the plain default. */
export function allowedValue(node: SchemaNode, env: string): JsonValue {
  const d = defaultOn(node, env);
  if (!isForbidden(node, env, d)) return d;
  const plain = node.schema['default'] ?? null;
  return isForbidden(node, env, plain) ? null : plain;
}

/**
 * What goes to the core: the model without the values of hidden branches, without empty values (`null`, `''`, an
 * empty list nothing required) and without secrets (they were never in it; a stray value is dropped all the same).
 */
export function cleanValue(form: CompiledForm, model: JsonObject): JsonObject {
  let out = model;
  // A field another branch shows at the same place keeps its value (Logs: Loki's url and OpenSearch's url are one
  // key); a hidden object goes as a whole, and its fields, after it in pre-order, don't put an empty one back.
  const shown = new Set(form.all.filter((n) => n.kind !== 'secret' && isVisible(n, model)).map((n) => n.pointer));
  const dropped: string[] = [];
  for (const n of form.all) {
    if (n.kind !== 'secret' && shown.has(n.pointer)) continue;
    if (dropped.some((p) => n.pointer.startsWith(`${p}/`))) continue;
    out = setAt(out, n.path, null);
    dropped.push(n.pointer);
  }
  const requiredLists = new Set(form.all.filter((n) => n.kind === 'tags' && n.required && isVisible(n, model))
    .map((n) => n.pointer));
  // An empty object can exist solely as the display destination for flat x-section fields.
  const displayGroups = new Set(form.sections.filter((n) => isObject(n.schema['properties'])
    && !Object.keys(n.schema['properties']).length && n.children.some((c) => c.schema['x-section'] === n.pointer))
    .map((n) => n.pointer));
  const strip = (v: JsonValue, pointer: string): JsonValue | undefined => {
    if (v === null || v === '') return undefined;
    if (Array.isArray(v)) return v.length || requiredLists.has(pointer) ? v : undefined;
    if (!isObject(v)) return v;
    const o: { [key: string]: JsonValue } = {};
    for (const [k, x] of Object.entries(v)) {
      const kept = strip(x, `${pointer}/${escapeKey(k)}`);
      if (kept !== undefined) o[k] = kept;
    }
    return !Object.keys(o).length && displayGroups.has(pointer) ? undefined : o;
  };
  const cleaned = strip(out, '');
  return isObject(cleaned) ? cleaned : {};
}

/** "Schema allowlist" → "schema": the noun the tag buttons name ("Remove schema public", "Add schema"). */
export function tagNoun(node: SchemaNode): string {
  const items = node.schema['items'];
  const title = isObject(items) ? str(items['title']) : null;
  if (title) return title.toLowerCase();
  return (node.title.split(/\s+/)[0] ?? 'value').toLowerCase().replace(/s$/, '');
}

/** The example a number error offers ("Enter a whole number, for example 5432"). */
export function exampleNumber(node: SchemaNode): number {
  const d = node.schema['default'];
  if (typeof d === 'number') return d;
  const min = node.schema['minimum'];
  return typeof min === 'number' ? min : 1;
}
