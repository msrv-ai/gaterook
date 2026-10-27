import { DOCUMENT, Injector, Signal, WritableSignal, afterNextRender, computed, inject, signal, untracked } from '@angular/core';
import { FieldTree, SchemaPath, SchemaPathTree, form, hidden, required, validate } from '@angular/forms/signals';
import { ApiError } from '../core/api/api-error';
import { SecretInputs, StoredSecret } from '../core/api/connections.api';
import { JsonObject, JsonValue } from '../core/api/types';
import {
  CompiledForm,
  FieldOption,
  SchemaNode,
  allowedValue,
  cleanValue,
  compileSchema,
  containsNode,
  defaultOn,
  getAt,
  initialValue,
  isForbidden,
  isVisible,
  sameJson,
  setAt,
} from './compile';
import { jsonProblem, requiredMessage, valueProblem } from './messages';

/** Sources whose secret is a reference the core resolves at call time (the value never passes through here). */
export const REFERENCE_SOURCES: ReadonlySet<string> = new Set(['vault', 'gcp-sm', 'aws-sm', 'dynamic']);

/** What a secret field asks its page to do (the API calls are the page's: replace a version, test a reference). */
export type SecretAction =
  | { readonly kind: 'save'; readonly node: SchemaNode; readonly secretId: string | null; readonly value: string }
  | { readonly kind: 'rotate'; readonly node: SchemaNode; readonly secretId: string | null }
  | { readonly kind: 'test'; readonly node: SchemaNode; readonly ref: string };

export interface SchemaFormOptions {
  /** Ids of the controls: `<prefix>-connection-host` (and `-hint`, `-err`); distinct per form on a page. */
  readonly idPrefix: string;
  /** The current config (edit, duplicate); `null` for a new one (the schema's defaults). */
  readonly current?: JsonObject | null;
  /** Stored secrets of the connection being edited, by field pointer: they show as Encrypted / a reference. */
  readonly stored?: readonly StoredSecret[];
  /** Where the config sits in the request, for the core's JSON Pointers (`/config/connection/host`). */
  readonly pointerPrefix?: string;
  /** Collapsed sections (by pointer) that start open. */
  readonly expanded?: readonly string[];
  /** Stored secrets (by field pointer) that start in the editing state (after Replace). */
  readonly replacing?: readonly string[];
  /** `x-stricter` fields get the "Policy may tighten" badge (off where the section already says so). */
  readonly stricterBadges?: boolean;
  /** The environment the form opens for: a new config starts with its `x-env-default`s, its locks hold. */
  readonly env?: string | null;
  /** The connection's metadata the core found (`x-options-from: metadata.kubeconfigContexts`), if any. */
  readonly metadata?: JsonObject | null;
  readonly injector?: Injector;
}

/** A field with `x-confirm` whose value differs from the one the form opened with: Save asks first. */
export interface ConfirmChange {
  readonly node: SchemaNode;
  readonly tone: 'warning' | 'danger';
  readonly from: JsonValue;
  readonly to: JsonValue;
}

interface ServerError {
  readonly pointer: string;
  readonly message: string;
  /** The value it was about: the error goes once the user changes it. */
  readonly value: JsonValue;
}

/**
 * The state of one schema-rendered form (ARCHITECTURE.md §8.3): the config as a Signal Forms model compiled from the
 * JSON Schema, the secrets apart from it (typed values or references, by field pointer, never in the model), the
 * server's errors by JSON Pointer, and `submit()` that shows the errors and moves focus to the first one.
 * Created with {@link createSchemaForm}; rendered by `gw-schema-section` / `gw-schema-fields`.
 */
