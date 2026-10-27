import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ActivityEvent, EventDetail } from '../../core/api/activity.api';
import { duration, formatBytes } from '../../core/time/time-format';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { GwCopy } from '../../kit/copy';
import { GwToggle } from '../../kit/disclosure';
import { GwEventDetail } from '../../kit/elements/event-detail/event-detail';
import { literalParts } from '../../kit/elements/shared/literals';
import { GwIcon } from '../../kit/icons';
import { GwTabs } from '../../kit/tabs';
import { PlaygroundData, PlaygroundRun } from './playground.data';
import {
  cellText,
  decisionBadge,
  httpStatusLine,
  isEmptyResult,
  isMasked,
  maskedFacts,
  requestText,
  responseText,
  resultTable,
  revisionNote,
  runDecision,
  sizeText,
  traceIdOf,
  urlParts,
  wireJson,
} from './playground-view';

/**
 * `<div gwPlaygroundResult [run]="last" [event]="audit">` — "What the agent receives" of one run (kit
 * playground.html `#pg-result`, `#pg-result-empty`): the meta strip (decision, duration, size, masked fields, trace
 * id), then Structured (the structuredContent, a table when it has rows and columns) · Text (`content[0].text`) ·
 * JSON-RPC (the request and the answer on the wire, in the revision the call used), and the call's audit event under
 * a disclosure with "Open in Activity". A read that came back with nothing is the kit's empty result instead.
 *
 * The decision is the audit's once the event is in the history; until then it is what the answer says.
 */
@Component({
  selector: 'div[gwPlaygroundResult]',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwTabs, GwCodeBlock, GwCopy, GwToggle, GwEventDetail],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-stack' },
  templateUrl: './playground-result.html',
})
export class GwPlaygroundResult {
  private readonly data = inject(PlaygroundData);
  protected readonly time = inject(TimeService);

  readonly run = input.required<PlaygroundRun>();
  /** The call's row in the audit (Activity), once it is there. */
  readonly event = input<ActivityEvent | null>(null);

  protected readonly call = computed(() => this.run().call);
  protected readonly table = computed(() => resultTable(this.call().structured));
  protected readonly decision = computed(() => runDecision(this.call(), this.event()));
  protected readonly badge = computed(() => decisionBadge(this.decision()));
  protected readonly duration = computed(() => duration(this.call().meta.durationMs));
  protected readonly size = computed(() => sizeText(this.call(), this.table()));
  protected readonly masked = computed(() => maskedFacts(this.call(), this.table()));
  protected readonly traceId = computed(() => traceIdOf(this.call()) ?? this.event()?.traceId ?? null);
  protected readonly empty = computed(() => isEmptyResult(this.call()));
  protected readonly structuredJson = computed(() => {
    const s = this.call().structured;
    return s === null || s === undefined ? null : wireJson(s);
  });
  protected readonly text = computed(() => this.call().text ?? '');
  protected readonly url = computed(() => urlParts(this.run().url, this.run().endpoint));
  protected readonly request = computed(() => requestText(this.call(), this.run().era, this.url(), this.run().tool));
  protected readonly response = computed(() => responseText(this.call()));
  protected readonly responseFilename = computed(() => `Response · ${httpStatusLine(this.call().wire.httpStatus)} · ${formatBytes(this.call().meta.bytes)}`);
  protected readonly revision = computed(() => revisionNote(this.run().era));
  protected readonly revisionParts = computed(() => literalParts(this.revision().text, this.revision().literals));
  /** The environment whose masking rules applied: the connection's, else the endpoint's. */
  protected readonly policyEnv = computed(() => this.run().env ?? this.data.summary()?.env ?? 'dev');
  /** Structured first (the kit's), Text when the answer has no structuredContent. */
  protected readonly firstTab = computed(() => (this.structuredJson() === null && this.text() ? 'pg-rt-text' : 'pg-rt-struct'));

  protected readonly eventOpen = signal(false);
  protected readonly detail = signal<EventDetail | null>(null);
  protected readonly detailError = signal(false);

  protected readonly cellText = cellText;
  protected readonly isMasked = isMasked;

  constructor() {
    // Another run: its event is another one, and the disclosure starts closed (the kit's state).
    effect(() => {
      this.run();
      untracked(() => {
        this.detail.set(null);
        this.detailError.set(false);
      });
    });
    effect(() => {
      if (!this.eventOpen()) return;
      const callId = this.call().callId;
      untracked(() => {
        if (!this.detail() && callId) void this.loadDetail(callId);
      });
    });
  }

  private async loadDetail(callId: string): Promise<void> {
    this.detailError.set(false);
    try {
      const d = await this.data.eventFor(callId);
      if (this.call().callId !== callId) return;
      if (d) this.detail.set(d);
      else this.detailError.set(true);
    } catch {
      if (this.call().callId === callId) this.detailError.set(true);
    }
  }

  protected retryDetail(): void {
    const callId = this.call().callId;
    if (callId) void this.loadDetail(callId);
  }
}
