import { NgTemplateOutlet } from '@angular/common';
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
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../code/code-block';
import { ApiError, isApiError } from '../../core/api/api-error';
import { ApprovalItem, ApprovalsApi } from '../../core/api/approvals.api';
import { ConnectionDetail, ConnectionHealth, ConnectionsApi, StoredSecret, Suggested, TestOutcome } from '../../core/api/connections.api';
import { ConnectionFormSpec, ConnectorCatalog, ConnectorTool, ConnectorTypeDetail, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointSummary, EndpointsApi, ToolGroup, ToolRow } from '../../core/api/endpoints.api';
import { Policies, PoliciesApi } from '../../core/api/policies.api';
import { MasterKeyApi, SecretRow, SecretsApi, rotationUnconfirmed } from '../../core/api/secrets.api';
import { Env, JsonObject, JsonValue, ToolMode } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { compactReferenceError } from '../../core/secret-reference-feedback';
import { CoreStore } from '../../core/stores/core.store';
import { PRODUCT } from '../../shell/title.strategy';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AccessChanges, GwAccessSwitch } from '../../kit/access-switch';
import { GwAriaDisabled } from '../../kit/aria-disabled';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { ConnectionFormValue, DatabaseDiscoveryRequest, GwConnectionForm, InitialCredentialReference, ProdDefault, SuggestionRequest, signInTarget } from '../../kit/elements/connection-form/connection-form';
import { FileBrowseRequest, FileBrowseTrust } from '../../kit/elements/connection-form/remote-files';
import { ProjectDiscoveryRequest } from '../../kit/elements/connection-form/cloud-projects';
import { databaseSelection, removeConfigPath } from '../../kit/elements/connection-form/database-selection';
import { GwConnectionTest, TestState, testConfirmTitle, testFacts, usesGoogleAdc } from '../../kit/elements/connection-form/connection-test';
import { GwConnectorPicker } from '../../kit/elements/connector-card/connector-card';
import { withCompatibleServices } from '../../kit/elements/shared/compatible-services';
import { connectorCategories, filterConnectorGroups } from '../../kit/elements/shared/connector-categories';
import { clientName } from '../../kit/elements/shared/client-mark';
import { connectorIcon } from '../../kit/elements/shared/connector-icons';
import { MODES, healthLook } from '../../kit/elements/shared/dictionaries';
import { literalParts } from '../../kit/elements/shared/literals';
import { GwIcon } from '../../kit/icons';
import { GwSearchClear, GwSearchClearButton } from '../../kit/search';
import { ModulesApi } from '../../core/api/modules.api';
import { McpProfile, McpServersApi, PluginsApi } from '../../core/api/plugins.api';
import { formatBytes } from '../../core/time/time-format';
import { ConnectorsData } from '../connectors/connectors.data';
import { failureText } from '../connectors/dialogs/failure';
import { FEATURED_MCP_SERVERS } from '../connectors/mcp-featured-servers';
import { RegistryResult } from '../connectors/mcp-registry-results';
import { DirectPluginInstaller, GwPluginReviewDialog, PluginInstaller } from '../connectors/dialogs/plugin-review.dialog';
import { GwTabs } from '../../kit/tabs';
import { ToastService } from '../../kit/toast.service';
import { getAt, setAt } from '../../schema-form/compile';
import { SecretAction, storedReference } from '../../schema-form/schema-form';
import { referenceKind } from '../../schema-form/secret-sources';
import {
  EndpointChoice,
  HealthStat,
  Mode,
  STEP_NAMES,
  StepNumber,
  ToolPreview,
  activityQuery,
  cardKey,
  copyName,
  defaultCardKey,
  deletedToast,
  endpointChoices,
  endpointUses,
  findCard,
  firstRunModes,
  firstRunPreview,
  firstRunTestText,
  firstRunToast,
  fixSnippet,
  isFirstRun,
  healthStat,
  initialEndpoints,
  list,
  policyDefaults,
  savedToast,
  secretWord,
  secretsSentence,
  sparkPoints,
  starterEndpoint,
  stepperSteps,
  testSummary,
  testTarget,
  testToast,
  toolCount,
  toolPreview,
  toolsLine,
  toolsSentence,
  usage,
  usedByMeta,
  usedByRows,
} from './connection-new-view';
import { GwConnectionCalls } from './connection-calls';
import { ConnectionToolModes } from './connection-tool-modes';
import { GwMcpConfigure, McpReviewed } from './mcp/mcp-configure';
import { EndpointOutcome, GwMcpEndpoints, McpFirstEndpoint, McpServerConnected } from './mcp/mcp-endpoints';
import {
  EXTERNAL_KEY,
  MCP_SERVER_TYPE,
  McpChoice,
  OFFICIAL_REGISTRY,
  PASTE_KEY,
  RegistryFinder,
  featuredKey,
  isMcpKey,
  mcpGroups,
  officialEntry,
  pasteGroup,
  profileKey,
  readPaste,
  registryCard,
  registryEntry,
  registryKey,
  registryLabel,
} from './mcp/mcp-picker';
import { GwMcpReview } from './mcp/mcp-review';

/** What step 1 shows: every kind, the connector types, or the MCP servers (`?show=mcp`). */
export type PickerShow = 'all' | 'connectors' | 'mcp';

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
  if (!e) return "The core didn't answer. Check that GateRook is running, then try again.";
  return e.kind === 'not-available' ? 'This build of the core has no such page yet.' : `${e.title}. ${e.detail}`;
}

/**
 * Add connection (ARCHITECTURE.md §8.4): one page, three modes.
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
 * - **Edit** (`/connections/<name>`): health (live through `connection.health`), its calls over the last 30 days
 *   (`GwConnectionCalls`), Test connection, Settings with stored secrets (Replace, Rotate where the source can),
 *   references and Delete, and Tools (counted in its tab) with endpoint modes.
 *   Both tab panels stay mounted so changing tabs preserves the settings draft.
 *
 * Step changes move focus to the step's heading and announce "Step 2 of 4: Configure", as the kit's page script does.
 */
/** A stored reference a Save points elsewhere: a new URI, another store, or both. */
interface ReferenceChange {
  readonly secretId: string;
  readonly field: string;
  readonly uri: string;
  readonly store: string | null;
}

@Component({
  selector: 'gw-connection-new-page',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    GwIcon,
    GwTabs,
    GwToggle,
    GwAriaDisabled,
    GwDialog,
    GwDialogClose,
    GwCodeBlock,
    GwConnectorPicker,
    GwSearchClear,
    GwSearchClearButton,
    GwPluginReviewDialog,
    GwConnectionForm,
    GwConnectionTest,
    GwAccessSwitch,
    ConnectionToolModes,
    GwConnectionCalls,
    GwMcpConfigure,
    GwMcpReview,
    GwMcpEndpoints,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // A catalog plugin chosen here installs through Connectors' review, straight with the plugins API. An MCP server's
  // steps review, start and list it through the same data as Connectors › MCP servers.
  providers: [{ provide: PluginInstaller, useClass: DirectPluginInstaller }, ConnectorsData],
  host: { class: 'gw-page gw-page--narrow', '[class.gw-form--sticky-actions]': "mode() === 'edit'" },
  templateUrl: './connection-new.page.html',
})
export class ConnectionNewPage {
  private readonly typesApi = inject(ConnectorTypesApi);
  private readonly pluginsApi = inject(PluginsApi);
  private readonly modulesApi = inject(ModulesApi);
  private readonly mcpApi = inject(McpServersApi);
  private readonly mcpData = inject(ConnectorsData);
  private readonly connections = inject(ConnectionsApi);
  private readonly accessChanges = inject(AccessChanges);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly policiesApi = inject(PoliciesApi);
  private readonly secretsApi = inject(SecretsApi);
  private readonly approvalsApi = inject(ApprovalsApi);
  private readonly masterKey = inject(MasterKeyApi);
  private readonly events = inject(EventStream);
  protected readonly core = inject(CoreStore);
  private readonly title = inject(Title);
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
  /** `?show=mcp` (Add MCP server, Connectors and Overview): step 1 opens on the MCP servers. */
  readonly show = input<string | null>(null);
  /** `?profile=<id>`: Configure on GateRook's verified profile of that vendor's server (Connectors' Cloud platforms). */
  readonly profile = input<string | null>(null);
  /** `?registry=<name>`: Configure on that entry of the Official MCP Registry (Connectors' Popular cards). */
  readonly registry = input<string | null>(null);
  /** `?registryServer=<name>`: Configure on a search result of the registry the core searches (Connectors' Use). */
  readonly registryServer = input<string | null>(null);
  /** `?catalog=<id>@<version>`: Configure on that MCP server of GateRook's signed catalog (Connectors › Catalog). */
  readonly catalogEntry = input<string | null>(null, { alias: 'catalog' });
  /** `?endpoint=<slug>` (Overview's Add service): the endpoint step 4 chooses first, when it may take the connection. */
  readonly endpoint = input<string | null>(null);

