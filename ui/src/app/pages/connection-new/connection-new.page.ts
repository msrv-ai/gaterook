import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../code/code-block';
import { ApiError, isApiError } from '../../core/api/api-error';
import { ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionDetail, ConnectionHealth, ConnectionsApi, StoredSecret, TestOutcome } from '../../core/api/connections.api';
import { ConnectionFormSpec, ConnectorCatalog, ConnectorTypeDetail, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { Policies, PoliciesApi } from '../../core/api/policies.api';
import { SecretRow, SecretsApi } from '../../core/api/secrets.api';
import { Env, JsonObject, JsonValue, ToolMode } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwAriaDisabled } from '../../kit/aria-disabled';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { ConnectionFormValue, DatabaseDiscoveryRequest, GwConnectionForm, ProdDefault } from '../../kit/elements/connection-form/connection-form';
import { databaseSelection } from '../../kit/elements/connection-form/database-selection';
import { GwConnectionTest, TestState, testConfirmTitle, testFacts } from '../../kit/elements/connection-form/connection-test';
import { GwConnectorPicker } from '../../kit/elements/connector-card/connector-card';
import { clientName } from '../../kit/elements/shared/client-mark';
import { connectorIcon } from '../../kit/elements/shared/connector-icons';
import { MODES, healthLook } from '../../kit/elements/shared/dictionaries';
import { literalParts } from '../../kit/elements/shared/literals';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { SecretAction } from '../../schema-form/schema-form';
import {
  EndpointChoice,
  HealthStat,
  Mode,
  STEP_NAMES,
  StepNumber,
  activityQuery,
  cardKey,
  copyName,
  defaultCardKey,
  deletedToast,
  endpointChoices,
  endpointUses,
  findCard,
  fixSnippet,
  healthStat,
  initialEndpoints,
  list,
  policyDefaults,
  savedToast,
  secretWord,
  secretsSentence,
  sparkPoints,
  stepperSteps,
  testSummary,
  testTarget,
  testToast,
  toolPreview,
  usage,
  usedByMeta,
  usedByRows,
} from './connection-new-view';

/** A load of the page: under way, done, or why not (an area whose backend isn't merged answers `not-available`). */
type LoadState = 'idle' | 'loading' | 'ready' | 'error';

/** Merges the core's patched draft into the form's model: objects key by key, anything else replaces. */
function merge(base: JsonValue, patch: JsonValue): JsonValue {
  if (!patch || typeof patch !== 'object' || Array.isArray(patch) || !base || typeof base !== 'object' || Array.isArray(base)) return patch;
  const out: { [k: string]: JsonValue } = { ...(base as JsonObject) };
  for (const [k, v] of Object.entries(patch)) out[k] = merge(out[k] ?? null, v);
  return out;
}

/** What a test result is about, so it runs again only when the values change (secrets included, in memory only). */
function draftKey(type: string | null, v: ConnectionFormValue): string {
  return JSON.stringify([type, v.env, v.config, v.secrets]);
}

/** The wording of an error alert: the core's own sentence, else what to do. */
export function errorText(e: ApiError | null): string {
  if (!e) return "The core didn't answer. Check that MCP Gateway is running, then try again.";
  return e.kind === 'not-available' ? 'This build of the core has no such page yet.' : `${e.title}. ${e.detail}`;
}

/**
 * Add connection (design/pages/connection-new.html, ARCHITECTURE.md §8.4): one page, three modes.
 *
 * - **Wizard** (`/connections/new`, `?type=&variant=` from Connectors): 1 Choose type (the catalog by group, Logs back
 *   ends as cards, unavailable types disabled with their reason) → 2 Configure (the connector's JSON Schema, the secret
 *   source, Environment and safety with what prod turns on, read from the prod policy) → 3 Test (the core connects from
 *   this machine and reports what the account may do: permissions, hints with a copyable fix, a host key to trust, a
 *   key that changed) → 4 Add to endpoint (the endpoints it may join, refused ones with their reason, and the modes its
 *   tools start in). Nothing is saved until Save connection: then `POST /api/connections` and, for each chosen
 *   endpoint, the core's own preview and `POST /api/endpoints/{slug}/connections/{name}`.
 * - **Duplicate** (`?duplicate=<name>`): the wizard from Configure with the settings of `GET …/{name}/duplicate`;
 *   credentials are never copied.
 * - **Edit** (`/connections/<name>`): health (live through `connection.health`), Test connection, the settings form
 *   with stored secrets (Replace, Rotate where the source can) or references, "Used by" and Delete.
 *
 * Step changes move focus to the step's heading and announce "Step 2 of 4: Configure", as the kit's page script does.
 */
