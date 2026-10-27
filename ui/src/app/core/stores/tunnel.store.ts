import { Injectable, computed, inject, signal } from '@angular/core';
import { isNotAvailable } from '../api/api-error';
import { CoreApi } from '../api/core.api';
import { TunnelApi, TunnelState } from '../api/tunnel.api';
import { EventStream } from '../events/event-stream';

/**
 * The tunnel (step K3): the sidebar's "Tunnel off / on" and the app-wide "Tunnel is public" banner. Until K3 is
 * merged the core answers `off` in its counters and has no `/api/tunnel`: `available` stays false and the UI shows
 * "Tunnel off" with nothing to switch.
 */
@Injectable({ providedIn: 'root' })
export class TunnelStore {
  private readonly api = inject(TunnelApi);
  private readonly core = inject(CoreApi);
  private readonly events = inject(EventStream);
  private readonly stateSignal = signal<TunnelState | null>(null);
  private readonly summarySignal = signal<string>('off');
  private readonly availableSignal = signal(false);
  private started = false;

  readonly state = this.stateSignal.asReadonly();
  readonly available = this.availableSignal.asReadonly();
  /** `off`, `starting`, `on` or `error`. */
  readonly summary = this.summarySignal.asReadonly();
  readonly on = computed(() => this.summary() === 'on');
  /** The published endpoint while the tunnel is on (the banner names it). */
  readonly endpoint = computed(() => this.stateSignal()?.endpoint ?? null);

  start(): void {
    if (this.started) return;
    this.started = true;
    this.events.on('tunnel.state', (s) => {
      this.summarySignal.set(s.state);
      void this.refresh();
    });
    void this.refresh();
  }

  /** "Turn off tunnel": the state comes back at once and again as `tunnel.state` events. */
  async stop(): Promise<void> {
    const state = await this.api.stop();
    this.stateSignal.set(state);
    this.summarySignal.set(state.state);
  }

  async refresh(): Promise<void> {
    try {
      this.summarySignal.set((await this.core.counters()).tunnel);
    } catch {
      return;
    }
    try {
      const state = await this.api.get();
      this.stateSignal.set(state);
      this.summarySignal.set(state.state);
      this.availableSignal.set(true);
    } catch (e) {
      if (isNotAvailable(e)) this.availableSignal.set(false);
    }
  }
}
