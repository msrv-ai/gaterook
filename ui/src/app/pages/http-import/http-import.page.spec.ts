import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { CONNECTOR_FORMS } from '../../../testing/connector-schemas';
import { ConnectionsApi, ConnectionRow } from '../../core/api/connections.api';
import { ConnectorTypesApi, ConnectionFormSpec } from '../../core/api/connector-types.api';
import { OpenApiInspection, OpenApiParsed, OpenApiParseRequest, Template, TemplatesApi } from '../../core/api/templates.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { ClipboardWriter } from '../../kit/clipboard';
import { ConnectionFormValue } from '../../kit/elements/connection-form/connection-form';
import { provideKitIcons } from '../../kit/icons';
import { HttpImportPage } from './http-import.page';
import { apiImportPrompt } from './http-import-prompt';

const spec: ConnectionFormSpec = {
  type: 'http', name: 'HTTP API', env: 'dev', schema: CONNECTOR_FORMS['http'], common: {},
  secretFields: [], secretSources: [{ id: 'local', label: 'Encrypted here', available: true, reason: null }],
  envDefaults: { prod: [], stage: [] }, variants: [],
};
const inspection: OpenApiInspection = {
  ok: true, blob: 'c25hcHNob3Q=', errors: [],
  info: { title: 'Example', openapi: '3.1.0', sha256: 'snapshot-hash', servers: ['https://api.example.com/v1'],
    authentication: [{ name: 'token', type: 'http', supported: true, config: { type: 'bearer' } }] },
};
const parsed: OpenApiParsed = {
  ok: true, definition: { name: 'Example', server: 'https://api.example.com/v1', operations: [{ opKey: 'GET /items', name: 'example_items', nature: 'read' }] },
  operations: [
    { opKey: 'GET /items', name: 'example_items', method: 'GET', path: '/items', summary: 'List items', tags: ['items'], nature: 'read', selected: true, skipped: null },
    { opKey: 'POST /upload', name: null, method: 'POST', path: '/upload', summary: 'Upload a file', tags: [], nature: 'mutation', selected: false, skipped: 'multipart/form-data body' },
  ],
  info: {}, errors: [], warnings: ['One operation was skipped.'],
  source: { kind: 'file', url: null, bytes: 8, sha256: 'snapshot-hash' },
};
const value: ConnectionFormValue = { name: 'example-api', env: 'dev', config: { baseUrls: ['https://api.example.com/v1'], auth: { type: 'bearer' } }, secrets: { '/auth/token': { source: 'local', value: 'private-test-token' } } };
const template = { id: 'tpl_example', name: 'Example', kind: 'openapi', connection: 'example-api', version: 1 } as Template;

