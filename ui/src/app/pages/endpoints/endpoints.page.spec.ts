import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { ConnectionsApi } from '../../core/api/connections.api';
import { EndpointStart, EndpointSummary, EndpointsApi, TokensApi } from '../../core/api/endpoints.api';
import { Template, TemplatesApi } from '../../core/api/templates.api';
import { OAuthApi } from '../../core/api/tunnel.api';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { TunnelStore } from '../../core/stores/tunnel.store';
import { clockTime, relativeTime } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { NOW, TUNNEL_OFF, at, kitConnections, kitEndpoints, kitTokens } from './endpoints.fixtures';
import { EndpointsPage, SKELETON_DELAY_MS } from './endpoints.page';

/** The page against fake admin API services: what it shows for each answer, and what it asks the core to do. */

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (t) => (t ? relativeTime(t, NOW) : ''),
  absolute: (t) => (t ? `abs ${t}` : ''),
  iso: (t) => t ?? '',
  clock: (t) => (t ? clockTime(t, false) : ''),
};

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

const RUNBOOKS: Template[] = [
  { id: 't1', kind: 'ssh-runbook', name: 'prod-vm', connection: 'prod-vm', version: 1, status: 'active', catalogRef: null, natures: { read: 1, mutation: 1 }, updatedAt: at(20, '10:00:00'), verified: true },
];

let calls: string[];
let events: FakeEvents;
let fragment: BehaviorSubject<string | null>;
let rows: EndpointSummary[];
let listAnswer: () => Promise<EndpointSummary[]>;
let createAnswer: () => Promise<EndpointSummary>;
let duplicateAnswer: (slug: string, newSlug: string) => Promise<EndpointSummary>;
let deleteAnswer: () => Promise<{ clients: string[]; tokensRevoked: string[]; rejectedApprovals: number }>;
let keepAnswer: (confirm: string | undefined) => Promise<EndpointSummary>;
/** `GET /api/endpoint-starts` (step API); null: a core without it. */
let startsAnswer: (() => Promise<EndpointStart[]>) | null = null;
afterEach(() => (startsAnswer = null));

const byslug = (slug: string) => rows.find((r) => r.slug === slug)!;