  readonly mode = computed<Mode>(() => (this.name() ? 'edit' : this.duplicate() ? 'duplicate' : 'add'));
  protected readonly formMode = computed<'create' | 'duplicate' | 'edit'>(() => {
    const m = this.mode();
    return m === 'add' ? 'create' : m;
  });
  readonly step = signal<StepNumber>(1);
  protected readonly stepCount = STEP_NAMES.length;
  protected readonly modeValue: Record<ToolMode, string> = { OFF: 'off', READ_ONLY: 'read', APPROVAL: 'approval', BYPASS: 'bypass' };
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
  /**
   * The core's cards, with a card for every compatible service of a built-in type (Supabase after PostgreSQL). The
   * generic MCP server type isn't among them: MCP servers have their own section after the connector types.
   */
  protected readonly groups = computed(() =>
    withCompatibleServices(this.catalog()?.groups ?? [])
      .map((g) => ({ ...g, cards: g.cards.filter((c) => c.type !== MCP_SERVER_TYPE) }))
      .filter((g) => g.cards.length));
  /** The search above the picker: it filters the types by name, other names and category, and reads a paste. */
  protected readonly typeQuery = signal('');
  /** All, Connectors or MCP servers (the switch beside the search). */
  readonly showKind = signal<PickerShow>('all');
  /** The core's groups split into the picker's categories (SQL databases, NoSQL databases, Data warehouses…). */
  protected readonly categories = computed(() => connectorCategories(this.groups()));
  /** GateRook's verified profiles of vendors' own MCP servers; none from a core without them. */
  protected readonly mcpProfiles = signal<readonly McpProfile[]>([]);
  /** The MCP section before the registry: External MCP server, the verified profiles, the Popular picks. */
  protected readonly mcpSection = computed(() => mcpGroups(this.mcpProfiles()));
  /** The Official MCP Registry, searched from three characters of the search (never with a paste). */
  protected readonly finder = new RegistryFinder((q, cursor) => this.mcpData.searchRegistry(q, cursor ?? null));
  /** "the Official MCP Registry", or the registry the core is set to once an answer names it. */
  protected readonly registryWords = computed(() => registryLabel(this.finder.registry()));
  protected readonly registryTitle = computed(() => {
    const registry = this.finder.registry();
    return registry && registry !== OFFICIAL_REGISTRY ? 'MCP registry' : 'Official MCP Registry';
  });
  protected readonly registryGroup = computed(() => {
    const results = this.finder.results();
    return results.length && this.finder.searched() !== null ? { name: this.registryTitle(), cards: results.map(registryCard) } : null;
  });
  /** A configuration, URL, command or connection string pasted into the search: its card stands alone. */
  protected readonly pasteRead = computed(() => readPaste(this.typeQuery()));
  protected readonly pasted = computed(() => {
    const read = this.pasteRead();
    return read ? pasteGroup(read, findCard(this.groups(), 'postgres')) : null;
  });
  /** Every card that can be chosen, the ones the search hides too: what Continue looks the choice up in. */
  protected readonly pickable = computed(() => [
    ...this.groups(),
    ...this.mcpSection(),
    ...(this.registryGroup() ? [this.registryGroup()!] : []),
    ...(this.pasted() ? [this.pasted()!] : []),
  ]);
  protected readonly card = computed(() => findCard(this.pickable(), this.selected()));
  /** What the picker shows: the paste's card alone, else the connector categories, then the MCP section. */
  protected readonly pickerGroups = computed(() => {
    if (this.pasteRead()) return this.pasted() ? [this.pasted()!] : [];
    const show = this.showKind();
    const query = this.typeQuery();
    return [
      ...(show !== 'mcp' ? filterConnectorGroups(this.categories(), query) : []),
      ...(show !== 'connectors' ? filterConnectorGroups(this.mcpSection(), query) : []),
      ...(show !== 'connectors' && this.registryGroup() ? [this.registryGroup()!] : []),
    ];
  });
  /** The registry is asked about this query (three characters or more, not a paste, MCP servers shown). */
  protected readonly registryAsked = computed(() =>
    this.showKind() !== 'connectors' && !this.pasteRead() && this.typeQuery().trim().length >= 3);
  /** "34 connector types and 28 MCP servers", or what matches while searching (a status region reads it). */
  protected readonly typeCountText = computed(() => {
    if (this.pasteRead()) return this.pasted() ? 'Read from your paste: one card, chosen' : 'GateRook can’t read this paste';
    const count = (groups: readonly { readonly cards: readonly unknown[] }[]) => groups.reduce((n, g) => n + g.cards.length, 0);
    const query = this.typeQuery().trim();
    const show = this.showKind();
    const types = count(query ? filterConnectorGroups(this.categories(), query) : this.categories());
    const servers = count(query ? filterConnectorGroups(this.mcpSection(), query) : this.mcpSection());
    const parts = [
      ...(show !== 'mcp' ? [`${types} connector ${types === 1 ? 'type' : 'types'}`] : []),
      ...(show !== 'connectors' ? [`${servers} MCP ${servers === 1 ? 'server' : 'servers'}`] : []),
    ];
    const registry = this.registryAsked()
      ? this.finder.searching() ? `; searching ${this.registryWords()}`
        : this.finder.searched() !== null ? `; ${this.finder.results().length} in ${this.registryWords()}` : ''
      : '';
    return `${parts.join(' and ')}${query ? ` match “${query}”` : ''}${registry}`;
  });
  /** Nothing to choose: no type or server matches and the registry found nothing (or isn't asked). */
  protected readonly nothingMatches = computed(() =>
    !this.pickerGroups().length && !this.pasteRead() && !(this.registryAsked() && this.finder.searching()));
  /**
   * The connector module the picked type's form is waiting for: one of GateRook's own connectors that isn't part of
   * the installer. The form's request installs it; `module.state` says which step it is at. Nothing is asked.
   */
  protected readonly moduleDownload = signal<{
    readonly id: string;
    readonly name: string;
    readonly sizeBytes: number;
    readonly step: string;
    readonly message: string | null;
  } | null>(null);
  /** What step 2 says while the module arrives ("Downloading Cassandra (9.4 MB). This happens once."). */
  protected readonly moduleDownloadText = computed(() => {
    const m = this.moduleDownload();
    if (!m) return '';
    switch (m.step) {
      case 'runtime':
        return `Preparing the Java runtime that ${m.name} runs on. This happens once.`;
      case 'verifying':
        return `Checking the download of ${m.name}.`;
      case 'installing':
        return `Installing ${m.name}.`;
      default:
        return `Downloading ${m.name} (${formatBytes(m.sizeBytes)}). This happens once.`;
    }
  });
  /** A catalog plugin that isn't installed yet: Continue reviews and installs it first. */
  protected readonly installPicked = computed(() => {
    const c = this.card();
    return c && !c.available && c.catalogRef ? c : null;
  });
  /** "3 connections use PostgreSQL" under the picker (ARCHITECTURE.md §8.4). */
  protected readonly usage = computed(() => {
    const c = this.card();
    return c ? usage(c) : null;
  });
  /** An MCP server card is chosen: Continue starts the MCP steps (Configure, Review, Add to endpoint). */
  protected readonly mcpServerPicked = computed(() => isMcpKey(this.selected()));
  /** The note under the picker for a chosen MCP server: where it comes from; nothing runs before its review. */
  protected readonly mcpNote = computed(() => {
    const key = this.selected();
    if (!isMcpKey(key)) return null;
    if (key === EXTERNAL_KEY) return 'You enter its command or URL next. Nothing runs before the review.';
    if (key === PASTE_KEY) return 'Filled in from your paste. Nothing runs before the review.';
    const profile = this.mcpProfiles().find((p) => profileKey(p.id) === key);
    if (profile) return `${profile.publisher}’s own server with GateRook’s verified profile. Nothing runs before the review.`;
    // A Popular pick always comes from the official registry; a search result from the one the core searched.
    const from = key!.startsWith(registryKey('')) ? this.registryWords() : 'the Official MCP Registry';
    return `From ${from}. Nothing runs before the review.`;
  });

  // ─── MCP server: steps 2–4 ───
  /** The MCP server chosen in step 1 (Configure started from it); null on a connector type. */
  protected readonly mcpChoice = signal<McpChoice | null>(null);
  /** Continue is reading a Popular card's entry from the registry. */
  protected readonly resolvingMcp = signal(false);
  /** What the review is about (Configure's request and the core's answer). */
  protected readonly mcpReviewed = signal<McpReviewed | null>(null);
  /** The server once it connected (or started): it is saved, with its tools Off until step 4. */
  protected readonly mcpServer = signal<McpServerConnected | null>(null);
  /** Which steps the wizard runs: a connector type's or an MCP server's. */
  readonly flow = computed<'connector' | 'mcp'>(() => (this.mcpChoice() ? 'mcp' : 'connector'));
  private readonly mcpConfigure = viewChild(GwMcpConfigure);
  private readonly mcpReview = viewChild(GwMcpReview);
  private readonly mcpEndpoints = viewChild(GwMcpEndpoints);

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
  protected readonly setupReferences = computed<readonly InitialCredentialReference[]>(() => {
    const detail = this.detail();
    if (!detail?.setupRequired) return [];
    const fields = new Set(detail.secrets.map(secret => secret.field));
    return (detail.setupSources ?? []).flatMap(source => {
      const field = source['field'];
      const ref = source['reference'];
      const store = source['store'];
      if (typeof field !== 'string' || fields.has(field) || typeof ref !== 'string' || !referenceKind(ref)
        || ['local', 'file', 'vault-dynamic'].includes(String(source['source']))) return [];
      fields.add(field);
      return [{ field, ref, ...(typeof store === 'string' && store ? { store } : {}) }];
    });
  }, { equal: (before, after) => before.length === after.length && before.every((reference, index) =>
    reference.field === after[index].field && reference.ref === after[index].ref && reference.store === after[index].store) });
  protected readonly nameProblem = signal<{ readonly name: string; readonly message: string } | null>(null);
  protected readonly formProblem = signal<{ readonly title: string; readonly message: string } | null>(null);
  private readonly policies = signal<{ readonly all: Policies; readonly schema: JsonObject | null } | null>(null);
  readonly form = viewChild(GwConnectionForm);
  private readonly pluginReview = viewChild.required(GwPluginReviewDialog);
  /** The catalog plugin being checked before its review opens (Continue shows it busy). */
  protected readonly checkingPlugin = signal<string | null>(null);
  /** The card whose plugin the review installs: Configure opens on it once it is installed. */
  private reviewing: string | null = null;
  private readonly editTabs = viewChild(GwTabs);
  /** The duplicate's settings: picking its type again keeps them, another type starts fresh. */
  private copied: { readonly type: string; readonly env: Env; readonly config: JsonObject } | null = null;
  protected readonly databaseSetup = computed(() => !!this.spec() && !!databaseSelection(this.spec()!.schema));
  private databaseRun = 0;
  private readonly fileConsents = new WeakMap<FileBrowseRequest, string>();

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
  protected readonly googleSignIn = computed(() => usesGoogleAdc(this.spec()?.type, this.draft()?.config ?? null));
  protected readonly googleSignInContext = computed(() => this.step() === 3 ? this.draft() : null);
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
  private toolRun = 0;
  private readonly toolScope = computed(() => {
    const spec = this.spec();
    const value = this.draft() ?? this.form()?.value();
    if (!spec || !value) return null;
    return { type: spec.type, config: value.config, env: value.env,
      key: JSON.stringify([spec.type, value.env, value.config]) };
  });
  private readonly toolResult = signal<{ readonly key: string; readonly state: LoadState; readonly tools: ConnectorTool[]; readonly error: string | null } | null>(null);
  protected readonly toolPreviewState = computed(() => {
    const result = this.toolResult();
    return result && result.key === this.toolScope()?.key ? result.state : 'idle';
  });
  protected readonly toolPreviewError = computed(() => this.toolPreviewState() === 'error' ? this.toolResult()?.error : null);
  private readonly configuredTools = computed(() => this.toolPreviewState() === 'ready' ? this.toolResult()!.tools : []);
  protected readonly choices = computed<EndpointChoice[]>(() =>
    endpointChoices(this.endpoints() ?? [], this.current()),
  );
  protected readonly previews = computed(() => {
    const card = this.card();
    const tools = this.configuredTools();
    const kind = card?.kind ?? this.typeDetail()?.type.kind ?? 'built-in';
    const dynamic = card ? card.tools === null : false;
    const signed = card?.signed ?? this.typeDetail()?.type.signed ?? false;
    const chosen = this.chosen();
    return this.choices()
      .filter((c) => !c.refusal && chosen.includes(c.slug))
      .map((c) => ({ choice: c, preview: toolPreview(tools, kind, dynamic, c, signed) }));
  });
  /** The type's tools that change data; their initial modes are shown before saving. */
  protected readonly mutatingTools = computed(() =>
    this.configuredTools().filter((t) => (t.nature ?? '').toLowerCase() !== 'read').map((t) => t.name),
  );
  /** "The password is encrypted with your master key on save" (what Save does with the secrets typed). */
  protected readonly saveNote = computed(() => {
    const v = this.draft();
    const spec = this.spec();
    if (!v || !spec) return null;
    const entries = Object.entries(v.secrets);
    if (!entries.length) return spec.secretFields.length ? { icon: 'circle-off', text: 'No secret is stored for it' } : null;
    const local = entries.filter(([, s]) => s.source === 'local').map(([p]) => secretWord(spec.secretFields, p));
    const first = this.firstRun();
    if (!local.length) {
      return { icon: 'lock-keyhole', text: first ? 'Nothing is stored here: the secret stays in your secret manager' : 'Only the reference is stored; the secret stays in its store' };
    }
    const words = list(local);
    const verb = local.length === 1 ? 'is' : 'are';
    return { icon: 'lock-keyhole', text: first ? `The ${words} ${verb} encrypted on this computer when you save` : `The ${words} ${verb} encrypted with your master key on save` };
  });

