import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActivityEvent, TraceDetail } from '../../core/api/activity.api';
import { Decision } from '../../core/api/types';
import { ClientLink, ConnectPreview } from '../../core/api/clients.api';
import { OAuthGrant } from '../../core/api/tunnel.api';
import { TimeService } from '../../core/time/time.service';
import { DEMO_CONNECTION_ENVS, DEMO_FACETS, DEMO_HISTOGRAM } from '../../dev/fixtures-activity';
import {
  DEMO_CLIENTS,
  DEMO_CLIENT_ENDPOINTS,
  DEMO_CLIENT_FAILURE,
  DEMO_CONSENT,
  DEMO_DISCONNECT_CODEX,
  DEMO_GRANTS,
  DEMO_PREVIEW_CURSOR,
  DEMO_PUBLISH,
  DEMO_TUNNEL_OFF,
} from '../../dev/fixtures-clients';
import { DEMO_EVENTS, DEMO_EVENT_APPROVAL, DEMO_TRACE } from '../../dev/fixtures-events';
import { provideKitIcons } from '../icons';
import { AriaDisabledGuard } from '../aria-disabled';
import { GwClaudeAiCard } from './client-card/claude-ai-card';
import { ClientPreviewRequest, GwClientCard } from './client-card/client-card';
import { GwOAuthConsent } from './client-card/oauth-consent';
import { GwEventDetail } from './event-detail/event-detail';
import { GwTraceView } from './event-detail/trace-view';
import { GwLogExplorer } from './log-explorer/log-explorer';
import { LogRange } from './log-explorer/log-view';

/** The new elements with the demo world's data: they render, and their actions say what the page must do. */

const NOW = Date.parse('2026-10-22T14:30:00+02:00');
const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: (at) => (at ? 'a while ago' : ''),
  absolute: (at) => (at ? `abs ${at}` : ''),
  iso: (at) => at ?? '',
  date: (at) => (at ? 'Oct 29' : ''),
  clock: (at) => (at ? '12:08' : ''),
};

beforeEach(() => {
  now.set(NOW);
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons(), { provide: TimeService, useValue: fakeTime }] });
  TestBed.inject(AriaDisabledGuard).install();
});
afterEach(() => (document.body.innerHTML = ''));

