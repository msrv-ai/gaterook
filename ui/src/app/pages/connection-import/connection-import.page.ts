import { ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, Injector, afterNextRender, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ConnectionImportsApi, ConnectionImportResult, ConnectionImportSession, ImportCandidate, ImportSelection } from '../../core/api/connection-imports.api';
import { ClientId } from '../../core/api/clients.api';
import { ConnectionsApi, SecretInputs, StoredSecret, TestOutcome } from '../../core/api/connections.api';
import { ConnectionFormSpec, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointsApi, EndpointSummary } from '../../core/api/endpoints.api';
import { SecretsApi } from '../../core/api/secrets.api';
import { JsonObject, JsonValue } from '../../core/api/types';
import { DesktopBridge, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { TimeService } from '../../core/time/time.service';
import { ClipboardWriter } from '../../kit/clipboard';
import { GwCodeBlock } from '../../code/code-block';
import { ConnectionFormValue, DatabaseDiscoveryRequest, GwConnectionForm } from '../../kit/elements/connection-form/connection-form';
import { GwConnectionTest, TestState } from '../../kit/elements/connection-form/connection-test';
import { FileBrowseRequest, FileBrowseTrust } from '../../kit/elements/connection-form/remote-files';
import { ProjectDiscoveryRequest } from '../../kit/elements/connection-form/cloud-projects';
import { removeConfigPath } from '../../kit/elements/connection-form/database-selection';
import { getAt, setAt } from '../../schema-form/compile';
import { SecretAction } from '../../schema-form/schema-form';
import { GwIcon } from '../../kit/icons';
import { GwToggle } from '../../kit/disclosure';
import { connectionImportPrompt } from './connection-import-prompt';
import { ClientsData } from '../clients/clients.data';
import { ImportAppConsent, ImportAppPicker, ImportAppSelection } from './import-app-picker';

function mergeConfig(base: JsonValue, patch: JsonValue): JsonValue {
  if (!base || !patch || typeof base !== 'object' || typeof patch !== 'object' || Array.isArray(base) || Array.isArray(patch)) return patch;
  const result: JsonObject = { ...base };
  for (const [key, value] of Object.entries(patch)) result[key] = mergeConfig(result[key] ?? null, value);
  return result;
}

@Component({
  selector: 'gw-connection-import-page',
  imports: [RouterLink, GwIcon, GwToggle, GwCodeBlock, GwConnectionForm, GwConnectionTest, ImportAppPicker],
  providers: [ClientsData],
  templateUrl: './connection-import.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--narrow' },
})
export class ConnectionImportPage {
  private readonly api = inject(ConnectionImportsApi);
  private readonly types = inject(ConnectorTypesApi);
  private readonly connections = inject(ConnectionsApi);
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly secretsApi = inject(SecretsApi);
  protected readonly desktop = inject(DesktopBridge);
  protected readonly time = inject(TimeService);
  private readonly clipboard = inject(ClipboardWriter);
  private readonly router = inject(Router);
  private readonly doc = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly destroy = inject(DestroyRef);
  private readonly clients = inject(ClientsData);
  readonly id = input<string | null>(null);
  readonly endpoint = input<string | null>(null);
  readonly app = input<ClientId | null>(null);
  readonly selectedClient = signal<ClientId | null>(null);
  readonly appConsent = signal<ImportAppConsent | null>(null);
  readonly appSelection = signal<ImportAppSelection | null>(null);
  readonly appConnecting = signal(false);
  readonly form = viewChild(GwConnectionForm);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly unavailable = signal(false);
  readonly repository = signal('');
  readonly sourceEndpoint = signal('');
  readonly targetEndpoint = signal('');
  readonly endpoints = signal<readonly EndpointSummary[]>([]);
  readonly session = signal<ConnectionImportSession | null>(null);
  readonly selected = signal<ReadonlySet<string>>(new Set());
  readonly drafts = signal<ReadonlyMap<string, ImportSelection>>(new Map());
  readonly active = signal<ImportCandidate | null>(null);
  readonly spec = signal<ConnectionFormSpec | null>(null);
  readonly editorValue = signal<ConnectionFormValue | null>(null);
  private readonly editorSecrets = signal<SecretInputs>({});
  private readonly editorSecretVersions = signal<Readonly<Record<string, number>>>({});
  readonly existingNames = signal<ReadonlySet<string>>(new Set());
  readonly testState = signal<TestState>('idle');
  readonly testResult = signal<TestOutcome | null>(null);
  readonly result = signal<ConnectionImportResult | null>(null);
  readonly reason = signal('');
  readonly confirmation = signal<{ candidateId: string; name: string; text: string; confirm: string } | null>(null);
  readonly candidates = computed(() => this.session()?.manifest?.connections ?? []);
  readonly prompt = computed(() => this.session()?.status === 'awaiting' ? connectionImportPrompt(this.session()!) : '');
  readonly expired = computed(() => !!this.session() && this.session()!.status !== 'imported' && this.time.secondsUntil(this.session()!.expiresAt) <= 0);
  readonly eligibleEndpoints = computed(() => this.endpoints().filter(e => !e.blockedReason && e.status === 'running'));
  readonly appEndpoint = computed(() => {
    const session = this.session();
    const endpoint = this.eligibleEndpoints().find(e => e.slug === (session?.endpoint ?? this.sourceEndpoint()));
    const manual = this.clients.manual().value;
    return endpoint ? { ...endpoint, url: session?.endpointUrl ?? (manual?.endpoint === endpoint.slug ? manual.url : '') } : null;
  });
  readonly selectedDrafts = computed(() => [...this.selected()].map(id => this.drafts().get(id)).filter((d): d is ImportSelection => !!d));
  readonly incompleteAllowed = computed(() => this.selectedDrafts().filter(d => d.allowIncomplete).length);
  readonly ready = computed(() => this.selected().size > 0 && this.selectedDrafts().length === this.selected().size && !!this.targetEndpoint() && !this.active() && !this.expired());
  readonly retainedSecrets = computed<readonly StoredSecret[]>(() => {
    const sources = new Map<string, StoredSecret>((this.active()?.secretSources ?? []).map(source => [source.field, {
      field: source.field, secretId: null, staged: true,
      name: source.reference ?? 'Credential prepared for import',
      state: source.source === 'vault-dynamic' ? 'short-lived' : source.source === 'reference' ? 'reference' : 'stored', version: null,
      source: source.reference?.split('://')[0] ?? 'encrypted', store: source.store,
      canRotate: false, rotateReason: 'This credential is staged for import. It has not been saved yet.',
    }]));
    for (const [field, secret] of Object.entries(this.editorSecrets())) sources.set(field, {
      field, secretId: null, staged: true, name: secret.source === 'local' ? 'Replacement prepared for import' : secret.ref,
      state: secret.source === 'local' ? 'stored' : secret.source === 'vault-dynamic' ? 'short-lived' : 'reference',
      version: this.editorSecretVersions()[field] ?? 0,
      source: secret.source === 'local' ? 'encrypted' : secret.ref.split('://')[0],
      store: secret.source === 'local' ? undefined : secret.store,
      canRotate: false, rotateReason: 'This credential will be saved only when you import.',
    });
    return [...sources.values()];
  });
  private generation = 0;
  private editing = 0;
  private refreshing = false;
  private preparedReview: string | null = null;

