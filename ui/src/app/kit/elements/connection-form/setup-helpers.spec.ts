import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CONNECTOR_FORMS } from '../../../../testing/connector-schemas';
import { ConnectionFormSpec } from '../../../core/api/connector-types.api';
import { Suggested } from '../../../core/api/connections.api';
import { JsonObject } from '../../../core/api/types';
import { compileSchema, isAdvanced, isRequired } from '../../../schema-form/compile';
import { provideKitIcons } from '../../icons';
import { ProjectDiscoveryRequest } from './cloud-projects';
import { ConnectionFormValue, DatabaseDiscoveryRequest, GwConnectionForm, SuggestionRequest } from './connection-form';

/**
 * The setup helpers of the connection form: optional settings gathered under a collapsed Advanced, a field required
 * by another branch of the schema never called optional, and suggestions asked for while the person types (local)
 * and once the form can sign in (service), offered under their fields and filled in while a field is untouched.
 */
function spec(type: string): ConnectionFormSpec {
  return {
    type, name: type, env: 'dev', schema: CONNECTOR_FORMS[type], common: {}, secretFields: [],
    secretSources: [{ id: 'local', label: 'Encrypted here', available: true, reason: null }],
    envDefaults: { prod: [], stage: [] }, variants: [],
  };
}

@Component({
  imports: [GwConnectionForm],
  template: `<form gwConnectionForm idPrefix="cf" aria-label="New connection" helpers [spec]="spec()" name="dev-cluster" [config]="config()" (save)="saved.push($event)" (suggest)="asked.push($event)" (discoverDatabases)="databases.push($event)" (discoverProjects)="projects.push($event)"></form>`,
})
class Host {
  readonly spec = signal(spec('kubernetes'));
  readonly config = signal<JsonObject | null>(null);
  readonly saved: ConnectionFormValue[] = [];
  readonly asked: SuggestionRequest[] = [];
  readonly databases: DatabaseDiscoveryRequest[] = [];
  readonly projects: ProjectDiscoveryRequest[] = [];
  readonly form = viewChild.required(GwConnectionForm);
}

