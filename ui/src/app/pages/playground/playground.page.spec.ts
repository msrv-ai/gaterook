import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ActivityApi, ActivityEvent } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ConnectionsApi } from '../../core/api/connections.api';
import { EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { PlaygroundApi, PlaygroundCallView } from '../../core/api/playground.api';
import { PoliciesApi } from '../../core/api/policies.api';
import { JsonObject } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { CONNECTIONS, DEV_GROUPS, ENDPOINTS, NOW, PROD_GROUPS, TABLE, callView, event, playTools, prodTools, toolsAnswer } from './playground.fixtures';
import { PlaygroundPage, SKELETON_DELAY_MS } from './playground.page';

/** The page against fake admin API services: what it shows for each answer, and what it asks the core to do. */

@Component({ template: '' })
class Blank {}

const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (t) => (t ? `rel ${t.slice(11, 19)}` : ''),
  absolute: (t) => (t ? `abs ${t}` : ''),
  iso: (t) => t ?? '',
  clock: (t) => (t ? t.slice(11, 19) : ''),
};

class FakeEvents {
  readonly reopened = signal(0);
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

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => (resolve = res));
  return { promise, resolve };
}

let calls: string[];
let bodies: { tool: string; arguments: JsonObject }[];
let events: FakeEvents;
let history: ActivityEvent[];
let callAnswer: (body: { tool: string; arguments: JsonObject }) => Promise<PlaygroundCallView>;
let storeLoad: () => Promise<void>;
let storeList: ReturnType<typeof signal<EndpointSummary[] | null>>;
let storeError: ReturnType<typeof signal<unknown>>;

function setup(): void {
  calls = [];
  bodies = [];
  events = new FakeEvents();
  history = [];
  callAnswer = () => Promise.resolve(callView({ callId: 'tc_run', structured: TABLE }));
  storeList = signal<EndpointSummary[] | null>(null);
  storeError = signal<unknown>(null);
  storeLoad = () => {
    storeList.set(ENDPOINTS);
    return Promise.resolve();
  };
  const play: Partial<PlaygroundApi> = {
    tools: (slug) => {
      calls.push(`tools ${slug}`);
      return Promise.resolve(toolsAnswer(slug, slug === 'incident-prod' ? prodTools() : playTools()));
    },
    call: (body) => {
      calls.push(`call ${body.endpoint} ${body.tool}`);
      bodies.push({ tool: body.tool, arguments: body.arguments });
      return callAnswer(body);
    },
    history: (slug) => {
      calls.push(`history ${slug}`);
      return Promise.resolve({ events: history, nextCursor: null });
    },
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'playground', component: Blank },
        { path: '**', component: Blank },
      ]),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: PlaygroundApi, useValue: play },
      {
        provide: EndpointsApi,
        useValue: {
          tools: (slug: string) => Promise.resolve(slug === 'incident-prod' ? PROD_GROUPS : DEV_GROUPS),
          settings: () => Promise.resolve({ protocol: { revisions: ['2026-07-28', '2025-11-25'] } }),
        },
      },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: CONNECTIONS, facets: {}, total: CONNECTIONS.length }) } },
      { provide: PoliciesApi, useValue: { get: () => Promise.resolve({ sections: { approvals: { comment: 'required' } } }) } },
      { provide: ActivityApi, useValue: { event: (id: number) => Promise.resolve({ event: event(id) }) } },
      { provide: EndpointsStore, useValue: { list: storeList, error: storeError, load: () => storeLoad() } },
      { provide: EventStream, useValue: events },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

