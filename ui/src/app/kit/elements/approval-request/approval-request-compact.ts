import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input, numberAttribute, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ActionSegment, ApprovalItem } from '../../../core/api/approvals.api';
import { GwClientMark } from '../shared/client-mark';
import { GwApprovalCountdown } from './approval-countdown';
import { previewParts, who, windowSeconds } from './approval-view';

let nextId = 0;

/**
 * `<article gwApprovalRequestCompact [item]="…" [segments]="detail.action.segments" [review]="['/approvals', id]">`
 * (approval-request `--compact`): one row of Overview's "Waiting for you" and of the queue, sorted by time to
 * auto-deny. Client mark, "tool on connection + env from client", the first line of the exact action (cut, whole line
 * in `title`), the countdown and Review. Never Approve or Deny here: a decision needs the exact action on screen.
 *
 * The line comes from the server's segments when the page has them, else from `preview` (or the item's summary) with
 * invisible and bidi characters escaped: a `‹U+202E›` is shown boxed and named, it never reorders the row.
 */
@Component({
  selector: 'article[gwApprovalRequestCompact]',
  imports: [NgTemplateOutlet, RouterLink, GwClientMark, GwApprovalCountdown],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-card gw-approval-request gw-approval-request--compact',
    '[attr.data-state]': 'expiredHere() ? "expired" : item().status === "pending" ? "pending" : item().status',
    '[attr.aria-labelledby]': 'uid',
  },
  template: `
    @if (item().client) {
      <span [gwClientMark]="item().client!" size="sm"></span>
    }
    <div class="gw-approval-request__summary">
      @switch (level()) {
        @case (2) { <h2 class="gw-text-h3 gw-approval-request__title" [id]="uid"><ng-container *ngTemplateOutlet="title" /></h2> }
        @case (4) { <h4 class="gw-text-h3 gw-approval-request__title" [id]="uid"><ng-container *ngTemplateOutlet="title" /></h4> }
        @default { <h3 class="gw-text-h3 gw-approval-request__title" [id]="uid"><ng-container *ngTemplateOutlet="title" /></h3> }
      }
      <p class="gw-approval-request__preview gw-text-code-sm gw-fg-secondary gw-truncate" [title]="line()">@for (p of parts(); track $index) {@if (p.cls) {<span [class]="p.cls" [attr.title]="p.title">{{ p.text }}</span>} @else {{{ p.text }}}}</p>
    </div>
    <div class="gw-approval-request__end">
      @if (item().status === 'pending') {
        <div gwApprovalCountdown [expiresAt]="item().expiresAt" [total]="total()" [announce]="announce()" (ended)="onEnded()"></div>
      }
      <a class="gw-btn gw-btn--sm" [routerLink]="review()" [fragment]="reviewFragment() ?? undefined" [attr.aria-current]="current() ? 'true' : null">Review<span class="gw-sr-only"> {{ what() }}</span></a>
    </div>
    <ng-template #title><code class="gw-code">{{ item().tool }}</code> on <span class="gw-approval-request__target"><code class="gw-code">{{ target() }}</code> <span class="gw-sr-only">environment</span> <span [class]="'gw-env gw-env--sm gw-env--' + item().env">{{ item().env }}</span></span> <span class="gw-approval-request__from gw-text-small gw-fg-muted">from {{ client() }}</span></ng-template>
  `,
})
export class GwApprovalRequestCompact {
  readonly item = input.required<ApprovalItem>();
  /** The exact action's segments (`ApprovalDetail.action.segments`), when the page loaded the detail. */
  readonly segments = input<readonly ActionSegment[] | null>(null);
  /** The action's text, when only that is known; the item's summary when neither is given. */
  readonly preview = input<string | null>(null);
  /** Where Review leads: the request in Approvals (its title takes focus there). */
  readonly review = input.required<string | readonly unknown[]>();
  readonly reviewFragment = input<string | null>(null);
  readonly level = input(3, { transform: numberAttribute });
  /**
   * Read out once, assertively, when the last minute starts (kit `data-gw-countdown-announce`: "sql_execute on dev-db
   * is denied automatically in less than a minute"); by default nothing, as a list may hold many rows. Approvals sets
   * it on every queued request; the detail pane's copy of the timer stays silent so nothing repeats.
   */
  readonly announce = input<string | null>(null);

  /**
   * The row's request is the one open next to the list (Approvals' queue): its Review link carries `aria-current`, as
   * the kit marks it; the page puts `gw-card--selected` on the row itself.
   */
  readonly current = input(false, { transform: booleanAttribute });
  readonly expired = output<void>();

  protected readonly uid = `arc-${++nextId}`;
  protected readonly expiredHere = signal(false);
  protected readonly target = computed(() => this.item().connection ?? this.item().endpoint);
  protected readonly client = computed(() => who(this.item().client));
  protected readonly what = computed(() => `${this.item().tool} on ${this.target()}`);
  protected readonly parts = computed(() => previewParts(this.segments(), this.preview() ?? this.item().summary ?? ''));
  /** The whole line for `title`, escapes included: the tooltip can't be reordered either. */
  protected readonly line = computed(() => this.parts().map((p) => p.text).join(''));
  protected readonly total = computed(() => windowSeconds(this.item()));

  protected onEnded(): void {
    this.expiredHere.set(true);
    this.expired.emit();
  }
}
