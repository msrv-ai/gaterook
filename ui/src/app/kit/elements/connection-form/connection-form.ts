import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  afterNextRender,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormField, form, readonly, required, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { ConnectionFormSpec, EnvChange } from '../../../core/api/connector-types.api';
import { SecretInputs, StoredSecret, TestOutcome } from '../../../core/api/connections.api';
import { Env, JsonObject, JsonValue } from '../../../core/api/types';
import { SchemaNode, getAt, rootSection } from '../../../schema-form/compile';
import { GwSchemaSection } from '../../../schema-form/section';
import { ConfirmChange, REFERENCE_SOURCES, SchemaForm, SecretAction, createSchemaForm, storedReference } from '../../../schema-form/schema-form';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwToggle } from '../../disclosure';
import { secretSourceId, sourceCopy } from '../../../schema-form/secret-sources';
import { GwIcon } from '../../icons';
import { literalParts } from '../shared/literals';
import { GwConnectionTest, TestState, TestTarget } from './connection-test';
import { DatabaseName, databaseConfig, databaseNames, databaseSelection, initialDatabases, removeConfigPath, withoutDatabaseFields } from './database-selection';

/** What Save (and Test) hand to the page: the request members of `POST /api/connections` / `/test`. */
export interface ConnectionFormValue {
  readonly name: string;
  readonly env: Env;
  readonly config: JsonObject;
  readonly secrets: SecretInputs;
}

export interface DatabaseDiscoveryRequest {
  /** In-memory fingerprint only; includes credentials and must never be logged or rendered. */
  readonly key: string;
  readonly value: ConnectionFormValue;
}

interface DatabaseDiscovery {
  readonly key: string;
  readonly state: 'loading' | 'ready' | 'error';
  readonly names: readonly DatabaseName[];
  readonly complete: boolean;
  readonly message: string | null;
  readonly hints: readonly string[];
}

/** The parts of the form a view shows (the kit's partial views show one section each). */
export type ConnectionFormPart = 'connection' | 'credentials' | 'safety' | 'advanced' | 'test' | 'actions';

/** One line of "Prod turns on stricter defaults": its icon, its words and the literals in them (set in mono). */
export interface ProdDefault {
  readonly icon: string;
  readonly text: string;
  readonly literals?: readonly string[];
}

const SLUG = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;

/** "Dev DB" → "dev-db": the example a slug error offers. */
export function slugify(text: string): string {
  const s = text
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .replace(/^[0-9-]+/, '');
  return s || 'dev-db';
}

const ENV_CARDS: readonly { readonly env: Env; readonly title: string; readonly description: string; readonly meta: string | null }[] = [
  { env: 'dev', title: 'Development', description: 'Local work and test data. Mutations still wait for approval.', meta: null },
  { env: 'stage', title: 'Staging', description: 'Shared test systems. Tighter limits than dev.', meta: null },
  { env: 'prod', title: 'Production', description: 'Real data. Read-only by default, stricter limits.', meta: 'Turns on stricter defaults' },
];

/** The dev card in a beginner's words (kit connection-new `?state=first-run`). */
const BEGINNER_DEV = 'Local work and test data. Changes to data still wait for your approval.';

/** What the credentials sign in to, in a sentence ("GateRook uses them only to sign in to the database"). */
export function signInTarget(type: string): string {
  switch (type) {
    case 'postgres':
    case 'mysql':
    case 'sql':
    case 'mongodb':
    case 'redis':
      return 'the database';
    case 'ssh':
      return 'the server';
    case 'kubernetes':
      return 'the cluster';
    case 'logs':
      return 'the log store';
    case 'http':
      return 'the API';
    case 'gcp':
    case 'aws':
      return 'the cloud account';
    default:
      return 'the service';
  }
}

/** The icon of one prod default, by what it is about (kit __defaults: lock, rows-3, timer, ban, hourglass). */
function defaultIcon(change: EnvChange): string {
  const p = `${change.pointer} ${change.title}`.toLowerCase();
  if (/read.?only/.test(p)) return 'lock';
  if (/row/.test(p)) return 'rows-3';
  if (/approval|hourglass/.test(p)) return 'hourglass';
  if (/timeout|time/.test(p)) return 'timer';
  if (/ddl|deny|forbid|block/.test(p)) return 'ban';
  return 'shield-check';
}

