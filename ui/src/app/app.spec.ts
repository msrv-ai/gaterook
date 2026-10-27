import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { provideKitIcons } from './kit/icons';

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
    expect(TestBed.inject(Title).getTitle()).toBe('Core starting · MCP Gateway');
    TestBed.inject(HttpTestingController).match(() => true);
    fixture.destroy();
  });
});
