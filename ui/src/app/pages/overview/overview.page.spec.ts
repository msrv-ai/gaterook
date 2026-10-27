import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActivityApi, ActivityEvent, EventsPage, Histogram } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ApprovalDetail, ApprovalList, ApprovalsApi } from '../../core/api/approvals.api';
import { ClientsApi } from '../../core/api/clients.api';
import { ConnectionsApi, TestOutcome } from '../../core/api/connections.api';
import { GettingStarted, Overview, OverviewApi } from '../../core/api/overview.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { NOW, event, health, histogram, overview } from './overview.fixtures';
import { OverviewPage, SKELETON_DELAY_MS } from './overview.page';

/** The page against fake admin API services: what it shows for each answer, and what it asks the core to do. */

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: (at) => (at ? 'a while ago' : ''),
  absolute: (at) => (at ? `abs ${at}` : ''),
  iso: (at) => at ?? '',
  date: () => '',
  clock: () => '',
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

const ok: TestOutcome = {
  ok: true,
  latencyMs: 42,
  serverVersion: null,
  identity: null,
  permissions: null,
  hints: [],
  checks: [],
  error: null,
  needsConfirmation: null,
  confirmationId: null,
  suggestedPolicyChange: null,
  details: null,
};

let calls: string[];
let overviewAnswers: (() => Promise<Overview>)[];
let events: FakeEvents;
let tests: { name: string; answer: ReturnType<typeof deferred<TestOutcome>> }[];
let dismissAnswer: () => Promise<GettingStarted>;
/** What Activity answers for the last decided calls. */
let decided: ActivityEvent[];

