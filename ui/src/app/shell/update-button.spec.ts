import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from '../app';
import { DesktopBridge, UpdateState } from '../core/desktop/desktop-bridge';
import { EVENT_STREAM_FETCH } from '../core/events/event-stream';
import { provideKitIcons } from '../kit/icons';

@Component({ template: '<p>Page</p>' })
class Page {}

const HEALTH = { status: 'ok', version: '0.4.0', uptimeMs: 60_000, ports: { local: 7465, public: 7466 }, runtime: 'native', warnings: [] };
const settle = () => new Promise<void>((resolve) => setTimeout(resolve));
const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

describe('sidebar update action', () => {
  let push: (state: UpdateState) => void;
  let install: ReturnType<typeof vi.fn>;
  let subscribe: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    install = vi.fn(() => new Promise<void>(() => undefined));
    subscribe = vi.fn((cb: (state: UpdateState) => void) => { push = cb; return () => undefined; });
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([
          { path: 'overview', component: Page, data: { section: 'Overview' } },
          { path: 'settings', component: Page, data: { section: 'Settings' } },
        ]),
        provideHttpClient(), provideHttpClientTesting(), provideKitIcons(),
        { provide: DesktopBridge, useValue: {
          available: true, platform: 'darwin',
          onCoreState: (cb: Parameters<DesktopBridge['onCoreState']>[0]) => {
            cb({ kind: 'ready', firstRun: false, locked: null, version: '0.4.0', ports: HEALTH.ports, runtime: 'native' });
            return () => undefined;
          },
          onDeepLink: () => () => undefined, onEvent: () => null, setNativeTheme: () => undefined,
          onUpdateState: subscribe, installUpdate: install,
        } },
        { provide: EVENT_STREAM_FETCH, useValue: () => new Promise<Response>(() => undefined) },
      ],
    });
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).match(() => true);
    document.body.innerHTML = '';
  });

  async function openApp() {
    const fixture = TestBed.createComponent(App);
    document.body.appendChild(fixture.nativeElement);
    await TestBed.inject(Router).navigateByUrl('/overview');
    await settle();
    TestBed.inject(HttpTestingController).expectOne('/api/health').flush(HEALTH);
    await settle();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, button: () => el.querySelector<HTMLButtonElement>('.gw-sidebar__update') };
  }

  it('appears below the version when a release is available and survives navigation', async () => {
    const { fixture, el, button } = await openApp();
    expect(button()).toBeNull();
    push({ state: 'up-to-date', version: '0.4.0' });
    await fixture.whenStable();
    expect(button()).toBeNull();
    push({ state: 'available', version: '0.4.1' });
    await fixture.whenStable();
    expect(text(button())).toBe('Update and restart');
    expect(text(button()!.previousElementSibling)).toBe('v0.4.0');
    expect(install).not.toHaveBeenCalled();
    await TestBed.inject(Router).navigateByUrl('/settings');
    await fixture.whenStable();
    expect(text(button())).toBe('Update and restart');
    expect(subscribe).toHaveBeenCalledTimes(1);
    el.querySelector<HTMLButtonElement>('.gw-sidebar__toggle')!.click();
    await fixture.whenStable();
    expect(el.querySelector('.gw-shell')!.hasAttribute('data-collapsed')).toBe(true);
    expect(text(button())).toBe('Update and restart');
    expect(button()!.title).toContain('0.4.1');
  });

  it('starts the download on click and keeps the action busy through restart', async () => {
    const { fixture, el, button } = await openApp();
    push({ state: 'available', version: '0.4.1' });
    await fixture.whenStable();
    button()!.click();
    await fixture.whenStable();
    expect(install).toHaveBeenCalledTimes(1);
    expect(button()!.getAttribute('aria-disabled')).toBe('true');
    expect(button()!.getAttribute('aria-busy')).toBe('true');
    expect(button()!.querySelector('.gw-spinner')).not.toBeNull();
    push({ state: 'downloading', version: '0.4.1', percent: 63 });
    await fixture.whenStable();
    expect(text(button())).toBe('Downloading 63%');
    expect(text(el.querySelector('#gw-update-status'))).toContain('Downloading 63%');
    button()!.click();
    expect(install).toHaveBeenCalledTimes(1);
    push({ state: 'installing', version: '0.4.1' });
    await fixture.whenStable();
    expect(text(button())).toBe('Restarting…');
  });

  it('shows an actionable failure and retries without losing the version', async () => {
    const { fixture, el, button } = await openApp();
    install.mockRejectedValueOnce(new Error('Download failed. Try again.'));
    push({ state: 'available', version: '0.4.1' });
    await fixture.whenStable();
    button()!.click();
    await fixture.whenStable();
    expect(text(button())).toBe('Retry update');
    expect(button()!.getAttribute('aria-disabled')).toBeNull();
    const error = el.querySelector('#gw-update-status')!;
    expect(text(error)).toBe('Download failed. Try again.');
    expect(error.classList.contains('gw-sr-only')).toBe(false);
    expect(button()!.getAttribute('aria-describedby')).toBe(error.id);
    button()!.click();
    await fixture.whenStable();
    expect(install).toHaveBeenCalledTimes(2);
  });
});
