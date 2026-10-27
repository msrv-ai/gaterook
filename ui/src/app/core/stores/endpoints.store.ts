import { Injectable, inject, signal } from '@angular/core';
import { EndpointSummary, EndpointsApi } from '../api/endpoints.api';
import { EventStream } from '../events/event-stream';

/** The endpoints list (Endpoints, the command palette's "Copy URL", the setup map). Requests change their counts. */
@Injectable({ providedIn: 'root' })
export class EndpointsStore {
  private readonly api = inject(EndpointsApi);
  private readonly events = inject(EventStream);
  private readonly listSignal = signal<EndpointSummary[] | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private started = false;

  readonly list = this.listSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  async load(): Promise<void> {
    if (!this.started) {
      this.started = true;
      this.events.on('approval.requested', () => void this.load());
      this.events.on('approval.resolved', () => void this.load());
    }
    try {
      this.listSignal.set(await this.api.list());
      this.errorSignal.set(null);
    } catch (e) {
      this.errorSignal.set(e);
    }
  }
}