  constructor() {
    const choice = this.router.currentNavigation()?.extras.state?.['importAppChoice'] as { client?: ClientId; consent?: ImportAppConsent } | undefined;
    if (choice?.client && ['claude-desktop', 'codex', 'cursor', 'antigravity'].includes(choice.client)) this.selectedClient.set(choice.client);
    if (choice?.consent && ['manual', 'restart'].includes(choice.consent.kind) && typeof choice.consent.key === 'string') this.appConsent.set(choice.consent);
    void this.clients.start();
    effect(() => {
      const endpoint = this.session()?.endpoint ?? this.sourceEndpoint();
      untracked(() => this.clients.selectManualEndpoint(endpoint || null));
    });
    effect(() => {
      const app = this.app();
      if (app && ['claude-desktop', 'codex', 'cursor', 'antigravity'].includes(app)) this.selectedClient.set(app);
    });
    effect(() => { void this.load(this.id(), this.endpoint()); });
    const timer = setInterval(() => {
      if (this.session()?.status === 'awaiting' && !this.expired() && !this.unavailable() && !this.loading() && !this.busy()) void this.refresh(false);
    }, 5000);
    this.destroy.onDestroy(() => {
      clearInterval(timer); this.generation++; this.editing++;
      this.drafts.set(new Map()); this.editorSecrets.set({}); this.editorValue.set(null);
    });
  }

