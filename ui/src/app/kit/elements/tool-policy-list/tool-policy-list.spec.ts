import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { DEMO_TOOL_GROUPS } from '../../../dev/fixtures-tools';
import { provideKitIcons } from '../../icons';
import { GwToolPolicyList, TemporaryBypassOpen, ToolModeChange } from './tool-policy-list';

describe('tool-policy-list bypass mode', () => {
  async function render(readOnly = false) {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
    const fixture = TestBed.createComponent(GwToolPolicyList);
    fixture.componentRef.setInput('groups', [DEMO_TOOL_GROUPS[0]]);
    fixture.componentRef.setInput('slug', 'dev');
    fixture.componentRef.setInput('readOnly', readOnly);
    const changes: ToolModeChange[] = [];
    fixture.componentInstance.modeChange.subscribe((change) => changes.push(change));
    fixture.detectChanges();
    await fixture.whenStable();
    const rows = [...fixture.nativeElement.querySelectorAll('tr.gw-tool-policy-list__row')] as HTMLElement[];
    const writer = rows.find((row) => row.textContent?.includes('sql_execute'))!;
    return { fixture, changes, writer, rows };
  }

  it('lets a mutating tool explicitly bypass approval and explains the resulting behavior', async () => {
    const { fixture, changes, writer } = await render();
    const bypass = writer.querySelector<HTMLInputElement>('input[value="bypass"]')!;
    expect(writer.querySelector<HTMLInputElement>('input[value="read"]')!.disabled).toBe(true);
    expect(bypass.disabled).toBe(false);
    bypass.click();
    await fixture.whenStable();
    expect(changes).toEqual([expect.objectContaining({ toolKey: 'sql_execute', connection: 'dev-db', mode: 'BYPASS' })]);
    expect(writer.textContent).toContain('Bypass runs without approval, including changes to data. Policy limits still apply.');
    expect(bypass.checked).toBe(true);
  });

  it('disables bypass for every tool on a read-only endpoint', async () => {
    const { changes, rows } = await render(true);
    for (const row of rows) {
      const bypass = row.querySelector<HTMLInputElement>('input[value="bypass"]')!;
      expect(bypass.disabled).toBe(true);
      bypass.click();
    }
    expect(changes).toEqual([]);
  });

  it('offers temporary bypass for an eligible DEV pair and passes its exact scope', async () => {
    const { fixture, writer } = await render();
    const group = DEMO_TOOL_GROUPS[0];
    fixture.componentRef.setInput('groups', [{ ...group, group: { ...group.group, tools: group.group.tools.map((tool) => ({ ...tool, temporaryBypassAllowed: tool.name === 'sql_execute' })) } }]);
    const changes: TemporaryBypassOpen[] = [];
    fixture.componentInstance.temporaryBypass.subscribe((change) => changes.push(change));
    await fixture.whenStable();
    const button = [...writer.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.includes('Temporary bypass'))!;
    button.click();
    expect(changes).toEqual([{ toolKey: 'sql_execute', connection: 'dev-db', toolName: 'sql_execute', opener: button }]);
    fixture.componentRef.setInput('endpointEnv', 'prod');
    await fixture.whenStable();
    expect(writer.textContent).not.toContain('Temporary bypass…');
  });
});