export class SchemaForm {
  readonly compiled: CompiledForm;
  readonly idPrefix: string;
  readonly model: WritableSignal<JsonObject>;
  /**
   * The Signal Forms tree over the model. Typed over `Record<string, unknown>`: the fields come from a schema at run
   * time, and the recursive `JsonValue` would only make the compiler unfold the tree's types without end.
   */
  readonly tree: FieldTree<Record<string, unknown>>;
  /** Errors show after the first Save (kit "Validation after submit"), then follow the input live. */
  readonly submitted = signal(false);
  /** The chosen secret source (`local`, `vault`, `gcp-sm`, `aws-sm`, `dynamic`). */
  readonly source = signal('local');
  /** What is typed into each secret field: a value (`local`) or a reference URI / role. */
  readonly secrets = signal<Readonly<Record<string, string>>>({});
  /** Stored secrets the user chose to replace (their field shows the editing state). */
  readonly replacing = signal<ReadonlySet<string>>(new Set());
  /** Collapsed sections (`x-collapsed`) the user, or an error inside, opened. */
  readonly expanded = signal<ReadonlySet<string>>(new Set());
  /** The text of each JSON field while it is typed (the model holds the parsed value only). */
  readonly jsonText = signal<Readonly<Record<string, string>>>({});
  private readonly storedState = signal<ReadonlyMap<string, StoredSecret>>(new Map());
  /**
   * The stored secrets by field pointer. A signal read: a page that replaced a version updates it with
   * {@link setStored} and the field shows the new version without the form being rebuilt (which would drop edits).
   */
  get stored(): ReadonlyMap<string, StoredSecret> {
    return this.storedState();
  }
  readonly stricterBadges: boolean;
  /** The object secret buttons name ("Replace password for dev-db"): the connection's name as it is typed. */
  readonly subject = signal('');
  /**
   * The first connection of a first run (kit connection-new `?state=first-run`): the fields speak to a beginner
   * ("Encrypted on this computer when you save."). Set by the page's form; the form isn't rebuilt for it.
   */
  readonly beginner = signal(false);
  private readonly envState = signal('dev');
  /**
   * The environment the form is for: reference placeholders (`vault://kv/dev/…`), the `x-env-default`s a new value
   * starts with and the `x-forbidden-env(-values)` locks. Changed with {@link setEnv}.
   */
  readonly env: Signal<string> = this.envState.asReadonly();
  /** The connection's metadata (`x-options-from`): the page sets it again when a test finds new facts. */
  readonly metadata = signal<JsonObject | null>(null);
  /** The model the form opened with: what an `x-confirm` change is measured against. */
  private readonly initial: JsonObject;
  /** The values came from a stored config (edit, duplicate): an env change only tightens them. */
  private readonly fromStored: boolean;
  private readonly listeners = new Set<(action: SecretAction) => void>();
  private readonly jsonErrors = signal<Readonly<Record<string, string>>>({});
  private readonly serverErrors = signal<readonly ServerError[]>([]);
  private readonly pointerPrefix: string;
  private readonly injector: Injector;
  private readonly doc: Document;

  /** The config as the core gets it: hidden branches, empty values and secrets left out. */
  readonly value: Signal<JsonObject>;

  constructor(schema: JsonObject, options: SchemaFormOptions & { readonly injector: Injector }) {
    this.compiled = compileSchema(schema);
    this.idPrefix = options.idPrefix;
    this.pointerPrefix = options.pointerPrefix ?? '';
    this.injector = options.injector;
    this.doc = options.injector.get(DOCUMENT);
    this.storedState.set(new Map((options.stored ?? []).map((s) => [s.field, s])));
    this.stricterBadges = options.stricterBadges ?? true;
    this.expanded.set(new Set(options.expanded ?? []));
    this.replacing.set(new Set(options.replacing ?? []));
    const firstRef = (options.stored ?? []).find((s) => s.state === 'reference');
    if (firstRef?.source) this.source.set(firstRef.source === 'short-lived' ? 'dynamic' : firstRef.source);
    const env = options.env ?? 'dev';
    this.envState.set(env);
    this.metadata.set(options.metadata ?? null);
    this.fromStored = !!options.current;
    // A new config starts with the env's defaults (TLS on for prod); a stored one keeps its values.
    this.model = signal(this.blankText(initialValue(schema, options.current ?? null, options.current ? null : env)));
    this.initial = this.model();
    this.tree = form(this.model as unknown as WritableSignal<Record<string, unknown>>, (p) => this.rules(p), { injector: options.injector });
    this.value = computed(() => cleanValue(this.compiled, this.model()));
    this.enforceLocks(env);
  }

  /**
   * A text field without a value holds `''`, not `null`: Signal Forms reads a text input bound to `null` as a number
   * field and refuses "db.dev.internal" as a parse error, so the model would never get what is typed. Empty strings
   * leave the config like nulls do ({@link cleanValue} drops both).
   */
  private blankText(model: JsonObject): JsonObject {
    let out = model;
    for (const node of this.compiled.all) {
      const text = node.kind === 'text' || (node.kind === 'select' && !!node.optionsFrom);
      if (text && getAt(out, node.path) === null && (node.path.length === 1 || getAt(out, node.path.slice(0, -1)) !== null)) {
        out = setAt(out, node.path, '');
      }
    }
    return out;
  }

