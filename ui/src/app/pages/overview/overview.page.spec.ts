import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ApprovalDetail, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { ConnectionRow, ConnectionsApi, TestOutcome } from '../../core/api/connections.api';
import { ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { Overview, OverviewApi } from '../../core/api/overview.api';
import { McpServersApi, PluginsApi } from '../../core/api/plugins.api';
import { SecretsApi } from '../../core/api/secrets.api';
import { TemplatesApi } from '../../core/api/templates.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { NOW, ROWS, busySummary, card, clientsFound, clientsInUse, devDb, firstRun, overview, row } from './overview.fixtures';
import { ENDPOINT_STORAGE_KEY, OverviewPage, SKELETON_DELAY_MS } from './overview.page';
import { catalogIndex, sentryReview } from '../connectors/connectors.fixtures';
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
    // The last 30 days read on their own (GwOverviewSummary); not among the calls the page's actions make.
    summary: () => Promise.resolve(busySummary()),
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
        starts: async () => [],
        create: async (request: Parameters<EndpointsApi['create']>[0]) => {
          calls.push(`create endpoint ${request.slug}`);
          const created = endpoint(request.slug);
          savedEndpoints.push(created);
          return created;
        },
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
        addConnection: async (slug: string, name: string) => { calls.push(`add ${slug} ${name}`); savedConnections = savedConnections.map((row) => row.name === name ? { ...row, usedBy: [...new Set([...row.usedBy, slug])] } : row); return {}; },
      } },
      { provide: ConnectionsApi, useValue: connectionsApi },
      { provide: ConnectorTypesApi, useValue: { catalog: async () => ({ groups: [], installed: [] }) } },
      { provide: PluginsApi, useValue: { catalog: async () => catalogIndex() } },
      { provide: SecretsApi, useValue: { list: async () => [] } },
      { provide: TemplatesApi, useValue: { list: async () => [] } },
      { provide: McpServersApi, useValue: {
        review: async (source: { config: { name: string } }) => { calls.push('review mcp'); return sentryReview({ name: source.config.name, env: [] }); },
        create: async (source: { config: { name: string } }) => {
          const name = source.config.name;
          calls.push(`create mcp ${name}`);
          savedConnections.push(healthyMcp(name, []));
          return { name, state: 'running', tools: 4 };
        },
        tools: async () => [],
      } },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0), address: signal('127.0.0.1:7465') } },
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

const selectedEndpoint = (root: ParentNode) => root.querySelector<HTMLInputElement>('input[name="overview-endpoint"]:checked')?.value ?? null;

/** Leaves Overview and opens it again: a new page instance, as after navigating back to the home page. */
async function reopen(fixture: ComponentFixture<OverviewPage>): Promise<{ fixture: ComponentFixture<OverviewPage>; el: HTMLElement }> {
  fixture.destroy();
  document.body.innerHTML = '';
  return render();
}

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

beforeEach(() => {
  localStorage.clear();
  setupPage();
});

