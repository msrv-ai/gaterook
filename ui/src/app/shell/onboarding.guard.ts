import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, firstValueFrom } from 'rxjs';
import { Onboarding, OverviewApi } from '../core/api/overview.api';
import { CoreStore } from '../core/stores/core.store';

/**
 * Whether a start should open on the Overview welcome guide: `firstRun` of this start's ready line, or nothing
 * set up yet (no connection, getting-started not hidden) and onboarding not skipped. Never because a key is missing:
 * a lost master key is the locked mode of the status screen, not a first run. "Skip setup" wins over `firstRun`: the
 * core keeps `firstRun` for its whole first start, and a reload after Skip setup must not bring Welcome back.
 */
export function wantsOnboarding(o: Onboarding): boolean {
  if (o.skipped) return false;
  if (o.firstRun) return true;
  const connections = o.setupMap.nodes.find((n) => n.id === 'connections')?.count ?? 0;
  return connections === 0 && !o.gettingStarted.dismissed;
}

/** The window's one decision, once asked: whether its first page is Welcome (null when the core couldn't say). */
let decision: Promise<boolean> | null = null;
/** Set once the decision is known: navigations from then on are a person's own and go where they lead. */
let decided = false;

/**
 * Decides where the window opens, once: a person who leaves Welcome for Add connection must not be sent back. The
 * decision waits until the core runs (the status screen covers the window meanwhile), then asks `/api/onboarding`;
 * if the core can't say, the app opens where it was asked to.
 *
 * Every navigation that starts before the decision is known waits for the same decision and follows it — the first
 * navigation of the window, and any other started meanwhile (a deep link, `ui.open`, a click). The router cancels
 * the earlier ones, so letting a later one through unasked would lose Welcome on a first run whenever anything
 * navigated in that moment (E1 saw Welcome on a first demo start only some of the time).
 */
export const onboardingGuard: CanActivateFn = async (_route, state) => {
  if (decided) return true;
  if (state.url.startsWith('/dev')) return true;
  // inject() and toObservable() work only before the first await.
  const router = inject(Router);
  decision ??= decide(inject(OverviewApi), inject(CoreStore));
  const welcome = await decision;
  if (!welcome || state.url.startsWith('/overview')) return true;
  return router.parseUrl('/overview');
};

async function decide(api: OverviewApi, core: CoreStore): Promise<boolean> {
  const running = firstValueFrom(toObservable(core.running).pipe(filter(Boolean)));
  core.start();
  try {
    await running;
    return wantsOnboarding(await api.onboarding());
  } catch {
    return false;
  } finally {
    decided = true;
  }
}

/** Tests only: forget that the window decided. */
export function resetOnboardingGuard(): void {
  decision = null;
  decided = false;
}