  // ─── Fields ───

  /** The Signal Forms field of a node (its value, touched, errors). */
  field(node: SchemaNode): FieldTree<JsonValue> {
    let f: unknown = this.tree;
    for (const k of node.path) f = (f as Record<string, unknown>)[k];
    return f as FieldTree<JsonValue>;
  }

  id(node: SchemaNode): string {
    return `${this.idPrefix}-${node.path.join('-')}`;
  }

  visible(node: SchemaNode): boolean {
    return isVisible(node, this.model());
  }

  /** A value the person chose: it is theirs from now on, so an env change no longer moves it. */
  set(node: SchemaNode, value: JsonValue): void {
    const f = this.field(node)();
    f.value.set(value);
    f.markAsDirty();
  }

  touch(node: SchemaNode): void {
    this.field(node)().markAsTouched();
  }

  /** The message under a field: the core's (until the value changes), else the first rule it breaks after Save. */
  error(node: SchemaNode): string | null {
    if (node.kind === 'object') return null;
    const value = node.kind === 'secret' ? (this.secrets()[node.pointer] ?? '') : getAt(this.model(), node.path);
    const server = this.serverErrors().find((e) => e.pointer === node.pointer && e.value === value);
    if (server) return server.message;
    if (!this.submitted() || !this.visible(node)) return null;
    if (node.kind === 'secret') return this.secretProblem(node);
    return this.field(node)().errors()[0]?.message ?? null;
  }

  // ─── Environment: x-env-default and the prod locks ───

  /**
   * Moves the form to another environment (ARCHITECTURE.md §8.3): every field the person hasn't changed that still
   * holds the old env's default takes the new env's (`x-env-default`: TLS on, `secondaryPreferred` on prod), and
   * values the new env forbids (`x-forbidden-env(-values)`) go back to an allowed default, as the core would refuse
   * them on save. A stored config is only tightened: a field without a default for the new env keeps its value.
   */
  setEnv(next: string): void {
    const prev = untracked(this.envState);
    if (prev === next) return;
    this.envState.set(next);
    untracked(() => {
      let model = this.model();
      for (const node of this.compiled.all) {
        if (node.kind === 'object' || node.kind === 'secret' || !Object.keys(node.envDefault).length) continue;
        if (this.dirty(node)) continue;
        const before = defaultOn(node, prev);
        const after = defaultOn(node, next);
        const now = getAt(model, node.path);
        if (sameJson(before, after) || !(sameJson(now, before) || (now === '' && before === null))) continue;
        if (this.fromStored && node.envDefault[next] === undefined) continue;
        model = setAt(model, node.path, after);
      }
      this.model.set(model);
      this.enforceLocks(next);
    });
  }

  /** The option stays at its default on this env (`x-forbidden-env`): its control is off, with the reason. */
  locked(node: SchemaNode): boolean {
    return node.forbiddenEnv.includes(this.env());
  }

  /** An option of a choice that can't be picked on this env. */
  optionForbidden(node: SchemaNode, value: JsonValue): boolean {
    return isForbidden(node, this.env(), value);
  }

  /** Why options are off on this env ("Use plain HTTP isn't allowed on prod."), or null. */
  lockReason(node: SchemaNode): string | null {
    const env = this.env();
    if (node.kind === 'checkbox') return this.locked(node) ? `Not allowed on ${env}.` : null;
    const titles = this.optionsOf(node)
      .filter((o) => isForbidden(node, env, o.value))
      .map((o) => o.title);
    if (!titles.length) return null;
    const list = titles.length === 1 ? titles[0] : `${titles.slice(0, -1).join(', ')} and ${titles.at(-1)}`;
    return `${list} ${titles.length === 1 ? "isn't" : "aren't"} allowed on ${env}.`;
  }

  /**
   * The options of a choice: the schema's own, or for `x-options-from` the connection's metadata (a list of names,
   * of `{name, server}` objects, or a map name → server). A current value the metadata doesn't list is kept as an
   * option, so opening the form never drops it.
   */
  optionsOf(node: SchemaNode): readonly FieldOption[] {
    if (!node.optionsFrom) return node.options;
    const options = metadataOptions(getAt(this.metadata(), node.optionsFrom));
    const current = getAt(this.model(), node.path);
    if (typeof current === 'string' && current && !options.some((o) => o.value === current)) {
      options.push({ value: current, title: current, description: null });
    }
    return options;
  }

