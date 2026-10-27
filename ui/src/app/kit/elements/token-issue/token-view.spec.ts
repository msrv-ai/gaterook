import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { IssuedToken, TokenRow } from '../../../core/api/endpoints.api';
import { TimeService } from '../../../core/time/time.service';
import { provideKitIcons } from '../../icons';
import { AriaDisabledGuard } from '../../aria-disabled';
import { GwTokenIssueDialog, GwTokens, tokenView } from './token-issue';

/**
 * `GET /api/endpoints/dev/tokens` as the GW_DEMO core answers it (named tokens from the M1.7 seed, captured on
 * 2026-10-25 at 10:58:44Z) plus the two grants the K1 seed adds: Claude Desktop's bridge and Codex's helper.
 */
const REAL_ROWS: TokenRow[] = [
  {
    id: 'tok_fc9hdyktf6jb', kind: 'named', name: 'old-claude', prefix: 'gwt_1UeZ', client: null,
    createdAt: '2026-10-25T10:58:44.061Z', expiresAt: '2026-11-01T10:58:44.061Z', lastUsedAt: null,
    revokedAt: '2026-10-25T10:58:44.075Z', state: 'revoked',
  },
  {
    id: 'tok_vu04frbwgk2s', kind: 'named', name: 'inspector-debug', prefix: 'gwt_DC6w', client: null,
    createdAt: '2026-10-25T10:58:44.048Z', expiresAt: '2026-10-28T10:58:44.048Z', lastUsedAt: null,
    revokedAt: null, state: 'active',
  },
  {
    id: 'tok_uve0jbvta3xu', kind: 'named', name: 'ci-smoke', prefix: 'gwt_Mu_o', client: null,
    createdAt: '2026-09-21T10:58:44.055Z', expiresAt: '2026-10-21T10:58:44.055Z', lastUsedAt: null,
    revokedAt: null, state: 'expired',
  },
  {
    id: 'gr_demoClaudeDk', kind: 'grant', name: 'claude-desktop', prefix: null, client: 'claude-desktop',
    createdAt: '2026-10-25T10:58:44.100Z', expiresAt: null, lastUsedAt: '2026-10-25T10:58:44.126Z',
    revokedAt: null, state: 'active',
  },
  {
    id: 'gr_demoCodexLp', kind: 'grant', name: 'codex-laptop', prefix: null, client: 'codex',
    createdAt: '2026-10-25T10:58:44.110Z', expiresAt: null, lastUsedAt: '2026-10-25T10:58:44.307Z',
    revokedAt: null, state: 'active',
  },
];
const NOW = Date.parse('2026-10-25T11:00:00Z');
const row = (name: string) => REAL_ROWS.find((r) => r.name === name)!;

describe('token-issue view on the core\'s rows', () => {
  it('reads grants as issued on the fly, whatever the case of their kind', () => {
    const bridge = tokenView(row('claude-desktop'), 'dev', NOW);
    expect(bridge).toMatchObject({ onTheFly: true, bridge: true, title: 'Bridge (.mcpb)', revocable: false });
    expect(bridge.note).toBe('Claude Desktop · issued on the fly');

    const codex = tokenView(row('codex-laptop'), 'dev', NOW);
    expect(codex).toMatchObject({ onTheFly: true, bridge: false, revocable: true, client: 'Codex' });
    expect(codex.note).toBe('Codex · gw-bridge headers helper, issued on the fly');

    // An upper-case kind is still a grant: a grant can never pass for a named token (DELETE /api/tokens/{grantId}).
    const shouting = tokenView({ ...row('codex-laptop'), kind: 'GRANT' as never }, 'dev', NOW);
    expect(shouting.onTheFly).toBe(true);

    const named = tokenView(row('ci-smoke'), 'dev', NOW);
    expect(named).toMatchObject({ onTheFly: false, note: 'gwt_Mu_o…', query: 'token:ci-smoke' });
  });

  it('derives Expiring from expiresAt: active and within 7 days', () => {
    const soon = tokenView(row('inspector-debug'), 'dev', NOW);
    expect(soon.expiring).toBe(true);
    expect(soon.look).toEqual({ tone: 'warning', icon: 'hourglass', text: 'Expiring' });
    expect(soon.revocable).toBe(true);

    // The same token a week earlier is just active.
    const early = tokenView(row('inspector-debug'), 'dev', NOW - 8 * 86_400_000);
    expect(early.expiring).toBe(false);
    expect(early.look.text).toBe('Active');

    // Revoked and expired tokens never expire again, and only active ones can be revoked.
    const revoked = tokenView(row('old-claude'), 'dev', NOW);
    expect(revoked).toMatchObject({ expiring: false, revocable: false });
    expect(revoked.look.text).toBe('Revoked');
    expect(tokenView(row('ci-smoke'), 'dev', NOW)).toMatchObject({ expiring: false, revocable: false });
    expect(tokenView(row('codex-laptop'), 'dev', NOW).expiring).toBe(false);
  });
});

