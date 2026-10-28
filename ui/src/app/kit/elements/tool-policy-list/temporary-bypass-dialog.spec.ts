import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ApiError } from '../../../core/api/api-error';
import { EndpointDetail, EndpointsApi, ToolGroup, ToolRow } from '../../../core/api/endpoints.api';
import { Env } from '../../../core/api/types';
import { TimeService } from '../../../core/time/time.service';
import { ActivationGuard } from '../../activation-guard';
import { provideKitIcons } from '../../icons';
import { GwTemporaryBypassDialog } from './temporary-bypass-dialog';

const NOW = Date.parse('2026-10-28T12:00:00Z');
const UNTIL = '2026-10-28T12:15:00Z';
const target = { slug: 'dev', toolKey: 'ssh_exec', connection: 'dev-vm' };

function tool(extra: Partial<ToolRow> = {}): ToolRow {
  return {
    toolKey: 'ssh_exec', name: 'ssh_exec', annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true },
    declaredBy: null, mode: 'APPROVAL', storedMode: 'APPROVAL', readOnlyAllowed: false, reason: null,
    readOnlyConfirmed: false, definitionHash: null, overrides: null, version: 4,
    bypassUntil: null, temporaryBypassAllowed: true, temporaryBypassReason: null, ...extra,
  };
}

describe('temporary bypass review', () => {
  async function render(options: { env?: Env; endpointEnv?: Env; readOnly?: boolean; row?: Partial<ToolRow> } = {}) {
    const now = signal(NOW);
    const arm = { reset: vi.fn(), ready: vi.fn(() => true) };
    let row = tool(options.row);
    const groups = (): ToolGroup[] => [{ group: 'SSH', connection: 'dev-vm', env: options.env ?? 'dev', tools: [row] }];
    const enable = vi.fn(async () => {
      row = { ...row, mode: 'BYPASS', bypassUntil: UNTIL, version: 5 };
      return groups();
    });
    const revoke = vi.fn(async () => {
      row = { ...row, mode: 'APPROVAL', bypassUntil: null, version: 6 };
      return groups();
    });
    const api = {
      get: vi.fn(async () => ({ endpoint: { env: options.endpointEnv ?? 'dev', access: options.readOnly ? 'read-only' : 'standard' } }) as EndpointDetail),
      tools: vi.fn(async () => groups()), temporaryBypass: enable, revokeTemporaryBypass: revoke,
    };
    TestBed.configureTestingModule({ providers: [provideKitIcons(), { provide: EndpointsApi, useValue: api },
      { provide: ActivationGuard, useValue: { arm: () => arm } },
      { provide: TimeService, useValue: { now, absolute: (value: string) => value } }] });
    const fixture = TestBed.createComponent(GwTemporaryBypassDialog);
    const changed: ToolGroup[][] = [];
    fixture.componentInstance.changed.subscribe((groups) => changed.push(groups));
    fixture.detectChanges();
    fixture.componentInstance.open(target);
    fixture.detectChanges();
    await Promise.resolve();
    await Promise.resolve();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const button = (text: string) => [...root.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.trim() === text)!;
    const click = async (text: string) => {
      button(text).click();
      await Promise.resolve();
      await Promise.resolve();
      await fixture.whenStable();
    };
    return { fixture, root, enable, revoke, api, now, arm, changed, button, click };
  }

  it('loads the exact scope, defaults to 15 minutes, and offers all six requested durations', async () => {
    const { root, api, enable, click, changed } = await render();
    expect(api.tools).toHaveBeenCalledWith('dev');
    expect(root.textContent).toContain('all AI apps using this endpoint');
    expect(root.textContent).toContain('Existing pending requests still need their own approval or denial.');
    const select = root.querySelector('select')!;
    expect([...select.options].map((option) => option.value)).toEqual(['15', '30', '60', '180', '720', '1440']);
    expect(select.value).toBe('15');
    expect(document.activeElement?.textContent).toBe('Cancel');
    await click('Enable bypass');
    expect(enable).toHaveBeenCalledWith('dev', { toolKey: 'ssh_exec', connection: 'dev-vm', durationMinutes: 15, version: 4 });
    expect(root.textContent).toContain(`Bypass until ${UNTIL}`);
    expect(changed).toHaveLength(1);
  });

  it('renews for the chosen duration and revokes with the new row version', async () => {
    const { root, enable, revoke, click } = await render({ row: { mode: 'BYPASS', bypassUntil: UNTIL } });
    const select = root.querySelector('select')!;
    select.value = '1440';
    select.dispatchEvent(new Event('change'));
    await click('Renew bypass');
    expect(enable).toHaveBeenCalledWith('dev', expect.objectContaining({ durationMinutes: 1440, version: 4 }));
    await click('End bypass now');
    expect(revoke).toHaveBeenCalledWith('dev', { toolKey: 'ssh_exec', connection: 'dev-vm', version: 5 });
    expect(root.textContent).toContain('Temporary bypass ended.');
    expect(root.textContent).not.toContain('Bypass until');
  });

  it.each([{ env: 'prod' as const }, { endpointEnv: 'prod' as const }, { readOnly: true },
    { row: { temporaryBypassAllowed: false, temporaryBypassReason: 'This tool is off.' } }])('keeps denied scope unavailable: %j', async (options) => {
    const { root, enable } = await render(options);
    expect(root.querySelector('select')).toBeNull();
    expect(root.textContent).not.toContain('Enable bypass');
    expect(enable).not.toHaveBeenCalled();
  });

  it('shows server failures and requires fresh review after a version conflict', async () => {
    const { root, enable, click, api } = await render();
    enable.mockRejectedValueOnce(new ApiError('conflict', 409, { detail: 'This tool changed. Review it again.' }));
    await click('Enable bypass');
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('This tool changed. Review it again.');
    expect(root.textContent).not.toContain('Enable bypass');
    await click('Reload tool');
    expect(api.tools).toHaveBeenCalledTimes(2);
    expect(root.textContent).toContain('Enable bypass');
  });

  it('marks expiry on the server clock without keeping an early-revoke action', async () => {
    const { fixture, root, now } = await render({ row: { mode: 'BYPASS', bypassUntil: UNTIL } });
    now.set(Date.parse(UNTIL));
    await fixture.whenStable();
    expect(root.textContent).toContain('Temporary bypass has expired. Approval applies again.');
    expect(root.textContent).not.toContain('End bypass now');
    expect(root.textContent).not.toContain('Renew bypass');
  });

  it('ignores activation until the protected review has been visible long enough', async () => {
    const { arm, enable, click } = await render();
    expect(arm.reset).toHaveBeenCalled();
    arm.ready.mockReturnValue(false);
    await click('Enable bypass');
    expect(enable).not.toHaveBeenCalled();
    arm.ready.mockReturnValue(true);
    await click('Enable bypass');
    expect(enable).toHaveBeenCalledOnce();
  });
});
