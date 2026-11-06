import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { provideKitIcons } from './kit/icons';
import { ToastService } from './kit/toast.service';
import { SettingsStore } from './core/stores/settings.store';

describe('App', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideKitIcons()],
    });
  });

  it('shows the status screen while the core has not answered', async () => {
    const fixture = TestBed.createComponent(App);
    document.body.appendChild(fixture.nativeElement);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const screen = el.querySelector('gw-status-screen')!;
    expect(screen).not.toBeNull();
    expect(el.querySelector('.gw-shell')).toBeNull();
    // It is the window: the main landmark, named by its title, and the window's title (kit: core-starting.html).
    expect(screen.getAttribute('role')).toBe('main');
    expect(screen.id).toBe('main');
    expect(document.getElementById(screen.getAttribute('aria-labelledby')!)?.tagName).toBe('H1');
    expect(TestBed.inject(Title).getTitle()).toBe('Core starting · GateRook');
    TestBed.inject(HttpTestingController).match(() => true);
    fixture.destroy();
  });

  it('applies the loaded notification duration and later saved changes', async () => {
    const settings = TestBed.inject(SettingsStore);
    settings.put({ name: 'general', values: { toastDurationSeconds: 30 }, version: 1, updatedAt: null });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const toasts = TestBed.inject(ToastService);
    expect(toasts.show({ title: 'Saved' }).timeout).toBe(30000);
    settings.put({ name: 'general', values: { toastDurationSeconds: 60 }, version: 2, updatedAt: null });
    await fixture.whenStable();
    expect(toasts.show({ title: 'Saved again' }).timeout).toBe(60000);
    TestBed.inject(HttpTestingController).match(() => true);
    fixture.destroy();
  });
});