  protected readonly steps = computed(() => {
    if (this.flow() === 'mcp') {
      const configure = this.mcpConfigure();
      const server = this.mcpServer();
      const choice = this.mcpChoice();
      const title = choice?.origin === 'external' ? 'External MCP server' : `${this.card()?.name ?? this.mcpReviewed()?.name ?? 'MCP server'} · MCP server`;
      const done = this.ready();
      return stepperSteps(
        done ? 5 : this.step(),
        {
          typeName: title,
          name: configure?.name().trim() || this.mcpReviewed()?.name || '',
          env: configure?.environment() ?? 'dev',
          test: server ? { passed: true, text: `${server.http ? 'Connected' : 'Started'}${server.tools === null ? '' : ` · ${server.tools} ${server.tools === 1 ? 'tool' : 'tools'}`}` } : null,
          endpoints: this.mcpEndpoints()?.chosen() ?? [],
        },
        this.firstRun(),
        done ? (done.endpointCreated ? null : done.endpoint) : this.reusesFirstEndpoint() ? this.firstEndpointSlug() : null,
        'mcp',
      );
    }
    return stepperSteps(
      this.ready() ? 5 : this.step(),
      {
        typeName: this.card()?.name ?? this.spec()?.name ?? null,
        name: this.current().name,
        env: this.current().env,
        test: this.summary(),
        endpoints: this.chosen().filter((s) => this.choices().some((c) => c.slug === s && !c.refusal)),
      },
      this.firstRun(),
      this.ready() ? (this.ready()!.endpointCreated ? null : this.ready()!.endpoint)
        : this.reusesFirstEndpoint() ? this.firstEndpointSlug() : null,
    );
  });

  // ─── First run (kit connection-new ?state=first-run) ───

  /**
   * No connection exists yet, with no endpoint or only the empty default: the wizard speaks to a beginner and
   * reuses the default when possible. The secret source waits behind its disclosure. Decided
   * from the core when the wizard opens (false until it answers, and for duplicates and the edit view).
   */
  readonly firstRun = signal(false);
  /** The names of the connections there are: a proposed Name never takes one. */
  protected readonly takenNames = signal<readonly string[]>([]);
  /** Which endpoints each existing connection is on: Overview's Add service says how many aren't on its endpoint. */
  private readonly existingUse = signal<readonly (readonly string[])[]>([]);
  /** Connections that already exist and aren't on the endpoint the page was opened for (`?endpoint=`). */
  protected readonly existingNotOn = computed(() => {
    const slug = this.endpoint();
    return slug ? this.existingUse().filter((usedBy) => !usedBy.includes(slug)).length : 0;
  });
  private readonly firstEndpoint = signal<EndpointSummary | null>(null);
  /** The first endpoint is named after this environment: the connection's, or the MCP server's. */
  private readonly firstEnv = computed<Env>(() => this.flow() === 'mcp'
    ? (this.mcpServer()?.env ?? this.mcpConfigure()?.environment() ?? 'dev')
    : this.current().env);
  protected readonly firstEndpointSlug = computed(() => {
    const endpoint = this.firstEndpoint();
    const env = this.firstEnv();
    // A live client or token must not cross the production boundary with an empty endpoint.
    return endpoint && !(env === 'prod' && endpoint.env !== 'prod' && (endpoint.tokens.active > 0 || endpoint.clients.length > 0))
      ? endpoint.slug : env;
  });
  protected readonly reusesFirstEndpoint = computed(() => this.firstEndpoint()?.slug === this.firstEndpointSlug());
  /** The master key has a backup (its date is known): the ready card doesn't ask for one. */
  protected readonly keyBackedUp = signal(false);
  /** After a first-run Save: what exists now, for the "<name> is ready" card that replaces the wizard. */
  protected readonly ready = signal<{
    readonly name: string;
    readonly env: Env;
    readonly endpoint: string;
    readonly url: string;
    readonly createdAt: string;
    readonly endpointCreated: boolean;
    readonly tools: ReturnType<typeof toolsLine>;
    readonly local: boolean;
    readonly tested: boolean;
    readonly health: ReturnType<typeof healthLook> | null;
    /** An MCP server (saved when it connected) rather than a connection saved by the wizard. */
    readonly mcp: { readonly http: boolean } | null;
  } | null>(null);
  /** The first endpoint's tools as Save will create them: reads in Read only, changes in Approval, on prod too. */
  protected readonly firstPreview = computed(() => {
    const card = this.card();
    const kind = card?.kind ?? this.typeDetail()?.type.kind ?? 'built-in';
    const signed = card?.signed ?? this.typeDetail()?.type.signed ?? false;
    return firstRunPreview(this.configuredTools(), kind, card ? card.tools === null : false, this.current().env, this.firstEndpointSlug(), signed);
  });
  /** "sql_execute" in "Anything that changes data, like sql_execute, waits for your approval." */
  protected readonly firstMutating = computed(() => this.mutatingTools()[0] ?? null);
  /** The secret the person picked lives here (encrypted) or in a secret manager: every line about it follows. */
  protected readonly secretLocal = computed(() => {
    const v = this.draft();
    const entries = v ? Object.values(v.secrets) : [];
    return !entries.length || entries.some((s) => s.source === 'local');
  });
  /** "Password": the connector's first secret, the ready card's row and the beginner's lines name it. */
  protected readonly secretTitle = computed(() => this.spec()?.secretFields[0]?.title ?? null);
  /** "Reads run at once. Anything that changes data, like sql_execute, waits for your approval. …" */
  protected readonly firstModes = computed(() => firstRunModes(this.firstPreview()));
  /** The address of the starter endpoint, or of the environment-specific endpoint Save will create. */
  protected readonly firstUrl = computed(() => this.reusesFirstEndpoint()
    ? this.firstEndpoint()!.url : `http://${this.core.address()}/mcp/${this.firstEndpointSlug()}`);
  /** Step 3's description in a first run: what that user may do "in the database", "on the server". */
  protected readonly firstTestText = computed(() => firstRunTestText(signInTarget(this.spec()?.type ?? '')));
  /** The ready card's Tools row, from the endpoint's tools as the core answered them. */
  protected readonly readyTools = computed(() => {
    const r = this.ready();
    return r ? toolsSentence(r.tools) : null;
  });