/** A value of a default as the kit writes it: 3000 ms → "3 s", 1000 → "1,000", true → on. */
function formatValue(v: JsonValue, unit: string | null): string {
  if (typeof v === 'number') {
    if (unit === 'ms' && v >= 1000 && v % 1000 === 0) return `${v / 1000} s`;
    return `${v.toLocaleString('en-US')}${unit ? ` ${unit}` : ''}`;
  }
  if (typeof v === 'boolean') return v ? 'on' : 'off';
  if (Array.isArray(v)) return v.join(', ');
  return v === null ? 'none' : String(v);
}

let nextId = 0;

/**
 * `<form gwConnectionForm [spec]="…">` (design/elements/connection-form.html): the Configure step of Add connection
 * and the edit view of a connection. The connector's JSON Schema (from `GET /api/connector-types/{type}/form`)
 * becomes the sections (§8.3 renderer); the gateway adds the connection's Name to the first one, the secret source
 * to the credentials, Environment and safety (prod shows its stricter defaults from `envDefaults`, read from the prod
 * policy, never hard-coded) and the Test connection panel. Secrets never enter the config: they go as `secrets`.
 * Errors show after Save next to their field, focus moves to the first; the core's 422 goes through
 * {@link applyServerError}.
 */
@Component({
  selector: 'form[gwConnectionForm]',
  imports: [NgTemplateOutlet, GwIcon, RouterLink, FormField, GwSchemaSection, GwConnectionTest, GwDialog, GwDialogClose, GwToggle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-form gw-connection-form',
    novalidate: '',
    '[attr.aria-busy]': 'busy() ? "true" : null',
    '(submit)': 'onSubmit($event)',
  },
  templateUrl: './connection-form.html',
})
export class GwConnectionForm {
  private readonly injector = inject(Injector);

  readonly spec = input.required<ConnectionFormSpec>();
  readonly mode = input<'create' | 'edit' | 'duplicate'>('create');
  /** The connection's name and env (edit, duplicate) or a suggestion (create). */
  readonly name = input('');
  readonly env = input<Env | null>(null);
  readonly config = input<JsonObject | null>(null);
  /** The connection's metadata the core found (kubeconfig contexts for `x-options-from`); null until it has any. */
  readonly metadata = input<JsonObject | null>(null);
  readonly stored = input<readonly StoredSecret[]>([]);
  /** Stored secrets (field pointers) that open in the editing state, as after Replace. */
  readonly replacing = input<readonly string[]>([]);
  /** Collapsed sections (pointers, `/advanced`) that open expanded. */
  readonly expandedSections = input<readonly string[]>([]);
  readonly idPrefix = input(`cf${++nextId}`);
  /** The parts shown; all by default. */
  readonly parts = input<readonly ConnectionFormPart[] | null>(null);
  /** Copy the page sets for the credentials section in a particular case ("stage-db reads its password…"). */
  readonly credentialsDescription = input<string | null>(null);
  readonly testState = input<TestState>('idle');
  readonly testOutcome = input<TestOutcome | null>(null);
  readonly testedAt = input<string | null>(null);
  readonly detailsOpen = input(false, { transform: booleanAttribute });
  readonly busy = input(false, { transform: booleanAttribute });
  readonly saveLabel = input('Save connection');
  readonly backLabel = input<string | null>('Back');
  /**
   * What prod turns on beyond the connector's own config defaults: the prod policy's values (kit "Prod turns on
   * stricter defaults": read-only transactions, row limit 100 (dev: 1,000)…), which the page reads from
   * `/api/policies`; never hard-coded here. Literals in a line (`CREATE`) are set in mono.
   */
  readonly policyDefaults = input<readonly ProdDefault[] | null>(null);
  /** The core's word on the Name (409: the name is taken), shown under it until the name changes. */
  readonly nameProblem = input<{ readonly name: string; readonly message: string } | null>(null);
  /**
   * The Name hint as Add connection words it ("Agents call tools with it and Activity filters by connection:dev-db";
   * the element's demo says "Agents and Activity see it as …"), and the Name shown read-only in the edit view.
   */
  readonly pageCopy = input(false, { transform: booleanAttribute });
  /**
   * The first connection of a first run (kit connection-new `?state=first-run`): plain descriptions for a beginner, the
   * secret encrypted here by default with the secret managers behind a disclosure ("Use a secret manager instead"),
   * and every line about the secret following the source picked.
   */
  readonly beginner = input(false, { transform: booleanAttribute });
  /** A step of a wizard asks before it continues (x-confirm), not before it saves. */
  readonly confirmAction = input<'save' | 'continue'>('save');