async function render(inputs: Record<string, string> = {}): Promise<{ fixture: ComponentFixture<PlaygroundPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(PlaygroundPage);
  for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
/** A row of the meta strip: "Decision Allowed". */
const kv = (row: Element | null | undefined) => `${text(row?.querySelector('dt'))} ${text(row?.querySelector('dd'))}`;
const $ = <T extends Element = HTMLElement>(el: HTMLElement, sel: string) => el.querySelector<T & Element>(sel) as T | null;
const toasts = () => TestBed.inject(ToastService).toasts();

function type(input: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

async function pick(fixture: ComponentFixture<PlaygroundPage>, el: HTMLElement, tool: string, connection?: string): Promise<void> {
  const toggle = $(el, '#pg-change-tool')!;
  if (toggle.getAttribute('aria-expanded') !== 'true') toggle.click();
  await settle(fixture);
  const links = Array.from(el.querySelectorAll<HTMLAnchorElement>('#pg-tools a.gw-table__row-link')).filter((a) => text(a) === tool);
  const link = connection ? links.find((a) => text(a.closest('section')?.querySelector('.gw-card__title')).startsWith(connection)) : links[0];
  link!.click();
  await settle(fixture);
}

async function run(fixture: ComponentFixture<PlaygroundPage>, el: HTMLElement): Promise<void> {
  $(el, '#pg-run')!.click();
  await settle(fixture);
}

describe('Playground page', () => {
  it('shows nothing for a moment, then placeholders, then the page', async () => {
    setup();
    const answer = deferred<void>();
    storeLoad = () => answer.promise.then(() => storeList.set(ENDPOINTS));
    const { fixture, el } = await render();
    expect(text($(el, 'h1'))).toBe('Playground');
    expect(el.querySelector('.gw-skeleton')).toBeNull();

    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelectorAll('.gw-skeleton').length).toBeGreaterThan(0);
    expect(text($(el, '[role="status"]'))).toBe('Loading the Playground');

    answer.resolve();
    await settle(fixture);
    expect($(el, '#pg-endpoint')).not.toBeNull();
    expect($(el, '#pg-run')).not.toBeNull();
  });

  it('says why the endpoints didn’t load and tries again', async () => {
    setup();
    let fail = true;
    storeLoad = () => {
      if (fail) storeError.set(new ApiError('network', 0, { title: 'No answer', detail: 'The core did not answer.' }));
      else storeList.set(ENDPOINTS);
      return Promise.resolve();
    };
    const { fixture, el } = await render();
    const alert = $(el, '.gw-alert--danger')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The endpoints didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The core did not answer.');
    fail = false;
    Array.from(alert.querySelectorAll('button')).find((b) => text(b) === 'Try again')!.click();
    await settle(fixture);
    expect($(el, '#pg-endpoint')).not.toBeNull();
  });

  it('offers to create an endpoint when there is none', async () => {
    setup();
    storeLoad = () => {
      storeList.set([]);
      return Promise.resolve();
    };
    const { el } = await render();
    expect(text($(el, '.gw-empty__title'))).toBe('No endpoints yet');
    expect($(el, '.gw-empty a')?.getAttribute('href')).toBe('/endpoints#create');
    expect($(el, '#pg-endpoint')).toBeNull();
  });

  it('opens the first endpoint that isn’t prod, with sql_query chosen and its arguments', async () => {
    setup();
    history = [];
    const { el } = await render();
    const select = $<HTMLSelectElement>(el, '#pg-endpoint')!;
    expect(Array.from(select.options).map((o) => o.text)).toEqual(['incident-prod · 3 prod connections', 'dev · 8 connections, 22 tools', 'stage-readonly · 3 connections, read only']);
    expect(select.value).toBe('dev');
    expect(text($(el, '#pg-endpoint-hint'))).toBe('http://127.0.0.1:7465/mcp/dev · the same tools, modes and limits an agent gets there');
    expect($(el, '#pg-prod')).toBeNull();

    expect(text($(el, '#pg-chosen .gw-toolbar__start'))).toBe('Chosen sql_query on dev-db environment dev Read only');
    expect($(el, '#pg-change-tool')?.getAttribute('aria-expanded')).toBe('false');
    expect($(el, '#pg-tools')?.hidden).toBe(true);

    expect(text($(el, '#pg-args .gw-card__description'))).toBe('For sql_query, built from its inputSchema');
    const conn = $<HTMLSelectElement>(el, '#pg-a-connection')!;
    expect(Array.from(conn.options).map((o) => o.text)).toEqual(['dev-db · PostgreSQL']);
    expect(conn.value).toBe('dev-db');
    expect(text($(el, '#pg-a-connection-hint'))).toBe('Environment dev · db.dev.internal:5432/app');
    expect($(el, '#pg-a-sql')?.tagName).toBe('TEXTAREA');
    expect($<HTMLInputElement>(el, '#pg-a-limit')?.value).toBe('50');
    expect(text($(el, 'label[for="pg-a-limit"]'))).toBe('limit Optional');
    expect(text($(el, '#pg-a-limit-hint'))).toBe('Max rows; the endpoint caps it. Integer, 50 when empty, from 1 to 1,000.');
    expect($(el, '#pg-approval-alert')).toBeNull();
    expect($(el, '#pg-run')?.getAttribute('aria-disabled')).toBeNull();

    expect(text($(el, '#pg-out .gw-empty__title'))).toBe('Nothing run yet');
    expect(text($(el, '[aria-labelledby="pg-hist-t"] .gw-empty__title'))).toBe('No runs yet');
  });

  it('opens the picker with Change tool; a picked row is chosen, the picker closes and focus goes to the arguments', async () => {
    setup();
    const { fixture, el } = await render();
    type($(el, '#pg-a-sql') as HTMLTextAreaElement, 'SELECT 1');
    await settle(fixture);

    $(el, '#pg-change-tool')!.click();
    await settle(fixture);
    expect($(el, '#pg-tools')?.hidden).toBe(false);
    expect($(el, '#pg-change-tool')?.getAttribute('aria-expanded')).toBe('true');
    // General first, then the connections.
    expect(Array.from(el.querySelectorAll('#pg-tools .gw-card__title')).map(text)).toEqual(['General', 'dev-db environment dev', 'dev-vm environment dev', 'billing-api environment stage']);
    expect(text(el.querySelector('#pg-tools .gw-card__description'))).toContain('Endpoint tools, no connection of their own');
    const current = el.querySelector('#pg-tools a[aria-current="true"]');
    expect(text(current)).toBe('sql_query');

    await pick(fixture, el, 'sql_execute');
    expect($(el, '#pg-tools')?.hidden).toBe(true);
    expect($(el, '#pg-change-tool')?.getAttribute('aria-expanded')).toBe('false');
    expect(text($(el, '#pg-chosen .gw-toolbar__start'))).toBe('Chosen sql_execute on dev-db environment dev Approval');
    expect(document.activeElement?.id).toBe('pg-a-connection');
    // sql goes on to the next tool, limit doesn't exist there.
    expect(($(el, '#pg-a-sql') as HTMLTextAreaElement).value).toBe('SELECT 1');
    expect($(el, '#pg-a-limit')).toBeNull();
    expect(text($(el, '#pg-approval-alert .gw-alert__title'))).toBe('This call needs approval');
    expect($(el, '#pg-approval-alert a')?.getAttribute('href')).toBe('/approvals');

    // A tool without arguments: Run is next.
    await pick(fixture, el, 'list_connections');
    expect(text($(el, '#pg-panel-form'))).toBe('list_connections takes no arguments.');
    expect(document.activeElement?.id).toBe('pg-run');
    expect(text($(el, '#pg-chosen .gw-toolbar__start'))).toBe('Chosen list_connections Read only');
  });

  it('runs the arguments and shows what the agent gets', async () => {
    setup();
    const { fixture, el } = await render();
    type($(el, '#pg-a-sql') as HTMLTextAreaElement, 'SELECT id FROM orders');
    await settle(fixture);
    history = [event(9, { callId: 'tc_run' }), event(8, { decision: 'denied', durationMs: null, tool: 'http_request', connection: 'billing-api', env: 'stage' })];
    await run(fixture, el);

    expect(bodies).toEqual([{ tool: 'sql_query', arguments: { connection: 'dev-db', sql: 'SELECT id FROM orders', limit: 50 } }]);
    expect(text($(el, '#pg-out-status'))).toBe('sql_query allowed in 38 ms: 2 rows, 1 field masked.');
    expect(text($(el, '#pg-out .gw-section__description'))).toBe(`Last run: sql_query on dev-db environment dev, rel ${new Date(NOW).toISOString().slice(11, 19)}. Masked and limited exactly as for an agent.`);
    const meta = Array.from(el.querySelectorAll('#pg-result .gw-kv__row')).map(kv);
    expect(meta.slice(0, 4)).toEqual(['Decision Allowed', 'Duration 38 ms', 'Size 3.1 KB · 2 rows', 'Masked 1 field: customer_email · by the dev masking rules']);
    expect(el.querySelector('#pg-result a[href="/policies/dev#dev-masking"]')).not.toBeNull();
    expect(text($(el, '#pg-trace'))).toBe('9e3b51d07a4c4f0e8b2d6c19a5f7e340');
    const headers = Array.from(el.querySelectorAll('#pg-result table th[scope="col"]')).map(text);
    expect(headers).toEqual(['id', 'status', 'customer_email', 'total']);
    expect(el.querySelectorAll('#pg-result table .gw-code--secret')).toHaveLength(2);

    // The history: the run shown above is marked; a denied call has no duration in the audit.
    const rows = Array.from(el.querySelectorAll('[aria-labelledby="pg-hist-t"] tbody tr'));
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('a')?.getAttribute('href')).toBe('/activity#event-9');
    expect(rows[0].querySelector('a')?.getAttribute('aria-current')).toBe('true');
    expect(text(rows[0].querySelector('.gw-sr-only'))).toBe('at 12:29:52, open run sql_query in Activity, shown above');
    expect(text(rows[1])).toContain('Denied by policy');
    expect(text(rows[1].children[3])).toBe('');
  });

  it('runs with ⌘/Ctrl + Enter from the form', async () => {
    setup();
    const { fixture, el } = await render();
    const sql = $(el, '#pg-a-sql') as HTMLTextAreaElement;
    type(sql, 'SELECT 1');
    await settle(fixture);
    sql.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true }));
    await settle(fixture);
    expect(calls.filter((c) => c.startsWith('call'))).toEqual(['call dev sql_query']);
  });

  it('stops Run at an empty required argument', async () => {
    setup();
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { fixture, el } = await render();
    await run(fixture, el);
    expect(bodies).toEqual([]);
    expect(text($(el, '#pg-a-sql-err'))).toBe('sql is required.');
    expect(announce).toHaveBeenCalledWith('Fix the highlighted argument first.');
  });

  it('edits the same arguments as JSON and checks them against the schema', async () => {
    setup();
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { fixture, el } = await render();
    type($(el, '#pg-a-sql') as HTMLTextAreaElement, 'SELECT 1');
    await settle(fixture);
    $(el, '#pg-tab-json')!.click();
    await settle(fixture);
    expect($(el, '#pg-panel-json')?.hidden).toBe(false);
    expect($(el, '#pg-panel-form')?.hidden).toBe(true);
    const json = $(el, '#pg-json') as HTMLTextAreaElement;
    expect(json.value).toBe('{\n  "connection": "dev-db",\n  "sql": "SELECT 1",\n  "limit": 50\n}');

    type(json, '{ "connection": "dev-db", "sql": "SELECT 2", "limit": 5000 }');
    await settle(fixture);
    expect(text($(el, '#pg-json-err'))).toBe('limit: 5000 is above the maximum. Use 1000 or less.');
    expect(json.getAttribute('aria-invalid')).toBe('true');
    expect(json.getAttribute('aria-describedby')).toBe('pg-json-err pg-json-hint');
    await run(fixture, el);
    expect(bodies).toEqual([]);
    expect(announce).toHaveBeenCalledWith('Fix the JSON arguments first.');

    type(json, '{ "connection": "dev-db", "sql": "SELECT 2", "limit": 10 }');
    await settle(fixture);
    expect($(el, '#pg-json-err')).toBeNull();
    expect(($(el, '#pg-a-sql') as HTMLTextAreaElement).value).toBe('SELECT 2');
    expect(($(el, '#pg-a-limit') as HTMLInputElement).value).toBe('10');
    await run(fixture, el);
    expect(bodies).toEqual([{ tool: 'sql_query', arguments: { connection: 'dev-db', sql: 'SELECT 2', limit: 10 } }]);

    // Reset: the tool's own defaults and the row's connection, in both tabs.
    (el.querySelector('#pg-args button[type="reset"]') as HTMLButtonElement).click();
    await settle(fixture);
    expect(json.value).toBe('{\n  "connection": "dev-db",\n  "limit": 50\n}');
    expect(($(el, '#pg-a-sql') as HTMLTextAreaElement).value).toBe('');
    expect(announce).toHaveBeenCalledWith('Arguments reset');
  });

  it('can’t run an Off tool and says where to change it', async () => {
    setup();
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { fixture, el } = await render({ tool: 'ssh_exec' });
    expect(text($(el, '#pg-chosen .gw-toolbar__start'))).toBe('Chosen ssh_exec on dev-vm environment dev Off');
    // The link's tool fills the picker's search, as the kit's script does.
    expect(($(el, '#pg-tools input[type="search"]') as HTMLInputElement).value).toBe('ssh_exec');
    const alert = $(el, '#pg-off-alert')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('Off on endpoint dev');
    expect(alert.querySelector('a')?.getAttribute('href')).toBe('/endpoints/dev?tab=tools');
    const runButton = $(el, '#pg-run')!;
    expect(runButton.getAttribute('aria-disabled')).toBe('true');
    expect(runButton.getAttribute('aria-describedby')).toBe('pg-off-alert');

    type($(el, '#pg-a-command') as HTMLInputElement, 'uptime');
    await settle(fixture);
    runButton.click();
    await settle(fixture);
    $(el, '#pg-a-command')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }));
    await settle(fixture);
    expect(bodies).toEqual([]);
    expect(announce).toHaveBeenCalledWith("ssh_exec is off on endpoint dev and can't run.");
  });

  it('warns on a prod endpoint which connections calls run on', async () => {
    setup();
    const { el } = await render({ endpoint: 'incident-prod' });
    expect($<HTMLSelectElement>(el, '#pg-endpoint')?.value).toBe('incident-prod');
    expect($(el, '#pg-endpoint')?.getAttribute('aria-describedby')).toBe('pg-endpoint-hint pg-prod');
    expect(text($(el, '#pg-prod'))).toBe(
      'Calls run on environment prod: prod-replica, prod-logs and prod-vm. They are real reads on production, and a runbook waits for approval like an agent’s; on prod, approving needs a comment.',
    );
    expect(text($(el, '#pg-chosen .gw-toolbar__start'))).toBe('Chosen sql_query on prod-replica environment prod Read only');
  });

  it('says when a link names an endpoint that doesn’t exist', async () => {
    setup();
    const { el } = await render({ endpoint: 'gone' });
    expect(text($(el, '.gw-alert--warning'))).toBe('There is no endpoint gone. It was removed or renamed, so the Playground opened dev.');
    expect($<HTMLSelectElement>(el, '#pg-endpoint')?.value).toBe('dev');
  });

  it('switches endpoints through the URL and opens the picker on the new one', async () => {
    setup();
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    const { fixture, el } = await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const select = $<HTMLSelectElement>(el, '#pg-endpoint')!;
    select.value = 'incident-prod';
    select.dispatchEvent(new Event('change'));
    expect(navigate).toHaveBeenCalledWith(['/playground'], { queryParams: { endpoint: 'incident-prod' } });
    fixture.componentRef.setInput('endpoint', 'incident-prod');
    await settle(fixture);
    expect(calls).toContain('tools incident-prod');
    expect($(el, '#pg-change-tool')?.getAttribute('aria-expanded')).toBe('true');
    expect($(el, '#pg-tools')?.hidden).toBe(false);
    expect(announce).toHaveBeenCalledWith('Endpoint incident-prod: 5 tools. Warning: calls run on prod-replica, prod-logs and prod-vm. sql_query chosen.');
  });

  it('sends an approval request, says so once, and shows the outcome after the decision', async () => {
    setup();
    const { fixture, el } = await render({ tool: 'sql_execute' });
    callAnswer = (body) => {
      if (body.tool === 'sql_execute') {
        events.emit('approval.requested', { id: 'ap_7f3k', endpoint: 'dev', tool: 'sql_execute', connection: 'dev-db' });
        return Promise.resolve(callView({ tool: 'sql_execute', callId: 'tc_p', text: 'Waiting for approval.', structured: { status: 'pending', handle: 'ap_7f3k' }, approval: { handle: 'ap_7f3k', status: 'pending' } }));
      }
      return Promise.resolve(callView({ tool: 'approval_status', callId: 'tc_s', text: 'Approved and ran: 1 row changed.' }));
    };
    type($(el, '#pg-a-sql') as HTMLTextAreaElement, "UPDATE orders SET status = 'synced'");
    await settle(fixture);
    await run(fixture, el);

    expect(toasts().map((t) => t.title)).toEqual(['Approval request sent']);
    expect(toasts()[0].body).toBe('sql_execute on dev-db waits for you in Approvals. The result shows up here once you approve it.');
    expect(text($(el, '#pg-out-status'))).toBe('sql_execute waits for approval in 38 ms.');
    expect(kv($(el, '#pg-result .gw-kv__row'))).toBe('Decision Waiting for approval');
    expect($(el, '#pg-pending a')?.getAttribute('href')).toBe('/approvals/ap_7f3k');

    events.emit('approval.resolved', { id: 'ap_7f3k', status: 'approved' });
    await settle(fixture);
    expect(bodies.at(-1)).toEqual({ tool: 'approval_status', arguments: { handle: 'ap_7f3k' } });
    expect(text($(el, '#pg-out .gw-section__description'))).toContain('Last run: approval_status');
    expect($(el, '#pg-pending')).toBeNull();
    expect(toasts()).toHaveLength(1);
  });

  it('says why a call didn’t run and tries again', async () => {
    setup();
    const { fixture, el } = await render({ tool: 'list_connections' });
    callAnswer = () => Promise.reject(new ApiError('bad-request', 400, { title: 'Bad arguments', detail: 'The core refused the arguments.' }));
    await run(fixture, el);
    const alert = $(el, '#pg-out .gw-alert--danger')!;
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The call didn't run");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The core refused the arguments.');
    expect(text($(el, '#pg-out-status'))).toBe("list_connections didn't run. The core refused the arguments.");

    callAnswer = () => Promise.resolve(callView({ tool: 'list_connections', text: '', structured: null }));
    Array.from(alert.querySelectorAll('button')).find((b) => text(b) === 'Try again')!.click();
    await settle(fixture);
    expect($(el, '#pg-out .gw-alert--danger')).toBeNull();
    // A read that came back with nothing is the kit's empty result.
    expect(text($(el, '#pg-result .gw-empty__title'))).toBe('Empty result');
    expect(text($(el, '#pg-result .gw-empty__text'))).toBe('list_connections ran and returned no content. The agent gets an empty result too, not an error.');
  });
});
