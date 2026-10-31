import { ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, Injector, afterNextRender, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ConnectionDetail, ConnectionRow, ConnectionsApi, TestOutcome } from '../../core/api/connections.api';
import { ConnectionFormSpec, ConnectorTypesApi } from '../../core/api/connector-types.api';
import { OpenApiInspection, OpenApiOperation, OpenApiParsed, OpenApiParseRequest, Template, TemplatesApi } from '../../core/api/templates.api';
import { JsonObject } from '../../core/api/types';
import { DesktopBridge, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { ClipboardWriter } from '../../kit/clipboard';
import { GwCodeBlock } from '../../code/code-block';
import { GwConnectionForm, ConnectionFormValue } from '../../kit/elements/connection-form/connection-form';
import { GwFileDrop } from '../../kit/file-drop';
import { GwIcon } from '../../kit/icons';
import { apiImportPrompt } from './http-import-prompt';

@Component({
  selector: 'gw-http-import-page',
  imports: [RouterLink, GwIcon, GwCodeBlock, GwConnectionForm, GwFileDrop],
  templateUrl: './http-import.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--narrow' },
})
export class HttpImportPage {
  private readonly api = inject(TemplatesApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly types = inject(ConnectorTypesApi);
  protected readonly desktop = inject(DesktopBridge);
  private readonly clipboard = inject(ClipboardWriter);
  private readonly doc = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly destroy = inject(DestroyRef);
  readonly connection = input<string | null>(null);
  readonly form = viewChild(GwConnectionForm);
  readonly step = signal(1);
  readonly busy = signal(false);
  readonly errors = signal<readonly string[]>([]);
  readonly notice = signal('');
  readonly spec = signal<ConnectionFormSpec | null>(null);
  readonly connections = signal<readonly ConnectionRow[]>([]);
  readonly existing = signal('');
  readonly detail = signal<ConnectionDetail | null>(null);
  readonly source = signal('file');
  readonly inputMethod = signal<'file' | 'text'>('file');
  readonly documentSource = computed(() => this.source() === 'ai' || this.source() === 'file' ? this.inputMethod() : this.source());
  readonly text = signal('');
  readonly url = signal('');
  readonly fileBlob = signal('');
  readonly reading = signal(false);
  readonly website = signal('');
  readonly documentation = signal('');
  readonly goal = signal('');
  readonly prompt = signal('');
  readonly inspection = signal<OpenApiInspection | null>(null);
  readonly initialConfig = signal<JsonObject>({ auth: { type: 'none' } });
  readonly initialName = signal('');
  readonly draft = signal<ConnectionFormValue | null>(null);
  readonly parsed = signal<OpenApiParsed | null>(null);
  readonly selected = signal<ReadonlySet<string>>(new Set());
  readonly mutations = signal<ReadonlySet<string>>(new Set());
  readonly filter = signal('');
  readonly templateName = signal('');
  readonly prefix = signal('api');
  readonly previewPrefix = signal('');
  readonly savedConnection = signal<string | null>(null);
  readonly savedWarnings = signal<readonly string[]>([]);
  readonly savedTemplate = signal<Template | null>(null);
  readonly testResult = signal<TestOutcome | null>(null);
  readonly rows = computed(() => {
    const q = this.filter().trim().toLowerCase();
    return (this.parsed()?.operations ?? []).filter(o => `${o.name} ${o.method} ${o.path} ${o.summary} ${o.tags.join(' ')}`.toLowerCase().includes(q));
  });
  readonly connectionName = computed(() => this.savedConnection() ?? this.detail()?.name ?? this.draft()?.name ?? '');
  private generation = 0;
  private fileRead = 0;

  constructor() {
    effect(() => { void this.load(this.connection()); });
    this.destroy.onDestroy(() => { this.generation++; this.fileRead++; });
  }

  async load(connection: string | null = this.connection()): Promise<void> {
    const generation = ++this.generation;
    this.busy.set(true);
    this.errors.set([]);
    try {
      const [spec, list] = await Promise.all([this.types.form('http'), this.connectionsApi.list({ type: 'http' })]);
      if (generation !== this.generation) return;
      this.spec.set(spec);
      this.connections.set(list.items.filter(c => c.type === 'http' && c.verified));
      if (connection) this.existing.set(connection);
    } catch (e) { if (generation === this.generation) this.fail(e); }
    finally { if (generation === this.generation) this.busy.set(false); }
  }

  value(event: Event): string { return (event.target as HTMLInputElement).value; }

  go(step: number): void {
    if (this.busy()) return;
    this.step.set(step);
    this.errors.set([]);
    this.focus('#http-step-title');
  }

  changeSource(value: string): void {
    this.fileRead++;
    this.reading.set(false);
    this.source.set(value);
    this.fileBlob.set('');
    this.errors.set([]);
  }

  changeInputMethod(value: 'file' | 'text'): void {
    this.fileRead++;
    this.reading.set(false);
    this.fileBlob.set('');
    this.inputMethod.set(value);
    this.errors.set([]);
  }

  async pickFile(file: File | null): Promise<void> {
    const run = ++this.fileRead;
    this.reading.set(false);
    this.fileBlob.set('');
    this.errors.set([]);
    if (!file) return;
    if (file.size > 20 * 1024 * 1024) { this.errors.set(['The file is larger than 20 MB.']); return; }
    this.reading.set(true);
    try {
      const blob = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '');
        reader.onerror = () => reject(new Error('The file could not be read. Choose it again.'));
        reader.readAsDataURL(file);
      });
      if (run === this.fileRead) this.fileBlob.set(blob);
    } catch (e) { if (run === this.fileRead) this.fail(e); }
    finally { if (run === this.fileRead) this.reading.set(false); }
  }

  async launch(client: 'claude' | 'codex' | 'cursor' | 'copy'): Promise<void> {
    this.errors.set([]);
    try {
      const prompt = apiImportPrompt(this.website(), this.documentation(), this.goal());
      this.prompt.set(prompt);
      if (client === 'copy') {
        this.notice.set(await this.clipboard.write(prompt) ? 'Prompt copied. Send it to your AI app, then upload the result or paste its YAML or JSON below.' : 'Could not copy. Select and copy the prompt below.');
      } else {
        await this.desktop.openAiPrompt(client, prompt);
        this.notice.set('Review and send the prompt in your AI app, then upload openapi.yaml or paste its contents below.');
      }
    } catch (e) { this.fail(e); }
  }

  async inspect(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.busy() || this.reading()) return;
    this.busy.set(true);
    this.errors.set([]);
    this.parsed.set(null);
    try {
      const request = this.documentSource() === 'url' ? { url: this.url() } : this.documentSource() === 'text' ? { text: this.text() } : { blob: this.fileBlob() };
      const result = await this.api.inspectOpenApi(request);
      if (this.destroy.destroyed) return;
      if (!result.ok || !result.blob || !result.info) { this.errors.set(result.errors); this.focus('#http-errors'); return; }
      this.inspection.set(result);
      const servers = result.info.servers.filter(url => /^https?:\/\//.test(url));
      const auth = result.info.authentication.find(a => a.supported)?.config ?? { type: 'none' };
      this.initialConfig.set({ baseUrls: servers.slice(0, 1), auth });
      this.initialName.set(result.info.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 48) || 'http-api');
      this.templateName.set(result.info.title.slice(0, 64) || 'API tools');
      this.prefix.set(this.initialName().replace(/[^a-z0-9]/g, '').replace(/^[0-9]+/, '').slice(0, 16) || 'api');
      this.detail.set(null);
      if (this.existing()) {
        const detail = await this.connectionsApi.get(this.existing());
        if (detail.type !== 'http' || !detail.verified) throw new Error('Choose a verified HTTP API connection.');
        this.detail.set(detail);
      }
      this.testResult.set(null);
      this.step.set(2);
      this.focus('#http-step-title');
    } catch (e) { this.fail(e); }
    finally { this.busy.set(false); }
  }

  async review(value?: ConnectionFormValue): Promise<void> {
    if (this.busy()) return;
    if (value) this.draft.set(value);
    const refreshing = this.step() === 3;
    this.busy.set(true);
    this.errors.set([]);
    try {
      const parsed = await this.api.parseOpenApi(this.parseRequest());
      this.parsed.set(parsed);
      if (!parsed.ok) { this.errors.set(parsed.errors); this.focus('#http-errors'); return; }
      this.selected.set(new Set(parsed.operations.filter(o => !o.skipped && (refreshing ? this.selected().has(o.opKey) : o.selected)).map(o => o.opKey)));
      if (!refreshing) this.mutations.set(new Set());
      this.previewPrefix.set(this.prefix());
      this.step.set(3);
      this.focus('#http-step-title');
    } catch (e) { this.fail(e); }
    finally { this.busy.set(false); }
  }

  private parseRequest(select?: string[]): OpenApiParseRequest {
    const name = this.savedConnection() ?? this.detail()?.name;
    const draft = this.draft();
    return { blob: this.inspection()!.blob!, name: this.templateName(), prefix: this.prefix(), select,
      ...(name ? { connection: name } : { draft: { name: draft!.name, env: draft!.env, config: draft!.config } }) };
  }

  toggle(operation: OpenApiOperation, checked: boolean): void {
    if (operation.skipped || this.busy()) return;
    const selected = new Set(this.selected());
    if (checked) {
      if (selected.size >= 200) { this.errors.set(['Select at most 200 operations.']); return; }
      selected.add(operation.opKey);
    } else selected.delete(operation.opKey);
    this.selected.set(selected);
  }

  clearSelection(): void { this.selected.set(new Set()); }

  selectVisible(): void {
    for (const row of this.rows()) if (!row.skipped && this.selected().size < 200) this.toggle(row, true);
  }

  markMutation(key: string, checked: boolean): void {
    const keys = new Set(this.mutations());
    if (checked) keys.add(key); else keys.delete(key);
    this.mutations.set(keys);
  }

  async test(value?: ConnectionFormValue): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.errors.set([]);
    this.testResult.set(null);
    try {
      this.testResult.set(this.detail() ? await this.connectionsApi.test(this.detail()!.name)
        : await this.connectionsApi.testDraft({ type: 'http', ...value! }));
    } catch (e) { this.fail(e); }
    finally { this.busy.set(false); }
  }

  async save(): Promise<void> {
    if (this.busy() || this.savedTemplate()) return;
    if (!this.selected().size) { this.errors.set(['Select at least one operation.']); this.focus('#http-errors'); return; }
    if (!this.templateName().trim()) { this.errors.set(['Enter a template name.']); this.focus('#http-template-name'); return; }
    if (this.prefix() !== this.previewPrefix()) { this.errors.set(['Refresh the tools to review their new names before saving.']); this.focus('#http-review-prefix'); return; }
    this.busy.set(true);
    this.errors.set([]);
    try {
      // Recompile the selected operations from the same inspected bytes, never download a changed URL on save.
      const parsed = await this.api.parseOpenApi(this.parseRequest([...this.selected()]));
      if (!parsed.ok || !parsed.definition) { this.errors.set(parsed.errors); this.focus('#http-errors'); return; }
      const operations = parsed.definition['operations'] as JsonObject[];
      if (operations.length !== this.selected().size) throw new Error('Some selected operations are no longer available. Go back and review the connection.');
      const definition = { ...parsed.definition, operations: operations.map(o => this.mutations().has(String(o['opKey'])) ? { ...o, nature: 'mutation' } : o) };
      if (!this.detail() && !this.savedConnection()) {
        const draft = this.draft()!;
        const connection = await this.connectionsApi.create({ type: 'http', ...draft });
        this.savedConnection.set(connection.name);
        // Credentials are no longer needed in the page once the connection exists.
        this.draft.set({ ...draft, secrets: {} });
      }
      const result = await this.api.create({ kind: 'openapi', connection: this.connectionName(), definition });
      if (!result.template || !result.compile.ok) {
        this.errors.set(result.compile.errors.length ? result.compile.errors : ['The template could not be saved.']);
        this.focus('#http-errors');
        return;
      }
      this.savedWarnings.set(result.compile.warnings);
      this.savedTemplate.set(result.template);
      this.step.set(4);
      this.focus('#http-step-title');
    } catch (e) { this.fail(e); }
    finally { this.busy.set(false); }
  }

  private fail(e: unknown): void {
    this.errors.set([isApiError(e) ? e.detail : desktopErrorText(e, 'The core did not answer. Try again.')]);
    this.focus('#http-errors');
  }

  private focus(selector: string): void {
    if (!this.destroy.destroyed) afterNextRender(() => this.doc.querySelector<HTMLElement>(selector)?.focus(), { injector: this.injector });
  }
}