  readonly save = output<ConnectionFormValue>();
  readonly back = output<void>();
  readonly runTest = output<ConnectionFormValue>();
  readonly secretAction = output<SecretAction>();
  readonly discoverDatabases = output<DatabaseDiscoveryRequest>();
  readonly databaseSelection = computed(() => databaseSelection(this.spec().schema));
  private readonly databaseBaselines = new WeakMap<SchemaForm, string>();

  /** The schema form, rebuilt when the connector or the starting config changes. */
  readonly state = computed<SchemaForm>(() => {
    const spec = this.spec();
    const current = databaseConfig(this.config(), this.databaseSelection());
    // Stored secrets change after Replace (a new version): the form takes them with setStored, not by being rebuilt.
    const stored = untracked(this.stored);
    const idPrefix = this.idPrefix();
    const replacing = this.replacing();
    const expanded = this.expandedSections();
    return untracked(() => {
      const state = createSchemaForm(spec.schema, {
        idPrefix,
        current,
        stored,
        replacing,
        expanded,
        // The env it opens for: a new prod connection starts with the prod defaults (x-env-default).
        env: this.common().env,
        metadata: this.metadata(),
        pointerPrefix: '/config',
        stricterBadges: false,
        injector: this.injector,
      });
      this.databaseBaselines.set(state, this.databaseFingerprint(state));
      return state;
    });
  });

  /** Name and environment: the gateway's own fields, not the connector's (a Signal Forms form of their own). */
  protected readonly common = linkedSignal(() => ({ name: this.name(), env: (this.env() ?? this.spec().env ?? 'dev') as string }));
  protected readonly submitted = signal(false);
  protected readonly commonForm = form(
    this.common,
    (p) => {
      required(p.name, { message: 'Name the connection, for example dev-db.' });
      // A connection keeps its name: agents, policies and the audit refer to it.
      readonly(p.name, () => this.mode() === 'edit');
      validate(p.name, ({ value }) =>
        value() && !SLUG.test(value())
          ? { kind: 'slug', message: `${value()} is not a slug. Use lowercase letters, digits and dashes, for example ${slugify(value())}.` }
          : null,
      );
    },
    { injector: this.injector },
  );

  protected readonly envCards = computed(() =>
    this.beginner() ? ENV_CARDS.map((c) => (c.env === 'dev' ? { ...c, description: BEGINNER_DEV } : c)) : ENV_CARDS,
  );
  /** First run: the secret sources wait behind "Use a secret manager instead" until opened. */
  protected readonly sourcesOpen = signal(false);
  /** "password", "private key": the connector's first secret, as the beginner's lines name it. */
  protected readonly secretWord = computed(() => (this.spec().secretFields[0]?.title ?? 'secret').toLowerCase());
  /** First run: the Connection section in a beginner's words (where the core connects from). */
  protected readonly beginnerConnection = 'GateRook connects from this computer, so your VPN and hosts file apply.';
  /**
   * First run: a collapsed section (Advanced) says it can be skipped — its schema's first sentence (what is in it),
   * then "The defaults suit most connections, so you can skip this." (kit connection-new `?state=first-run`).
   */
  protected advancedText(section: SchemaNode): string | null {
    if (!this.beginner() || !section.collapsed) return null;
    const lead = /^[^.]*\./.exec(section.description ?? '')?.[0] ?? '';
    return `${lead ? `${lead} ` : ''}The defaults suit most connections, so you can skip this.`;
  }

