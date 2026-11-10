import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { ActionSegment, ApprovalDetail, ApprovalStatus } from '../../../core/api/approvals.api';
import { EndpointDetail, EndpointsApi, ToolGroup, ToolRow } from '../../../core/api/endpoints.api';
import { TimeService } from '../../../core/time/time.service';
import { DEMO_APPROVALS } from '../../../dev/fixtures-approvals';
import { ActivationGuard } from '../../activation-guard';
import { provideKitIcons } from '../../icons';
import { GwTemporaryBypassDialog } from '../tool-policy-list/temporary-bypass-dialog';
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

describe('what the review shows with the action', () => {
  it('boxes what the server marked in a parameter value, a warning and the note, and warns about it', async () => {
    const detail: ApprovalDetail = {
      ...request(),
      target: 'dev-mongo · app‮',
      targetSegments: [{ t: 'text', v: 'dev-mongo · app' }, { t: 'invisible', v: '‹U+202E›' }],
      fields: [{
        label: 'Parameters',
        text: ":status = 'аctive' (text)",
        labelSegments: null,
        segments: [{ t: 'text', v: ":status = '" }, { t: 'confusable', v: 'а' }, { t: 'text', v: "ctive' (text)" }],
      }],
      impact: [{ label: 'Documents affected', text: 'at most one', labelSegments: null, segments: null }],
      warnings: ['Check the​status'],
      warningSegments: [[{ t: 'text', v: 'Check the' }, { t: 'invisible', v: '‹U+200B›' }, { t: 'text', v: 'status' }]],
      agentNote: 'Cancels the order⁦',
      agentNoteSegments: [{ t: 'text', v: 'Cancels the order' }, { t: 'invisible', v: '‹U+2066›' }],
      detailsHiddenChars: 3,
      detailsConfusables: 1,
    };
    const fixture = await render(detail);
    const host = fixture.nativeElement as HTMLElement;
    const warning = host.querySelector(`[id$="-details-warning"]`);

    expect(warning?.querySelector('.gw-alert__title')?.textContent).toBe('The details contain 3 hidden characters and 1 look-alike letter');
    expect(warning?.textContent).toContain('Found in the target, Parameters, the warning and the agent\'s note');
    const impact = host.querySelector('.gw-approval-request__facts dl') as HTMLElement;
    const letter = impact.querySelector('.gw-tok-confusable');
    expect(letter?.textContent).toBe('а');
    expect(letter?.getAttribute('title')).toBe('U+0430 Cyrillic small letter a');
    expect(impact.textContent).toContain(":status = 'аctive' (text)");
    const alerts = [...host.querySelectorAll('.gw-alert__body')].map((e) => e as HTMLElement);
    const warned = alerts.find((e) => e.textContent?.startsWith('Check the'));
    expect(warned?.querySelector('.gw-tok-invisible')?.textContent).toBe('‹U+200B›');
    expect(warned?.textContent).not.toContain('​');
    const note = host.querySelector('.gw-approval-request__note') as HTMLElement;
    expect(note.querySelector('.gw-tok-invisible')?.getAttribute('title')).toBe('U+2066 left-to-right isolate');
    expect(note.textContent).not.toContain('⁦');
    expect(host.querySelector('.gw-code-block__filename')?.textContent).toBe('mongo_update · dev-mongo · app‹U+202E›');
    expect(host.textContent).not.toContain('‮');
    expect(host.querySelector('.gw-btn--approve')?.textContent).toContain('with hidden characters');
  });

  it('shows ordinary details as they are, without a warning', async () => {
    const fixture = await render({
      ...request(),
      fields: [{ label: 'Parameters', text: ":title = 'Встреча с клиентом' (text)", labelSegments: null, segments: null }],
      warnings: ['Changes up to 1 row'],
      warningSegments: [null],
    });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[id$="-details-warning"]')).toBeNull();
    expect(host.querySelector('.gw-approval-request__facts .gw-tok-confusable')).toBeNull();
    expect(host.querySelector('.gw-approval-request__facts dl')?.textContent).toContain("Встреча с клиентом");
    expect(host.querySelector('.gw-btn--approve')?.textContent).not.toContain('hidden characters');
  });
});

