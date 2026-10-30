import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SearchResult } from '../../../core/api/core.api';
import { ApprovalsStore } from '../../../core/stores/approvals.store';
import { COPY_FAILED } from '../../clipboard';
import { provideKitIcons } from '../../icons';
import { GwCommandPalette, matches, paletteOptions } from './command-palette';

/** "Copy URL of endpoint …": the palette is a modal dialog, so the copy must not depend on the (inert) page. */

const RESULT: SearchResult = {
  goTo: [{ id: 'overview', label: 'Overview', route: '/overview' }],
  actions: [],
  connections: [],
  endpoints: [{ slug: 'dev', env: 'dev', route: '/endpoints/dev' }],
};

it('names concrete connectors in search and presents search connectors as databases', () => {
  const result: SearchResult = { ...RESULT, connections: [
    { name: 'search-db', type: 'opensearch', env: 'dev', route: '/connections/search-db' },
    { name: 'elastic-db', type: 'elasticsearch', env: 'dev', route: '/connections/elastic-db' },
    { name: 'cloud-logs', type: 'gcp-logging', env: 'dev', route: '/connections/cloud-logs' },
  ] };
  const rows = paletteOptions(result, { pending: 0, address: '127.0.0.1:7465' }).filter((row) => row.group === 'connections');
  expect(rows.map((row) => [row.label, row.hint, row.icon])).toEqual([
    ['search-db', 'OpenSearch', 'database'], ['elastic-db', 'Elasticsearch', 'database'], ['cloud-logs', 'GCP Cloud Logging', 'scroll-text'],
  ]);
});

@Component({
  imports: [GwCommandPalette],
  template: `<button id="opener" type="button">Search</button><gw-command-palette #p [result]="result" address="127.0.0.1:7465" />`,
})
class Host {
  readonly result = RESULT;
  readonly palette = viewChild.required<GwCommandPalette>('p');
}

/** The app-wide live region (the palette has its own result-count status line inside its dialog). */
const announced = (role: 'status' | 'alert') =>
  Array.from(document.querySelectorAll(`[role="${role}"]`)).find((e) => !e.closest('gw-command-palette')) ?? null;

async function openAndRunCopy(): Promise<HTMLElement> {
  const fixture = TestBed.createComponent(Host);
  document.body.appendChild(fixture.nativeElement);
  fixture.detectChanges();
  await fixture.whenStable();
  const el = fixture.nativeElement as HTMLElement;
  fixture.componentInstance.palette().open(el.querySelector<HTMLElement>('#opener'));
  fixture.detectChanges();
  el.querySelector<HTMLButtonElement>('#cmdk-o-copy-dev')!.click();
  fixture.detectChanges();
  return el;
}

beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [provideRouter([]), provideKitIcons(), { provide: ApprovalsStore, useValue: { pendingCount: signal(0) } }],
  });
});
afterEach(() => {
  document.body.innerHTML = '';
  Reflect.deleteProperty(navigator, 'clipboard');
  vi.useRealTimers();
});

describe('command palette copy', () => {
  it('copies the endpoint URL and announces it outside the closed palette', async () => {
    const written: string[] = [];
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (t: string) => void written.push(t) } });
    vi.useFakeTimers();
    const el = await openAndRunCopy();
    await vi.advanceTimersByTimeAsync(60);
    expect(written).toEqual(['http://127.0.0.1:7465/mcp/dev']);
    expect(el.querySelector<HTMLDialogElement>('#cmdk')!.open).toBe(false);
    // In the page, not inside the palette's dialog, which is closed (hidden) by now.
    expect(announced('status')?.textContent).toBe('Copied to clipboard');
  });

  it('a refused write is announced as a failure, never as copied', async () => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.reject(new Error('denied')) } });
    vi.useFakeTimers();
    await openAndRunCopy();
    await vi.advanceTimersByTimeAsync(60);
    expect(announced('alert')?.textContent).toBe(COPY_FAILED);
    expect(announced('status')?.textContent ?? '').toBe('');
  });
});

describe('command palette words', () => {
  it('names pages and actions in the kit’s words, whatever the core’s search calls them', () => {
    const options = paletteOptions(
      {
        goTo: [
          { id: 'clients', label: 'Clients', route: '/clients' },
          { id: 'overview', label: 'Overview', route: '/overview' },
        ],
        actions: [{ id: 'connect-client', label: 'Connect a client', route: '/clients' }],
        connections: [],
        endpoints: [],
      },
      { pending: 0, address: '127.0.0.1:7465' },
    );
    const byId = Object.fromEntries(options.map((o) => [o.id, o]));
    expect(byId['clients'].label).toBe('AI apps');
    expect(byId['clients'].hint).toBe('Access');
    expect(byId['connect-client'].label).toBe('Connect your AI app');
    // The old word still finds the page and the action (kit data-keywords "clients mcp …").
    expect(options.filter((o) => matches(o, 'client')).map((o) => o.id)).toEqual(['clients', 'connect-client']);
    expect(options.filter((o) => matches(o, 'ai app')).map((o) => o.id)).toEqual(['clients', 'connect-client']);
  });
});
