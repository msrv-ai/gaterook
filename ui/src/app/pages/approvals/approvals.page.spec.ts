import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { approvalsMatcher } from '../../app.routes';
import { ActivityApi, ActivityEvent } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ApprovalDecision, ApprovalDetail, ApprovalItem, ApprovalList, ApprovalStatus, ApprovalsApi } from '../../core/api/approvals.api';
import { Notifications, NotificationsApi } from '../../core/api/notifications.api';
import { EVENT_STREAM_FETCH, EventStream } from '../../core/events/event-stream';
import { TimeService } from '../../core/time/time.service';
import { ACTIVATION_DELAY_MS } from '../../kit/activation-guard';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { ApprovalsPage } from './approvals.page';

/**
 * The Approvals page against the kit's page script (design/pages/approvals.html): which request opens, where focus
 * goes, what a decision does to the queue, live updates, the history tab and the empty, loading and error states.
 * The admin API is faked at the service level with the demo world's requests (BLUEPRINT §9).
 */

const NOW = Date.parse('2026-10-22T14:30:00+02:00');
const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: (at) => (at ? 'a while ago' : ''),
  absolute: (at) => at ?? '',
  iso: (at) => at ?? '',
  date: () => 'Today',
  clock: (at) => (at ? '14:29:13' : ''),
};

function item(over: Partial<ApprovalItem>): ApprovalItem {
  return {
    id: 'ap_sql000000001',
    tool: 'sql_execute',
    connection: 'dev-db',
    env: 'dev',
    endpoint: 'dev',
    client: 'codex',
    tokenLabel: 'codex-laptop',
    requestedAt: '2026-10-22T14:29:12+02:00',
    expiresAt: '2026-10-22T14:34:12+02:00',
    status: 'pending',
    summary: 'sql_execute on dev-db',
    decidedVia: null,
    decidedAt: null,
    commentPolicy: 'optional',
    ...over,
  };
}

function detail(i: ApprovalItem, text: string, over: Partial<ApprovalDetail> = {}): ApprovalDetail {
  return {
    ...i,
    callId: `tc_${i.id}`,
    toolKey: i.tool,
    action: { kind: 'sql', language: 'sql', text, segments: [{ t: 'text', v: text }], hiddenChars: 0, confusables: 0, warning: null, diff: null },
    target: `${i.connection} · db.dev.internal:5432/app`,
    fields: [],
    impact: [{ label: 'Rows', text: '1 row (estimate)' }],
    warnings: [],
    rule: { id: `${i.env}.sql.write-approval`, text: `Every change waits for a person on ${i.env}.` },
    agentNote: 'The customer cancelled order 48213',
    comment: null,
    digest: `digest-of-${i.id}`,
    executedAt: null,
    error: null,
    result: null,
    resultExpired: false,
    timeline: [{ id: 1, at: i.requestedAt, kind: 'requested', detail: { by: i.tokenLabel ?? '' } }],
    channels: { desktop: 'off', slack: 'off', telegram: 'off' },
    ...over,
  };
}

const K8S = item({ id: 'ap_k8s000000001', tool: 'k8s_rollout_restart', connection: 'dev-cluster', client: 'claude-desktop', tokenLabel: 'claude-desktop', requestedAt: '2026-10-22T14:25:48+02:00', expiresAt: '2026-10-22T14:30:48+02:00' });
const SQL = item({});
const HTTP = item({ id: 'ap_http00000001', tool: 'http_request', connection: 'billing-api', env: 'stage', requestedAt: '2026-10-22T14:29:30+02:00', expiresAt: '2026-10-22T14:39:30+02:00' });
const REJECTED = item({ id: 'ap_rej00000001', tool: 'k8s_apply', connection: 'dev-cluster', client: 'claude-desktop', status: 'rejected', decidedVia: 'app', decidedAt: '2026-10-22T13:58:30+02:00' });

/** The fake core: its queue, history and details change as the test decides. */
class FakeCore {
  pending: ApprovalItem[] = [];
  history: ApprovalItem[] = [];
  details = new Map<string, ApprovalDetail>();
  decisions: { id: string; decision: ApprovalDecision }[] = [];
  decideFails: ApiError | null = null;
  listFails: ApiError | null = null;
  notifications: Notifications | ApiError = new ApiError('not-available', 404, {});
  events: ActivityEvent[] = [];