  /** Changed fields that ask before saving (`x-confirm`), in display order: the page shows them in a dialog. */
  readonly confirmations = computed<readonly ConfirmChange[]>(() => {
    const model = this.model();
    const out: ConfirmChange[] = [];
    for (const node of this.compiled.all) {
      if (!node.confirm || node.kind === 'object' || node.kind === 'secret' || !isVisible(node, model)) continue;
      const from = getAt(this.initial, node.path);
      const to = getAt(model, node.path);
      if (!sameJson(from, to)) out.push({ node, tone: node.confirm, from, to });
    }
    return out;
  });

  /** Values the env forbids go back to an allowed default (the core refuses them with a 422 otherwise). */
  private enforceLocks(env: string): void {
    let model = untracked(this.model);
    let changed = false;
    for (const node of this.compiled.all) {
      if (node.kind === 'object' || node.kind === 'secret') continue;
      if (!isForbidden(node, env, getAt(model, node.path))) continue;
      model = setAt(model, node.path, allowedValue(node, env));
      changed = true;
    }
    if (changed) this.model.set(model);
  }

  /** Whether the person changed the field (typed into it, or picked a value). */
  private dirty(node: SchemaNode): boolean {
    let f: unknown = this.tree;
    for (const k of node.path) {
      f = (f as Record<string, unknown> | undefined)?.[k];
      if (!f) return false;
    }
    return (f as FieldTree<JsonValue>)().dirty();
  }

  // ─── Secrets ───

  /** How a secret field shows: a new value, the stored version, its replacement, a reference. */
  secretState(node: SchemaNode): 'empty' | 'stored' | 'editing' | 'reference' {
    const stored = this.stored.get(node.pointer);
    if (REFERENCE_SOURCES.has(this.source())) return stored?.state === 'reference' && !this.replacing().has(node.pointer) ? 'reference' : 'empty';
    if (stored?.state === 'stored') return this.replacing().has(node.pointer) ? 'editing' : 'stored';
    return 'empty';
  }

  setSecret(node: SchemaNode, value: string): void {
    this.secrets.update((s) => ({ ...s, [node.pointer]: value }));
  }

  /**
   * The stored secrets after the page changed one (a replaced value is version N+1, a new reference): the fields
   * whose secret changed leave the editing state and forget what was typed; the rest of the form stays as edited.
   */
  setStored(stored: readonly StoredSecret[]): void {
    const before = untracked(this.storedState);
    const next = new Map(stored.map((s) => [s.field, s]));
    const changed = [...next.keys()].filter((f) => JSON.stringify(before.get(f)) !== JSON.stringify(next.get(f)));
    this.storedState.set(next);
    if (!changed.length) return;
    const saved = [...untracked(this.replacing)].filter((f) => changed.includes(f));
    this.replacing.update((r) => new Set([...r].filter((f) => !changed.includes(f))));
    // The new version is in: the field is back to its stored state, and the keyboard back on its Replace.
    const node = this.compiled.all.find((n) => n.pointer === saved[0]);
    if (node) this.focusSecret(node, false);
    this.secrets.update((s) => {
      const out = { ...s };
      for (const f of changed) delete out[f];
      return out;
    });
  }

  replace(node: SchemaNode, on: boolean): void {
    this.replacing.update((s) => {
      const next = new Set(s);
      if (on) next.add(node.pointer);
      else next.delete(node.pointer);
      return next;
    });
    if (!on) this.setSecret(node, '');
    this.focusSecret(node, on);
  }

  /**
   * Replace (or Change) puts the cursor in the field for the new value; Cancel brings it back to Replace (kit page
   * script): the button that had the focus is gone once the field changes state.
   */
  private focusSecret(node: SchemaNode, editing: boolean): void {
    afterNextRender(
      () => {
        const id = this.id(node);
        const target = editing
          ? this.doc.getElementById(id)
          : this.doc.getElementById(`${id}-l`)?.parentElement?.querySelector<HTMLElement>('.gw-secret__actions button');
        target?.focus();
      },
      { injector: this.injector },
    );
  }