  protected readonly placeholderName = '<name>';
  protected readonly sourceCopy = sourceCopy;
  protected readonly sourceId = secretSourceId;
  protected readonly literalParts = literalParts;

  /** Options belong to the exact server, authentication and TLS settings that discovered them. */
  private readonly databaseKey = computed(() => this.databaseFingerprint(this.state()));

  private databaseFingerprint(state: SchemaForm): string {
    const selection = this.databaseSelection();
    let config = state.value();
    if (selection) {
      config = removeConfigPath(config, selection.path);
      if (selection.legacyPath) config = removeConfigPath(config, selection.legacyPath);
    }
    return JSON.stringify([this.spec().type, this.common().env, config, state.secretInputs(), state.source(),
      this.stored().map((secret) => [secret.field, secret.version, secret.state, secret.source])]);
  }
  protected readonly databaseDiscovery = linkedSignal(() => {
    this.databaseKey();
    return null as DatabaseDiscovery | null;
  });
  protected readonly databaseSearch = signal('');
  protected readonly selectedDatabases = computed(() => {
    const selection = this.databaseSelection();
    return selection ? databaseNames(getAt(this.state().model(), selection.path)) : [];
  });
  protected readonly databaseChoices = computed(() => {
    const discovery = this.databaseDiscovery();
    const selected = this.selectedDatabases();
    const all = [...new Set([...(discovery?.names ?? []), ...selected])];
    const query = this.databaseSearch().trim().toLocaleLowerCase();
    return all.map((name, index) => ({ name, index, selected: selected.includes(name),
      unavailable: discovery?.state === 'ready' && !discovery.names.includes(name) }))
      .filter((choice) => String(choice.name).toLocaleLowerCase().includes(query));
  });
  protected readonly databaseError = computed(() => {
    const node = this.state().compiled.all.find((field) => field.pointer === this.databaseSelection()?.pointer);
    return (node ? this.state().error(node) : null) ?? (this.submitted() ? this.databaseProblem() : null);
  });

  private databaseProblem(): string | null {
    const selection = this.databaseSelection();
    if (!selection) return null;
    const selected = this.selectedDatabases();
    const previous = initialDatabases(this.config(), selection);
    // Existing connections can keep or narrow their saved scope without contacting an offline server.
    if (this.mode() === 'edit' && this.databaseKey() === this.databaseBaselines.get(this.state())
      && (selected.length || !previous.length) && selected.every((name) => previous.includes(name))) return null;
    const discovery = this.databaseDiscovery();
    if (discovery?.state !== 'ready') return 'Load databases with the current connection settings before continuing.';
    if (!selected.length) return 'Select at least one database.';
    if (selected.some((name) => !discovery.names.includes(name))) return 'Remove databases that are no longer available, or reload the list.';
    return null;
  }

  databaseSelectionValid(): boolean {
    const problem = this.databaseProblem();
    if (!problem) return true;
    this.submitted.set(true);
    afterNextRender(() => document.getElementById(`${this.idPrefix()}-database-load`)?.focus(), { injector: this.injector });
    return false;
  }

  protected loadDatabases(): void {
    const selection = this.databaseSelection();
    if (!selection || this.databaseDiscovery()?.state === 'loading'
      || !this.state().submit([selection.pointer, ...(selection.legacyPointer ? [selection.legacyPointer] : [])])) return;
    const key = this.databaseKey();
    this.startDatabaseDiscovery(key);
    this.discoverDatabases.emit({ key, value: this.value() });
  }

  startDatabaseDiscovery(key: string): boolean {
    if (key !== this.databaseKey()) return false;
    this.databaseSearch.set('');
    this.databaseDiscovery.set({ key, state: 'loading', names: [], complete: true, message: null, hints: [] });
    return true;
  }

