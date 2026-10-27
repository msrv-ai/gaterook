import { Injectable, inject, signal } from '@angular/core';
import { ApprovalItem, ApprovalsApi } from '../api/approvals.api';
import { CoreApi } from '../api/core.api';
import { EventStream } from '../events/event-stream';

/**
 * What waits for a person (ARCHITECTURE.md §8.1 stores): the sidebar's and titlebar's counter, the pending queue.
 * `approval.requested` / `approval.resolved` events refresh both, so a request decided in Slack or expired by the
 * core disappears everywhere at once.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalsStore {
  private readonly approvals = inject(ApprovalsApi);
  private readonly core = inject(CoreApi);
  private readonly events = inject(EventStream);
  private readonly pendingSignal = signal<ApprovalItem[]>([]);
  private readonly countSignal = signal(0);
  private started = false;

  /** How many requests wait now. */
  readonly pendingCount = this.countSignal.asReadonly();
  /** The pending queue, soonest auto-deny first (as the core sorts it). */
  readonly pending = this.pendingSignal.asReadonly();

  start(): void {
    if (this.started) return;
    this.started = true;
    this.events.on('approval.requested', () => void this.refresh());
    this.events.on('approval.resolved', (e) => {
      this.pendingSignal.update((list) => list.filter((a) => a.id !== e.id));
      void this.refresh();
    });
    void this.refresh();
  }

  async refresh(): Promise<void> {
    try {
      const counters = await this.core.counters();
      this.countSignal.set(counters.pendingApprovals);
      const page = await this.approvals.list('pending');
      this.pendingSignal.set(page.items);
      this.countSignal.set(page.pending);
    } catch {
      // The core is away: CoreStore shows that; keep the last known numbers.
    }
  }
}