describe('token-issue elements', () => {
  const now = signal(NOW);
  const fakeTime: Partial<TimeService> = {
    now,
    minute: now,
    relative: (at) => (at ? 'in 3 days' : ''),
    absolute: (at) => at ?? '',
    iso: (at) => at ?? '',
    date: (at) => (at ? 'Oct 28' : ''),
    clock: (at) => at ?? '',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons(), { provide: TimeService, useValue: fakeTime }] });
    TestBed.inject(AriaDisabledGuard).install();
  });
  afterEach(() => (document.body.innerHTML = ''));

  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it('lists the real rows: the bridge has no Revoke, the soon-expiring token gets its badge', async () => {
    @Component({ imports: [GwTokens], template: `<div gwTokens slug="dev" [tokens]="rows" (revoke)="revoked.push($event.id)"></div>` })
    class Host {
      rows = REAL_ROWS;
      revoked: string[] = [];
    }
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const rows = Array.from(el.querySelectorAll('tbody tr'));
    const byName = (n: string) => rows.find((r) => text(r.querySelector('th')).startsWith(n))!;

    expect(text(byName('Bridge (.mcpb)').querySelector('td:last-child'))).toBe('');
    expect(text(byName('codex-laptop').querySelector('td:last-child'))).toContain('Revoke');
    expect(text(byName('inspector-debug').querySelector('.gw-badge--warning.gw-badge--sm'))).toBe('In 3 days');
    expect(text(el.querySelector('.gw-card__description'))).toContain('2 active, 1 expired, 1 revoked');

    // Revoking Codex's grant hands the page the grant row: its kind says DELETE /api/grants/{id}.
    (byName('codex-laptop').querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    const confirm = el.querySelector('dialog[open]')!;
    expect(text(confirm.querySelector('.gw-dialog__title'))).toBe('Revoke codex-laptop?');
    (Array.from(confirm.querySelectorAll('button')).find((b) => text(b) === 'Revoke codex-laptop') as HTMLButtonElement).click();
    expect(fixture.componentInstance.revoked).toEqual(['gr_demoCodexLp']);
  });

  it('shows the issued token once: closing drops it, reopening starts from the form', async () => {
    @Component({ imports: [GwTokenIssueDialog], template: `<gw-token-issue-dialog slug="dev" [issued]="issued()" (done)="done = done + 1" />` })
    class Host {
      issued = signal<IssuedToken | null>(null);
      done = 0;
      dialog = viewChild.required(GwTokenIssueDialog);
    }
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const host = fixture.componentInstance;
    const el = fixture.nativeElement as HTMLElement;
    const dialog = () => el.querySelector('dialog')!;

    host.dialog().open();
    fixture.detectChanges();
    expect(dialog().querySelector('[data-step="form"]')).not.toBeNull();

    const token = 'gwt_TESTONLYxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx';
    host.issued.set({ id: 'tok_new', name: 'cursor-laptop', token, prefix: 'gwt_TEST', expiresAt: '2026-11-01T11:00:00Z' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(dialog().querySelector('[data-step="result"]')).not.toBeNull();
    expect(dialog().textContent).toContain(token);
    // The result step's initial focus is Copy, not a field of the form that is gone.
    expect(document.activeElement?.textContent).toContain('Copy');

    // Done needs the box; then the dialog closes and the token leaves the DOM, though the page kept its input.
    const box = dialog().querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    box.click();
    fixture.detectChanges();
    (dialog().querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(dialog().hasAttribute('open')).toBe(false);
    expect(host.done).toBe(1);
    expect(el.textContent).not.toContain(token);

    host.dialog().open();
    fixture.detectChanges();
    expect(dialog().querySelector('[data-step="form"]')).not.toBeNull();
    expect(el.textContent).not.toContain(token);
  });
});
