import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ActivityApi } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ClientsApi, ConnectRequest, ConnectResult } from '../../core/api/clients.api';
import { ConnectionsApi } from '../../core/api/connections.api';
import { ConnectorTypesApi } from '../../core/api/connector-types.api';
import { EndpointDetail, EndpointSettings, EndpointSettingsUpdate, EndpointsApi, TokensApi } from '../../core/api/endpoints.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { SettingsApi } from '../../core/api/settings.api';
import { OAuthApi, OAuthGrant } from '../../core/api/tunnel.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { TimeService } from '../../core/time/time.service';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { connected, kitClients, previewFor } from '../clients/clients.fixtures';
import { EndpointDetailPage, SKELETON_DELAY_MS } from './endpoint-detail.page';
import { EndpointDetailData } from './endpoint-detail.data';
import { NOW, clientsView, connectionRow, connectionRows, detail, groups, policies, preview, settings, summary, tokens, wired } from './endpoint-detail.fixtures';

/** The page against fake admin API services: what it shows for each answer, and what it asks the core to do. */

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (t) => (t ? `rel ${t.slice(11, 16)}` : ''),
  absolute: (t) => (t ? `abs ${t}` : ''),
  iso: (t) => t ?? '',
  date: (t) => (t ? `date ${t.slice(5, 10)}` : ''),
  clock: (t) => (t ? t.slice(11, 16) : ''),
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

let calls: string[];
let detailAnswers: (() => Promise<EndpointDetail>)[];
let settingsAnswer: (u: EndpointSettingsUpdate) => Promise<EndpointSettings>;
let rows = connectionRows();