  completeDatabaseDiscovery(key: string, outcome: TestOutcome | null, error: string | null = null): boolean {
    if (key !== this.databaseKey() || this.databaseDiscovery()?.key !== key) return false;
    const details = outcome?.details;
    const found = details && typeof details === 'object' && !Array.isArray(details) ? details['databases'] : null;
    const ok = outcome?.ok === true && Array.isArray(found);
    this.databaseDiscovery.set({
      key, state: ok ? 'ready' : 'error', names: databaseNames(found ?? null),
      complete: !!details && typeof details === 'object' && !Array.isArray(details) && details['discoveryComplete'] !== false,
      message: error ?? outcome?.error?.message ?? outcome?.needsConfirmation?.text ?? (ok ? null : "The server didn't return a database list."),
      hints: (outcome?.hints ?? []).map((hint) => hint.text),
    });
    return true;
  }

  protected selectDatabase(name: DatabaseName, checked: boolean): void {
    const selection = this.databaseSelection();
    const node = this.state().compiled.all.find((field) => field.pointer === selection?.pointer);
    if (!node) return;
    const selected = this.selectedDatabases();
    this.state().set(node, checked ? [...new Set([...selected, name])] : selected.filter((value) => value !== name));
  }

  constructor() {
    // The secret field's buttons and placeholders name the connection as it is typed.
    effect(() => {
      const s = this.state();
      s.subject.set(this.common().name);
      // Picking another env moves the untouched defaults and enforces that env's locks (x-env-default, x-forbidden-env).
      s.setEnv(this.common().env);
    });
    effect(() => {
      const metadata = this.metadata();
      untracked(this.state).metadata.set(metadata);
    });
    effect(() => {
      const stored = this.stored();
      untracked(this.state).setStored(stored);
    });
    effect(() => {
      const beginner = this.beginner();
      untracked(this.state).beginner.set(beginner);
    });
    // Secret actions go to the page (it owns the API calls).
    effect((onCleanup) => {
      const off = this.state().onSecretAction((a) => this.secretAction.emit(a));
      onCleanup(off);
    });
  }

  protected show(part: ConnectionFormPart): boolean {
    const parts = this.parts();
    return !parts || parts.includes(part);
  }

  // ─── Sections ───

  /** The section the secret source belongs to: the object with `x-gateway-secret`, else the one holding a secret. */
  protected readonly secretSection = computed<SchemaNode | null>(() => {
    const sections = this.displaySections();
    return (
      sections.find((s) => s.secret) ??
      sections.find((s) => s.children.some((c) => c.kind === 'secret')) ??
      null
    );
  });

  /**
   * The schema's root-level fields as the Connection section: all of a flat schema (Kubernetes, an MCP server), or
   * the loose fields before the sections (HTTP's base URLs, AWS regions). It comes first and takes the Name.
   */
  protected readonly rootSection = computed<SchemaNode | null>(() => {
    const fields = withoutDatabaseFields(this.state().compiled.loose, this.databaseSelection());
    return fields.length ? rootSection(fields, 'Connection') : null;
  });

  /** A flat schema keeps its secrets at the root: the secret source picker goes there, after the Name. */
  protected readonly rootHoldsSecrets = computed(
    () => !this.secretSection() && !!this.rootSection()?.children.some((c) => c.kind === 'secret'),
  );

  /** The connector's sections around Environment and safety: it goes before the first collapsed one (Advanced). */
  protected readonly sectionsBefore = computed(() => {
    const all = this.displaySections();
    const cut = all.findIndex((s) => s.collapsed);
    const root = this.rootSection();
    const before = cut < 0 ? all : all.slice(0, cut);
    return root ? [root, ...before] : before;
  });

  protected readonly sectionsAfter = computed(() => {
    const all = this.displaySections();
    const cut = all.findIndex((s) => s.collapsed);
    return cut < 0 ? [] : all.slice(cut);
  });

  private readonly databaseSections = computed(() => withoutDatabaseFields(this.state().compiled.sections, this.databaseSelection()));

  private readonly displaySections = computed(() => {
    const state = this.state();
    return this.databaseSections().filter((node) => state.visible(node));
  });

  protected partOf(section: SchemaNode): ConnectionFormPart {
    if (section === this.secretSection()) return 'credentials';
    if (section.collapsed) return 'advanced';
    return 'connection';
  }

  /** Whether a source picker belongs here: a new connection, or one whose secret is a reference already. */
  protected readonly showSources = computed(() => {
    if (this.spec().secretSources.length < 2) return false;
    if (this.mode() !== 'edit') return true;
    return [...this.state().stored.values()].some(storedReference);
  });