beforeEach(() => {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
});
afterEach(() => (document.body.innerHTML = ''));

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function render(type = 'kubernetes', config: JsonObject | null = null) {
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.spec.set(spec(type));
  fixture.componentInstance.config.set(config);
  document.body.appendChild(fixture.nativeElement);
  const el = fixture.nativeElement as HTMLElement;
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  const host = fixture.componentInstance;
  const type_ = async (id: string, value: string) => {
    const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  /** The latest request of a scope, after its debounce. */
  const asked = async (scope: 'local' | 'service', ms = 1000) => {
    await wait(ms);
    await settle();
    return host.asked.filter((r) => r.scope === scope).at(-1) ?? null;
  };
  const answer = async (request: SuggestionRequest, result: Suggested) => {
    host.form().completeSuggestions(request.key, request.scope, result);
    await settle();
  };
  const label = (id: string) => el.querySelector(`label[for="${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const inAdvanced = (id: string) => !!el.querySelector(`#cf-advanced-settings-body #${id}`);
  return { fixture, el, host, settle, type: type_, asked, answer, label, inAdvanced };
}

const contexts: Suggested = {
  fields: {
    '/context': { value: 'kind-dev', source: '~/.kube/config', complete: true, options: [
      { value: 'kind-dev', label: 'kind-dev (current)', detail: '127.0.0.1:6443' },
      { value: 'gke-prod', label: null, detail: '34.1.2.3' },
    ] },
    '/namespace': { value: 'app-dev', source: 'context kind-dev', complete: true, options: [{ value: 'app-dev', label: null, detail: null }] },
  },
  notes: ['Context gke-prod signs in with gke-gcloud-auth-plugin.'],
};

describe('setup helpers: the compiled form', () => {
  it('requires the Context of a kubeconfig file although only another branch says so', () => {
    const form = compileSchema(CONNECTOR_FORMS['kubernetes']);
    const context = form.all.find((n) => n.pointer === '/context')!;
    expect(context.required).toBe(false);
    expect(isRequired(context, { auth: 'kubeconfig-file' })).toBe(true);
    expect(isRequired(context, { auth: 'kubeconfig' })).toBe(false);
    // It decides nothing, but may be required: never under Advanced.
    expect(isAdvanced(context, form)).toBe(false);
  });

  it('puts optional settings under Advanced, never credentials, choices that decide other fields or required ones', () => {
    const form = compileSchema(CONNECTOR_FORMS['kubernetes']);
    const advanced = (pointer: string) => isAdvanced(form.all.find((n) => n.pointer === pointer)!, form);
    expect(['/caCert', '/insecureSkipTlsVerify', '/requestTimeoutMs', '/proxy'].map(advanced)).toEqual([true, true, true, true]);
    expect(['/auth', '/server', '/token', '/namespace', '/allowExecPlugin'].map(advanced)).toEqual([false, false, false, false, false]);
    const aws = compileSchema(CONNECTOR_FORMS['aws']);
    const awsAdvanced = (pointer: string) => isAdvanced(aws.all.find((n) => n.pointer === pointer)!, aws);
    expect(['/credentials/roleArn', '/credentials/externalId', '/accountAlias'].map(awsAdvanced)).toEqual([true, true, true]);
    expect(['/credentials/source', '/credentials/accessKeyId', '/regions'].map(awsAdvanced)).toEqual([false, false, false]);
  });
});

describe('setup helpers: the Kubernetes form', () => {
  it('shows only what a kubeconfig file needs, with the optional settings collapsed under Advanced', async () => {
    const v = await render();
    const select = v.el.querySelector<HTMLSelectElement>('#cf-auth')!;
    select.value = 'kubeconfig-file';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await v.settle();

    expect(v.label('cf-context')).toBe('Context');
    expect(v.label('cf-kubeconfigPath')).toBe('kubeconfig file');
    expect(v.el.querySelector('#cf-allowExecPlugin')).not.toBeNull();
    expect(v.inAdvanced('cf-allowExecPlugin')).toBe(false);
    for (const id of ['cf-caCert', 'cf-insecureSkipTlsVerify', 'cf-requestTimeoutMs', 'cf-proxy']) expect(v.inAdvanced(id)).toBe(true);
    expect(v.el.querySelector('#cf-advanced-settings-body')!.hasAttribute('hidden')).toBe(true);
    // Values Test connection sets once the person trusted them don't show as fields to type into.
    expect(v.el.querySelector('#cf-trustedExec')).toBeNull();
    expect(v.el.querySelector('#cf-pinnedCluster-serverUrl')).toBeNull();
    // A path on this computer is typed, never read from a secret store.
    expect(v.el.querySelector('#cf-kubeconfigPath-src')).toBeNull();
    expect(v.el.querySelector('#cf-namespace-src')).toBeNull();

    v.el.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
    v.el.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await v.settle();
    expect(v.el.querySelector('#cf-context-err')?.textContent).toMatch(/context/i);
    expect(v.host.saved).toEqual([]);
  });

  it('asks for local suggestions as the form changes and fills in the current context', async () => {
    const v = await render('kubernetes', { auth: 'kubeconfig-file', kubeconfigPath: '~/.kube/config' });
    const first = (await v.asked('local'))!;
    expect(first.value.config).toMatchObject({ auth: 'kubeconfig-file', kubeconfigPath: '~/.kube/config' });
    // No sign-in yet: the Context is still empty.
    expect(await v.asked('service', 50)).toBeNull();

    await v.answer(first, contexts);
    const context = v.el.querySelector<HTMLSelectElement>('#cf-context')!;
    expect([...context.options].map((o) => o.value)).toEqual(['kind-dev', 'gke-prod']);
    expect(context.value).toBe('kind-dev');
    expect(v.el.querySelector('#cf-context-found-line')?.textContent).toContain('Filled in from ~/.kube/config.');
    expect(v.el.querySelector<HTMLInputElement>('#cf-namespace')!.value).toBe('app-dev');
    expect(v.el.querySelector('#cf-helpers')?.textContent).toContain('gke-gcloud-auth-plugin');

    // The filled-in form can sign in: a service request follows, and its namespaces are offered under the field.
    const service = (await v.asked('service'))!;
    expect(service.value.config).toMatchObject({ context: 'kind-dev', namespace: 'app-dev' });
    await v.answer(service, { fields: { '/namespace': { value: null, source: 'the cluster', complete: true, options: [
      { value: 'app-dev', label: null, detail: null }, { value: 'payments', label: null, detail: null },
    ] } }, notes: ['Signed in to 127.0.0.1:6443: 2 namespaces. Choose the default namespace.'] });
    const line = v.el.querySelector('#cf-namespace-found-line')!;
    expect(line.textContent).toContain('Filled in from context kind-dev. Also found in the cluster:');
    [...line.querySelectorAll('button')].find((b) => b.textContent === 'payments')!.click();
    await v.settle();
    expect(v.el.querySelector<HTMLInputElement>('#cf-namespace')!.value).toBe('payments');
    expect(v.el.querySelector('#cf-helpers')?.textContent).toContain('2 namespaces');
  });

  it('drops an answer for settings that changed meanwhile and never overwrites what the person typed', async () => {
    const v = await render('kubernetes', { auth: 'kubeconfig-file', kubeconfigPath: '~/.kube/config' });
    const stale = (await v.asked('local'))!;
    await v.type('cf-namespace', 'mine');
    expect(v.host.form().completeSuggestions(stale.key, 'local', contexts)).toBe(false);
    const fresh = (await v.asked('local'))!;
    await v.answer(fresh, contexts);
    expect(v.el.querySelector<HTMLSelectElement>('#cf-context')!.value).toBe('kind-dev');
    expect(v.el.querySelector<HTMLInputElement>('#cf-namespace')!.value).toBe('mine');
  });
});

describe('setup helpers: Auto for http:// or https://', () => {
  it('adds the scheme the address answered once the person leaves the field', async () => {
    const v = await render('qdrant');
    await v.type('cf-url', 'localhost:6333');
    const request = (await v.asked('local'))!;
    await v.answer(request, { fields: { '/url': { value: 'http://localhost:6333', source: 'a TLS check of localhost:6333',
      complete: true, options: [{ value: 'http://localhost:6333', label: null, detail: null }] } },
    notes: ['localhost:6333 answers without TLS: http:// is added when you leave the field.'] });
    const input = v.el.querySelector<HTMLInputElement>('#cf-url')!;
    input.dispatchEvent(new Event('blur'));
    await v.settle();
    expect(input.value).toBe('http://localhost:6333');
    expect(v.el.querySelector('#cf-url-found-line')?.textContent).toContain('Filled in from a TLS check of localhost:6333.');
  });
});

describe('setup helpers: the Name', () => {
  it('gets a Connection section of its own when the credentials come first', async () => {
    const v = await render('gcp');
    const legends = [...v.el.querySelectorAll('.gw-form__legend')].map((e) => e.textContent?.trim());
    expect(legends[0]).toBe('Connection');
    expect(v.el.querySelector('#cf-name')).not.toBeNull();
  });
});

describe('setup helpers: lookups the form starts by itself', () => {
  it('loads the databases once a new connection can sign in, and a failure is a quiet hint', async () => {
    const v = await render('postgres');
    await v.type('cf-connection-host', 'db.dev.internal');
    await v.type('cf-credentials-user', 'app');
    await wait(1700);
    await v.settle();
    // No password typed yet: PostgreSQL accepts a user without one, so the form may sign in already.
    expect(v.host.databases).toHaveLength(1);
    expect(v.host.databases[0].value.config).toMatchObject({ connection: { host: 'db.dev.internal' }, credentials: { user: 'app' } });

    v.host.form().completeDatabaseDiscovery(v.host.databases[0].key, null, 'password authentication failed for user "app"');
    await v.settle();
    expect(v.el.querySelector('[role="alert"]')?.textContent ?? '').not.toContain('password authentication failed');
    expect(v.el.textContent).toContain("Couldn't load databases yet: password authentication failed");
    // The same settings are not tried again by themselves.
    await wait(1700);
    await v.settle();
    expect(v.host.databases).toHaveLength(1);
  });

  it('loads the projects a Google sign-in on this computer can see', async () => {
    const v = await render('gcp');
    await wait(1700);
    await v.settle();
    expect(v.host.projects).toHaveLength(1);
    expect(v.host.projects[0].value.config).toMatchObject({ credentials: { source: 'adc' } });
  });
});