  /** The `secrets` member of the request: one entry per filled secret field, never an empty one. */
  readonly secretInputs = computed<SecretInputs>(() => {
    const out: Record<string, SecretInputs[string]> = {};
    const reference = REFERENCE_SOURCES.has(this.source());
    for (const node of this.compiled.all) {
      if (node.kind !== 'secret' || !this.visible(node)) continue;
      const typed = this.secrets()[node.pointer]?.trim() ?? '';
      if (!typed) continue;
      out[node.pointer] = reference ? { source: 'reference', ref: typed } : { source: 'local', value: this.secrets()[node.pointer] };
    }
    return out;
  });

  /** The page's handler of secret actions; returns the unsubscribe. */
  onSecretAction(listener: (action: SecretAction) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  emit(action: SecretAction): void {
    for (const l of this.listeners) l(action);
  }

  private secretProblem(node: SchemaNode): string | null {
    const state = this.secretState(node);
    if (state === 'stored' || state === 'reference') return null;
    const typed = this.secrets()[node.pointer]?.trim() ?? '';
    if (REFERENCE_SOURCES.has(this.source())) {
      if (!typed) return this.source() === 'dynamic' ? 'Enter the Vault role.' : 'Enter the secret reference.';
      const scheme = this.source() === 'dynamic' ? null : `${this.source()}://`;
      if (scheme && !typed.startsWith(scheme)) return `A reference starts with ${scheme}. Copy it from the store.`;
      return null;
    }
    if (!typed && (node.required || state === 'editing')) return `${node.title} is required.`;
    return null;
  }

  // ─── JSON fields ───

  setJson(node: SchemaNode, text: string): void {
    this.jsonText.update((t) => ({ ...t, [node.pointer]: text }));
    try {
      const parsed: JsonValue = text.trim() ? JSON.parse(text) : null;
      this.jsonErrors.update((e) => omit(e, node.pointer));
      this.set(node, parsed);
    } catch (error) {
      this.jsonErrors.update((e) => ({ ...e, [node.pointer]: jsonProblem(error) }));
    }
  }

  jsonValue(node: SchemaNode): string {
    const typed = this.jsonText()[node.pointer];
    if (typed !== undefined) return typed;
    const v = getAt(this.model(), node.path);
    return v === null ? '' : JSON.stringify(v, null, 2);
  }

  // ─── Sections ───

  isExpanded(node: SchemaNode): boolean {
    return !node.collapsed || this.expanded().has(node.pointer);
  }

  setExpanded(node: SchemaNode, open: boolean): void {
    this.expanded.update((s) => {
      const next = new Set(s);
      if (open) next.add(node.pointer);
      else next.delete(node.pointer);
      return next;
    });
  }

  // ─── Submit ───

  /** Shows every message as after Save, without moving focus (a view that opens on a refused form). */
  showErrors(): void {
    this.submitted.set(true);
  }

  /** Fields with a message right now, in display order. */
  readonly invalidNodes = computed(() => this.compiled.all.filter((n) => this.error(n) !== null));

  /**
   * Save pressed: shows every message, opens the collapsed sections that hold one and moves focus to the first
   * invalid field. Returns whether the form may go to the core.
   */
  submit(ignorePointers: readonly string[] = []): boolean {
    this.submitted.set(true);
    untracked(() => this.tree().markAsTouched());
    const invalid = untracked(this.invalidNodes).filter((node) => !ignorePointers.includes(node.pointer));
    if (!invalid.length) return true;
    for (const section of this.compiled.sections) {
      if (section.collapsed && invalid.some((n) => containsNode(section, n))) this.setExpanded(section, true);
    }
    this.focus(invalid[0]);
    return false;
  }

  /** Focus on a field's control once the DOM shows it (a section may open first). */
  focus(node: SchemaNode): void {
    afterNextRender(
      () => {
        const el = this.doc.getElementById(this.id(node)) ?? this.doc.querySelector<HTMLElement>(`[name="${this.id(node)}"]`);
        el?.focus();
      },
      { injector: this.injector },
    );
  }

  /**
   * The core's 422 problem: each error whose pointer belongs to this form shows under its field (until the value
   * changes); the others (`/name`, `/env`) are returned for the caller to show. Focus goes to the first one here.
   */
  applyServerErrors(error: ApiError): ApiError['errors'] {
    const mine: ServerError[] = [];
    const others: ApiError['errors'][number][] = [];
    for (const e of error.errors) {
      const pointer = e.field.startsWith(this.pointerPrefix + '/') ? e.field.slice(this.pointerPrefix.length) : null;
      const node = pointer ? this.compiled.all.find((n) => n.pointer === pointer) : undefined;
      if (!node) {
        others.push(e);
        continue;
      }
      const value = node.kind === 'secret' ? (untracked(this.secrets)[node.pointer] ?? '') : getAt(untracked(this.model), node.path);
      mine.push({ pointer: node.pointer, message: e.message, value });
    }
    this.serverErrors.set(mine);
    const first = this.compiled.all.find((n) => mine.some((m) => m.pointer === n.pointer));
    if (first) {
      for (const section of this.compiled.sections) if (containsNode(section, first)) this.setExpanded(section, true);
      this.focus(first);
    }
    return others;
  }

  /** Back to the values the form started with (after Discard). */
  reset(current: JsonObject | null, schema: JsonObject): void {
    this.model.set(this.blankText(initialValue(schema, current, current ? null : untracked(this.envState))));
    this.secrets.set({});
    this.replacing.set(new Set());
    this.jsonText.set({});
    this.jsonErrors.set({});
    this.serverErrors.set([]);
    this.submitted.set(false);
    untracked(() => this.tree().reset());
    this.enforceLocks(untracked(this.envState));
  }

  // ─── Rules ───

  /** Signal Forms rules from the compiled nodes: required, the value checks, hidden for inactive branches. */
  private rules(p: SchemaPathTree<Record<string, unknown>>): void {
    for (const node of this.compiled.all) {
      if (node.kind === 'object' || node.kind === 'secret') continue;
      let sp: unknown = p;
      for (const k of node.path) sp = (sp as Record<string, unknown>)[k];
      const path = sp as SchemaPath<JsonValue>;
      const active = () => isVisible(node, this.model());
      if (node.when.length) hidden(path, () => !active());
      if (node.required && node.kind === 'tags') {
        // An empty list is a value to `required`; "at least one" is the rule here.
        validate(path, ({ value }) => {
          const v = value();
          return active() && (!Array.isArray(v) || v.length === 0) ? { kind: 'required', message: requiredMessage(node) } : null;
        });
      } else if (node.required) {
        required(path, { message: requiredMessage(node), when: () => active() });
      }
      validate(path, ({ value }) => {
        if (!active()) return null;
        const problem = node.kind === 'json' ? (this.jsonErrors()[node.pointer] ?? null) : valueProblem(node, value());
        return problem ? { kind: 'schema', message: problem } : null;
      });
      if (node.forbiddenEnv.length || Object.keys(node.forbiddenValues).length) {
        // The core's EnvLocks rule, said before Save: a locked value can't reach it from these controls, but a JSON
        // or text field can still hold one.
        validate(path, ({ value }) => {
          const env = this.env();
          return active() && isForbidden(node, env, value()) ? { kind: 'env', message: `${node.title} isn't allowed on ${env}.` } : null;
        });
      }
    }
  }
}

/** Options from connection metadata: `["kind-dev"]`, `[{name, server}]` or `{"kind-dev": "https://…"}`. */
function metadataOptions(v: JsonValue): FieldOption[] {
  const str = (x: JsonValue | undefined): string | null => (typeof x === 'string' && x ? x : null);
  const out: FieldOption[] = [];
  if (Array.isArray(v)) {
    for (const item of v) {
      if (typeof item === 'string' && item) out.push({ value: item, title: item, description: null });
      else if (item && typeof item === 'object' && !Array.isArray(item)) {
        const value = str(item['name']) ?? str(item['value']) ?? str(item['id']);
        if (value) out.push({ value, title: str(item['title']) ?? str(item['label']) ?? value, description: str(item['server']) ?? str(item['description']) });
      }
    }
  } else if (v && typeof v === 'object') {
    for (const [name, detail] of Object.entries(v)) {
      const description = typeof detail === 'string' ? detail : detail && typeof detail === 'object' && !Array.isArray(detail) ? str(detail['server']) : null;
      out.push({ value: name, title: name, description });
    }
  }
  return out;
}

function omit<T>(o: Readonly<Record<string, T>>, key: string): Record<string, T> {
  const out = { ...o };
  delete out[key];
  return out;
}

/** A schema form in the current injection context (a component's field initializer or constructor). */
export function createSchemaForm(schema: JsonObject, options: SchemaFormOptions): SchemaForm {
  return new SchemaForm(schema, { ...options, injector: options.injector ?? inject(Injector) });
}
