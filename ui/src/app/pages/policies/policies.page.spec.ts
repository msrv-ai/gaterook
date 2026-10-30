import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { ActivityApi, ActivityEvent, EventsPage } from '../../core/api/activity.api';
import { ApiError } from '../../core/api/api-error';
import { ConnectionsApi } from '../../core/api/connections.api';
import { EndpointsApi } from '../../core/api/endpoints.api';
import { NotificationsApi } from '../../core/api/notifications.api';
import { EnvPolicy, Policies, PoliciesApi } from '../../core/api/policies.api';
import { Template, TemplatesApi } from '../../core/api/templates.api';
import { Env, JsonObject, JsonValue } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';
import { CoreStore } from '../../core/stores/core.store';
import { relativeTime } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { kitConnections, kitEndpoints, kitPoliciesAnswer, kitRunbookTemplates, kitSchemas } from './policies.fixtures';
import { PoliciesPage, SKELETON_DELAY_MS, envOfFragment } from './policies.page';
import { fallback } from './policy-model';

/** The page against fake admin API services: what it shows for each answer, and what it asks the core to do. */

const NOW = Date.parse('2026-10-22T14:30:00+02:00');
const now = signal(NOW);
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  relative: (t) => (t ? relativeTime(t, NOW) : ''),
  absolute: (t) => (t ? `abs ${t}` : ''),
  iso: (t) => t ?? '',
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

function event(env: Env, tool: string, connection: string, token: string | null, over: Partial<ActivityEvent> = {}): ActivityEvent {
  return {
    id: 1,
    callId: 'c1',
    at: new Date(NOW - 20_000).toISOString(),
    phase: 'completed',
    decision: 'allowed',
    tool,
    connection,
    env,
    client: null,
    endpoint: 'dev',
    tokenLabel: token,
    durationMs: 12,
    rule: null,
    approvalId: null,
    traceId: null,
    errorKind: null,
    errorMessage: null,
    ...over,
  } as ActivityEvent;
}

/**
 * The core as far as the page talks to it: policies change as the real PolicyService changes them (only what is
 * sent, `null` unsets, the version moves), so what the page shows after a save is what it would show live.
 */
class FakeCore {
  answer = kitPoliciesAnswer();
  readonly schemas = kitSchemas();
  calls: string[] = [];
  allAnswer: (() => Promise<Policies>) | null = null;
  updateFails: unknown = null;
  resetFails: unknown = null;
  keepFails: ((confirm: string | undefined) => unknown) | null = null;
  usage: Partial<Record<Env, ActivityEvent[]>> = {};
  lastRuns: Record<string, string> = {};
  definitions: Record<string, JsonObject> = {};

  policies(): Promise<Policies> {
    this.calls.push('all');
    return this.allAnswer ? this.allAnswer() : Promise.resolve(structuredClone(this.answer));
  }

  update(env: Env, body: { sections: JsonObject; version: number; confirm?: string | null }): Promise<EnvPolicy> {
    this.calls.push(`update ${env} ${JSON.stringify(body)}`);
    if (this.updateFails) return Promise.reject(this.updateFails);
    const p = structuredClone(this.answer[env]) as { -readonly [K in keyof EnvPolicy]: EnvPolicy[K] };
    const explicit = p.explicit as Record<string, Record<string, JsonValue>>;
    const sections = p.sections as Record<string, Record<string, JsonValue>>;
    for (const [s, values] of Object.entries(body.sections)) {
      for (const [k, v] of Object.entries(values as JsonObject)) {
        explicit[s] ??= {};
        if (v === null) delete explicit[s][k];
        else explicit[s][k] = v;
        if (!Object.keys(explicit[s]).length) delete explicit[s];
        sections[s][k] = v ?? (fallback({ dev: this.answer.dev, stage: this.answer.stage, prod: this.answer.prod }, this.schemas, env, s, k).value as JsonValue);
      }
    }
    p.version++;
    this.answer = { ...this.answer, [env]: p };
    return Promise.resolve(structuredClone(p));
  }

  reset(env: Env): Promise<EnvPolicy> {
    this.calls.push(`reset ${env}`);
    if (this.resetFails) return Promise.reject(this.resetFails);
    const p = { ...this.answer[env], explicit: {}, version: this.answer[env].version + 1 };
    this.answer = { ...this.answer, [env]: p };
    return Promise.resolve(structuredClone(p));
  }