  async load(id: string | null = this.id(), endpoint: string | null = this.endpoint()): Promise<void> {
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set('');
    this.unavailable.set(false);
    this.active.set(null);
    this.spec.set(null);
    this.drafts.set(new Map());
    this.editorSecrets.set({});
    this.selected.set(new Set());
    this.preparedReview = null;
    this.result.set(null);
    this.session.set(null);
    try {
      const [endpoints, connections, session] = await Promise.all([
        this.endpointsApi.list(), this.connections.list(), id ? this.api.get(id) : Promise.resolve(null),
      ]);
      if (generation !== this.generation) return;
      this.endpoints.set(endpoints);
      this.existingNames.set(new Set(connections.items.map(c => c.name)));
      this.session.set(session);
      if (session) this.prepareReview(session);
      this.sourceEndpoint.set(session?.endpoint ?? endpoint ?? endpoints.find(e => e.slug === 'default')?.slug ?? endpoints[0]?.slug ?? '');
      this.targetEndpoint.set(session?.endpoint ?? endpoint ?? '');
      if (session) this.repository.set(session.repository);
    } catch (e) {
      if (generation !== this.generation) return;
      this.unavailable.set(!!id && isApiError(e) && ['not-found', 'bad-request'].includes(e.kind));
      this.fail(e);
    } finally { if (generation === this.generation) this.loading.set(false); }
  }

  value(event: Event): string { return (event.target as HTMLInputElement).value; }

  sourceChanged(endpoint: string): void {
    if (this.busy() || this.appConnecting()) return;
    this.sourceEndpoint.set(endpoint);
    this.selectedClient.set(null); this.appConsent.set(null); this.appSelection.set(null);
  }

  async chooseRepository(): Promise<void> {
    if (this.busy()) return;
    try { const path = await this.desktop.chooseRepository(); if (path) this.repository.set(path); }
    catch (e) { this.fail(e); }
  }

