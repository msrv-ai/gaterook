import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ApprovalDetail, ApprovalsApi } from '../core/api/approvals.api';
import { EventStream } from '../core/events/event-stream';
import { GatewayEvents } from '../core/events/gateway-events';
import { clockTime } from '../core/time/time-format';
import { clientName } from '../kit/elements/shared/client-mark';
import { ToastOptions, ToastService } from '../kit/toast.service';
import { deepLinkUrl } from './deep-links';

type Notice = GatewayEvents['notify.desktop'];

/** The kit's in-app toast of one request (design/elements/approval-notification.html "In-app toast"). */
export function requestToast(r: Pick<ApprovalDetail, 'tool' | 'connection' | 'endpoint' | 'env' | 'client' | 'expiresAt'>): Pick<ToastOptions, 'title' | 'body'> {
  const who = r.client ? clientName(r.client) : 'an agent';
  const deadline = r.expiresAt ? ` Auto-deny at ${clockTime(r.expiresAt, true)}.` : '';
  return {
    title: `${r.env === 'prod' ? 'PROD · ' : ''}Approval needed`,
    body: `${r.tool} on ${r.connection ?? r.endpoint} (${r.env}) from ${who}.${deadline}`,
  };
}

/**
 * The window's own toast for the core's desktop notifications (`notify.desktop`, ARCHITECTURE.md §7.4; kit
 * approval-notification "In-app toast"): while the window is in front, Electron main shows no OS notification and
 * this toast replaces it — Review opens the request (the queue for a summary), it stays until used or dismissed, and
 * it never holds the decision: there is no Approve or Deny in it. A request decided meanwhile (here, in a chat or by
 * its timeout) takes its toast away. In the background the OS notification is main's; nothing shows here then.
 *
 * The toast names the request from the request itself (`GET /api/approvals/{id}`), as the kit writes it: tool,
 * connection, environment, client, auto-deny time — never the action text, which only the request shows, escaped.
 */
@Injectable({ providedIn: 'root' })
export class DesktopNoticeToasts {
  private readonly doc = inject(DOCUMENT);
  private readonly events = inject(EventStream);
  private readonly approvals = inject(ApprovalsApi);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  /** Notice id (a request's id, or the summary's) → its toast on screen. */
  private readonly shown = new Map<string, number>();
  private started = false;

  start(): void {
    if (this.started) return;
    this.started = true;
    this.events.on('notify.desktop', (n) => void this.onNotice(n));
    this.events.on('approval.resolved', (e) => this.drop(e.id));
  }

  private async onNotice(n: Notice): Promise<void> {
    // In the background the OS notification is Electron main's (and a browser has none): nothing to add here.
    if (!n || typeof n.id !== 'string' || !this.doc.hasFocus()) return;
    switch (n.kind) {
      case 'request': {
        const url = deepLinkUrl(`approvals/${n.id}`);
        if (!url) return;
        let detail: ApprovalDetail | null = null;
        try {
          detail = await this.approvals.get(n.id);
        } catch {
          // The core's own title then; the request is still one Review away.
        }
        if (detail && detail.status !== 'pending') return;
        const text = detail ? requestToast(detail) : { title: `${n.prod ? 'PROD · ' : ''}Approval needed`, body: n.subtitle ?? null };
        this.show(n.id, { ...text, tone: 'warning', icon: 'hourglass', actions: [{ label: 'Review', onClick: () => this.open(url) }] });
        return;
      }
      case 'summary':
        this.show(n.id, {
          title: n.title,
          body: n.body ?? null,
          tone: 'warning',
          icon: 'hourglass',
          actions: [{ label: 'Review', onClick: () => this.open('/approvals') }],
        });
        return;
      case 'test':
        this.show(n.id, { title: n.title, body: 'While this window is in front, an approval request shows here like this, with Review.', tone: 'neutral', icon: 'bell' });
        return;
    }
  }

  /** One toast per notice: a summary replaces the last one (the core sends it again as requests pile up). */
  private show(id: string, options: ToastOptions): void {
    this.drop(id);
    this.shown.set(id, this.toasts.show(options).id);
  }

  private drop(id: string): void {
    const toast = this.shown.get(id);
    if (toast === undefined) return;
    this.toasts.dismiss(toast);
    this.shown.delete(id);
  }

  private open(url: string): void {
    void this.router.navigateByUrl(url);
  }
}
