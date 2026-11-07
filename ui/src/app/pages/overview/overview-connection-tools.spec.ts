import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiError } from '../../core/api/api-error';
import { ConnectionDetail, ConnectionRow, ConnectionsApi } from '../../core/api/connections.api';
import { EndpointsApi, ToolGroup, ToolRow } from '../../core/api/endpoints.api';
import { TimeService } from '../../core/time/time.service';
import { provideKitIcons } from '../../kit/icons';
import { endpoint } from '../endpoints/endpoints.fixtures';
import { OverviewConnectionTools } from './overview-connection-tools';

const health = { state: 'healthy', latencyMs: 3, message: null, checkedAt: null };
const row = (name = 'dev-db', patch: Partial<ConnectionRow> = {}): ConnectionRow => ({
  name, type: 'postgres', typeName: 'PostgreSQL', env: 'dev', host: null, badges: [], secretSource: null,
  health, usedBy: ['dev', 'stage'], verified: true, enabled: true, version: 1, ...patch,
});
const detail = (name = 'dev-db'): ConnectionDetail => ({
  name, type: 'postgres', typeName: 'PostgreSQL', env: 'dev', config: {}, version: 1, secrets: [], health,
  spark24h: [], verified: true,
  usedBy: ['dev', 'stage'].map((endpoint) => ({ endpoint, tool: name === 'dev-db' ? 'sql_query' : 'redis_get', mode: 'APPROVAL' })),
});
const tool = (name = 'sql_query', mode: ToolRow['mode'] = 'APPROVAL', version = 4): ToolRow => ({
  name, toolKey: `postgres/${name}`, mode, storedMode: mode, version,
  annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  declaredBy: null, readOnlyAllowed: true, reason: null, readOnlyConfirmed: false, definitionHash: null, overrides: null,
});

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 3; i++) {
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve));
  }
  await fixture.whenStable();
  fixture.detectChanges();
}

