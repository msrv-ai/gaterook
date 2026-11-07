import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ToolRow } from '../../../core/api/endpoints.api';
import { DEMO_TOOL_GROUPS, DEMO_TOOL_GROUPS_EXTERNAL } from '../../../dev/fixtures-tools';
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

describe('tool-policy-list read only of a plugin tool', () => {
  async function render(row: Partial<ToolRow> = {}) {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
    const fixture = TestBed.createComponent(GwToolPolicyList);
    const g = DEMO_TOOL_GROUPS_EXTERNAL[0];
    const tools = g.group.tools.map((t) => (t.name === 'list_issues' ? { ...t, toolKey: 'plugin:issues/list_issues', mode: 'APPROVAL' as const, storedMode: 'APPROVAL' as const, readOnlyAllowed: false, ...row } : t));
    fixture.componentRef.setInput('groups', [{ ...g, type: 'plugin:issues', group: { ...g.group, tools } }]);
    fixture.componentRef.setInput('slug', 'dev');
    const changes: ToolModeChange[] = [];
    const confirmed: unknown[] = [];
    fixture.componentInstance.modeChange.subscribe((change) => changes.push(change));
    fixture.componentInstance.confirmReadOnly.subscribe((c) => confirmed.push(c));
    fixture.detectChanges();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const tr = [...root.querySelectorAll<HTMLElement>('tr.gw-tool-policy-list__row')].find((r) => r.textContent?.includes('list_issues'))!;
    const radio = (value: string) => tr.querySelector<HTMLInputElement>(`input[value="${value}"]`)!;
    const dialog = root.querySelector('dialog')!;
    const button = (text: string) => [...dialog.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim().startsWith(text))!;
    return { fixture, tr, radio, dialog, button, changes, confirmed };
  }

  it('opens the confirmation from Read only and keeps the mode until Allow picks it', async () => {
    const { fixture, tr, radio, dialog, button, changes, confirmed } = await render();
    const read = radio('read');
    expect(read.disabled).toBe(false);
    expect(read.hasAttribute('aria-disabled')).toBe(false);
    expect(tr.textContent).toContain('Read only needs your confirmation for this tool');
    expect(tr.textContent).not.toContain('Confirm read only');

    read.click();
    await fixture.whenStable();
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.textContent).toContain('Let list_issues run without approval?');
    expect(read.checked).toBe(false);
    expect(radio('approval').checked).toBe(true);

    button('Allow read only').click();
    expect(confirmed).toEqual([]);
    dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await fixture.whenStable();
    button('Allow read only').click();
    await fixture.whenStable();
    expect(confirmed).toEqual([{ toolKey: 'plugin:issues/list_issues', connection: 'github-mcp', definitionHash: 'sha256-list_issues' }]);
    expect(changes).toEqual([]);
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(read.checked).toBe(true);
    expect(document.activeElement).toBe(read);
    expect(tr.textContent).not.toContain('needs your confirmation');
  });

  it('keeps the mode when the confirmation is cancelled', async () => {
    const { fixture, radio, dialog, button, changes, confirmed } = await render();
    radio('read').click();
    await fixture.whenStable();
    button('Cancel').click();
    await fixture.whenStable();
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(radio('read').checked).toBe(false);
    expect(radio('approval').checked).toBe(true);
    expect(document.activeElement).toBe(radio('read'));
    expect(changes).toEqual([]);
    expect(confirmed).toEqual([]);
  });

  it('sets a confirmed tool to Read only without asking again', async () => {
    const { fixture, radio, dialog, changes } = await render({ readOnlyConfirmed: true });
    radio('read').click();
    await fixture.whenStable();
    expect(dialog.hasAttribute('open')).toBe(false);
    expect(changes).toEqual([expect.objectContaining({ toolKey: 'plugin:issues/list_issues', connection: 'github-mcp', mode: 'READ_ONLY' })]);
  });

  it('keeps Read only unavailable when there is no definition to confirm', async () => {
    const { tr, radio } = await render({ definitionHash: null });
    expect(radio('read').getAttribute('aria-disabled')).toBe('true');
    expect(tr.textContent).toContain('Read only needs your confirmation for this tool');
  });
});

describe('tool-policy-list MCP bulk modes', () => {
  it('offers bulk changes and defaults for MCP servers, and does not confirm eligible read tools', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons()] });
    const fixture = TestBed.createComponent(GwToolPolicyList);
    const group = DEMO_TOOL_GROUPS_EXTERNAL[0];
    fixture.componentRef.setInput('groups', [{ ...group, group: { ...group.group, tools: group.group.tools.map((tool) => ({ ...tool, mode: 'APPROVAL', storedMode: 'APPROVAL' })) } }]);
    fixture.componentRef.setInput('slug', 'dev');
    const bulk = vi.fn();
    const modeChange = vi.fn();
    const confirm = vi.fn();
    fixture.componentInstance.bulk.subscribe(bulk);
    fixture.componentInstance.modeChange.subscribe(modeChange);
    fixture.componentInstance.confirmReadOnly.subscribe(confirm);
    fixture.detectChanges();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const trigger = [...root.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.includes('Set all to…'))!;
    expect(trigger).toBeDefined();
    trigger.click();
    root.querySelector<HTMLButtonElement>('[role="menuitem"][data-value="DEFAULT"]')!.click();
    expect(bulk).toHaveBeenCalledWith({ connection: 'github-mcp', mode: 'DEFAULT' });
    const read = root.querySelector<HTMLInputElement>('input[value="read"]')!;
    read.click();
    await fixture.whenStable();
    expect(confirm).not.toHaveBeenCalled();
    expect(modeChange).toHaveBeenCalledWith(expect.objectContaining({ toolKey: 'mcp:github-mcp/list_issues', mode: 'READ_ONLY' }));
  });
});
