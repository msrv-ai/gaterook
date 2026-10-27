import { ChangeDetectionStrategy, Component, input, output, viewChild } from '@angular/core';
import { EventDetail } from '../../../core/api/activity.api';
import { ApprovalDetail } from '../../../core/api/approvals.api';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwIcon } from '../../icons';
import { GwEventDetail } from './event-detail';

let nextId = 0;

/**
 * `<gw-event-drawer #d [detail]="detail" description="Call 12 of 23 in trace 4f2a9c">` (event-detail.html, "In the
 * Activity drawer"): the element in a `gw-drawer--lg`, opened from an event row with `d.open(row)`, so the list stays
 * in place; Esc or Close returns focus to the row's button.
 */
@Component({
  selector: 'gw-event-drawer',
  imports: [GwDialog, GwDialogClose, GwIcon, GwEventDetail],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog class="gw-drawer gw-drawer--lg" gwDialog #drawer="gwDialog" (closed)="closed.emit($event)" [attr.aria-labelledby]="uid + '-title'" [attr.aria-describedby]="description() ? uid + '-desc' : null">
      @if (detail(); as d) {
        <header class="gw-drawer__header">
          <h2 class="gw-drawer__title" [id]="uid + '-title'">Event <span class="gw-mono">{{ d.event.callId }}</span></h2>
          @if (description()) {
            <p class="gw-drawer__description" [id]="uid + '-desc'">{{ description() }}</p>
          }
          <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-drawer__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
        </header>
        <div class="gw-drawer__body">
          <article gwEventDetail [detail]="d" [approval]="approval()" [showEventId]="false" [traced]="traced()" [revokeLabel]="revokeLabel()" (revoke)="revoke.emit($event)"></article>
        </div>
      }
    </dialog>
  `,
})
export class GwEventDrawer {
  readonly detail = input<EventDetail | null>(null);
  readonly approval = input<ApprovalDetail | null>(null);
  /** "Call 12 of 23 in trace 4f2a9c". */
  readonly description = input<string | null>(null);
  /** See `GwEventDetail.traced`. */
  readonly traced = input<boolean | null>(null);
  /** See `GwEventDetail.revokeLabel`. */
  readonly revokeLabel = input<string | null>(null);
  readonly revoke = output<HTMLElement>();
  /** The drawer closed (Esc, Close, the backdrop): the page drops `#event-<id>` from its address. */
  readonly closed = output<string>();

  protected readonly uid = `evd-${++nextId}`;
  private readonly drawer = viewChild.required<GwDialog>('drawer');

  /** Opens once the detail just set has rendered; focus returns to `opener`. */
  open(opener?: HTMLElement | null): void {
    this.drawer().openAfterRender(opener);
  }

  close(): void {
    this.drawer().close();
  }

  get isOpen(): boolean {
    return this.drawer().isOpen();
  }
}
