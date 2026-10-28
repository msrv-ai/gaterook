import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NavigationCancel, NavigationEnd, NavigationSkipped, Router, provideRouter } from '@angular/router';
import { filter, firstValueFrom } from 'rxjs';
import { NavigationHistory } from './navigation-history';

@Component({ template: '' })
class Page {}

describe('the titlebar navigation history', () => {
  let router: Router;
  let location: Location;
  let history: NavigationHistory;
  let allowLeaving: boolean;

  beforeEach(() => {
    allowLeaving = true;
    TestBed.configureTestingModule({
      providers: [
        provideLocationMocks(),
        provideRouter([
          { path: 'overview', component: Page },
          { path: 'connections', component: Page },
          { path: 'settings', component: Page, canDeactivate: [() => allowLeaving] },
          { path: 'blocked', component: Page, canActivate: [() => false] },
          { path: 'redirect', redirectTo: 'connections', pathMatch: 'full' },
        ]),
      ],
    });
    router = TestBed.inject(Router);
    location = TestBed.inject(Location);
    // An earlier document is not part of this application's navigation session.
    location.go('/outside');
    location.go('/overview');
    history = TestBed.inject(NavigationHistory);
    router.setUpLocationChangeListener();
  });

  function settled() {
    return firstValueFrom(router.events.pipe(filter((event) =>
      event instanceof NavigationEnd || event instanceof NavigationCancel || event instanceof NavigationSkipped,
    )));
  }

  async function back() {
    const done = settled();
    history.back();
    await done;
  }

  it('stops at the first app entry even when older browser history exists', async () => {
    await router.navigateByUrl('/overview');
    const nativeBack = vi.spyOn(location, 'back');
    expect(history.canGoBack()).toBe(false);
    history.back();
    expect(nativeBack).not.toHaveBeenCalled();

    await router.navigateByUrl('/connections');
    expect(history.canGoBack()).toBe(true);
    await back();
    expect(router.url).toBe('/overview');
    expect(history.canGoBack()).toBe(false);
    history.back();
    expect(nativeBack).toHaveBeenCalledTimes(1);
  });

  it('does not turn replaced filters or skipped location changes into Back stops', async () => {
    await router.navigateByUrl('/overview');
    await router.navigateByUrl('/connections');
    await router.navigateByUrl('/connections?q=dev', { replaceUrl: true });
    await router.navigateByUrl('/settings', { skipLocationChange: true });
    await back();
    expect(router.url).toBe('/overview');
    expect(history.canGoBack()).toBe(false);
  });

  it('follows native Back and Forward, then discards the abandoned forward branch', async () => {
    await router.navigateByUrl('/overview');
    await router.navigateByUrl('/connections');
    await router.navigateByUrl('/settings');
    let done = settled();
    location.back();
    await done;
    expect(router.url).toBe('/connections');
    done = settled();
    location.forward();
    await done;
    expect(router.url).toBe('/settings');
    await back();
    await router.navigateByUrl('/connections?q=new');
    await back();
    expect(router.url).toBe('/connections');
    await back();
    expect(router.url).toBe('/overview');
    expect(history.canGoBack()).toBe(false);
  });

  it('records the redirect destination and ignores a blocked navigation', async () => {
    await router.navigateByUrl('/overview');
    await router.navigateByUrl('/blocked');
    expect(history.canGoBack()).toBe(false);
    await router.navigateByUrl('/redirect');
    expect(router.url).toBe('/connections');
    await back();
    expect(router.url).toBe('/overview');
    expect(history.canGoBack()).toBe(false);
  });

  it('locks immediately so repeated Back clicks cannot leave the app', async () => {
    await router.navigateByUrl('/overview');
    await router.navigateByUrl('/connections');
    const nativeBack = vi.spyOn(location, 'back');
    const done = settled();
    history.back();
    history.back();
    expect(history.canGoBack()).toBe(false);
    expect(nativeBack).toHaveBeenCalledTimes(1);
    await done;
    expect(router.url).toBe('/overview');
    expect(history.canGoBack()).toBe(false);
  });

  it('keeps a rejected native Back inside the app and allows later navigation', async () => {
    await router.navigateByUrl('/overview');
    await router.navigateByUrl('/settings');
    allowLeaving = false;
    await back();
    expect(router.url).toBe('/settings');
    expect(history.canGoBack()).toBe(false);
    allowLeaving = true;
    await router.navigateByUrl('/connections');
    expect(history.canGoBack()).toBe(true);
    await back();
    expect(router.url).toBe('/settings');
    expect(history.canGoBack()).toBe(false);
  });
});