  protected readonly credentialsText = computed(() => {
    const custom = this.credentialsDescription();
    if (custom) return custom;
    if (this.beginner()) return `Your AI never sees these. GateRook uses them only to sign in to ${signInTarget(this.spec().type)}.`;
    const node = this.secretSection();
    if (node?.description) return node.description;
    if (this.mode() === 'edit' && !this.showSources()) {
      return 'Stored and encrypted here with the master key. Replace it with a new value; nobody can view the current one.';
    }
    return 'The agent never sees these. The core decrypts or fetches the secret only for the duration of a call.';
  });

  /** The page's copy names the connection first ("stage-db reads its password from Vault…"). */
  protected readonly namedCredentials = computed(() => {
    const name = this.common().name;
    const text = this.credentialsDescription();
    return !!name && !!text && text.startsWith(`${name} `);
  });

  protected setSource(id: string, available: boolean, event: Event): void {
    if (!available) {
      // aria-disabled cards stay focusable and say why; they never become the choice.
      event.preventDefault();
      (event.target as HTMLInputElement).checked = false;
      return;
    }
    this.state().source.set(secretSourceId(id));
  }

  // ─── Name ───

  protected readonly nameError = computed(() => {
    // The core's word (the name is taken) holds until the name it was about changes.
    const problem = this.nameProblem();
    if (problem && problem.name === this.common().name) return problem.message;
    if (!this.submitted()) return null;
    return this.commonForm.name().errors()[0]?.message ?? null;
  });

  protected readonly nameValid = computed(() => SLUG.test(this.common().name));

  /** The Name field: always in a new connection; in the edit view only on the page (read-only there, kit page). */
  protected readonly showName = computed(() => this.mode() !== 'edit' || this.pageCopy());

  protected nameErrorParts(message: string) {
    return literalParts(message, [this.common().name]);
  }

  // ─── Environment and safety ───

  protected setEnv(env: Env): void {
    this.commonForm.env().value.set(env);
  }

  /** The connector's own prod config defaults (TLS on), then what the prod policy adds (the page passes those). */
  protected readonly prodDefaults = computed<readonly ProdDefault[]>(() => [
    ...this.spec().envDefaults.prod.map((c): ProdDefault => {
      const node = this.state().compiled.all.find((n) => n.pointer === c.pointer);
      const unit = node?.unit ?? null;
      const icon = defaultIcon(c);
      if (typeof c.to === 'boolean') return { icon, text: c.to ? c.title : `${c.title}: off` };
      const from = c.from === null || c.from === undefined ? '' : ` (dev: ${formatValue(c.from, unit)})`;
      return { icon, text: `${c.title} ${formatValue(c.to, unit)}${from}` };
    }),
    ...(this.policyDefaults() ?? []),
  ]);

  // ─── Test ───

  /** Where the test connects and as whom, from the usual fields (host, port, user, connect timeout). */
  protected readonly target = computed<TestTarget>(() => {
    const s = this.state();
    const find = (re: RegExp) => s.compiled.all.find((n) => re.test(n.key) && n.kind !== 'object');
    const host = find(/^(host|hostname|address|endpoint)$/i);
    const port = find(/^port$/i);
    const user = find(/^(user|username|login)$/i);
    const timeout = find(/^connect(ion)?timeout(ms)?$/i);
    const m = s.model();
    const h = host ? getAt(m, host.path) : null;
    const p = port ? getAt(m, port.path) : null;
    const u = user ? getAt(m, user.path) : null;
    const t = timeout ? getAt(m, timeout.path) : null;
    return {
      address: typeof h === 'string' && h ? (p !== null ? `${h}:${p}` : h) : null,
      user: REFERENCE_SOURCES.has(s.source()) && s.source() === 'dynamic' ? null : typeof u === 'string' && u ? u : null,
      timeoutMs: typeof t === 'number' ? t : null,
    };
  });

  protected readonly userFieldId = computed(() => {
    const s = this.state();
    const user = s.compiled.all.find((n) => /^(user|username|login)$/i.test(n.key));
    return user ? s.id(user) : null;
  });

