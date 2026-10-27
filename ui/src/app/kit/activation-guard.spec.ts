import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApprovalDecision, ApprovalDetail } from '../core/api/approvals.api';
import { OAuthConsent } from '../core/api/tunnel.api';
import { TimeService } from '../core/time/time.service';
import { DEMO_APPROVALS } from '../dev/fixtures-approvals';
import { DEMO_CONSENT } from '../dev/fixtures-clients';
import { ACTIVATION_DELAY_MS, ActivationGuard } from './activation-guard';
import { AriaDisabledGuard } from './aria-disabled';
import { GwApprovalRequest } from './elements/approval-request/approval-request';
import { GwOAuthConsent } from './elements/client-card/oauth-consent';
import { provideKitIcons } from './icons';

/**
 * Self-approval through the GUI (DECISIONS 6, ARCHITECTURE.md §7.1 / §9): decision buttons ignore activation for
 * 700 ms after they appear, after the window gets focus and after a deep link.
 */

const now = signal(Date.parse('2026-10-22T14:30:00+02:00'));
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: () => 'just now',
  absolute: (at) => at ?? '',
  iso: (at) => at ?? '',
  date: () => 'Today',
  clock: () => '14:30',
};

let clock = 0;
beforeEach(() => {
  clock = 1_000;
  vi.spyOn(performance, 'now').mockImplementation(() => clock);
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons(), { provide: TimeService, useValue: fakeTime }] });
  TestBed.inject(AriaDisabledGuard).install();
});
afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const button = (root: ParentNode, name: string) =>
  Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((b) => text(b) === name || text(b).startsWith(name + ' '))!;

describe('ActivationGuard', () => {
  it('an arm is ready 700 ms after it was reset, and again after the window got focus', () => {
    const guard = TestBed.inject(ActivationGuard);
    const arm = guard.arm();
    expect(arm.ready()).toBe(false);
    clock += ACTIVATION_DELAY_MS - 1;
    expect(arm.ready()).toBe(false);
    clock += 1;
    expect(arm.ready()).toBe(true);

    window.dispatchEvent(new Event('focus'));
    expect(arm.ready()).toBe(false);
    clock += ACTIVATION_DELAY_MS;
    expect(arm.ready()).toBe(true);

    // A deep link (App routes it and re-arms), and the window becoming visible again.
    guard.rearm();
    expect(arm.ready()).toBe(false);
    clock += ACTIVATION_DELAY_MS;
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(arm.ready()).toBe(false);
  });
});

describe('approval-request decisions', () => {
  @Component({
    imports: [GwApprovalRequest],
    template: `<article gwApprovalRequest [request]="request()" (decide)="decisions.push($event)"></article>`,
  })
  class Host {
    request = signal<ApprovalDetail>(DEMO_APPROVALS.sql);
    decisions: ApprovalDecision[] = [];
  }

  async function render() {
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
  }

  it('ignores Approve and Deny for 700 ms after the request appears', async () => {
    const { el, host } = await render();
    button(el, 'Approve and run').click();
    button(el, 'Deny').click();
    expect(host.decisions).toEqual([]);

    clock += ACTIVATION_DELAY_MS;
    button(el, 'Approve and run').click();
    expect(host.decisions.map((d) => d.decision)).toEqual(['approve']);
  });

  it('waits again when the window gets focus and when another request takes its place', async () => {
    const { el, fixture, host } = await render();
    clock += ACTIVATION_DELAY_MS;
    window.dispatchEvent(new Event('focus'));
    button(el, 'Approve and run').click();
    expect(host.decisions).toEqual([]);

    clock += ACTIVATION_DELAY_MS;
    host.request.set(DEMO_APPROVALS.k8s);
    fixture.detectChanges();
    await fixture.whenStable();
    button(el, 'Approve and run').click();
    expect(host.decisions).toEqual([]);

    clock += ACTIVATION_DELAY_MS;
    button(el, 'Approve and run').click();
    expect(host.decisions).toEqual([{ decision: 'approve', comment: null, digest: DEMO_APPROVALS.k8s.digest }]);
  });

  it('another request in its place starts empty and pending, with its own countdown', async () => {
    const start = now();
    const { el, fixture, host } = await render();
    clock += ACTIVATION_DELAY_MS;
    // A comment on request A…
    const area = el.querySelector<HTMLTextAreaElement>('textarea')!;
    area.value = 'Needed for the release';
    area.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // …is not B's: the prod request needs its own comment before Approve is available.
    host.request.set(DEMO_APPROVALS.prod);
    fixture.detectChanges();
    await fixture.whenStable();
    clock += ACTIVATION_DELAY_MS;
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('');
    expect(button(el, 'Approve and run').getAttribute('aria-disabled')).toBe('true');
    button(el, 'Approve and run').click();
    button(el, 'Deny').click();
    expect(host.decisions).toEqual([{ decision: 'deny', comment: null }]);

    // A request that expired on screen does not make the next one expired.
    host.request.set(DEMO_APPROVALS.k8s);
    fixture.detectChanges();
    await fixture.whenStable();
    now.set(Date.parse(DEMO_APPROVALS.k8s.expiresAt) + 1_000);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.querySelector('article')!.getAttribute('data-state')).toBe('expired');
    host.request.set({ ...DEMO_APPROVALS.sql, expiresAt: new Date(now() + 120_000).toISOString() });
    fixture.detectChanges();
    await fixture.whenStable();
    const card = el.querySelector('article')!;
    expect(card.getAttribute('data-state')).toBe('pending');
    expect(card.querySelector('[role="timer"]')?.textContent).toContain('2:00');
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.readOnly).toBe(false);
    clock += ACTIVATION_DELAY_MS;
    expect(button(el, 'Approve and run').getAttribute('aria-disabled')).not.toBe('true');

    // …and its own expiry still ends it.
    now.set(now() + 121_000);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(card.getAttribute('data-state')).toBe('expired');
    now.set(start);
  });
});

describe('sign-in consent', () => {
  @Component({
    imports: [GwOAuthConsent],
    template: `<gw-oauth-consent [consent]="consent()" (decide)="decisions.push($event.decision)" />`,
  })
  class Host {
    consent = signal<OAuthConsent | null>(null);
    decisions: string[] = [];
  }

  it('"Allow access" does nothing for 700 ms; Deny is never delayed', async () => {
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const host = fixture.componentInstance;
    host.consent.set(DEMO_CONSENT);
    fixture.detectChanges();
    await fixture.whenStable();
    await Promise.resolve();
    const dialog = (fixture.nativeElement as HTMLElement).querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);

    button(dialog, 'Allow access').click();
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(host.decisions).toEqual([]);

    clock += ACTIVATION_DELAY_MS;
    window.dispatchEvent(new Event('focus'));
    button(dialog, 'Allow access').click();
    expect(host.decisions).toEqual([]);

    button(dialog, 'Deny').click();
    expect(host.decisions).toEqual(['deny']);
  });
});