  async start(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.busy() || this.appConnecting()) return;
    this.error.set('');
    if (!this.repository().trim() || !this.sourceEndpoint()) { this.fail(new Error('Choose a repository and the endpoint your AI app is connected to.')); return; }
    if (!this.appSelection()?.ready || !this.appEndpoint()) { this.fail(new Error('Choose an AI app and connect it to this endpoint before creating the prompt.')); return; }
    this.busy.set(true);
    try {
      const session = await this.api.start(this.repository().trim(), this.sourceEndpoint());
      if (this.destroy.destroyed) return;
      this.session.set(session);
      this.targetEndpoint.set(session.endpoint);
      await this.router.navigate(['/connection-import', session.id], { queryParams: { app: this.selectedClient() }, state: { importAppChoice: { client: this.selectedClient(), consent: this.appConsent() } } });
    } catch (e) { this.fail(e); }
    finally { this.busy.set(false); }
  }

  async handoff(client: 'claude' | 'codex' | 'cursor' | 'copy' | 'antigravity'): Promise<void> {
    if (!this.prompt() || this.expired()) return;
    const selected = this.appSelection();
    const launch = selected?.client === 'claude-desktop' ? 'claude' : selected?.client;
    if (!selected?.ready || (client !== 'copy' && client !== launch)) { this.fail(new Error('Choose a connected AI app for this endpoint before sending the prompt.')); return; }
    this.error.set('');
    try {
      if (client === 'copy' || client === 'antigravity') {
        this.notice.set(await this.clipboard.write(this.prompt())
          ? `Prompt copied. Paste it into ${selected.name} with repository access and GateRook connected.`
          : 'Could not copy. Select the prompt below and copy it.');
      } else {
        await this.desktop.openAiPrompt(client, this.prompt());
        this.notice.set('Review and send the prompt in your AI app. GateRook will open the results when they arrive.');
      }
    } catch (e) { this.fail(e); this.notice.set('Use Copy prompt and paste it into your AI app.'); }
  }

  async handoffSelected(): Promise<void> {
    const app = this.appSelection();
    if (!app?.ready) return;
    await this.handoff(!this.desktop.aiPromptAvailable ? 'copy' : app.client === 'claude-desktop' ? 'claude' : app.client);
  }

  async discard(): Promise<void> {
    const session = this.session();
    if (!session || session.status === 'imported' || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.api.cancel(session.id);
      if (this.destroy.destroyed) return;
      this.session.set(null);
      this.preparedReview = null;
      this.drafts.set(new Map()); this.editorSecrets.set({}); this.editorValue.set(null);
      this.selected.set(new Set()); this.active.set(null); this.spec.set(null);
      this.confirmation.set(null); this.notice.set('Import discarded. No saved connections were changed.');
      await this.router.navigate(['/connection-import']);
    } catch (e) { this.fail(e); }
    finally { this.busy.set(false); }
  }

  async refresh(showError = true): Promise<void> {
    const current = this.session();
    if (!current || this.refreshing || this.busy()) return;
    this.refreshing = true;
    try {
      const session = await this.api.get(current.id);
      if (this.destroy.destroyed || this.session()?.id !== current.id) return;
      if (session.status === 'review' && this.preparedReview !== session.id) {
        const connections = await this.connections.list();
        if (this.destroy.destroyed || this.session()?.id !== current.id) return;
        this.existingNames.set(new Set(connections.items.map(c => c.name)));
      }
      this.session.set(session);
      this.prepareReview(session);
      if (current.status === 'awaiting' && session.status === 'review') {
        this.notice.set('The AI analysis is ready. Available settings are prepared for import. Review the selection and use Settings to make changes.');
        this.focus('#import-review-title');
      }
    } catch (e) {
      if (isApiError(e) && ['not-found', 'bad-request'].includes(e.kind)) { this.unavailable.set(true); this.fail(e); }
      else if (showError) this.fail(e);
    } finally { this.refreshing = false; }
  }

  /** Prepare defaults once; subsequent polls must retain the person's choices and edited credentials. */
  private prepareReview(session: ConnectionImportSession): void {
    if (session.status !== 'review' || !session.manifest || this.preparedReview === session.id) return;
    this.preparedReview = session.id;
    const names = new Map<string, number>();
    for (const candidate of session.manifest.connections) names.set(candidate.name, (names.get(candidate.name) ?? 0) + 1);
    const drafts = new Map<string, ImportSelection>();
    for (const candidate of session.manifest.connections) {
      if (!candidate.supported || this.existingNames().has(candidate.name) || names.get(candidate.name) !== 1
        || !/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(candidate.name) || candidate.name.length > 64) continue;
      drafts.set(candidate.id, { candidateId: candidate.id, name: candidate.name, env: candidate.env,
        config: structuredClone(candidate.config), secrets: {}, allowIncomplete: true });
    }
    this.drafts.set(drafts);
    this.selected.set(new Set(drafts.keys()));
  }

  toggle(candidate: ImportCandidate, checked: boolean): void {
    if (this.busy() || !candidate.supported) return;
    this.confirmation.set(null);
    const selected = new Set(this.selected());
    if (checked) selected.add(candidate.id); else selected.delete(candidate.id);
    this.selected.set(selected);
  }

  async configure(candidate: ImportCandidate): Promise<void> {
    if (this.busy() || !candidate.supported || this.expired()) return;
    const editing = ++this.editing;
    this.busy.set(true);
    this.error.set('');
    this.active.set(null);
    this.spec.set(null);
    this.confirmation.set(null);
    this.testState.set('idle');
    this.editorSecrets.set({});
    this.editorSecretVersions.set({});
    this.testResult.set(null);
    const previous = this.drafts().get(candidate.id);
    try {
      const spec = await this.types.form(candidate.type, previous?.env ?? candidate.env);
      if (editing !== this.editing || this.destroy.destroyed) return;
      this.editorValue.set({ name: previous?.name ?? candidate.name, env: previous?.env ?? candidate.env, config: previous?.config ?? candidate.config, secrets: {} });
      this.editorSecrets.set(previous?.secrets ?? {});
      // Reopening keeps reviewed overrides in memory, but permission consent must be obtained for the new review.
      if (previous) {
        const drafts = new Map(this.drafts());
        const { confirm: _confirmation, ...reviewed } = previous;
        drafts.set(candidate.id, reviewed);
        this.drafts.set(drafts);
      }
      this.active.set(candidate);
      this.spec.set(spec);
      this.focus('#import-configure-title');
    } catch (e) { this.fail(e); }
    finally { if (editing === this.editing) this.busy.set(false); }
  }

  keep(value: ConnectionFormValue): void {
    const candidate = this.active();
    if (!candidate || this.busy()) return;
    if (this.existingNames().has(value.name)) { this.fail(new Error(`A connection named ${value.name} already exists. Choose a different name; imports never overwrite connections.`)); return; }
    if ([...this.drafts().values()].some(d => d.candidateId !== candidate.id && d.name === value.name)) { this.fail(new Error('Two selected drafts have the same name. Choose a unique name for each connection.')); return; }
    if (candidate.env === 'prod' && value.env !== 'prod') { this.fail(new Error('This candidate was discovered as production. Keep its production environment.')); return; }
    const drafts = new Map(this.drafts());
    drafts.set(candidate.id, { candidateId: candidate.id, ...value, secrets: { ...this.editorSecrets(), ...value.secrets } });
    this.drafts.set(drafts);
    this.closeEditor();
  }

  keepAvailable(candidate: ImportCandidate, value?: ConnectionFormValue): void {
    if (this.busy() || !candidate.supported || this.expired()) return;
    const previous = this.drafts().get(candidate.id);
    const current = value ?? (this.active()?.id === candidate.id ? this.form()?.value() : null);
    const name = current?.name ?? previous?.name ?? candidate.name;
    const env = current?.env ?? previous?.env ?? candidate.env;
    if (!/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(name) || name.length > 64) { this.fail(new Error('Choose a connection name using lowercase letters, numbers and hyphens.')); return; }
    if (this.existingNames().has(name)) { this.fail(new Error(`A connection named ${name} already exists. Open Settings and choose a new name.`)); return; }
    if ([...this.drafts().values()].some(d => d.candidateId !== candidate.id && d.name === name)) { this.fail(new Error('Choose a unique name for each connection.')); return; }
    if (candidate.env === 'prod' && env !== 'prod') { this.fail(new Error('This candidate was discovered as production. Keep its production environment.')); return; }
    const secrets = current ? { ...this.editorSecrets(), ...current.secrets } : previous?.secrets ?? {};
    const drafts = new Map(this.drafts());
    drafts.set(candidate.id, { candidateId: candidate.id, name, env,
      config: current?.config ?? previous?.config ?? candidate.config, secrets, allowIncomplete: true });
    this.drafts.set(drafts);
    this.confirmation.set(null);
    if (this.active()?.id === candidate.id) this.closeEditor();
    this.notice.set('Available settings kept. Review the import selection; if required setup is missing, it will be saved disabled for you to finish later.');
  }

  closeEditor(): void {
    if (this.busy()) return;
    this.editing++;
    this.active.set(null);
    this.spec.set(null);
    this.editorValue.set(null);
    this.editorSecrets.set({});
    this.testResult.set(null);
    this.error.set('');
    this.focus('#import-review-title');
  }

  async test(value?: ConnectionFormValue): Promise<void> {
    const form = this.form();
    const candidate = this.active();
    const current = value ?? form?.value();
    if (!candidate || !current || this.busy()) return;
    const draft = { ...current, secrets: { ...this.editorSecrets(), ...current.secrets } };
    if (this.needsRetainedSecrets(draft)) { this.fail(new Error('This draft uses staged credentials. Import it first, then test the saved connection.')); return; }
    this.busy.set(true);
    this.testState.set('testing');
    this.error.set('');
    try { this.testResult.set(await this.connections.testDraft({ type: candidate.type, ...draft })); }
    catch (e) { this.fail(e); if (isApiError(e)) form?.applyServerError(e); }
    finally { this.testState.set('done'); this.busy.set(false); }
  }

  async trust(confirmationId: string): Promise<void> {
    const form = this.form();
    if (!form || this.busy()) return;
    this.busy.set(true);
    try {
      const result = await this.connections.confirmDraft(confirmationId);
      if (form !== this.form()) return;
      form.state().model.update(m => mergeConfig(m, result.config) as JsonObject);
      this.testResult.set(result.retest);
    } catch (e) { this.fail(e); }
    finally { this.busy.set(false); }
  }

  async discoverDatabases(request: DatabaseDiscoveryRequest): Promise<void> {
    const form = this.form();
    const candidate = this.active();
    if (!form || !candidate || !form.startDatabaseDiscovery(request.key)) return;
    const value = this.withEditorSecrets(request.value);
    if (this.needsRetainedSecrets(value)) { form.completeDatabaseDiscovery(request.key, null, 'Enter database names from the analysis. Discover databases after importing the staged credentials.'); return; }
    try {
      const result = await this.connections.databases({ type: candidate.type, ...value });
      if (form === this.form()) form.completeDatabaseDiscovery(request.key, result);
    } catch (e) { if (form === this.form()) form.completeDatabaseDiscovery(request.key, null, this.message(e)); }
  }

  async discoverProjects(request: ProjectDiscoveryRequest): Promise<void> {
    const form = this.form();
    const picker = form?.projects();
    const candidate = this.active();
    if (!picker || !candidate || !picker.start(request)) return;
    const value = this.withEditorSecrets(request.value);
    if (this.needsRetainedSecrets(value)) { picker.complete(request, null, 'Enter project IDs from the analysis. Discover projects after importing the staged credentials.'); return; }
    try {
      const result = await this.connections.projects({ type: candidate.type, ...value });
      if (form === this.form()) picker.complete(request, result);
    } catch (e) { if (form === this.form()) picker.complete(request, null, this.message(e)); }
  }

  async browseFiles(request: FileBrowseRequest): Promise<void> {
    const form = this.form();
    const picker = form?.files();
    const candidate = this.active();
    if (!picker || !candidate || !picker.current(request) || !picker.start(request)) return;
    const value = this.withEditorSecrets(request.value);
    if (this.needsRetainedSecrets(value)) { picker.complete(request, null, 'Enter the exact log file paths from the analysis. Browse files after importing the staged credentials.'); return; }
    try {
      const result = await this.connections.browseFiles({ type: candidate.type, ...value, path: request.path });
      if (form === this.form()) picker.complete(request, result);
    } catch (e) { if (form === this.form()) picker.complete(request, null, this.message(e)); }
  }

  async trustFiles(event: FileBrowseTrust): Promise<void> {
    const form = this.form();
    const picker = form?.files();
    if (!form || !picker?.current(event.request)) return;
    try {
      const result = await this.connections.confirmDraft(event.confirmationId);
      if (form !== this.form() || !picker.current(event.request)) return;
      form.state().model.update(model => {
        let patched = mergeConfig(model, result.config) as JsonObject;
        for (const path of [picker.selection().rootPath, picker.selection().path]) {
          const current = getAt(model, path);
          patched = current === null ? removeConfigPath(patched, path) : setAt(patched, path, current);
        }
        return patched;
      });
      picker.reset();
      const trustedDraft = this.fileDraftKey(form);
      afterNextRender(() => {
        if (this.destroy.destroyed || form !== this.form() || trustedDraft !== this.fileDraftKey(form)) return;
        picker.load(event.request.path, request => void this.browseFiles(request));
      }, { injector: this.injector });
    } catch (e) { if (form === this.form()) picker.complete(event.request, null, this.message(e)); }
  }

  targetChanged(endpoint: string): void { this.targetEndpoint.set(endpoint); this.confirmation.set(null); }

  async secretAction(action: SecretAction): Promise<void> {
    const form = this.form();
    if (!form || this.busy()) return;
    if (action.kind === 'save') {
      const secret = form.value().secrets[action.node.pointer];
      if (!secret) { this.fail(new Error('Enter the replacement credential before keeping it.')); return; }
      // Existing discovery fingerprints include secret versions; a prepared replacement also invalidates them.
      this.editorSecretVersions.update(versions => ({ ...versions, [action.node.pointer]: (versions[action.node.pointer] ?? 0) + 1 }));
      this.editorSecrets.update(values => ({ ...values, [action.node.pointer]: secret }));
      this.notice.set('Replacement kept in this draft. It will be saved only when you import the connection.');
    } else if (action.kind === 'test') {
      this.busy.set(true);
      try {
        const result = await this.secretsApi.testReference(action.ref, action.store);
        this.notice.set(result.found ? 'The secret reference is available. Its value stays in GateRook.' : result.error ?? 'This secret reference is not available. Check its store and path.');
      } catch (e) { this.fail(e); }
      finally { this.busy.set(false); }
    }
  }

  async commit(): Promise<void> {
    if (this.busy() || !this.ready()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await this.api.commit(this.session()!.id, this.targetEndpoint(), this.selectedDrafts(), this.reason().trim() || undefined);
      if (this.destroy.destroyed) return;
      this.result.set(result);
      this.notice.set('');
      this.session.update(s => s ? { ...s, status: 'imported', submissionToken: '' } : s);
      this.drafts.set(new Map());
      this.selected.set(new Set());
      this.confirmation.set(null);
      this.focus('#import-complete-title');
    } catch (e) {
      this.fail(e);
      if (isApiError(e) && e.confirm === 'danger') {
        const index = /^\/selections\/(\d+)(?:\/|$)/.exec(e.field ?? '');
        const draft = index ? this.selectedDrafts()[Number(index[1])] : null;
        if (draft) this.confirmation.set({ candidateId: draft.candidateId, name: draft.name, text: e.detail, confirm: e.confirm });
      }
    } finally { this.busy.set(false); }
  }

  confirmImport(): void {
    const confirmation = this.confirmation();
    if (!confirmation || this.busy()) return;
    const draft = this.drafts().get(confirmation.candidateId);
    if (!draft || draft.name !== confirmation.name) return;
    const drafts = new Map(this.drafts());
    drafts.set(draft.candidateId, { ...draft, confirm: confirmation.confirm });
    this.drafts.set(drafts);
    this.confirmation.set(null);
    void this.commit();
  }

  private needsRetainedSecrets(draft: ConnectionFormValue): boolean { return (this.active()?.secretSources ?? []).some(source => !draft.secrets[source.field]); }
  private withEditorSecrets(draft: ConnectionFormValue): ConnectionFormValue { return { ...draft, secrets: { ...this.editorSecrets(), ...draft.secrets } }; }
  /** In-memory comparison only; the effective draft includes credentials and must never be logged. */
  private fileDraftKey(form: GwConnectionForm): string {
    const value = this.withEditorSecrets(form.value());
    return JSON.stringify([this.active()?.id, this.active()?.type, value.env, value.config, value.secrets]);
  }
  private message(error: unknown): string { return isApiError(error) ? `${error.title}. ${error.detail}` : desktopErrorText(error, 'GateRook could not complete this action. Try again.'); }
  private fail(error: unknown): void { if (this.destroy.destroyed) return; this.error.set(this.message(error)); this.focus('#import-error'); }
  private focus(selector: string): void { afterNextRender(() => this.doc.querySelector<HTMLElement>(selector)?.focus(), { injector: this.injector }); }
}