function setup(
  first: () => Promise<EndpointDetail> = () => Promise.resolve(detail()),
  clientOptions?: { readonly api: Partial<ClientsApi>; readonly desktop: Partial<DesktopBridge> },
): void {
  calls = [];
  rows = connectionRows();
  detailAnswers = [first];
  settingsAnswer = (u) => Promise.resolve(settings({ instructions: u.instructions ?? null, limits: u.limits ?? {}, version: 9 }));
  const endpoints: Partial<EndpointsApi> = {
    get: (slug) => {
      calls.push(`get ${slug}`);
      const next = detailAnswers.length > 1 ? detailAnswers.shift()! : detailAnswers[0];
      return next();
    },
    tools: () => Promise.resolve(groups()),
    check: () => Promise.resolve({ tools: 18, revisions: 2, instructionsChars: 827, instructionsMax: 4000, gatewayP95Ms: 3, problems: [], text: '' }),
    disable: (slug) => {
      calls.push(`disable ${slug}`);
      return Promise.resolve(summary({ status: 'disabled' }));
    },
    enable: (slug) => {
      calls.push(`enable ${slug}`);
      return Promise.resolve(summary());
    },
    duplicate: (slug, newSlug) => {
      calls.push(`duplicate ${slug} ${newSlug}`);
      return Promise.resolve(summary({ slug: newSlug }));
    },
    delete: (slug, confirm) => {
      calls.push(`delete ${slug} ${confirm}`);
      return Promise.resolve({ clients: ['claude-desktop', 'codex'], tokensRevoked: ['codex-laptop'], rejectedApprovals: 3 });
    },
    previewConnection: (slug, name) =>
      Promise.resolve(name === 'prod-replica' ? preview(name, { refusal: 'prod-replica is a prod connection: it goes only on a prod endpoint, and dev is stage' }) : preview(name)),
    addConnection: (slug, name) => {
      calls.push(`add ${name}`);
      return Promise.resolve(detail());
    },
    removeConnection: (slug, name) => {
      calls.push(`remove ${name}`);
      return Promise.resolve({ stoppedTools: [] });
    },
    splitPreview: (slug, r) => {
      calls.push(`split-preview ${r.move.join()} ${r.newSlug}`);
      return Promise.reject(new ApiError('server', 500, {}));
    },
    confirmIntegrity: (slug) => {
      calls.push(`keep ${slug}`);
      return Promise.resolve(summary());
    },
    updateSettings: (slug, u) => {
      calls.push(`settings ${JSON.stringify(u)}`);
      return settingsAnswer(u);
    },
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'endpoints/:slug', component: EndpointDetailPage }], withComponentInputBinding()),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: EndpointsApi, useValue: endpoints },
      {
        provide: TokensApi,
        useValue: {
          list: () => Promise.resolve(tokens()),
          issue: (slug: string, r: { name: string }) => {
            calls.push(`issue ${r.name}`);
            return Promise.resolve({ id: 'tok_new', name: r.name, token: 'gwt_secret_value', prefix: 'gwt_secr', expiresAt: '2026-10-29T12:00:00.000Z' });
          },
        },
      },
      { provide: ClientsApi, useValue: clientOptions?.api ?? { list: () => Promise.resolve(clientsView()), disconnectPreview: (id: string) => (calls.push(`disconnect-preview ${id}`), Promise.reject(new Error('x'))) } },
      ...(clientOptions ? [{ provide: DesktopBridge, useValue: clientOptions.desktop }] : []),
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: rows, facets: {}, total: rows.length }) } },
      { provide: ConnectorTypesApi, useValue: { get: () => Promise.resolve({ tools: [] }) } },
      { provide: PoliciesApi, useValue: { all: () => Promise.resolve(policies()) } },
      { provide: SettingsApi, useValue: { get: () => Promise.resolve({ name: 'authorization', values: { clientRegistration: 'cimd' }, version: 0, updatedAt: null }) } },
      { provide: ActivityApi, useValue: { facets: () => Promise.resolve({}) } },
      { provide: OAuthApi, useValue: { grants: () => Promise.reject(new ApiError('not-available', 404, {})) } },
      { provide: EventStream, useValue: new FakeEvents() },
      { provide: CoreStore, useValue: { running: signal(true), instanceChanges: signal(0) } },
      {
        provide: EndpointsStore,
        useValue: { list: signal([summary(), summary({ slug: 'dev-copy' }), summary({ slug: 'incident-prod', env: 'prod' })]), load: () => Promise.resolve() },
      },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
  localStorage.clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function render(inputs: Record<string, string> = { slug: 'dev' }): Promise<{ fixture: ComponentFixture<EndpointDetailPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(EndpointDetailPage);
  for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
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
const kv = (row: Element) => `${text(row.querySelector('dt'))} ${text(row.querySelector('dd'))}`;
const toasts = () => TestBed.inject(ToastService).toasts();
const openDialog = () => document.querySelector('dialog[open]');
const button = (root: ParentNode, label: string) => Array.from(root.querySelectorAll<HTMLElement>('button, a')).find((b) => text(b) === label || text(b).startsWith(label))!;

async function cursorConnection(method: 'oauth' | 'token' = 'oauth') {
  const answer = deferred<ConnectResult>();
  const browserAnswer = deferred<{ link: string }>();
  const request: ConnectRequest = { endpoint: 'dev', method };
  const result = connected('cursor', request);
  const cursor = kitClients().clients.find((c) => c.client === 'cursor')!;
  let saved = false;
  const openExternal = vi.fn(async (_url: string) => undefined);
  setup(undefined, {
    api: {
      list: () => Promise.resolve({ ...clientsView(), clients: [{ ...cursor, links: saved ? [result.link] : [] }] }),
      connectPreview: (client, next) => {
        calls.push(`preview ${client} ${next.method}`);
        return Promise.resolve(previewFor(client, next));
      },
      browserSignIn: (client, endpoint) => {
        calls.push(`browser ${client} ${endpoint}`);
        return browserAnswer.promise;
      },
      connect: (client, previewId) => {
        calls.push(`connect ${client} ${previewId}`);
        return answer.promise.then((value) => {
          saved = true;
          return value;
        });
      },
    },
    desktop: { available: true, openExternal },
  });
  const { fixture, el } = await render({ slug: 'dev', tab: 'clients' });
  button(el.querySelector('#clients')!, 'Connect Cursor').click();
  await settle(fixture);
  const dialog = openDialog()!;
  if (method === 'oauth') {
    dialog.querySelector<HTMLInputElement>('input[value="oauth"]')!.click();
    await settle(fixture);
  }
  return { fixture, el, dialog, answer, browserAnswer, result, openExternal };
}

describe('Endpoint detail page', () => {
  it('retries a slightly early bypass expiry read with backoff and stops after the core confirms expiry', async () => {
    setup();
    const { fixture } = await render();
    const data = fixture.debugElement.injector.get(EndpointDetailData);
    const reload = vi.spyOn(data, 'reloadTools').mockResolvedValue(undefined);
    const before = now();
    const until = new Date(before).toISOString();
    const expired = groups().map((group, index) => index === 0
      ? { ...group, tools: group.tools.map((tool, toolIndex) => toolIndex === 0 ? { ...tool, bypassUntil: until, mode: 'BYPASS' as const } : tool) }
      : group);
    try {
      await data.applyTemporaryBypass(expired);
      await settle(fixture);
      expect(reload).toHaveBeenCalledTimes(1);
      now.set(before + 1000);
      await settle(fixture);
      expect(reload).toHaveBeenCalledTimes(1);
      now.set(before + 3000);
      await settle(fixture);
      expect(reload).toHaveBeenCalledTimes(2);
      await data.applyTemporaryBypass(groups());
      now.set(before + 30_000);
      await settle(fixture);
      expect(reload).toHaveBeenCalledTimes(2);
    } finally {
      now.set(before);
    }
  });

  it('manages an observed OAuth connection through its sign-in without requesting a config disconnect', async () => {
    const link = { ...connected('cursor', { endpoint: 'dev', method: 'oauth' }).link, id: 'og_cursor', configPath: null, configDisplay: null, entryName: null, lastCallAt: null };
    const cursor = kitClients().clients.find((c) => c.client === 'cursor')!;
    const disconnectPreview = vi.fn();
    setup(undefined, {
      api: { list: () => Promise.resolve({ ...clientsView(), clients: [{ ...cursor, status: 'connected', links: [link] }] }), disconnectPreview },
      desktop: { available: true },
    });
    const { el } = await render({ slug: 'dev', tab: 'clients' });
    const card = el.querySelector('li[gwClientCard]')!;
    expect(text(card.querySelector('.gw-status__label'))).toBe('Connected');
    expect(text(card)).toContain('Signed in through the browser');
    expect(text(card)).toContain('no call yet');
    expect(button(card, 'Disconnect')).toBeUndefined();
    expect(button(card, 'Open config')).toBeUndefined();
    expect(button(card, 'Manage sign-in').getAttribute('href')).toBe('/endpoints/dev?tab=tokens#ep-oauth-sessions');
    expect(disconnectPreview).not.toHaveBeenCalled();
  });

  describe('Cursor connection from AI apps', () => {
    it('starts OAuth in the browser without previewing or writing a config entry', async () => {
      const { fixture, el, dialog, browserAnswer, openExternal } = await cursorConnection();
      expect(calls).not.toContain('preview cursor oauth');
      expect(text(dialog)).not.toContain('What will change');
      dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      await settle(fixture);
      expect(calls).toContain('browser cursor dev');
      expect(calls.some((call) => call.startsWith('connect '))).toBe(false);
      expect(openExternal).not.toHaveBeenCalled();

      browserAnswer.resolve({ link: previewFor('cursor', { endpoint: 'dev', method: 'oauth' }).link! });
      await settle(fixture);
      expect(openExternal).toHaveBeenCalledExactlyOnceWith('https://cursor.com/install-mcp?name=gaterook-dev&config=eyJ1cmwiOiJodHRwOi8vMTI3LjAuMC4xOjc0NjUvbWNwL2RldiJ9');
      expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'Continue in Cursor' });
      expect(button(el.querySelector('#clients')!, 'Disconnect Cursor')).toBeUndefined();
    });

    it('does not open the browser when enabling OAuth fails', async () => {
      const { fixture, dialog, browserAnswer, openExternal } = await cursorConnection();
      dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      browserAnswer.reject(new ApiError('conflict', 409, { detail: 'The endpoint changed. Try again.' }));
      await settle(fixture);

      expect(openExternal).not.toHaveBeenCalled();
      expect(toasts().at(-1)).toMatchObject({ tone: 'danger', title: "Cursor browser sign-in wasn't opened", body: 'The endpoint changed. Try again.' });
      expect(calls.some((call) => call.startsWith('connect '))).toBe(false);
    });

    it('keeps token connections in the app', async () => {
      const { fixture, dialog, answer, result, openExternal } = await cursorConnection('token');
      dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      answer.resolve(result);
      await settle(fixture);

      expect(calls).toContain('connect cursor pv_cursor_dev_token');
      expect(openExternal).not.toHaveBeenCalled();
      expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'Cursor connected to dev' });
    });

    it('reports a browser launch failure without creating a managed connection', async () => {
      const { fixture, el, dialog, browserAnswer, openExternal } = await cursorConnection();
      openExternal.mockRejectedValueOnce(new Error('The browser could not be started.'));
      dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      browserAnswer.resolve({ link: previewFor('cursor', { endpoint: 'dev', method: 'oauth' }).link! });
      await settle(fixture);

      expect(openExternal).toHaveBeenCalledOnce();
      expect(toasts().at(-1)).toMatchObject({ tone: 'danger', title: "Cursor browser sign-in wasn't opened", body: 'The browser could not be started.' });
      expect(text(el.querySelector('#clients'))).not.toContain("Cursor wasn't connected");
      expect(button(el.querySelector('#clients')!, 'Disconnect Cursor')).toBeUndefined();
      expect(calls.some((call) => call.startsWith('connect '))).toBe(false);
    });
  });

  it('shows nothing for a moment, then placeholders, then the endpoint', async () => {
    const answer = deferred<EndpointDetail>();
    setup(() => answer.promise);
    const { fixture, el } = await render();
    expect(text(el.querySelector('h1'))).toBe('Endpoint dev');
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelectorAll('.gw-skeleton').length).toBeGreaterThan(0);
    expect(text(el.querySelector('[role="status"]'))).toBe('Loading endpoint dev');
    answer.resolve(detail());
    await settle(fixture);
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(el.querySelector('#ep-tabs')).not.toBeNull();
  });

  it('renders the kit’s header, wiring and tab counts from the core', async () => {
    setup();
    const { el } = await render();
    expect(text(el.querySelector('.gw-page-header__description'))).toBe(
      'Local development on 8 connections. Agents read freely; anything that changes data waits for your approval.',
    );
    expect(text(el.querySelector('#ep-status'))).toBe('Running');
    expect(text(el.querySelector('#ep-url'))).toBe('http://127.0.0.1:7465/mcp/dev');
    expect(text(el.querySelector('.gw-page-header__meta'))).toContain('MCP 2025-11-25 · 2026-07-28');
    expect(text(el.querySelector('#ep-signin'))).toBe('Tokens · OAuth off');
    expect(text(el.querySelector('a[href="/approvals"]'))).toBe('3 waiting for approval');
    expect(el.querySelector('a[href="/activity?q=endpoint:dev"]')).not.toBeNull();
    expect(el.querySelector('a[href="/playground?endpoint=dev"]')).not.toBeNull();
    // One primary per level: none in the header.
    expect(el.querySelectorAll('.gw-page-header .gw-btn--primary')).toHaveLength(0);

    expect(text(el.querySelector('#ep-map-title'))).toBe('How dev connects');
    expect(text(el.querySelector('.gw-setup-map--wiring'))).toContain('120 calls per min per token · 512 KB per result, from the dev policy');
    expect(Array.from(el.querySelectorAll('[role="tab"]')).map(text)).toEqual([
      'Tools & policies 22 tools',
      'Connections 8 connections',
      'Tokens 2 active tokens',
      'AI apps 2 connected',
      'Settings',
    ]);
    expect(el.querySelector('#tools')?.hasAttribute('hidden')).toBe(false);
    expect(el.querySelector('#connections')?.hasAttribute('hidden')).toBe(true);
    expect(text(el.querySelector('#ep-risk .gw-alert__title'))).toBe('Risky combination in endpoint dev');
  });

  it('opens the tab named in ?tab= and mirrors a picked tab in the URL', async () => {
    setup();
    const { fixture, el } = await render({ slug: 'dev', tab: 'tokens' });
    expect(el.querySelector('#tab-tokens')?.getAttribute('aria-selected')).toBe('true');
    expect(el.querySelector('#tokens')?.hasAttribute('hidden')).toBe(false);
    expect(text(el.querySelector('#tokens .gw-card__title'))).toBe('Access tokens');

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    el.querySelector<HTMLElement>('#tab-settings')!.click();
    await settle(fixture);
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { tab: 'settings' }, replaceUrl: true }));
    el.querySelector<HTMLElement>('#tab-tools')!.click();
    expect(navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { tab: null } }));

    fixture.componentRef.setInput('tab', 'clients');
    await settle(fixture);
    expect(el.querySelector('#clients')?.hasAttribute('hidden')).toBe(false);
    expect(text(el.querySelector('#ep-clients-title'))).toBe('AI apps using dev');
    expect(el.querySelectorAll('#clients li.gw-client-card')).toHaveLength(3);
  });

  it('says what failed and loads again with Try again', async () => {
    setup(() => Promise.reject(new ApiError('server', 500, { detail: 'The database is locked.' })));
    const { fixture, el } = await render();
    expect(text(el.querySelector('.gw-alert--danger .gw-alert__title'))).toBe("Endpoint dev didn't load");
    expect(text(el.querySelector('.gw-alert--danger .gw-alert__body'))).toBe('The database is locked.');
    detailAnswers = [() => Promise.resolve(detail())];
    button(el, 'Try again').click();
    await settle(fixture);
    expect(el.querySelector('#ep-tabs')).not.toBeNull();
  });

  it('says when there is no such endpoint', async () => {
    setup(() => Promise.reject(new ApiError('not-found', 404, { detail: 'No endpoint nope.' }, { detail: 'No endpoint nope.' })));
    const { el } = await render({ slug: 'nope' });
    expect(text(el.querySelector('.gw-empty__title'))).toBe('No endpoint named nope');
    expect(el.querySelector('.gw-empty a')?.getAttribute('href')).toBe('/endpoints');
  });

  it('disables at once and offers Undo', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#ep-menu')!, 'Disable').click();
    await settle(fixture);
    expect(calls).toContain('disable dev');
    expect(text(el.querySelector('#ep-status'))).toBe('Disabled');
    const t = toasts().at(-1)!;
    expect(t.title).toBe('Endpoint dev disabled');
    expect(t.body).toBe('Claude Desktop and Codex get an error on their next call. Tokens and settings are kept.');
    t.actions[0].onClick?.();
    await settle(fixture);
    expect(calls).toContain('enable dev');
    expect(text(el.querySelector('#ep-status'))).toBe('Running');
  });

  it('deletes after the confirmation, with what stops working, then goes to Endpoints', async () => {
    setup();
    const { fixture, el } = await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button(el.querySelector('#ep-menu')!, 'Delete endpoint').click();
    await settle(fixture);
    const dialog = el.querySelector('#ep-dlg-delete')!;
    expect(text(dialog.querySelector('#ep-del-desc'))).toBe(
      'Claude Desktop and Codex lose access at once, its tokens are revoked and the 3 requests waiting for approval are denied. Connections, their secrets and the audit log stay.',
    );
    expect(Array.from(dialog.querySelectorAll('.gw-kv__row')).map(kv)).toEqual([
      'URL http://127.0.0.1:7465/mcp/dev',
      'AI apps Claude Desktop, Codex',
      'Tokens codex-laptop, inspector-debug (revoked)',
      'Waiting 3 approval requests (denied)',
    ]);
    expect(dialog.querySelector('[autofocus]')?.textContent?.trim()).toBe('Cancel');
    button(dialog, 'Delete endpoint dev').click();
    await settle(fixture);
    expect(calls).toContain('delete dev dev');
    expect(toasts().at(-1)?.title).toBe('Endpoint dev deleted');
    expect(navigate).toHaveBeenCalledWith(['/endpoints']);
  });

  it('suggests a free slug for Duplicate and refuses one that is taken', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#ep-menu')!, 'Duplicate').click();
    await settle(fixture);
    const input = el.querySelector<HTMLInputElement>('#ep-dup-slug')!;
    expect(input.value).toBe('dev-copy-2');
    input.value = 'dev-copy';
    input.dispatchEvent(new Event('input'));
    await settle(fixture);
    el.querySelector<HTMLFormElement>('#ep-dlg-duplicate form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(text(el.querySelector('#ep-dup-err'))).toBe('An endpoint named dev-copy already exists. Pick another name.');
    expect(calls.some((c) => c.startsWith('duplicate'))).toBe(false);

    input.value = 'dev-two';
    input.dispatchEvent(new Event('input'));
    await settle(fixture);
    expect(text(el.querySelector('#ep-dup-url'))).toBe('URL: http://127.0.0.1:7465/mcp/dev-two');
    el.querySelector<HTMLFormElement>('#ep-dlg-duplicate form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(calls).toContain('duplicate dev dev-two');
    expect(toasts().at(-1)).toMatchObject({ title: 'Endpoint dev-two created', body: 'A copy of dev without tokens or AI app links.' });
  });

  it("says since when a connection is unreachable when the core tells (kit: \"can't reach … since 13:42\")", async () => {
    setup();
    rows = connectionRows().map((r) => (r.name === 'billing-api' ? { ...r, health: { ...r.health, since: '2026-10-22T11:42:00+02:00' } } : r));
    const { el } = await render({ slug: 'dev', tab: 'connections' });
    expect(text(el.querySelector('#connections .gw-alert--danger .gw-alert__body'))).toBe(
      "The gateway can't reach https://billing.stage.acme.dev since 11:42, so http_request calls on dev fail. Check the VPN or the host, then test again.",
    );
  });

  it('lists the connections with the unreachable one first and removes one after asking', async () => {
    setup();
    const { fixture, el } = await render({ slug: 'dev', tab: 'connections' });
    const panel = el.querySelector('#connections')!;
    expect(text(panel.querySelector('.gw-alert--danger .gw-alert__title'))).toBe('billing-api environment stage is unreachable');
    expect(text(panel.querySelector('.gw-alert--danger .gw-alert__body'))).toBe(
      "The gateway couldn't reach https://billing.stage.acme.dev at 11:42, so http_request calls on dev fail. Check the VPN or the host, then test again.",
    );
    expect(text(panel.querySelector('#ep-conn-title + .gw-card__description'))).toBe(
      '6 in dev, 2 in stage. Each keeps the policy of its own environment. Removing one takes its tools off dev; the connection and its secret stay.',
    );
    const first = panel.querySelector('tbody tr')!;
    expect(Array.from(first.children).map(text).slice(0, 5)).toEqual(['dev-db environment dev', 'PostgreSQL', 'dev-db.internal', '4 · 3 read only, 1 approval', 'Healthy · 12 ms']);

    button(first, 'Remove').click();
    await settle(fixture);
    expect(text(el.querySelector('#ep-rm-title'))).toBe('Remove dev-db from dev?');
    expect(text(el.querySelector('#ep-rm-desc'))).toBe('Its 4 tools disappear from dev on the next call, with their modes and limits. The connection, its secret and its audit history stay.');
    button(el.querySelector('#ep-dlg-remove')!, 'Remove dev-db from dev').click();
    await settle(fixture);
    expect(calls).toContain('remove dev-db');
    expect(toasts().at(-1)).toMatchObject({ title: 'dev-db removed from dev', body: 'Add it back any time with Add connection.' });
  });

  it('groups many unreachable connections into one alert', async () => {
    setup();
    rows = connectionRows().map((r) => ({ ...r, health: { ...r.health, state: 'unreachable' } }));
    const { el } = await render({ slug: 'dev', tab: 'connections' });
    const alerts = el.querySelectorAll('#connections .gw-alert--danger');
    expect(alerts).toHaveLength(1);
    expect(text(alerts[0].querySelector('.gw-alert__title'))).toBe('8 connections on dev are unreachable');
    expect(alerts[0].querySelector('a')?.getAttribute('href')).toBe('/connections?health=unreachable');
  });

  it('adds the picked connections and keeps the refused ones from being picked', async () => {
    setup();
    const { fixture, el } = await render({ slug: 'dev', tab: 'connections' });
    el.querySelector<HTMLElement>('#ep-conn-add')!.click();
    await settle(fixture);
    const dialog = el.querySelector('#ep-dlg-add-conn')!;
    expect(text(dialog.querySelector('#ep-add-stage-db-hint'))).toBe('PostgreSQL · secret in Vault · its tools start as 3 read only, 1 approval');
    const refused = dialog.querySelector<HTMLInputElement>('#ep-add-prod-replica')!;
    expect(refused.getAttribute('aria-disabled')).toBe('true');
    expect(text(dialog.querySelector('#ep-add-prod-replica-hint'))).toBe('prod-replica is a prod connection: it goes only on a prod endpoint, and dev is stage.');
    expect(text(dialog.querySelector('.gw-alert--warning'))).toBe('A prod connection goes only on a prod endpoint, where the prod policy applies. For incidents, incident-prod already has them.');
    // Nothing picked: Add says so instead of doing nothing.
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(text(dialog.querySelector('#ep-add-err'))).toBe('Pick at least one connection to add.');

    refused.click();
    dialog.querySelector<HTMLInputElement>('#ep-add-stage-db')!.click();
    await settle(fixture);
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(calls.filter((c) => c.startsWith('add'))).toEqual(['add stage-db']);
    expect(toasts().at(-1)).toMatchObject({ title: 'stage-db added to dev', body: 'Its tools start as 3 read only, 1 approval; change them under Tools & policies.' });
  });

  it('opens Split endpoint from the risk callout and asks the core what each side would hold', async () => {
    setup();
    const { fixture, el } = await render();
    button(el.querySelector('#ep-risk')!, 'Split endpoint').click();
    await settle(fixture);
    expect(text(openDialog()?.querySelector('.gw-dialog__title'))).toBe('Split endpoint dev');
    expect(calls).toContain('split-preview billing-api dev-billing');
    expect(Array.from(openDialog()!.querySelectorAll('.gw-choice-card__title')).map(text)).toEqual([
      'External channel: billing-api stage',
      'External channel: dev-vm dev',
      'Untrusted content: app-logs dev',
      'Private data: dev-db dev',
    ]);
  });

  it('keeps an endpoint changed outside GateRook only after asking', async () => {
    setup(() => Promise.resolve(detail({ endpoint: { blockedReason: 'The stored row does not match its signature.' } })));
    const { fixture, el } = await render();
    expect(text(el.querySelector('.gw-alert--warning .gw-alert__title'))).toBe('dev was changed outside GateRook');
    button(el, 'Keep as stored').click();
    await settle(fixture);
    expect(text(el.querySelector('#ep-keep-title'))).toBe('Keep dev as stored?');
    button(el.querySelector('#ep-dlg-keep')!, 'Keep dev').click();
    await settle(fixture);
    expect(calls).toContain('keep dev');
    expect(toasts().at(-1)?.title).toBe('dev is in use again');
  });

  it('issues a token and shows it once', async () => {
    setup();
    const { fixture, el } = await render({ slug: 'dev', tab: 'tokens' });
    button(el.querySelector('#tokens')!, 'Issue token').click();
    await settle(fixture);
    const dialog = openDialog()!;
    const name = dialog.querySelector<HTMLInputElement>('input[type="text"]')!;
    name.value = 'cursor-laptop';
    name.dispatchEvent(new Event('input'));
    await settle(fixture);
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(calls).toContain('issue cursor-laptop');
    expect(text(openDialog())).toContain('gwt_secret_value');
  });

  it('lists the OAuth sign-ins card as off, with a way to the setting', async () => {
    setup();
    const { el } = await render({ slug: 'dev', tab: 'tokens' });
    expect(text(el.querySelector('#ep-oauth-sessions .gw-empty__title'))).toBe('No sign-ins: OAuth is off for dev');
    expect(el.querySelector('#ep-oauth-sessions a')?.getAttribute('href')).toBe('/?tab=settings#ep-st-auth');
  });

  it('brings an OAuth management link into view and focuses it once the endpoint loads', async () => {
    const answer = deferred<EndpointDetail>();
    setup(() => answer.promise);
    const scrollIntoView = vi.fn();
    const original = HTMLElement.prototype.scrollIntoView;
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    try {
      const harness = await RouterTestingHarness.create();
      await harness.navigateByUrl('/endpoints/dev?tab=tokens#ep-oauth-sessions', EndpointDetailPage);
      expect(harness.routeNativeElement?.querySelector('#ep-oauth-sessions')).toBeNull();
      answer.resolve(detail({ settings: { oauthEnabled: true } }));
      await settle(harness.fixture);

      const section = harness.routeNativeElement!.querySelector('#ep-oauth-sessions')!;
      expect(harness.routeNativeElement!.querySelector('#tokens')?.hasAttribute('hidden')).toBe(false);
      expect(document.activeElement).toBe(section);
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' });

      await harness.navigateByUrl('/endpoints/dev?tab=clients', EndpointDetailPage);
      await settle(harness.fixture);
      const tab = harness.routeNativeElement!.querySelector<HTMLButtonElement>('#tab-clients')!;
      tab.focus();
      scrollIntoView.mockClear();
      const events = TestBed.inject(EventStream) as unknown as FakeEvents;
      events.emit('oauth.grant.changed', { id: 'og_cursor', endpoint: 'dev' });
      await settle(harness.fixture);
      expect(document.activeElement).toBe(tab);
      expect(scrollIntoView).not.toHaveBeenCalled();
    } finally {
      if (original) HTMLElement.prototype.scrollIntoView = original;
      else delete (HTMLElement.prototype as { scrollIntoView?: unknown }).scrollIntoView;
    }
  });

  it('shows the registered OAuth app name instead of its loopback callback host', async () => {
    setup(() => Promise.resolve(detail({ settings: { oauthEnabled: true } })));
    const grant: OAuthGrant = {
      id: 'og_cursor', client: 'localhost', clientName: 'Cursor', clientKind: 'dcr', endpoint: 'dev', ingress: 'LOCAL',
      createdAt: new Date(NOW - 240_000).toISOString(), expiresAt: new Date(NOW + 604_800_000).toISOString(), lastUsedAt: null, state: 'active',
    };
    const oauth = TestBed.inject(OAuthApi);
    vi.spyOn(oauth, 'grants').mockResolvedValue([grant]);
    const revoke = vi.fn(async () => undefined);
    oauth.revoke = revoke;
    const { fixture, el } = await render({ slug: 'dev', tab: 'tokens' });
    const signIns = el.querySelector('#ep-oauth-sessions')!;
    expect(text(signIns.querySelector('tbody th'))).toBe('Cursor');
    expect(text(signIns.querySelector('thead'))).toContain('Last used');
    expect(text(signIns.querySelector('tbody td:nth-of-type(2)'))).toBe('Never');
    expect(text(signIns)).not.toContain('localhost');
    button(signIns, 'Revoke sign-in of Cursor').click();
    await settle(fixture);
    expect(revoke).toHaveBeenCalledExactlyOnceWith('og_cursor');
    expect(toasts().at(-1)?.title).toBe('Sign-in of Cursor revoked');
    expect(grant.client).toBe('localhost');
  });

  describe('settings', () => {
    it('saves the instructions with a Save bar that shows only while they changed', async () => {
      setup();
      const { fixture, el } = await render({ slug: 'dev', tab: 'settings' });
      const bar = el.querySelector('#ep-st-instructions .gw-form__actions')!;
      expect(bar.hasAttribute('hidden')).toBe(true);
      expect(text(el.querySelector('#ep-st-instr-hint'))).toContain('Empty, so agents get the 827 characters GateRook writes');
      const area = el.querySelector<HTMLTextAreaElement>('#ep-st-instr')!;
      area.value = 'Read first.';
      area.dispatchEvent(new Event('input'));
      await settle(fixture);
      expect(bar.hasAttribute('hidden')).toBe(false);
      expect(text(el.querySelector('#ep-st-instr-count'))).toBe('11 / 4,000');

      detailAnswers = [() => Promise.resolve(detail({ settings: { instructions: 'Read first.', version: 9 } }))];
      el.querySelector('#ep-st-instr-form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      await settle(fixture);
      expect(calls).toContain('settings {"instructions":"Read first.","version":8}');
      expect(toasts().at(-1)?.title).toBe('Server instructions saved');
      expect(bar.hasAttribute('hidden')).toBe(true);
    });

    it("shows the text the gateway writes in the field, as the kit does (step API), and keeps it the gateway's", async () => {
      const generated = 'You are connected to GateRook, endpoint "dev" (Local development).\nRead tools run at once.';
      setup(() => Promise.resolve(detail({ settings: { instructions: null, defaultInstructions: generated } })));
      const { fixture, el } = await render({ slug: 'dev', tab: 'settings' });
      const area = el.querySelector<HTMLTextAreaElement>('#ep-st-instr')!;
      const bar = el.querySelector('#ep-st-instructions .gw-form__actions')!;
      expect(area.value).toBe(generated);
      expect(bar.hasAttribute('hidden')).toBe(true);
      expect(text(el.querySelector('#ep-st-instr-count'))).toBe(`${generated.length} / 4,000`);
      expect(text(el.querySelector('#ep-st-instr-hint'))).toBe(
        'Plain text. Never put secrets here: every agent on dev reads it. GateRook writes this text from the tools of dev and the approval rules until you change it.',
      );

      // An edit is the endpoint's own text; Discard goes back to the gateway's.
      area.value = `${generated}\nAsk before restarting anything.`;
      area.dispatchEvent(new Event('input'));
      await settle(fixture);
      expect(bar.hasAttribute('hidden')).toBe(false);
      el.querySelector('#ep-st-instr-form')!.dispatchEvent(new Event('reset', { cancelable: true }));
      await settle(fixture);
      expect(area.value).toBe(generated);
      expect(bar.hasAttribute('hidden')).toBe(true);
    });

    it("saves the gateway's text back as null: the gateway keeps writing it", async () => {
      const generated = 'You are connected to GateRook, endpoint "dev".';
      setup(() => Promise.resolve(detail({ settings: { instructions: 'Custom.', defaultInstructions: generated } })));
      const { fixture, el } = await render({ slug: 'dev', tab: 'settings' });
      const area = el.querySelector<HTMLTextAreaElement>('#ep-st-instr')!;
      expect(area.value).toBe('Custom.');
      area.value = generated;
      area.dispatchEvent(new Event('input'));
      await settle(fixture);
      el.querySelector('#ep-st-instr-form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      await settle(fixture);
      expect(calls).toContain('settings {"instructions":null,"version":8}');
    });

    it('tells a stale save from an endpoint changed outside GateRook', async () => {
      // Review 2026-10-26: every 409 read "changed meanwhile … save again"; an integrity block isn't fixed by saving.
      setup();
      const { fixture, el } = await render({ slug: 'dev', tab: 'settings' });
      const area = el.querySelector<HTMLTextAreaElement>('#ep-st-instr')!;
      const save = async () => {
        area.value = 'Read first.';
        area.dispatchEvent(new Event('input'));
        await settle(fixture);
        el.querySelector('#ep-st-instr-form')!.dispatchEvent(new Event('submit', { cancelable: true }));
        await settle(fixture);
        return text(el.querySelector('#ep-st-instructions [role="alert"] .gw-alert__body'));
      };
      settingsAnswer = () => Promise.reject(new ApiError('conflict', 409, { title: 'Changed outside GateRook', detail: 'dev was changed outside GateRook. Review it first.' }));
      expect(await save()).toBe('dev was changed outside GateRook. Review it first.');
      settingsAnswer = () => Promise.reject(new ApiError('conflict', 409, { title: 'Changed meanwhile', detail: 'Someone changed this in the meantime. Reload and try again.' }));
      expect(await save()).toBe('These settings changed meanwhile, in another window. Check the values, then save again.');
    });

    it('discards back to what the core keeps', async () => {
      setup();
      const { fixture, el } = await render({ slug: 'dev', tab: 'settings' });
      const area = el.querySelector<HTMLTextAreaElement>('#ep-st-instr')!;
      area.value = 'Draft';
      area.dispatchEvent(new Event('input'));
      await settle(fixture);
      el.querySelector('#ep-st-instr-form')!.dispatchEvent(new Event('reset', { cancelable: true }));
      await settle(fixture);
      expect(area.value).toBe('');
      expect(el.querySelector('#ep-st-instructions .gw-form__actions')?.hasAttribute('hidden')).toBe(true);
    });

    it('refuses a limit that is not a whole number and saves the others with the rest of the limits', async () => {
      setup(() => Promise.resolve(detail({ settings: { limits: { maxResponseKb: 256, other: 1 } } })));
      const { fixture, el } = await render({ slug: 'dev', tab: 'settings' });
      expect(el.querySelector<HTMLInputElement>('#ep-st-size')!.value).toBe('256');
      expect(text(el.querySelector('#ep-st-rate-token-hint'))).toBe('Empty: 120 from the dev policy. For each token on dev such as codex-laptop.');
      const perToken = el.querySelector<HTMLInputElement>('#ep-st-rate-token')!;
      perToken.value = 'lots';
      perToken.dispatchEvent(new Event('input'));
      await settle(fixture);
      el.querySelector('#ep-st-limits-form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      await settle(fixture);
      expect(text(el.querySelector('#ep-st-rate-token-err'))).toBe('Enter a whole number, or leave it empty to follow the policy.');
      expect(calls.some((c) => c.startsWith('settings'))).toBe(false);

      perToken.value = '60';
      perToken.dispatchEvent(new Event('input'));
      await settle(fixture);
      el.querySelector('#ep-st-limits-form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      await settle(fixture);
      expect(calls).toContain('settings {"limits":{"maxResponseKb":256,"other":1,"rateLimitPerToken":60},"version":8}');
    });

    it('meters the busiest token of the endpoint', async () => {
      setup();
      const { el } = await render({ slug: 'dev', tab: 'settings' });
      const meter = el.querySelector('#ep-st-limits [role="meter"]')!;
      expect(meter.getAttribute('aria-valuetext')).toBe('14 of 120 calls in the last minute, well under the limit');
      expect(meter.getAttribute('data-level')).toBe('ok');
      expect(text(meter.querySelector('.gw-meter__label'))).toBe('Busiest token now · codex-laptop');
    });

    it('turns OAuth on at once, and back when the core refuses', async () => {
      setup();
      const { fixture, el } = await render({ slug: 'dev', tab: 'settings' });
      const sw = el.querySelector<HTMLInputElement>('#ep-st-oauth')!;
      settingsAnswer = () => Promise.reject(new ApiError('conflict', 409, { detail: 'Stale.' }));
      sw.click();
      await settle(fixture);
      expect(sw.checked).toBe(false);
      expect(toasts().at(-1)).toMatchObject({ tone: 'danger', title: "OAuth 2.1 wasn't turned on" });

      settingsAnswer = () => Promise.resolve(settings({ oauthEnabled: true, version: 9 }));
      sw.click();
      await settle(fixture);
      expect(toasts().at(-1)).toMatchObject({ tone: 'success', title: 'Saved', body: 'OAuth 2.1 sign-in for dev: on.' });
    });

    it('keeps at least one MCP revision and says who a revision would break', async () => {
      setup();
      const { fixture, el } = await render({ slug: 'dev', tab: 'settings' });
      expect(Array.from(el.querySelectorAll('#ep-st-protocol .gw-kv__row')).map(kv)).toEqual(['Claude Desktop 2025-11-25', 'Codex 2026-07-28']);
      el.querySelector<HTMLInputElement>('#ep-st-rev-2025-11-25')!.click();
      await settle(fixture);
      expect(text(el.querySelector('#ep-st-protocol .gw-alert--warning'))).toBe('Claude Desktop used 2025-11-25 lately: it stops working on dev when you save.');
      el.querySelector<HTMLInputElement>('#ep-st-rev-2026-07-28')!.click();
      await settle(fixture);
      el.querySelector('#ep-st-proto-form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      await settle(fixture);
      expect(text(el.querySelector('#ep-st-proto-err'))).toBe('Keep at least one revision: with none, no AI app can call dev.');
      expect(calls.some((c) => c.startsWith('settings'))).toBe(false);
    });
  });

  it('shows the endpoint wired to a different set when the route changes to another slug', async () => {
    setup();
    const { fixture, el } = await render();
    detailAnswers = [() => Promise.resolve(detail({ endpoint: { slug: 'dev-billing', description: 'Billing' }, connections: [wired()[7]] }))];
    rows = [connectionRow('billing-api', { env: 'stage' })];
    fixture.componentRef.setInput('slug', 'dev-billing');
    await settle(fixture);
    expect(calls).toContain('get dev-billing');
    expect(text(el.querySelector('.gw-page-header__description'))).toBe('Billing on 1 connection. Agents read freely; anything that changes data waits for your approval.');
  });
});