describe('HTTP API import', () => {
  let fixture: ComponentFixture<HttpImportPage>;
  let page: HttpImportPage;
  let api: { inspectOpenApi: ReturnType<typeof vi.fn>; parseOpenApi: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> };
  let connections: { list: ReturnType<typeof vi.fn>; get: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> };
  let openAiPrompt: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    api = {
      inspectOpenApi: vi.fn(async () => structuredClone(inspection)),
      parseOpenApi: vi.fn(async (_request: OpenApiParseRequest) => structuredClone(parsed)),
      create: vi.fn(async () => ({ template, compile: { ok: true, errors: [], warnings: [] } })),
    };
    connections = { list: vi.fn(async () => ({ items: [] })), get: vi.fn(), create: vi.fn(async () => ({ name: 'example-api' } as ConnectionRow)) };
    openAiPrompt = vi.fn(async () => undefined);
    await TestBed.configureTestingModule({ imports: [HttpImportPage], providers: [
      provideRouter([]), provideHttpClient(), provideKitIcons(),
      { provide: TemplatesApi, useValue: api }, { provide: ConnectionsApi, useValue: connections },
      { provide: ConnectorTypesApi, useValue: { form: async () => spec } },
      { provide: DesktopBridge, useValue: { aiPromptAvailable: true, openAiPrompt, available: false } },
      { provide: ClipboardWriter, useValue: { write: async () => true } },
    ] }).compileComponents();
    fixture = TestBed.createComponent(HttpImportPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  async function review(): Promise<void> {
    page.source.set('url'); page.url.set('https://api.example.com/openapi.yaml');
    await page.inspect();
    page.authConfirmed.set(true);
    await page.review(structuredClone(value));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('previews from the inspected snapshot without persisting or passing credentials to the parser', async () => {
    await review();
    expect(api.parseOpenApi).toHaveBeenCalledWith(expect.objectContaining({ blob: inspection.blob, draft: { name: value.name, env: value.env, config: value.config } }));
    expect(JSON.stringify(api.parseOpenApi.mock.calls)).not.toContain('private-test-token');
    expect(connections.create).not.toHaveBeenCalled();
    expect(api.create).not.toHaveBeenCalled();
    const row = fixture.nativeElement.querySelector('input[aria-label="Import POST /upload"]') as HTMLInputElement;
    expect(row.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('multipart/form-data body');
  });

  it('saves selected tools and preserves the state-changing GET choice', async () => {
    await review();
    page.markMutation('GET /items', true);
    await page.save();
    expect(connections.create).toHaveBeenCalledExactlyOnceWith({ type: 'http', ...value });
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ connection: value.name, definition: expect.objectContaining({ operations: [expect.objectContaining({ nature: 'mutation' })] }) }));
    expect(page.draft()?.secrets).toEqual({});
    expect(page.step()).toBe(4);
  });

  it('retains a created connection after a template failure and reuses it on retry', async () => {
    await review();
    api.create.mockResolvedValueOnce({ template: null, compile: { ok: false, errors: ['Name already used.'], warnings: [] } });
    await page.save();
    expect(page.step()).toBe(3);
    expect(page.savedConnection()).toBe(value.name);
    page.prefix.set('another');
    await page.save();
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(page.errors().join()).toContain('Refresh');
    await page.review();
    expect(page.selected().has('GET /items')).toBe(true);
    await page.save();
    expect(connections.create).toHaveBeenCalledTimes(1);
    expect(api.parseOpenApi.mock.lastCall?.[0]).toMatchObject({ connection: value.name, blob: inspection.blob, prefix: 'another' });
    expect(page.step()).toBe(4);
  });

  it('does not save when the selected operations become unavailable', async () => {
    await review();
    api.parseOpenApi.mockResolvedValueOnce({ ...parsed, definition: { operations: [] } });
    await page.save();
    expect(connections.create).not.toHaveBeenCalled();
    expect(api.create).not.toHaveBeenCalled();
    expect(page.errors().join()).toContain('no longer available');
  });

  it('requires authentication review and reports invalid files without advancing', async () => {
    await page.inspect();
    await page.review(value);
    expect(api.parseOpenApi).not.toHaveBeenCalled();
    expect(page.errors().join()).toContain('Confirm');
    page.go(1);
    api.inspectOpenApi.mockResolvedValueOnce({ ok: false, errors: ['External references are not supported.'] });
    await page.inspect();
    expect(page.step()).toBe(1);
    expect(page.errors()).toEqual(['External references are not supported.']);
  });

  it('opens only a generated prompt and keeps connection secrets out of it', async () => {
    page.website.set('https://example.com'); page.goal.set('Read customers'); page.draft.set(value);
    await page.launch('codex');
    expect(openAiPrompt).toHaveBeenCalledWith('codex', expect.stringContaining('OpenAPI 3.1.0'));
    expect(page.prompt()).not.toContain('private-test-token');
    expect(page.prompt()).toContain('Never invent endpoints');
    page.website.set('https://user:secret@example.com');
    await page.launch('claude');
    expect(openAiPrompt).toHaveBeenCalledTimes(1);
  });

  it('does not select unsupported operations or exceed the tool limit', async () => {
    await review();
    page.toggle(parsed.operations[1], true);
    expect(page.selected().size).toBe(1);
    page.selected.set(new Set(Array.from({ length: 200 }, (_, i) => `GET /${i}`)));
    page.toggle(parsed.operations[0], true);
    expect(page.selected().size).toBe(200);
    expect(page.errors().join()).toContain('200');
  });
});

describe('API discovery prompt', () => {
  it('refuses query credentials, fragments and non-web destinations', () => {
    for (const url of ['file:///etc/passwd', 'https://example.com?token=secret', 'https://example.com#token', 'invalid']) {
      expect(() => apiImportPrompt(url, '', 'Read records')).toThrow();
    }
  });
});
