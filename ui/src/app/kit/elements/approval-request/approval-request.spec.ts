import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActionSegment, ApprovalDetail, ApprovalStatus } from '../../../core/api/approvals.api';
import { TimeService } from '../../../core/time/time.service';
import { DEMO_APPROVALS } from '../../../dev/fixtures-approvals';
import { ActivationGuard } from '../../activation-guard';
import { provideKitIcons } from '../../icons';
import { GwApprovalRequest } from './approval-request';

const now = signal(Date.parse('2026-10-22T14:30:00+02:00'));
const fakeTime: Partial<TimeService> = {
  now,
  minute: now,
  secondsUntil: (at: string) => (Date.parse(at) - now()) / 1000,
  relative: () => 'just now',
  absolute: (at) => at ?? '',
  iso: (at) => at ?? '',
  clock: () => '14:29',
};

const original = '{"update":"orders","updates":[{"q":{"id":9007199254740993},"u":{"$set":{"state":"queued"}}}]}';
const pretty = `{
  "update": "orders",
  "updates": [
    {
      "q": {
        "id": 9007199254740993
      },
      "u": {
        "$set": {
          "state": "queued"
        }
      }
    }
  ]
}`;

function request(status: ApprovalStatus = 'pending', segments: ActionSegment[] = [{ t: 'text', v: original }]): ApprovalDetail {
  return {
    ...DEMO_APPROVALS.sql,
    tool: 'mongo_update',
    toolKey: 'mongo_update',
    connection: 'dev-mongo',
    target: 'dev-mongo',
    status,
    fields: [],
    impact: [],
    rule: null,
    agentNote: null,
    action: {
      kind: 'json', language: 'json', text: original, segments,
      hiddenChars: segments.filter((s) => s.t === 'invisible').length,
      confusables: 0, warning: null, diff: null,
    },
  };
}

beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      { provide: TimeService, useValue: fakeTime },
      { provide: ActivationGuard, useValue: { arm: () => ({ reset: () => undefined, ready: () => true }) } },
    ],
  });
});
afterEach(() => (document.body.innerHTML = ''));

async function render(detail: ApprovalDetail): Promise<ComponentFixture<GwApprovalRequest>> {
  const fixture = TestBed.createComponent(GwApprovalRequest);
  fixture.componentRef.setInput('request', detail);
  document.body.appendChild(fixture.nativeElement);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function code(fixture: ComponentFixture<GwApprovalRequest>): HTMLElement {
  return fixture.nativeElement.querySelector('gw-code-block pre code');
}

function chooseOriginal(fixture: ComponentFixture<GwApprovalRequest>): void {
  const radio = fixture.nativeElement.querySelector('gw-code-block input[value="original"]') as HTMLInputElement;
  expect(radio).not.toBeNull();
  radio.checked = true;
  radio.dispatchEvent(new Event('change', { bubbles: true }));
  fixture.detectChanges();
}

describe('approval JSON request views', () => {
  it.each(['pending', 'completed'] as const)('makes a %s MongoDB action readable and restores its original text', async (status) => {
    const detail = request(status);
    const before = JSON.stringify(detail);
    const fixture = await render(detail);

    expect(code(fixture).textContent).toBe(pretty);
    chooseOriginal(fixture);
    expect(code(fixture).textContent).toBe(original);
    expect(JSON.stringify(detail)).toBe(before);
  });

  it('preserves server safety annotations and their warning in both views', async () => {
    const segments: ActionSegment[] = [
      { t: 'text', v: '{"note":"before' },
      { t: 'invisible', v: '‹U+202E›' },
      { t: 'text', v: 'after","count":1}' },
    ];
    const detail = request('pending', segments);
    const fixture = await render({ ...detail, action: { ...detail.action!, text: '{"note":"before\u202eafter","count":1}' } });
    const assertSafety = () => {
      const marker = code(fixture).querySelector('.gw-tok-invisible');
      expect(marker?.textContent).toBe('‹U+202E›');
      expect(marker?.getAttribute('title')).toBe('U+202E right-to-left override');
      expect(fixture.nativeElement.textContent).toContain('Contains 1 hidden character');
      expect(code(fixture).textContent).not.toContain('\u202e');
    };

    expect(code(fixture).textContent).toBe('{\n  "note": "before‹U+202E›after",\n  "count": 1\n}');
    assertSafety();
    chooseOriginal(fixture);
    expect(code(fixture).textContent).toBe(segments.map((s) => s.v).join(''));
    assertSafety();
  });

  it('keeps approval bound to the original digest after switching views', async () => {
    const detail = request();
    const before = JSON.stringify(detail);
    const fixture = await render(detail);
    const decision = vi.fn();
    fixture.componentInstance.decide.subscribe(decision);
    chooseOriginal(fixture);

    (fixture.nativeElement.querySelector('.gw-btn--approve') as HTMLButtonElement).click();

    expect(decision).toHaveBeenCalledExactlyOnceWith({ decision: 'approve', comment: null, digest: detail.digest });
    expect(JSON.stringify(detail)).toBe(before);
  });
});
