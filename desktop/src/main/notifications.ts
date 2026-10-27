// Shows the core's `notify.desktop` events and sign-in requests as OS notifications (ARCHITECTURE.md §7.3, §7.4;
// pure rules: notices.ts). Main keeps every notification it shows (otherwise the garbage collector drops them with
// their click handlers), closes one when its request is decided elsewhere, replaces the burst summary in place, and
// tells the core what the OS did (`POST /internal/notifications/desktop`: shown, failed, unsupported) so Settings ›
// Notifications can say whether desktop notifications work.
import { Notification } from 'electron';
import type { Logger } from './log.ts';
import { actionAt, actionIndexOf, consentNotice, denyRequest, notificationSpec, parseNotice, placement, type DesktopNotice }
  from './notices.ts';

export interface NotificationsOptions {
  readonly log: Logger;
  readonly windowFocused: () => boolean;
  /** Brings the window forward on a route (`approvals/ap_…`, `approvals`, `settings/notifications`). */
  readonly review: (route: string) => void;
  /** Brings the window forward as it is (a sign-in: the dialog shows wherever the window is). */
  readonly focus: () => void;
  readonly deny: (path: string, body: object) => Promise<number>;
  readonly report: (event: 'shown' | 'failed' | 'unsupported', detail: string | null) => void;
  readonly badge: (pending: number) => void;
  /**
   * False in the development smoke: notifications are built but not put on the screen (an automated run must not
   * fill the person's notification centre, nor ask the OS for permission).
   */
  readonly display?: boolean;
}

export class DesktopNotifications {
  private readonly o: NotificationsOptions;
  private readonly shown = new Map<string, Notification>();
  private readonly notices = new Map<string, DesktopNotice>();
  private lastReport: string | null = null;

  constructor(o: NotificationsOptions) {
    this.o = o;
    if (process.platform === 'darwin') {
      // Notifications of an earlier session belong to nothing now: their buttons would do nothing.
      try {
        Notification.removeAll();
      } catch {
        // Older systems without the API.
      }
    }
  }

  /** The core restarted: it forgot what main reported, so the next outcome is reported again. */
  coreRestarted(): void {
    this.lastReport = null;
  }

  onNotice(data: unknown): void {
    const n = parseNotice(data);
    if (!n) {
      this.o.log('notify.desktop: not a notice, ignored');
      return;
    }
    if (n.pendingCount !== null) this.o.badge(n.pendingCount);
    // The smoke never shows notifications, so whether this system could doesn't decide its path (Linux CI has no
    // notification server).
    const where = placement(this.o.windowFocused(), this.o.display === false || Notification.isSupported());
    if (where === 'window') return; // the window in front shows the queue and its counter
    if (where === 'unsupported') {
      this.report('unsupported', 'This system has no desktop notifications');
      return;
    }
    this.show(n);
  }

  /** A request was decided (anywhere): its notification has nothing left to do. */
  resolved(id: unknown): void {
    if (typeof id !== 'string') return;
    this.shown.get(id)?.close();
    this.shown.delete(id);
    this.notices.delete(id);
  }

  /** The notices on screen now (the smoke checks them; their text is the core's, already masked). */
  visible(): DesktopNotice[] {
    return [...this.notices.values()];
  }

  /** A button of a notice on screen, pressed by the development smoke (OS notifications can't be clicked by a test). */
  press(id: string, index: number | null): boolean {
    const n = this.notices.get(id);
    if (!n) return false;
    this.act(n, index);
    return true;
  }

  onConsent(data: unknown): void {
    const c = consentNotice(data);
    if (!c || this.o.windowFocused() || !Notification.isSupported()) return;
    const key = `consent:${c.id}`;
    this.shown.get(key)?.close();
    const note = new Notification({ title: c.title, body: c.body, timeoutType: 'never', urgency: 'critical', silent: false });
    note.on('click', () => this.o.focus());
    note.on('close', () => {
      if (this.shown.get(key) === note) this.shown.delete(key);
    });
    note.on('failed', (_e, error) => this.o.log(`sign-in notification failed: ${error}`));
    this.shown.set(key, note);
    if (this.o.display !== false) note.show();
  }

  consentResolved(id: unknown): void {
    if (typeof id !== 'string') return;
    this.shown.get(`consent:${id}`)?.close();
    this.shown.delete(`consent:${id}`);
  }

  private show(n: DesktopNotice): void {
    this.shown.get(n.id)?.close(); // the summary replaces itself
    const spec = notificationSpec(n, process.platform);
    const note = new Notification({ ...spec, actions: spec.actions.map((a) => ({ ...a })) });
    note.on('show', () => this.report('shown', null));
    note.on('failed', (_e, error) => {
      this.o.log(`notification failed: ${error}`);
      this.report('failed', String(error).slice(0, 300));
    });
    note.on('click', () => this.act(n, null));
    note.on('action', (details, index) => this.act(n, actionIndexOf(details, index)));
    note.on('close', () => {
      if (this.shown.get(n.id) === note) {
        this.shown.delete(n.id);
        this.notices.delete(n.id);
      }
    });
    this.shown.set(n.id, note);
    this.notices.set(n.id, n);
    if (this.o.display !== false) note.show();
  }

  /** Review opens the request; Deny denies it in place. There is no Approve here, whatever the event said. */
  private act(n: DesktopNotice, index: number | null): void {
    const action = actionAt(n, index);
    if (action === 'deny') {
      const deny = denyRequest(n);
      if (!deny) return;
      void this.o.deny(deny.path, deny.body).then((status) => {
        // 409: decided elsewhere meanwhile, nothing to say.
        if (status >= 400 && status !== 409) this.o.log(`deny from a notification: HTTP ${status}`);
      }, (e: Error) => this.o.log(`deny from a notification failed: ${e.message}`));
      return;
    }
    this.o.review(n.route);
  }

  private report(event: 'shown' | 'failed' | 'unsupported', detail: string | null): void {
    const key = `${event}:${detail ?? ''}`;
    if (key === this.lastReport) return;
    this.lastReport = key;
    this.o.report(event, detail);
  }
}