describe('Overview connection tools', () => {
  afterEach(() => { document.body.innerHTML = ''; });

  function setup(get: (name: string) => Promise<ConnectionDetail> = async (name) => detail(name)) {
    const connections = { get: vi.fn(get) };
    let stored = tool();
    const groups = (): ToolGroup[] => [
      { group: 'dev-db', connection: 'dev-db', env: 'dev', tools: [stored] },
      { group: 'other-db', connection: 'other-db', env: 'dev', tools: [tool('redis_get')] },
    ];
    const endpoints = {
      get: vi.fn(async (slug: string) => ({ endpoint: endpoint(slug) })),
      tools: vi.fn(async () => groups()),
      setMode: vi.fn(async (_slug: string, change: { toolKey: string; connection: string; mode: ToolRow['mode']; version: number }) => {
        if (change.version !== stored.version) throw new Error('Wrong tool version');
        stored = tool('sql_query', change.mode, stored.version + 1);
        return groups();
      }),
    };
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons(),
      { provide: ConnectionsApi, useValue: connections }, { provide: EndpointsApi, useValue: endpoints },
      { provide: TimeService, useValue: { absolute: (value: string) => value } },
    ] });
    const fixture = TestBed.createComponent(OverviewConnectionTools);
    const root = fixture.nativeElement as HTMLElement;
    document.body.appendChild(root);
    fixture.componentRef.setInput('connection', row());
    fixture.componentRef.setInput('endpoint', endpoint('dev'));
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    return { fixture, root, connections, endpoints, changed };
  }

  it('loads only the selected endpoint and changes only that connection and tool with the current version', async () => {
    const p = setup();
    await settle(p.fixture);
    expect(p.connections.get).toHaveBeenCalledExactlyOnceWith('dev-db');
    expect(p.endpoints.get).toHaveBeenCalledExactlyOnceWith('dev');
    expect(p.endpoints.tools).toHaveBeenCalledExactlyOnceWith('dev');
    expect(p.root.querySelectorAll('tbody tr')).toHaveLength(1);
    expect(p.root.textContent).toContain('sql_query');
    expect(p.root.textContent).not.toContain('redis_get');
    expect(p.root.querySelectorAll('select option')).toHaveLength(1);
    expect(p.changed).not.toHaveBeenCalled();

    p.root.querySelector<HTMLButtonElement>('button[aria-label="Approval: sql_query on dev"]')!.click();
    p.root.querySelector<HTMLButtonElement>('[role="menuitemradio"][data-value="OFF"]')!.click();
    await settle(p.fixture);
    expect(p.endpoints.setMode).toHaveBeenCalledExactlyOnceWith('dev', {
      toolKey: 'postgres/sql_query', connection: 'dev-db', mode: 'OFF', version: 4,
    });
    expect(p.root.querySelector('button[aria-label="Off: sql_query on dev"]')).not.toBeNull();
    expect(p.changed).toHaveBeenCalledTimes(1);
  });

  it('shows a failed load and retries without presenting another connection’s tools', async () => {
    let failed = true;
    const p = setup(async (name) => {
      if (failed) throw new ApiError('server', 503, { detail: 'The local gateway is restarting.' });
      return detail(name);
    });
    await settle(p.fixture);
    expect(p.root.querySelector('[role="alert"]')?.textContent).toContain('The local gateway is restarting.');
    expect(p.root.querySelector('gw-connection-tool-modes')).toBeNull();
    failed = false;
    p.root.querySelector<HTMLButtonElement>('.gw-alert button')!.click();
    await settle(p.fixture);
    expect(p.root.querySelector('tbody')?.textContent).toContain('sql_query');
    expect(p.connections.get).toHaveBeenCalledTimes(2);
  });

  it('ignores a late response after selecting another connection', async () => {
    let resolve!: (value: ConnectionDetail) => void;
    const late = new Promise<ConnectionDetail>((done) => { resolve = done; });
    const p = setup((name) => name === 'dev-db' ? late : Promise.resolve(detail(name)));
    p.fixture.detectChanges();
    await new Promise((done) => setTimeout(done));
    p.fixture.componentRef.setInput('connection', row('other-db'));
    await settle(p.fixture);
    expect(p.root.querySelector('tbody')?.textContent).toContain('redis_get');
    resolve(detail('dev-db'));
    await settle(p.fixture);
    expect(p.root.querySelector('tbody')?.textContent).toContain('redis_get');
    expect(p.root.querySelector('tbody')?.textContent).not.toContain('sql_query');
  });

  it('shows saved modes and a paused-access explanation without enabling the endpoint', async () => {
    const p = setup();
    p.fixture.componentRef.setInput('endpoint', endpoint('dev', { status: 'disabled' }));
    await settle(p.fixture);
    expect(p.root.textContent).toContain('Effective access: Off');
    expect(p.root.textContent).toContain('Endpoint dev is off. The modes below are saved settings.');
    const trigger = p.root.querySelector<HTMLButtonElement>('button[aria-label="Approval: sql_query on dev"]')!;
    expect(trigger.matches(':disabled')).toBe(true);
    trigger.click();
    p.root.querySelector<HTMLButtonElement>('[role="menuitemradio"][data-value="OFF"]')!.click();
    await settle(p.fixture);
    expect(p.endpoints.setMode).not.toHaveBeenCalled();
  });

  it('does not load or offer modes when the connection is unassigned or changes are blocked', async () => {
    const p = setup();
    p.fixture.componentRef.setInput('connection', row('dev-db', { usedBy: ['stage'] }));
    await settle(p.fixture);
    expect(p.root.textContent).toContain('This service is not connected to dev.');
    expect(p.connections.get).not.toHaveBeenCalled();
    p.fixture.componentRef.setInput('connection', row());
    p.fixture.componentRef.setInput('blocked', 'The gateway is unavailable.');
    await settle(p.fixture);
    expect(p.root.textContent).toContain('The gateway is unavailable.');
    expect(p.root.querySelector('gw-connection-tool-modes')).toBeNull();
    expect(p.connections.get).not.toHaveBeenCalled();
  });
});
