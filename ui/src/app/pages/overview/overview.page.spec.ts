import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ApprovalDetail, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { ConnectionRow, ConnectionsApi, TestOutcome } from '../../core/api/connections.api';
import { EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { Overview, OverviewApi } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { NOW, ROWS, clientsFound, clientsInUse, devDb, firstRun, overview, row } from './overview.fixtures';
import { OverviewPage, SKELETON_DELAY_MS } from './overview.page';
import { endpoint, kitEndpoints } from '../endpoints/endpoints.fixtures';
import { detail as endpointDetail, groups as endpointTools } from '../endpoint-detail/endpoint-detail.fixtures';

/** The page against fake admin API services: what it shows in each state, and what it asks the core to do. */

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: (at) => (at ? 'a while ago' : ''),
  absolute: (at) => (at ? `abs ${at}` : ''),
  iso: (at) => at ?? '',
  date: () => '',
  clock: () => '12:10',
};

/** A controllable promise: the test decides when (and how) the core answers. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

class FakeEvents {
  readonly handlers = new Map<string, Set<(data: unknown) => void>>();
  on(type: string, h: (data: unknown) => void): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type)!.add(h);
    return () => this.handlers.get(type)!.delete(h);
  }
  emit(type: string, data: unknown): void {
    this.handlers.get(type)?.forEach((h) => h(data));
  }
}

const unreachable: TestOutcome = {
  ok: false,
  latencyMs: 0,
  serverVersion: null,
  identity: null,
  permissions: null,
  hints: [],
  checks: [],
  error: { code: 'unreachable', message: 'billing.stage.acme.dev did not answer. Check the VPN or the host, then test again.', technical: null },
  needsConfirmation: null,
  confirmationId: null,
  suggestedPolicyChange: null,
  details: null,
};

let calls: string[];
let overviewAnswers: (() => Promise<Overview>)[];
let clientsAnswer: () => Promise<ClientsView>;
let endpointsAnswer: () => Promise<EndpointSummary[]>;
let connectionsAnswer: () => Promise<readonly ConnectionRow[]>;
let events: FakeEvents;
let tests: { name: string; answer: ReturnType<typeof deferred<TestOutcome>> }[];
let savedConnections: ConnectionRow[];
let savedEndpoints: EndpointSummary[];
let connectionWrite: (name: string, enabled: boolean) => Promise<ConnectionRow>;

function setupPage(first: () => Promise<Overview> = () => Promise.resolve(overview()), clients: () => Promise<ClientsView> = () => Promise.resolve(clientsInUse())): void {
  calls = [];
  overviewAnswers = [first];
  clientsAnswer = async () => {
    const view = await clients();
    return { ...view, clients: view.clients.map((card) => ({ ...card, methods: [{ id: 'token', label: 'Token', prodAllowed: true, reason: null }] })) };
  };
  savedEndpoints = kitEndpoints();
  savedConnections = ROWS.map((connection) => connection.name === 'github-mcp'
    ? { ...connection, type: 'mcp-server', typeName: 'GitHub MCP' }
    : ['aws-stage', 'billing-api'].includes(connection.name)
      ? { ...connection, usedBy: [...connection.usedBy, 'stage-readonly'] } : connection);
  endpointsAnswer = () => Promise.resolve(savedEndpoints);
  connectionsAnswer = () => Promise.resolve(savedConnections);
  connectionWrite = async (name, enabled) => {
    savedConnections = savedConnections.map((connection) => connection.name === name ? { ...connection, enabled } : connection);
    return savedConnections.find((connection) => connection.name === name)!;
  };
  events = new FakeEvents();
  tests = [];
  const overviewApi: Partial<OverviewApi> = {
    get: () => {
      calls.push('GET /api/overview');
      const next = overviewAnswers.length > 1 ? overviewAnswers.shift()! : overviewAnswers[0];
      return next();
    },
  };
  const approvalsApi: Partial<ApprovalsApi> = {
    get: (id) =>
      Promise.resolve({
        id,
        action: { kind: 'sql', language: 'sql', text: `ACTION OF ${id}`, segments: [{ t: 'text', v: `ACTION OF ${id}` }], hiddenChars: 0, confusables: 0, warning: null, diff: null },
      } as unknown as ApprovalDetail),
  };
  const connectionsApi: Partial<ConnectionsApi> = {
    list: async () => {
      calls.push('GET /api/connections');
      const items = [...await connectionsAnswer()];
      return { items, facets: { health: {}, env: {}, type: {} }, total: items.length };
    },
    setEnabled: (name, enabled) => { calls.push(`connection ${name} ${enabled}`); return connectionWrite(name, enabled); },
    get: (name) => Promise.resolve({ ...devDb(), name, usedBy: name === 'dev-db' ? devDb().usedBy : [] }),
    test: (name) => {
      calls.push(`test ${name}`);
      const answer = deferred<TestOutcome>();
      tests.push({ name, answer });
      return answer.promise;
    },
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: OverviewApi, useValue: overviewApi },
      { provide: ApprovalsApi, useValue: approvalsApi },
      { provide: ClientsApi, useValue: { list: () => { calls.push('GET /api/clients'); return clientsAnswer(); } } },
      { provide: EndpointsApi, useValue: {
        list: () => { calls.push('GET /api/endpoints'); return endpointsAnswer(); },
        get: async (slug: string) => endpointDetail({ endpoint: savedEndpoints.find((endpoint) => endpoint.slug === slug) }),
        tools: async () => endpointTools(),
        disable: async (slug: string) => {
          calls.push(`endpoint ${slug} false`);
          savedEndpoints = savedEndpoints.map((endpoint) => endpoint.slug === slug ? { ...endpoint, status: 'disabled' } : endpoint);
          return savedEndpoints.find((endpoint) => endpoint.slug === slug)!;
        },
        enable: async (slug: string) => {
          calls.push(`endpoint ${slug} true`);
          savedEndpoints = savedEndpoints.map((endpoint) => endpoint.slug === slug ? { ...endpoint, status: 'running' } : endpoint);
          return savedEndpoints.find((endpoint) => endpoint.slug === slug)!;
        },
        previewConnection: async (slug: string, name: string) => {
          calls.push(`preview ${slug} ${name}`);
          return { connection: name, env: 'dev', tools: [{ toolKey: 'mcp:list', tool: 'List issues', mode: 'READ_ONLY', locked: null, declaredBy: 'server' }], warnings: [], refusal: null };
        },
        addConnection: async (slug: string, name: string) => { calls.push(`add ${slug} ${name}`); return {}; },
      } },
      { provide: ConnectionsApi, useValue: connectionsApi },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0) } },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function render(): Promise<{ fixture: ComponentFixture<OverviewPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(OverviewPage);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const byText = <T extends HTMLElement>(root: ParentNode, selector: string, name: string) =>
  Array.from(root.querySelectorAll<T>(selector)).find((b) => text(b) === name || text(b).startsWith(name + ' ')) ?? null;
const href = (a: Element | null) => a?.getAttribute('href') ?? null;


const service = (root: ParentNode, name: string) => [...root.querySelectorAll<HTMLElement>('[data-map-service]')].find((node) => node.dataset['mapService'] === name) ?? null;
const services = (root: ParentNode) => [...root.querySelectorAll<HTMLElement>('[data-map-service]')].map((node) => node.dataset['mapService']);
const app = (root: ParentNode, name: string) => root.querySelector<HTMLElement>(`[data-map-app="${name}"]`)!;
const switchOf = (root: ParentNode) => root.querySelector<HTMLInputElement>('input[role="switch"]')!;
const findButton = (root: ParentNode, name: string) => byText<HTMLButtonElement>(root, 'button', name)!;

async function selectEndpoint(fixture: ComponentFixture<unknown>, el: HTMLElement, slug: string): Promise<void> {
  const radio = [...el.querySelectorAll<HTMLInputElement>('input[name="overview-endpoint"]')].find((input) => input.value === slug)!;
  radio.click();
  await settle(fixture);
}

async function searchFor(fixture: ComponentFixture<unknown>, el: HTMLElement, value: string): Promise<HTMLInputElement> {
  const input = el.querySelector<HTMLInputElement>('input[aria-label="Find a connection"]')!;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await settle(fixture);
  return input;
}

async function showAll(fixture: ComponentFixture<unknown>, el: HTMLElement): Promise<void> {
  findButton(el.querySelector('[aria-labelledby="ov-services-title"]')!, 'Show all').click();
  await settle(fixture);
}

function healthyMcp(name: string, usedBy: string[]): ConnectionRow {
  return { ...row(name, 'dev', usedBy), type: 'mcp-server', typeName: 'External MCP server' };
}

function openReview(el: HTMLElement): HTMLDialogElement | null {
  return [...el.querySelectorAll<HTMLDialogElement>('dialog[open]')].find((dialog) => text(dialog).includes('Review service access')) ?? null;
}

beforeEach(() => setupPage());

describe('Overview connection map', () => {
  it('toggles an endpoint from its card without selecting it or changing connections', async () => {
    const { fixture, el } = await render();
    const card = el.querySelector('[data-map-endpoint="stage-readonly"]')!.parentElement!;
    const control = switchOf(card);
    expect(control.checked).toBe(true);
    control.click();
    await settle(fixture);
    expect(control.checked).toBe(false);
    expect(calls).toContain('endpoint stage-readonly false');
    expect(calls.some((call) => call.startsWith('connection '))).toBe(false);
    expect(el.querySelector<HTMLInputElement>('input[name="overview-endpoint"]:checked')!.value).toBe('dev');
    expect(text(el.querySelector('#ov-detail-title'))).toBe('Access through dev');
    expect(text(card)).toContain('Access paused');
  });

  it('shows scoped connection tools and restores endpoint details when the selected endpoint is clicked', async () => {
    const { fixture, el } = await render();
    await showAll(fixture, el);
    const details = el.querySelector('#ov-detail')!;
    expect(details.querySelector('gw-access-switch[kind="endpoint"]')).not.toBeNull();
    service(el, 'dev-db')!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(fixture);
    expect(text(details)).toContain('sql_query');
    expect(details.querySelector('gw-overview-connection-tools')).not.toBeNull();
    expect(details.querySelector('gw-access-switch[kind="endpoint"]')).toBeNull();
    await selectEndpoint(fixture, el, 'dev');
    expect(details.querySelector('gw-overview-connection-tools')).toBeNull();
    expect(details.querySelector('gw-access-switch[kind="endpoint"]')).not.toBeNull();
    expect(text(details)).toContain('Access through dev');
  });

  it('changes both service membership and AI app access with the selected endpoint', async () => {
    const { fixture, el } = await render();
    await showAll(fixture, el);
    expect(services(el)).toContain('dev-db');
    expect(services(el)).not.toContain('prod-replica');
    expect(text(app(el, 'codex'))).toContain('Connected');
    expect(app(el, 'codex').querySelector('a')).toBeNull();

    await selectEndpoint(fixture, el, 'incident-prod');
    expect(services(el)).toEqual(['prod-logs', 'prod-replica', 'prod-vm']);
    expect(text(app(el, 'codex'))).toContain('Not connected');
    const connect = app(el, 'codex').querySelector('a')!;
    expect(href(connect)).toBe('/clients?endpoint=incident-prod&connect=codex#cl-cx-t');
    expect(connect.getAttribute('aria-label')).toBe('Connect Codex');
    expect(connect.hasAttribute('aria-disabled')).toBe(false);
    expect(text(app(el, 'claude-desktop'))).toContain('Connected');
    expect(href(app(el, 'antigravity').querySelector('a'))).toBe('/clients#cl-ag-t');
    expect(el.querySelector('a[href="/endpoints#create"]')).not.toBeNull();
  });

  it('starts compact, searches hidden long connection names and service types, and clears the filter', async () => {
    const longName = 'orders-reporting-read-replica-for-international-finance-team';
    savedConnections.push({ ...row(longName, 'dev', ['dev']), typeName: 'Reporting database' });
    const { fixture, el } = await render();
    expect(services(el)).toHaveLength(4);
    expect(service(el, longName)).toBeNull();
    const input = await searchFor(fixture, el, 'international-finance');
    expect(services(el)).toEqual([longName]);
    expect(text(service(el, longName))).toContain(longName);
    expect(text(el.querySelector('#ov-services-scope'))).toBe('1 of 9 services on dev');
    expect(text(service(el, longName)?.querySelector('[role="tooltip"]'))).toContain(longName);
    await searchFor(fixture, el, 'Reporting database');
    expect(services(el)).toEqual([longName]);
    el.querySelector<HTMLButtonElement>('button[aria-label="Clear connection search"]')!.click();
    await settle(fixture);
    expect(input.value).toBe('');
    expect(document.activeElement).toBe(input);
    expect(services(el)).toHaveLength(4);
    await showAll(fixture, el);
    expect(services(el)).toContain(longName);
    expect(services(el)).toHaveLength(9);
  });

  it('preserves a search while its scope changes and offers a usable empty state', async () => {
    const { fixture, el } = await render();
    await searchFor(fixture, el, 'prod-replica');
    expect(text(el)).toContain('No matching connections');
    await selectEndpoint(fixture, el, 'incident-prod');
    expect(services(el)).toEqual(['prod-replica']);
    await searchFor(fixture, el, 'does-not-exist');
    findButton(el.querySelector('#ov-services')!, 'Clear search').click();
    await settle(fixture);
    expect(services(el)).toEqual(['prod-logs', 'prod-replica', 'prod-vm']);
  });

  it('shows only enabled unreachable connections from the endpoint badge and resets both filters', async () => {
    savedConnections.push({ ...row('disabled-service', 'dev', ['dev']), enabled: false, health: { state: 'unreachable', latencyMs: null, message: null, checkedAt: new Date(NOW).toISOString() } });
    const { fixture, el } = await render();
    await searchFor(fixture, el, 'redis');
    const problems = el.querySelector<HTMLButtonElement>('button[aria-label="Show 1 unreachable connections on dev"]')!;
    expect(problems).not.toBeNull();
    expect(text(document.getElementById(problems.getAttribute('aria-describedby')!))).toContain('1 enabled connection is unreachable');
    problems.click();
    await settle(fixture);
    expect(services(el)).toEqual(['billing-api']);
    expect(problems.getAttribute('aria-pressed')).toBe('true');
    expect(service(el, 'billing-api')?.classList).toContain('gw-card--invalid');
    expect((el.querySelector('#ov-service-search') as HTMLInputElement).value).toBe('');
    expect(service(el, 'cache-redis')).toBeNull();
    expect(service(el, 'disabled-service')).toBeNull();
    findButton(el.querySelector('.gw-alert--danger')!, 'Show all').click();
    await settle(fixture);
    expect(services(el)).toContain('cache-redis');
    expect(services(el)).toContain('disabled-service');
    expect(service(el, 'cache-redis')?.classList.contains('gw-card--invalid')).toBe(false);
    expect(service(el, 'disabled-service')?.classList.contains('gw-card--invalid')).toBe(false);
  });

  it('distinguishes actual connected MCP services from installed services and opens an exact access review', async () => {
    savedConnections.push(healthyMcp('issue-tracker-mcp', ['dev']));
    const { fixture, el } = await render();
    expect(text(service(el, 'issue-tracker-mcp')?.querySelector('.gw-status__label'))).toBe('Connected');
    expect(text(el.querySelector('#ov-mcp .gw-card__description'))).toContain('dev');
    const mcp = el.querySelector('#ov-mcp')!;
    expect(text(mcp)).toContain('github-mcp');
    expect(text(mcp)).toContain('Not connected to dev');
    expect(text(mcp)).not.toContain('docs-mcp');
    expect(mcp.querySelector('a[target="_blank"]')).toBeNull();
    mcp.querySelector<HTMLButtonElement>('button[aria-label="Connect github-mcp to endpoint dev"]')!.click();
    await settle(fixture);
    expect(text(openReview(el))).toContain('Connect github-mcp to dev');
    expect(text(openReview(el))).toContain('List issues');
    expect(calls).toContain('preview dev github-mcp');
    expect(calls.some((call) => call.startsWith('add '))).toBe(false);
  });

  it('links Add an MCP service directly to MCP setup', async () => {
    const { el } = await render();
    expect(href(byText(el.querySelector('#ov-mcp')!, 'a', 'Add an MCP service'))).toBe('/connectors?add=mcp-server#mcp-servers');
  });

  it('opens the service chooser with new-service routes and existing services scoped to the endpoint', async () => {
    const { fixture, el } = await render();
    findButton(el.querySelector('.gw-page-header__actions')!, 'Add service').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[open]')!;
    expect(text(dialog.querySelector('h2'))).toBe('Add a service');
    expect(href(byText(dialog, 'a', 'Connect an MCP service by URL'))).toBe('/connectors?add=mcp-server&source=url#mcp-servers');
    expect(href(byText(dialog, 'a', 'Connect a database or API'))).toBe('/connections/new');
    expect(href(byText(dialog, 'a', 'Connect existing services'))).toBe('/connections?notConnectedTo=dev');
    expect(text(dialog)).not.toContain('Already in GateRook');
    expect(text(dialog)).not.toContain('Browse services');
    expect(calls.some((call) => call.startsWith('add '))).toBe(false);
  });

  it('waits for the global switch write, restores filtered rows and carries the saved setting across endpoints', async () => {
    const write = deferred<ConnectionRow>();
    connectionWrite = () => write.promise;
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('button[aria-label="Show 1 unreachable connections on dev"]')!.click();
    await settle(fixture);
    const control = switchOf(service(el, 'billing-api')!);
    expect(control.checked).toBe(true);
    expect(control.getAttribute('aria-label')).toContain('across all endpoints');
    control.click();
    fixture.detectChanges();
    expect(control.checked).toBe(true);
    expect(control.disabled).toBe(true);
    expect(calls.filter((call) => call.startsWith('connection '))).toEqual(['connection billing-api false']);
    savedConnections = savedConnections.map((connection) => connection.name === 'billing-api' ? { ...connection, enabled: false } : connection);
    write.resolve(savedConnections.find((connection) => connection.name === 'billing-api')!);
    await settle(fixture);
    expect(switchOf(service(el, 'billing-api')!).checked).toBe(false);
    expect(text(service(el, 'billing-api'))).toContain('Connection off');
    expect(services(el)).toContain('dev-db');
    expect(el.querySelector('button[aria-label="Show 1 unreachable connections on dev"]')).toBeNull();
    await selectEndpoint(fixture, el, 'stage-readonly');
    expect(switchOf(service(el, 'billing-api')!).checked).toBe(false);
    expect(text(service(el, 'billing-api'))).toContain('Connection off');
  });

  it('keeps the saved switch value and explains a failed access change', async () => {
    connectionWrite = async () => { throw new ApiError('conflict', 409, { detail: 'This connection changed. Refresh and try again.' }); };
    const { fixture, el } = await render();
    const control = switchOf(service(el, 'billing-api')!);
    control.click();
    await settle(fixture);
    expect(control.checked).toBe(true);
    expect(control.disabled).toBe(false);
    expect(text(service(el, 'billing-api'))).toContain('Unreachable');
    expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({ tone: 'danger', title: 'Could not disable billing-api', body: 'This connection changed. Refresh and try again.' });
  });

  it('pauses only the endpoint while retaining connection settings and access through other endpoints', async () => {
    const { fixture, el } = await render();
    switchOf(el.querySelector('#ov-detail')!).click();
    await settle(fixture);
    const billing = service(el, 'billing-api')!;
    const control = switchOf(billing);
    expect(text(billing)).toContain('Endpoint off');
    expect(control.checked).toBe(false);
    expect(control.getAttribute('aria-disabled')).toBe('true');
    expect(text(document.getElementById(control.getAttribute('aria-describedby')!))).toContain('saved connection setting is On');
    expect(text(app(el, 'codex'))).toContain('Endpoint off');
    expect(text(el.querySelector('#ov-detail'))).toContain('All tools are off on this endpoint');
    expect(calls).toContain('endpoint dev false');
    expect(calls.some((call) => call.startsWith('connection '))).toBe(false);
    await selectEndpoint(fixture, el, 'stage-readonly');
    expect(switchOf(service(el, 'billing-api')!).checked).toBe(true);
    expect(text(service(el, 'billing-api'))).toContain('Unreachable');
    await selectEndpoint(fixture, el, 'dev');
    switchOf(el.querySelector('#ov-detail')!).click();
    await settle(fixture);
    expect(switchOf(service(el, 'billing-api')!).checked).toBe(true);
    expect(text(service(el, 'billing-api'))).toContain('Unreachable');
  });

  it('clears the problem-only view after an external pause so service switches remain visible', async () => {
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('button[aria-label="Show 1 unreachable connections on dev"]')!.click();
    await settle(fixture);
    savedEndpoints = savedEndpoints.map((endpoint) => endpoint.slug === 'dev' ? { ...endpoint, status: 'disabled' } : endpoint);
    events.emit('approval.requested', { id: 'refresh' });
    await sleep(180);
    await settle(fixture);
    expect(el.querySelector('[aria-pressed="true"][aria-controls="ov-services ov-mcp"]')).toBeNull();
    expect(services(el)).toContain('billing-api');
    expect(services(el)).toContain('app-logs');
    expect(text(service(el, 'billing-api'))).toContain('Endpoint off');
  });

  it('tests the selected unreachable connection once, reports its result and keeps the endpoint scope', async () => {
    const { fixture, el } = await render();
    service(el, 'billing-api')!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(fixture);
    const test = findButton(el.querySelector('#ov-detail')!, 'Test connection again');
    test.click();
    fixture.detectChanges();
    test.click();
    expect(tests.map((request) => request.name)).toEqual(['billing-api']);
    expect(test.getAttribute('aria-busy')).toBe('true');
    tests[0].answer.resolve(unreachable);
    await settle(fixture);
    expect(test.hasAttribute('aria-busy')).toBe(false);
    expect(text(service(el, 'billing-api'))).toContain('Unreachable');
    expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({ tone: 'danger', title: 'billing-api is still unreachable', body: unreachable.error!.message });
    expect((el.querySelector('input[name="overview-endpoint"]:checked') as HTMLInputElement).value).toBe('dev');
  });

  it('drops an obsolete problem filter when its endpoint is removed externally', async () => {
    const { fixture, el } = await render();
    el.querySelector<HTMLButtonElement>('button[aria-label="Show 1 unreachable connections on dev"]')!.click();
    await settle(fixture);
    savedEndpoints = savedEndpoints.filter((endpoint) => endpoint.slug !== 'dev');
    events.emit('approval.requested', { id: 'refresh' });
    await sleep(180);
    await settle(fixture);
    expect((el.querySelector('input[name="overview-endpoint"]:checked') as HTMLInputElement).value).toBe('stage-readonly');
    expect(services(el)).toEqual(['aws-stage', 'billing-api', 'stage-db']);
    expect(el.querySelector('[aria-pressed="true"][aria-controls="ov-services ov-mcp"]')).toBeNull();
  });

  it('offers request review and shows a clearly labelled path without sending a service request', async () => {
    const { fixture, el } = await render();
    expect(text(el.querySelector('#ov-approvals'))).toContain('3 requests need your approval');
    expect(href(byText(el.querySelector('#ov-approvals')!, 'a', 'Review requests'))).toBe('/approvals');
    const before = [...calls];
    service(el, 'billing-api')!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(fixture);
    findButton(el.querySelector('#ov-detail')!, 'Show path').click();
    await settle(fixture);
    expect(text(el.querySelector('#ov-detail'))).toContain('Example path only; no request is sent.');
    expect(text(el.querySelector('#ov-detail'))).toContain('claude-desktop → dev → billing-api');
    expect(text(el.querySelector('#ov-detail'))).toContain('An unreachable service cannot complete the call.');
    expect(calls).toEqual(before);
    findButton(el.querySelector('#ov-detail')!, 'Clear').click();
    await settle(fixture);
    expect(text(el.querySelector('#ov-detail'))).not.toContain('Example path only');
    expect(href(byText(el, 'a', 'Activity'))).toBe('/activity');
  });
});

describe('Overview availability', () => {
  it('waits briefly before showing loading placeholders and replaces them with the real map', async () => {
    const answer = deferred<Overview>();
    overviewAnswers = [() => answer.promise];
    const { fixture, el } = await render();
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    await sleep(SKELETON_DELAY_MS + 20);
    fixture.detectChanges();
    expect(el.querySelector('[aria-label="Loading connection map"]')).not.toBeNull();
    expect(el.querySelector('.gw-skeleton')).not.toBeNull();
    answer.resolve(overview());
    await settle(fixture);
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(service(el, 'billing-api')).not.toBeNull();
  });

  it('explains a failed first load and lets the user retry', async () => {
    overviewAnswers = [() => Promise.reject(new ApiError('network', 0, { detail: 'The local gateway did not answer.' })), () => Promise.resolve(overview())];
    const { fixture, el } = await render();
    expect(text(el.querySelector('[role="alert"]'))).toContain('The local gateway did not answer.');
    expect(el.querySelector('#ov-network')).toBeNull();
    findButton(el, 'Try again').click();
    await settle(fixture);
    expect(service(el, 'billing-api')).not.toBeNull();
    expect(text(el)).not.toContain("The overview didn't load");
  });

  it('keeps the last map after a failed refresh but blocks access mutations until it reloads', async () => {
    const { fixture, el } = await render();
    overviewAnswers = [() => Promise.reject(new Error('Gateway unavailable'))];
    events.emit('approval.requested', { id: 'refresh' });
    await sleep(180);
    await settle(fixture);
    expect(text(el)).toContain('This overview may be out of date.');
    expect(service(el, 'billing-api')).not.toBeNull();
    const control = switchOf(service(el, 'billing-api')!);
    expect(control.getAttribute('aria-disabled')).toBe('true');
    control.click();
    expect(calls.some((call) => call.startsWith('connection '))).toBe(false);
    expect(text(el)).toContain('Status unavailable');
    overviewAnswers = [() => Promise.resolve(overview())];
    findButton(el, 'Try again').click();
    await settle(fixture);
    expect(control.hasAttribute('aria-disabled')).toBe(false);
    expect(text(el)).not.toContain('This overview may be out of date.');
  });

  it.each(['connections', 'endpoints', 'clients'])('shows an unavailable %s area as unknown instead of invented access', async (area) => {
    if (area === 'connections') connectionsAnswer = () => Promise.reject(new Error('No connection data'));
    if (area === 'endpoints') endpointsAnswer = () => Promise.reject(new Error('No endpoint data'));
    if (area === 'clients') clientsAnswer = () => Promise.reject(new Error('No client data'));
    const { el } = await render();
    if (area === 'connections') {
      expect(text(el)).toContain('Connections could not be loaded.');
      expect(text(el)).toContain('Connection data unavailable');
      expect((el.querySelector('#ov-service-search') as HTMLInputElement).disabled).toBe(true);
      expect(services(el)).toEqual([]);
      expect(el.querySelector('button[aria-label^="Show 1 unreachable"]')).toBeNull();
    } else if (area === 'endpoints') {
      expect(text(el)).toContain('Endpoints could not be loaded.');
      expect(el.querySelector('input[name="overview-endpoint"]')).toBeNull();
      expect((el.querySelector('#ov-service-search') as HTMLInputElement).disabled).toBe(true);
      expect(app(el, 'codex').querySelector('a')?.getAttribute('aria-disabled')).toBe('true');
      expect(text(el.querySelector('#ov-mcp'))).not.toContain('Not connected to an endpoint');
      expect(el.querySelector('button[aria-label^="Connect github-mcp"]')).toBeNull();
    } else {
      expect(text(el)).toContain('AI app status is unavailable.');
      expect(el.querySelector('[data-map-app]')).toBeNull();
      expect(text(el)).toContain('Unknown apps');
      expect(text(el)).not.toContain('0 apps');
    }
  });

  it('supports first run without inventing endpoints and enables explicit setup once an empty endpoint exists', async () => {
    overviewAnswers = [() => Promise.resolve(firstRun())];
    savedConnections = [];
    savedEndpoints = [];
    clientsAnswer = async () => clientsFound();
    const { fixture, el } = await render();
    expect(text(el)).toContain('Connect your first service');
    expect(services(el)).toEqual([]);
    expect(el.querySelector('input[name="overview-endpoint"]')).toBeNull();
    expect(app(el, 'codex').querySelector('a')?.getAttribute('aria-disabled')).toBe('true');
    findButton(el.querySelector('.gw-page-header__actions')!, 'Add service').click();
    await settle(fixture);
    const picker = el.querySelector<HTMLDialogElement>('dialog[open]')!;
    expect(href(byText(picker, 'a', 'Connect a database or API'))).toBe('/connections/new');
    findButton(picker, 'Cancel').click();
    savedEndpoints = [endpoint('empty')];
    clientsAnswer = async () => ({ ...clientsFound(), clients: clientsFound().clients.map((card) => ({ ...card, methods: [{ id: 'token', label: 'Token', prodAllowed: true, reason: null }] })) });
    events.emit('approval.requested', { id: 'refresh' });
    await sleep(180);
    await settle(fixture);
    const connect = app(el, 'codex').querySelector('a')!;
    expect(href(connect)).toBe('/clients?endpoint=empty&connect=codex#cl-cx-t');
    expect(connect.hasAttribute('aria-disabled')).toBe(false);
    expect(text(el)).toContain('No services connected to this endpoint');
  });
});
