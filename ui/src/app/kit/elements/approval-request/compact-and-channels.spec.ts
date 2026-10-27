import { Component, input, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActionSegment, ApprovalItem } from '../../../core/api/approvals.api';
import { TimeService } from '../../../core/time/time.service';
import { DEMO_REQUESTS } from '../../../dev/fixtures';
import { provideKitIcons } from '../../icons';
import { GwNotificationPreview, NotificationRequest } from '../approval-notification/approval-notification';
import { GwApprovalRequestCompact } from './approval-request-compact';
import { previewParts } from './approval-view';

const now = signal(Date.parse('2026-10-22T14:30:00+02:00'));
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: () => 'just now',
  absolute: (at) => at ?? '',
  iso: (at) => at ?? '',
  date: () => 'Today',
  clock: () => '14:29',
};

beforeEach(() => {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons(), { provide: TimeService, useValue: fakeTime }] });
});
afterEach(() => (document.body.innerHTML = ''));

const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

describe('compact row preview (DESIGN §8 "Видно — значит видно")', () => {
  it('takes the first non-blank line of the server segments, keeping escapes and look-alikes', () => {
    const segments: ActionSegment[] = [
      { t: 'text', v: '\nDELETE FROM ' },
      { t: 'confusable', v: 'о' },
      { t: 'text', v: 'rders WHERE id = 1' },
      { t: 'invisible', v: '‹U+202E›' },
      { t: 'text', v: ' OR 1=1\nSELECT 2' },
    ];
    const parts = previewParts(segments, null);
    expect(parts.map((p) => p.text).join('')).toBe('DELETE FROM оrders WHERE id = 1‹U+202E› OR 1=1');
    expect(parts.filter((p) => p.cls).map((p) => [p.cls, p.title])).toEqual([
      ['gw-tok-confusable', 'U+043E Cyrillic small letter o'],
      ['gw-tok-invisible', 'U+202E right-to-left override'],
    ]);
  });

  it('escapes invisible and bidi characters of plain text (a summary, or text without segments)', () => {
    const parts = previewParts(null, 'UPDATE t SET a = 1‮ -- x​');
    expect(parts.map((p) => p.text).join('')).toBe('UPDATE t SET a = 1‹U+202E› -- x‹U+200B›');
    expect(parts.every((p) => !/[‮​]/.test(p.text))).toBe(true);
  });

  it('marks plain text with the server rules: tag characters, non-ASCII spaces, look-alike letters', () => {
    const parts = previewParts(null, 'DELETE FROM \u043Erders\u00A0WHERE id = 1\u{E0041}');
    expect(parts.filter((p) => p.cls).map((p) => [p.cls, p.text])).toEqual([
      ['gw-tok-confusable', 'о'],
      ['gw-tok-invisible', '‹U+00A0›'],
      ['gw-tok-invisible', '‹U+E0041›'],
    ]);
  });

  it('renders the escapes boxed and named; neither the line nor its title holds the raw character', async () => {
    @Component({
      imports: [GwApprovalRequestCompact],
      template: `<article gwApprovalRequestCompact [item]="item" [review]="['/approvals', item.id]"></article>`,
    })
    class Host {
      item: ApprovalItem = {
        id: 'ap_abcdefghijkl', tool: 'sql_execute', connection: 'dev-db', env: 'dev', endpoint: 'dev', client: 'codex',
        tokenLabel: 'codex-laptop', requestedAt: '2026-10-22T14:29:12+02:00', expiresAt: '2026-10-22T14:34:12+02:00',
        status: 'pending', summary: 'UPDATE orders SET note = ‮1=1 WHERE id = 7', decidedVia: null, decidedAt: null,
        commentPolicy: 'optional',
      };
    }
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const p = (fixture.nativeElement as HTMLElement).querySelector('.gw-approval-request__preview')!;
    const box = p.querySelector('.gw-tok-invisible')!;
    expect(box.textContent).toBe('‹U+202E›');
    expect(box.getAttribute('title')).toBe('U+202E right-to-left override');
    expect(p.textContent).not.toMatch(/‮/);
    expect(p.getAttribute('title')).toBe('UPDATE orders SET note = ‹U+202E›1=1 WHERE id = 7');
  });
});

describe('channel previews offer Approve only where the core would (§7.4, §8.6.7)', () => {
  @Component({
    imports: [GwNotificationPreview],
    template: `<figure gwNotificationPreview="slack" [request]="request()"></figure><figure gwNotificationPreview="telegram" [request]="request()"></figure>`,
  })
  class Host {
    readonly request = input.required<NotificationRequest>();
  }

  async function approveButtons(request: NotificationRequest): Promise<{ count: number; captions: string[] }> {
    const fixture = TestBed.createComponent(Host);
    fixture.componentRef.setInput('request', request);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const count = Array.from(el.querySelectorAll('button')).filter((b) => text(b) === 'Approve').length;
    const captions = Array.from(el.querySelectorAll('.gw-text-caption')).map(text);
    fixture.destroy();
    return { count, captions };
  }

  it('dev with an optional comment: Approve in both chats', async () => {
    expect((await approveButtons(DEMO_REQUESTS.sql)).count).toBe(2);
  });

  it('never on prod, even when the comment is optional', async () => {
    const { count, captions } = await approveButtons({ ...DEMO_REQUESTS.prod, commentPolicy: 'optional' });
    expect(count).toBe(0);
    expect(captions.some((c) => c.includes('production: approve in the app'))).toBe(true);
  });

  it('not with a required comment, nor with hidden or look-alike characters', async () => {
    expect((await approveButtons({ ...DEMO_REQUESTS.sql, commentPolicy: 'required' })).count).toBe(0);
    const hidden = { ...DEMO_REQUESTS.sql, action: { ...DEMO_REQUESTS.sql.action!, hiddenChars: 1 } };
    const r = await approveButtons(hidden);
    expect(r.count).toBe(0);
    expect(r.captions.some((c) => c.includes('hidden characters: approve in the app'))).toBe(true);
    expect((await approveButtons({ ...DEMO_REQUESTS.sql, action: { ...DEMO_REQUESTS.sql.action!, confusables: 2 } })).count).toBe(0);
  });

  it('not when the chat can\'t show the whole action (Slack 3,000, Telegram 4,096)', async () => {
    const long = (n: number) => ({ ...DEMO_REQUESTS.sql, action: { ...DEMO_REQUESTS.sql.action!, text: 'x'.repeat(n), segments: [{ t: 'text' as const, v: 'x'.repeat(n) }] } });
    expect((await approveButtons(long(3001))).count).toBe(1); // Telegram still fits
    expect((await approveButtons(long(4097))).count).toBe(0);
  });
});