  // ─── Edit view ───
  protected readonly detail = signal<ConnectionDetail | null>(null);
  protected readonly detailState = signal<LoadState | 'not-found'>('idle');
  protected readonly detailError = signal<ApiError | null>(null);
  protected readonly accessRefreshing = signal(false);
  protected readonly accessSyncError = signal<string | null>(null);
  protected readonly accessBusy = computed(() => this.accessRefreshing() || this.accessChanges.pending().has(`connection:${this.name()}`));
  protected readonly host = signal<string | null>(null);
  protected readonly health = signal<ConnectionHealth | null>(null);
  /** Health events received while a detail request is in flight must survive its older snapshot. */
  private healthRevision = 0;
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
  /** The Tools tab's count: the rows its table lists. */
  protected readonly toolCount = computed(() => toolCount(this.usedBy()));
  /** Saved endpoint membership exists independently of the connection's tool rows. */
  private readonly assignedEndpoints = signal<readonly string[]>([]);
  protected readonly uses = computed(() => endpointUses(this.usedBy(), this.endpoints() ?? [], this.assignedEndpoints()));
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
  private readonly editTestDraft = signal<{ readonly value: ConnectionFormValue; readonly confirm: string | null } | null>(null);
  protected readonly confirmTitle = computed(() => testConfirmTitle(this.editConfirm(), testTarget(this.editTestDraft()?.value.config ?? null).address));
  protected readonly confirmFacts = computed(() => testFacts(this.editConfirm()?.needsConfirmation?.data));
  /** The core asked before sending the stored secret to a new host (409 `send-secret-to-new-host`). */
  protected readonly newHost = signal<{ readonly title: string; readonly detail: string; readonly value: ConnectionFormValue; readonly test?: boolean; readonly discoveryKey?: string; readonly fileRequest?: FileBrowseRequest; readonly projectRequest?: ProjectDiscoveryRequest; readonly reason?: string | null } | null>(null);
  /** A prod save the core stopped for the rights of the connection's user (409 `confirm: danger`). */
  protected readonly dangerDialog = viewChild<GwDialog>('dangerDialog');
  protected readonly danger = signal<{ readonly title: string; readonly detail: string; readonly retry: () => void } | null>(null);
  /** The secret waiting for Rotate's confirmation. */
  protected readonly rotating = signal<{ readonly secretId: string; readonly word: string; readonly version: number | null; readonly key: string } | null>(null);
  /** A prod secret's new value waiting for its comment (the core keeps one with every prod secret change). */
  /**
   * What waits for the comment a prod secret's change keeps: a new value (Replace), or the references a Save changes
   * (Change, another Vault).
   */
  protected readonly replacingProd = signal<
    | { readonly kind: 'value'; readonly secretId: string; readonly word: string; readonly value: string; readonly plural?: false }
    | { readonly kind: 'references'; readonly word: string; readonly plural: boolean; readonly value: ConnectionFormValue; readonly confirm: string | null }
    | null
  >(null);
  protected readonly comment = signal('');
  protected readonly commentError = signal<string | null>(null);

  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => ++this.toolRun);
    effect(() => {
      if (this.mode() === 'edit' || this.step() < 3 || !this.toolScope()) return;
      untracked(() => void this.loadToolPreview());
    });
    // One start per mode and subject: the same component serves ?type=, ?duplicate= and the edit view.
    effect(() => {
      const mode = this.mode();
      this.name();
      this.duplicate();
      this.type();
      this.variant();
      this.show();
      this.profile();
      this.registry();
      this.registryServer();
      this.catalogEntry();
      untracked(() => void this.start(mode));
    });
    // The Official MCP Registry follows the search while MCP servers are shown.
    effect(() => {
      const query = this.typeQuery();
      const asked = this.showKind() !== 'connectors';
      untracked(() => this.finder.find(asked ? query : ''));
    });
    destroy.onDestroy(() => this.finder.stop());
    // What a paste reads is the choice; clearing the paste clears it.
    effect(() => {
      const pasted = this.pasted();
      untracked(() => {
        if (pasted) this.onPick(cardKey(pasted.cards[0]));
        else if (this.selected() === PASTE_KEY || this.selected() === 'postgres:paste') this.selected.set(null);
      });
    });
    const offModule = this.events.on('module.state', (m) => {
      const waiting = this.moduleDownload();
      if (waiting && waiting.id === m.id) this.moduleDownload.set({ ...waiting, step: m.step, message: m.message ?? null });
    });
    destroy.onDestroy(offModule);
    // GateRook's list of connector modules arrived or changed while the page is open: its cards follow.
    const offList = this.events.on('module.list', () => {
      if (this.catalog()) void this.reloadCatalog();
    });
    destroy.onDestroy(offList);
    const offHealth = this.events.on('connection.health', (h) => {
      if (this.mode() !== 'edit' || h.name !== this.name()) return;
      this.healthRevision++;
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

  protected async loadToolPreview(retry = false): Promise<void> {
    const scope = this.toolScope();
    if (!scope || (!retry && this.toolResult()?.key === scope.key)) return;
    const run = ++this.toolRun;
    const gen = this.gen;
    this.toolResult.set({ key: scope.key, state: 'loading', tools: [], error: null });
    try {
      const result = await this.typesApi.previewTools(scope.type, scope.config, scope.env);
      if (run !== this.toolRun || gen !== this.gen || scope.key !== this.toolScope()?.key) return;
      this.toolResult.set({ key: scope.key, state: 'ready', tools: result.tools, error: null });
    } catch (error) {
      if (run !== this.toolRun || gen !== this.gen || scope.key !== this.toolScope()?.key) return;
      this.toolResult.set({ key: scope.key, state: 'error', tools: [],
        error: isApiError(error) ? error.detail : "The core didn't return this connection's tools. Try again." });
    }
  }

  private async start(mode: Mode): Promise<void> {
    const gen = ++this.gen;
    this.mcpChoice.set(null);
    this.mcpReviewed.set(null);
    this.mcpServer.set(null);
    this.resolvingMcp.set(false);
    const show = this.show();
    this.showKind.set(show === 'mcp' || show === 'connectors' ? show : 'all');
    ++this.toolRun;
    this.toolResult.set(null);
    ++this.databaseRun;
    this.step.set(mode === 'duplicate' ? 2 : 1);
    this.outcome.set(null);
    this.testState.set('idle');
    this.editTesting.set(false);
    this.trusting.set(false);
    this.editConfirm.set(null);
    this.editTestDraft.set(null);
    this.testedKey = null;
    this.draft.set(null);
    this.nameProblem.set(null);
    this.formProblem.set(null);
    this.chosenTouched = false;
    this.copied = null;
    this.ready.set(null);
    this.firstRun.set(false);
    this.firstEndpoint.set(null);
    if (mode === 'add') {
      void this.checkFirstRun(gen);
      void this.loadProfiles();
    }
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
    if (await this.startFromLink(gen)) return;
    // Add MCP server opens on the MCP section with nothing chosen yet: External MCP server leads it.
    this.selected.set(this.showKind() === 'mcp' ? null : defaultCardKey(this.groups(), this.type(), this.variant()));
    // Deep link to a step (kit: #step-2…#step-4): the step opens without moving focus.
    const m = /^step-([2-4])$/.exec(this.route.snapshot.fragment ?? '');
    if (m && this.selected() && (await this.openType(this.selected()!, true)) && gen === this.gen) {
      this.go(Number(m[1]) as StepNumber, { initial: true });
    }
  }

  /**
   * A link to an MCP server's setup opens its Configure: `?type=mcp-server` (External MCP server), `?profile=`,
   * `?registry=` (a Popular pick, read from the Official MCP Registry first; a server it can't use stays in step 1 with
   * the reason), `?registryServer=` (a search result, read from the registry the core searches) and
   * `?catalog=<id>@<version>`. True when the link was one of these.
   */
  private async startFromLink(gen: number): Promise<boolean> {
    if (this.type() === MCP_SERVER_TYPE) {
      this.showKind.set('mcp');
      this.selected.set(EXTERNAL_KEY);
      this.startMcp({ origin: 'external' }, true);
      return true;
    }
    const profileId = this.profile();
    if (profileId) {
      const profile = (await this.loadProfiles()).find((p) => p.id === profileId);
      if (gen !== this.gen) return true;
      if (profile) {
        this.showKind.set('mcp');
        this.selected.set(profileKey(profile.id));
        this.startMcp({ origin: 'profile', profile }, true);
        return true;
      }
    }
    const registryName = this.registry();
    if (registryName) {
      this.showKind.set('mcp');
      const featured = FEATURED_MCP_SERVERS.find((f) => f.registryName === registryName);
      this.selected.set(featured ? featuredKey(featured) : null);
      const choice = await this.officialChoice(registryName, featured?.name ?? registryName);
      if (choice && gen === this.gen) this.startMcp(choice, true);
      return true;
    }
    const serverName = this.registryServer();
    if (serverName) {
      this.showKind.set('mcp');
      this.selected.set(null);
      const choice = await this.registryChoice(serverName);
      if (choice && gen === this.gen) this.startMcp(choice, true);
      return true;
    }
    const entry = this.catalogEntry();
    const at = entry ? entry.lastIndexOf('@') : -1;
    if (entry && at > 0) {
      const id = entry.slice(0, at);
      const version = entry.slice(at + 1);
      const name = await this.pluginsApi.catalog().then((c) => c.entries.find((e) => e.id === id)?.name ?? id, () => id);
      if (gen !== this.gen) return true;
      this.showKind.set('mcp');
      this.selected.set(null);
      this.startMcp({ origin: 'catalog', id, version, name }, true);
      return true;
    }
    return false;
  }

  private profilesLoad: Promise<readonly McpProfile[]> | null = null;

  /** GateRook's verified profiles, asked once per page; a core without them has none. */
  private loadProfiles(): Promise<readonly McpProfile[]> {
    this.profilesLoad ??= this.mcpApi.profiles().then(
      (p) => (Array.isArray(p) ? p : []),
      () => [],
    ).then((profiles) => {
      this.mcpProfiles.set(profiles);
      return profiles;
    });
    return this.profilesLoad;
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

  /**
   * Enter in the search never submits step 1 (that would continue with a type the search may hide): when exactly one
   * available type matches, it picks that one.
   */
  protected onTypeSearchEnter(event: Event): void {
    event.preventDefault();
    if (this.registryAsked()) this.finder.find(this.typeQuery(), true);
    const shown = this.pickerGroups().flatMap((g) => g.cards).filter((c) => c.available);
    if (this.typeQuery().trim() && shown.length === 1) this.onPick(cardKey(shown[0]));
  }

  /** The switch beside the search: every kind, the connector types, or the MCP servers. */
  protected setShow(show: PickerShow): void {
    this.showKind.set(show);
    // A choice the switch hides isn't the one Continue should take.
    const key = this.selected();
    if (key && !this.pickerGroups().some((g) => g.cards.some((c) => cardKey(c) === key))) this.selected.set(null);
  }

  /** "Enter it by hand" in the empty search: External MCP server's Configure. */
  protected enterByHand(): void {
    this.typeQuery.set('');
    this.selected.set(EXTERNAL_KEY);
    this.startMcp({ origin: 'external' });
  }

  /** Show more of the registry's results; the keyboard moves to the first new one. */
  protected async moreRegistry(): Promise<void> {
    const first = await this.finder.more();
    if (!first) return;
    afterNextRender(() => {
      const radios = Array.from(this.doc.querySelectorAll<HTMLInputElement>('#type-picker .gw-choice-card__input'));
      radios.find((r) => r.value === registryKey(first.server.name))?.focus();
    }, { injector: this.injector });
  }

  protected retryCatalog(): void {
    void this.start(this.mode());
  }

  /**
   * First run or not: no connections, with no endpoints or only the empty starter endpoint.
   * Without an answer it is the usual wizard; Save works the same, step 4 lists the endpoints once they load.
   */
  private async checkFirstRun(gen: number): Promise<void> {
    try {
      const [rows, endpoints] = await Promise.all([this.connections.list(), this.endpointsApi.list()]);
      if (gen !== this.gen) return;
      this.takenNames.set(rows.items.map((r) => r.name));
      this.existingUse.set(rows.items.map((r) => r.usedBy));
      this.endpoints.set(endpoints);
      this.endpointsState.set('ready');
      if (!this.chosenTouched) this.chosen.set(this.initialChosen());
      const first = isFirstRun(rows.total, endpoints);
      this.firstRun.set(first);
      if (!first) return;
      this.firstEndpoint.set(starterEndpoint(endpoints));
      this.title.setTitle(`Add your first connection · ${PRODUCT}`);
      // The ready card asks for a backup of the key only while there is none.
      this.masterKey.status().then(
        (k) => {
          if (gen === this.gen) this.keyBackedUp.set(!!k.lastBackupAt);
        },
        () => undefined,
      );
    } catch {
      // The usual wizard.
    }
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
        .filter((c) => c.type === draft.type && !c.compatibleWith);
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
    this.accessRefreshing.set(false);
    this.accessSyncError.set(null);
    this.detailState.set('loading');
    this.specState.set('loading');
    const healthRevision = this.healthRevision;
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
      if (healthRevision === this.healthRevision) this.health.set(detail.health);
      this.typeDetail.set(typeDetail);
      this.endpoints.set(endpoints);
      this.assignedEndpoints.set(rows?.items.find((r) => r.name === name)?.usedBy ?? []);
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

  protected toolModesChanged(change: { readonly endpoint: string; readonly tools: readonly ToolRow[] }): void {
    this.detail.update((detail) => detail ? { ...detail, usedBy: detail.usedBy.map((use) => {
      const tool = change.endpoint === use.endpoint ? change.tools.find((row) => row.name === use.tool) : null;
      return tool ? { ...use, mode: tool.mode } : use;
    }) } : detail);
  }

  protected retryEdit(): void {
    void this.startEdit(this.name()!, ++this.gen);
  }

  /** The detail's secrets with what Secrets knows of each: when it was set, whether Rotate can work. */
  private enrich(secrets: readonly StoredSecret[], rows: readonly SecretRow[]): StoredSecret[] {
    return secrets.map((s) => {
      const row = rows.find((r) => r.id === s.secretId);
      return row ? { ...s, store: row.store, ref: row.ref, updatedAt: row.rotatedAt, canRotate: row.canRotate, rotateReason: row.rotateReason } : s;
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
    const card = findCard(this.pickable(), key);
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
    this.formProblem.set(null);
    // A type of a connector module that isn't here yet: the form's request installs it and waits for it.
    const module = card?.module && card.module.state !== 'ready' ? card.module : null;
    this.moduleDownload.set(module
      ? { id: module.id, name: card!.name, sizeBytes: module.sizeBytes, step: module.step ?? 'queued', message: null }
      : null);
    try {
      // The form first: it is what installs a module, and the type's detail exists only once it is here.
      const spec = await this.typesApi.form(type);
      const detail = await this.typesApi.get(type).catch(() => null);
      this.moduleDownload.set(null);
      if (module) void this.reloadCatalog();
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
      if (module) void this.explainModule(module.id, key);
      return false;
    }
  }

  /** The catalog read again, quietly: the card of a module that just arrived no longer says it downloads. */
  private async reloadCatalog(): Promise<void> {
    const gen = this.gen;
    try {
      const catalog = await this.typesApi.catalog();
      if (gen === this.gen) this.catalog.set(catalog);
    } catch {
      // The cards stay as they were.
    }
  }

  /**
   * Why a connector module didn't arrive, in the core's words: the form's own error only says the type isn't there.
   * Still downloading after the form stopped waiting is no failure: Try again joins the same download.
   */
  private async explainModule(id: string, key: string): Promise<void> {
    const waiting = this.moduleDownload();
    try {
      const found = (await this.modulesApi.list()).modules.find((m) => m.id === id);
      if (this.specCard() === key || this.selected() !== key || this.specState() !== 'error') return;
      const message = found?.state === 'installing'
        ? `${waiting?.name ?? 'The connector'} is still downloading. Try again in a moment.`
        : found?.message ?? waiting?.message ?? null;
      this.moduleDownload.set(waiting ? { ...waiting, step: found?.state ?? 'failed', message } : null);
    } catch {
      // The form's error stands.
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
    if (isMcpKey(key)) {
      await this.continueMcp(key!);
      return;
    }
    const picked = findCard(this.pickable(), key);
    if (key && picked && !picked.available && picked.catalogRef) {
      await this.reviewPlugin(key, picked.name, picked.catalogRef);
      return;
    }
    if (!key || !picked?.available) {
      this.pickError.set('Choose a connector type to continue.');
      afterNextRender(() => this.doc.querySelector<HTMLInputElement>('#step-1 .gw-choice-card__input:not([aria-disabled])')?.focus(), {
        injector: this.injector,
      });
      return;
    }
    this.pickError.set(null);
    this.mcpChoice.set(null);
    this.go(2);
    await this.openType(key);
  }

  /**
   * The MCP server chosen in step 1 opens Configure: a Popular card's entry is read from the Official MCP Registry
   * first (Continue shows it busy), the others fill Configure at once.
   */
  private async continueMcp(key: string): Promise<void> {
    if (this.resolvingMcp()) return;
    this.pickError.set(null);
    let choice: McpChoice | null = null;
    if (key === EXTERNAL_KEY) choice = { origin: 'external' };
    else if (key === PASTE_KEY) {
      const read = this.pasteRead();
      if (read?.kind === 'mcp') choice = { origin: 'paste', server: read.server };
    } else {
      const profile = this.mcpProfiles().find((p) => profileKey(p.id) === key);
      const result = this.finder.results().find((r) => registryKey(r.server.name) === key);
      const featured = FEATURED_MCP_SERVERS.find((f) => featuredKey(f) === key);
      if (profile) choice = { origin: 'profile', profile };
      else if (result) choice = result.use.kind === 'none' ? null : { origin: 'registry', result, registry: this.finder.registry() };
      else if (featured) choice = await this.officialChoice(featured.registryName, featured.name);
      if (!choice && !featured) this.pickError.set('Choose what to connect to continue.');
    }
    if (choice) this.startMcp(choice);
  }

  /** A Popular pick: its server of the Official MCP Registry by its exact name. */
  private officialChoice(name: string, title: string): Promise<McpChoice | null> {
    return this.resolveChoice(title, async () =>
      ({ result: await officialEntry((q) => this.mcpData.searchRegistry(q), name), registry: OFFICIAL_REGISTRY }));
  }

  /** A search result Connectors opened: the same entry of the registry the core searches. */
  private registryChoice(name: string): Promise<McpChoice | null> {
    return this.resolveChoice(name, () => registryEntry((q) => this.mcpData.searchRegistry(q), name));
  }

  /** Reads a registry entry while Continue shows it busy; the reason goes under the picker when it can't be used. */
  private async resolveChoice(
    title: string,
    find: () => Promise<{ readonly result: RegistryResult; readonly registry: string | null }>,
  ): Promise<McpChoice | null> {
    const gen = this.gen;
    this.resolvingMcp.set(true);
    try {
      const { result, registry } = await find();
      return gen === this.gen ? { origin: 'registry', result, registry } : null;
    } catch (e) {
      if (gen === this.gen) this.pickError.set(`${title} can’t be set up from the registry: ${failureText(e, 'The registry didn’t answer.')}`);
      return null;
    } finally {
      if (gen === this.gen) this.resolvingMcp.set(false);
    }
  }

  /** Configure on the chosen MCP server; a server saved earlier in this visit is kept, its steps start over. */
  private startMcp(choice: McpChoice, initial = false): void {
    this.mcpChoice.set(choice);
    this.mcpReviewed.set(null);
    this.mcpServer.set(null);
    this.go(2, { initial });
    afterNextRender(() => this.mcpConfigure()?.load(choice, this.current().env), { injector: this.injector });
  }

  /** Configure's Continue: the core reviewed what would run. */
  protected onMcpReviewed(reviewed: McpReviewed): void {
    this.mcpReviewed.set(reviewed);
    this.mcpReview()?.load(reviewed);
    this.go(3);
  }

  /**
   * The server connected (or started): it is saved now, so the wizard can't go back past this point. In first run its
   * last step offers the first endpoint, as a connection's does: the empty default, or one named after its environment.
   */
  protected onMcpConnected(server: McpServerConnected): void {
    this.mcpServer.set(server);
    const first: McpFirstEndpoint | null = this.firstRun()
      ? { slug: this.firstEndpointSlug(), url: this.firstUrl(), reuse: this.reusesFirstEndpoint(), starterEnv: this.firstEndpoint()?.env ?? null, address: this.core.address() }
      : null;
    void this.mcpEndpoints()?.load(server, this.endpoint(), first);
    this.go(4);
  }

  /**
   * Add to endpoint or Not now: the server's own page, as after saving a connection. A first run that put it on its
   * first endpoint ends on the "<name> is ready" card instead.
   */
  protected async onMcpFinished(outcome: EndpointOutcome): Promise<void> {
    const server = this.mcpServer();
    if (!server) return;
    const first = outcome.first;
    if (!first) {
      void this.router.navigate(['/connections', server.name], { replaceUrl: true });
      return;
    }
    const gen = this.gen;
    const groups: ToolGroup[] | null = await this.endpointsApi.tools(first.slug).catch(() => null);
    const detail = await this.connections.get(server.name).catch(() => null);
    if (gen !== this.gen) return;
    const h = detail?.health;
    this.ready.set({
      name: server.name,
      env: server.env,
      endpoint: first.slug,
      url: first.url,
      createdAt: new Date(this.time.now()).toISOString(),
      endpointCreated: first.created,
      tools: toolsLine((groups ?? []).filter((g) => g.connection === server.name).flatMap((g) => g.tools)),
      local: true,
      tested: true,
      health: h && h.state !== 'unknown' ? healthLook(h.state, h.latencyMs) : null,
      mcp: { http: server.http },
    });
    afterNextRender(() => this.doc.getElementById('fr-done-t')?.focus(), { injector: this.injector });
  }

  /**
   * A catalog plugin chosen before it is installed: the core checks the signed package, Connectors' review shows what
   * it is (a danger confirmation for an unsigned one), and once it is installed Configure opens on it.
   */
  private async reviewPlugin(key: string, name: string, catalogRef: string): Promise<void> {
    if (this.checkingPlugin()) return;
    const opener = this.doc.activeElement instanceof HTMLElement ? this.doc.activeElement : null;
    this.checkingPlugin.set(key);
    try {
      const check = await this.pluginsApi.check({ catalog: catalogRef });
      this.reviewing = key;
      this.pluginReview().show(check, null, opener);
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${name} wasn't checked`, body: failureText(e, 'This build of the gateway core can’t install plugins yet.') });
    } finally {
      this.checkingPlugin.set(null);
    }
  }

  /** The review closed: after an install, the catalog again and Configure on the plugin's type. */
  protected async onPluginReviewClosed(result: string): Promise<void> {
    const key = this.reviewing;
    this.reviewing = null;
    if (result !== 'installed' || !key) return;
    const gen = this.gen;
    try {
      const catalog = await this.typesApi.catalog();
      if (gen !== this.gen) return;
      this.catalog.set(catalog);
    } catch {
      return;
    }
    if (!findCard(this.pickable(), key)?.available) return;
    this.selected.set(key);
    this.pickError.set(null);
    this.go(2);
    await this.openType(key);
  }

  protected retrySpec(): void {
    if (this.mode() === 'duplicate' && !this.spec()) void this.startDuplicate(this.duplicate()!, this.gen);
    else if (this.selected()) void this.openType(this.selected()!);
  }

  /** Back / Continue: the panel, the stepper, focus on the step's heading and "Step 2 of 4: Configure". */
  go(n: StepNumber, opts: { readonly focus?: string | false | null; readonly initial?: boolean } = {}): void {
    this.step.set(n);
    const mcp = this.flow() === 'mcp';
    if (n === 3 && !mcp) this.testIfChanged();
    if (n === 4 && !mcp) void this.loadEndpoints();
    if (opts.initial) return;
    if (opts.focus !== false) {
      afterNextRender(
        () => {
          const heading = n > 1 && mcp ? `step-${n}-mcp-title` : `step-${n}-title`;
          const target = (opts.focus && this.doc.getElementById(opts.focus)) || this.doc.getElementById(heading);
          target?.focus();
        },
        { injector: this.injector },
      );
    }
    this.announcer.announce(`Step ${n} of ${STEP_NAMES.length}: ${this.steps()[n - 1].name}`);
  }

  /** Configure's Continue (the form has checked itself) or the edit view's Save changes. */
  protected onFormSubmit(value: ConnectionFormValue): void {
    this.formProblem.set(null);
    if (this.mode() === 'edit') {
      void this.saveEdit(value);
      return;
    }
    this.nameProblem.set(null);
    this.draft.set(value);
    this.go(3);
  }

  /**
   * The setup helpers' lookups. Quiet: a failure (an incomplete draft, a stored secret that would go to a new host)
   * offers nothing new and never asks the person anything; Test connection still does.
   */
  async loadSuggestions(request: SuggestionRequest): Promise<void> {
    const form = this.form();
    const spec = this.spec();
    if (!form || !spec) return;
    const gen = this.gen;
    let result: Suggested | null = null;
    try {
      result = await this.connections.suggest({ type: spec.type, env: request.value.env, config: request.value.config,
        secrets: request.value.secrets, scope: request.scope,
        ...(this.mode() === 'edit' ? { existing: this.name() } : {}) });
    } catch {
      result = null;
    }
    if (gen === this.gen && form === this.form()) form.completeSuggestions(request.key, request.scope, result);
  }

  /** The Configure form owns selection state; requests and stored-secret consent stay with the page. */
  async loadDatabases(request: DatabaseDiscoveryRequest, confirm: string | null = null): Promise<void> {
    const form = this.form();
    const spec = this.spec();
    if (!form || !spec || !form.startDatabaseDiscovery(request.key)) return;
    this.formProblem.set(null);
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
        this.applyFormErrors(error, true, request.value.name);
      }
    }
  }

  async loadProjects(request: ProjectDiscoveryRequest, confirm: string | null = null): Promise<void> {
    const form = this.form();
    const picker = form?.projects();
    const spec = this.spec();
    if (!form || !picker || !spec || !picker.start(request)) return;
    this.formProblem.set(null);
    const gen = this.gen;
    try {
      const result = await this.connections.projects({ type: spec.type, env: request.value.env,
        config: request.value.config, secrets: request.value.secrets,
        ...(this.mode() === 'edit' ? { existing: this.name() } : {}), ...(confirm ? { confirm } : {}) });
      if (gen === this.gen && form === this.form() && picker === form.projects()) picker.complete(request, result);
    } catch (e) {
      if (gen !== this.gen || form !== this.form() || picker !== form.projects()) return;
      const error = isApiError(e) ? e : null;
      if (!picker.complete(request, null, error?.detail ?? "The core didn't answer. Try again.")) return;
      if (error?.confirm === 'send-secret-to-new-host') {
        this.newHost.set({ title: error.title, detail: error.detail, value: request.value, projectRequest: request });
        this.hostDialog()?.openAfterRender();
      } else if (error?.kind === 'unprocessable') this.applyFormErrors(error, true, request.value.name);
    }
  }

  /** Browsing uses the same draft credential and host-consent boundary as testing. */
  async browseFiles(request: FileBrowseRequest, confirm: string | null = null): Promise<void> {
    const form = this.form();
    const picker = form?.files();
    const spec = this.spec();
    if (!form || !picker || !spec || !picker.current(request) || !picker.start(request)) return;
    const gen = this.gen;
    if (confirm) this.fileConsents.set(request, confirm);
    try {
      const result = await this.connections.browseFiles({ type: spec.type, env: request.value.env,
        config: request.value.config, secrets: request.value.secrets, path: request.path,
        ...(this.mode() === 'edit' ? { existing: this.name() } : {}), ...(confirm ? { confirm } : {}) });
      if (gen === this.gen && form === this.form()) picker.complete(request, result);
    } catch (e) {
      if (gen !== this.gen || form !== this.form()) return;
      const error = isApiError(e) ? e : null;
      if (!picker.complete(request, null, error?.detail ?? "The core didn't answer. Try again.")) return;
      if (error?.confirm === 'send-secret-to-new-host') {
        this.newHost.set({ title: error.title, detail: error.detail, value: request.value, fileRequest: request });
        this.hostDialog()?.openAfterRender();
      } else if (error?.kind === 'unprocessable') this.applyFormErrors(error, true, request.value.name);
    }
  }

  async trustFiles(event: FileBrowseTrust): Promise<void> {
    const form = this.form();
    const picker = form?.files();
    if (!form || !picker?.current(event.request)) return;
    const gen = this.gen;
    try {
      const result = await this.connections.confirmDraft(event.confirmationId);
      if (gen !== this.gen || form !== this.form() || !picker.current(event.request)) return;
      form.state().model.update((model) => {
        let patched = merge(model, result.config) as JsonObject;
        // A pending trust response must not restore file permissions removed while it was in flight.
        for (const path of [picker.selection().rootPath, picker.selection().path]) {
          const current = getAt(model, path);
          patched = current === null ? removeConfigPath(patched, path) : setAt(patched, path, current);
        }
        return patched;
      });
      // The trusted key changes the fingerprint. Browse again using the current draft.
      picker.reset();
      const trustedDraft = draftKey(this.spec()?.type ?? null, form.value());
      const consent = this.fileConsents.get(event.request) ?? null;
      afterNextRender(() => {
        if (gen !== this.gen || form !== this.form() || trustedDraft !== draftKey(this.spec()?.type ?? null, form.value())) return;
        picker.load(event.request.path, (request) => void this.browseFiles(request, consent));
      }, { injector: this.injector });
    } catch (e) {
      if (gen === this.gen && form === this.form()) picker.complete(event.request, null,
        isApiError(e) ? e.detail : 'Could not trust the host key. Browse again to retry.');
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
    this.formProblem.set(null);
    let outcome: TestOutcome;
    try {
      outcome = await this.connections.testDraft({ type: spec.type, env: v.env, config: v.config, secrets: v.secrets });
    } catch (e) {
      const err = isApiError(e) ? e : null;
      // A refused draft (422: a value this env forbids) says so here; its fields show it back in Configure.
      if (err?.kind === 'unprocessable' && run === this.testRun) this.applyFormErrors(err, false, v.name);
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

  /**
   * Trust the key or certificate the test stopped for. The core patches the draft; it cannot test an unsaved draft
   * again (it keeps none of its credentials), so without a retest of its own the wizard tests the patched draft.
   */
  async trust(confirmationId: string): Promise<void> {
    if (this.trusting()) return;
    this.trusting.set(true);
    let testAgain = false;
    try {
      const r = await this.connections.confirmDraft(confirmationId);
      const form = this.form();
      if (form && r.config && Object.keys(r.config).length) form.state().model.update((m) => merge(m, r.config) as JsonObject);
      if (r.retest) {
        const v = form?.value() ?? this.draft();
        if (v) {
          this.draft.set(v);
          this.testedKey = draftKey(this.spec()?.type ?? null, v);
        }
        this.outcome.set(r.retest);
        this.testedAt.set(new Date(this.time.now()).toISOString());
      } else {
        testAgain = true;
      }
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "Couldn't trust it", body: isApiError(e) ? e.detail : 'Test again, then trust what it shows.' });
    } finally {
      this.trusting.set(false);
    }
    if (testAgain) await this.runTest();
  }

  /** "Change user" (or, for a database file, "Choose another file"): back to Configure, focus on that field. */
  protected changeUser(): void {
    const s = this.form()?.state();
    const node = s?.compiled.all.find((n) => /^(user|username|login)$/i.test(n.key))
      ?? s?.compiled.all.find((n) => n.key === 'file');
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
    if (!this.chosenTouched) this.chosen.set(this.initialChosen());
  }

  /** The endpoints step 4 starts with: the one the page was opened for (`?endpoint=`) when it may take it, else the usual. */
  private initialChosen(): string[] {
    const choices = this.choices();
    const wanted = this.endpoint();
    if (wanted && choices.some((c) => c.slug === wanted && !c.refusal)) return [wanted];
    return initialEndpoints(choices, this.current().env);
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
    if (this.saving() || this.endpointsState() === 'loading' || this.endpointsState() === 'idle'
      || this.toolPreviewState() !== 'ready' || !form || !spec) return;
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
    if (this.firstRun()) {
      await this.finishFirstRun(v);
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

  /**
   * First run, once the connection exists: reuse the empty default endpoint or create the first endpoint named after
   * its environment, with every tool in the mode the core starts it in: reads run at once, changes wait for approval,
   * on prod too. Then the "<name> is ready" card replaces the wizard. A step that fails says so and leads to the
   * connection's own page.
   */
  private async finishFirstRun(v: ConnectionFormValue): Promise<void> {
    let slug = this.firstEndpointSlug();
    let reuse = this.reusesFirstEndpoint();
    let url = this.firstUrl();
    let endpointCreated = false;
    const stop = (e: unknown) => {
      this.saving.set(false);
      this.toasts.show({
        tone: 'warning',
        title: `Connection ${v.name} saved, endpoint ${slug} not ready`,
        body: `${isApiError(e) ? e.detail : "The core didn't answer."} Add ${v.name} to an endpoint under Endpoints.`,
        actions: [{ label: 'Open Endpoints', onClick: () => void this.router.navigateByUrl('/endpoints') }],
      });
      void this.router.navigate(['/connections', v.name], { replaceUrl: true });
    };
    if (reuse) {
      try {
        const preview = await this.endpointsApi.previewConnection(slug, v.name);
        if (preview.refusal) {
          if (v.env !== 'prod' || this.firstEndpoint()?.env === 'prod') {
            return stop(new ApiError('unprocessable', 422, { detail: preview.refusal }));
          }
          // The core may know about a live grant or environment change since the wizard opened.
          slug = 'prod';
          reuse = false;
          url = `http://${this.core.address()}/mcp/${slug}`;
        }
      } catch (e) {
        return stop(e);
      }
    }
    if (!reuse) {
      try {
        url = (await this.endpointsApi.create({ slug, startFrom: 'empty' })).url;
        endpointCreated = true;
      } catch (e) {
        // An endpoint by that name appeared meanwhile: the core checks attachment again below.
        if (!isApiError(e) || e.kind !== 'conflict') return stop(e);
      }
    }
    try {
      await this.endpointsApi.addConnection(slug, v.name);
    } catch (e) {
      return stop(e);
    }
    const mine = (groups: readonly ToolGroup[]) => groups.filter((g) => g.connection === v.name).flatMap((g) => g.tools);
    const groups: ToolGroup[] | null = await this.endpointsApi.tools(slug).catch(() => null);
    const detail = await this.connections.get(v.name).catch(() => null);
    const outcome = this.outcome();
    const h = detail?.health;
    this.ready.set({
      name: v.name,
      env: v.env,
      endpoint: slug,
      url,
      createdAt: new Date(this.time.now()).toISOString(),
      endpointCreated,
      tools: toolsLine(groups ? mine(groups) : this.firstPreview().rows),
      local: this.secretLocal(),
      tested: this.summary()?.passed === true,
      health: h && h.state !== 'unknown' ? healthLook(h.state, h.latencyMs) : outcome?.ok ? healthLook('healthy', outcome.latencyMs) : null,
      mcp: null,
    });
    this.saving.set(false);
    afterNextRender(() => this.doc.getElementById('fr-done-t')?.focus(), { injector: this.injector });
    this.toasts.show({ tone: 'success', ...firstRunToast(v.name, slug, endpointCreated) });
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
      // Validation owns focus; a second render callback for the heading would override the invalid control.
      this.go(2, { focus: false });
      this.applyFormErrors(err, true, v.name);
      return;
    }
    this.toasts.show({ tone: 'danger', title: `Couldn't save ${v.name}`, body: err?.detail ?? "The core didn't answer. Try again." });
  }

  /** Keep errors without a field beside the form, and focus them when there is no invalid control to visit. */
  private applyFormErrors(error: ApiError, focus = true, submittedName = this.current().name): void {
    if (focus) this.showSettings();
    const others = this.form()?.applyServerError(error) ?? error.errors;
    const name = others.find((item) => item.field === '/name');
    if (name) this.nameProblem.set({ name: submittedName, message: name.message });
    const rest = others.filter((item) => item !== name);
    const problem = rest.length || !error.errors.length
      ? { title: error.title, message: [...new Set(rest.map((item) => item.message))].join(' ') || error.detail }
      : null;
    this.formProblem.set(problem);
    const target = name ? `${this.form()?.idPrefix() ?? 'cf'}-name`
      : problem && others.length === error.errors.length ? 'connection-form-error' : null;
    if (focus && target) {
      afterNextRender(() => this.doc.getElementById(target)?.focus(), { injector: this.injector });
    }
  }

  // ─── Edit: save, test, secrets, delete ───

  /** Form errors must stay visible even if a pending save finishes while Tools is selected. */
  protected showSettings(): void {
    if (this.mode() === 'edit') this.editTabs()?.selectById('connection-settings-tab');
  }

  protected async accessChanged(enabled: boolean): Promise<void> {
    this.detail.update((d) => d ? { ...d, enabled } : d);
    await this.syncAccess();
  }

  /** Refresh the edit version after an access change without replacing unsaved fields. */
  protected async syncAccess(): Promise<void> {
    const name = this.name();
    const gen = this.gen;
    if (!name || !this.detail() || this.accessRefreshing()) return;
    this.accessRefreshing.set(true);
    this.accessSyncError.set(null);
    try {
      const fresh = await this.connections.get(name);
      if (gen !== this.gen) return;
      const baseline = this.detail()!;
      const secretState = (secrets: readonly StoredSecret[]) => secrets.map((s) =>
        [s.field, s.secretId, s.name, s.state, s.version, s.source]).sort((a, b) => String(a[0]).localeCompare(String(b[0])));
      const unchanged = JSON.stringify([baseline.name, baseline.type, this.startEnv(), this.startConfig(), secretState(this.stored())])
        === JSON.stringify([fresh.name, fresh.type, fresh.env, fresh.config, secretState(fresh.secrets)]);
      this.detail.update((d) => d ? { ...d, enabled: fresh.enabled ?? d.enabled, verified: fresh.verified } : d);
      if (!unchanged || !fresh.verified || fresh.version < this.version) {
        this.accessSyncError.set('Saved settings changed elsewhere. Your draft is kept. Discard your edits and reload to review the current settings before saving.');
        return;
      }
      this.version = fresh.version;
      this.detail.update((d) => d ? { ...d, version: fresh.version } : d);
    } catch {
      if (gen === this.gen) this.accessSyncError.set('Access was updated, but the current settings could not be checked. Your draft is kept. Check again before saving.');
    } finally {
      if (gen === this.gen) this.accessRefreshing.set(false);
    }
  }

  /**
   * What Save changes among the stored references, which the settings never carry: a reference typed over a stored one
   * (Change), or the same one in another store picked in its field's menu.
   */
  private referenceChanges(value: ConnectionFormValue): ReferenceChange[] {
    const stored = this.stored().filter((s) => !!s.secretId && storedReference(s));
    const out: ReferenceChange[] = [];
    for (const [field, input] of Object.entries(value.secrets)) {
      const s = input.source === 'local' ? undefined : stored.find((x) => x.field === field);
      if (!s || input.source === 'local') continue;
      const store = input.store ?? s.store ?? null;
      if (input.ref === s.ref && store === (s.store ?? null)) continue;
      out.push({ secretId: s.secretId!, field, uri: input.ref, store });
    }
    return out;
  }

  /** Points each changed reference to where it now reads from; the ones the core refused, with its reason. */
  private async applyReferenceChanges(changes: readonly ReferenceChange[], reason: string | null): Promise<{ readonly field: string; readonly detail: string }[]> {
    const failed: { field: string; detail: string }[] = [];
    for (const c of changes) {
      try {
        await this.secretsApi.setReference(c.secretId, c.uri, c.store, reason);
      } catch (e) {
        failed.push({ field: c.field, detail: isApiError(e) ? e.detail : "The core didn't answer." });
      }
    }
    return failed;
  }

  private async saveEdit(value: ConnectionFormValue, confirm: string | null = null, reason: string | null = null): Promise<void> {
    const name = this.name()!;
    if (this.saving() || this.accessBusy() || this.accessSyncError()) return;
    const changes = this.referenceChanges(value);
    const changed = new Set(changes.map((c) => c.field));
    // A value typed where a reference is stored can't become a stored value: say so rather than drop it.
    const storedReferences = new Set(this.stored().filter((s) => !!s.secretId && storedReference(s)).map((s) => s.field));
    if (Object.entries(value.secrets).some(([field, input]) => input.source === 'local' && storedReferences.has(field))) {
      this.formProblem.set({ title: 'Keep the secret in its store',
        message: 'A secret read from a store can\'t be turned into a value stored here. Keep a reference in its field (vault://…) and change it there, or delete the connection and add it again.' });
      afterNextRender(() => this.doc.getElementById('connection-form-error')?.focus(), { injector: this.injector });
      return;
    }
    if (changes.length && reason === null && value.env === 'prod') {
      // The core keeps a comment with every change of a prod secret, references included: ask for it first.
      const fields = this.spec()?.secretFields ?? [];
      this.replacingProd.set({ kind: 'references', value, confirm, plural: changes.length > 1,
        word: changes.length > 1 ? 'references' : `${secretWord(fields, changes[0].field)} reference` });
      this.comment.set('');
      this.commentError.set(null);
      this.commentDialog()?.openAfterRender();
      return;
    }
    const completingSetup = this.detail()?.setupRequired === true;
    const existingFields = new Set(this.stored().filter(secret => !!secret.secretId).map(secret => secret.field));
    if (completingSetup) {
      if (Object.keys(value.secrets).some(field => existingFields.has(field) && !changed.has(field))) {
        this.formProblem.set({ title: 'Save credential replacements first',
          message: 'Use the Save control beside an existing credential to replace its value. For an existing reference, use Replace reference in Secrets. Your typed values have not been discarded. Missing credentials are saved with the completed settings.' });
        afterNextRender(() => this.doc.getElementById('connection-form-error')?.focus(), { injector: this.injector });
        return;
      }
    }
    this.saving.set(true);
    // A new name is saved with the settings; the rest of the page then follows the connection to its new address.
    const renamed = value.name !== name ? value.name : null;
    try {
      // New secrets and fields read from a store go with the settings; a stored one changes through its own control.
      const secrets = Object.fromEntries(Object.entries(value.secrets).filter(([field]) => !changed.has(field)
        && !existingFields.has(field)));
      await this.connections.update(name, { config: value.config, env: value.env, version: this.version, confirm,
        ...(renamed ? { name: renamed } : {}), ...(Object.keys(secrets).length ? { secrets } : {}) });
      // The settings are saved first: a reference the core refuses then leaves them as saved, and says why.
      const failed = await this.applyReferenceChanges(changes, reason);
      const saved = renamed ?? name;
      if (failed.length) {
        const fields = this.spec()?.secretFields ?? [];
        this.toasts.show({
          tone: 'danger',
          title: `The ${secretWord(fields, failed[0].field)} reference of ${saved} didn't change`,
          body: `${failed[0].detail} The other settings are saved.`,
        });
      } else {
        this.toasts.show({
          tone: 'success',
          title: renamed ? `Connection ${name} renamed to ${renamed}` : `Connection ${name} saved`,
          body: renamed ? 'Agents use the new name from their next tool list. Its endpoints, policies and secret names follow it; past calls keep the old name.'
            : completingSetup ? 'Setup is complete. Access stays off until you enable this connection.'
            : changes.length ? 'The new settings and references apply from the next call. Calls in flight finish with the old ones.'
            : 'The new settings apply from the next call. Calls in flight finish with the old ones.',
        });
      }
      if (renamed) {
        this.announcer.announce(`Renamed to ${renamed}`);
        await this.router.navigate(['/connections', renamed], { replaceUrl: true });
      } else {
        await this.reloadDetail();
      }
    } catch (e) {
      const err = isApiError(e) ? e : null;
      if (err?.kind === 'conflict' && err.confirm === 'danger') {
        this.askDanger(err.title, err.detail, () => void this.saveEdit(value, withConfirm(confirm, 'danger'), reason));
      } else if (err?.kind === 'conflict' && err.confirm === 'send-secret-to-new-host') {
        this.newHost.set({ title: err.title, detail: err.detail, value, reason });
        this.hostDialog()?.openAfterRender();
      } else if (renamed && err?.kind === 'conflict' && err.title === 'Name in use') {
        // Said under the Name until it changes; nothing was saved.
        this.nameProblem.set({ name: renamed, message: err.detail });
        this.showSettings();
        const nameId = `${this.form()?.idPrefix() ?? 'cf'}-name`;
        afterNextRender(() => this.doc.getElementById(nameId)?.focus(), { injector: this.injector });
      } else if (err?.kind === 'conflict') {
        this.toasts.show({ tone: 'warning', title: `${name} changed meanwhile`, body: `${err.detail} Its current settings are shown again.` });
        await this.reloadDetail().catch(() => undefined);
      } else if (err?.kind === 'unprocessable') {
        this.applyFormErrors(err);
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
    if (pending?.fileRequest) void this.browseFiles(pending.fileRequest, 'send-secret-to-new-host');
    else if (pending?.projectRequest) void this.loadProjects(pending.projectRequest, 'send-secret-to-new-host');
    else if (pending?.discoveryKey) void this.loadDatabases({ key: pending.discoveryKey, value: pending.value }, 'send-secret-to-new-host');
    else if (pending?.test) void this.testEditDraft(pending.value, 'send-secret-to-new-host');
    else if (pending) void this.saveEdit(pending.value, 'send-secret-to-new-host', pending.reason ?? null);
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
    this.formProblem.set(null);
    this.announcer.announce('Changes discarded');
  }

  /** The detail after a save: the form opens on the saved settings, the version follows. */
  private async reloadDetail(): Promise<void> {
    const name = this.name()!;
    const healthRevision = this.healthRevision;
    const [detail, secrets] = await Promise.all([this.connections.get(name), this.secretsApi.list().catch(() => [] as SecretRow[])]);
    this.version = detail.version;
    this.startEnv.set(detail.env);
    this.startConfig.set(detail.config);
    this.stored.set(this.enrich(detail.secrets, secrets));
    if (healthRevision === this.healthRevision) this.health.set(detail.health);
    this.detail.set(detail);
  }

  private async refreshHealth(): Promise<void> {
    const healthRevision = this.healthRevision;
    try {
      const detail = await this.connections.get(this.name()!);
      if (healthRevision === this.healthRevision) this.health.set(detail.health);
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

  /** Test the current form without saving it; omitted credentials come from the edited connection. */
  async testEditDraft(value = this.form()?.value(), confirm: string | null = null): Promise<void> {
    const name = this.name();
    const form = this.form();
    const spec = this.spec();
    if (!name || !form || !spec || !value || this.editTesting() || this.trusting() || this.saving() || form.databaseLoading()) return;
    const gen = this.gen;
    const key = draftKey(spec.type, value);
    this.editConfirm.set(null);
    this.editTestDraft.set(null);
    this.formProblem.set(null);
    this.editTesting.set(true);
    this.announcer.announce(`Testing ${name}`);
    try {
      const outcome = await this.connections.testDraft({ type: spec.type, env: value.env, config: value.config,
        secrets: value.secrets, existing: name, ...(confirm ? { confirm } : {}) });
      if (gen !== this.gen || form !== this.form()) return;
      if (key !== draftKey(spec.type, form.value())) {
        this.changedTestDraft();
        return;
      }
      if (outcome.needsConfirmation && outcome.confirmationId) {
        this.editTestDraft.set({ value, confirm });
        this.editConfirm.set(outcome);
        this.trustDialog()?.openAfterRender();
      } else {
        this.toasts.show(testToast(name, outcome, testTarget(value.config).address));
      }
    } catch (e) {
      if (gen !== this.gen || form !== this.form()) return;
      if (key !== draftKey(spec.type, form.value())) {
        this.changedTestDraft();
        return;
      }
      const error = isApiError(e) ? e : null;
      if (error?.confirm === 'send-secret-to-new-host') {
        this.newHost.set({ title: error.title, detail: error.detail, value, test: true });
        this.hostDialog()?.openAfterRender();
      } else if (error?.kind === 'unprocessable') {
        this.applyFormErrors(error, true, value.name);
      } else {
        this.toasts.show({ tone: 'danger', title: `Couldn't test ${name}`, body: error?.detail ?? "The core didn't answer." });
      }
    } finally {
      if (gen === this.gen) this.editTesting.set(false);
    }
  }

  private changedTestDraft(): void {
    this.trustDialog()?.close('changed');
    this.editConfirm.set(null);
    this.editTestDraft.set(null);
    this.toasts.show({ tone: 'info', title: 'Connection settings changed', body: 'Test again to check the current settings.' });
  }

  /** Trust only the tested draft, then test it again with the same credentials and host consent. */
  protected async trustEditDraft(): Promise<void> {
    const id = this.editConfirm()?.confirmationId;
    const pending = this.editTestDraft();
    const form = this.form();
    const spec = this.spec();
    if (!id || !pending || !form || !spec || this.trusting()) return;
    const key = draftKey(spec.type, pending.value);
    if (key !== draftKey(spec.type, form.value())) {
      this.changedTestDraft();
      return;
    }
    const gen = this.gen;
    this.trusting.set(true);
    try {
      const result = await this.connections.confirmDraft(id);
      if (gen !== this.gen || form !== this.form()) return;
      if (key !== draftKey(spec.type, form.value())) {
        this.changedTestDraft();
        return;
      }
      if (result.config) form.state().model.update((model) => merge(model, result.config) as JsonObject);
      this.trustDialog()?.close('trust');
      this.editConfirm.set(null);
      this.editTestDraft.set(null);
    } catch (e) {
      if (gen === this.gen) this.toasts.show({ tone: 'danger', title: "Couldn't trust it", body: isApiError(e) ? e.detail : 'Test again, then trust what it shows.' });
      return;
    } finally {
      if (gen === this.gen) this.trusting.set(false);
    }
    await this.testEditDraft(form.value(), pending.confirm);
  }

  protected onSecretAction(action: SecretAction): void {
    const word = secretWord(this.spec()?.secretFields ?? [], action.node.pointer);
    if (action.kind === 'save') {
      if (!action.secretId) return;
      if (this.current().env === 'prod') {
        // The core keeps a comment with every change of a prod secret (Setup changes): ask for it first.
        this.replacingProd.set({ kind: 'value', secretId: action.secretId, word, value: action.value });
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
      void this.testReference(action.ref, action.store);
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
    if (pending.kind === 'value') void this.replaceSecret(pending.secretId, pending.word, pending.value, text);
    else void this.saveEdit(pending.value, pending.confirm, text);
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
      if (rotationUnconfirmed(e)) {
        // The source may already use the new value, which the core keeps until the next Rotate settles it.
        this.toasts.show({ tone: 'warning', title: `Rotation of ${name}/${r.key} unconfirmed`, body: e.detail });
        await this.refreshSecrets();
        return;
      }
      this.toasts.show({ tone: 'warning', title: `${name}/${r.key} wasn't rotated`, body: isApiError(e) ? e.detail : "The core didn't answer." });
    }
  }

  private async testReference(ref: string, store?: string): Promise<void> {
    const name = this.name() ?? this.current().name;
    if (!ref) {
      this.toasts.show({ tone: 'warning', title: 'Enter the reference first', body: 'Paste the reference from the store, then test it.' });
      return;
    }
    try {
      const r = await this.secretsApi.testReference(ref, store);
      if (r.found) {
        this.toasts.show({ tone: 'success', title: `Reference of ${name} works`, body: `Found${r.resolvedVersion ? ` version ${r.resolvedVersion}` : ''}. The value was neither shown nor kept.` });
      } else {
        const failures: Record<string, { title: string; body: string }> = {
          'not-found': { title: `Reference of ${name} not found`, body: 'Check the secret path, version and key in the selected store.' },
          gone: { title: `Reference of ${name} is no longer available`, body: 'The referenced secret version was deleted, disabled or destroyed. Choose an available version.' },
          denied: { title: `Access to reference of ${name} denied`, body: 'Check the selected store identity and its permission to read this secret.' },
          unavailable: { title: 'Secret store unavailable', body: 'Check the store connection, network access and sign-in, then test again.' },
          invalid: { title: `Reference of ${name} is invalid`, body: 'Check the reference format and the selected store settings.' },
          'not-connected': { title: 'Secret store is not connected', body: 'Connect the selected store in Secrets → External stores, then test again.' },
        };
        const failure = failures[r.reason ?? ''] ?? { title: `Couldn't test the reference of ${name}`, body: 'Check the selected store connection and test again.' };
        // RefTest.error is a safe store diagnostic, never a secret value or an upstream response body.
        this.toasts.show({ tone: 'danger', title: failure.title, body: compactReferenceError(r.error, failure.body) });
      }
    } catch (e) {
      const err = isApiError(e) ? e : null;
      this.toasts.show(
        err?.kind === 'not-available'
          ? { tone: 'neutral', title: 'Testing references is not available yet', body: 'This build of the core has no external secret stores.' }
          : { tone: 'danger', title: "Couldn't test the reference", body: compactReferenceError(err?.detail, "The reference test failed. Check the store connection and try again.") },
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

  /** The tool card template's context, typed again. */
  protected previewOf(preview: ToolPreview): ToolPreview {
    return preview;
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