  keep(env: Env, body: { confirm?: string | null }): Promise<EnvPolicy> {
    this.calls.push(`keep ${env} ${body.confirm ?? '-'}`);
    const fails = this.keepFails?.(body.confirm ?? undefined);
    if (fails) return Promise.reject(fails);
    const p = { ...this.answer[env], verified: true, serving: true };
    this.answer = { ...this.answer, [env]: p };
    return Promise.resolve(structuredClone(p));
  }

  events(range: { q?: string; limit?: number }): Promise<EventsPage> {
    this.calls.push(`events ${range.q}`);
    const env = range.q?.match(/^env:(\w+)$/)?.[1] as Env | undefined;
    if (env) return Promise.resolve({ events: this.usage[env] ?? [], nextCursor: null });
    const runbook = range.q?.match(/runbook:(\S+)$/)?.[1];
    const at = runbook ? this.lastRuns[runbook] : undefined;
    return Promise.resolve({ events: at ? [event('dev', 'ssh_run', 'dev-vm', null, { at })] : [], nextCursor: null });
  }

  template(id: string): Promise<Template> {
    const t = kitRunbookTemplates().find((x) => x.id === id)!;
    return Promise.resolve({ ...t, ...(this.definitions[t.name] ? { definition: this.definitions[t.name] } : {}) } as Template);
  }
}

let core: FakeCore;
let events: FakeEvents;
let fragment: BehaviorSubject<string | null>;
let running: ReturnType<typeof signal<boolean>>;
let navigate: ReturnType<typeof vi.fn>;

function setup(): void {
  core = new FakeCore();
  events = new FakeEvents();
  fragment = new BehaviorSubject<string | null>(null);
  running = signal(true);
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      { provide: ActivatedRoute, useValue: { fragment } },
      { provide: TimeService, useValue: fakeTime },
      {
        provide: PoliciesApi,
        useValue: {
          all: () => core.policies(),
          schema: () => {
            core.calls.push('schema');
            return Promise.resolve(core.schemas);
          },
          update: (env: Env, body: { sections: JsonObject; version: number; confirm?: string | null }) => core.update(env, body),
          reset: (env: Env) => core.reset(env),
          confirmIntegrity: (env: Env, body: { confirm?: string | null }) => core.keep(env, body),
        },
      },
      { provide: ConnectionsApi, useValue: { list: () => Promise.resolve({ items: kitConnections(), facets: { health: {}, env: {}, type: {} }, total: 12 }) } },
      { provide: EndpointsApi, useValue: { list: () => Promise.resolve(kitEndpoints()) } },
      { provide: TemplatesApi, useValue: { list: () => Promise.resolve(kitRunbookTemplates()), get: (id: string) => core.template(id) } },
      { provide: ActivityApi, useValue: { events: (range: { q?: string }) => core.events(range) } },
      { provide: NotificationsApi, useValue: { get: () => Promise.reject(new ApiError('not-available', 404, {})) } },
      { provide: EventStream, useValue: events },
      { provide: CoreStore, useValue: { running, instanceChanges: signal(0) } },
    ],
  });
  TestBed.inject(AriaDisabledGuard).install();
  navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true) as unknown as ReturnType<typeof vi.fn>;
}

afterEach(() => {
  document.body.innerHTML = '';
  TestBed.inject(ToastService).clear();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function render(env: string | null = 'dev'): Promise<{ fixture: ComponentFixture<PoliciesPage>; el: HTMLElement }> {
  const fixture = TestBed.createComponent(PoliciesPage);
  fixture.componentRef.setInput('env', env);
  document.body.appendChild(fixture.nativeElement);
  await settle(fixture);
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 6; i++) {
    await fixture.whenStable();
    await sleep(0);
  }
  fixture.detectChanges();
  await fixture.whenStable();
}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const $ = <T extends HTMLElement = HTMLElement>(el: HTMLElement, selector: string) => el.querySelector<T>(selector)!;
const type = (input: HTMLInputElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event('input'));
};
const choose = (select: HTMLSelectElement, value: string) => {
  select.value = value;
  select.dispatchEvent(new Event('input'));
  select.dispatchEvent(new Event('change'));
};
const toasts = () => TestBed.inject(ToastService).toasts();
const updates = () => core.calls.filter((c) => c.startsWith('update'));

