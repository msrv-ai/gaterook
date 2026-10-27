import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SearchResult } from '../../../core/api/core.api';
import { ApprovalsStore } from '../../../core/stores/approvals.store';
import { COPY_FAILED } from '../../clipboard';
import { provideKitIcons } from '../../icons';
import { GwCommandPalette } from './command-palette';

/** "Copy URL of endpoint …": the palette is a modal dialog, so the copy must not depend on the (inert) page. */

const RESULT: SearchResult = {
  goTo: [{ id: 'overview', label: 'Overview', route: '/overview' }],
  actions: [],
  connections: [],
  endpoints: [{ slug: 'dev', env: 'dev', route: '/endpoints/dev' }],
};

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
