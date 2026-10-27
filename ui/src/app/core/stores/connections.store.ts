import { Injectable, inject, signal } from '@angular/core';
import { ConnectionList, ConnectionsApi } from '../api/connections.api';
import { EventStream } from '../events/event-stream';

/**
 * The connections list with live health: `connection.health` events (test-all, the health scheduler) patch the row
 * they name, so Test all shows its results row by row without reloading.
 */
@Injectable({ providedIn: 'root' })
export class ConnectionsStore {
  private readonly api = inject(ConnectionsApi);
  private readonly events = inject(EventStream);
  private readonly listSignal = signal<ConnectionList | null>(null);
  private readonly loadingSignal = signal(false);
  private readonly errorSignal = signal<unknown>(null);
  private started = false;

  readonly list = this.listSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  start(): void {
    if (this.started) return;
    this.started = true;
    this.events.on('connection.health', (h) => {
      this.listSignal.update((list) =>
        list
          ? {
              ...list,
              items: list.items.map((r) =>
                r.name === h.name
                  ? { ...r, health: { state: h.state, latencyMs: h.latencyMs ?? null, message: h.message, checkedAt: h.checkedAt } }
                  : r,
              ),
            }
          : list,
      );
    });
  }

  async load(): Promise<void> {
    this.start();
    this.loadingSignal.set(true);
    try {
      this.listSignal.set(await this.api.list());
      this.errorSignal.set(null);
    } catch (e) {
      this.errorSignal.set(e);
    } finally {
      this.loadingSignal.set(false);
    }
  }
}
