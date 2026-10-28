import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CONNECTOR_FORMS } from '../../../../testing/connector-schemas';
import { ConnectionFormSpec } from '../../../core/api/connector-types.api';
import { StoredSecret, TestOutcome } from '../../../core/api/connections.api';
import { JsonObject } from '../../../core/api/types';
import { provideKitIcons } from '../../icons';
import { ProjectDiscoveryRequest } from './cloud-projects';
import { ConnectionFormValue, GwConnectionForm } from './connection-form';

const SPEC: ConnectionFormSpec = {
  type: 'logs', name: 'Logs', env: 'dev', schema: CONNECTOR_FORMS['logs'], common: {},
  secretFields: [], secretSources: [{ id: 'local', label: 'Encrypted here', available: true, reason: null }],
  envDefaults: { prod: [], stage: [] }, variants: [],
};
const OUTCOME: TestOutcome = {
  ok: true, latencyMs: 1, serverVersion: null, identity: null, permissions: null,
  hints: [], checks: [], error: null, needsConfirmation: null, confirmationId: null,
  suggestedPolicyChange: null, details: null,
};
const listing = (ids = ['example-dev', 'example-prod'], complete = true): TestOutcome => ({
  ...OUTCOME, details: { projects: ids.map((projectId) => ({ projectId, displayName: `Display ${projectId}` })), discoveryComplete: complete },
});

@Component({
  imports: [GwConnectionForm],
  template: `<form gwConnectionForm idPrefix="cf" name="cloud-logs" [spec]="spec()" [config]="config()" [mode]="mode()" [stored]="stored()" (save)="saved.push($event)" (discoverProjects)="requests.push($event)"></form>`,
})
class Host {
  readonly spec = signal(SPEC);
  readonly config = signal<JsonObject>({ backend: 'gcp-logging', credentials: { source: 'adc' } });
  readonly mode = signal<'create' | 'edit'>('create');
  readonly stored = signal<readonly StoredSecret[]>([]);
  readonly saved: ConnectionFormValue[] = [];
  readonly requests: ProjectDiscoveryRequest[] = [];
  readonly form = viewChild.required(GwConnectionForm);
}

async function render(type = 'logs') {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
  const fixture = TestBed.createComponent(Host);
  document.body.appendChild(fixture.nativeElement);
  const host = fixture.componentInstance;
  host.spec.set({ ...SPEC, type, schema: CONNECTOR_FORMS[type] });
  host.config.set({ ...(type === 'logs' ? { backend: 'gcp-logging' } : {}), credentials: { source: 'adc' } });
  const scopeField = type === 'gcp' ? 'scopes' : 'resourceNames';
  const el = fixture.nativeElement as HTMLElement;
  const settle = async () => { fixture.detectChanges(); await fixture.whenStable(); };
  await settle();
  const picker = () => host.form().projects()!;
  const load = async () => {
    el.querySelector<HTMLButtonElement>('#cf-project-load')!.click();
    await settle();
    return host.requests.at(-1)!;
  };
  const complete = async (request: ProjectDiscoveryRequest, outcome: TestOutcome) => {
    picker().complete(request, outcome);
    await settle();
  };
  const select = async (id: string) => {
    const input = el.querySelector<HTMLSelectElement>('#cf-project-select')!;
    input.value = id;
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
  };
  const manual = async (scope: string) => {
    const input = el.querySelector<HTMLInputElement>(`#cf-${scopeField}`)!;
    input.value = scope;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await settle();
  };
  const save = async () => {
    el.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
  };
  return { host, el, settle, picker, load, complete, select, manual, save };
}

afterEach(() => { document.body.innerHTML = ''; });