  protected test(): void {
    this.runTest.emit(this.value());
  }

  // ─── Submit ───

  protected readonly validationSummary = computed(() => {
    if (!this.submitted()) return null;
    return this.nameError() ?? this.state().invalidNodes().map((node) => this.state().error(node))[0]
      ?? this.databaseProblem();
  });

  value(): ConnectionFormValue {
    const s = this.state();
    const selection = this.databaseSelection();
    let config = s.value();
    if (selection?.legacyPath) config = removeConfigPath(config, selection.legacyPath);
    return { name: this.common().name, env: this.common().env as Env, config, secrets: s.secretInputs() };
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (this.busy()) return;
    this.submitted.set(true);
    untracked(() => this.commonForm().markAsTouched());
    const fieldsOk = this.state().submit();
    const nameOk = this.mode() === 'edit' || !this.commonForm.name().invalid();
    if (!nameOk) {
      // The name comes first on the page: it takes the focus over the schema's first error (registered after it, so
      // it runs after it).
      afterNextRender(() => document.getElementById(`${this.idPrefix()}-name`)?.focus(), { injector: this.injector });
    }
    if (!fieldsOk || !nameOk || !this.databaseSelectionValid()) return;
    // A changed setting that asks first (x-confirm: Run the credential command, a port) goes through its dialog.
    const changes = untracked(this.state().confirmations);
    if (changes.length) {
      this.confirming.set(changes);
      this.confirmDialog()?.openAfterRender();
      return;
    }
    this.save.emit(this.value());
  }

  // ─── x-confirm ───

  /** The changes the open confirmation lists. */
  protected readonly confirming = signal<readonly ConfirmChange[]>([]);
  protected readonly confirmDialog = viewChild<GwDialog>('confirmDialog');
  protected readonly confirmTone = computed(() => (this.confirming().some((c) => c.tone === 'danger') ? 'danger' : 'warning'));

  /** "Save dev-cluster with this change?" (a wizard step: "Continue with this change to dev-cluster?") */
  protected readonly confirmTitle = computed(() => {
    const name = this.common().name;
    const what = this.confirming().length === 1 ? 'this change' : 'these changes';
    if (this.confirmAction() === 'continue') return `Continue with ${what}${name ? ` to ${name}` : ''}?`;
    return name ? `Save ${name} with ${what}?` : `Save the connection with ${what}?`;
  });

  /** The setting's own sentence when one setting changed, else what the dialog is for. */
  protected readonly confirmDescription = computed(() => {
    const changes = this.confirming();
    const only = changes.length === 1 ? changes[0].node.description : null;
    return only ?? 'These settings ask before they are saved. Check each one.';
  });

  /** "On, was off" / "7466, was 7465". */
  protected changeText(c: ConfirmChange): string {
    const unit = c.node.unit;
    const to = formatValue(c.to, unit);
    const from = formatValue(c.from, unit);
    return `${to.charAt(0).toUpperCase()}${to.slice(1)}, was ${from}`;
  }

  protected confirmSave(): void {
    this.confirmDialog()?.close('save');
    this.save.emit(this.value());
  }

  /** Shows every message as after Save, without moving focus. */
  showErrors(): void {
    this.submitted.set(true);
    this.state().showErrors();
  }

  /** Discard: back to the values the form was given (config, name, env), nothing typed kept, no messages. */
  reset(): void {
    this.state().reset(databaseConfig(this.config(), this.databaseSelection()), this.spec().schema);
    this.databaseDiscovery.set(null);
    this.common.set({ name: this.name(), env: (this.env() ?? this.spec().env ?? 'dev') as string });
    this.submitted.set(false);
    untracked(() => this.commonForm().reset());
  }

  /** The connection's name and env as the person set them (the page's stepper and summaries follow them). */
  readonly current = computed(() => ({ name: this.common().name, env: this.common().env as Env }));

  /** The core refused the save (422): field errors under their fields, the rest returned to the page. */
  applyServerError(error: Parameters<SchemaForm['applyServerErrors']>[0]): ReturnType<SchemaForm['applyServerErrors']> {
    return this.state().applyServerErrors(error);
  }
}
