import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, numberAttribute } from '@angular/core';
import { ApprovalDetail } from '../../../core/api/approvals.api';
import { duration } from '../../../core/time/time-format';
import { TimeService } from '../../../core/time/time.service';
import { GwCodeBlock } from '../../../code/code-block';
import { GwIcon } from '../../icons';
import { clientName } from '../shared/client-mark';

/** What a notification shows of a request: the same fields as the request itself (approval-request). */
export type NotificationRequest = Pick<
  ApprovalDetail,
  | 'tool'
  | 'connection'
  | 'env'
  | 'endpoint'
  | 'client'
  | 'tokenLabel'
  | 'requestedAt'
  | 'expiresAt'
  | 'action'
  | 'summary'
  | 'rule'
  | 'impact'
  | 'commentPolicy'
  | 'status'
  | 'decidedAt'
  | 'decidedVia'
  | 'executedAt'
>;

export type NotificationChannel = 'desktop' | 'slack' | 'telegram' | 'toast';

/** The longest statement each chat shows in full; above it Approve is dropped (never cut). */
const LIMITS: Record<'slack' | 'telegram', number> = { slack: 3000, telegram: 4096 };

/**
 * `<figure gwNotificationPreview channel="slack" [request]="…">` (elements/approval-notification.html): how one
 * approval request reaches the approver outside the window, with the same fields as the request. The desktop
 * notification is always Review and Deny; Slack and Telegram show Approve only off prod, when the policy doesn't
 * require a comment, the action has no hidden or look-alike characters and fits whole, else Deny and Open in app with
 * the reason. Prod is in the title of every channel.
 * After a decision or a timeout the chat message is edited: the buttons give way to the outcome. The preview is inert:
 * it shows what the channel gets, nothing in it acts.
 */