describe.each(['logs', 'gcp-logging', 'gcp'])('%s project selection', (type) => {
  const scopeField = type === 'gcp' ? 'scopes' : 'resourceNames';
  it('places project selection after credentials and saves multiple projects with their resource prefixes', async () => {
    const v = await render(type);
    const credentials = v.el.querySelector('#cf-credentials-source')!;
    const resourceNames = v.el.querySelector(`#cf-${scopeField}`)!;
    expect(credentials.compareDocumentPosition(resourceNames) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(v.el.querySelectorAll(`#cf-${scopeField}`)).toHaveLength(1);
    expect(v.el.querySelector('#cf-projects-title')!.textContent).toBe(type === 'gcp'
      ? 'Projects, folders or organizations' : 'Projects and log views');

    const request = await v.load();
    expect(request.value).toMatchObject({ name: 'cloud-logs', config: { ...(type === 'logs' ? { backend: 'gcp-logging' } : {}), credentials: { source: 'adc' } }, secrets: {} });
    expect(resourceNames.getAttribute('aria-invalid')).not.toBe('true');
    await v.complete(request, listing());
    expect(resourceNames.getAttribute('aria-invalid')).not.toBe('true');
    expect(v.el.querySelector('#cf-project-select')!.textContent).toContain('Display example-dev');
    await v.select('example-dev');
    expect(v.el.querySelector<HTMLSelectElement>('#cf-project-select')!.value).toBe('');
    await v.select('example-prod');
    await v.select('example-dev');
    await v.save();
    expect(v.host.saved).toHaveLength(1);
    expect(v.host.saved[0].config[scopeField]).toEqual(['projects/example-dev', 'projects/example-prod']);
  });

  it('requires a selected service account key before discovery and sends it only as a secret', async () => {
    const v = await render(type);
    const source = v.el.querySelector<HTMLSelectElement>('#cf-credentials-source')!;
    source.value = 'serviceAccountKey';
    source.dispatchEvent(new Event('change', { bubbles: true }));
    await v.settle();
    await v.load();
    expect(v.host.requests).toHaveLength(0);

    const secret = v.el.querySelector<HTMLTextAreaElement>('#cf-credentials-serviceAccountKey')!;
    const key = '{"type":"service_account","project_id":"example-dev"}';
    secret.value = key;
    secret.dispatchEvent(new Event('input', { bubbles: true }));
    await v.settle();
    const request = await v.load();
    expect(request.value.secrets).toEqual({ '/credentials/serviceAccountKey': { source: 'local', value: key } });
    expect(request.value.config['credentials']).toMatchObject({ source: 'serviceAccountKey' });
    expect(v.el.querySelector('#cf-project-load')!.getAttribute('aria-busy')).toBe('true');
    await v.load();
    expect(v.host.requests).toHaveLength(1);
  });

  it('focuses the missing credential before the missing scope when submitting the reordered form', async () => {
    const v = await render(type);
    const source = v.el.querySelector<HTMLSelectElement>('#cf-credentials-source')!;
    source.value = 'serviceAccountKey';
    source.dispatchEvent(new Event('change', { bubbles: true }));
    await v.settle();
    await v.save();
    expect(v.host.saved).toHaveLength(0);
    expect(document.activeElement?.id).toBe('cf-credentials-serviceAccountKey');
  });

  it('ignores late results and clears options when credentials change, while retaining chosen scopes', async () => {
    const v = await render(type);
    await v.manual('projects/saved-project');
    const old = await v.load();
    const impersonate = v.el.querySelector<HTMLInputElement>('#cf-credentials-impersonate')!;
    impersonate.value = 'reader@example-dev.iam.gserviceaccount.com';
    impersonate.dispatchEvent(new Event('input', { bubbles: true }));
    await v.settle();
    expect(v.picker().complete(old, listing(['old-project']))).toBe(false);
    await v.settle();
    expect(v.el.querySelector('#cf-project-select')?.textContent ?? '').not.toContain('old-project');
    const current = await v.load();
    expect(current.value.config['credentials']).toMatchObject({ impersonate: 'reader@example-dev.iam.gserviceaccount.com' });
    await v.complete(current, listing(['current-project']));
    await v.select('current-project');
    expect(v.host.form().value().config[scopeField]).toEqual(['projects/saved-project', 'projects/current-project']);

    impersonate.value = 'other@example-dev.iam.gserviceaccount.com';
    impersonate.dispatchEvent(new Event('input', { bubbles: true }));
    await v.settle();
    expect(v.el.querySelector('#cf-project-select')?.textContent ?? '').not.toContain('current-project');
    expect(v.host.form().value().config[scopeField]).toEqual(['projects/saved-project', 'projects/current-project']);
  });

  it.each(['error', 'empty', 'partial'] as const)('keeps manual resource scopes usable after discovery returns %s', async (state) => {
    const v = await render(type);
    const request = await v.load();
    await v.complete(request, state === 'error'
      ? { ...OUTCOME, ok: false, error: { code: 'permission-denied', message: 'Project listing is not permitted.', technical: null } }
      : listing(state === 'empty' ? [] : ['example-dev'], state !== 'partial'));
    if (state === 'error') expect(v.el.textContent).toContain('Project listing is not permitted.');
    if (state === 'partial') expect(v.el.textContent).toMatch(/incomplete/i);
    const scope = type === 'gcp' ? 'folders/123456' : 'projects/example-dev/locations/global/buckets/application/views/reader';
    await v.manual(scope);
    await v.save();
    expect(v.host.saved).toHaveLength(1);
    expect(v.host.saved[0].config[scopeField]).toEqual([scope]);
  });

  it('validates manual resource names and explains the projects/ prefix', async () => {
    const v = await render(type);
    await v.manual('example-dev');
    await v.save();
    expect(v.host.saved).toHaveLength(0);
    expect(v.el.querySelector(`#cf-${scopeField}`)!.getAttribute('aria-invalid')).toBe('true');
    expect(v.el.textContent).toContain('projects/');
  });

  it('keeps a saved scope editable without requiring project discovery', async () => {
    const v = await render(type);
    v.host.mode.set('edit');
    v.host.config.set({ ...(type === 'logs' ? { backend: 'gcp-logging' } : {}), credentials: { source: 'adc' }, [scopeField]: ['projects/saved-project'] });
    await v.settle();
    await v.save();
    expect(v.host.requests).toHaveLength(0);
    expect(v.host.saved).toHaveLength(1);
    expect(v.host.saved[0].config[scopeField]).toEqual(['projects/saved-project']);
  });

});

describe('Legacy logs project selection', () => {
  it('shows the picker only for the Google Cloud Logging backend', async () => {
    const v = await render();
    const request = await v.load();
    const originalPicker = v.picker();
    const backend = v.el.querySelector<HTMLSelectElement>('#cf-backend')!;
    backend.value = 'loki';
    backend.dispatchEvent(new Event('change', { bubbles: true }));
    await v.settle();
    expect(v.el.querySelector('#cf-project-load')).toBeNull();
    expect(v.el.querySelector('#cf-resourceNames')).toBeNull();
    originalPicker.complete(request, listing());
    await v.settle();
    expect(v.el.querySelector('#cf-project-select')).toBeNull();
    expect(v.host.form().value().config['backend']).toBe('loki');
  });
});