  seed(): void {
    this.pending = [K8S, SQL, HTTP];
    this.history = [REJECTED];
    this.details = new Map([
      [K8S.id, detail(K8S, 'kubectl rollout restart deployment/api -n app-dev', { action: { kind: 'command', language: 'sh', text: 'kubectl rollout restart deployment/api -n app-dev', segments: [{ t: 'text', v: 'kubectl rollout restart deployment/api -n app-dev' }], hiddenChars: 0, confusables: 0, warning: null, diff: null } })],
      [SQL.id, detail(SQL, "UPDATE orders SET status = 'cancelled' WHERE id = 48213;")],
      [HTTP.id, detail(HTTP, 'POST /v1/refunds HTTP/1.1')],
      [REJECTED.id, detail(REJECTED, '-  replicas: 3', { timeline: [{ id: 4, at: REJECTED.requestedAt, kind: 'decided', detail: { decision: 'rejected', by: 'you', via: 'app' } }] })],
    ]);
    this.events = [{ id: 412, approvalId: SQL.id, traceId: 'c7e19d9b0a2f4e1c8d3b5a7f9e1c2d4b' } as ActivityEvent];
  }

  /** The core decides: the request leaves the queue and joins the history. */
  resolve(id: string, status: ApprovalItem['status'], via = 'app', over: Partial<ApprovalDetail> = {}): void {
    const i = this.pending.find((p) => p.id === id) ?? this.details.get(id)!;
    this.pending = this.pending.filter((p) => p.id !== id);
    const decided = { ...item(i), status, decidedVia: via, decidedAt: '2026-10-22T14:30:00+02:00' };
    this.history = [decided, ...this.history.filter((h) => h.id !== id)];
    this.details.set(id, { ...this.details.get(id)!, status, decidedVia: via, decidedAt: decided.decidedAt, ...over });
  }

  list(status: 'pending' | 'history'): ApprovalList {
    const counts = { pending: this.pending.length, rejected: this.history.length };
    return { status, items: status === 'pending' ? this.pending : this.history, nextCursor: null, pending: this.pending.length, counts };
  }
}

let core: FakeCore;
let clock = 0;

beforeEach(() => {
  now.set(NOW);
  clock = 10_000;
  vi.spyOn(performance, 'now').mockImplementation(() => clock);
  core = new FakeCore();
  core.seed();
  const approvals: Partial<ApprovalsApi> = {
    list: vi.fn(async (status: 'pending' | 'history') => {
      if (core.listFails) throw core.listFails;
      return structuredClone(core.list(status));
    }),
    get: vi.fn(async (id: string) => {
      const d = core.details.get(id);
      if (!d) throw new ApiError('not-found', 404, { title: 'Request not found', detail: `There is no approval request ${id}.` });
      return structuredClone(d);
    }),
    decide: vi.fn(async (id: string, decision: ApprovalDecision) => {
      if (core.decideFails) throw core.decideFails;
      core.decisions.push({ id, decision });
      const status: ApprovalStatus = decision.decision === 'approve' ? 'running' : 'rejected';
      core.resolve(id, status);
      return { id, status };
    }),
  };
  const activity: Partial<ActivityApi> = { events: vi.fn(async () => ({ events: core.events, nextCursor: null })) };
  const notifications: Partial<NotificationsApi> = {
    get: vi.fn(async () => {
      if (core.notifications instanceof ApiError) throw core.notifications;
      return core.notifications;
    }),
  };
  @Component({ template: '' })
  class Elsewhere {}
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ matcher: approvalsMatcher, component: ApprovalsPage }, { path: '**', component: Elsewhere }], withComponentInputBinding()),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: ApprovalsApi, useValue: approvals },
      { provide: ActivityApi, useValue: activity },
      { provide: NotificationsApi, useValue: notifications },
      // A stream that never connects: the tests emit the core's events themselves.
      { provide: EVENT_STREAM_FETCH, useValue: (() => new Promise<Response>(() => undefined)) as typeof fetch },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

async function open(url = '/approvals') {
  const harness = await RouterTestingHarness.create();
  document.body.appendChild(harness.fixture.nativeElement);
  await harness.navigateByUrl(url);
  await settle(harness);
  const el = harness.routeNativeElement as HTMLElement;
  const settleAll = () => settle(harness);
  return {
    harness,
    el,
    settle: settleAll,
    rows: () => Array.from(el.querySelectorAll<HTMLElement>('#apq-list > li > article')),
    detail: () => el.querySelector<HTMLElement>('.gw-split__detail article.gw-approval-request'),
    button: (name: string) =>
      Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => text(b) === name || text(b).startsWith(`${name} `))!,
  };
}

