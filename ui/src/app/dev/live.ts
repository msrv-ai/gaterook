import { ChangeDetectionStrategy, Component, ResourceRef, computed, inject, input, resource } from '@angular/core';
import { isApiError } from '../core/api/api-error';
import { TimeService } from '../core/time/time.service';
import { DEMO_NOW } from './demo-time';

/**
 * Dev only. The gallery renders the elements of merged areas with the dev core's GW_DEMO world (`npm run dev -w ui`
 * starts it): the same answers the pages get, so a mismatch between an element and the real API shows here first.
 * Fixtures stay for the states the demo world doesn't have right now (it is one world at one moment) and for the
 * areas whose step isn't merged yet; those are rebased to the core's clock with {@link demoFixture}.
 */
export function liveRead<T>(load: () => Promise<T>): ResourceRef<T | undefined> {
  return resource({ loader: () => load() });
}

/** Every ISO date-time string in a fixture (the API's `Iso` fields). */
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/;

/** A deep copy of `value` with every ISO date-time moved by `deltaMs`. */
export function rebase<T>(value: T, deltaMs: number): T {
  if (typeof value === 'string') {
    return (ISO.test(value) ? new Date(Date.parse(value) + deltaMs).toISOString() : value) as T;
  }
  if (Array.isArray(value)) return value.map((v) => rebase(v, deltaMs)) as T;
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, rebase(v, deltaMs)])) as T;
  }
  return value;
}

/**
 * A fixture written for the demo world's 14:30 (BLUEPRINT §9), moved to now on the core's clock: a pending request
 * still has its 0:48 left and "2 min ago" still reads so, next to the live sections of the same page. Call it in an
 * injection context (a demo's field initializer).
 */
export function demoFixture<T>(value: T): T {
  const now = inject(TimeService).now();
  return rebase(value, Math.round((now - DEMO_NOW) / 1000) * 1000);
}

/**
 * `<p [gwLiveNote]="read">`: what a live section shows until the dev core answered, or why it didn't (the gallery
 * runs with the dev loop's core; without it there is nothing live to show).
 */
@Component({
  selector: 'p[gwLiveNote]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-text-caption', '[class.gw-fg-muted]': '!failed()', '[class.gw-fg-danger]': 'failed()', role: 'status' },
  template: `{{ text() }}`,
})
export class GwLiveNote {
  readonly gwLiveNote = input.required<ResourceRef<unknown>>();
  protected readonly failed = computed(() => this.gwLiveNote().status() === 'error');
  protected readonly text = computed(() => {
    const read = this.gwLiveNote();
    if (read.status() !== 'error') return 'Loading from the dev core…';
    const e = read.error();
    const why = isApiError(e) ? e.detail : e instanceof Error ? e.message : String(e);
    return `The dev core didn't answer (${why}). Start it with npm run dev -w ui.`;
  });
}
