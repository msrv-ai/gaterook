import { Location } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import {
  NavigationCancel,
  NavigationCancellationCode,
  NavigationEnd,
  NavigationError,
  NavigationSkipped,
  NavigationStart,
  Router,
} from '@angular/router';

/** Tracks browser entries visited in this window so Back cannot leave the application's session. */
@Injectable({ providedIn: 'root' })
export class NavigationHistory {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly position = signal(-1);
  private readonly busy = signal(false);
  private entries: (number | null)[] = [];
  private browserPath = '';
  private traversing = false;

  readonly canGoBack = computed(() => this.position() > 0 && !this.busy());

  constructor() {
    const destroy = inject(DestroyRef);
    // A native history traversal happens before the router schedules its NavigationStart.
    const changes = this.location.subscribe((event) => {
      if (event.type === 'popstate') this.busy.set(true);
    });
    const navigations = this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.busy.set(true);
        this.browserPath = this.location.path(true);
        if (event.navigationTrigger !== 'imperative') {
          this.traversing = true;
          this.restorePosition(event.restoredState?.navigationId ?? null);
        }
      } else if (event instanceof NavigationEnd) {
        const extras = this.router.currentNavigation()?.extras;
        if (this.position() < 0) {
          this.reset();
        } else if (this.traversing || extras?.replaceUrl || extras?.skipLocationChange || this.browserPath === this.location.path(true)) {
          // Angular rewrites navigationId when an existing browser entry is visited or replaced.
          this.entries[this.position()] = this.navigationId();
        } else {
          this.entries.splice(this.position() + 1, Infinity, this.navigationId());
          this.position.update((index) => index + 1);
        }
        this.finish();
      } else if (event instanceof NavigationSkipped) {
        // A native traversal to the same URL can be skipped without emitting NavigationStart.
        if (this.router.currentNavigation()?.trigger !== 'imperative') this.restorePosition(this.navigationId());
        this.finish();
      } else if (event instanceof NavigationCancel || event instanceof NavigationError) {
        if (event instanceof NavigationCancel && (
          event.code === NavigationCancellationCode.Redirect ||
          event.code === NavigationCancellationCode.SupersededByNewNavigation
        )) return;
        // A rejected native Back can replace the destination entry with the current page. Start a new
        // conservative boundary rather than infer a browser position from the resulting duplicate IDs.
        if (this.traversing) this.reset();
        this.finish();
      }
    });
    destroy.onDestroy(() => {
      changes.unsubscribe();
      navigations.unsubscribe();
    });
  }

  back(): void {
    if (!this.canGoBack()) return;
    // Lock before the asynchronous popstate event: a double click must never cross the session boundary.
    this.busy.set(true);
    this.location.back();
  }

  private navigationId(): number | null {
    const state = this.location.getState() as { navigationId?: unknown } | null;
    return typeof state?.navigationId === 'number' ? state.navigationId : null;
  }

  private restorePosition(id: number | null): void {
    const index = id === null ? -1 : this.entries.indexOf(id);
    if (index < 0) this.reset();
    else this.position.set(index);
  }

  private reset(): void {
    this.entries = [this.navigationId()];
    this.position.set(0);
  }

  private finish(): void {
    this.traversing = false;
    this.busy.set(false);
  }
}