function setup(list: EndpointSummary[] = kitEndpoints()): void {
  calls = [];
  events = new FakeEvents();
  fragment = new BehaviorSubject<string | null>(null);
  rows = list;
  listAnswer = () => Promise.resolve(rows);
  createAnswer = () => Promise.resolve({ ...kitEndpoints()[0], slug: 'payments-dev' });
  duplicateAnswer = (slug, newSlug) => {
    const copy = { ...byslug(slug), slug: newSlug, url: `http://127.0.0.1:7465/mcp/${newSlug}` };
    const i = rows.findIndex((r) => r.slug === slug);
    rows = [...rows.slice(0, i + 1), copy, ...rows.slice(i + 1)];
    return Promise.resolve(copy);
  };
  deleteAnswer = () => Promise.resolve({ clients: ['claude-desktop', 'codex'], tokensRevoked: ['inspector-debug', 'codex-laptop'], rejectedApprovals: 3 });
  keepAnswer = () => Promise.resolve({ ...byslug('dev'), blockedReason: null });
  const endpoints: Partial<Record<keyof EndpointsApi, unknown>> = {
    list: () => {
      calls.push('list');
      return listAnswer();
    },
    create: (r: { slug: string; description: string | null; startFrom: string }) => {
      calls.push(`create ${r.slug} ${r.description ?? '-'} ${r.startFrom}`);
      return createAnswer();
    },
    duplicate: (slug: string, newSlug: string) => {
      calls.push(`duplicate ${slug} ${newSlug}`);
      return duplicateAnswer(slug, newSlug);
    },
    disable: (slug: string) => {
      calls.push(`disable ${slug}`);
      rows = rows.map((r) => (r.slug === slug ? { ...r, status: 'disabled' } : r));
      return Promise.resolve(byslug(slug));
    },
    enable: (slug: string) => {
      calls.push(`enable ${slug}`);
      rows = rows.map((r) => (r.slug === slug ? { ...r, status: 'running' } : r));
      return Promise.resolve(byslug(slug));
    },
    delete: (slug: string, confirm: string) => {
      calls.push(`delete ${slug} ${confirm}`);
      return deleteAnswer();
    },
    confirmIntegrity: (slug: string, body: { confirm?: string }) => {
      calls.push(`keep ${slug} ${body.confirm ?? '-'}`);
      return keepAnswer(body.confirm);
    },
    restoreRisk: (slug: string) => {
      calls.push(`restore ${slug}`);
      return Promise.resolve(byslug(slug).risk);
    },
    ...(startsAnswer ? { starts: () => (calls.push('starts'), startsAnswer!()) } : {}),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      { provide: ActivatedRoute, useValue: { fragment } },
      { provide: TimeService, useValue: fakeTime },
      { provide: EndpointsApi, useValue: endpoints },
      { provide: TokensApi, useValue: { list: (slug: string) => Promise.resolve(kitTokens()[slug] ?? []) } },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: kitConnections(), facets: { health: {}, env: {}, type: {} }, total: 12 }) } },
      { provide: TemplatesApi, useValue: { list: () => Promise.resolve(RUNBOOKS) } },
      {
        provide: OAuthApi,
        useValue: {
          grants: () =>
            Promise.resolve([{ id: 'og_1', client: 'claude.ai', endpoint: 'stage-readonly', ingress: 'TUNNEL', createdAt: at(22, '08:52:00'), expiresAt: at(29, '08:52:00'), lastUsedAt: null, state: 'active' }]),
        },
      },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0), address: signal('127.0.0.1:7465') } },
      { provide: TunnelStore, useValue: { start: () => undefined, available: signal(true), state: signal(TUNNEL_OFF) } },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
  vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
  localStorage.clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function render(): Promise<{ fixture: ComponentFixture<EndpointsPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(EndpointsPage);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const card = (el: HTMLElement, slug: string) => el.querySelector(`#ep-${slug}-title`)!.closest('article')!;
/** The value of one kv row of a card, by its key. */
const kv = (article: Element, key: string) => {
  const row = Array.from(article.querySelectorAll('.gw-kv__row')).find((r) => text(r.querySelector('dt')) === key);
  return row ? text(row.querySelector('dd')) : null;
};
const toasts = () => TestBed.inject(ToastService).toasts();
const type = (input: HTMLInputElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event('input'));
};
const click = (el: HTMLElement, selector: string) => el.querySelector<HTMLElement>(selector)!.click();