/** Lets the fake API's promises, the effects and the render run until the page is quiet. */
async function settle(harness: RouterTestingHarness): Promise<void> {
  for (let i = 0; i < 6; i++) {
    await new Promise((r) => setTimeout(r));
    harness.detectChanges();
  }
  await harness.fixture.whenStable();
}

describe('Approvals page', () => {
  it('lists the queue most urgent first and opens the request about to be denied, without moving focus', async () => {
    const { el, rows, detail } = await open();
    expect(rows().map((r) => r.getAttribute('data-ap-item'))).toEqual([K8S.id, SQL.id, HTTP.id]);
    expect(el.querySelector('#apq-list')?.getAttribute('aria-label')).toBe('Waiting for approval, 3 requests, most urgent first');
    expect(text(el.querySelector('#ap-tab-pending'))).toBe('Pending 3 waiting');
    expect(text(el.querySelector('#ap-tab-history'))).toBe('History 1 decided in the last 24 hours');
    // The open request: selected row, aria-current on its Review link, its detail next to the list.
    expect(rows()[0].classList).toContain('gw-card--selected');
    expect(rows()[0].querySelector('a')?.getAttribute('aria-current')).toBe('true');
    expect(rows()[1].querySelector('a')?.hasAttribute('aria-current')).toBe(false);
    expect(text(detail()?.querySelector('h2'))).toBe('k8s_rollout_restart on dev-cluster environment dev');
    // The preview line is the first line of the exact action, not the summary.
    expect(text(rows()[1].querySelector('.gw-approval-request__preview'))).toBe("UPDATE orders SET status = 'cancelled' WHERE id = 48213;");
    expect(document.activeElement).toBe(document.body);
  });

  it('opens the request the URL names and puts focus on its title (Review, deep link)', async () => {
    const { el, rows, detail, harness, settle } = await open();
    const page = harness.routeDebugElement?.componentInstance;
    rows()[1].querySelector<HTMLAnchorElement>('a')!.click();
    await settle();
    expect(TestBed.inject(Router).url).toBe(`/approvals/${SQL.id}`);
    expect(document.activeElement).toBe(detail()?.querySelector('h2'));
    expect(rows()[1].classList).toContain('gw-card--selected');
    // The same page (one route for /approvals and /approvals/<id>): queue, timers and comments stay.
    expect(harness.routeDebugElement?.componentInstance).toBe(page);
    // Review on the open request again: no navigation, focus still goes to its title.
    (document.activeElement as HTMLElement).blur();
    rows()[1].querySelector<HTMLAnchorElement>('a')!.click();
    await settle();
    expect(document.activeElement).toBe(detail()?.querySelector('h2'));
    expect(el.querySelectorAll('#apq-list .gw-card--selected')).toHaveLength(1);
  });

  it('links the open request to its trace and fills the kit’s meta row', async () => {
    const { detail } = await open(`/approvals/${SQL.id}`);
    const trace = detail()!.querySelector<HTMLAnchorElement>('.gw-approval-request__meta a.gw-link');
    expect(text(trace)).toBe('c7e19d: open the task in Activity');
    expect(trace?.getAttribute('href')).toBe('/activity?view=traces&q=trace:c7e19d');
  });

  it("links the request's own call when its detail names it (step API), even when Activity's index doesn't", async () => {
    core.events = [];
    core.details.set(K8S.id, { ...core.details.get(K8S.id)!, eventId: 977, traceId: '7d19b0e4c2a6418f9b5d3e7a0c8f2163', traceIsTask: true });
    core.details.set(HTTP.id, { ...core.details.get(HTTP.id)!, eventId: 978, traceId: 'aa11bb22cc33dd44ee55ff6600112233', traceIsTask: false });
    const { detail, settle } = await open(`/approvals/${K8S.id}`);
    const trace = detail()!.querySelector<HTMLAnchorElement>('.gw-approval-request__meta a.gw-link');
    expect(trace?.getAttribute('href')).toBe('/activity?view=traces&q=trace:7d19b0');
    // A lone call's trace isn't a task of Traces: no Trace row for it.
    await TestBed.inject(Router).navigateByUrl(`/approvals/${HTTP.id}`);
    await settle();
    expect(Array.from(detail()!.querySelectorAll('.gw-approval-request__meta .gw-kv__key')).map(text)).not.toContain('Trace');
  });

  it('Deny: the request leaves the queue, a toast says what happens, the next most urgent opens with focus', async () => {
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { el, rows, detail, button, settle } = await open(`/approvals/${SQL.id}`);
    clock += ACTIVATION_DELAY_MS;
    button('Deny').click();
    await settle();
    expect(core.decisions).toEqual([{ id: SQL.id, decision: { decision: 'deny', comment: null } }]);
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.title, t.body])).toEqual([
      ['sql_execute on dev-db rejected', 'Codex was told the statement was rejected. Nothing ran on dev-db.'],
    ]);
    expect(announce).toHaveBeenCalledWith('2 requests still waiting');
    expect(rows().map((r) => r.getAttribute('data-ap-item'))).toEqual([K8S.id, HTTP.id]);
    expect(TestBed.inject(Router).url).toBe(`/approvals/${K8S.id}`);
    expect(document.activeElement).toBe(detail()?.querySelector('h2'));
    expect(text(el.querySelector('#ap-pending-count'))).toBe('2');
  });

  it('a decision too soon after the request appeared is ignored (700 ms, decision 6)', async () => {
    const { button, settle } = await open(`/approvals/${SQL.id}`);
    button('Deny').click();
    await settle();
    expect(core.decisions).toEqual([]);
  });

  it('after the last request the empty states show and focus goes to the empty detail’s title', async () => {
    core.pending = [SQL];
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { el, button, settle } = await open();
    clock += ACTIVATION_DELAY_MS;
    button('Approve and run').click();
    await settle();
    expect(core.decisions[0].decision).toEqual({ decision: 'approve', comment: null, digest: `digest-of-${SQL.id}` });
    expect(announce).toHaveBeenCalledWith('Nothing waiting for approval');
    expect(text(el.querySelector('#apq-empty'))).toBe('Nothing waiting Agents will ask here before any change.');
    const title = el.querySelector('#ap-detail-empty-title');
    expect(text(title)).toBe('No request to review');
    expect(document.activeElement).toBe(title);
    expect(text(el.querySelector('#ap-tab-pending'))).toBe('Pending 0 waiting');
  });

  it('says so when the gateway stops an approved call before it runs', async () => {
    const { settle, button } = await open(`/approvals/${SQL.id}`);
    clock += ACTIVATION_DELAY_MS;
    button('Approve and run').click();
    await settle();
    // The executor checked again and refused it: `approval.resolved` (rejected, by the core).
    core.resolve(SQL.id, 'rejected', 'app', { error: 'Access of codex-laptop to dev was revoked. Not executed.' });
    TestBed.inject(EventStream).emit('approval.resolved', { id: SQL.id, status: 'rejected', via: 'core' });
    await settle();
    const toasts = TestBed.inject(ToastService).toasts();
    expect(toasts.map((t) => t.title)).toEqual(['sql_execute on dev-db approved', "sql_execute on dev-db didn't run"]);
    expect(toasts[1].body).toBe('Access of codex-laptop to dev was revoked. Not executed.');
  });

  it('409: decided elsewhere meanwhile — a toast, and the request shows what happened', async () => {
    const { detail, button, settle } = await open(`/approvals/${SQL.id}`);
    core.decideFails = new ApiError('conflict', 409, { title: 'Already decided', detail: 'The request is rejected.' });
    core.resolve(SQL.id, 'rejected', 'slack');
    clock += ACTIVATION_DELAY_MS;
    button('Approve and run').click();
    await settle();
    expect(TestBed.inject(ToastService).toasts().map((t) => [t.tone, t.title, t.body])).toEqual([['warning', 'Already decided', 'The request is rejected.']]);
    expect(detail()?.getAttribute('data-state')).toBe('rejected');
    expect(TestBed.inject(Router).url).toBe(`/approvals/${SQL.id}`);
  });

  it('422: the reason stays next to the buttons and nothing moves on', async () => {
    const { detail, button, settle } = await open(`/approvals/${SQL.id}`);
    core.decideFails = new ApiError('unprocessable', 422, { title: 'Comment required', detail: 'Add a comment to approve on prod.' });
    clock += ACTIVATION_DELAY_MS;
    button('Approve and run').click();
    await settle();
    expect(text(detail()?.querySelector('[role=alert]'))).toBe('Add a comment to approve on prod.');
    expect(TestBed.inject(Router).url).toBe(`/approvals/${SQL.id}`);
  });

  it('live: a new request joins the queue; one decided elsewhere leaves it and the open one shows its outcome', async () => {
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { rows, detail, settle } = await open(`/approvals/${SQL.id}`);
    const NEW = item({ id: 'ap_new00000001', tool: 'redis_del', connection: 'cache-redis', expiresAt: '2026-10-22T14:35:00+02:00' });
    core.pending = [...core.pending, NEW];
    core.details.set(NEW.id, detail_(NEW));
    TestBed.inject(EventStream).emit('approval.requested', { id: NEW.id, endpoint: 'dev', connection: 'cache-redis', env: 'dev', tool: 'redis_del', summary: null, commentPolicy: 'optional', expiresAt: NEW.expiresAt });
    await settle();
    expect(rows().map((r) => r.getAttribute('data-ap-item'))).toEqual([K8S.id, SQL.id, NEW.id, HTTP.id]);
    expect(announce).toHaveBeenCalledWith('New approval request: redis_del on cache-redis');

    core.resolve(SQL.id, 'rejected', 'slack', { comment: 'Not now' });
    TestBed.inject(EventStream).emit('approval.resolved', { id: SQL.id, status: 'rejected', via: 'slack' });
    await settle();
    expect(announce).toHaveBeenCalledWith('sql_execute on dev-db was rejected in Slack');
    expect(rows().map((r) => r.getAttribute('data-ap-item'))).toEqual([K8S.id, NEW.id, HTTP.id]);
    expect(detail()?.getAttribute('data-state')).toBe('rejected');
    expect(text(detail()?.querySelector('.gw-badge'))).toBe('Rejected');
  });

  it('a countdown that ends here: Expired in place, and the count and the queue’s name follow', async () => {
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { el, rows, detail, button, settle } = await open();
    // The queued request's last minute is read out once, assertively (the detail's copy of the timer stays silent).
    now.set(Date.parse(K8S.expiresAt) - 30_000);
    await settle();
    expect(announce.mock.calls.filter(([t]) => t.includes('less than a minute'))).toEqual([
      ['k8s_rollout_restart on dev-cluster is denied automatically in less than a minute', { assertive: true }],
    ]);
    now.set(Date.parse(K8S.expiresAt) + 1000);
    await settle();
    expect(rows()[0].getAttribute('data-state')).toBe('expired');
    expect(detail()?.getAttribute('data-state')).toBe('expired');
    expect(button('Approve and run').getAttribute('aria-disabled')).toBe('true');
    expect(text(el.querySelector('#ap-pending-count'))).toBe('2');
    expect(el.querySelector('#apq-list')?.getAttribute('aria-label')).toBe('Waiting for approval, 2 requests, most urgent first');
  });

  it('#ap-panel-history (the kit’s tab link) opens History', async () => {
    const { el } = await open('/approvals#ap-panel-history');
    expect(el.querySelector('#ap-tab-history')?.getAttribute('aria-selected')).toBe('true');
    expect(el.querySelector('#ap-history-detail')).not.toBeNull();
  });

  it('History: decided requests open their call in Activity and the detail pane says so', async () => {
    const { el, button, settle } = await open();
    el.querySelector<HTMLButtonElement>('#ap-tab-history')!.click();
    await settle();
    const row = el.querySelector('#ap-panel-history tbody tr');
    expect(text(row)).toContain('k8s_apply on dev-cluster from Claude Desktop, rejected by you: open in Activity');
    expect(text(row?.querySelector('td'))).toBe('Rejected By you');
    expect(row?.querySelector('a')?.getAttribute('href')).toBe('/activity?q=tool:k8s_apply%20connection:dev-cluster');
    expect(text(el.querySelector('.gw-paginator__info'))).toBe('The only one in the last 24 hours');
    expect(el.querySelector('.gw-split__detail article')).toBeNull();
    expect(text(el.querySelector('#ap-history-detail h2'))).toBe('Decided requests open in Activity');
    button('Back to pending requests').click();
    await settle();
    expect(document.activeElement).toBe(el.querySelector('#ap-tab-pending'));
    expect(el.querySelector<HTMLElement>('#ap-panel-pending')!.hidden).toBe(false);
    expect(el.querySelector('.gw-split__detail article.gw-approval-request')).not.toBeNull();
  });

  it('the delivery card says nothing left the window, and the header says the channels are not available yet', async () => {
    const { el, button, settle } = await open();
    expect(text(el.querySelector('.gw-page-header__meta'))).toBe(
      'Sent to Notification channels not available yet Notification settings You decide here Approval rules in Policies',
    );
    button('How this request was delivered').click();
    await settle();
    const card = el.querySelector<HTMLElement>(`#ap-delivery-${K8S.id}`)!;
    expect(card.hidden).toBe(false);
    expect(text(card)).toBe(
      'Not sent outside the window No notification channel sent this request, so it waited here, in GateRook. Desktop notifications, Slack and Telegram are set up in Notification settings.',
    );
  });

  it('with Settings › Notifications: the kit’s line and a Slack preview of the request', async () => {
    core.notifications = {
      channels: [
        { kind: 'desktop', enabled: true, config: {}, state: null, stateDetail: null, lastDeliveryAt: null, version: 1 },
        { kind: 'slack', enabled: true, config: { channel: '#deploy-approvals' }, state: 'ok', stateDetail: null, lastDeliveryAt: null, version: 1 },
        { kind: 'telegram', enabled: false, config: {}, state: null, stateDetail: null, lastDeliveryAt: null, version: 1 },
      ],
      links: [],
    };
    const k8s = core.details.get(K8S.id)!;
    core.details.set(K8S.id, {
      ...k8s,
      channels: { desktop: 'sent', slack: 'sent', telegram: 'off' },
      timeline: [...k8s.timeline, { id: 2, at: '2026-10-22T14:25:49+02:00', kind: 'notified', detail: { channel: 'slack', state: 'sent' } }],
    });
    const { el } = await open();
    expect(text(el.querySelector('.gw-page-header__meta'))).toBe(
      'Sent to Desktop on Slack #deploy-approvals Telegram not set up Notification settings You decide, here or from Slack Approval rules in Policies',
    );
    const card = el.querySelector(`#ap-delivery-${K8S.id}`)!;
    expect(text(card.querySelector('h3'))).toBe('S Slack');
    expect(text(card.querySelector('.gw-card__description'))).toContain('Posted to #deploy-approvals at 14:29:13');
    expect(card.querySelector('figure.gw-approval-notification__preview')).not.toBeNull();
  });

  it('a deep link to a request that doesn’t exist says so, focused', async () => {
    const { el } = await open('/approvals/ap_gone00000000');
    const title = el.querySelector('.gw-split__detail h2');
    expect(text(title)).toBe('Request not found');
    expect(document.activeElement).toBe(title);
  });

  it('loading, then an error with Try again, then the data', async () => {
    core.listFails = new ApiError('server', 500, { title: 'The core failed', detail: 'See the core log.' });
    const { el, button, settle } = await open();
    expect(text(el.querySelector('.gw-alert--danger .gw-alert__title'))).toBe("Couldn't load the approval requests");
    expect(el.querySelector('#apq-list')).toBeNull();
    core.listFails = null;
    button('Try again').click();
    await settle();
    expect(el.querySelector('.gw-alert--danger')).toBeNull();
    expect(el.querySelectorAll('#apq-list > li')).toHaveLength(3);
  });

  it('shows skeletons, not invented rows, while the first answer is on its way', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const api = TestBed.inject(ApprovalsApi);
    const list = vi.mocked(api.list);
    const real = list.getMockImplementation()!;
    list.mockImplementation(async (status, cursor, limit) => {
      await gate;
      return real(status, cursor, limit);
    });
    const { el, settle } = await open();
    expect(el.querySelector('#ap-panel-pending [aria-busy=true] [role=status]')?.textContent).toBe('Loading approval requests');
    expect(el.querySelectorAll('.gw-skeleton').length).toBeGreaterThan(0);
    expect(el.querySelector('#ap-pending-count')).toBeNull();
    release();
    await settle();
    expect(el.querySelectorAll('.gw-skeleton')).toHaveLength(0);
  });
});

/** A detail for an item added during a test. */
function detail_(i: ApprovalItem): ApprovalDetail {
  return detail(i, 'DEL session:42');
}