describe('Overview last 30 days', () => {
  it('sums up the month under the connection map, above the line about the last 24 hours', async () => {
    const { el } = await render();
    const month = el.querySelector('#ov-month')!;
    expect(text(month.querySelector('#ov-month-title'))).toBe('Last 30 days');
    expect([...month.querySelectorAll('.gw-stat__label')].map(text)).toEqual(['Calls', 'Denied by policy', 'Traffic in', 'Traffic out']);
    expect(month.querySelectorAll('.gw-chart[role="img"]')).toHaveLength(2);
    const order = [el.querySelector('#ov-detail')!, month, byText(el, 'p', '1,287 calls from your AI apps')!];
    expect(order.every(Boolean)).toBe(true);
    expect(order[0].compareDocumentPosition(order[1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(order[1].compareDocumentPosition(order[2]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

describe('Overview connection map', () => {
  it.each([6, 5, 4])('filters and expands %i connections alongside assigned and unassigned MCP services', async (nativeCount) => {
    savedConnections = [
      ...Array.from({ length: nativeCount }, (_, index) => row(`database-${index}`, 'dev', ['dev'])),
      row('stage-database', 'stage', ['stage-readonly']),
      ...Array.from({ length: 5 }, (_, index) => healthyMcp(`docs-mcp-${index}`, index < 2 ? ['dev'] : [])),
    ];
    const { fixture, el } = await render();
    expect(el.querySelectorAll('#ov-services [data-map-service]')).toHaveLength(4);
    expect(el.querySelectorAll('#ov-mcp [data-map-service]')).toHaveLength(2);
    expect(el.querySelector('#ov-mcp button[aria-label="Connect docs-mcp-4 to endpoint dev"]')).not.toBeNull();

    if (nativeCount > 4) {
      await showAll(fixture, el);
      expect(el.querySelectorAll('#ov-services [data-map-service]')).toHaveLength(nativeCount);
    }
    await searchFor(fixture, el, 'docs-mcp-0');
    expect(services(el)).toEqual(['docs-mcp-0']);
    await searchFor(fixture, el, 'database-0');
    expect(services(el)).toEqual(['database-0']);
    await searchFor(fixture, el, '');
    await selectEndpoint(fixture, el, 'stage-readonly');
    expect(services(el)).toEqual(['stage-database']);
  });

  it('selects an AI app without changing access and clears selection when its endpoint changes', async () => {
    const { fixture, el } = await render();
    const before = [...calls];
    const codex = app(el, 'codex');
    const select = codex.querySelector<HTMLButtonElement>('button')!;
    select.click();
    await settle(fixture);
    expect(codex.getAttribute('data-selected')).toBe('true');
    expect(select.getAttribute('aria-pressed')).toBe('true');
    expect(text(el.querySelector('#ov-detail-title'))).toBe('Codex');
    expect(app(el, 'claude-desktop').getAttribute('data-selected')).toBe('false');
    expect(calls).toEqual(before);
    expect(tests).toEqual([]);

    await selectEndpoint(fixture, el, 'incident-prod');
    expect(codex.getAttribute('data-selected')).toBe('false');
    expect(select.getAttribute('aria-pressed')).toBe('false');
    expect(text(codex)).toContain('Not connected');
    expect(text(el.querySelector('#ov-detail-title'))).toBe('Access through incident-prod');
  });

  it('opens connection settings directly without selecting the card or changing access, including unfinished connections', async () => {
    savedConnections = savedConnections.map((connection) => connection.name === 'dev-db'
      ? { ...connection, enabled: false, setupRequired: true } : connection);
    TestBed.inject(Router).resetConfig([{ path: 'connections/:name', children: [] }]);
    const { fixture, el } = await render();
    await showAll(fixture, el);
    const card = service(el, 'dev-db')!;
    const settings = card.querySelector<HTMLAnchorElement>('a[aria-label="Open settings for dev-db"]')!;
    expect(href(settings)).toBe('/connections/dev-db');
    expect(settings.closest('button')).toBeNull();
    expect(settings.getAttribute('aria-disabled')).toBeNull();
    settings.focus();
    expect(document.activeElement).toBe(settings);
    settings.click();
    await settle(fixture);
    expect(TestBed.inject(Router).url).toBe('/connections/dev-db');
    expect(card.getAttribute('data-selected')).toBe('false');
    expect(calls.some((call) => call.startsWith('connection ') || call.startsWith('endpoint '))).toBe(false);
  });

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
    expect(localStorage.getItem(ENDPOINT_STORAGE_KEY)).toBeNull();
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

  it('scopes services and detected AI apps to the selected endpoint and refreshes app detection', async () => {
    const installedApps = await clientsAnswer();
    clientsAnswer = async () => ({ ...installedApps, clients: [...installedApps.clients, {
      ...card('vscode', 'VS Code', 'detected'),
      methods: [{ id: 'oauth', label: 'Browser sign-in', prodAllowed: true, reason: null }],
    }] });
    const { fixture, el } = await render();
    await showAll(fixture, el);
    expect(services(el)).toContain('dev-db');
    expect(services(el)).not.toContain('prod-replica');
    expect(text(service(el, 'dev-db')!.querySelector('.gw-env--dev'))).toBe('dev');
    expect(text(service(el, 'aws-stage')!.querySelector('.gw-env--stage'))).toBe('stage');
    expect(text(app(el, 'codex'))).toContain('Connected');
    expect(app(el, 'codex').querySelector('a')).toBeNull();

    await selectEndpoint(fixture, el, 'incident-prod');
    expect(services(el)).toEqual(['prod-logs', 'prod-replica', 'prod-vm']);
    expect(text(service(el, 'prod-replica')!.querySelector('.gw-env--prod'))).toBe('prod');
    expect(text(app(el, 'codex'))).toContain('Not connected');
    const connect = app(el, 'codex').querySelector('a')!;
    expect(href(connect)).toBe('/clients?endpoint=incident-prod&connect=codex#cl-cx-t');
    expect(connect.getAttribute('aria-label')).toBe('Connect Codex');
    expect(connect.hasAttribute('aria-disabled')).toBe(false);
    expect(text(app(el, 'claude-desktop'))).toContain('Connected');
    expect(app(el, 'antigravity')).toBeNull();
    expect(href(app(el, 'vscode').querySelector('a'))).toBe('/clients?endpoint=incident-prod&connect=vscode#cl-vs-t');
    expect(app(el, 'vscode').querySelector('a')!.hasAttribute('aria-disabled')).toBe(false);
    expect(findButton(el, 'Add endpoint').getAttribute('aria-haspopup')).toBe('dialog');

    clientsAnswer = async () => ({ ...installedApps, clients: [...installedApps.clients, card('vscode', 'VS Code', 'not-installed')]
      .map((client) => ({ ...client, detected: false })) });
    events.emit('clients.drift', { client: 'vscode' });
    await sleep(1_050);
    await settle(fixture);
    expect(el.querySelector('[data-map-app]')).toBeNull();
    expect(text(el)).toContain('No AI apps found on this computer.');
  });

  it('creates an endpoint in place on Overview and returns focus after cancelling', async () => {
    const { fixture, el } = await render();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');
    const url = router.url;
    const opener = findButton(el, 'Add endpoint');
    opener.focus();
    opener.click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#ep-create')!;
    const slug = dialog.querySelector<HTMLInputElement>('#ep-create-slug')!;
    expect(dialog.open).toBe(true);
    expect(document.activeElement).toBe(slug);
    expect(text(el.querySelector('h1'))).toBe('Overview');
    expect(router.url).toBe(url);
    findButton(dialog, 'Cancel').click();
    await settle(fixture);
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(opener);

    opener.click();
    await settle(fixture);
    slug.value = 'new-dev';
    slug.dispatchEvent(new Event('input', { bubbles: true }));
    await settle(fixture);
    findButton(dialog, 'Create endpoint').click();
    await settle(fixture);
    expect(calls.filter((call) => call.startsWith('create endpoint'))).toEqual(['create endpoint new-dev']);
    expect(dialog.open).toBe(false);
    expect(text(el.querySelector('#ov-services-scope'))).toBe('0 services on new-dev');
    expect(el.querySelector<HTMLInputElement>('[data-map-endpoint="new-dev"] input')?.checked).toBe(true);
    expect(localStorage.getItem(ENDPOINT_STORAGE_KEY)).toBe('new-dev');
    expect(text(el.querySelector('h1'))).toBe('Overview');
    expect(router.url).toBe(url);
    expect(navigate).not.toHaveBeenCalled();
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
    expect(text(service(el, longName)?.querySelector('button'))).toContain(longName);
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
    expect(byText(el.querySelector('#ov-services')!, 'a', 'Import with AI')).toBeNull();
    await selectEndpoint(fixture, el, 'incident-prod');
    expect(services(el)).toEqual(['prod-replica']);
    await searchFor(fixture, el, 'does-not-exist');
    findButton(el.querySelector('#ov-services')!, 'Clear search').click();
    await settle(fixture);
    expect(services(el)).toEqual(['prod-logs', 'prod-replica', 'prod-vm']);
  });

  it('offers repository import for an empty selected endpoint and keeps its context', async () => {
    savedConnections = savedConnections.map((connection) => ({ ...connection,
      usedBy: connection.usedBy.filter((slug) => slug !== 'incident-prod') }));
    TestBed.inject(Router).resetConfig([{ path: 'connection-import', children: [] }]);
    const { fixture, el } = await render();
    expect(byText(el.querySelector('#ov-services')!, 'a', 'Import with AI')).toBeNull();
    await selectEndpoint(fixture, el, 'incident-prod');
    const empty = el.querySelector('#ov-services')!;
    expect(text(empty)).toContain('No connectors on this endpoint');
    expect(text(empty)).toContain('analyze a repository and prepare connections for review');
    const importLink = byText<HTMLAnchorElement>(empty, 'a', 'Import with AI')!;
    expect(href(importLink)).toBe('/connection-import?endpoint=incident-prod');
    const before = [...calls];
    importLink.click();
    await settle(fixture);
    expect(TestBed.inject(Router).url).toBe('/connection-import?endpoint=incident-prod');
    expect(calls).toEqual(before);
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
    savedConnections.push(healthyMcp('docs-mcp', ['stage-readonly']));
    const { fixture, el } = await render();
    expect(text(service(el, 'issue-tracker-mcp')?.querySelector('.gw-status__label'))).toBe('Connected');
    expect(text(el.querySelector('#ov-mcp .gw-card__description'))).toContain('dev');
    const mcp = el.querySelector('#ov-mcp')!;
    expect(text(mcp)).toContain('github-mcp');
    expect(text(mcp)).toContain('Not connected to dev');
    expect(text(mcp)).not.toContain('docs-mcp');
    expect(mcp.querySelector('a[target="_blank"]')).toBeNull();
    await searchFor(fixture, el, 'docs-mcp');
    expect(text(mcp)).not.toContain('docs-mcp');
    await searchFor(fixture, el, '');
    await selectEndpoint(fixture, el, 'stage-readonly');
    expect(text(service(el, 'docs-mcp')?.querySelector('.gw-status__label'))).toBe('Connected');
    expect(text(mcp)).not.toContain('issue-tracker-mcp');
    expect(text(mcp)).toContain('github-mcp');
    expect(text(mcp)).toContain('Not connected to stage-readonly');
    await selectEndpoint(fixture, el, 'dev');
    expect(text(mcp)).not.toContain('docs-mcp');
    mcp.querySelector<HTMLButtonElement>('button[aria-label="Connect github-mcp to endpoint dev"]')!.click();
    await settle(fixture);
    expect(text(openReview(el))).toContain('Connect github-mcp to dev');
    expect(text(openReview(el))).toContain('List issues');
    expect(calls).toContain('preview dev github-mcp');
    expect(calls.some((call) => call.startsWith('add '))).toBe(false);
  });

  it('opens and cancels MCP setup on Overview while preserving endpoint, search and focus', async () => {
    const { fixture, el } = await render();
    await selectEndpoint(fixture, el, 'stage-readonly');
    await searchFor(fixture, el, 'mcp');
    const opener = findButton(el.querySelector('#ov-mcp')!, 'Add an MCP service');
    const beforeUrl = TestBed.inject(Router).url;
    opener.click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    expect(dialog.open).toBe(true);
    expect(el.querySelector('gw-mcp-setup-flow')).not.toBeNull();
    findButton(dialog, 'Cancel').click();
    await settle(fixture);
    expect(dialog.open).toBe(false);
    expect(TestBed.inject(Router).url).toBe(beforeUrl);
    expect(el.querySelector<HTMLInputElement>('input[name="overview-endpoint"]:checked')!.value).toBe('stage-readonly');
    expect(el.querySelector<HTMLInputElement>('#ov-service-search')!.value).toBe('mcp');
    expect(document.activeElement).toBe(opener);
    expect(calls.some((call) => call.startsWith('create mcp'))).toBe(false);
  });

  it('opens the URL form locally and closes it back to the original Add service button', async () => {
    const { fixture, el } = await render();
    const opener = findButton(el.querySelector('.gw-page-header__actions')!, 'Add service');
    opener.click();
    await settle(fixture);
    findButton(el.querySelector('dialog[open]')!, 'Connect an MCP service by URL').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    expect(dialog.open).toBe(true);
    expect(dialog.querySelector<HTMLInputElement>('input[value="http"]')!.checked).toBe(true);
    expect(el.querySelectorAll('dialog[open]')).toHaveLength(1);
    findButton(dialog, 'Cancel').click();
    await settle(fixture);
    expect(document.activeElement).toBe(opener);
    expect(TestBed.inject(Router).url).toBe('/');
    expect(calls).not.toContain('review mcp');
  });

  it.each(['add', 'skip'])('keeps the complete reviewed MCP flow on Overview and refreshes the map: %s', async (finish) => {
    const { fixture, el } = await render();
    await selectEndpoint(fixture, el, 'stage-readonly');
    const opener = findButton(el.querySelector('#ov-mcp')!, 'Add an MCP service');
    opener.click();
    await settle(fixture);
    const add = el.querySelector<HTMLDialogElement>('dialog[gwMcpAddDialog]')!;
    const name = add.querySelector<HTMLInputElement>('input[placeholder="sentry-mcp"]')!;
    const command = add.querySelector<HTMLInputElement>('input[placeholder^="npx"]')!;
    for (const [input, value] of [[name, 'sample-mcp'], [command, 'npx -y @example/mcp@1.0.0']] as const) {
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    findButton(add, 'Review and start').click();
    await settle(fixture);
    const review = el.querySelector<HTMLDialogElement>('dialog[gwMcpReviewDialog]')!;
    expect(review.open).toBe(true);
    expect(calls).not.toContain('create mcp sample-mcp');
    findButton(review, 'Back').click();
    await settle(fixture);
    expect(add.open).toBe(true);
    expect(name.value).toBe('sample-mcp');
    expect(command.value).toBe('npx -y @example/mcp@1.0.0');
    findButton(add, 'Review and start').click();
    await settle(fixture);
    review.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await settle(fixture);
    findButton(review, 'Start MCP server sample-mcp').click();
    await settle(fixture);
    expect(calls).toContain('create mcp sample-mcp');
    const step = el.querySelector<HTMLDialogElement>('dialog[gwMcpEndpointDialog]')!;
    expect(step.open).toBe(true);
    const reads = calls.filter((call) => call === 'GET /api/connections').length;
    findButton(step, finish === 'add' ? 'Add to endpoint' : 'Not now').click();
    await settle(fixture);
    expect(step.open).toBe(false);
    expect(calls.filter((call) => call === 'GET /api/connections').length).toBeGreaterThan(reads);
    expect(TestBed.inject(Router).url).toBe('/');
    expect(el.querySelector<HTMLInputElement>('input[name="overview-endpoint"]:checked')!.value).toBe('stage-readonly');
    expect(document.activeElement).toBe(opener);
    expect(text(el.querySelector('#ov-mcp')).includes('sample-mcp')).toBe(finish === 'skip');
    expect(calls.includes('add dev sample-mcp')).toBe(finish === 'add');
    await selectEndpoint(fixture, el, 'dev');
    expect(text(el.querySelector('#ov-mcp'))).toContain('sample-mcp');
  });

  it('opens the service chooser with new-service routes and existing services scoped to the endpoint', async () => {
    const { fixture, el } = await render();
    findButton(el.querySelector('.gw-page-header__actions')!, 'Add service').click();
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('dialog[open]')!;
    expect(text(dialog.querySelector('h2'))).toBe('Add a service');
    expect(findButton(dialog, 'Connect an MCP service by URL')).toBe(document.activeElement);
    expect(href(byText(dialog, 'a', 'Built-in connectors'))).toBe('/connections/new');
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
    expect(control.checked).toBe(true);
    expect(control.getAttribute('aria-disabled')).not.toBe('true');
    expect(text(billing.querySelector('.gw-setup-map__network-toggle > span'))).toBe('On');
    expect(text(document.getElementById(control.getAttribute('aria-describedby')!))).toContain('Changing this connection does not turn the endpoint on');
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

  it('shows the selected service path automatically and can replay, clear and restore it without sending a request', async () => {
    const { fixture, el } = await render();
    expect(text(el.querySelector('#ov-approvals'))).toContain('3 requests need your approval');
    expect(href(byText(el.querySelector('#ov-approvals')!, 'a', 'Review requests'))).toBe('/approvals');
    const before = [...calls];
    service(el, 'billing-api')!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(fixture);
    const showPath = findButton(el.querySelector('#ov-detail')!, 'Replay path');
    expect(showPath.getAttribute('aria-controls')).toBe('ov-request-path');
    expect(showPath.getAttribute('aria-expanded')).toBe('true');
    const route = el.querySelector('ol[aria-label="Request route"]')!;
    expect(route.children).toHaveLength(3);
    expect(text(route.children[0])).toContain('Claude Desktop');
    expect(text(route.children[1])).toContain('GateRook');
    expect(text(route.children[1])).toContain('Endpoint dev');
    expect(text(route.children[2])).toContain('billing-api');
    expect(text(el.querySelector('#ov-detail'))).toContain('Example path only; no request is sent.');
    expect(text(el.querySelector('#ov-detail'))).toContain('An unreachable service cannot complete the call.');
    showPath.click();
    await settle(fixture);
    expect(el.querySelector('ol[aria-label="Request route"]')).not.toBe(route);
    expect(calls).toEqual(before);
    expect(tests).toEqual([]);
    findButton(el.querySelector('#ov-detail')!, 'Clear').click();
    await settle(fixture);
    expect(text(el.querySelector('#ov-detail'))).not.toContain('Example path only');
    expect(showPath.getAttribute('aria-expanded')).toBe('false');
    expect(text(showPath)).toBe('Show path');
    expect(document.activeElement).toBe(showPath);
    showPath.click();
    await settle(fixture);
    expect(text(el.querySelector('ol[aria-label="Request route"]'))).toContain('billing-api');
    service(el, 'cache-redis')!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(fixture);
    expect(text(el.querySelector('ol[aria-label="Request route"]'))).toContain('cache-redis');
    expect(text(el.querySelector('ol[aria-label="Request route"]'))).not.toContain('billing-api');
    expect(calls).toEqual(before);
    expect(tests).toEqual([]);
    expect(href(byText(el, 'a', 'Activity'))).toBe('/activity');
  });

  it('keeps request paths hidden for disabled services and disconnected apps', async () => {
    savedConnections = savedConnections.map((connection) => connection.name === 'cache-redis'
      ? { ...connection, enabled: false } : connection);
    const { fixture, el } = await render();
    service(el, 'billing-api')!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(fixture);
    expect(el.querySelector('ol[aria-label="Request route"]')).not.toBeNull();
    service(el, 'cache-redis')!.querySelector<HTMLButtonElement>('button')!.click();
    await settle(fixture);
    expect(el.querySelector('ol[aria-label="Request route"]')).toBeNull();
    expect(findButton(el.querySelector('#ov-detail')!, 'Show path').getAttribute('aria-disabled')).toBe('true');
    expect(text(el.querySelector('#ov-trace-reason'))).toContain('Enable this connection');
    app(el, 'cursor').querySelector<HTMLButtonElement>('button')!.click();
    await settle(fixture);
    expect(el.querySelector('ol[aria-label="Request route"]')).toBeNull();
    expect(text(el.querySelector('#ov-trace-reason'))).toContain('This app is not connected');
  });
});

describe('Overview remembered endpoint', () => {
  it('opens the endpoint chosen last, including one chosen from its problem badge', async () => {
    let { fixture, el } = await render();
    expect(selectedEndpoint(el)).toBe('dev');
    expect(localStorage.getItem(ENDPOINT_STORAGE_KEY)).toBeNull();
    await selectEndpoint(fixture, el, 'incident-prod');
    expect(localStorage.getItem(ENDPOINT_STORAGE_KEY)).toBe('incident-prod');

    ({ fixture, el } = await reopen(fixture));
    expect(selectedEndpoint(el)).toBe('incident-prod');
    expect(text(el.querySelector('#ov-services-scope'))).toMatch(/ services on incident-prod$/);
    expect(text(el.querySelector('#ov-detail-title'))).toBe('Access through incident-prod');

    el.querySelector<HTMLButtonElement>('button[aria-label="Show 1 unreachable connections on dev"]')!.click();
    await settle(fixture);
    expect(localStorage.getItem(ENDPOINT_STORAGE_KEY)).toBe('dev');
    ({ el } = await reopen(fixture));
    expect(selectedEndpoint(el)).toBe('dev');
    expect(el.querySelector('[aria-pressed="true"][aria-controls="ov-services ov-mcp"]')).toBeNull();
  });

  it('lets an endpoint link take precedence without replacing the remembered endpoint', async () => {
    localStorage.setItem(ENDPOINT_STORAGE_KEY, 'incident-prod');
    await TestBed.inject(Router).navigateByUrl('/?endpoint=stage-readonly');
    const { el } = await render();
    expect(selectedEndpoint(el)).toBe('stage-readonly');
    expect(localStorage.getItem(ENDPOINT_STORAGE_KEY)).toBe('incident-prod');
  });

  it('falls back to the first endpoint while the remembered one is missing and keeps it for later', async () => {
    localStorage.setItem(ENDPOINT_STORAGE_KEY, 'incident-prod');
    const removed = savedEndpoints;
    savedEndpoints = removed.filter((endpoint) => endpoint.slug !== 'incident-prod');
    let { fixture, el } = await render();
    expect(selectedEndpoint(el)).toBe('dev');
    expect(text(el.querySelector('#ov-detail-title'))).toBe('Access through dev');
    expect(localStorage.getItem(ENDPOINT_STORAGE_KEY)).toBe('incident-prod');

    savedEndpoints = removed;
    ({ fixture, el } = await reopen(fixture));
    expect(selectedEndpoint(el)).toBe('incident-prod');

    savedEndpoints = removed.filter((endpoint) => endpoint.slug !== 'incident-prod');
    events.emit('approval.requested', { id: 'refresh' });
    await sleep(180);
    await settle(fixture);
    expect(selectedEndpoint(el)).toBe('dev');
    expect(localStorage.getItem(ENDPOINT_STORAGE_KEY)).toBe('incident-prod');
  });

  it('still opens and switches endpoints when storage is blocked', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('Blocked', 'SecurityError'); });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Blocked', 'SecurityError'); });
    try {
      const { fixture, el } = await render();
      expect(selectedEndpoint(el)).toBe('dev');
      await selectEndpoint(fixture, el, 'stage-readonly');
      expect(selectedEndpoint(el)).toBe('stage-readonly');
      expect(setItem).toHaveBeenCalledWith(ENDPOINT_STORAGE_KEY, 'stage-readonly');
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
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
    expect(href(byText(el.querySelector('#ov-services')!, 'a', 'Import with AI'))).toBe('/connection-import');
    expect(text(el.querySelector('#ov-services'))).toContain('analyze a repository and prepare connections for review');
    expect(el.querySelector('input[name="overview-endpoint"]')).toBeNull();
    expect(app(el, 'codex').querySelector('a')?.getAttribute('aria-disabled')).toBe('true');
    findButton(el.querySelector('.gw-page-header__actions')!, 'Add service').click();
    await settle(fixture);
    const picker = el.querySelector<HTMLDialogElement>('dialog[open]')!;
    expect(href(byText(picker, 'a', 'Built-in connectors'))).toBe('/connections/new');
    findButton(picker, 'Cancel').click();
    savedEndpoints = [endpoint('empty')];
    clientsAnswer = async () => ({ ...clientsFound(), clients: clientsFound().clients.map((card) => ({ ...card, methods: [{ id: 'token', label: 'Token', prodAllowed: true, reason: null }] })) });
    events.emit('approval.requested', { id: 'refresh' });
    await sleep(180);
    await settle(fixture);
    const connect = app(el, 'codex').querySelector('a')!;
    expect(href(connect)).toBe('/clients?endpoint=empty&connect=codex#cl-cx-t');
    expect(connect.hasAttribute('aria-disabled')).toBe(false);
    expect(text(el)).toContain('No connectors on this endpoint');
    expect(href(byText(el.querySelector('#ov-services')!, 'a', 'Import with AI'))).toBe('/connection-import?endpoint=empty');
  });
});
