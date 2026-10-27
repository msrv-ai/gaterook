import { Injectable, inject, signal } from '@angular/core';
import { isApiError } from '../api/api-error';
import { OAuthApi, OAuthConsent } from '../api/tunnel.api';
import { EventStream } from '../events/event-stream';

/** What became of an answer to a sign-in: taken, or the request was gone (answered elsewhere or expired, 409/404). */
export type ConsentAnswer = 'answered' | 'gone';

/**
 * Sign-ins waiting for an answer on this computer (OAuth consent, ARCHITECTURE.md §7.3, step K3): the shell's consent
 * dialog shows the oldest, on any page. `oauth.consent.requested` reads the list again, `oauth.consent.resolved`
 * drops the one that was answered (here, elsewhere or by its timeout). A core without the route (404) or away keeps
 * the last list: the dialog never shows a request the core didn't report.
 */
@Injectable({ providedIn: 'root' })
export class ConsentStore {
  private readonly api = inject(OAuthApi);
  private readonly events = inject(EventStream);
  private readonly pendingSignal = signal<OAuthConsent[]>([]);
  /** Answered here and on their way: a list read meanwhile must not bring one back into the dialog. */
  private readonly answered = new Set<string>();
  private started = false;

  /** Oldest first, as the core lists them. */
  readonly pending = this.pendingSignal.asReadonly();

  start(): void {
    if (this.started) return;
    this.started = true;
    this.events.on('oauth.consent.requested', () => void this.refresh());
    this.events.on('oauth.consent.resolved', (e) => {
      this.answered.delete(e.id);
      this.pendingSignal.update((list) => list.filter((c) => c.id !== e.id));
    });
    void this.refresh();
  }

  async refresh(): Promise<void> {
    try {
      const list = await this.api.consents();
      this.pendingSignal.set(list.filter((c) => !this.answered.has(c.id)));
    } catch {
      // Not in this build (404), locked or away: CoreStore shows that; keep what was known.
    }
  }

  /**
   * The person's answer (`POST /api/oauth/consents/{id}`). The request leaves the list first, so the dialog moves to
   * the next one at once; if the answer didn't arrive, the list is read again (a request still waiting comes back)
   * and the error goes to the caller.
   */
  async decide(id: string, decision: 'allow' | 'deny'): Promise<ConsentAnswer> {
    this.answered.add(id);
    this.pendingSignal.update((list) => list.filter((c) => c.id !== id));
    try {
      await this.api.decide(id, decision);
      return 'answered';
    } catch (e) {
      if (isApiError(e) && (e.status === 409 || e.status === 404)) return 'gone';
      this.answered.delete(id);
      void this.refresh();
      throw e;
    }
  }
}