describe('Policies page', () => {
  it('shows nothing for a moment, then a skeleton, then the three environments', async () => {
    setup();
    const answer = deferred<Policies>();
    core.allAnswer = () => answer.promise;
    const { fixture, el } = await render();
    expect(text($(el, 'h1'))).toBe('Policies');
    expect(el.querySelector('.gw-skeleton')).toBeNull();

    await sleep(SKELETON_DELAY_MS + 50);
    await settle(fixture);
    expect(el.querySelector('[aria-busy="true"] [role="status"]')?.textContent).toBe('Loading policies');
    expect(el.querySelectorAll('.gw-skeleton').length).toBeGreaterThan(3);

    answer.resolve(kitPoliciesAnswer());
    await settle(fixture);
    expect(el.querySelector('.gw-skeleton')).toBeNull();
    expect(Array.from(el.querySelectorAll('[role="tab"]')).map(text)).toEqual(['dev Development 6 connections', 'stage Staging 3 connections', 'prod Production 3 connections']);
    expect(el.querySelectorAll('[role="tabpanel"]')).toHaveLength(3);
  });

  it('says what went wrong when the policies don’t load, with details, and tries again', async () => {
    setup();
    core.allAnswer = () => Promise.reject(new ApiError('server', 500, { title: 'The core failed', detail: 'The policy table could not be read.' }));
    const { fixture, el } = await render();
    const alert = $(el, '.gw-alert--danger[role="alert"]');
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The policies didn't load");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('The policy table could not be read.');
    const details = $(el, '#pol-error-details');
    expect(details.hidden).toBe(true);
    expect(details.textContent).toContain('GET /api/policies → 500');

    core.allAnswer = null;
    Array.from(alert.querySelectorAll('button')).find((b) => text(b) === 'Try again')!.click();
    await settle(fixture);
    expect(el.querySelector('.gw-alert--danger')).toBeNull();
    expect(el.querySelectorAll('[role="tabpanel"]')).toHaveLength(3);
  });

  it('keeps the policies on screen when a reload fails, and says they may be out of date', async () => {
    setup();
    const { fixture, el } = await render();
    core.allAnswer = () => Promise.reject(new ApiError('network', 0, { title: 'No answer', detail: 'The core didn’t answer.' }));
    events.emit('integrity.alert', { kind: 'policy' });
    await settle(fixture);
    expect(text(el.querySelector('.gw-alert--warning[role="status"] .gw-alert__title'))).toBe('These policies may be out of date.');
    expect(el.querySelectorAll('[role="tabpanel"]')).toHaveLength(3);
  });

  it('shows the environment the route names, and moves the route with the tabs and deep links', async () => {
    setup();
    const { fixture, el } = await render('stage');
    expect($(el, '#pol-tab-stage').getAttribute('aria-selected')).toBe('true');
    expect($(el, '#pol-tab-dev').getAttribute('aria-selected')).toBe('false');
    expect($(el, '#stage').hidden).toBe(false);
    expect($(el, '#dev').hidden).toBe(true);
    expect($(el, '#stage').getAttribute('aria-labelledby')).toBe('pol-tab-stage');
    expect(text($(el, '.gw-page-header__description'))).toContain("Stage inherits each value it doesn't set from dev; prod has its own, stricter defaults.");

    $(el, '#pol-tab-prod').click();
    await settle(fixture);
    expect(navigate).toHaveBeenCalledWith(['/policies', 'prod'], { replaceUrl: true });

    fragment.next('prod-approvals');
    await settle(fixture);
    expect(navigate).toHaveBeenCalledWith(['/policies', 'prod'], { fragment: 'prod-approvals', replaceUrl: true });

    fixture.componentRef.setInput('env', 'qa');
    fragment.next(null);
    await settle(fixture);
    expect(navigate).toHaveBeenCalledWith(['/policies', 'dev'], { replaceUrl: true });
    expect($(el, '#dev').hidden).toBe(false);
    expect(envOfFragment('stage-http')).toBe('stage');
    expect(envOfFragment('more')).toBeNull();
  });

  it('lays stage out as the kit does, with where each value comes from', async () => {
    setup();
    const { el } = await render('stage');
    const stage = $(el, '#stage');
    const kv = (key: string) => text(Array.from(stage.querySelectorAll('.gw-toolbar .gw-kv__row')).find((r) => text(r.querySelector('dt')) === key)?.querySelector('dd'));
    expect(kv('Applies to')).toBe('stage-db aws-stage billing-api');
    expect(kv('Values')).toBe('3 stage defaults · 6 set on stage · 32 inherited from dev');
    expect(text(stage.querySelector('.gw-toolbar .gw-btn--danger-ghost'))).toBe('Reset to dev');
    expect(Array.from(stage.querySelectorAll('form > section')).map((s) => s.id)).toEqual([
      'stage-sql',
      'stage-ssh',
      'stage-k8s',
      'stage-cloud',
      'stage-http',
      'stage-logs',
      'stage-masking',
      'stage-limits',
      'stage-approvals',
    ]);

    expect($<HTMLInputElement>(stage, '#ps-sql-rowLimit').value).toBe('500');
    expect(text($(stage, '#ps-sql-rowLimit-hint'))).toBe('Set on stage · dev: 1,000 rows');
    expect($<HTMLInputElement>(stage, '#ps-sql-statementTimeoutMs').value).toBe('');
    expect($<HTMLInputElement>(stage, '#ps-sql-statementTimeoutMs').placeholder).toBe('5');
    expect(text($(stage, '#ps-sql-statementTimeoutMs-hint'))).toBe('Inherited from dev: 5 s');
    expect(text(stage.querySelector('#stage-sql code.gw-code'))).toBe('stage.sql.write-approval');
    expect(text(stage.querySelector('#stage-masking code.gw-code--secret'))).toBe('‹masked›');

    // No SSH connection on stage: the kit's empty card and the rules stage will have, read only.
    const ssh = $(stage, '#stage-ssh');
    expect(text(ssh.querySelector('.gw-empty__title'))).toBe('No SSH connection on stage');
    expect(ssh.querySelector('input')).toBeNull();
    expect(ssh.querySelector('a[href^="/connections/new"]')).not.toBeNull();

    const comment = $<HTMLSelectElement>(stage, '#ps-approvals-comment');
    expect(comment.value).toBe('');
    expect(text(comment.options[0])).toBe('Same as dev: optional');
    expect(el.querySelector('.gw-form__actions')).toBeNull();
  });

  it('saves only what changes and starts again from the core’s answer', async () => {
    setup();
    const { fixture, el } = await render('prod');
    choose($<HTMLSelectElement>(el, '#pp-approvals-timeoutMinutes'), '15');
    await settle(fixture);
    expect(text($(el, '#pp-unsaved'))).toBe('Unsaved changes · approval timeout on prod, 10 to 15 min');
    expect(text($(el, '#pp-approvals-timeoutMinutes-hint'))).toBe('Unsaved · prod default: 10 min');
    expect($(el, '#pp-form').classList).toContain('gw-form--sticky-actions');

    $(el, '#pp-form button[type="submit"]').click();
    await settle(fixture);
    expect(updates()).toEqual(['update prod {"sections":{"approvals":{"timeoutMinutes":15}},"version":2}']);
    expect(toasts().map((t) => `${t.title}: ${t.body}`)).toEqual(['Prod policy saved: Approval timeout on prod: 15 min. Requests already waiting keep their timer.']);
    expect(el.querySelector('#pp-form .gw-form__actions')).toBeNull();
    expect($<HTMLSelectElement>(el, '#pp-approvals-timeoutMinutes').value).toBe('15');
    expect(text($(el, '#pp-approvals-timeoutMinutes-hint'))).toBe('Set on prod · dev: 5 min');
    expect(document.activeElement).toBe($(el, '#prod'));
  });

  it('starts with unlimited log ranges and can save a cap beyond a year or remove it', async () => {
    setup();
    const { fixture, el } = await render('prod');
    const range = () => $<HTMLInputElement>(el, '#pp-logs-maxRangeHours');
    expect(range().value).toBe('0');
    expect(text($(el, '#pp-logs-maxRangeHours-hint'))).toContain('0 means no time limit.');

    type(range(), '17520');
    await settle(fixture);
    $(el, '#pp-form button[type="submit"]').click();
    await settle(fixture);
    expect(updates()).toEqual(['update prod {"sections":{"logs":{"maxRangeHours":17520}},"version":2}']);
    expect(range().value).toBe('17,520');

    type(range(), '0');
    await settle(fixture);
    expect(text($(el, '#pp-unsaved'))).toContain('17,520 h to no limit');
    $(el, '#pp-form button[type="submit"]').click();
    await settle(fixture);
    expect(updates()[1]).toBe('update prod {"sections":{"logs":{"maxRangeHours":null}},"version":3}');
    expect(range().value).toBe('0');
  });

  it('keeps the log time limit editable with only a GCP connection', async () => {
    setup();
    const sample = kitConnections().find((connection) => connection.env === 'prod')!;
    const connection = { ...sample, type: 'gcp', name: 'production-logs' };
    vi.spyOn(TestBed.inject(ConnectionsApi), 'list').mockResolvedValue({
      items: [connection], facets: { health: {}, env: {}, type: {} }, total: 1,
    });
    const { fixture, el } = await render('prod');
    const section = $(el, '#prod-logs');
    expect(section.querySelector('.gw-empty')).toBeNull();
    const range = $<HTMLInputElement>(section, '#pp-logs-maxRangeHours');
    expect(range).not.toBeNull();
    expect(range.value).toBe('0');
    expect(range.disabled).toBe(false);

    type(range, '36');
    await settle(fixture);
    $(el, '#pp-form button[type="submit"]').click();
    await settle(fixture);
    expect(updates()).toEqual(['update prod {"sections":{"logs":{"maxRangeHours":36}},"version":2}']);
  });

  it('checks numbers before saving and puts the keyboard on the first wrong one', async () => {
    setup();
    const { fixture, el } = await render('dev');
    type($<HTMLInputElement>(el, '#pd-limits-rateLimitPerToken'), '0');
    await settle(fixture);
    expect(el.querySelector('#pd-limits-rateLimitPerToken-error')).toBeNull();
    $(el, '#pd-form button[type="submit"]').click();
    await settle(fixture);
    expect(text($(el, '#pd-limits-rateLimitPerToken-error'))).toBe('Enter a whole number from 1 to 10,000 calls per min.');
    expect($(el, '#pd-limits-rateLimitPerToken').getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe($(el, '#pd-limits-rateLimitPerToken'));
    expect(updates()).toEqual([]);

    type($<HTMLInputElement>(el, '#pd-limits-rateLimitPerToken'), '100');
    await settle(fixture);
    $(el, '#pd-form button[type="submit"]').click();
    await settle(fixture);
    expect(updates()).toEqual(['update dev {"sections":{"limits":{"rateLimitPerToken":100}},"version":4}']);
    expect(toasts()[0].body).toBe('Rate limit per token on dev: 100 calls per min. Stage follows wherever it sets nothing itself.');
    // Stage follows dev's new value after the reload.
    expect(text($(el, '#ps-limits-rateLimitPerToken-hint'))).toBe('Inherited from dev: 100 calls per min');
  });

  it('shows the core’s refusal at the field it names, and a stale version above Save', async () => {
    setup();
    const { fixture, el } = await render('stage');
    type($<HTMLInputElement>(el, '#ps-sql-rowLimit'), '750');
    await settle(fixture);
    core.updateFails = new ApiError('unprocessable', 422, { title: 'Not stricter', detail: 'Stage can’t allow more rows than dev.', field: '/sections/sql/rowLimit' });
    $(el, '#ps-form button[type="submit"]').click();
    await settle(fixture);
    expect(text($(el, '#ps-sql-rowLimit-error'))).toBe('Stage can’t allow more rows than dev.');
    expect(document.activeElement).toBe($(el, '#ps-sql-rowLimit'));

    // Changing the value clears the refusal; a stale version is said above Save.
    type($<HTMLInputElement>(el, '#ps-sql-rowLimit'), '700');
    await settle(fixture);
    expect(el.querySelector('#ps-sql-rowLimit-error')).toBeNull();
    core.updateFails = new ApiError('conflict', 409, { title: 'Changed', detail: 'Someone saved the stage policy meanwhile.' });
    $(el, '#ps-form button[type="submit"]').click();
    await settle(fixture);
    const alert = $(el, '#ps-form .gw-form__actions [role="alert"]');
    expect(text(alert.querySelector('.gw-alert__title'))).toBe("The stage policy wasn't saved.");
    expect(text(alert.querySelector('.gw-alert__body'))).toBe('Someone saved the stage policy meanwhile. Discard your changes to see the current values, then make them again.');
    expect(document.activeElement).toBe(alert);
  });

  it('discards unsaved changes back to the stored policy', async () => {
    setup();
    const { fixture, el } = await render('stage');
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    type($<HTMLInputElement>(el, '#ps-sql-rowLimit'), '250');
    $<HTMLInputElement>(el, '#ps-sql-denyDdl').click();
    await settle(fixture);
    expect(text($(el, '#ps-unsaved'))).toBe('Unsaved changes · row limit and DDL on stage');

    $(el, '#ps-form button[type="reset"]').click();
    await settle(fixture);
    expect($<HTMLInputElement>(el, '#ps-sql-rowLimit').value).toBe('500');
    expect($<HTMLInputElement>(el, '#ps-sql-denyDdl').checked).toBe(true);
    expect(el.querySelector('#ps-form .gw-form__actions')).toBeNull();
    expect(announce).toHaveBeenCalledWith('Changes to the stage policy discarded');
    expect(document.activeElement).toBe($(el, '#stage'));
  });

  it('asks before allowing ssh_exec on prod, and saves the yes with the change', async () => {
    setup();
    const { fixture, el } = await render('prod');
    const box = $<HTMLInputElement>(el, '#pp-ssh-allowExec');
    box.click();
    await settle(fixture);
    const dialog = $<HTMLDialogElement>(el, '#pol-dlg-prod-exec');
    expect(dialog.open).toBe(true);
    expect(dialog.className).toContain('gw-dialog--danger');
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Allow any shell command on prod-vm?');
    expect(text(dialog.querySelector('.gw-dialog__description'))).toBe(
      'Agents could ask to run anything on prod-vm, not only its two runbooks. Commands need your approval unless ssh_exec uses Bypass. Policy denials and limits still apply.',
    );
    expect(text(document.activeElement)).toBe('Keep it off');
    expect(box.checked).toBe(false);

    (document.activeElement as HTMLElement).click();
    await settle(fixture);
    expect(dialog.open).toBe(false);
    expect(box.checked).toBe(false);
    expect(document.activeElement).toBe(box);

    box.click();
    await settle(fixture);
    Array.from(dialog.querySelectorAll('button')).find((b) => text(b) === 'Allow ssh_exec on prod')!.click();
    await settle(fixture);
    expect(box.checked).toBe(true);
    expect(text($(el, '#pp-unsaved'))).toBe('Unsaved changes · ssh_exec on prod, allowed');

    $(el, '#pp-form button[type="submit"]').click();
    await settle(fixture);
    expect(updates()).toEqual(['update prod {"sections":{"ssh":{"allowExec":true}},"version":2,"confirm":"danger"}']);
  });

  it('resets stage to dev after asking, and says what went back', async () => {
    setup();
    const { fixture, el } = await render('stage');
    const trigger = $(el, '#stage .gw-toolbar .gw-btn--danger-ghost');
    trigger.click();
    await settle(fixture);
    const dialog = $<HTMLDialogElement>(el, '#pol-dlg-reset-stage');
    expect(dialog.open).toBe(true);
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text(dialog.querySelector('.gw-dialog__title'))).toBe('Reset the stage policy to dev?');
    expect(text(dialog.querySelector('.gw-dialog__description'))).toBe(
      'The 6 values set on stage go back to dev: row limit 500 to 1,000 rows, max response 256 to 512 KB, allowed services to none, regions to none, resource patterns to none, allowed base URLs to none.',
    );

    core.resetFails = new ApiError('server', 500, { title: 'Failed', detail: 'The core couldn’t write the policy.' });
    $(dialog, '.gw-btn--danger').click();
    await settle(fixture);
    expect(dialog.open).toBe(true);
    expect(text(dialog.querySelector('[role="alert"] .gw-alert__title'))).toBe("The stage policy wasn't reset.");
    expect(text(dialog.querySelector('[role="alert"] .gw-alert__body'))).toBe('The core couldn’t write the policy.');

    core.resetFails = null;
    $(dialog, '.gw-btn--danger').click();
    await settle(fixture);
    expect(core.calls.filter((c) => c.startsWith('reset'))).toEqual(['reset stage', 'reset stage']);
    expect(dialog.open).toBe(false);
    expect(toasts().map((t) => `${t.title}: ${t.body}`)).toEqual(['Stage policy reset: 6 values on stage now follow dev.']);
    expect($<HTMLInputElement>(el, '#ps-sql-rowLimit').value).toBe('');
    expect(el.querySelector('#stage .gw-toolbar .gw-btn--danger-ghost')).toBeNull();
    expect(document.activeElement).toBe($(el, '#stage'));
  });

  it('denies calls on a policy changed outside GateRook until it is reviewed and kept', async () => {
    setup();
    core.answer = { ...core.answer, prod: { ...core.answer.prod, verified: false, serving: false, explicit: { ...core.answer.prod.explicit, ssh: { allowExec: true } } } };
    core.keepFails = (confirm) => (confirm ? null : new ApiError('conflict', 409, { title: 'Confirm', detail: 'The stored policy allows ssh_exec on prod.', confirm: 'danger' }));
    const { fixture, el } = await render('prod');
    const alert = $(el, '#prod .gw-alert--warning');
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('The prod policy was changed outside GateRook');
    expect(text(alert.querySelector('.gw-alert__body'))).toBe(
      'Calls on prod are denied until you review it. Keep it as stored if you made the change yourself, or reset it to its defaults. The values below are what prod would have without it.',
    );

    Array.from(alert.querySelectorAll('button')).find((b) => text(b).startsWith('Review and keep'))!.click();
    await settle(fixture);
    const dialog = $<HTMLDialogElement>(el, '#pol-dlg-keep-prod');
    expect(dialog.open).toBe(true);
    expect(dialog.className).toContain('gw-dialog--warning');
    expect(text(document.activeElement)).toBe('Cancel');
    expect(text(dialog.querySelector('.gw-dialog__body p'))).toBe(
      'What it sets: table allowlist public.orders, public.order_items, public.payments, masked fields 5 items, ssh_exec allowed.',
    );

    $(dialog, '.gw-btn--primary').click();
    await settle(fixture);
    expect(text(dialog.querySelector('[role="alert"] .gw-alert__body'))).toBe('The stored policy allows ssh_exec on prod. Keep it again to confirm.');
    $(dialog, '.gw-btn--primary').click();
    await settle(fixture);
    expect(core.calls.filter((c) => c.startsWith('keep'))).toEqual(['keep prod -', 'keep prod danger']);
    expect(dialog.open).toBe(false);
    expect(toasts().map((t) => t.title)).toEqual(['prod policy kept as stored']);
    expect(el.querySelector('#prod .gw-alert--warning')).toBeNull();
  });

  it('tells stage when the dev policy it inherits was changed outside GateRook', async () => {
    setup();
    core.answer = { ...core.answer, dev: { ...core.answer.dev, verified: false, serving: false }, stage: { ...core.answer.stage, serving: false } };
    const { el } = await render('stage');
    const alert = $(el, '#stage .gw-alert--warning');
    expect(text(alert.querySelector('.gw-alert__title'))).toBe('stage inherits the dev policy, which was changed outside GateRook');
    expect(alert.querySelector('a')?.getAttribute('href')).toBe('/policies/dev');
    expect(text($(el, '#dev .gw-alert--warning .gw-alert__body'))).toContain('Calls on dev and stage are denied until you review it.');
  });

  it('lists the runbooks of each SSH host with what the core knows of them', async () => {
    setup();
    core.lastRuns = { 'compose-ps': new Date(NOW - 120_000).toISOString() };
    core.definitions = {
      'compose-ps': {
        name: 'compose-ps',
        description: 'List the containers and their state',
        command: 'docker compose ps {service}',
        readOnly: true,
        readOnlyConfirmedFor: 'docker compose ps {service}',
        params: [{ name: 'service', type: 'enum', values: ['api', 'worker'] }],
      },
    };
    const { el } = await render('dev');
    const cards = Array.from(el.querySelectorAll('#dev-ssh ul[aria-label="Runbooks on dev"] > li'));
    expect(cards.map((c) => text(c.querySelector('.gw-card__title')))).toEqual(['compose-logs', 'compose-ps', 'compose-restart']);
    const ps = cards[1];
    expect(text(ps.querySelector('.gw-card__description'))).toBe('List the containers and their state');
    expect(text(ps.querySelector('.gw-mode'))).toBe('Read only');
    const row = (card: Element, key: string) => text(Array.from(card.querySelectorAll('.gw-kv__row')).find((r) => text(r.querySelector('dt')) === key)?.querySelector('dd'));
    expect(row(ps, 'Command')).toBe('docker compose ps {service}');
    expect(row(ps, 'Parameters')).toBe('service optional, one of api, worker');
    expect(row(ps, 'Last run')).toBe('2 min ago · View runbook activity on dev-vm');
    // Without a definition the core says nothing about the command or the mode: the page doesn't guess.
    expect(cards[0].querySelector('.gw-mode')).toBeNull();
    expect(row(cards[0], 'Command')).toBe('');
    expect(row(cards[0], 'Last run')).toBe('Never');
  });

  it("takes a runbook's last run from the core's answer (step API) without asking Activity", async () => {
    setup();
    const ran = new Date(NOW - 5 * 3_600_000).toISOString();
    core.answer = {
      ...core.answer,
      dev: { ...core.answer.dev, runbooks: core.answer.dev.runbooks.map((r) => (r.name === 'compose-ps' ? { ...r, lastRunAt: ran } : r)) },
    };
    const { el } = await render('dev');
    const ps = Array.from(el.querySelectorAll('#dev-ssh ul[aria-label="Runbooks on dev"] > li')).find(
      (c) => text(c.querySelector('.gw-card__title')) === 'compose-ps',
    )!;
    const lastRun = Array.from(ps.querySelectorAll('.gw-kv__row')).find((r) => text(r.querySelector('dt')) === 'Last run');
    expect(text(lastRun?.querySelector('dd'))).toBe('5 h ago · View runbook activity on dev-vm');
    expect(core.calls.filter((c) => c.includes('runbook:compose-ps'))).toEqual([]);
  });

  it('fills the rate-limit meters from the last minute and follows the core’s events', async () => {
    setup();
    core.usage = { dev: Array.from({ length: 24 }, () => event('dev', 'sql_query', 'dev-db', 'codex-laptop')) };
    const { fixture, el } = await render('dev');
    const meters = el.querySelectorAll<HTMLElement>('#dev-limits [role="meter"]');
    expect(meters).toHaveLength(2);
    expect(text(meters[0])).toBe('Busiest token codex-laptop 24 / 120');
    expect(meters[0].dataset['level']).toBe('ok');
    expect(text(meters[1])).toBe('Busiest tool sql_query on dev-db 24 / 30');
    expect(meters[1].dataset['level']).toBe('warn');
    expect(meters[1].getAttribute('aria-valuetext')).toBe('24 of 30 calls in the last minute, near the limit');

    // A call comes in: the meters recount once, a second later.
    core.usage = { dev: Array.from({ length: 30 }, () => event('dev', 'sql_query', 'dev-db', 'codex-laptop')) };
    const before = core.calls.filter((c) => c === 'events env:dev').length;
    events.emit('audit.call', { phase: 'received', tool: 'sql_query' });
    events.emit('audit.call', { phase: 'received', tool: 'sql_query' });
    await sleep(1100);
    await settle(fixture);
    expect(core.calls.filter((c) => c === 'events env:dev').length).toBe(before + 1);
    expect(el.querySelectorAll<HTMLElement>('#dev-limits [role="meter"]')[1].dataset['level']).toBe('critical');

    // A policy row that stops verifying: the page reads the policies again.
    const loads = core.calls.filter((c) => c === 'all').length;
    events.emit('integrity.alert', { kind: 'policy' });
    events.emit('integrity.alert', { kind: 'endpoint' });
    await settle(fixture);
    expect(core.calls.filter((c) => c === 'all').length).toBe(loads + 1);

    // The core came back after being away.
    running.set(false);
    await settle(fixture);
    running.set(true);
    await settle(fixture);
    expect(core.calls.filter((c) => c === 'all').length).toBe(loads + 2);
  });

  it('validates list entries where they are typed', async () => {
    setup();
    const { fixture, el } = await render('stage');
    const input = $<HTMLInputElement>(el, '#ps-http-allowedBaseUrls');
    type(input, 'http://169.254.169.254/latest/meta-data/');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);
    expect(text($(el, '#ps-http-allowedBaseUrls-error'))).toBe('169.254.169.254 is a cloud metadata address and is always blocked. Add the public base URL of the API instead.');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(el.querySelector('#ps-form .gw-form__actions')).toBeNull();

    type(input, 'https://api.stripe.com');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);
    expect(el.querySelector('#ps-http-allowedBaseUrls-error')).toBeNull();
    expect(text($(el, '#ps-unsaved'))).toBe('Unsaved changes · allowed base URLs on stage, to https://billing.stage.acme.dev, https://api.stripe.com');
  });
});