async function render<T>(type: new () => T): Promise<{ fixture: ComponentFixture<T>; el: HTMLElement; host: T }> {
  const fixture = TestBed.createComponent(type);
  document.body.appendChild(fixture.nativeElement);
  fixture.detectChanges();
  await fixture.whenStable();
  return { fixture, el: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
}

const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const button = (root: ParentNode, name: string) =>
  Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((b) => text(b) === name || text(b).startsWith(name + ' '))!;

describe('client-card', () => {
  @Component({
    imports: [GwClientCard],
    template: `
      <article gwClientCard [card]="cursor" [endpoints]="endpoints" [connectPreview]="preview()" (previewRequest)="requests.push($event)" (connect)="connected.push($event.previewId)"></article>
      <article gwClientCard [card]="codex" [endpoints]="endpoints" [disconnectPreview]="leaving()" (disconnectStart)="starts.push($event)" (disconnect)="gone.push($event.previewId)" [canOpenConfig]="false" (openConfig)="opened = opened + 1"></article>
      <article gwClientCard [card]="cursor" [endpoints]="endpoints" [failure]="failure"></article>
    `,
  })
  class Host {
    cursor = DEMO_CLIENTS.cursor;
    codex = DEMO_CLIENTS.codex;
    endpoints = DEMO_CLIENT_ENDPOINTS;
    failure = DEMO_CLIENT_FAILURE;
    preview = signal<ConnectPreview | null>(null);
    leaving = signal<ConnectPreview | null>(null);
    requests: ClientPreviewRequest[] = [];
    connected: string[] = [];
    starts: ClientLink[] = [];
    gone: string[] = [];
    opened = 0;
  }

  it('Connect previews first and confirms only the exact change it showed', async () => {
    const { el, fixture, host } = await render(Host);
    const [cursor] = Array.from(el.querySelectorAll('article'));
    button(cursor, 'Connect').click();
    await fixture.whenStable();
    const dialog = cursor.querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(host.requests.at(-1)).toEqual({ client: 'cursor', request: { endpoint: 'dev', method: 'token', token: { name: 'cursor-laptop', ttlDays: 7 } } });
    const confirm = button(dialog, 'Confirm and connect');
    expect(confirm.getAttribute('aria-disabled')).toBe('true');
    confirm.click();
    expect(host.connected).toEqual([]);
    host.preview.set(DEMO_PREVIEW_CURSOR);
    fixture.detectChanges();
    expect(text(dialog)).toContain('~/.cursor/mcp.json.bak-2026-10-22');
    expect(text(dialog)).toContain('The token is written in plain text');
    button(dialog, 'Confirm and connect').click();
    expect(host.connected).toEqual(['pv_cursor_dev']);
    expect(dialog.hasAttribute('open')).toBe(false);
  });

  it('Disconnect asks first, needs its preview, and says what it removes', async () => {
    const { el, fixture, host } = await render(Host);
    const codex = el.querySelectorAll('article')[1];
    button(codex, 'Disconnect').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(host.starts.map((l) => l.id)).toEqual(['cl_cx_dev']);
    const dialog = Array.from(codex.querySelectorAll('dialog')).find((d) => d.hasAttribute('open'))!;
    expect(document.activeElement).toBe(button(dialog, 'Cancel'));
    const go = button(dialog, 'Disconnect Codex');
    expect(go.getAttribute('aria-disabled')).toBe('true');
    host.leaving.set(DEMO_DISCONNECT_CODEX);
    fixture.detectChanges();
    expect(text(dialog)).toContain('[mcp_servers.gateway-dev] from ~/.codex/config.toml');
    button(dialog, 'Disconnect Codex').click();
    expect(host.gone).toEqual(['pv_codex_dev_out']);
  });

  it('Open config outside the desktop app is unavailable with a reason', async () => {
    const { el, host } = await render(Host);
    const open = button(el.querySelectorAll('article')[1], 'Open config');
    expect(open.getAttribute('aria-disabled')).toBe('true');
    open.click();
    expect(host.opened).toBe(0);
  });

  it('a failed write shows what failed, the fix to copy and Try again', async () => {
    const { el } = await render(Host);
    const failed = el.querySelectorAll('article')[2];
    expect(text(failed.querySelector('.gw-status'))).toBe('Error');
    expect(text(failed.querySelector('.gw-alert__title'))).toBe("Can't write ~/.cursor/mcp.json");
    expect(text(failed.querySelector('pre'))).toBe('sudo chown "$USER" ~/.cursor/mcp.json');
    expect(button(failed, 'Try again')).toBeTruthy();
  });
});

describe('claude.ai card and the sign-in consent', () => {
  @Component({
    imports: [GwClaudeAiCard, GwOAuthConsent],
    template: `
      <article gwClaudeAiCard [tunnel]="tunnel" [preview]="preview" [grants]="grants" [endpoints]="endpoints" (publish)="published.push($event)" (revoke)="revoked.push($event)"></article>
      <article gwClaudeAiCard></article>
      <gw-oauth-consent [consent]="consent()" (decide)="decisions.push($event.decision); consent.set(null)" />
    `,
  })
  class Host {
    tunnel = DEMO_TUNNEL_OFF;
    preview = DEMO_PUBLISH;
    grants = DEMO_GRANTS;
    endpoints = DEMO_CLIENT_ENDPOINTS;
    consent = signal<typeof DEMO_CONSENT | null>(null);
    published: string[] = [];
    revoked: OAuthGrant[] = [];
    decisions: string[] = [];
  }

  it('Publish never acts at once: it lists what becomes public, Cancel has focus', async () => {
    const { el, fixture, host } = await render(Host);
    const card = el.querySelector('article')!;
    button(card, 'Publish for claude.ai').click();
    await fixture.whenStable();
    const dialog = Array.from(card.querySelectorAll('dialog')).find((d) => d.hasAttribute('open'))!;
    expect(text(dialog)).toContain('/mcp/stage-readonly, /oauth/*, /.well-known/oauth-*');
    expect(document.activeElement).toBe(button(dialog, 'Cancel'));
    expect(host.published).toEqual([]);
    button(dialog, 'Publish stage-readonly').click();
    expect(host.published).toEqual(['stage-readonly']);
  });

  it('Revoke asks first', async () => {
    const { el, fixture, host } = await render(Host);
    const card = el.querySelector('article')!;
    button(card, 'Revoke').click();
    fixture.detectChanges();
    await fixture.whenStable();
    const dialog = Array.from(card.querySelectorAll('dialog')).find((d) => d.hasAttribute('open'))!;
    button(dialog, 'Revoke claude.ai access').click();
    expect(host.revoked.map((g) => g.id)).toEqual(['og_7Hc2']);
  });

  it('without a tunnel in this build, the card says so and offers nothing', async () => {
    const { el } = await render(Host);
    const bare = el.querySelectorAll('article')[1];
    expect(text(bare.querySelector('.gw-status'))).toBe('Not available yet');
    expect(bare.querySelector('footer')).toBeNull();
  });

  it('a sign-in request opens with Deny focused; closing it denies', async () => {
    // "Allow access" waits 700 ms after the request appears (activation-guard.spec.ts): a controllable clock.
    let clock = 0;
    vi.spyOn(performance, 'now').mockImplementation(() => clock);
    const { el, fixture, host } = await render(Host);
    host.consent.set(DEMO_CONSENT);
    fixture.detectChanges();
    await fixture.whenStable();
    await Promise.resolve();
    const dialog = el.querySelector(':scope > gw-oauth-consent dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(document.activeElement).toBe(button(dialog, 'Deny'));
    expect(text(dialog)).toContain('4F7-2KQ');
    (dialog as HTMLDialogElement).close();
    expect(host.decisions).toEqual(['deny']);
    host.consent.set({ ...DEMO_CONSENT, id: 'oc_next' });
    fixture.detectChanges();
    await fixture.whenStable();
    await Promise.resolve();
    clock += 700;
    button(dialog, 'Allow access').click();
    expect(host.decisions).toEqual(['deny', 'allow']);
    vi.restoreAllMocks();
  });
});

describe('event-detail and trace view', () => {
  @Component({
    imports: [GwEventDetail, GwTraceView],
    template: `
      @for (d of details; track d.event.id) {
        <article gwEventDetail [detail]="d" [approval]="d === events.approved ? approval : null"></article>
      }
      <section gwTraceView [trace]="trace"></section>
    `,
  })
  class Host {
    events = DEMO_EVENTS;
    details = Object.values(DEMO_EVENTS);
    approval = DEMO_EVENT_APPROVAL;
    trace = DEMO_TRACE;
  }

  it('renders every state with its sections, headed one level below the call', async () => {
    const { el } = await render(Host);
    const articles = Array.from(el.querySelectorAll<HTMLElement>('article.gw-event-detail'));
    expect(articles).toHaveLength(5);
    const sections = (a: HTMLElement) => Array.from(a.querySelectorAll('h4.gw-text-overline')).map(text);
    expect(sections(articles[0])).toEqual(['Request', 'Arguments', 'Policy decision', 'Approval', 'Result', 'Timing', 'Raw event']);
    expect(sections(articles[1])).not.toContain('Approval');
    expect(text(articles[0].querySelector('blockquote'))).toBe('Re-queue the stuck job. Payload checked against SUP-4471.');
    expect(text(articles[0])).toContain('Matches the approved action, byte for byte');
    expect(articles[4].querySelector('.gw-alert--danger')).not.toBeNull();
    for (const a of articles) {
      const labelled = a.getAttribute('aria-labelledby')!.split(' ');
      expect(labelled.every((id) => document.getElementById(id))).toBe(true);
    }
  });

  it('keeps the raw record collapsed until asked, and masked values stay masked', async () => {
    const { el } = await render(Host);
    const a = el.querySelector<HTMLElement>('article')!;
    const raw = a.querySelector<HTMLElement>('[id$="-raw-body"]')!;
    expect(raw.hidden).toBe(true);
    button(a, 'View raw').click();
    expect(raw.hidden).toBe(false);
    expect(text(raw)).toContain('‹masked›');
  });

  it('a trace names its task as unverified and lists its phases', async () => {
    const { el } = await render(Host);
    const trace = el.querySelector('section.gw-event-detail__trace')!;
    expect(text(trace)).toContain('Task name as reported by Codex, unverified');
    expect(Array.from(trace.querySelectorAll('.gw-timeline__title')).map(text)).toEqual([
      'Explore · 9 calls',
      'Change · 3 calls',
      'Wait for deploy · 6 calls',
      'Verify · 5 calls',
    ]);
  });
});

describe('log-explorer', () => {
  @Component({
    imports: [GwLogExplorer],
    template: `<section gwLogExplorer [(query)]="q" [(zoom)]="zoom" [facets]="facets" [histogram]="histogram" [total]="1287" [matched]="matched()" [connectionEnvs]="envs">@if (matched() !== 0) {<p id="table">rows</p>}</section>`,
  })
  class Host {
    q = signal('');
    zoom = signal<string | null>(null);
    matched = signal<number | null>(null);
    facets = DEMO_FACETS;
    histogram = DEMO_HISTOGRAM;
    envs = DEMO_CONNECTION_ENVS;
  }

  it('a checked facet becomes a tag; removing the tag unchecks it', async () => {
    const { el, fixture, host } = await render(Host);
    const denied = el.querySelector<HTMLInputElement>('input[value="denied"]')!;
    denied.click();
    fixture.detectChanges();
    expect(host.q()).toBe('decision:denied');
    expect(Array.from(el.querySelectorAll('.gw-tag__label')).map(text)).toEqual(['decision:denied']);
    el.querySelector<HTMLButtonElement>('.gw-tag__remove')!.click();
    fixture.detectChanges();
    expect(host.q()).toBe('');
    expect(el.querySelector<HTMLInputElement>('input[value="denied"]')!.checked).toBe(false);
  });

  it('Enter turns typed field:value into tags', async () => {
    const { el, fixture, host } = await render(Host);
    const input = el.querySelector<HTMLInputElement>('input[type="search"]')!;
    input.value = 'env:prod sync';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
    expect(host.q()).toBe('env:prod sync');
    expect(el.querySelector('.gw-tag--prod')).not.toBeNull();
    expect(input.value).toBe('sync');
  });

  it('a bar zooms into its hour and the range names it; the page table is projected', async () => {
    const { el, fixture, host } = await render(Host);
    expect(el.querySelector('#table')).not.toBeNull();
    const bars = el.querySelectorAll<HTMLButtonElement>('.gw-chart__bar');
    expect(bars).toHaveLength(24);
    bars[19].click();
    fixture.detectChanges();
    expect(host.zoom()).toBe(DEMO_HISTOGRAM.buckets[19].from);
    expect(bars[19].getAttribute('aria-pressed')).toBe('true');
    bars[19].click();
    fixture.detectChanges();
    expect(host.zoom()).toBeNull();
  });

  it('no match: the empty state replaces the table and clears the filters', async () => {
    const { el, fixture, host } = await render(Host);
    host.q.set('client:cursor');
    host.matched.set(0);
    fixture.detectChanges();
    expect(el.querySelector('#table')).toBeNull();
    expect(text(el.querySelector('.gw-empty__title'))).toBe('No calls match these filters');
    expect(text(el.querySelector('[role="status"]'))).toBe('0 of 1,287 calls');
    button(el, 'Clear filters').click();
    fixture.detectChanges();
    expect(host.q()).toBe('');
  });

  describe('insights variant (design/pages/insights.html)', () => {
    @Component({
      imports: [GwLogExplorer],
      template: `<section gwLogExplorer variant="insights" [(query)]="q" [(range)]="range" [status]="status"></section>`,
    })
    class InsightsHost {
      q = signal('');
      range = signal<LogRange>({ kind: 'relative', key: '24h' });
      status = '1,284 calls · compared with the previous 24 hours';
    }

    it("rows 1 and 2 in the page's words: no Live, Saved views, Export or custom range; the page's count", async () => {
      const { el } = await render(InsightsHost);
      const root = el.querySelector('section')!;
      expect(root.getAttribute('aria-label')).toBe('Filter insights');
      expect(el.querySelector('[role="search"]')!.getAttribute('aria-label')).toBe('Insights');
      const input = el.querySelector<HTMLInputElement>('input[type="search"]')!;
      expect(input.getAttribute('aria-label')).toBe('Filter calls in insights');
      expect(input.placeholder).toBe('Filter: endpoint:dev client:codex env:prod');
      expect(Array.from(el.querySelectorAll('[role="menuitemradio"]')).map(text)).toEqual(['Last hour', 'Last 24 hours', 'Last 7 days', 'Last 30 days']);
      expect(el.textContent).not.toContain('Custom…');
      expect(el.textContent).not.toContain('Saved views');
      expect(el.textContent).not.toContain('Export');
      expect(el.querySelector('[aria-pressed]')).toBeNull();
      expect(el.querySelector('dialog')).toBeNull();
      expect(el.querySelector('.gw-log-explorer__facets, .gw-chart')).toBeNull();
      expect(el.querySelector('h2.gw-popover__title')).not.toBeNull();
      expect(text(el.querySelector('.gw-popover__body p'))).toContain('every chart and number on this page follows it');
      expect(text(el.querySelector('.gw-log-explorer__active p'))).toBe('No filters: every endpoint, AI app and connection. Type field:value above to narrow every chart.');
      expect(text(el.querySelector('[role="status"]'))).toBe('1,284 calls · compared with the previous 24 hours');
    });

    it('a range pick sets the range, Last 30 days included', async () => {
      const { el, fixture, host } = await render(InsightsHost);
      el.querySelector<HTMLButtonElement>('[aria-haspopup="menu"][title]')!.click();
      el.querySelector<HTMLButtonElement>('[role="menuitemradio"][data-value="30d"]')!.click();
      fixture.detectChanges();
      expect(host.range()).toEqual({ kind: 'relative', key: '30d' });
      expect(text(el.querySelector('[data-gw-menu-value]'))).toBe('Last 30 days');
    });
  });
});

describe('trace view and event detail on the Activity page', () => {
  const at = (hm: string) => `2026-10-22T${hm}:00+02:00`;
  const call = (id: number, hm: string, decision: Decision, tool: string, extra: Partial<ActivityEvent> = {}): ActivityEvent => ({
    ...DEMO_EVENTS.allowed.event,
    id,
    callId: `ev_${id}`,
    at: at(hm),
    decision,
    tool,
    ...extra,
  });
  const waiting: TraceDetail = {
    trace: { ...DEMO_TRACE.trace, traceId: 'c7e19d4a02b84f6e9a31d5c8b7f20e64', title: 'Cancel duplicate order 48213', calls: 5, status: 'waiting', decisions: { allowed: 3, pending: 2 }, waitedForPeopleMs: 0 },
    phases: [],
    calls: [
      call(1, '14:24', 'allowed', 'mongo_find'),
      call(4, '14:29', 'pending', 'sql_execute', { approvalId: 'ap_sql', connection: 'dev-db' }),
      call(5, '14:29', 'pending', 'http_request', { approvalId: 'ap_http', connection: 'billing-api' }),
    ],
  };
  const cleanUp: TraceDetail = {
    trace: { ...DEMO_TRACE.trace, traceId: 'b81e44d0920a4203e4b733f52954db89', title: 'Clean up', calls: 14, status: 'ended', decisions: { allowed: 12, denied: 2 }, waitedForPeopleMs: 0 },
    phases: [
      { name: 'Explore', from: at('14:05'), to: at('14:11'), calls: 12, decisions: { allowed: 12 }, summary: 'logs_search on prod-logs' },
      { name: 'Clean up', from: at('14:11'), to: at('14:13'), calls: 2, decisions: { denied: 2 }, summary: 'sql_execute twice' },
    ],
    calls: [call(301, '14:12', 'denied', 'sql_execute')],
  };

  @Component({
    imports: [GwTraceView, GwEventDetail],
    template: `
      <section gwTraceView page id="t-wait" [trace]="waiting" (showCalls)="shown = $event"></section>
      <section gwTraceView page id="t-clean" [trace]="cleanUp"></section>
      <section gwTraceView page id="t-done" [trace]="done"></section>
      <article gwEventDetail id="e-untraced" [detail]="denied" [traced]="false" revokeLabel="codex-laptop" (revoke)="opener = $event"></article>
    `,
  })
  class Host {
    waiting = waiting;
    cleanUp = cleanUp;
    done = DEMO_TRACE;
    denied = DEMO_EVENTS.denied;
    shown: string | null = null;
    opener: HTMLElement | null = null;
  }

  it('a task still waiting says so, names what waits and links to the request', async () => {
    const { el, host } = await render(Host);
    const t = el.querySelector('#t-wait')!;
    expect(text(t.querySelector('.gw-text-small'))).toContain('· 5 calls so far ·');
    expect(text(t.querySelector('.gw-status'))).toBe('In progress, waiting for you');
    expect(Array.from(t.querySelectorAll('.gw-kv__value .gw-badge')).map(text)).toEqual(['3 allowed', '2 waiting for approval']);
    const waitRow = Array.from(t.querySelectorAll('.gw-kv__row')).find((r) => text(r.querySelector('dt')) === 'Waiting for people')!;
    expect(text(waitRow.querySelector('dd'))).toBe('sql_execute and http_request · Review in Approvals: sql_execute on dev-db');
    expect(waitRow.querySelector('a')!.getAttribute('href')).toBe('/approvals/ap_sql');
    const show = Array.from(t.querySelectorAll('a')).find((a) => text(a).startsWith('Show 5 calls'))!;
    expect(text(show)).toBe('Show 5 calls of trace c7e19d');
    expect(show.getAttribute('href')).toBe('/?q=trace:c7e19d');
    show.click();
    expect(host.shown).toBe('c7e19d');
    expect(text(t.querySelector('button'))).toBe('Copy trace id c7e19d');
  });

  it('an ended task folds its phases behind a toggle; a phase that stopped a call links to it', async () => {
    const { el, fixture } = await render(Host);
    const t = el.querySelector('#t-clean')!;
    expect(text(t.querySelector('.gw-text-small'))).not.toContain('so far');
    expect(Array.from(t.querySelectorAll('dt')).map(text)).toEqual(['Started', 'Ended', 'Decisions', 'Phases']);
    expect(Array.from(t.querySelectorAll('.gw-kv__value .gw-badge')).map(text)).toEqual(['12 allowed', '2 denied by policy']);
    const list = t.querySelector<HTMLElement>('ol.gw-timeline')!;
    expect(list.hidden).toBe(true);
    const toggle = t.querySelector<HTMLButtonElement>('button[aria-controls]')!;
    expect(toggle.getAttribute('aria-controls')).toBe(list.id);
    toggle.click();
    fixture.detectChanges();
    expect(list.hidden).toBe(false);
    const link = list.querySelector('a')!;
    expect(text(link)).toBe('Open the denied call');
    expect(link.getAttribute('href')).toBe('/#event-301');
    // A task people waited for shows its phases at once, with the wait.
    const done = el.querySelector('#t-done')!;
    expect(text(done.querySelector('.gw-kv__row:last-child'))).toBe('Waited for people 47 s');
    expect(done.querySelector<HTMLElement>('ol.gw-timeline')!.hidden).toBe(false);
  });

  it('the drawer hides Open trace for a call outside a task and offers Revoke of its access', async () => {
    const { el, host } = await render(Host);
    const e = el.querySelector('#e-untraced')!;
    expect(Array.from(e.querySelectorAll('a')).some((a) => text(a) === 'Open trace')).toBe(false);
    const revoke = e.querySelector<HTMLButtonElement>('.gw-btn--danger-ghost')!;
    expect(text(revoke)).toBe('Revoke codex-laptop');
    expect(revoke.getAttribute('aria-haspopup')).toBe('dialog');
    revoke.click();
    expect(host.opener).toBe(revoke);
  });
});

describe('log-explorer on the Activity page', () => {
  @Component({
    imports: [GwLogExplorer],
    template: `<section gwLogExplorer page [(query)]="q" [facets]="facets" [histogram]="histogram" [total]="1287" [waiting]="3" [matched]="matched()" customNote="The audit log keeps calls for 1 year.">@if (matched() !== 0) {<p id="table">rows</p>} @else {<p id="page-empty">empty</p>}</section>`,
  })
  class Host {
    q = signal('');
    matched = signal<number | null>(1287);
    facets = DEMO_FACETS;
    histogram = DEMO_HISTOGRAM;
  }

  it('leaves Export and the empty state to the page, counts what waits and draws four gridlines', async () => {
    const { el, fixture, host } = await render(Host);
    expect(Array.from(el.querySelectorAll('button')).some((b) => text(b).startsWith('Export'))).toBe(false);
    expect(text(el.querySelector('[role="status"]'))).toBe('1,287 calls · 3 waiting for approval');
    expect(el.querySelectorAll('.gw-chart__gridline')).toHaveLength(4);
    expect(el.querySelector('.gw-popover h2')).not.toBeNull();
    host.q.set('client:cursor');
    host.matched.set(0);
    fixture.detectChanges();
    expect(el.querySelector('#page-empty')).not.toBeNull();
    expect(el.querySelector('.gw-empty')).toBeNull();
    expect(el.querySelector('.gw-chart')).not.toBeNull();
  });
});
