import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { ConnectionImportsApi, ConnectionImportTask } from '../api/connection-imports.api';
import { TimeService } from '../time/time.service';
import { CoreStore } from './core.store';

/** Import tasks belong to the window, so polling continues when the person opens another page. */
@Injectable({ providedIn: 'root' })
export class ConnectionImportsStore {
  private readonly api = inject(ConnectionImportsApi);
  private readonly core = inject(CoreStore);
  private readonly time = inject(TimeService);
  private readonly destroy = inject(DestroyRef);
  private readonly items = signal<readonly ConnectionImportTask[]>([]);
  private observed: { running: boolean; instance: number } | null = null;
  private epoch = 0;
  private pending: Promise<void> | null = null;

  readonly tasks = computed(() => {
    const items = this.items();
    if (!this.core.running() || this.core.instanceChanges() !== this.observed?.instance) return [];
    const now = this.time.now();
    return items.filter(task => Date.parse(task.expiresAt) > now
      && (task.status === 'review' || (task.status === 'awaiting' && task.progress !== null)));
  });

  constructor() {
    effect(onCleanup => {
      const running = this.core.running();
      const instance = this.core.instanceChanges();
      untracked(() => {
        this.observeCore(running, instance);
        if (running) void this.refresh();
      });
      if (running) {
        const timer = setInterval(() => void this.refresh(), 5000);
        onCleanup(() => clearInterval(timer));
      }
    });
    this.destroy.onDestroy(() => this.reset());
  }

  refresh(): Promise<void> {
    if (this.destroy.destroyed) return Promise.resolve();
    const running = this.core.running();
    const instance = this.core.instanceChanges();
    this.observeCore(running, instance);
    if (!running) return Promise.resolve();
    if (this.pending) return this.pending;
    const epoch = this.epoch;
    const request = this.api.list().then(result => {
      if (!this.destroy.destroyed && epoch === this.epoch && this.core.running()
          && instance === this.core.instanceChanges()) this.items.set(result.items);
    }, () => {
      // Keep the last report during a transient failure; the UI can still show that progress is paused.
    }).finally(() => {
      if (this.pending === request) this.pending = null;
    });
    this.pending = request;
    return request;
  }

  /** Call after cancellation succeeds so an earlier list response cannot restore the removed task. */
  remove(id: string): void {
    if (this.destroy.destroyed) return;
    this.epoch++;
    this.pending = null;
    this.items.update(items => items.filter(task => task.id !== id));
    void this.refresh();
  }

  private observeCore(running: boolean, instance: number): void {
    if (this.observed?.running === running && this.observed.instance === instance) return;
    this.observed = { running, instance };
    this.reset();
  }

  private reset(): void {
    this.epoch++;
    this.pending = null;
    this.items.set([]);
  }
}