@Component({
  selector: 'gw-connection-new-page',
  imports: [
    RouterLink,
    GwIcon,
    GwToggle,
    GwAriaDisabled,
    GwDialog,
    GwDialogClose,
    GwCodeBlock,
    GwConnectorPicker,
    GwConnectionForm,
    GwConnectionTest,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--narrow' },
  templateUrl: './connection-new.page.html',
})
export class ConnectionNewPage {
  private readonly typesApi = inject(ConnectorTypesApi);
  private readonly connections = inject(ConnectionsApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly policiesApi = inject(PoliciesApi);
  private readonly secretsApi = inject(SecretsApi);
  private readonly approvalsApi = inject(ApprovalsApi);
  private readonly events = inject(EventStream);
  private readonly tunnel = inject(TunnelStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly doc = inject(DOCUMENT);
  protected readonly time = inject(TimeService);

  /** Route parameter `name`: the connection of the edit view. */
  readonly name = input<string | null>(null);
  /** `?type=` (and `&variant=`): the card the picker opens on (Connectors' "Add connection"). */
  readonly type = input<string | null>(null);
  readonly variant = input<string | null>(null);
  /** `?duplicate=<name>`: the wizard from Configure with that connection's settings. */
  readonly duplicate = input<string | null>(null);

  readonly mode = computed<Mode>(() => (this.name() ? 'edit' : this.duplicate() ? 'duplicate' : 'add'));
  protected readonly formMode = computed<'create' | 'duplicate' | 'edit'>(() => {
    const m = this.mode();
    return m === 'add' ? 'create' : m;
  });
  readonly step = signal<StepNumber>(1);
  protected readonly stepCount = STEP_NAMES.length;
  protected readonly modeValue: Record<ToolMode, string> = { OFF: 'off', READ_ONLY: 'read', APPROVAL: 'approval' };
  protected readonly errorText = errorText;
  protected readonly literalParts = literalParts;
  protected readonly activityQuery = activityQuery;
  /** Each start of the page (a new mode or subject); an answer for an older one is dropped. */
  private gen = 0;

  // ─── Step 1: the catalog ───
  protected readonly catalog = signal<ConnectorCatalog | null>(null);
  protected readonly catalogState = signal<LoadState>('idle');
  protected readonly catalogError = signal<ApiError | null>(null);
  readonly selected = signal<string | null>(null);
  protected readonly pickError = signal<string | null>(null);
  protected readonly groups = computed(() => this.catalog()?.groups ?? []);
  protected readonly card = computed(() => findCard(this.groups(), this.selected()));
  /** "3 connections use PostgreSQL" under the picker (ARCHITECTURE.md §8.4). */
  protected readonly usage = computed(() => {
    const c = this.card();
    return c ? usage(c) : null;
  });

  // ─── Step 2: the form ───
  protected readonly spec = signal<ConnectionFormSpec | null>(null);
  protected readonly specState = signal<LoadState>('idle');
  protected readonly specError = signal<ApiError | null>(null);
  /** The card the form was built for (its type, and a Logs back end's preset). */
  private readonly specCard = signal<string | null>(null);
  protected readonly typeDetail = signal<ConnectorTypeDetail | null>(null);
  protected readonly startConfig = signal<JsonObject | null>(null);
  protected readonly startName = signal('');
  protected readonly startEnv = signal<Env | null>(null);
  protected readonly stored = signal<StoredSecret[]>([]);
  protected readonly nameProblem = signal<{ readonly name: string; readonly message: string } | null>(null);
  private readonly policies = signal<{ readonly all: Policies; readonly schema: JsonObject | null } | null>(null);
  readonly form = viewChild(GwConnectionForm);
  /** The duplicate's settings: picking its type again keeps them, another type starts fresh. */
  private copied: { readonly type: string; readonly env: Env; readonly config: JsonObject } | null = null;
  protected readonly databaseSetup = computed(() => !!this.spec() && !!databaseSelection(this.spec()!.schema));
  private databaseRun = 0;

  /** The name and env as the person set them in Configure (the stepper and the later steps follow them). */
  readonly current = computed(() => this.form()?.current() ?? { name: this.startName(), env: this.startEnv() ?? 'dev' });
  protected readonly typeName = computed(() => this.card()?.name ?? this.spec()?.name ?? this.detail()?.typeName ?? '');
  protected readonly typeIcon = computed(() => this.card()?.icon ?? connectorIcon(this.spec()?.type ?? this.detail()?.type ?? ''));

  /** What prod turns on, from the prod policy against dev's (kit: never hard-coded). */
  protected readonly prodLines = computed<ProdDefault[] | null>(() => {
    const p = this.policies();
    const type = this.spec()?.type;
    if (!p || !type) return null;
    return policyDefaults(type, p.all.dev, p.all.prod, p.schema);
  });

  // ─── Step 3: the test ───
  protected readonly testState = signal<TestState>('idle');
  protected readonly outcome = signal<TestOutcome | null>(null);
  protected readonly testedAt = signal<string | null>(null);
  protected readonly trusting = signal(false);
  /** The values the current outcome is about. */
  private testedKey: string | null = null;
  private testRun = 0;
  /** The values Configure continued with (the test and Save send them). */
  private readonly draft = signal<ConnectionFormValue | null>(null);
  protected readonly target = computed(() => testTarget(this.draft()?.config ?? this.startConfig()));
  protected readonly elementTarget = computed(() => {
    const t = this.target();
    return { address: t.hostPort, user: t.user, timeoutMs: t.timeoutMs };
  });
  protected readonly snippet = computed(() => fixSnippet(this.outcome(), this.draft()?.config ?? null));
  protected readonly summary = computed(() => testSummary(this.outcome(), this.target().hostPort));

  // ─── Step 4: endpoints ───
  protected readonly endpoints = signal<EndpointSummary[] | null>(null);
  protected readonly endpointsState = signal<LoadState>('idle');
  protected readonly endpointsError = signal<ApiError | null>(null);
  readonly chosen = signal<readonly string[]>([]);
  private chosenTouched = false;
  protected readonly saving = signal(false);
  protected readonly choices = computed<EndpointChoice[]>(() =>
    endpointChoices(this.endpoints() ?? [], this.current(), this.tunnel.summary()),
  );
  protected readonly previews = computed(() => {
    const card = this.card();
    const tools = this.typeDetail()?.tools ?? [];
    const kind = card?.kind ?? this.typeDetail()?.type.kind ?? 'built-in';
    const dynamic = card ? card.tools === null : false;
    const chosen = this.chosen();
    return this.choices()
      .filter((c) => !c.refusal && chosen.includes(c.slug))
      .map((c) => ({ choice: c, preview: toolPreview(tools, kind, dynamic, c) }));
  });
  /** "sql_execute is Approval or Off, never more": the type's tools that change data. */
  protected readonly mutatingTools = computed(() =>
    (this.typeDetail()?.tools ?? []).filter((t) => (t.nature ?? '').toLowerCase() !== 'read').map((t) => t.name),
  );
  /** "The password is encrypted with your master key on save" (what Save does with the secrets typed). */
  protected readonly saveNote = computed(() => {
    const v = this.draft();
    const spec = this.spec();
    if (!v || !spec) return null;
    const entries = Object.entries(v.secrets);
    if (!entries.length) return spec.secretFields.length ? { icon: 'circle-off', text: 'No secret is stored for it' } : null;
    const local = entries.filter(([, s]) => s.source === 'local').map(([p]) => secretWord(spec.secretFields, p));
    if (!local.length) return { icon: 'lock-keyhole', text: 'Only the reference is stored; the secret stays in its store' };
    const words = list(local);
    return { icon: 'lock-keyhole', text: `The ${words} ${local.length === 1 ? 'is' : 'are'} encrypted with your master key on save` };
  });

  protected readonly steps = computed(() =>
    stepperSteps(this.step(), {
      typeName: this.card()?.name ?? this.spec()?.name ?? null,
      name: this.current().name,
      env: this.current().env,
      test: this.summary(),
      endpoints: this.chosen().filter((s) => this.choices().some((c) => c.slug === s && !c.refusal)),
    }),
  );

  // ─── Edit view ───
  protected readonly detail = signal<ConnectionDetail | null>(null);
  protected readonly detailState = signal<LoadState | 'not-found'>('idle');
  protected readonly detailError = signal<ApiError | null>(null);
  protected readonly host = signal<string | null>(null);
  protected readonly health = signal<ConnectionHealth | null>(null);
  protected readonly editTesting = signal(false);
  private version = 0;
  protected readonly healthView = computed(() => {
    const h = this.health();
    return h ? healthLook(h.state, h.latencyMs) : null;
  });
  protected readonly stat = computed<HealthStat | null>(() => {
    const h = this.health();
    return h ? healthStat(h, this.detail()?.spark24h ?? [], this.detail()?.healthIntervalSeconds ?? null) : null;
  });
  protected readonly spark = computed(() => sparkPoints(this.detail()?.spark24h ?? []));
  protected readonly usedBy = computed(() =>
    usedByRows(this.detail()?.usedBy ?? [], (this.typeDetail()?.tools ?? []).map((t) => t.name)),
  );
  protected readonly uses = computed(() => endpointUses(this.usedBy(), this.endpoints() ?? []));
  protected readonly usesMeta = computed(() => usedByMeta(this.uses()));
  /** Delete's "Stops working": the endpoints where some of its tools are on. */
  protected readonly usesOn = computed(() => this.uses().filter((u) => u.tools.length));
  protected readonly pending = signal<ApprovalItem[]>([]);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);
  protected readonly deleteSentence = computed(() =>
    secretsSentence(this.stored(), Object.fromEntries((this.spec()?.secretFields ?? []).map((f) => [f.pointer, f.title]))),
  );
  /** A stored secret without a value (lost with an old master key, never set): calls that need it fail. */
  protected readonly brokenSecrets = computed(() => this.stored().filter((s) => s.state === 'needs-value'));

  // Dialogs
  protected readonly deleteDialog = viewChild<GwDialog>('deleteDialog');
  protected readonly trustDialog = viewChild<GwDialog>('trustDialog');
  protected readonly hostDialog = viewChild<GwDialog>('hostDialog');
  protected readonly rotateDialog = viewChild<GwDialog>('rotateDialog');
  protected readonly commentDialog = viewChild<GwDialog>('commentDialog');
  /** The edit view's test that stopped for a confirmation (the trust dialog shows it). */
  protected readonly editConfirm = signal<TestOutcome | null>(null);
  protected readonly confirmTitle = computed(() => testConfirmTitle(this.editConfirm(), this.host()));
  protected readonly confirmFacts = computed(() => testFacts(this.editConfirm()?.needsConfirmation?.data));
  /** The core asked before sending the stored secret to a new host (409 `send-secret-to-new-host`). */
  protected readonly newHost = signal<{ readonly title: string; readonly detail: string; readonly value: ConnectionFormValue; readonly discoveryKey?: string } | null>(null);
  /** A prod save the core stopped for the rights of the connection's user (409 `confirm: danger`). */
  protected readonly dangerDialog = viewChild<GwDialog>('dangerDialog');
  protected readonly danger = signal<{ readonly title: string; readonly detail: string; readonly retry: () => void } | null>(null);
  /** The secret waiting for Rotate's confirmation. */
  protected readonly rotating = signal<{ readonly secretId: string; readonly word: string; readonly version: number | null; readonly key: string } | null>(null);
  /** A prod secret's new value waiting for its comment (the core keeps one with every prod secret change). */
  protected readonly replacingProd = signal<{ readonly secretId: string; readonly word: string; readonly value: string } | null>(null);
  protected readonly comment = signal('');
  protected readonly commentError = signal<string | null>(null);

  constructor() {
    const destroy = inject(DestroyRef);
    // One start per mode and subject: the same component serves ?type=, ?duplicate= and the edit view.
    effect(() => {
      const mode = this.mode();
      this.name();
      this.duplicate();
      this.type();
      this.variant();
      untracked(() => void this.start(mode));
    });
    const offHealth = this.events.on('connection.health', (h) => {
      if (this.mode() !== 'edit' || h.name !== this.name()) return;
      this.health.set({ state: h.state, latencyMs: h.latencyMs ?? null, message: h.message, checkedAt: h.checkedAt });
    });
    destroy.onDestroy(offHealth);
    // A stream that was down missed health changes: read the connection's health again.
    let seen = this.events.reopened();
    effect(() => {
      const n = this.events.reopened();
      if (n === seen) return;
      seen = n;
      if (untracked(this.mode) === 'edit' && untracked(this.detail)) untracked(() => void this.refreshHealth());
    });
  }

  // ─── Start ───

  private async start(mode: Mode): Promise<void> {
    const gen = ++this.gen;
    ++this.databaseRun;
    this.step.set(mode === 'duplicate' ? 2 : 1);
    this.outcome.set(null);
    this.testState.set('idle');
    this.testedKey = null;
    this.draft.set(null);
    this.nameProblem.set(null);
    this.chosenTouched = false;
    this.copied = null;
    if (mode === 'edit') {
      await this.startEdit(this.name()!, gen);
      return;
    }
    await this.loadCatalog(gen);
    if (gen !== this.gen) return;
    if (mode === 'duplicate') {
      await this.startDuplicate(this.duplicate()!, gen);
      return;
    }
    this.selected.set(defaultCardKey(this.groups(), this.type(), this.variant()));
    // Deep link to a step (kit: #step-2…#step-4): the step opens without moving focus.
    const m = /^step-([2-4])$/.exec(this.route.snapshot.fragment ?? '');
    if (m && this.selected() && (await this.openType(this.selected()!, true)) && gen === this.gen) {
      this.go(Number(m[1]) as StepNumber, { initial: true });
    }
  }

  private async loadCatalog(gen: number): Promise<void> {
    if (this.catalog()) return;
    this.catalogState.set('loading');
    try {
      const catalog = await this.typesApi.catalog();
      if (gen !== this.gen) return;
      this.catalog.set(catalog);
      this.catalogState.set('ready');
    } catch (e) {
      if (gen !== this.gen) return;
      this.catalogError.set(isApiError(e) ? e : null);
      this.catalogState.set('error');
    }
  }

  protected retryCatalog(): void {
    void this.start(this.mode());
  }

  private async startDuplicate(source: string, gen: number): Promise<void> {
    this.specState.set('loading');
    try {
      const draft = await this.connections.duplicate(source);
      if (gen !== this.gen) return;
      this.copied = { type: draft.type, env: draft.env, config: draft.config };
      // A Logs connection is one card per back end: the one whose preset the copied config holds.
      const cards = this.groups()
        .flatMap((g) => g.cards)
        .filter((c) => c.type === draft.type);
      const preset = (c: (typeof cards)[number]) =>
        !!c.presetConfig &&
        typeof c.presetConfig === 'object' &&
        !Array.isArray(c.presetConfig) &&
        Object.entries(c.presetConfig).every(([k, v]) => JSON.stringify(draft.config[k] ?? null) === JSON.stringify(v));
      const card = cards.find(preset) ?? cards[0];
      this.selected.set(card ? cardKey(card) : draft.type);
      this.startName.set(copyName(source, []));
      this.startEnv.set(draft.env);
      await this.openType(this.selected()!, true);
    } catch (e) {
      if (gen !== this.gen) return;
      this.specError.set(isApiError(e) ? e : null);
      this.specState.set('error');
    }
  }

  private async startEdit(name: string, gen: number): Promise<void> {
    this.detailState.set('loading');
    this.specState.set('loading');
    try {
      const detail = await this.connections.get(name);
      const [spec, typeDetail, secrets, rows, endpoints] = await Promise.all([
        this.typesApi.form(detail.type, detail.env),
        this.typesApi.get(detail.type).catch(() => null),
        this.secretsApi.list().catch(() => [] as SecretRow[]),
        this.connections.list({ q: name }).catch(() => null),
        this.endpointsApi.list().catch(() => null),
      ]);
      if (gen !== this.gen) return;
      this.version = detail.version;
      this.startName.set(detail.name);
      this.startEnv.set(detail.env);
      this.startConfig.set(detail.config);
      this.stored.set(this.enrich(detail.secrets, secrets));
      this.host.set(detail.host ?? rows?.items.find((r) => r.name === name)?.host ?? testTarget(detail.config).address);
      this.health.set(detail.health);
      this.typeDetail.set(typeDetail);
      this.endpoints.set(endpoints);
      this.spec.set(spec);
      this.specCard.set(detail.type);
      this.specState.set('ready');
      this.detail.set(detail);
      this.detailState.set('ready');
      void this.loadPolicies();
    } catch (e) {
      if (gen !== this.gen) return;
      const err = isApiError(e) ? e : null;
      this.detailError.set(err);
      this.detailState.set(err?.kind === 'not-found' ? 'not-found' : 'error');
      this.specState.set('error');
    }
  }

  protected retryEdit(): void {
    void this.startEdit(this.name()!, ++this.gen);
  }

  /** The detail's secrets with what Secrets knows of each: when it was set, whether Rotate can work. */
  private enrich(secrets: readonly StoredSecret[], rows: readonly SecretRow[]): StoredSecret[] {
    return secrets.map((s) => {
      const row = rows.find((r) => r.id === s.secretId);
      return row ? { ...s, updatedAt: row.rotatedAt, canRotate: row.canRotate, rotateReason: row.rotateReason } : s;
    });
  }

  private async loadPolicies(): Promise<void> {
    if (this.policies()) return;
    try {
      const [all, schema] = await Promise.all([this.policiesApi.all(), this.policiesApi.schema().catch(() => null)]);
      this.policies.set({ all, schema });
    } catch {
      // The prod alert then says it in general terms; Policies has the values.
    }
  }

  // ─── Wizard ───

  /** The form for a card: its type's schema with a Logs back end's preset, or a duplicate's copied settings. */
  private async openType(key: string, initial = false): Promise<boolean> {
    const card = findCard(this.groups(), key);
    if (!card && !this.copied) return false;
    if (this.specCard() === key && this.spec()) return true;
    const type = card?.type ?? this.copied!.type;
    const keepCopy = this.mode() === 'duplicate' && this.copied?.type === type;
    // Another type keeps what the person typed of the gateway's own fields: the name and the environment.
    if (!keepCopy && !initial && this.form()) {
      this.startName.set(this.current().name);
      this.startEnv.set(this.current().env);
    }
    this.specState.set('loading');
    this.specError.set(null);
    try {
      const [spec, detail] = await Promise.all([this.typesApi.form(type), this.typesApi.get(type).catch(() => null)]);
      this.startConfig.set(keepCopy ? this.copied!.config : this.preset(card));
      this.typeDetail.set(detail);
      this.spec.set(spec);
      this.specCard.set(key);
      this.specState.set('ready');
      this.outcome.set(null);
      this.testState.set('idle');
      this.testedKey = null;
      void this.loadPolicies();
      return true;
    } catch (e) {
      this.specError.set(isApiError(e) ? e : null);
      this.specState.set('error');
      return false;
    }
  }

  private preset(card: { readonly presetConfig: JsonValue } | null): JsonObject | null {
    const p = card?.presetConfig;
    return p && typeof p === 'object' && !Array.isArray(p) ? (p as JsonObject) : null;
  }

  protected onPick(key: string | null): void {
    this.selected.set(key);
    this.pickError.set(null);
  }

  protected async continueFromType(): Promise<void> {
    const key = this.selected();
    if (!key || !findCard(this.groups(), key)?.available) {
      this.pickError.set('Choose a connector type to continue.');
      afterNextRender(() => this.doc.querySelector<HTMLInputElement>('#step-1 .gw-choice-card__input:not([aria-disabled])')?.focus(), {
        injector: this.injector,
      });
      return;
    }
    this.pickError.set(null);
    this.go(2);
    await this.openType(key);
  }

  protected retrySpec(): void {
    if (this.mode() === 'duplicate' && !this.spec()) void this.startDuplicate(this.duplicate()!, this.gen);
    else if (this.selected()) void this.openType(this.selected()!);
  }

  /** Back / Continue: the panel, the stepper, focus on the step's heading and "Step 2 of 4: Configure". */
  go(n: StepNumber, opts: { readonly focus?: string | null; readonly initial?: boolean } = {}): void {
    this.step.set(n);
    if (n === 3) this.testIfChanged();
    if (n === 4) void this.loadEndpoints();
    if (opts.initial) return;
    afterNextRender(
      () => {
        const target = (opts.focus && this.doc.getElementById(opts.focus)) || this.doc.getElementById(`step-${n}-title`);
        target?.focus();
      },
      { injector: this.injector },
    );
    this.announcer.announce(`Step ${n} of ${STEP_NAMES.length}: ${STEP_NAMES[n - 1]}`);
  }

  /** Configure's Continue (the form has checked itself) or the edit view's Save changes. */
  protected onFormSubmit(value: ConnectionFormValue): void {
    if (this.mode() === 'edit') {
      void this.saveEdit(value);
      return;
    }
    this.nameProblem.set(null);
    this.draft.set(value);
    this.go(3);
  }

  /** The Configure form owns selection state; requests and stored-secret consent stay with the page. */
  async loadDatabases(request: DatabaseDiscoveryRequest, confirm: string | null = null): Promise<void> {
    const form = this.form();
    const spec = this.spec();
    if (!form || !spec || !form.startDatabaseDiscovery(request.key)) return;
    const run = ++this.databaseRun;
    const gen = this.gen;
    try {
      const result = await this.connections.databases({ type: spec.type, env: request.value.env,
        config: request.value.config, secrets: request.value.secrets,
        ...(this.mode() === 'edit' ? { existing: this.name() } : {}), ...(confirm ? { confirm } : {}) });
      if (run === this.databaseRun && gen === this.gen && form === this.form()) form.completeDatabaseDiscovery(request.key, result);
    } catch (e) {
      if (run !== this.databaseRun || gen !== this.gen || form !== this.form()) return;
      const error = isApiError(e) ? e : null;
      if (!form.completeDatabaseDiscovery(request.key, null, error?.detail ?? "The core didn't answer. Try again.")) return;
      if (error?.confirm === 'send-secret-to-new-host') {
        this.newHost.set({ title: error.title, detail: error.detail, value: request.value, discoveryKey: request.key });
        this.hostDialog()?.openAfterRender();
      } else if (error?.kind === 'unprocessable') {
        form.applyServerError(error);
      }
    }
  }

  // ─── Test ───

  private testIfChanged(): void {
    const v = this.form()?.value() ?? null;
    if (v) this.draft.set(v);
    const draft = this.draft();
    if (draft && draftKey(this.spec()?.type ?? null, draft) !== this.testedKey) void this.runTest();
  }

  async runTest(): Promise<void> {
    const v = this.form()?.value() ?? this.draft();
    const spec = this.spec();
    if (!v || !spec || this.testState() === 'testing') return;
    this.draft.set(v);
    const run = ++this.testRun;
    const key = draftKey(spec.type, v);
    this.testState.set('testing');
    let outcome: TestOutcome;
    try {
      outcome = await this.connections.testDraft({ type: spec.type, env: v.env, config: v.config, secrets: v.secrets });
    } catch (e) {
      const err = isApiError(e) ? e : null;
      // A refused draft (422: a value this env forbids) says so here; its fields show it back in Configure.
      if (err?.kind === 'unprocessable' && run === this.testRun) this.form()?.applyServerError(err);
      outcome = this.failure(err);
    }
    if (run !== this.testRun) return;
    this.outcome.set(outcome);
    this.testedKey = key;
    this.testedAt.set(new Date(this.time.now()).toISOString());
    this.testState.set('done');
  }

  /** A test the core couldn't run (no answer, a refused draft) as the panel's failed result. */
  private failure(err: ApiError | null): TestOutcome {
    return {
      ok: false,
      latencyMs: 0,
      serverVersion: null,
      identity: null,
      permissions: null,
      hints: [],
      checks: [],
      error: { code: err?.kind === 'unprocessable' ? 'refused' : 'error', message: errorText(err), technical: null },
      needsConfirmation: null,
      confirmationId: null,
      suggestedPolicyChange: null,
      details: null,
    };
  }

  /** Trust the key or certificate the test stopped for: the core patches the draft and tests again. */
  async trust(confirmationId: string): Promise<void> {
    if (this.trusting()) return;
    this.trusting.set(true);
    try {
      const r = await this.connections.confirmDraft(confirmationId);
      const form = this.form();
      if (form && r.config && Object.keys(r.config).length) form.state().model.update((m) => merge(m, r.config) as JsonObject);
      const v = form?.value() ?? this.draft();
      if (v) {
        this.draft.set(v);
        this.testedKey = draftKey(this.spec()?.type ?? null, v);
      }
      if (r.retest) this.outcome.set(r.retest);
      this.testedAt.set(new Date(this.time.now()).toISOString());
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "Couldn't trust it", body: isApiError(e) ? e.detail : 'Test again, then trust what it shows.' });
    } finally {
      this.trusting.set(false);
    }
  }

  /** "Change user": back to Configure with focus on the User field. */
  protected changeUser(): void {
    const s = this.form()?.state();
    const node = s?.compiled.all.find((n) => /^(user|username|login)$/i.test(n.key));
    this.go(2, { focus: node && s ? s.id(node) : null });
  }

  // ─── Add to endpoint ───

  private async loadEndpoints(): Promise<void> {
    if (this.endpointsState() === 'loading') return;
    if (!this.endpoints()) {
      this.endpointsState.set('loading');
      try {
        this.endpoints.set(await this.endpointsApi.list());
        this.endpointsState.set('ready');
      } catch (e) {
        this.endpointsError.set(isApiError(e) ? e : null);
        this.endpointsState.set('error');
        return;
      }
    } else {
      this.endpointsState.set('ready');
    }
    if (!this.chosenTouched) this.chosen.set(initialEndpoints(this.choices(), this.current().env));
  }

  protected retryEndpoints(): void {
    this.endpoints.set(null);
    void this.loadEndpoints();
  }

  protected toggleEndpoint(c: EndpointChoice, event: Event): void {
    const input = event.target as HTMLInputElement;
    if (c.refusal) {
      input.checked = false;
      return;
    }
    this.chosenTouched = true;
    const on = input.checked;
    this.chosen.update((l) => (on ? [...l.filter((s) => s !== c.slug), c.slug] : l.filter((s) => s !== c.slug)));
  }

  /** Save connection: create it, then add it where it may go (the core checks each endpoint again first). */
  async save(event?: Event, confirm: string | null = null): Promise<void> {
    event?.preventDefault();
    const form = this.form();
    const spec = this.spec();
    if (this.saving() || !form || !spec) return;
    if (!form.databaseSelectionValid()) {
      this.go(2);
      return;
    }
    const v = form.value();
    this.saving.set(true);
    try {
      await this.connections.create({ name: v.name, type: spec.type, env: v.env, config: v.config, secrets: v.secrets, ...(confirm ? { confirm } : {}) });
    } catch (e) {
      this.saving.set(false);
      if (isApiError(e) && e.kind === 'conflict' && e.confirm === 'danger') {
        this.askDanger(e.title, e.detail, () => void this.save(undefined, withConfirm(confirm, 'danger')));
        return;
      }
      this.refused(e, v);
      return;
    }
    const usable = new Set(this.choices().filter((c) => !c.refusal).map((c) => c.slug));
    const added: string[] = [];
    const notAdded: { readonly slug: string; readonly why: string }[] = [];
    for (const slug of this.chosen().filter((s) => usable.has(s))) {
      try {
        const preview = await this.endpointsApi.previewConnection(slug, v.name);
        if (preview.refusal) {
          notAdded.push({ slug, why: preview.refusal });
          continue;
        }
        await this.endpointsApi.addConnection(slug, v.name);
        added.push(slug);
      } catch (e) {
        notAdded.push({ slug, why: isApiError(e) ? e.detail : "The core didn't answer." });
      }
    }
    this.saving.set(false);
    const open = { label: 'Open Connections', onClick: () => void this.router.navigateByUrl('/connections') };
    if (notAdded.length) {
      this.toasts.show({
        tone: 'warning',
        title: `Connection ${v.name} saved, not added to ${list(notAdded.map((n) => n.slug))}`,
        body: [added.length ? `Added to ${list(added)}.` : null, ...notAdded.map((n) => n.why)].filter(Boolean).join(' '),
        actions: [open],
      });
    } else {
      // How often its health is checked: the core says it with the connection (step API).
      const interval = await this.connections.get(v.name).then((d) => d.healthIntervalSeconds ?? null, () => null);
      this.toasts.show({ tone: 'success', ...savedToast(v.name, added, interval), actions: [open] });
    }
    // The connection exists now: its own view, not a wizard that would save it twice.
    void this.router.navigate(['/connections', v.name], { replaceUrl: true });
  }

  /** The core refused the new connection: the name (409) or a field (422) go back to Configure to be fixed. */
  private refused(e: unknown, v: ConnectionFormValue): void {
    const err = isApiError(e) ? e : null;
    const nameId = `${this.form()?.idPrefix() ?? 'cf'}-name`;
    if (err?.kind === 'conflict') {
      this.nameProblem.set({ name: v.name, message: err.detail });
      this.go(2, { focus: nameId });
      return;
    }
    if (err?.kind === 'unprocessable') {
      const others = this.form()?.applyServerError(err) ?? [];
      const name = others.find((o) => o.field === '/name');
      if (name) this.nameProblem.set({ name: v.name, message: name.message });
      const rest = others.filter((o) => o.field !== '/name');
      this.go(2, { focus: name ? nameId : null });
      if (rest.length || !err.errors.length) {
        this.toasts.show({ tone: 'danger', title: err.title, body: rest.map((o) => o.message).join(' ') || err.detail });
      }
      return;
    }
    this.toasts.show({ tone: 'danger', title: `Couldn't save ${v.name}`, body: err?.detail ?? "The core didn't answer. Try again." });
  }

  // ─── Edit: save, test, secrets, delete ───

  private async saveEdit(value: ConnectionFormValue, confirm: string | null = null): Promise<void> {
    const name = this.name()!;
    if (this.saving()) return;
    this.saving.set(true);
    try {
      await this.connections.update(name, { config: value.config, env: value.env, version: this.version, confirm });
      this.toasts.show({
        tone: 'success',
        title: `Connection ${name} saved`,
        body: 'The new settings apply from the next call. Calls in flight finish with the old ones.',
      });
      await this.reloadDetail();
    } catch (e) {
      const err = isApiError(e) ? e : null;
      if (err?.kind === 'conflict' && err.confirm === 'danger') {
        this.askDanger(err.title, err.detail, () => void this.saveEdit(value, withConfirm(confirm, 'danger')));
      } else if (err?.kind === 'conflict' && err.confirm === 'send-secret-to-new-host') {
        this.newHost.set({ title: err.title, detail: err.detail, value });
        this.hostDialog()?.openAfterRender();
      } else if (err?.kind === 'conflict') {
        this.toasts.show({ tone: 'warning', title: `${name} changed meanwhile`, body: `${err.detail} Its current settings are shown again.` });
        await this.reloadDetail().catch(() => undefined);
      } else if (err?.kind === 'unprocessable') {
        const others = this.form()?.applyServerError(err) ?? [];
        if (others.length || !err.errors.length) {
          this.toasts.show({ tone: 'danger', title: err.title, body: others.map((o) => o.message).join(' ') || err.detail });
        }
      } else {
        this.toasts.show({ tone: 'danger', title: `Couldn't save ${name}`, body: err?.detail ?? "The core didn't answer. Try again." });
      }
    } finally {
      this.saving.set(false);
    }
  }

  protected sendToNewHost(): void {
    const pending = this.newHost();
    this.hostDialog()?.close('send');
    if (pending?.discoveryKey) void this.loadDatabases({ key: pending.discoveryKey, value: pending.value }, 'send-secret-to-new-host');
    else if (pending) void this.saveEdit(pending.value, 'send-secret-to-new-host');
  }

  private askDanger(title: string, detail: string, retry: () => void): void {
    this.danger.set({ title, detail, retry });
    this.dangerDialog()?.openAfterRender();
  }

  protected saveDespiteDanger(): void {
    const pending = this.danger();
    this.dangerDialog()?.close('save');
    pending?.retry();
  }

  protected discard(): void {
    this.form()?.reset();
    this.announcer.announce('Changes discarded');
  }

  /** The detail after a save: the form opens on the saved settings, the version follows. */
  private async reloadDetail(): Promise<void> {
    const name = this.name()!;
    const [detail, secrets] = await Promise.all([this.connections.get(name), this.secretsApi.list().catch(() => [] as SecretRow[])]);
    this.version = detail.version;
    this.startEnv.set(detail.env);
    this.startConfig.set(detail.config);
    this.stored.set(this.enrich(detail.secrets, secrets));
    this.health.set(detail.health);
    this.detail.set(detail);
  }

  private async refreshHealth(): Promise<void> {
    try {
      const detail = await this.connections.get(this.name()!);
      this.health.set(detail.health);
    } catch {
      // The next event or a reload brings it.
    }
  }

  /** The secrets after Replace or Rotate: the field shows the new version, the rest of the form stays as typed. */
  private async refreshSecrets(): Promise<void> {
    const name = this.name()!;
    const [detail, secrets] = await Promise.all([this.connections.get(name), this.secretsApi.list().catch(() => [] as SecretRow[])]);
    this.stored.set(this.enrich(detail.secrets, secrets));
    this.version = detail.version;
  }

  /** The header's Test connection: in place on the button, the result as a toast (kit), a host key in a dialog. */
  async testStored(): Promise<void> {
    const name = this.name()!;
    if (this.editTesting()) return;
    this.editTesting.set(true);
    this.announcer.announce(`Testing ${name}`);
    try {
      const outcome = await this.connections.test(name);
      if (outcome.needsConfirmation && outcome.confirmationId) {
        this.editConfirm.set(outcome);
        this.trustDialog()?.openAfterRender();
      } else {
        this.toasts.show(testToast(name, outcome, this.host()));
      }
      await this.refreshHealth();
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `Couldn't test ${name}`, body: isApiError(e) ? e.detail : "The core didn't answer." });
    } finally {
      this.editTesting.set(false);
    }
  }

  protected async trustStored(): Promise<void> {
    const name = this.name()!;
    const id = this.editConfirm()?.confirmationId;
    if (!id || this.trusting()) return;
    this.trusting.set(true);
    try {
      const r = await this.connections.confirmTest(name, id);
      this.trustDialog()?.close('trust');
      if (r.retest) this.toasts.show(testToast(name, r.retest, this.host()));
      await this.reloadDetail();
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "Couldn't trust it", body: isApiError(e) ? e.detail : 'Test again, then trust what it shows.' });
    } finally {
      this.trusting.set(false);
    }
  }

  protected onSecretAction(action: SecretAction): void {
    const word = secretWord(this.spec()?.secretFields ?? [], action.node.pointer);
    if (action.kind === 'save') {
      if (!action.secretId) return;
      if (this.current().env === 'prod') {
        // The core keeps a comment with every change of a prod secret (Setup changes): ask for it first.
        this.replacingProd.set({ secretId: action.secretId, word, value: action.value });
        this.comment.set('');
        this.commentError.set(null);
        this.commentDialog()?.openAfterRender();
        return;
      }
      void this.replaceSecret(action.secretId, word, action.value, null);
    } else if (action.kind === 'rotate') {
      if (!action.secretId) return;
      const s = this.stored().find((x) => x.secretId === action.secretId);
      this.rotating.set({ secretId: action.secretId, word, version: s?.version ?? null, key: s?.name?.split('/').at(-1) ?? word });
      this.rotateDialog()?.openAfterRender();
    } else {
      void this.testReference(action.ref);
    }
  }

  protected onComment(event: Event): void {
    this.comment.set((event.target as HTMLTextAreaElement).value);
    this.commentError.set(null);
  }

  protected confirmComment(event: Event): void {
    event.preventDefault();
    const pending = this.replacingProd();
    const text = this.comment().trim();
    if (!pending) return;
    if (!text) {
      this.commentError.set('Add a comment: it is required to change a secret on prod.');
      this.doc.getElementById('dlg-comment-text')?.focus();
      return;
    }
    this.commentDialog()?.close('replace');
    void this.replaceSecret(pending.secretId, pending.word, pending.value, text);
  }

  private async replaceSecret(id: string, word: string, value: string, reason: string | null): Promise<void> {
    const name = this.name()!;
    try {
      const r = await this.secretsApi.replace(id, { value, reason });
      this.toasts.show({
        tone: 'success',
        title: `${word.charAt(0).toUpperCase()}${word.slice(1)} of ${name} saved`,
        body: `Version ${r.version} is in use from the next call. Nobody can view it.`,
      });
      await this.refreshSecrets();
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `Couldn't save the ${word} of ${name}`, body: isApiError(e) ? e.detail : "The core didn't answer." });
    }
  }

  protected async confirmRotate(): Promise<void> {
    const r = this.rotating();
    this.rotateDialog()?.close('rotate');
    if (!r) return;
    const name = this.name()!;
    try {
      await this.secretsApi.rotate(r.secretId);
      this.toasts.show({
        tone: 'info',
        title: `Rotating ${name}/${r.key}`,
        body: `The connector sets a new value at the source and tests ${name} with it. Nobody can view it.`,
      });
      await this.refreshSecrets();
    } catch (e) {
      this.toasts.show({ tone: 'warning', title: `${name}/${r.key} wasn't rotated`, body: isApiError(e) ? e.detail : "The core didn't answer." });
    }
  }

  private async testReference(ref: string): Promise<void> {
    const name = this.name() ?? this.current().name;
    if (!ref) {
      this.toasts.show({ tone: 'warning', title: 'Enter the reference first', body: 'Paste the reference from the store, then test it.' });
      return;
    }
    try {
      const r = await this.secretsApi.testReference(ref);
      this.toasts.show(
        r.found
          ? { tone: 'success', title: `Reference of ${name} works`, body: `Found${r.resolvedVersion ? ` version ${r.resolvedVersion}` : ''}. The value was neither shown nor kept.` }
          : { tone: 'danger', title: `Reference of ${name} not found`, body: 'Check the path and the key in the store.' },
      );
    } catch (e) {
      const err = isApiError(e) ? e : null;
      this.toasts.show(
        err?.kind === 'not-available'
          ? { tone: 'neutral', title: 'Testing references is not available yet', body: 'This build of the core has no external secret stores.' }
          : { tone: 'danger', title: "Couldn't test the reference", body: err?.detail ?? "The core didn't answer." },
      );
    }
  }

  protected async openDelete(opener: HTMLElement): Promise<void> {
    this.deleteError.set(null);
    this.pending.set([]);
    this.deleteDialog()?.open(opener);
    try {
      const page = await this.approvalsApi.list('pending');
      this.pending.set(page.items.filter((a) => a.connection === this.name()));
    } catch {
      // The core denies them anyway; the dialog just can't name them.
    }
  }

  async confirmDelete(): Promise<void> {
    const name = this.name()!;
    if (this.deleting()) return;
    this.deleting.set(true);
    try {
      await this.connections.delete(name, name);
      this.deleteDialog()?.close('delete');
      const gone = this.uses()
        .filter((u) => u.tools.length)
        .map((u) => u.slug);
      const back = { label: 'Back to Connections', onClick: () => void this.router.navigateByUrl('/connections') };
      this.toasts.show({ tone: 'success', ...deletedToast(name, gone), actions: [back] });
      void this.router.navigateByUrl('/connections', { replaceUrl: true });
    } catch (e) {
      this.deleteError.set(errorText(isApiError(e) ? e : null));
    } finally {
      this.deleting.set(false);
    }
  }

  // ─── Template helpers ───

  protected pendingFrom(a: ApprovalItem): string {
    return a.client ? ` from ${clientName(a.client)}` : a.tokenLabel ? ` from ${a.tokenLabel}` : '';
  }

  protected modeOf(mode: string): (typeof MODES)[string] {
    return MODES[mode] ?? MODES['OFF'];
  }

  protected trustLabel(o: TestOutcome): string {
    switch (o.needsConfirmation?.kind) {
      case 'host-key':
        return 'Trust this key and test again';
      case 'tls-cert':
        return 'Trust this certificate and test again';
      case 'exec-plugin':
        return 'Allow the command and test again';
      default:
        return 'Trust and test again';
    }
  }

  protected readonly list = list;
}

/** One more confirmation for a request the core asked about again: they accumulate, separated by commas. */
function withConfirm(previous: string | null, next: string): string {
  return previous ? `${previous},${next}` : next;
}
