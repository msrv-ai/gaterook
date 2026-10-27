import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, numberAttribute, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EventDetail } from '../../../core/api/activity.api';
import { ApprovalDetail } from '../../../core/api/approvals.api';
import { duration, elapsed } from '../../../core/time/time-format';
import { TimeService } from '../../../core/time/time.service';
import { GwCodeBlock } from '../../../code/code-block';
import { GwCopy } from '../../copy';
import { GwExpand, GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { GwToast } from '../../toast-region';
import { historySteps } from '../approval-request/approval-view';
import { GwClientMark } from '../shared/client-mark';
import {
  actionMatch,
  approvalPath,
  argumentsJson,
  callRows,
  clientLabel,
  decisionBadge,
  durationText,
  errorAlert,
  maskedCaption,
  policySteps,
  rawJson,
  resultStatus,
  sizeText,
  timing,
  tokenParts,
} from './event-view';

let nextId = 0;

/**
 * `<article gwEventDetail [detail]="d" [approval]="a">` (elements/event-detail.html): everything about one logged
 * call, in the order a developer asks: what happened (decision, call, time, duration) · who asked (client, token,
 * endpoint, protocol, trace) · the arguments as stored (masked values stay masked) · why the policy decided so ·
 * who approved, and whether what ran is byte for byte what was approved · what came back · where the time went · the
 * raw record (collapsed). Built from the audit rows of the call; the approval's own record (`approval`, when the page
 * has it) adds the comment and the notifications.
 *
 * Used in the Activity drawer, as the history detail in Approvals and as the last call in Playground.
 */
@Component({
  selector: 'article[gwEventDetail]',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwClientMark, GwCodeBlock, GwCopy, GwToast, GwToggle, GwExpand],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-event-detail', '[attr.aria-labelledby]': 'uid + "-decision " + uid + "-title"' },
  templateUrl: './event-detail.html',
})
export class GwEventDetail {
  protected readonly time = inject(TimeService);

  readonly detail = input.required<EventDetail>();
  /** `GET /api/approvals/{id}` of the call's approval, when there is one: the comment and the notifications. */
  readonly approval = input<ApprovalDetail | null>(null);
  /** The call's heading level (3 in a drawer titled at 2); sections are one below. */
  readonly level = input(3, { transform: numberAttribute });
  /** Hide the Event row (the drawer's title already names it). */
  readonly showEventId = input(true);
  /**
   * Whether the call's trace is a task the core keeps (Activity › Traces): false hides Open trace, which would lead to
   * an empty list (every call has a trace id; only a client's trace or a named task becomes a task). null: unknown.
   */
  readonly traced = input<boolean | null>(null);
  /**
   * The access that made this call, when the page can revoke it (kit `activity.html` drawer A: "Revoke codex-laptop"
   * in the Request header); null hides the button.
   */
  readonly revokeLabel = input<string | null>(null);
  /** Revoke was pressed (its button, for the confirmation to return focus to). */
  readonly revoke = output<HTMLElement>();

  protected readonly uid = `ed-${++nextId}`;
  protected readonly e = computed(() => this.detail().event);
  protected readonly rows = computed(() => callRows(this.detail()));
  protected readonly badge = computed(() => decisionBadge(this.e().decision));
  protected readonly who = computed(() => clientLabel(this.e().client));
  protected readonly duration = computed(() => durationText(this.detail()));
  /** Copy link: the app's own scheme (decision 11, BLUEPRINT §9 "Links"), opened through shell/deep-links.ts. */
  protected readonly link = computed(() => `gaterook://activity/events/${this.e().id}`);
  protected readonly token = computed(() => tokenParts(this.rows().received ?? this.rows().last, this.e().tokenLabel));
  protected readonly protocol = computed(() => (this.rows().received ?? this.rows().last)?.protocolVersion ?? null);
  protected readonly args = computed(() => argumentsJson(this.rows().received));
  protected readonly maskedText = computed(() => maskedCaption(this.args()?.masked ?? 0));
  protected readonly policy = computed(() => policySteps(this.detail()));
  protected readonly policyEnv = computed(() => this.e().env);
  protected readonly asked = computed(() => !!(this.e().approvalId ?? this.rows().received?.approvalId));
  protected readonly history = computed(() => {
    const a = this.approval();
    return a ? historySteps(a) : null;
  });
  protected readonly path = computed(() => approvalPath(this.detail()));
  protected readonly match = computed(() => actionMatch(this.detail()));
  protected readonly status = computed(() => resultStatus(this.detail()));
  protected readonly size = computed(() => sizeText(this.detail()));
  protected readonly completed = computed(() => this.rows().completed);
  protected readonly approvedResult = computed(() => {
    const r = this.approval()?.result;
    return r && r.structured !== null && r.structured !== undefined ? JSON.stringify(r.structured, null, 2) : null;
  });
  protected readonly timing = computed(() => timing(this.detail()));
  protected readonly raw = computed(() => rawJson(this.detail()));
  protected readonly error = computed(() => errorAlert(this.detail()));
  protected readonly traceQuery = computed(() => {
    const id = this.e().traceId;
    return id && this.traced() !== false ? { view: 'traces', q: `trace:${id}` } : null;
  });

  protected readonly elapsed = elapsed;
  protected readonly spanText = duration;
  protected readonly legend: Record<string, { readonly swatch: string; readonly text: string }> = {
    gateway: { swatch: 'gw-legend__swatch--neutral', text: 'Gateway' },
    resource: { swatch: 'gw-legend__swatch--brand', text: 'Resource' },
    approval: { swatch: 'gw-legend__swatch--warning gw-legend__swatch--hatch', text: 'Waiting for approval' },
  };
}