function setup(first: () => Promise<Overview> = () => Promise.resolve(overview())): void {
  calls = [];
  overviewAnswers = [first];
  events = new FakeEvents();
  tests = [];
  dismissAnswer = () => Promise.resolve({ ...overview().gettingStarted, dismissed: true });
  decided = [event(8), event(7, { decision: 'denied', durationMs: null, connection: 'prod-replica', env: 'prod' })];
  const overviewApi: Partial<OverviewApi> = {
    get: () => {
      calls.push('GET /api/overview');
      const next = overviewAnswers.length > 1 ? overviewAnswers.shift()! : overviewAnswers[0];
      return next();
    },
    dismissGettingStarted: () => {
      calls.push('dismiss');
      return dismissAnswer();
    },
    restoreGettingStarted: () => {
      calls.push('restore');
      return Promise.resolve({ ...overview().gettingStarted, dismissed: false });
    },
  };
  const activityApi: Partial<ActivityApi> = {
    histogram: () => Promise.resolve(histogram() as Histogram),
    events: (range) => {
      calls.push(`events ${range?.q}`);
      return Promise.resolve<EventsPage>({ events: decided, nextCursor: null });
    },
  };
  const approvalsApi: Partial<ApprovalsApi> = {
    list: (status) =>
      Promise.resolve<ApprovalList>({ status, items: status === 'pending' ? overview().waiting : [], nextCursor: null, pending: 3, counts: { expired: 3 } }),
    get: (id) =>
      Promise.resolve({
        id,
        action: { kind: 'sql', language: 'sql', text: `ACTION OF ${id}`, segments: [{ t: 'text', v: `ACTION OF ${id}` }], hiddenChars: 0, confusables: 0, warning: null, diff: null },
      } as unknown as ApprovalDetail),
  };
  const clientsApi: Partial<ClientsApi> = { list: () => Promise.reject(new ApiError('not-available', 404, {})) };
  const connectionsApi: Partial<ConnectionsApi> = {
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
      { provide: ActivityApi, useValue: activityApi },
      { provide: ApprovalsApi, useValue: approvalsApi },
      { provide: ClientsApi, useValue: clientsApi },
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

describe('Overview page', () => {
  it('shows nothing for a moment, then the skeleton until the core answers', async () => {
    const answer = deferred<Overview>();
    setup(() => answer.promise);
    const { fixture, el } = await render();
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(text(el.querySelector('h1'))).toBe('Overview');

    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelectorAll('.gw-skeleton').length).toBeGreaterThan(5);
    expect(el.querySelectorAll('[aria-busy="true"]').length).toBe(5);
    expect(text(el.querySelector('[role="status"]'))).toBe('Loading the overview');

    answer.resolve(overview());
    await settle(fixture);
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(el.querySelector('[aria-busy="true"]')).toBeNull();
  });

  it('renders the kit sections from the answers', async () => {
    setup();
    const { el } = await render();

    // The one primary of the header, while requests wait.
    expect(text(el.querySelector('.gw-page-header__actions .gw-btn--primary'))).toBe('Review approvals');
    expect(text(el.querySelector('#getting-started .gw-getting-started__count'))).toBe('5 of 6 done');
    expect(text(el.querySelector('#getting-started .gw-card__description'))).toBe("Six steps from install to your agent's first safe call.");
    expect(Array.from(el.querySelectorAll('.gw-setup-map__status')).map(text)).toEqual([
      'Master key: no OS protection, not backed up',
      '1 need attention',
      'dev, stage, prod',
      '3 endpoints',
      '1 named token',
    ]);
    const tiles = Array.from(el.querySelectorAll('.gw-stat'));
    expect(tiles.map((t) => text(t.querySelector('.gw-stat__value')))).toEqual(['1,284', '3', '17', '31 ms']);
    expect(tiles[2].querySelector('.gw-stat__delta')?.getAttribute('data-sentiment')).toBe('bad');
    expect(tiles[3].querySelector('.gw-stat__chart')).toBeNull();
    expect(tiles[0].querySelector('svg.gw-sparkline')?.getAttribute('role')).toBe('img');

    // Waiting for you: three compact requests, their exact first line from the request's detail.
    const waiting = el.querySelector('ul[aria-label="Waiting for approval, 3 requests"]')!;
    expect(waiting.querySelectorAll('article.gw-approval-request--compact').length).toBe(3);
    expect(text(waiting.querySelector('.gw-approval-request__preview'))).toBe('ACTION OF ap_k8s000000001');

    // Health: the alert where it happens, problems first, the rest folded.
    expect(text(el.querySelector('.gw-alert--danger .gw-alert__title'))).toBe("Can't reach billing.stage.acme.dev.");
    expect(text(el.querySelector('.gw-alert--danger .gw-alert__title .gw-mono'))).toBe('billing.stage.acme.dev');
    const problemRows = el.querySelectorAll('#health-table tbody:not(#health-ok) tr');
    expect(Array.from(problemRows).map((r) => text(r.querySelector('th')))).toEqual(['billing-api environment stage', 'cache-redis environment dev']);
    expect((el.querySelector('#health-ok') as HTMLElement).hidden).toBe(true);

    // Recent: decided calls from Activity, each opening its event.
    expect(calls).toContain('events -decision:pending');
    const rows = el.querySelectorAll('table.gw-table--interactive tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('a.gw-table__row-link')?.getAttribute('href')).toBe('/activity#event-8');
    expect(text(rows[1].querySelector('.gw-badge'))).toBe('Denied by policy');
  });

  it("draws the overhead per hour and says how often health is checked when the core tells (step API)", async () => {
    const o = overview();
    setup(() =>
      Promise.resolve(
        overview({
          stats: { ...o.stats, gatewayP95Spark: [...Array<number | null>(21).fill(null), 29, 31, 30] },
          healthIntervalSeconds: 60,
        }),
      ),
    );
    const { el } = await render();
    const overhead = Array.from(el.querySelectorAll('.gw-stat'))[3];
    expect(overhead.querySelector('svg.gw-sparkline')?.getAttribute('aria-label')).toBe(
      'Gateway overhead p95 per hour: always under the 50 ms target',
    );
    expect(overhead.querySelectorAll('polyline').length).toBe(1);
    expect(text(el.querySelector('section[aria-labelledby="health-title"] .gw-section__description'))).toBe(
      '4 connections, checked every minute. Problems first.',
    );
  });

  it('has no primary with an empty queue, and says so in each empty section', async () => {
    setup(() =>
      Promise.resolve(
        overview({
          stats: { ...overview().stats, pending: 0 },
          waiting: [],
          health: [],
          recent: [],
        }),
      ),
    );
    decided = [];
    const { el } = await render();
    expect(el.querySelector('.gw-page-header__actions .gw-btn--primary')).toBeNull();
    expect(text(el.querySelector('section[aria-labelledby="waiting-title"] .gw-empty__title'))).toBe('Nothing waiting');
    expect(text(el.querySelector('section[aria-labelledby="health-title"] .gw-empty__title'))).toBe('No connections yet');
    expect(text(el.querySelector('section[aria-labelledby="recent-title"] .gw-empty__title'))).toBe('No calls yet');
  });

  it('folds healthy connections behind a disclosure that says how many', async () => {
    setup();
    const { fixture, el } = await render();
    const toggle = byText<HTMLButtonElement>(el, 'button', '2 healthy connections')!;
    expect(toggle.getAttribute('aria-controls')).toBe('health-ok');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    await settle(fixture);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect((el.querySelector('#health-ok') as HTMLElement).hidden).toBe(false);
    expect(el.querySelectorAll('#health-ok tr').length).toBe(2);
  });

  it('Dismiss hides the checklist at once, moves focus on, and Undo brings it back in order', async () => {
    setup();
    const { fixture, el } = await render();
    byText<HTMLButtonElement>(el, 'button', 'Dismiss checklist')!.click();
    await settle(fixture);
    expect(el.querySelector('#getting-started')).toBeNull();
    expect(document.activeElement?.id).toBe('setup-title');
    const toasts = TestBed.inject(ToastService).toasts();
    expect(toasts.at(-1)?.title).toBe('Checklist dismissed');

    toasts.at(-1)!.actions[0].onClick!();
    await settle(fixture);
    expect(calls.filter((c) => c === 'dismiss' || c === 'restore')).toEqual(['dismiss', 'restore']);
    expect(el.querySelector('#getting-started')).not.toBeNull();
    expect(text(document.activeElement)).toBe('Dismiss checklist');
  });

  it('puts the checklist back and says why when the core refuses Dismiss', async () => {
    setup();
    dismissAnswer = () => Promise.reject(new ApiError('server', 500, { title: 'The core failed', detail: 'See the core log for details, then try again.' }));
    const { fixture, el } = await render();
    byText<HTMLButtonElement>(el, 'button', 'Dismiss checklist')!.click();
    await settle(fixture);
    expect(el.querySelector('#getting-started')).not.toBeNull();
    const last = TestBed.inject(ToastService).toasts().at(-1)!;
    expect(last.tone).toBe('danger');
    expect(last.title).toBe("The checklist wasn't dismissed");
  });

  it('Test again is busy in place while it tests, ignores more clicks, then says what it found', async () => {
    setup();
    const { fixture, el } = await render();
    const button = el.querySelector<HTMLButtonElement>('#health-retest')!;
    expect(text(button)).toBe('Test again billing-api');
    button.click();
    await settle(fixture);
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(button.getAttribute('aria-describedby')).toBe('health-retest-status');
    expect(button.querySelector('.gw-spinner')).not.toBeNull();
    const status = el.querySelector<HTMLElement>('#health-retest-status')!;
    expect(status.hidden).toBe(false);
    expect(text(status)).toBe('Testing billing-api with its stored credentials.');

    button.click();
    expect(tests.map((t) => t.name)).toEqual(['billing-api']);

    tests[0].answer.resolve({ ...ok, ok: false, error: { code: 'unreachable', message: 'billing.stage.acme.dev did not answer. Check the VPN or the host.', technical: null } });
    await settle(fixture);
    expect(button.getAttribute('aria-busy')).toBeNull();
    expect(button.getAttribute('aria-describedby')).toBeNull();
    expect(status.hidden).toBe(true);
    const last = TestBed.inject(ToastService).toasts().at(-1)!;
    expect(last).toMatchObject({ tone: 'danger', title: 'billing-api is still unreachable', body: 'billing.stage.acme.dev did not answer. Check the VPN or the host.' });
  });

  it("follows the core's events: a decided request leaves, a health change shows at once", async () => {
    setup();
    const { fixture, el } = await render();
    const before = calls.filter((c) => c === 'GET /api/overview').length;

    events.emit('approval.resolved', { id: 'ap_sql000000001', status: 'rejected', via: 'app' });
    await settle(fixture);
    expect(el.querySelectorAll('article.gw-approval-request--compact').length).toBe(2);

    events.emit('connection.health', { name: 'billing-api', env: 'stage', state: 'healthy', latencyMs: 40, message: null, checkedAt: new Date(NOW).toISOString() });
    await settle(fixture);
    expect(el.querySelector('.gw-alert--danger')).toBeNull();

    await sleep(300);
    await settle(fixture);
    expect(calls.filter((c) => c === 'GET /api/overview').length).toBeGreaterThan(before);
  });

  it('says what failed when nothing loaded, and Try again loads it', async () => {
    let fail = true;
    setup(() => (fail ? Promise.reject(new ApiError('server', 500, { title: 'The core failed', detail: 'See the core log for details, then try again.' })) : Promise.resolve(overview())));
    const { fixture, el } = await render();
    const alert = el.querySelector('.gw-alert--danger[role="alert"]')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The overview didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('See the core log for details, then try again.');
    const details = el.querySelector<HTMLElement>('#overview-error-details')!;
    expect(details.hidden).toBe(true);
    byText<HTMLButtonElement>(alert, 'button', 'Details')!.click();
    await settle(fixture);
    expect(details.hidden).toBe(false);
    expect(text(details.querySelector('pre'))).toContain('GET /api/overview → 500');

    fail = false;
    byText<HTMLButtonElement>(alert, 'button', 'Try again')!.click();
    await settle(fixture);
    expect(el.querySelector('#getting-started')).not.toBeNull();
    expect(el.querySelector('[role="alert"]')).toBeNull();
  });

  it('keeps what it showed when a refresh fails, and says it may be out of date', async () => {
    let fail = false;
    setup(() => (fail ? Promise.reject(new ApiError('server', 500, {})) : Promise.resolve(overview())));
    const { fixture, el } = await render();
    fail = true;
    events.emit('approval.requested', { id: 'ap_new', endpoint: 'dev', connection: 'dev-db', env: 'dev', tool: 'sql_execute', summary: null, commentPolicy: 'optional', expiresAt: '' });
    await sleep(300);
    await settle(fixture);
    expect(el.querySelector('#getting-started')).not.toBeNull();
    expect(text(el.querySelector('.gw-alert--warning .gw-alert__title'))).toBe('This overview may be out of date.');
  });

  it('shows "not checked yet" rather than "no problems" before the first check', async () => {
    setup(() => Promise.resolve(overview({ health: [health('dev-db', 'unknown', { checkedAt: null })] })));
    const { el } = await render();
    expect(text(el.querySelector('section[aria-labelledby="health-title"] .gw-empty__title'))).toBe('Not checked yet');
    const toggle = byText<HTMLButtonElement>(el, 'button', '1 connection not checked yet')!;
    expect(toggle.getAttribute('aria-controls')).toBe('health-table');
    expect((el.querySelector('#health-table') as HTMLElement).hidden).toBe(true);
  });
});