describe('temporary bypass from approval review', () => {
  it('opens the shared review and enables 30 minutes for the exact pair without approving the request', async () => {
    const detail = { ...request(), env: 'dev' as const };
    const until = new Date(now() + 30 * 60_000).toISOString();
    const row: ToolRow = {
      toolKey: detail.toolKey, name: detail.tool, mode: 'APPROVAL', storedMode: 'APPROVAL',
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
      declaredBy: null, readOnlyAllowed: false, reason: null, readOnlyConfirmed: false, definitionHash: null,
      overrides: null, version: 7, bypassUntil: null, temporaryBypassAllowed: true, temporaryBypassReason: null,
    };
    const groups: ToolGroup[] = [{ group: 'MongoDB', connection: detail.connection, env: 'dev', tools: [row] }];
    const temporaryBypass = vi.fn(async () => [{ ...groups[0], tools: [{ ...row, mode: 'BYPASS' as const, bypassUntil: until, version: 8 }] }]);
    TestBed.configureTestingModule({ providers: [{ provide: EndpointsApi, useValue: {
      get: vi.fn(async () => ({ endpoint: { env: 'dev', access: 'standard' } }) as EndpointDetail),
      tools: vi.fn(async () => groups), temporaryBypass,
    } }] });
    const fixture = await render(detail);
    const decision = vi.fn();
    fixture.componentInstance.decide.subscribe(decision);
    const settle = async () => {
      for (let i = 0; i < 4; i++) {
        await Promise.resolve();
        await fixture.whenStable();
      }
    };
    const opener = fixture.nativeElement.querySelector('button[aria-haspopup="dialog"]') as HTMLButtonElement;
    opener.focus();
    opener.click();
    await settle();
    const dialog = fixture.nativeElement.querySelector('dialog[open]') as HTMLDialogElement;
    expect(dialog).not.toBeNull();
    expect(document.activeElement?.textContent).toBe('Cancel');
    const duration = dialog.querySelector('select')!;
    duration.value = '30';
    duration.dispatchEvent(new Event('change'));
    (dialog.querySelector('.gw-btn--primary') as HTMLButtonElement).click();
    await settle();
    expect(temporaryBypass).toHaveBeenCalledExactlyOnceWith(detail.endpoint, {
      toolKey: detail.toolKey, connection: detail.connection, durationMinutes: 30, version: 7,
    });
    expect(fixture.nativeElement.textContent).toContain(`Bypass enabled until ${until}`);
    expect(decision).not.toHaveBeenCalled();
    expect(detail.status).toBe('pending');
    dialog.close();
    expect(document.activeElement).toBe(opener);

    // A delayed update from this dialog must not label a newly selected request on another endpoint.
    fixture.componentRef.setInput('request', { ...detail, id: 'another-request', endpoint: 'stage' });
    await settle();
    const shared = fixture.debugElement.query(By.directive(GwTemporaryBypassDialog)).componentInstance as GwTemporaryBypassDialog;
    shared.changed.emit([{ ...groups[0], tools: [{ ...row, mode: 'BYPASS', bypassUntil: until }] }]);
    await settle();
    expect(fixture.nativeElement.textContent).not.toContain('Bypass enabled until');
  });

  it.each(['dev', 'stage'] as const)('offers temporary bypass on %s without changing the pending decision', async (env) => {
    const fixture = await render({ ...request(), env });
    const decision = vi.fn();
    fixture.componentInstance.decide.subscribe(decision);
    const action = fixture.nativeElement.querySelector('button[aria-haspopup="dialog"]');

    expect(action?.textContent).toContain('Temporary bypass');
    expect(fixture.nativeElement.textContent).toContain('Pending requests still need a separate decision');
    expect(fixture.nativeElement.querySelector('.gw-btn--approve')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('gw-temporary-bypass-dialog')).toBeNull();
    expect(decision).not.toHaveBeenCalled();
  });

  it('also offers bypass from a completed request', async () => {
    const fixture = await render({ ...request('completed'), env: 'dev' });
    expect(fixture.nativeElement.querySelector('button[aria-haspopup="dialog"]')?.textContent).toContain('Temporary bypass');
    expect(fixture.nativeElement.querySelector('.gw-btn--approve')).toBeNull();
  });

  it('does not offer bypass on production, core tools, or a known production endpoint', async () => {
    const fixture = await render({ ...request(), env: 'prod' });
    const action = () => fixture.nativeElement.querySelector('button[aria-haspopup="dialog"]');
    expect(action()).toBeNull();

    fixture.componentRef.setInput('request', { ...request(), env: 'dev', connection: null });
    fixture.detectChanges();
    expect(action()).toBeNull();

    fixture.componentRef.setInput('request', { ...request(), env: 'dev' });
    fixture.componentRef.setInput('endpointEnv', 'prod');
    fixture.detectChanges();
    expect(action()).toBeNull();
  });

  it('ignores bypass activation while a decision is being sent', async () => {
    const fixture = await render({ ...request(), env: 'dev' });
    fixture.componentRef.setInput('busy', 'approve');
    fixture.detectChanges();
    const action = fixture.nativeElement.querySelector('button[aria-haspopup="dialog"]') as HTMLButtonElement;
    expect(action.getAttribute('aria-disabled')).toBe('true');
    action.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('gw-temporary-bypass-dialog')).toBeNull();
  });

  it('honors the activation delay before opening bypass settings', async () => {
    TestBed.overrideProvider(ActivationGuard, { useValue: { arm: () => ({ reset: () => undefined, ready: () => false }) } });
    const fixture = await render({ ...request(), env: 'dev' });
    (fixture.nativeElement.querySelector('button[aria-haspopup="dialog"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('gw-temporary-bypass-dialog')).toBeNull();
  });
});
