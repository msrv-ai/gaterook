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
  inUse,
  initialValue,
  isForbidden,
  isVisible,
  sameJson,
  setAt,
} from './compile';
import { jsonProblem, requiredMessage, valueProblem } from './messages';
import { REFERENCE_SCHEMES, isTargetKey, referenceKind, secretSourceId } from './secret-sources';

/**
 * A connected store a field's value may be read from, as the field's menu offers it: one per store, from the form
 * spec's `secretSources` (several Vaults: one each, named).
 */
export interface StoreOption {
  /** `vault`, `gcp-sm`, `aws-sm`. */
  readonly kind: string;
  /** The store's name, which the reference is bound to; `null` for a store the core picks itself. */
  readonly store: string | null;
  /** "HashiCorp Vault", or "Vault · vault-stage" with several. */
  readonly title: string;
  /** Its address ("https://vault.stage.example.test"), when known. */
  readonly detail: string | null;
  readonly available: boolean;
  readonly reason: string | null;
}

/** A saved reference remains configured while its store is disconnected or its credentials are leased. */
export function storedReference(secret: StoredSecret): boolean {
  return ['reference', 'short-lived', 'not-connected'].includes(secret.state)
    && !!secret.source && secret.source !== 'local';
}

/** What a secret field asks its page to do (the API calls are the page's: replace a version, test a reference). */
export type SecretAction =
  | { readonly kind: 'save'; readonly node: SchemaNode; readonly secretId: string | null; readonly value: string }
  | { readonly kind: 'rotate'; readonly node: SchemaNode; readonly secretId: string | null }
  | { readonly kind: 'test'; readonly node: SchemaNode; readonly ref: string; readonly store?: string };

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
  /**
   * `dynamic` for a connection whose stored login is short-lived Vault credentials: they stay as stored and their
   * controls are hidden. `local` otherwise: each field says where its own value comes from ({@link fieldKind}).
   */
  readonly source = signal('local');
  /**
   * What is typed into each secret field: a value, or a reference (`vault://kv/prod/db#password`) — a value that starts
   * with a store's scheme is read from that store at call time. Never in the model.
   */
  readonly secrets = signal<Readonly<Record<string, string>>>({});
  /** The connected stores the fields' menus offer ({@link StoreOption}): the page sets them from the form spec. */
  readonly stores = signal<readonly StoreOption[]>([]);
  /**
   * The store chosen in a field's menu, by field pointer: its reference is bound to that one. A field without one
   * reads from the only store of its kind, or from the first of several, asked in turn, that has it.
   */
  readonly fieldStores = signal<Readonly<Record<string, string>>>({});
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
    // Short-lived Vault credentials, also while their Vault is disconnected, stay as stored.
    if ((options.stored ?? []).some((s) => storedReference(s)
      && (s.state === 'short-lived' || secretSourceId(s.source ?? '') === 'dynamic'))) this.source.set('dynamic');
    const env = options.env ?? 'dev';
    this.envState.set(env);
    this.metadata.set(options.metadata ?? null);
    this.fromStored = !!options.current;
    // A new config starts with the env's defaults (TLS on for prod); a stored one keeps its values.
    this.model = signal(this.blankText(initialValue(schema, options.current ?? null, options.current ? null : env)));
    this.initial = this.model();
    this.tree = form(this.model as unknown as WritableSignal<Record<string, unknown>>, (p) => this.rules(p), { injector: options.injector });
    // A text field read from a store has its reference in `secrets`, not in the config (the core keeps it there).
    this.value = computed(() => cleanValue(this.compiled, this.withoutTextReferences(this.model()), this.filled()));
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
    return isVisible(node, this.model()) && !(this.source() === 'dynamic' && this.loginUsername(node));
  }

  /**
   * Whether the field must be filled in now: it is required, and each omittable object around it is in use (the type
   * of a sign-in that has a client ID; nothing while Sign-in is left empty).
   */
  needed(node: SchemaNode): boolean {
    return node.required && node.within.every((path) => inUse(this.compiled, path, this.model(), this.filled()));
  }

  loginUsername(node: SchemaNode): boolean {
    return this.loginField(node) && node.kind === 'text' && (node.key === 'user' || node.key === 'username');
  }

  // ─── Values read from a store ───

  /** Whether a field may be read from a store: a secret, or a text field ({@link textReferable}). */
  referable(node: SchemaNode): boolean {
    return node.kind === 'secret' || this.textReferable(node);
  }

  /**
   * A text field a reference may fill (`vault://kv/prod/db#username`), as the core reads them: not a field with a
   * unit, a list of options or a file picker, and never one that says where the credentials go (a host, a URL).
   */
  textReferable(node: SchemaNode): boolean {
    return node.kind === 'text' && !node.unit && !node.optionsFrom && !isTargetKey(node.key)
      && node.schema['x-file-picker'] === undefined;
  }

  /**
   * A field with the menu of where its value comes from ("Text" or a connected store): each secret, and each text
   * field next to one (a login's user, a client ID). Any other text field takes a reference typed into it too.
   */
  credential(node: SchemaNode): boolean {
    if (node.kind === 'secret') return true;
    if (!this.textReferable(node)) return false;
    const parent = node.path.slice(0, -1);
    return this.compiled.all.some((n) => n.kind === 'secret' && n.path.length === node.path.length
      && parent.every((part, i) => part === n.path[i]));
  }

  /** What is typed into a field: a secret's value or reference, a text field's value. */
  fieldText(node: SchemaNode): string {
    if (node.kind === 'secret') return this.secrets()[node.pointer] ?? '';
    const v = getAt(this.model(), node.path);
    return typeof v === 'string' ? v : '';
  }

  setFieldText(node: SchemaNode, text: string): void {
    if (node.kind === 'secret') this.setSecret(node, text);
    else this.set(node, text);
  }

  /** The kind of store a field's typed reference reads from (`vault`), or `null` while a value is typed. */
  fieldKind(node: SchemaNode): string | null {
    return this.referable(node) ? referenceKind(this.fieldText(node)) : null;
  }

  /** The reference typed into a field, as the core gets it; empty while a value is typed or only a scheme is. */
  fieldReference(node: SchemaNode): string {
    const kind = this.fieldKind(node);
    const typed = this.fieldText(node).trim();
    return kind && typed.length > REFERENCE_SCHEMES[kind].length ? typed : '';
  }

  /**
   * The store a field's typed reference is bound to, by name: the one chosen in its menu. `undefined` leaves it to
   * the core: the only store of its kind, or the first of several, asked in turn, that has it.
   */
  fieldStore(node: SchemaNode): string | undefined {
    const kind = this.fieldKind(node);
    const chosen = this.fieldStores()[node.pointer];
    return kind && chosen && this.stores().some((s) => s.kind === kind && s.store === chosen) ? chosen : undefined;
  }

  /** The connected stores of a kind, as a field's menu lists them. */
  storesOf(kind: string): readonly StoreOption[] {
    return this.stores().filter((s) => s.kind === kind);
  }

  /** Where a field's value comes from, for its menu: `null` for Text, else the store option it reads from. */
  fieldSource(node: SchemaNode): StoreOption | null {
    const kind = this.fieldKind(node);
    if (!kind) return null;
    const store = this.fieldStore(node);
    const options = this.storesOf(kind);
    return options.find((o) => o.store === (store ?? null)) ?? (!store && options.length === 1 ? options[0] : null)
      ?? { kind, store: null, title: kindTitle(kind), detail: null, available: true, reason: null };
  }

  /**
   * The person picked where a field's value comes from in its menu: Text empties a typed reference; a store puts
   * its scheme in front (`vault://`), in place of another store's. A typed secret is never kept in a reference,
   * where it would show.
   */
  chooseSource(node: SchemaNode, option: StoreOption | null): void {
    const text = this.fieldText(node);
    const kind = referenceKind(text);
    const stores = { ...untracked(this.fieldStores) };
    if (option?.store) stores[node.pointer] = option.store;
    else delete stores[node.pointer];
    this.fieldStores.set(stores);
    if (!option) {
      if (kind) this.setFieldText(node, '');
      return;
    }
    if (kind === option.kind) return;
    const rest = kind ? text.trimStart().slice(REFERENCE_SCHEMES[kind].length) : node.kind === 'secret' ? '' : text.trim();
    this.setFieldText(node, REFERENCE_SCHEMES[option.kind] + rest);
  }

  /** How a text field read from a store shows: its stored reference, or the field to type a value or reference in. */
  textState(node: SchemaNode): 'reference' | 'input' {
    const stored = this.stored.get(node.pointer);
    return stored && storedReference(stored) && !this.replacing().has(node.pointer) ? 'reference' : 'input';
  }

  /** The model without the text fields read from a store: their place in the stored config is the core's. */
  private withoutTextReferences(model: JsonObject): JsonObject {
    let out = model;
    for (const node of this.compiled.all) {
      if (this.textReferable(node) && referenceKind(stringAt(out, node.path))) out = setAt(out, node.path, null);
    }
    return out;
  }

  /** Login providers supply the username, but other fields such as MongoDB's authSource remain editable. */
  private loginField(node: SchemaNode): boolean {
    return this.compiled.all.some((parent) => parent.secret === 'login' && parent.kind === 'object'
      && parent.path.length + 1 === node.path.length && parent.path.every((part, i) => part === node.path[i]));
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
    if (node.kind === 'object' || !this.visible(node)) return null;
    const value = node.kind === 'secret' ? (this.secrets()[node.pointer] ?? '') : getAt(this.model(), node.path);
    const server = this.serverErrors().find((e) => e.pointer === node.pointer && e.value === value);
    return server ? server.message : this.problem(node);
  }

  /** The first of the form's own rules the field breaks after Save (never a message of the core). */
  private problem(node: SchemaNode): string | null {
    if (node.kind === 'object' || !this.visible(node) || !this.submitted()) return null;
    if (node.kind === 'secret') return this.secretProblem(node);
    const kind = this.fieldKind(node);
    if (kind) return this.referenceProblem(node, this.fieldText(node), kind);
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
    const replacing = this.replacing().has(node.pointer);
    // An import stages its own provider per field: the well shows it until the person changes it.
    if (stored?.staged && !replacing) return storedReference(stored) ? 'reference' : 'stored';
    // Change on a stored reference opens the field with it, to point elsewhere.
    if (stored && storedReference(stored)) return replacing ? 'empty' : 'reference';
    if (stored?.state === 'stored') return replacing ? 'editing' : 'stored';
    return 'empty';
  }

  setSecret(node: SchemaNode, value: string): void {
    this.secrets.update((s) => ({ ...s, [node.pointer]: value }));
  }

  /**
   * The fields something is in that the config doesn't carry, by pointer: a typed secret (a value or a reference), a
   * text field read from a store. Their objects stay in the config.
   */
  private readonly filled = computed<ReadonlySet<string>>(() => {
    const out = new Set(Object.entries(this.secrets()).filter(([, typed]) => typed.trim()).map(([pointer]) => pointer));
    for (const node of this.compiled.all) {
      if (this.textReferable(node) && (this.fieldReference(node) || this.textState(node) === 'reference')) out.add(node.pointer);
    }
    return out;
  });

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
    this.fieldStores.update((s) => {
      const out = { ...s };
      for (const f of changed) delete out[f];
      return out;
    });
    // A text field read from a store held its new reference while it was changed: the stored one stands for it now.
    let model = untracked(this.model);
    for (const n of this.compiled.all) {
      if (changed.includes(n.pointer) && this.textReferable(n) && referenceKind(stringAt(model, n.path))) model = setAt(model, n.path, '');
    }
    this.model.set(model);
  }

  replace(node: SchemaNode, on: boolean): void {
    this.replacing.update((s) => {
      const next = new Set(s);
      if (on) next.add(node.pointer);
      else next.delete(node.pointer);
      return next;
    });
    const stored = this.stored.get(node.pointer);
    if (on && stored && storedReference(stored)) {
      // Change: the field opens with the reference it reads, in its store, to be pointed elsewhere.
      if (stored.store) this.fieldStores.update((f) => ({ ...f, [node.pointer]: stored.store! }));
      this.setFieldText(node, stored.ref ?? REFERENCE_SCHEMES[secretSourceId(stored.source ?? '')] ?? '');
    } else if (!on) this.setFieldText(node, '');
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

  /**
   * The `secrets` member of the request: each filled secret field (a value, or a reference that starts with a store's
   * scheme), never an empty one; and each text field read from a store, by its reference.
   */
  readonly secretInputs = computed<SecretInputs>(() => {
    const out: Record<string, SecretInputs[string]> = {};
    for (const node of this.compiled.all) {
      if (!this.visible(node) || !this.referable(node)) continue;
      const typed = this.fieldText(node);
      if (!typed.trim()) continue;
      const store = this.fieldStore(node);
      // A scheme alone (vault://, just picked in the menu) names nothing yet.
      const ref = this.fieldReference(node);
      if (this.fieldKind(node)) {
        if (ref) out[node.pointer] = { source: 'reference', ref, ...(store ? { store } : {}) };
      } else if (node.kind === 'secret') out[node.pointer] = { source: 'local', value: typed };
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
    const typed = this.secrets()[node.pointer] ?? '';
    const kind = referenceKind(typed);
    if (kind) return this.referenceProblem(node, typed, kind);
    if (!typed.trim() && (this.needed(node) || state === 'editing')) return `${node.title} is required.`;
    return null;
  }

  /** A reference names a path after its store's scheme (`vault://kv/prod/db#password`). */
  private referenceProblem(node: SchemaNode, typed: string, kind: string): string | null {
    const source = this.fieldSource(node);
    if (source && !source.available) return source.reason ?? `Connect ${source.title} in Secrets or choose another source.`;
    const scheme = REFERENCE_SCHEMES[kind];
    if (typed.trim().length > scheme.length) return null;
    const example = kind === 'vault' ? `vault://kv/${this.env()}/db#${node.key}` : `${scheme}…`;
    return `Enter where ${kindTitle(kind)} keeps the ${node.title.toLowerCase()} after ${scheme}, such as ${example}.`;
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
   * invalid field. Returns whether the form may go to the core. Only the form's own rules hold it back: a message of
   * the core stays until its value changes, but Save sends the form for the core to check again, so a refusal that
   * another field settled (two sign-ins, answered by leaving the sign-in out) doesn't block it.
   */
  submit(ignorePointers: readonly string[] = []): boolean {
    this.submitted.set(true);
    untracked(() => this.tree().markAsTouched());
    const invalid = untracked(() => this.compiled.all.filter((node) => this.problem(node) !== null))
      .filter((node) => !ignorePointers.includes(node.pointer));
    if (!invalid.length) return true;
    for (const section of this.compiled.all.filter((n) => n.kind === 'object')) {
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
      const node = pointer ? this.compiled.all.find((n) => n.pointer === pointer && n.kind !== 'object' && this.visible(n)) : undefined;
      if (!node) {
        others.push(e);
        continue;
      }
      const value = node.kind === 'secret' ? (untracked(this.secrets)[node.pointer] ?? '') : getAt(untracked(this.model), node.path);
      mine.push({ pointer: node.pointer, message: e.message, value });
    }
    this.serverErrors.set(mine);
    const first = this.compiled.all.find((n) => this.visible(n) && mine.some((m) => m.pointer === n.pointer));
    if (first) {
      for (const section of this.compiled.all) {
        if (section.kind === 'object' && containsNode(section, first)) this.setExpanded(section, true);
      }
      this.focus(first);
    }
    return others;
  }

  /** Back to the values the form started with (after Discard). */
  reset(current: JsonObject | null, schema: JsonObject): void {
    this.model.set(this.blankText(initialValue(schema, current, current ? null : untracked(this.envState))));
    this.secrets.set({});
    this.fieldStores.set({});
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
    const siblings = new Map<string, SchemaNode[]>();
    for (const node of this.compiled.all) {
      if (node.kind === 'object' || node.kind === 'secret') continue;
      const at = siblings.get(node.pointer) ?? [];
      at.push(node);
      siblings.set(node.pointer, at);
    }
    // Branches can share a saved path. One inactive branch must not hide the active branch's control or errors.
    for (const nodes of siblings.values()) {
      const node = nodes[0];
      let sp: unknown = p;
      for (const k of node.path) sp = (sp as Record<string, unknown>)[k];
      hidden(sp as SchemaPath<JsonValue>, () => !nodes.some((n) => this.visible(n)));
    }
    for (const node of this.compiled.all) {
      if (node.kind === 'object' || node.kind === 'secret') continue;
      let sp: unknown = p;
      for (const k of node.path) sp = (sp as Record<string, unknown>)[k];
      const path = sp as SchemaPath<JsonValue>;
      // A text field read from a store holds its reference, or keeps the stored one: checked as a reference, not as
      // the field's value.
      const active = () => this.visible(node) && !this.fieldKind(node) && this.textState(node) !== 'reference';
      if (node.required && node.kind === 'tags') {
        // An empty list is a value to `required`; "at least one" is the rule here.
        validate(path, ({ value }) => {
          const v = value();
          return active() && this.needed(node) && (!Array.isArray(v) || v.length === 0)
            ? { kind: 'required', message: requiredMessage(node) } : null;
        });
      } else if (node.required) {
        required(path, { message: requiredMessage(node), when: () => active() && this.needed(node) });
      }
      validate(path, ({ value }) => {
        if (!active()) return null;
        const problem = node.kind === 'json' ? (this.jsonErrors()[node.pointer] ?? null) : valueProblem(node, value());
        return problem ? { kind: 'schema', message: problem } : null;
      });
      if (node.kind === 'text' && !this.textReferable(node)) {
        // Where the credentials go is typed: the core refuses a reference there.
        validate(path, ({ value }) => this.visible(node) && referenceKind(typeof value() === 'string' ? value() as string : '')
          ? { kind: 'reference', message: isTargetKey(node.key)
            ? `Type the ${node.title.toLowerCase()}: where a connection sends its credentials is never read from a store.`
            : `Type the ${node.title.toLowerCase()}: this field isn't read from a store.` }
          : null);
      }
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

/** "HashiCorp Vault" for `vault`: how a field's menu and hints name a kind of store. */
export function kindTitle(kind: string): string {
  return kind === 'vault' ? 'Vault' : kind === 'gcp-sm' ? 'GCP Secret Manager' : kind === 'aws-sm' ? 'AWS Secrets Manager' : kind;
}

function stringAt(model: JsonObject, path: readonly string[]): string {
  const v = getAt(model, path);
  return typeof v === 'string' ? v : '';
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