describe('Endpoints page', () => {
  it('shows nothing for a moment, then skeleton cards, then the endpoints', async () => {
    setup();
    const answer = deferred<EndpointSummary[]>();
    listAnswer = () => answer.promise;
    const { fixture, el } = await render();
    expect(text(el.querySelector('h1'))).toBe('Endpoints');
    expect(el.querySelector('.gw-skeleton')).toBeNull();

    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelectorAll('.gw-card .gw-skeleton').length).toBe(8);
    expect(el.querySelector('[aria-busy="true"] [role="status"]')?.textContent).toBe('Loading endpoints');

    answer.resolve(kitEndpoints());
    await settle(fixture);
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(el.querySelectorAll('ul[aria-label="Endpoints"] > li > article.gw-card')).toHaveLength(3);
  });

  it('renders the kit’s cards from the core’s endpoints', async () => {
    setup();
    const { el } = await render();
    const tenPast12 = clockTime(at(22, '12:10:00'), false);
    expect(text(el.querySelector('.gw-page-header__meta'))).toBe(`3 endpoints All running 1 published for claude.ai · tunnel off since ${tenPast12}`);
    expect(el.querySelector('.gw-page-header__meta time')?.getAttribute('datetime')).toBe(at(22, '12:10:00'));
    expect(el.querySelectorAll('.gw-page-header .gw-btn--primary')).toHaveLength(1);

    const dev = card(el, 'dev');
    expect(dev.getAttribute('aria-labelledby')).toBe('ep-dev-title');
    expect(text(dev.querySelector('.gw-card__title'))).toBe('Endpoint dev');
    expect(dev.querySelector('.gw-card__title a')?.getAttribute('href')).toBe('/endpoints/dev');
    expect(text(dev.querySelector('.gw-card__description'))).toBe('Local development. Agents read freely; anything that changes data waits for your approval.');
    expect(text(dev.querySelector('#ep-dev-status'))).toBe('Running');
    expect(kv(dev, 'URL')).toBe('http://127.0.0.1:7465/mcp/dev Copy URL of endpoint dev Copied');
    expect(kv(dev, 'Connections')).toBe('8 · 6 in dev 2 in stage');
    expect(kv(dev, 'Connection health')).toBe('6 healthy cache-redis slow · 1.8 s billing-api unreachable');
    expect(kv(dev, 'Tools')).toBe('22 · 14 read only 3 approval 5 off');
    expect(kv(dev, 'Clients')).toBe('CD Claude Desktop Cx Codex');
    expect(kv(dev, 'Tokens')).toBe('2 active · 1 expired 1 expires in 3 days');
    expect(kv(dev, 'Approvals')).toBe('3 waiting for approval on dev');
    expect(dev.querySelector('.gw-kv__row a[href="/approvals"]')).not.toBeNull();
    const devRisk = dev.querySelector('.gw-risk-callout')!;
    expect(devRisk.className).toContain('gw-alert--warning');
    expect(devRisk.className).toContain('gw-risk-callout--compact');
    expect(text(devRisk)).toBe('Risky combination: private data + untrusted content + external channel through http_request Review the risk in endpoint dev');
    expect(devRisk.querySelector('a')?.getAttribute('href')).toBe('/endpoints/dev#ep-risk');
    expect(Array.from(dev.querySelectorAll('.gw-card__footer a')).map((a) => a.getAttribute('href'))).toEqual([
      '/activity?q=endpoint:dev',
      '/playground?endpoint=dev',
      '/endpoints/dev',
    ]);
    const menu = el.querySelector('#ep-dev-menu')!;
    expect(menu.getAttribute('aria-label')).toBe('Actions for endpoint dev');
    expect(Array.from(menu.querySelectorAll('[role="menuitem"]')).map(text)).toEqual(['Duplicate', 'Disable', 'Delete endpoint']);

    const stage = card(el, 'stage-readonly');
    expect(text(stage.querySelector('.gw-card__actions .gw-badge'))).toBe('Published for claude.ai');
    expect(kv(stage, 'Clients')).toBe(`ai claude.ai Tunnel off since ${tenPast12} Tunnel settings`);
    expect(kv(stage, 'Tokens')).toBe('1 OAuth sign-in you allowed · Manage the OAuth sign-ins to stage-readonly');
    expect(kv(stage, 'Approvals')).toBe('None waiting');
    expect(text(stage.querySelector('.gw-risk-callout .gw-alert__body'))).toBe(
      'private data + untrusted content + external channel through http_get, published to the internet',
    );

    const prod = card(el, 'incident-prod');
    expect(kv(prod, 'Tokens')).toBe('None · the Claude Desktop bridge gets short-lived ones');
    expect(kv(prod, 'Connection health')).toBe('3 healthy');
    const contained = prod.querySelector('.gw-risk-callout')!;
    expect(contained.className).toContain('gw-alert--info');
    expect(text(contained)).toBe('Contained: private data + untrusted content + limited channel (2 fixed runbooks) Review the contained risk in endpoint incident-prod');
  });

  it('follows the core’s events: approvals re-read the counters, health changes at once', async () => {
    setup();
    const { fixture, el } = await render();
    calls = [];
    rows = rows.map((r) => (r.slug === 'incident-prod' ? { ...r, approvalsWaiting: 1 } : r));
    events.emit('approval.requested', { id: 'ap_1', endpoint: 'incident-prod' });
    await settle(fixture);
    expect(calls).toEqual(['list']);
    expect(kv(card(el, 'incident-prod'), 'Approvals')).toBe('1 waiting for approval on incident-prod');

    events.emit('connection.health', { name: 'billing-api', env: 'stage', state: 'healthy', latencyMs: 80, message: null, checkedAt: at(22, '14:31:00') });
    await settle(fixture);
    expect(kv(card(el, 'stage-readonly'), 'Connection health')).toBe('3 healthy');
  });

  it('shows the kit’s empty state, whose Create endpoint opens the dialog', async () => {
    setup([]);
    const { fixture, el } = await render();
    expect(el.querySelector('.gw-page-header__meta')).toBeNull();
    const empty = el.querySelector('section.gw-card .gw-empty')!;
    expect(text(empty.querySelector('.gw-empty__title'))).toBe('No endpoints');
    const button = empty.querySelector<HTMLButtonElement>('.gw-empty__actions .gw-btn')!;
    expect(button.className).not.toContain('gw-btn--primary');
    button.click();
    await settle(fixture);
    expect(el.querySelector<HTMLDialogElement>('#ep-create')!.open).toBe(true);
    expect(document.activeElement).toBe(el.querySelector('#ep-create-slug'));
  });

  it('says when the list didn’t load, and when it may be out of date', async () => {
    setup();
    listAnswer = () => Promise.reject(new ApiError('server', 500, { title: 'Server error', detail: 'The endpoint list could not be read.' }));
    const { fixture, el } = await render();
    const alert = el.querySelector('.gw-alert--danger')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The endpoints didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The endpoint list could not be read.');
    expect(el.querySelector('#ep-error-details')?.hasAttribute('hidden')).toBe(true);

    listAnswer = () => Promise.resolve(rows);
    click(alert as HTMLElement, '.gw-btn');
    await settle(fixture);
    expect(el.querySelector('.gw-alert--danger')).toBeNull();
    expect(el.querySelectorAll('article.gw-card')).toHaveLength(3);

    listAnswer = () => Promise.reject(new ApiError('network', 0, {}));
    events.emit('approval.resolved', { id: 'ap_1', status: 'approved', via: 'app' });
    await settle(fixture);
    expect(text(el.querySelector('.gw-alert--warning .gw-alert__title'))).toBe('This list may be out of date.');
    expect(el.querySelectorAll('article.gw-card')).toHaveLength(3);
  });

  it("says how many tools each way to start turns on, as the core would start it now (step API)", async () => {
    startsAnswer = () =>
      Promise.resolve([
        { startFrom: 'empty', env: 'dev', access: 'standard', connections: [], tools: { read: 0, approval: 0, off: 0 } },
        { startFrom: 'read-only-dev', env: 'dev', access: 'read-only', connections: ['a', 'b', 'c', 'd', 'e', 'f'], tools: { read: 12, approval: 0, off: 9 } },
        { startFrom: 'incident-response', env: 'prod', access: 'standard', connections: ['p', 'q', 'r'], tools: { read: 7, approval: 0, off: 3 } },
      ]);
    setup();
    const { fixture, el } = await render();
    click(el, '#ep-create-open');
    await settle(fixture);
    expect(calls).toContain('starts');
    expect(Array.from(el.querySelectorAll('#ep-create .gw-choice-card__meta')).map(text)).toEqual([
      'Start from scratch',
      '6 dev connections · 12 tools',
      '3 prod connections · stricter prod limits',
    ]);
  });

  it('creates an endpoint: slug first, the URL as it is typed, errors after a try, then its page', async () => {
    setup();
    const { fixture, el } = await render();
    click(el, '#ep-create-open');
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#ep-create')!;
    expect(dialog.open).toBe(true);
    const slug = el.querySelector<HTMLInputElement>('#ep-create-slug')!;
    expect(document.activeElement).toBe(slug);
    expect(slug.required).toBe(true);
    expect(slug.maxLength).toBe(40);
    expect(text(dialog.querySelector('#ep-create-url'))).toBe('URL: http://127.0.0.1:7465/mcp/your-slug');
    expect(Array.from(dialog.querySelectorAll('.gw-choice-card__meta')).map(text)).toEqual([
      'Start from scratch',
      '6 dev connections',
      '3 prod connections · stricter prod limits',
    ]);

    // Nothing typed: no error yet; a try shows it at the field, with the focus there.
    expect(dialog.querySelector('.gw-field__error')).toBeNull();
    click(dialog, 'button[type="submit"]');
    await settle(fixture);
    expect(text(dialog.querySelector('#ep-create-slug-err'))).toBe('Enter a slug. It becomes the end of the endpoint URL.');
    expect(slug.getAttribute('aria-invalid')).toBe('true');
    expect(slug.getAttribute('aria-describedby')).toBe('ep-create-slug-err ep-create-url ep-create-slug-hint');
    expect(document.activeElement).toBe(slug);
    expect(calls.some((c) => c.startsWith('create'))).toBe(false);

    type(slug, 'Payments_Dev');
    await settle(fixture);
    expect(text(dialog.querySelector('#ep-create-url'))).toBe('URL: http://127.0.0.1:7465/mcp/Payments_Dev');
    expect(text(dialog.querySelector('#ep-create-slug-err'))).toMatch(/^Use lowercase letters/);

    // The core refuses a taken slug: its words at the field, the dialog stays.
    type(slug, 'dev');
    createAnswer = () => Promise.reject(new ApiError('conflict', 409, { title: 'Slug in use', detail: 'An endpoint /mcp/dev already exists.' }));
    click(dialog, 'button[type="submit"]');
    await settle(fixture);
    expect(calls).toContain('create dev - empty');
    expect(text(dialog.querySelector('#ep-create-slug-err'))).toBe('An endpoint /mcp/dev already exists.');
    expect(dialog.open).toBe(true);

    type(slug, 'payments-dev');
    type(el.querySelector<HTMLInputElement>('#ep-create-desc-input')!, 'Payments service on dev');
    el.querySelector<HTMLInputElement>('input[name="ep-create-start"][value="incident-response"]')!.click();
    createAnswer = () => Promise.resolve({ ...kitEndpoints()[0], slug: 'payments-dev' });
    await settle(fixture);
    expect(dialog.querySelector('.gw-field__error')).toBeNull();
    click(dialog, 'button[type="submit"]');
    await settle(fixture);
    expect(calls).toContain('create payments-dev Payments service on dev incident-response');
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'Endpoint payments-dev created', body: 'Check its connections and tool modes next.' });
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/endpoints', 'payments-dev']);
  });

  it('shows a refusal that names no field in the dialog, and resets the form on close', async () => {
    setup();
    const { fixture, el } = await render();
    click(el, '#ep-create-open');
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#ep-create')!;
    type(el.querySelector<HTMLInputElement>('#ep-create-slug')!, 'payments');
    createAnswer = () => Promise.reject(new ApiError('server', 500, { title: 'Server error', detail: 'The audit is unavailable.' }));
    click(dialog, 'button[type="submit"]');
    await settle(fixture);
    const alert = dialog.querySelector('[role="alert"]')!;
    expect(text(alert)).toBe("The endpoint wasn't created The audit is unavailable.");
    expect(document.activeElement).toBe(alert);

    click(dialog, '.gw-dialog__footer .gw-btn:not(.gw-btn--primary)');
    await settle(fixture);
    expect(dialog.open).toBe(false);
    expect(el.querySelector<HTMLInputElement>('#ep-create-slug')!.value).toBe('');
    expect(dialog.querySelector('[role="alert"]')).toBeNull();
  });

  it('opens Create from /endpoints#create, and drops the fragment when it closes', async () => {
    setup();
    const { fixture, el } = await render();
    fragment.next('create');
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#ep-create')!;
    expect(dialog.open).toBe(true);
    dialog.close();
    await settle(fixture);
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith([], { fragment: undefined, replaceUrl: true });
  });

  it('duplicates with a suggested slug, and the copy shows after its source', async () => {
    setup();
    const { fixture, el } = await render();
    click(el, '#ep-dev-menu .gw-menu__item:nth-of-type(1)');
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#ep-duplicate')!;
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('#ep-dup-title'))).toBe('Duplicate endpoint dev');
    const slug = dialog.querySelector<HTMLInputElement>('#ep-dup-slug')!;
    expect(slug.value).toBe('dev-copy');
    expect(document.activeElement).toBe(slug);
    expect(text(dialog.querySelector('#ep-dup-url'))).toBe('URL: http://127.0.0.1:7465/mcp/dev-copy');
    expect(text(dialog.querySelector('button[type="submit"]'))).toBe('Duplicate endpoint dev');

    click(dialog, 'button[type="submit"]');
    await settle(fixture);
    expect(calls).toContain('duplicate dev dev-copy');
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)).toMatchObject({ title: 'Endpoint dev-copy created', body: 'A copy of dev without tokens or client links.' });
    expect(Array.from(el.querySelectorAll('.gw-card__title a')).map(text)).toEqual([
      'Endpoint dev',
      'Endpoint dev-copy',
      'Endpoint stage-readonly',
      'Endpoint incident-prod',
    ]);
  });

  it('disables at once with Undo in a toast', async () => {
    setup();
    const { fixture, el } = await render();
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    click(el, '#ep-dev-menu .gw-menu__item:nth-of-type(2)');
    await settle(fixture);
    expect(calls).toContain('disable dev');
    expect(text(el.querySelector('#ep-dev-status'))).toBe('Disabled');
    expect(el.querySelector('#ep-dev-status')?.className).toContain('gw-status--neutral');
    expect(text(el.querySelector('.gw-page-header__meta'))).toContain('2 running 1 disabled');
    const toast = toasts().at(-1)!;
    expect(toast).toMatchObject({ tone: 'neutral', title: 'Endpoint dev disabled', body: 'Claude Desktop and Codex get an error on their next call. Tokens and settings are kept.' });
    // The menu offers Enable now.
    expect(Array.from(el.querySelectorAll('#ep-dev-menu [role="menuitem"]')).map(text)).toEqual(['Duplicate', 'Enable', 'Delete endpoint']);

    toast.actions[0].onClick!();
    await settle(fixture);
    expect(calls).toContain('enable dev');
    expect(text(el.querySelector('#ep-dev-status'))).toBe('Running');
    expect(announce).toHaveBeenCalledWith('Endpoint dev is running again');
  });

  it('deletes after a danger confirmation that says what stops working', async () => {
    setup();
    const { fixture, el } = await render();
    click(el, '#ep-dev-menu .gw-menu__item--danger');
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#ep-delete')!;
    expect(dialog.open).toBe(true);
    expect(dialog.className).toContain('gw-dialog--danger');
    expect(text(dialog.querySelector('#ep-del-title'))).toBe('Delete endpoint dev?');
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text(dialog.querySelector('#ep-del-desc'))).toBe(
      'Claude Desktop and Codex lose access at once, its tokens are revoked and the 3 requests waiting for approval are denied. Connections, their secrets and the audit log stay.',
    );
    expect(Array.from(dialog.querySelectorAll('.gw-kv__row')).map((r) => `${text(r.querySelector('dt'))}: ${text(r.querySelector('dd'))}`)).toEqual([
      'URL: http://127.0.0.1:7465/mcp/dev',
      'Clients: Claude Desktop, Codex',
      'Tokens: codex-laptop, inspector-debug (revoked)',
      'Waiting: 3 approval requests (denied)',
    ]);

    // Refused: the dialog stays with the core's words.
    deleteAnswer = () => Promise.reject(new ApiError('unprocessable', 422, { detail: 'Type dev to confirm.' }));
    click(dialog, '.gw-btn--danger');
    await settle(fixture);
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('[role="alert"]'))).toBe("dev wasn't deleted Type dev to confirm.");

    deleteAnswer = () => Promise.resolve({ clients: ['claude-desktop', 'codex'], tokensRevoked: ['inspector-debug', 'codex-laptop'], rejectedApprovals: 3 });
    rows = rows.filter((r) => r.slug !== 'dev');
    click(dialog, '.gw-btn--danger');
    await settle(fixture);
    expect(calls.filter((c) => c === 'delete dev dev')).toHaveLength(2);
    expect(dialog.open).toBe(false);
    expect(el.querySelector('#ep-dev-title')).toBeNull();
    expect(toasts().at(-1)).toMatchObject({
      tone: 'success',
      title: 'Endpoint dev deleted',
      body: 'Its URL stops answering. The audit log keeps every call. 2 tokens revoked, 3 waiting requests denied.',
    });
    // The card that took its place has the keyboard.
    expect(document.activeElement).toBe(el.querySelector('#ep-stage-readonly-link'));
  });

  it('keeps an endpoint changed outside GateRook, resetting what the core asks to', async () => {
    setup(kitEndpoints().map((e) => (e.slug === 'dev' ? { ...e, blockedReason: 'tool sql_execute' } : e)));
    const { fixture, el } = await render();
    const dev = card(el, 'dev');
    expect(text(dev.querySelector('#ep-dev-status'))).toBe('Needs review');
    expect(text(dev.querySelector('.gw-alert--warning:not(.gw-risk-callout) .gw-alert__body'))).toBe(
      'The tool sql_execute was changed outside GateRook. It serves nothing until you review it.',
    );
    const duplicate = el.querySelector<HTMLButtonElement>('#ep-dev-menu .gw-menu__item:nth-of-type(1)')!;
    expect(duplicate.getAttribute('aria-disabled')).toBe('true');
    expect(text(duplicate)).toBe('DuplicateReview the change first');
    duplicate.click();
    await settle(fixture);
    expect(el.querySelector<HTMLDialogElement>('#ep-duplicate')!.open).toBe(false);

    click(dev, '.gw-alert__actions button');
    await settle(fixture);
    const dialog = el.querySelector<HTMLDialogElement>('#ep-keep')!;
    expect(dialog.open).toBe(true);
    expect(dialog.className).toContain('gw-dialog--warning');
    expect(text(document.activeElement)).toBe('Cancel');

    keepAnswer = (confirm) =>
      confirm
        ? Promise.resolve({ ...byslug('dev'), blockedReason: null })
        : Promise.reject(new ApiError('unprocessable', 422, { title: 'Invalid values', detail: 'Limits that an ordinary change could not store are reset to the defaults.', confirm: 'reset-invalid' }));
    click(dialog, '.gw-btn--primary');
    await settle(fixture);
    expect(calls).toContain('keep dev -');
    expect(text(dialog.querySelector('[role="alert"] .gw-alert__title'))).toBe('Some values are reset when you keep it');
    expect(text(dialog.querySelector('.gw-btn--primary'))).toBe('Reset and keep dev');

    rows = rows.map((r) => ({ ...r, blockedReason: null }));
    click(dialog, '.gw-btn--primary');
    await settle(fixture);
    expect(calls).toContain('keep dev reset-invalid');
    expect(dialog.open).toBe(false);
    expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'Endpoint dev is in use again' });
    expect(text(el.querySelector('#ep-dev-status'))).toBe('Running');
  });
});
