import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ApprovalDetail, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { ConnectionRow, ConnectionsApi, TestOutcome } from '../../core/api/connections.api';
import { EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { Overview, OverviewApi } from '../../core/api/overview.api';
import { TunnelState } from '../../core/api/tunnel.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { NOW, ROWS, browserSignedIn, clientsBrowserSignedIn, clientsFound, clientsInUse, devDb, firstRun, overview, setup, tunnelOff } from './overview.fixtures';
import { OverviewPage, SKELETON_DELAY_MS } from './overview.page';
import { endpoint, kitEndpoints } from '../endpoints/endpoints.fixtures';

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
let tunnel: ReturnType<typeof signal<TunnelState | null>>;

function setupPage(first: () => Promise<Overview> = () => Promise.resolve(overview()), clients: () => Promise<ClientsView> = () => Promise.resolve(clientsInUse())): void {
  calls = [];
  overviewAnswers = [first];
  clientsAnswer = clients;
  endpointsAnswer = () => Promise.resolve(kitEndpoints());
  connectionsAnswer = () => Promise.resolve(ROWS.map((row) => ['aws-stage', 'billing-api'].includes(row.name) ? { ...row, usedBy: [...row.usedBy, 'stage-readonly'] } : row));
  events = new FakeEvents();
  tests = [];
  tunnel = signal<TunnelState | null>(tunnelOff());
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
    list: async () => ({ items: [...await connectionsAnswer()], facets: { health: {}, env: {}, type: {} }, total: ROWS.length }),
    get: () => Promise.resolve(devDb()),
    test: (name) => {
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
      { provide: ClientsApi, useValue: { list: () => clientsAnswer() } },
      { provide: EndpointsApi, useValue: { list: () => endpointsAnswer() } },
      { provide: ConnectionsApi, useValue: connectionsApi },
      { provide: EventStream, useValue: events },
      { provide: TunnelStore, useValue: { start: () => undefined, available: signal(true), state: tunnel } },
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
/** A tile by its title; its name, state, meta and action as a person reads them. */
const tile = (root: ParentNode, name: string) => Array.from(root.querySelectorAll<HTMLElement>('.gw-status-tile')).find((t) => text(t.querySelector('.gw-status-tile__title')).startsWith(name)) ?? null;
const href = (a: Element | null) => a?.getAttribute('href') ?? null;

describe('Overview page', () => {
  it('shows nothing for a moment, then the skeleton until the core answers', async () => {
    const answer = deferred<Overview>();
    setupPage(() => answer.promise);
    const { fixture, el } = await render();
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    await sleep(SKELETON_DELAY_MS + 20);
    fixture.detectChanges();
    expect(el.querySelectorAll('.gw-skeleton').length).toBeGreaterThan(0);
    expect(text(el.querySelector('[role=status]'))).toBe('Loading the overview');
    answer.resolve(overview());
    await settle(fixture);
    expect(el.querySelector('.gw-skeleton')).toBeNull();
  });

  describe('in use', () => {
    it('puts what waits for approval first, then the connections, the AI apps and one line about Activity', async () => {
      setupPage();
      const { el } = await render();
      expect(text(el.querySelector('.gw-page-header__description'))).toBe('What waits for your approval and what is connected.');
      // The page's one action is a plain button: nothing on this screen is a primary.
      expect(byText(el, '.gw-page-header__actions a', 'Add connection')!.classList.contains('gw-btn--primary')).toBe(false);
      expect(el.querySelector('.gw-btn--primary')).toBeNull();

      const wait = el.querySelector('#ov-wait')!;
      expect(text(wait.querySelector('h2'))).toBe('Waiting for your approval , 3 requests');
      expect(wait.querySelector('ul')!.getAttribute('aria-label')).toBe('Waiting for approval, 3 requests');
      expect(Array.from(wait.querySelectorAll('.gw-approval-request__title')).map(text)).toEqual([
        'k8s_rollout_restart on dev-cluster environment dev from Claude Desktop',
        'sql_execute on dev-db environment dev from Codex',
        'http_request on billing-api environment stage from Codex',
      ]);
      expect(href(byText(wait, 'a', 'Review'))).toBe('/approvals/ap_k8s000000001');

      const sections = Array.from(el.querySelectorAll('h2')).map(text);
      expect(sections).toEqual(['Waiting for your approval , 3 requests', 'Your connections', 'AI apps']);
      expect(text(el.querySelector(':scope > p.gw-text-small:last-child'))).toBe('1,287 calls from your AI apps in the last 24 hours, 17 denied by policy. Each one is in Activity.');
      expect(href(byText(el, 'a', 'Activity'))).toBe('/activity');
    });

    it('keeps health tiles and retry available when endpoint grouping cannot load', async () => {
      setupPage();
      endpointsAnswer = () => Promise.reject(new Error('Unavailable'));
      const { fixture, el } = await render();
      const section = el.querySelector('section[aria-labelledby="ov-conn-title"]')!;
      expect(text(section.querySelector('.gw-section__description'))).toBe(
        "12 connections: 10 healthy, 1 slow, 1 unreachable. The tag shows each one's environment: dev, stage or prod.",
      );
      const first = section.querySelector('ul[aria-label="Connections that need attention first"]')!;
      expect(Array.from(first.querySelectorAll('.gw-status-tile__title')).map(text)).toEqual([
        'billing-api environment stage',
        'cache-redis environment dev',
        'app-logs environment dev',
        'dev-cluster environment dev',
        'dev-db environment dev',
        'dev-vm environment dev',
      ]);

      const billing = tile(first, 'billing-api')!;
      expect(billing.classList).toContain('gw-card--interactive');
      expect(href(billing.querySelector('.gw-card__link'))).toBe('/connections/billing-api');
      expect(text(billing.querySelector('.gw-status'))).toBe('Unreachable');
      expect(text(billing.querySelector('.gw-status-tile__main p'))).toBe('HTTP API · billing.stage.acme.dev');
      expect(text(billing.querySelector('button'))).toBe('Test again billing-api');
      expect(text(tile(first, 'cache-redis')!.querySelector('.gw-status'))).toBe('Slow · 1.8 s');
      expect(tile(first, 'cache-redis')!.querySelector('button')).toBeNull();

      // The disclosure sits before the folded list, which opens and closes with it.
      const toggle = byText<HTMLButtonElement>(section, 'button', '6 more connections, all healthy')!;
      const more = section.querySelector<HTMLElement>('#ov-conn-more')!;
      expect(toggle.getAttribute('aria-controls')).toBe('ov-conn-more');
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(more.hidden).toBe(true);
      expect(more.getAttribute('aria-label')).toBe('More connections, all healthy');
      toggle.click();
      fixture.detectChanges();
      expect(more.hidden).toBe(false);
      expect(more.querySelectorAll('.gw-status-tile')).toHaveLength(6);
    });

    it('groups all connections by endpoint, repeats shared connections and names each endpoint\'s AI apps', async () => {
      setupPage();
      const { el } = await render();
      const section = el.querySelector('section[aria-labelledby="ov-conn-title"]')!;
      const groups = Array.from(section.querySelectorAll('section.gw-card'));
      expect(groups.map((g) => text(g.querySelector('h3')))).toEqual(['Endpoint dev', 'Endpoint stage-readonly', 'Endpoint incident-prod', 'Not on an endpoint']);
      expect(href(byText(section, 'a', 'All endpoints'))).toBe('/endpoints');
      expect(href(groups[0].querySelector('h3 a'))).toBe('/endpoints/dev');
      expect(groups.map((g) => g.querySelectorAll('.gw-status-tile').length)).toEqual([8, 3, 3, 1]);
      expect(section.querySelector('#ov-conn-more')).toBeNull();
      expect(section.querySelectorAll('.gw-status-tile h3')).toHaveLength(0);
      expect(section.querySelectorAll('.gw-status-tile h4')).toHaveLength(15);
      expect(text(groups[0].querySelector('.gw-tag-list'))).toContain('Claude Desktop · Connected');
      expect(text(groups[0].querySelector('.gw-tag-list'))).toContain('Codex · Connected');
      expect(text(groups[2].querySelector('.gw-tag-list'))).toBe('Claude Desktop · Connected');
      expect(text(groups[1])).toContain('No AI apps linked.');
      expect(text(groups[3])).toContain('github-mcp');
      // The same unhealthy connection stays visible first in both endpoint groups.
      for (const group of groups.slice(0, 2)) {
        expect(text(group.querySelector('.gw-status-tile__title'))).toBe('billing-api environment stage');
        expect(href(tile(group, 'billing-api')!.querySelector('.gw-card__link'))).toBe('/connections/billing-api');
      }
    });

    it('keeps empty and disabled endpoints visible with a useful next step', async () => {
      setupPage();
      endpointsAnswer = () => Promise.resolve([endpoint('empty', { status: 'disabled' }), endpoint('review', { blockedReason: 'Changed outside GateRook' })]);
      connectionsAnswer = () => Promise.resolve([]);
      const { el } = await render();
      const groups = el.querySelectorAll('section[aria-labelledby="ov-conn-title"] section.gw-card');
      expect(text(groups[0])).toContain('0 connections');
      expect(text(groups[0])).toContain('Disabled');
      expect(text(groups[0])).toContain('No connections on this endpoint.');
      expect(href(byText(groups[0], 'a', 'Add connections'))).toBe('/endpoints/empty');
      expect(text(groups[1])).toContain('Needs review');
    });

    it('distinguishes unavailable AI app data from an endpoint with no connected apps', async () => {
      setupPage(undefined, () => Promise.reject(new Error('Unavailable')));
      const { el } = await render();
      const section = el.querySelector('section[aria-labelledby="ov-conn-title"]')!;
      expect(text(section)).toContain("AI app connections couldn't be loaded.");
      expect(text(section)).not.toContain('No AI apps linked.');
      expect(section.querySelectorAll('.gw-status-tile')).toHaveLength(15);
    });

    it('does not label connections as unassigned when their endpoint wiring cannot load', async () => {
      setupPage();
      connectionsAnswer = () => Promise.reject(new Error('Unavailable'));
      const { fixture, el } = await render();
      const section = el.querySelector('section[aria-labelledby="ov-conn-title"]')!;
      expect(text(section)).toContain("Endpoint groups couldn't be loaded.");
      expect(text(section)).not.toContain('Not on an endpoint');
      connectionsAnswer = () => Promise.resolve(ROWS);
      byText<HTMLButtonElement>(section, 'button', 'Try again')!.click();
      await settle(fixture);
      expect(text(section)).not.toContain("Endpoint groups couldn't be loaded.");
      expect(section.querySelector('h3 a')).not.toBeNull();
    });

    it('synchronizes Test again and health updates across every copy of a shared connection', async () => {
      setupPage();
      const { fixture, el } = await render();
      const shared = () => Array.from(el.querySelectorAll('.gw-status-tile')).filter((t) => text(t.querySelector('.gw-status-tile__title')) === 'billing-api environment stage');
      expect(shared()).toHaveLength(2);
      shared()[0].querySelector('button')!.click();
      fixture.detectChanges();
      shared()[1].querySelector('button')!.click();
      fixture.detectChanges();
      expect(tests).toHaveLength(1);
      expect(shared().every((t) => t.querySelector('button')?.getAttribute('aria-busy') === 'true')).toBe(true);
      tests[0].answer.resolve(unreachable);
      await settle(fixture);
      events.emit('connection.health', { name: 'billing-api', env: 'stage', state: 'healthy', latencyMs: 40, message: null, checkedAt: new Date(NOW).toISOString() });
      fixture.detectChanges();
      expect(shared().every((t) => text(t.querySelector('.gw-status')) === 'Healthy' && !t.querySelector('button'))).toBe(true);
    });

    it('lists the AI apps: the one to connect first, the connected local apps with what they can use', async () => {
      setupPage();
      const { el } = await render();
      const apps = el.querySelector('section[aria-labelledby="ov-apps-title"]')!;
      expect(apps.querySelector('ul')!.getAttribute('aria-label')).toBe('AI apps, the one to connect first');
      expect(href(byText(apps, '.gw-section__actions a', 'All AI apps'))).toBe('/clients');
      const tiles = Array.from(apps.querySelectorAll('.gw-status-tile'));
      expect(tiles.map((t) => text(t.querySelector('.gw-status-tile__title')))).toEqual(['Cursor', 'Claude Desktop', 'Codex']);

      const cursor = tile(apps, 'Cursor')!;
      // Its action is the tile's only destination: no link of its own, not interactive.
      expect(cursor.classList).not.toContain('gw-card--interactive');
      expect(cursor.querySelector('.gw-card__link')).toBeNull();
      expect(text(cursor.querySelector('.gw-status'))).toBe('Not connected');
      expect(href(byText(cursor, 'a', 'Connect'))).toBe('/clients#cl-cu-t');
      expect(text(byText(cursor, 'a', 'Connect'))).toBe('Connect Cursor');
      expect(cursor.querySelector('.gw-tag-list')).toBeNull();

      const claude = tile(apps, 'Claude Desktop')!;
      expect(href(claude.querySelector('.gw-card__link'))).toBe('/clients#cl-cd-t');
      expect(text(claude.querySelector('.gw-status-tile__main p'))).toBe('Can use 11 connections, 3 of them prod');
      const endpoints = claude.querySelector('.gw-status-tile__main .gw-tag-list')!;
      expect(endpoints.getAttribute('aria-label')).toBe('Connected endpoints for Claude Desktop');
      expect(Array.from(endpoints.querySelectorAll('.gw-badge')).map(text)).toEqual(['dev', 'incident-prod']);
      expect(text(tile(apps, 'Codex')!.querySelector('.gw-status-tile__main p'))).toBe('Can use 8 connections');
      expect(Array.from(tile(apps, 'Codex')!.querySelectorAll('.gw-badge')).map(text)).toEqual(['dev']);
      expect(tile(apps, 'claude.ai')).toBeNull();
      // Antigravity isn't installed: it stays on AI apps.
      expect(tile(apps, 'Antigravity')).toBeNull();
    });

    it('says quietly that nothing waits when the queue is empty', async () => {
      setupPage(() => Promise.resolve(overview({ waiting: [], stats: { ...overview().stats, pending: 0 } })));
      const { el } = await render();
      expect(el.querySelector('#ov-wait')).toBeNull();
      expect(text(el.querySelector('#ov-wait-none'))).toBe(
        'Nothing waits for your approval. Calls that need your approval appear here and as a desktop notification. Tools in Bypass run without asking.',
      );
    });

    it('Test again is busy in place while it tests, ignores more clicks, then says what it found', async () => {
      setupPage();
      const { fixture, el } = await render();
      const billing = () => tile(el, 'billing-api')!;
      const button = billing().querySelector('button')!;
      button.click();
      fixture.detectChanges();
      button.click();
      fixture.detectChanges();
      expect(tests.map((t) => t.name)).toEqual(['billing-api']);
      expect(button.getAttribute('aria-busy')).toBe('true');
      expect(button.getAttribute('aria-disabled')).toBe('true');
      expect(button.querySelector('.gw-spinner')).not.toBeNull();
      const state = billing().querySelector('.gw-status--checking')!;
      expect(state.getAttribute('role')).toBe('status');
      expect(text(state)).toBe('Checking… billing-api');
      expect(button.getAttribute('aria-describedby')).toBe(state.id);

      tests[0].answer.resolve(unreachable);
      await settle(fixture);
      expect(button.hasAttribute('aria-busy')).toBe(false);
      expect(text(billing().querySelector('.gw-status'))).toBe('Unreachable');
      expect(TestBed.inject(ToastService).toasts().at(-1)).toMatchObject({
        tone: 'danger',
        title: 'billing-api is still unreachable',
        body: 'billing.stage.acme.dev did not answer. Check the VPN or the host, then test again.',
      });
    });

    it('a request that runs out here leaves at once; after the last one the quiet line takes its place', async () => {
      setupPage(() => Promise.resolve(overview({ waiting: [overview().waiting[0]], stats: { ...overview().stats, pending: 1 } })));
      const { fixture, el } = await render();
      expect(el.querySelectorAll('#ov-wait li')).toHaveLength(1);
      // The countdown hit zero (gw-countdown-end): the row goes, and the core is asked again shortly.
      now.set(Date.parse(overview().waiting[0].expiresAt) + 1000);
      await sleep(1100);
      await settle(fixture);
      expect(el.querySelector('#ov-wait')).toBeNull();
      expect(el.querySelector('#ov-wait-none')).not.toBeNull();
      now.set(NOW);
    });

    it("follows the core's events: a decided request leaves, a health change shows at once", async () => {
      setupPage();
      const { fixture, el } = await render();
      events.emit('approval.resolved', { id: 'ap_sql000000001', status: 'rejected', via: 'app' });
      fixture.detectChanges();
      expect(el.querySelectorAll('#ov-wait li')).toHaveLength(2);
      events.emit('connection.health', { name: 'billing-api', env: 'stage', state: 'healthy', latencyMs: 40, message: null, checkedAt: new Date(NOW).toISOString() });
      fixture.detectChanges();
      expect(text(tile(el, 'billing-api')!.querySelector('.gw-status'))).toBe('Healthy');
      expect(tile(el, 'billing-api')!.querySelector('button')).toBeNull();
    });
  });

  describe('first run', () => {
    it('is a three-step guide: Add your first connection is current and the one primary', async () => {
      setupPage(() => Promise.resolve(firstRun()), () => Promise.resolve(clientsFound()));
      const { el } = await render();
      expect(text(el.querySelector('.gw-page-header__description'))).toBe('Let your AI apps use your databases and servers without ever seeing a password.');
      expect(el.querySelector('.gw-page-header__actions')).toBeNull();
      const card = el.querySelector('section[aria-labelledby="ov-first-title"]')!;
      expect(text(card.querySelector('h2'))).toBe('Get started');
      expect(text(card.querySelector('.gw-card__description'))).toBe(
        'Your AI app asks GateRook, and GateRook works with your database or server for it. Passwords stay on this computer.',
      );
      const guide = card.querySelector('ol.gw-stepper.gw-stepper--guide')!;
      expect(guide.getAttribute('aria-labelledby')).toBe('ov-first-title');
      const steps = Array.from(guide.querySelectorAll<HTMLElement>(':scope > li'));
      expect(steps.map((s) => [s.dataset['state'], s.getAttribute('aria-current'), text(s.querySelector('h3'))])).toEqual([
        ['current', 'step', 'Add your first connection'],
        ['upcoming', null, 'Connect your AI app'],
        ['upcoming', null, 'Check that it works'],
      ]);
      expect(steps.map((s) => text(s.querySelector('.gw-stepper__marker')))).toEqual(['1', '2', '3']);

      const primaries = el.querySelectorAll('.gw-btn--primary');
      expect(primaries).toHaveLength(1);
      expect(text(primaries[0])).toBe('Add connection');
      expect(href(primaries[0])).toBe('/connections/new');
      expect(text(steps[2].querySelector('.gw-stepper__description'))).toBe(
        'Restart your AI app if GateRook says so, then ask it: “Show me the tables in my database”. Its first request shows up here.',
      );
    });

    it('shows the AI apps of this computer ahead; Connect waits and says why, How to install leads to the app', async () => {
      setupPage(() => Promise.resolve(firstRun()), () => Promise.resolve(clientsFound()));
      const { fixture, el } = await render();
      const step = el.querySelectorAll<HTMLElement>('ol.gw-stepper--guide > li')[1];
      const list = step.querySelector('ul')!;
      expect(list.getAttribute('aria-label')).toBe('AI apps on this computer');
      expect(Array.from(list.querySelectorAll('h4')).map(text)).toEqual(['Claude Desktop', 'Codex', 'Cursor', 'Antigravity']);
      const connect = byText<HTMLButtonElement>(tile(list, 'Claude Desktop')!, 'button', 'Connect')!;
      expect(text(connect)).toBe('Connect Claude Desktop');
      expect(connect.getAttribute('aria-disabled')).toBe('true');
      expect(connect.getAttribute('aria-describedby')).toBe('ov-first-why');
      expect(text(el.querySelector('#ov-first-why'))).toBe('Add a connection first: your AI app needs something to work with.');
      // aria-disabled: it stays in the Tab order and does nothing.
      const router = TestBed.inject(Router);
      const go = vi.spyOn(router, 'navigateByUrl');
      connect.click();
      fixture.detectChanges();
      expect(go).not.toHaveBeenCalled();

      const antigravity = tile(list, 'Antigravity')!;
      expect(text(antigravity.querySelector('.gw-status'))).toBe('Not installed');
      expect(text(antigravity.querySelector('.gw-status-tile__main p'))).toBe('Not found on this computer');
      const install = byText(antigravity, 'a', 'How to install')!;
      expect(install.classList).toContain('gw-btn--link');
      expect(href(install)).toBe('/clients#cl-ag-t');
    });

    it('leaves the apps out when this core has no clients area', async () => {
      setupPage(() => Promise.resolve(firstRun()), () => Promise.reject(new ApiError('not-available', 404, {})));
      const { el } = await render();
      expect(el.querySelector('.gw-status-tile')).toBeNull();
      expect(el.querySelector('#ov-first-why')).toBeNull();
      expect(el.querySelectorAll('ol.gw-stepper--guide > li')).toHaveLength(3);
    });
  });

  describe('setup', () => {
    it('marks step 1 done with the connection, makes Connect your AI app current and waits for the first request', async () => {
      setupPage(() => Promise.resolve(setup()), () => Promise.resolve(clientsFound()));
      const { el } = await render();
      const card = el.querySelector('section[aria-labelledby="ov-setup-title"]')!;
      expect(text(card.querySelector('h2'))).toBe('Almost done');
      expect(text(card.querySelector('.gw-card__description'))).toBe('Your connection works. Connect the AI app you use, and it can start reading dev-db.');
      const steps = Array.from(card.querySelectorAll<HTMLElement>('ol.gw-stepper--guide > li'));
      expect(steps.map((s) => s.dataset['state'])).toEqual(['complete', 'current', 'upcoming']);
      expect(text(steps[0].querySelector('h3'))).toBe('Add your first connection, completed');
      expect(steps[0].querySelector('.gw-stepper__marker svg')).not.toBeNull();
      expect(text(steps[0].querySelector('.gw-stepper__description'))).toBe('dev-db · PostgreSQL 16 · changes need your approval · Healthy');
      expect(href(steps[0].querySelector('.gw-stepper__description a'))).toBe('/connections/dev-db');
      // The master key was backed up (its getting-started step is done): no reminder, only Add another connection.
      expect(text(steps[0].querySelector('.gw-stepper__content'))).toBe('The password is encrypted with a key kept on this computer. · Add another connection');

      const connect = byText(tile(steps[1], 'Codex')!, 'a', 'Connect')!;
      expect(href(connect)).toBe('/clients#cl-cx-t');
      expect(connect.classList.contains('gw-btn--primary')).toBe(false);
      expect(el.querySelector('.gw-btn--primary')).toBeNull();
      expect(text(steps[2].querySelector('.gw-stepper__description'))).toBe(
        'Restart your AI app if GateRook says so, then ask it: “Show me the tables in dev-db”. Its first request shows up here.',
      );
      expect(text(steps[2].querySelector('.gw-status'))).toBe('Waiting for the first request');
    });

    it('reminds to back up the master key until it is', async () => {
      const o = setup();
      const steps = o.gettingStarted.steps.map((s) => (s.id === 'master-key' ? { ...s, state: 'current' as const } : s));
      setupPage(() => Promise.resolve({ ...o, gettingStarted: { ...o.gettingStarted, steps } }), () => Promise.resolve(clientsFound()));
      const { el } = await render();
      const content = el.querySelector('ol.gw-stepper--guide > li .gw-stepper__content')!;
      expect(text(content)).toBe('The password is encrypted with a key kept on this computer. Back up the key so you can restore it. · Add another connection');
      expect(href(byText(content, 'a', 'Back up the key'))).toBe('/secrets#master-key');
    });

    it('shows an observed browser sign-in as connected while setup progress catches up', async () => {
      setupPage(() => Promise.resolve(setup()), () => Promise.resolve(clientsBrowserSignedIn()));
      const { el } = await render();
      const cursor = tile(el, 'Cursor')!;
      expect(text(cursor.querySelector('.gw-status__label'))).toBe('Connected');
      expect(byText(cursor, 'a', 'Connect')).toBeNull();
      expect(href(cursor.querySelector('.gw-card__link'))).toBe('/clients#cl-cu-t');
      expect(text(el)).toContain('Waiting for the first request');
    });

    it('leaves setup on OAuth authentication alone and keeps the first request unrecorded', async () => {
      setupPage(() => Promise.resolve(setup()), () => Promise.resolve(clientsFound()));
      const { fixture, el } = await render();
      expect(el.querySelector('#ov-setup-title')).not.toBeNull();
      overviewAnswers = [() => Promise.resolve(browserSignedIn())];
      clientsAnswer = () => Promise.resolve(clientsBrowserSignedIn());
      calls = [];
      events.emit('oauth.grant.changed', { id: 'og_cursor', endpoint: 'dev' });
      await sleep(1_050);
      await settle(fixture);
      expect(calls).toEqual(['GET /api/overview']);
      expect(el.querySelector('#ov-setup-title')).toBeNull();
      expect(text(tile(el, 'Cursor')?.querySelector('.gw-status__label'))).toBe('Connected');
      expect(byText(tile(el, 'Cursor')!, 'a', 'Connect')).toBeNull();
      expect(text(el)).toContain('No calls from your AI apps in the last 24 hours');
    });

    it('becomes the home screen once the core says an AI app is connected', async () => {
      setupPage(() => Promise.resolve(setup()), () => Promise.resolve(clientsFound()));
      const { fixture, el } = await render();
      expect(el.querySelector('#ov-setup-title')).not.toBeNull();
      overviewAnswers = [() => Promise.resolve(overview())];
      clientsAnswer = () => Promise.resolve(clientsInUse());
      events.emit('approval.requested', { id: 'ap_new' });
      await sleep(200);
      await settle(fixture);
      expect(el.querySelector('#ov-setup-title')).toBeNull();
      expect(text(el.querySelector('#ov-conn-title'))).toBe('Your connections');
    });
  });

  it('says what failed when nothing loaded, and Try again loads it', async () => {
    setupPage(() => Promise.reject(new ApiError('server', 500, { title: 'Internal error', detail: 'The database is locked.' })));
    const { fixture, el } = await render();
    const alert = el.querySelector('.gw-alert--danger')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The overview didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The database is locked.');
    overviewAnswers = [() => Promise.resolve(overview())];
    byText<HTMLButtonElement>(alert, 'button', 'Try again')!.click();
    await settle(fixture);
    expect(el.querySelector('.gw-alert--danger')).toBeNull();
    expect(el.querySelector('#ov-conn-title')).not.toBeNull();
  });

  it('keeps what it showed when a refresh fails, and says it may be out of date', async () => {
    setupPage();
    const { fixture, el } = await render();
    overviewAnswers = [() => Promise.reject(new ApiError('server', 500, { title: 'Internal error', detail: 'Try again later.' }))];
    events.emit('approval.requested', { id: 'ap_new' });
    await sleep(200);
    await settle(fixture);
    expect(text(el.querySelector('.gw-alert--warning .gw-alert__title'))).toBe('This overview may be out of date.');
    expect(el.querySelector('#ov-conn-title')).not.toBeNull();
  });
});
