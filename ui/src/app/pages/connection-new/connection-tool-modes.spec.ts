import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ConnectionUsedBy } from '../../core/api/connections.api';
import { EndpointsApi, ToolRow } from '../../core/api/endpoints.api';
import { TimeService } from '../../core/time/time.service';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { ConnectionToolModes } from './connection-tool-modes';

const tool = (patch: Partial<ToolRow> = {}): ToolRow => ({
  toolKey: 'postgres/sql_query', name: 'sql_query', mode: 'APPROVAL', storedMode: 'APPROVAL',
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  declaredBy: null, readOnlyAllowed: true, reason: null, readOnlyConfirmed: false,
  definitionHash: null, overrides: { rowLimit: 25 }, version: 4, ...patch,
});

describe('connection tool modes', () => {
  afterEach(() => { document.body.innerHTML = ''; });
  async function render(options: { tool?: Partial<ToolRow>; tools?: readonly Partial<ToolRow>[]; readOnly?: boolean; readOnlyEndpoints?: readonly string[]; loadError?: boolean; blocked?: string } = {}) {
    const initial = options.tools ?? [options.tool ?? {}];
    const stored: Record<string, ToolRow[]> = { dev: initial.map((row) => tool(row)), stage: initial.map((row) => tool({ ...row, version: 8 })) };
    let loadError = options.loadError;
    const groups = (slug: string) => [
      { group: 'dev-db', connection: 'dev-db', env: 'dev' as const, tools: structuredClone(stored[slug]) },
      { group: 'other-db', connection: 'other-db', env: 'dev' as const, tools: [tool({ mode: 'OFF' })] },
    ];
    const api = {
      get: vi.fn(async (slug: string) => ({ endpoint: { access: options.readOnly || options.readOnlyEndpoints?.includes(slug) ? 'read-only' : 'standard', blockedReason: options.blocked ?? null } })),
      tools: vi.fn(async (slug: string) => {
        if (loadError) throw new Error('offline');
        return groups(slug);
      }),
      setMode: vi.fn(async (slug: string, request: { toolKey: string; connection: string; mode: ToolRow['mode']; version: number }) => {
        const current = request.connection === 'dev-db' ? stored[slug].find((row) => row.toolKey === request.toolKey) : undefined;
        if (!current || current.version !== request.version) {
          throw new ApiError('conflict', 409, { detail: 'The tool changed. Reload its current version.' });
        }
        stored[slug] = stored[slug].map((row) => row.toolKey === request.toolKey
          ? { ...row, mode: request.mode, storedMode: request.mode, version: row.version + 1, bypassUntil: null } : row);
        return groups(slug);
      }),
      bulk: vi.fn(async (slug: string, request: { group: string; mode: ToolRow['mode'] | 'DEFAULT' }) => {
        const changed: string[] = [];
        const skipped: { toolKey: string; reason: string }[] = [];
        if (request.group !== 'dev-db') throw new Error('Wrong connection');
        stored[slug] = stored[slug].map((row) => {
          if (request.mode === 'READ_ONLY' && !row.readOnlyAllowed) {
            skipped.push({ toolKey: row.toolKey, reason: 'Not declared read only' });
            return row;
          }
          const mode = request.mode === 'DEFAULT' ? row.readOnlyAllowed ? 'READ_ONLY' : 'APPROVAL' : request.mode;
          changed.push(row.toolKey);
          return { ...row, mode, storedMode: mode, version: row.version + 1, bypassUntil: null };
        });
        return { changed, skipped };
      }),
      confirmReadOnly: vi.fn(async (slug: string, request: { toolKey: string; connection: string; definitionHash: string }) => {
        // Confirmation records trust in the declaration; the saved mode stays unchanged until setMode.
        stored[slug] = stored[slug].map((row) => request.connection === 'dev-db' && row.toolKey === request.toolKey
          ? { ...row, readOnlyConfirmed: true, readOnlyAllowed: true, version: row.version + 1 } : row);
        return groups(slug);
      }),
    };
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons(),
      { provide: EndpointsApi, useValue: api }, { provide: TimeService, useValue: { absolute: (at: string) => at } },
    ] });
    const fixture = TestBed.createComponent(ConnectionToolModes);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      for (let i = 0; i < 3; i++) {
        await new Promise((resolve) => setTimeout(resolve));
        fixture.detectChanges();
      }
      await fixture.whenStable();
    };
    fixture.componentRef.setInput('connection', 'dev-db');
    fixture.componentRef.setInput('rows', ['dev', 'stage'].flatMap((endpoint) => stored[endpoint].map((row): ConnectionUsedBy => ({ endpoint, tool: row.name, mode: row.storedMode }))));
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    fixture.detectChanges();
    await settle();
    const root = fixture.nativeElement as HTMLElement;
    const trigger = (slug = 'dev') => root.querySelector<HTMLButtonElement>(`button[aria-label$=" on ${slug}"]`)!;
    const menu = (slug = 'dev') => root.querySelector<HTMLElement>(`[role="menu"][aria-label$=" on ${slug}"]`)!;
    const option = (mode: string, slug = 'dev') => menu(slug).querySelector<HTMLButtonElement>(`[data-value="${mode}"]`)!;
    const pick = async (mode: string, slug = 'dev') => {
      if (trigger(slug).getAttribute('aria-expanded') !== 'true') trigger(slug).click();
      option(mode, slug).click();
      await settle();
    };
    const bulkTrigger = () => root.querySelector<HTMLButtonElement>('button[aria-label^="Set all "]')!;
    const bulkOption = (mode: string) => root.querySelector<HTMLButtonElement>(`#connection-bulk-menu [data-value="${mode}"]`)!;
    const scope = async (slug: string) => {
      const select = root.querySelector<HTMLSelectElement>('#connection-bulk-endpoint')!;
      select.value = slug;
      select.dispatchEvent(new Event('change'));
      await settle();
    };
    const pickBulk = async (mode: string) => {
      if (bulkTrigger().getAttribute('aria-expanded') !== 'true') bulkTrigger().click();
      bulkOption(mode).click();
      await settle();
    };
    return { fixture, settle, root, trigger, menu, option, pick, bulkTrigger, bulkOption, pickBulk, scope, api, changed, stored, recover: () => { loadError = false; } };
  }

  it('groups endpoints under one tool and saves every mode to only the chosen pair with its latest version', async () => {
    const p = await render();
    expect(p.root.querySelectorAll('tbody tr')).toHaveLength(1);
    expect(p.root.querySelectorAll('thead th')).toHaveLength(3);
    expect(p.root.querySelector('a[href^="/activity"]')?.getAttribute('href')).toBe('/activity?q=connection:dev-db%20tool:sql_query');
    for (const [index, mode] of ['OFF', 'READ_ONLY', 'BYPASS', 'APPROVAL'].entries()) {
      await p.pick(mode);
      expect(p.api.setMode).toHaveBeenLastCalledWith('dev', {
        toolKey: 'postgres/sql_query', connection: 'dev-db', mode, definitionHash: null, version: 4 + index,
      });
      expect(p.option(mode).getAttribute('aria-checked')).toBe('true');
    }
    expect(p.trigger('stage').textContent).toContain('Approval');
    expect(p.stored['dev'][0].overrides).toEqual({ rowLimit: 25 });
  });

  it('keeps the saved mode during a request and reloads the current mode after a version conflict', async () => {
    const p = await render();
    let reject!: (error: unknown) => void;
    p.api.setMode.mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail; }));
    await p.pick('BYPASS');
    expect(p.trigger().getAttribute('aria-disabled')).toBe('true');
    expect(document.activeElement).toBe(p.trigger());
    expect(p.trigger().textContent).toContain('Approval');
    expect(p.option('APPROVAL').getAttribute('aria-checked')).toBe('true');
    expect(p.trigger('stage').disabled).toBe(false);
    p.stored['dev'][0] = tool({ mode: 'OFF', storedMode: 'OFF', version: 9 });
    reject(new ApiError('conflict', 409, { title: 'Changed meanwhile', detail: 'Reload the current mode.' }));
    await p.settle();
    expect(p.trigger().textContent).toContain('Off');
    expect(p.trigger().disabled).toBe(false);
    expect(TestBed.inject(ToastService).toasts().at(-1)?.title).toBe("The mode wasn't changed");
    await p.pick('READ_ONLY');
    expect(p.api.setMode).toHaveBeenLastCalledWith('dev', expect.objectContaining({ version: 9 }));
  });

  it('disables Bypass for read-only endpoints', async () => {
    const p = await render({ readOnly: true });
    expect(p.option('BYPASS').disabled).toBe(true);
    expect(p.menu().textContent).toContain('Unavailable: the endpoint is read only');
    await p.pick('BYPASS');
    expect(p.api.setMode).not.toHaveBeenCalled();
  });

  it('locks a mutating tool on a read-only endpoint and explains why', async () => {
    const p = await render({ readOnly: true, tool: { readOnlyAllowed: false, annotations: { ...tool().annotations, readOnlyHint: false, destructiveHint: true }, storedMode: 'OFF' } });
    expect(p.trigger().disabled).toBe(true);
    expect(p.root.textContent).toContain('Locked: endpoint dev is read only');
  });

  it('disables Read only for a mutating tool on a standard endpoint', async () => {
    const p = await render({ tool: { readOnlyAllowed: false, annotations: { ...tool().annotations, readOnlyHint: false, destructiveHint: true } } });
    expect(p.option('READ_ONLY').disabled).toBe(true);
    await p.pick('READ_ONLY');
    expect(p.api.setMode).not.toHaveBeenCalled();
    await p.pick('BYPASS');
    expect(p.api.setMode).toHaveBeenCalledOnce();
  });

  it('refreshes modes when saved connection settings change their version', async () => {
    const p = await render();
    p.stored['dev'][0] = tool({ mode: 'OFF', storedMode: 'OFF', version: 12 });
    p.fixture.componentRef.setInput('connectionVersion', 2);
    await p.settle();
    expect(p.trigger().textContent).toContain('Off');
    await p.pick('READ_ONLY');
    expect(p.api.setMode).toHaveBeenLastCalledWith('dev', expect.objectContaining({ version: 12 }));
  });

  it('requires an individual acknowledgement before trusting a plugin read-only declaration', async () => {
    const p = await render({ tool: { declaredBy: 'search-plugin', readOnlyAllowed: false, definitionHash: 'sha256-current' } });
    const setMode = p.api.setMode.getMockImplementation()!;
    let finishMode!: () => void;
    p.api.setMode.mockImplementationOnce(async (...args) => {
      await new Promise<void>((resolve) => { finishMode = resolve; });
      return setMode(...args);
    });
    await p.pick('READ_ONLY');
    const dialog = p.root.querySelector('dialog')!;
    const allow = dialog.querySelector<HTMLButtonElement>('.gw-btn--primary')!;
    expect(dialog.open).toBe(true);
    expect(document.activeElement?.textContent).toBe('Cancel');
    expect(p.option('APPROVAL').getAttribute('aria-checked')).toBe('true');
    allow.click();
    expect(p.api.confirmReadOnly).not.toHaveBeenCalled();
    dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await p.settle();
    p.changed.mockClear();
    allow.click();
    await p.settle();
    expect(p.api.confirmReadOnly).toHaveBeenCalledExactlyOnceWith('dev', {
      toolKey: 'postgres/sql_query', connection: 'dev-db', definitionHash: 'sha256-current',
    });
    expect(p.api.setMode).toHaveBeenCalledExactlyOnceWith('dev', {
      toolKey: 'postgres/sql_query', connection: 'dev-db', mode: 'READ_ONLY', definitionHash: 'sha256-current', version: 5,
    });
    expect(p.trigger().textContent).toContain('Approval');
    expect(p.trigger().getAttribute('aria-disabled')).toBe('true');
    expect(p.changed).not.toHaveBeenCalled();
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
    expect(p.stored['dev'][0]).toMatchObject({ storedMode: 'APPROVAL', readOnlyConfirmed: true, version: 5 });
    finishMode();
    await p.settle();
    expect(p.trigger().textContent).toContain('Read only');
    expect(p.trigger().getAttribute('aria-disabled')).not.toBe('true');
    expect(p.changed).toHaveBeenCalledOnce();
    expect(p.stored['dev'][0]).toMatchObject({ storedMode: 'READ_ONLY', readOnlyConfirmed: true, version: 6 });
    expect(p.stored['stage'][0]).toMatchObject({ storedMode: 'APPROVAL', readOnlyConfirmed: false, version: 8 });
    expect(p.trigger('stage').textContent).toContain('Approval');
    expect(document.activeElement).toBe(p.trigger());
  });

  it('reloads the saved mode after confirmation succeeds but its mode change conflicts, then retries at the latest version', async () => {
    const p = await render({ tool: { declaredBy: 'search-plugin', readOnlyAllowed: false, definitionHash: 'sha256-current' } });
    p.api.setMode.mockImplementationOnce(async () => {
      p.stored['dev'][0] = { ...p.stored['dev'][0], version: 7 };
      throw new ApiError('conflict', 409, { detail: 'Another change updated this tool. Try again.' });
    });
    await p.pick('READ_ONLY');
    const dialog = p.root.querySelector('dialog')!;
    dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await p.settle();
    dialog.querySelector<HTMLButtonElement>('.gw-btn--primary')!.click();
    await p.settle();
    expect(p.api.setMode).toHaveBeenCalledExactlyOnceWith('dev', {
      toolKey: 'postgres/sql_query', connection: 'dev-db', mode: 'READ_ONLY', definitionHash: 'sha256-current', version: 5,
    });
    expect(p.trigger().textContent).toContain('Approval');
    expect(p.stored['dev'][0]).toMatchObject({ storedMode: 'APPROVAL', readOnlyConfirmed: true, version: 7 });
    expect(TestBed.inject(ToastService).toasts().map((toast) => toast.tone)).toEqual(['danger']);
    expect(p.api.tools).toHaveBeenCalledTimes(3);

    await p.pick('READ_ONLY');
    expect(dialog.open).toBe(false);
    expect(p.api.confirmReadOnly).toHaveBeenCalledOnce();
    expect(p.api.setMode).toHaveBeenLastCalledWith('dev', {
      toolKey: 'postgres/sql_query', connection: 'dev-db', mode: 'READ_ONLY', definitionHash: 'sha256-current', version: 7,
    });
    expect(p.trigger().textContent).toContain('Read only');
  });

  it('does not use another connection’s tool when the confirmation response no longer contains the selected pair', async () => {
    const p = await render({ tool: { declaredBy: 'search-plugin', readOnlyAllowed: false, definitionHash: 'sha256-current' } });
    p.api.confirmReadOnly.mockResolvedValueOnce([
      { group: 'other-db', connection: 'other-db', env: 'dev', tools: [tool({ readOnlyConfirmed: true, version: 99 })] },
    ]);
    await p.pick('READ_ONLY');
    const dialog = p.root.querySelector('dialog')!;
    dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await p.settle();
    dialog.querySelector<HTMLButtonElement>('.gw-btn--primary')!.click();
    await p.settle();
    expect(p.api.setMode).not.toHaveBeenCalled();
    expect(p.trigger().textContent).toContain('Approval');
    expect(TestBed.inject(ToastService).toasts().map((toast) => toast.tone)).toEqual(['danger']);
    expect(p.api.tools).toHaveBeenCalledTimes(3);
  });

  it('does not write the mode after switching connections while confirmation is pending', async () => {
    const p = await render({ tool: { declaredBy: 'search-plugin', readOnlyAllowed: false, definitionHash: 'sha256-current' } });
    const confirm = p.api.confirmReadOnly.getMockImplementation()!;
    let finishConfirmation!: () => void;
    p.api.confirmReadOnly.mockImplementationOnce(async (...args) => {
      await new Promise<void>((resolve) => { finishConfirmation = resolve; });
      return confirm(...args);
    });
    await p.pick('READ_ONLY');
    const dialog = p.root.querySelector('dialog')!;
    dialog.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    await p.settle();
    dialog.querySelector<HTMLButtonElement>('.gw-btn--primary')!.click();
    await p.settle();
    expect(p.api.confirmReadOnly).toHaveBeenCalledOnce();
    expect(p.api.setMode).not.toHaveBeenCalled();
    p.fixture.componentRef.setInput('connection', 'other-db');
    await p.settle();
    p.changed.mockClear();
    finishConfirmation();
    await p.settle();
    expect(p.api.setMode).not.toHaveBeenCalled();
    expect(p.changed).not.toHaveBeenCalled();
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
  });

  it('keeps undeclared external tools in Approval, Bypass or Off', async () => {
    const p = await render({ tool: { declaredBy: 'search-mcp', readOnlyAllowed: false, annotations: { readOnlyHint: null, destructiveHint: null, idempotentHint: null, openWorldHint: null }, definitionHash: 'sha256-current' } });
    expect(p.option('READ_ONLY').disabled).toBe(true);
    await p.pick('READ_ONLY');
    expect(p.api.confirmReadOnly).not.toHaveBeenCalled();
    expect(p.api.setMode).not.toHaveBeenCalled();
    expect(p.menu().textContent).toContain('Not declared read only');
  });

  it('shows the saved mode and temporary bypass expiry, then clears the bypass when the saved mode changes', async () => {
    const p = await render({ tool: { mode: 'BYPASS', bypassUntil: '2026-11-02T15:00:00Z' } });
    expect(p.trigger().textContent).toContain('Approval');
    expect(p.root.textContent).toContain('Temporary bypass until 2026-11-02T15:00:00Z');
    await p.pick('OFF');
    expect(p.stored['dev'][0].bypassUntil).toBeNull();
    expect(p.trigger().textContent).toContain('Off');
  });

  it('offers a retry after a load failure without enabling a stale mode picker', async () => {
    const p = await render({ loadError: true });
    expect(p.trigger().disabled).toBe(true);
    expect(p.root.textContent).toContain("Couldn't load the endpoint's tool modes");
    p.recover();
    [...p.root.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.includes('Retry'))!.click();
    await p.settle();
    expect(p.trigger().disabled).toBe(false);
    await p.pick('OFF');
    expect(p.api.setMode).toHaveBeenCalledOnce();
  });

  it('ignores a save response after switching to another connection', async () => {
    const p = await render();
    let resolve!: (groups: never[]) => void;
    p.api.setMode.mockImplementationOnce(() => new Promise((done) => { resolve = done; }));
    await p.pick('OFF');
    p.fixture.componentRef.setInput('connection', 'other-db');
    await p.settle();
    p.changed.mockClear();
    resolve([]);
    await p.settle();
    expect(p.changed).not.toHaveBeenCalled();
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
  });

  it('sets every tool on every endpoint in one request per endpoint and refreshes saved modes', async () => {
    const p = await render({ tools: [{}, { toolKey: 'mcp:notes/update', name: 'update', readOnlyAllowed: false }] });
    await p.pickBulk('BYPASS');
    expect(p.api.bulk.mock.calls).toEqual([
      ['dev', { group: 'dev-db', mode: 'BYPASS' }],
      ['stage', { group: 'dev-db', mode: 'BYPASS' }],
    ]);
    expect(p.api.setMode).not.toHaveBeenCalled();
    expect(p.api.confirmReadOnly).not.toHaveBeenCalled();
    expect(Object.values(p.stored).flat().every((row) => row.storedMode === 'BYPASS')).toBe(true);
    expect(p.trigger().textContent).toContain('Bypass');
    expect(p.trigger('stage').textContent).toContain('Bypass');
    expect(p.root.querySelector('[aria-label="Results of changing all tool modes"]')?.textContent).toContain('2 tools updated');
  });

  it('restores per-tool defaults only on the selected endpoint', async () => {
    const p = await render({ tools: [
      { storedMode: 'OFF', mode: 'OFF', declaredBy: 'notes-mcp' },
      { toolKey: 'mcp:notes/update', name: 'update', storedMode: 'BYPASS', mode: 'BYPASS', readOnlyAllowed: false },
    ] });
    await p.scope('stage');
    await p.pickBulk('DEFAULT');
    expect(p.api.bulk).toHaveBeenCalledExactlyOnceWith('stage', { group: 'dev-db', mode: 'DEFAULT' });
    expect(p.stored['stage'].map((row) => row.storedMode)).toEqual(['READ_ONLY', 'APPROVAL']);
    expect(p.stored['dev'].map((row) => row.storedMode)).toEqual(['OFF', 'BYPASS']);
    expect(p.stored['stage'][0].overrides).toEqual({ rowLimit: 25 });
  });

  it('sets eligible MCP reads without confirmation and reports tools that bulk Read only skipped', async () => {
    const p = await render({ tools: [
      { toolKey: 'mcp:notes/read', name: 'read', declaredBy: 'notes-mcp', readOnlyAllowed: true },
      { toolKey: 'mcp:notes/update', name: 'update', declaredBy: 'notes-mcp', readOnlyAllowed: false },
    ] });
    await p.scope('dev');
    await p.pickBulk('READ_ONLY');
    expect(p.api.confirmReadOnly).not.toHaveBeenCalled();
    expect(p.stored['dev'].map((row) => row.storedMode)).toEqual(['READ_ONLY', 'APPROVAL']);
    expect(p.root.textContent).toContain('1 kept their saved mode');
    expect(p.root.querySelector<HTMLElement>('#connection-bulk-skipped-0')?.hidden).toBe(true);
    p.root.querySelector<HTMLButtonElement>('[aria-controls="connection-bulk-skipped-0"]')!.click();
    expect(p.root.querySelector<HTMLElement>('#connection-bulk-skipped-0')?.hidden).toBe(false);
    expect(p.root.textContent).toContain('update: Not declared read only');
  });

  it('changes an eligible MCP read directly without a per-tool confirmation', async () => {
    const p = await render({ tool: { toolKey: 'mcp:notes/read', declaredBy: 'notes-mcp', readOnlyAllowed: true, readOnlyConfirmed: false } });
    await p.pick('READ_ONLY');
    expect(p.api.confirmReadOnly).not.toHaveBeenCalled();
    expect(p.api.setMode).toHaveBeenCalledExactlyOnceWith('dev', expect.objectContaining({ toolKey: 'mcp:notes/read', mode: 'READ_ONLY' }));
    expect(p.root.querySelector('dialog')?.open).toBe(false);
  });

  it('keeps bulk and individual changes unavailable while a bulk request is pending', async () => {
    const p = await render();
    let resolve!: (value: { changed: string[]; skipped: { toolKey: string; reason: string }[] }) => void;
    p.api.bulk.mockImplementationOnce(() => new Promise((done) => { resolve = done; }));
    await p.pickBulk('OFF');
    expect(p.bulkTrigger().getAttribute('aria-disabled')).toBe('true');
    expect(p.bulkTrigger().getAttribute('aria-busy')).toBe('true');
    expect(p.root.querySelector<HTMLSelectElement>('#connection-bulk-endpoint')?.disabled).toBe(true);
    expect(p.trigger().textContent).toContain('Approval');
    await p.pick('BYPASS');
    await p.pickBulk('APPROVAL');
    expect(p.api.setMode).not.toHaveBeenCalled();
    expect(p.api.bulk).toHaveBeenCalledOnce();
    resolve({ changed: [], skipped: [] });
    await p.settle();
    expect(p.bulkTrigger().getAttribute('aria-disabled')).not.toBe('true');
  });

  it('retains successful endpoints and shows a failed endpoint when a multi-endpoint change partly fails', async () => {
    const p = await render();
    p.api.bulk.mockRejectedValueOnce(new ApiError('conflict', 409, { detail: 'The endpoint changed. Try again.' }));
    await p.pickBulk('OFF');
    expect(p.stored['dev'][0].storedMode).toBe('APPROVAL');
    expect(p.stored['stage'][0].storedMode).toBe('OFF');
    expect(p.root.textContent).toContain('dev: Could not update modes. The endpoint changed. Try again.');
    expect(p.root.textContent).toContain('stage: 1 tool updated');
    expect(TestBed.inject(ToastService).toasts().at(-1)?.tone).toBe('danger');
  });

  it('disables bulk Bypass if any selected endpoint is read only and allows choosing another endpoint', async () => {
    const p = await render({ readOnlyEndpoints: ['dev'] });
    expect(p.bulkOption('BYPASS').disabled).toBe(true);
    await p.pickBulk('BYPASS');
    expect(p.api.bulk).not.toHaveBeenCalled();
    await p.scope('stage');
    expect(p.bulkOption('BYPASS').disabled).toBe(false);
    await p.pickBulk('BYPASS');
    expect(p.api.bulk).toHaveBeenCalledExactlyOnceWith('stage', { group: 'dev-db', mode: 'BYPASS' });
  });

  it('does not offer bulk changes when endpoint details could not load or the endpoint is blocked', async () => {
    const p = await render({ loadError: true });
    expect(p.bulkTrigger().getAttribute('aria-disabled')).toBe('true');
    await p.pickBulk('OFF');
    expect(p.api.bulk).not.toHaveBeenCalled();
    p.recover();
    p.api.get.mockResolvedValue({ endpoint: { access: 'standard', blockedReason: 'Endpoint is unavailable' } });
    [...p.root.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent?.includes('Retry'))!.click();
    await p.settle();
    await p.scope('dev');
    expect(p.root.textContent).toContain('Endpoint is unavailable');
    await p.pickBulk('OFF');
    expect(p.api.bulk).not.toHaveBeenCalled();
  });

  it('does not continue a bulk request on another endpoint after switching connections', async () => {
    const p = await render();
    let resolve!: (value: { changed: string[]; skipped: { toolKey: string; reason: string }[] }) => void;
    p.api.bulk.mockImplementationOnce(() => new Promise((done) => { resolve = done; }));
    await p.pickBulk('OFF');
    p.fixture.componentRef.setInput('connection', 'other-db');
    await p.settle();
    p.changed.mockClear();
    resolve({ changed: ['postgres/sql_query'], skipped: [] });
    await p.settle();
    expect(p.api.bulk).toHaveBeenCalledOnce();
    expect(p.changed).not.toHaveBeenCalled();
    expect(TestBed.inject(ToastService).toasts()).toEqual([]);
    expect(p.root.querySelector('[aria-label="Results of changing all tool modes"]')).toBeNull();
  });
});