@Component({
  selector: 'figure[gwNotificationPreview]',
  imports: [GwIcon, GwCodeBlock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-approval-notification__preview' },
  templateUrl: './approval-notification.html',
})
export class GwNotificationPreview {
  private readonly time = inject(TimeService);

  readonly gwNotificationPreview = input<NotificationChannel | ''>('');
  readonly channel = input<NotificationChannel>('desktop');
  readonly request = input.required<NotificationRequest>();
  /** The desktop look: macOS or Windows. */
  readonly os = input<'mac' | 'win'>('mac');
  /** Slack: where it was posted ("#deploy-approvals"), for the screen-reader caption. */
  readonly slackChannel = input<string | null>(null);
  /** A fact after the rule ("estimated rows 1"). */
  readonly ruleNote = input<string | null>(null);
  /** The caption under a desktop preview ("macOS"); none when the card names the OS. */
  readonly caption = input<string | null>(null);
  readonly level = input(3, { transform: numberAttribute });

  protected readonly kind = computed(() => this.gwNotificationPreview() || this.channel());
  protected readonly r = computed(() => this.request());
  protected readonly prod = computed(() => this.r().env === 'prod');
  protected readonly titlePrefix = computed(() => (this.prod() ? 'PROD · ' : ''));
  protected readonly who = computed(() => (this.r().client ? clientName(this.r().client!) : 'an agent'));
  protected readonly target = computed(() => this.r().connection ?? this.r().endpoint);
  protected readonly text = computed(() => this.r().action?.text ?? this.r().summary ?? '');
  /** The one line a desktop notification has room for. */
  protected readonly line = computed(() => {
    const summary = this.r().summary;
    if (summary) return summary;
    return this.text().split('\n').find((l) => l.trim()) ?? '';
  });
  protected readonly commentRequired = computed(() => this.r().commentPolicy === 'required');
  protected readonly decided = computed(() => this.r().status !== 'pending' && this.r().status !== 'running');
  /**
   * Why a chat offers no Approve (null when it does, or for the desktop and the toast): the same rules as the core's
   * `notifications.Eligibility` (ARCHITECTURE.md §7.4, §8.6.7), checked in its order, with its words for prod (kit:
   * "Runs on production: approve in the app."). Never on prod, whatever the comment policy; not with a comment
   * required; not when the inspector found hidden or look-alike characters; not when the chat can't show it whole.
   */
  protected readonly noApprove = computed<string | null>(() => {
    const k = this.kind();
    if (k !== 'slack' && k !== 'telegram') return null;
    const a = this.r().action;
    if (this.prod()) return 'Runs on production: approve in the app';
    if (this.commentRequired()) return 'a comment is required: approve in the app';
    if (a && (a.hiddenChars > 0 || a.confusables > 0)) return 'hidden characters: approve in the app';
    if (this.text().length > LIMITS[k]) return 'too long to show here: approve in the app';
    return null;
  });
  /** Whether a chat may offer Approve (Slack and Telegram only). */
  protected readonly approvable = computed(() => {
    const k = this.kind();
    return (k === 'slack' || k === 'telegram') && this.noApprove() === null;
  });
  protected readonly sent = computed(() => this.time.clock(this.r().requestedAt));
  protected readonly deadline = computed(() => this.time.clock(this.r().expiresAt, true));
  protected readonly outcome = computed(() => {
    const r = this.r();
    switch (r.status) {
      case 'completed':
      case 'running': {
        const ms = r.executedAt && r.decidedAt ? Date.parse(r.executedAt) - Date.parse(r.decidedAt) : null;
        const where = r.decidedVia === 'app' || r.decidedVia === 'desktop-notification' ? 'in MCP Gateway' : `in ${r.decidedVia ?? 'the app'}`;
        return { tone: 'success', icon: 'user-check', label: 'Approved', text: `by you ${where}${ms !== null && ms >= 0 ? ` · executed in ${duration(ms)}` : ''}` };
      }
      case 'rejected':
        return { tone: 'danger', icon: 'user-x', label: 'Rejected', text: `by you in ${r.decidedVia === 'app' ? 'MCP Gateway' : (r.decidedVia ?? 'MCP Gateway')}` };
      case 'expired':
        return { tone: 'neutral', icon: 'timer-off', label: 'Expired', text: `Denied automatically at ${this.time.clock(r.expiresAt, true)}` };
      case 'failed':
        return { tone: 'danger', icon: 'circle-alert', label: 'Failed', text: 'Approved, but the call failed' };
      default:
        return { tone: 'neutral', icon: 'ban', label: 'Cancelled', text: 'The request was withdrawn' };
    }
  });
  protected readonly edited = computed(() => (this.r().decidedAt ? this.time.clock(this.r().requestedAt) : this.sent()));
}

let nextCard = 0;

/**
 * `<section gwNotificationCard heading="Slack" letter="S">` (elements/approval-notification.html): the card a channel
 * preview sits in: the channel's mark (an icon tile, or the generic client mark with a letter), its name, a description
 * (`[gwCardDescription]`), optional header actions (`[gwCardActions]`) and the stage with the previews and their note.
 */
@Component({
  selector: 'section[gwNotificationCard]',
  imports: [GwIcon, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card gw-approval-notification', '[attr.aria-labelledby]': 'uid' },
  template: `
    <header class="gw-card__header">
      <div class="gw-card__heading">
        @switch (level()) {
          @case (2) { <h2 class="gw-card__title" [id]="uid"><ng-container [ngTemplateOutlet]="mark" />{{ heading() }}</h2> }
          @default { <h3 class="gw-card__title" [id]="uid"><ng-container [ngTemplateOutlet]="mark" />{{ heading() }}</h3> }
        }
        <p class="gw-card__description"><ng-content select="[gwCardDescription]" /></p>
      </div>
      <ng-content select="[gwCardActions]" />
    </header>
    <div class="gw-card__body gw-approval-notification__stage"><ng-content /></div>
    <ng-template #mark>
      @if (letter()) {
        <span class="gw-client-mark gw-client-mark--generic gw-client-mark--sm" aria-hidden="true">{{ letter() }}</span>
      } @else {
        <span class="gw-icon-tile gw-icon-tile--sm gw-icon-tile--neutral"><svg [lucideIcon]="icon()" class="gw-icon gw-icon--sm"></svg></span>
      }
    </ng-template>
  `,
})
export class GwNotificationCard {
  readonly heading = input.required<string>();
  readonly icon = input('monitor');
  /** Slack and Telegram show a generic mark with their letter. */
  readonly letter = input<string | null>(null);
  readonly level = input(3, { transform: numberAttribute });
  protected readonly uid = `an-${++nextCard}`;
}
