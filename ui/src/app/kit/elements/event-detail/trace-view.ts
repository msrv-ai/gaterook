import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, numberAttribute, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TraceDetail } from '../../../core/api/activity.api';
import { elapsed } from '../../../core/time/time-format';
import { TimeService } from '../../../core/time/time.service';
import { GwCopy } from '../../copy';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { GwToast } from '../../toast-region';
import { decisionLook } from '../shared/dictionaries';
import { literalWords } from '../shared/literals';
import { decisionCounts, decisionsText, phaseLook, phaseOutlier, titleSource, traceLine, waitingTools } from './event-view';

let nextId = 0;

/**
 * `<section gwTraceView [trace]="t">` (event-detail.html, "Trace view"): one agent task, its calls grouped into
 * phases with the decisions in each. The task name comes from the agent, so it is marked unverified. Activity's
 * Traces tab shows one per trace; Open trace in an event leads here.
 *
 * `page`: the card of Activity › Traces (pages/activity.html "Agent tasks"). It says whether the task is still going
 * ("5 calls so far", Status "In progress, waiting for you" with what waits and Review in Approvals), shows the
 * decisions as badges, and folds the phases behind "Show N phases" when nobody had to be asked (the decisions
 * already sum the task up); Show N calls filters the page in place by the trace's short id, and a phase that
 * stopped a call links to that call's event.
 */
@Component({
  selector: 'section[gwTraceView]',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwCopy, GwToast, GwToggle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-event-detail__trace', '[attr.aria-labelledby]': 'uid + "-title"' },
  template: `
    <ng-template #heading>
      @if (level() === 2) {
        <h2 class="gw-text-h3" [id]="uid + '-title'">{{ title() }}</h2>
      } @else {
        <h3 class="gw-text-h3" [id]="uid + '-title'">{{ title() }}</h3>
      }
    </ng-template>

    <ng-template #phaseItems>
      @for (p of phases(); track $index) {
        <li class="gw-timeline__item">
          <span class="gw-timeline__marker gw-timeline__marker--{{ p.look.tone }}"><svg [lucideIcon]="p.look.icon" class="gw-icon gw-icon--sm"></svg></span>
          <div class="gw-timeline__content">
            <p class="gw-timeline__title">{{ p.phase.name }} · {{ p.phase.calls }} {{ p.phase.calls === 1 ? 'call' : 'calls' }}</p>
            @if (p.summary.length) {
              <p class="gw-timeline__meta">@for (s of p.summary; track $index) {@if (s.code) {<code class="gw-code">{{ s.text }}</code>} @else {{{ s.text }}}}</p>
            }
            <ul class="gw-event-detail__trace-decisions" [attr.aria-label]="'Decisions in ' + p.phase.name">
              @for (d of p.decisions; track d.decision) {
                <li><span class="gw-badge gw-badge--{{ d.look.tone }} gw-badge--sm"><svg [lucideIcon]="d.look.icon" class="gw-icon gw-badge__icon"></svg>{{ d.count }} {{ d.words }}</span></li>
              }
            </ul>
            @if (page() && p.outlier; as o) {
              <a class="gw-btn gw-btn--link gw-btn--sm" [routerLink]="[]" [fragment]="'event-' + o.id" queryParamsHandling="preserve">{{ o.text }}</a>
            }
          </div>
          <time class="gw-timeline__time" [attr.datetime]="time.iso(p.phase.from)" [title]="time.iso(p.phase.from) + ', until ' + time.clock(p.phase.to, true)">{{ time.clock(p.phase.from) }}–{{ time.clock(p.phase.to) }}</time>
        </li>
      }
    </ng-template>

    <div class="gw-event-detail__trace-header">
      <div class="gw-event-detail__trace-heading">
        <ng-container [ngTemplateOutlet]="heading" />
        <p class="gw-text-small gw-fg-muted">Trace <span class="gw-mono" [title]="t().trace.traceId">{{ line().short }}</span> · {{ line().calls }}@if (page() && !ended()) { so far} · {{ line().span }} · {{ line().who }}@if (t().trace.endpoint; as ep) { on endpoint <code class="gw-code">{{ ep }}</code>}</p>
        @if (source(); as s) {
          <p class="gw-text-caption gw-fg-muted">{{ s }}</p>
        }
      </div>
      <div class="gw-event-detail__actions">
        @if (page()) {
          <button class="gw-btn gw-btn--sm" type="button" [gwCopy]="t().trace.traceId" [gwToast]="'Trace id ' + short() + ' copied'" gwToastTone="success"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy trace id<span class="gw-sr-only"> {{ short() }}</span></button>
          <a class="gw-btn gw-btn--sm" [routerLink]="[]" [queryParams]="{ q: 'trace:' + short(), view: null }" queryParamsHandling="merge" (click)="showCalls.emit(short())"><svg lucideIcon="filter" class="gw-icon gw-icon--sm"></svg> Show {{ line().calls }}<span class="gw-sr-only"> of trace {{ short() }}</span></a>
        } @else {
          <button class="gw-btn gw-btn--sm" type="button" [gwCopy]="t().trace.traceId" gwToast="Trace id copied" gwToastTone="success"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy trace id</button>
          <a class="gw-btn gw-btn--sm" routerLink="/activity" [queryParams]="{ q: 'trace:' + t().trace.traceId }"><svg lucideIcon="filter" class="gw-icon gw-icon--sm"></svg> Show {{ line().calls }}</a>
        }
      </div>
    </div>

    @if (page()) {
      <dl class="gw-kv gw-kv--inline">
        <div class="gw-kv__row">
          <dt class="gw-kv__key">Started</dt>
          <dd class="gw-kv__value"><time [attr.datetime]="time.iso(t().trace.startedAt)" [title]="time.iso(t().trace.startedAt)">{{ time.clock(t().trace.startedAt, true) }}</time></dd>
        </div>
        @if (ended()) {
          <div class="gw-kv__row">
            <dt class="gw-kv__key">Ended</dt>
            <dd class="gw-kv__value"><time [attr.datetime]="time.iso(t().trace.endedAt)" [title]="time.iso(t().trace.endedAt)">{{ time.clock(t().trace.endedAt, true) }}</time></dd>
          </div>
        } @else {
          <div class="gw-kv__row">
            <dt class="gw-kv__key">Status</dt>
            <dd class="gw-kv__value"><span class="gw-status" [class.gw-status--warning]="waiting()" [class.gw-status--info]="!waiting()"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">{{ waiting() ? 'In progress, waiting for you' : 'In progress' }}</span></span></dd>
          </div>
        }
        <div class="gw-kv__row">
          <dt class="gw-kv__key">Decisions</dt>
          <dd class="gw-kv__value">@for (d of decisionBadges(); track d.decision; let last = $last) {<span class="gw-badge gw-badge--{{ d.look.tone }} gw-badge--sm"><svg [lucideIcon]="d.look.icon" class="gw-icon gw-badge__icon"></svg>{{ d.count }} {{ d.words }}</span>@if (!last) { }}</dd>
        </div>
        @if (waiting()) {
          <div class="gw-kv__row">
            <dt class="gw-kv__key">Waiting for people</dt>
            <dd class="gw-kv__value">@for (w of waitingFor().tools; track w; let i = $index; let last = $last) {@if (i > 0) {{{ last ? ' and ' : ', ' }}}<code class="gw-code">{{ w }}</code>}@if (waitingFor().review; as r) { · <a class="gw-link" [routerLink]="['/approvals', r.approvalId]">Review in Approvals<span class="gw-sr-only">: {{ r.tool }}@if (r.connection) { on {{ r.connection }}}</span></a>}</dd>
          </div>
        } @else if (t().trace.waitedForPeopleMs > 0) {
          <div class="gw-kv__row">
            <dt class="gw-kv__key">Waited for people</dt>
            <dd class="gw-kv__value gw-tabular">{{ waited() }}</dd>
          </div>
        } @else if (phases().length) {
          <div class="gw-kv__row">
            <dt class="gw-kv__key">Phases</dt>
            <dd class="gw-kv__value">
              <button class="gw-btn gw-btn--link gw-btn--sm" type="button" gwToggle [attr.aria-controls]="uid + '-phases'">
                <span class="gw-event-detail__when-collapsed">Show {{ phases().length }} {{ phases().length === 1 ? 'phase' : 'phases' }}<span class="gw-sr-only"> of trace {{ short() }}</span></span>
                <span class="gw-event-detail__when-expanded">Hide phases<span class="gw-sr-only"> of trace {{ short() }}</span></span>
              </button>
            </dd>
          </div>
        }
      </dl>
      @if (!waiting() && phases().length) {
        @if (t().trace.waitedForPeopleMs > 0) {
          <ol class="gw-timeline" [attr.aria-label]="'Phases of trace ' + short() + ', ' + title()"><ng-container [ngTemplateOutlet]="phaseItems" /></ol>
        } @else {
          <ol class="gw-timeline" [id]="uid + '-phases'" [attr.aria-label]="'Phases of trace ' + short() + ', ' + title()" hidden><ng-container [ngTemplateOutlet]="phaseItems" /></ol>
        }
      }
    } @else {
      <dl class="gw-kv gw-kv--inline">
        <div class="gw-kv__row">
          <dt class="gw-kv__key">Started</dt>
          <dd class="gw-kv__value"><time [attr.datetime]="time.iso(t().trace.startedAt)" [title]="time.iso(t().trace.startedAt)">{{ time.clock(t().trace.startedAt, true) }}</time></dd>
        </div>
        <div class="gw-kv__row">
          <dt class="gw-kv__key">{{ t().trace.status === 'ended' ? 'Ended' : 'Last call' }}</dt>
          <dd class="gw-kv__value"><time [attr.datetime]="time.iso(t().trace.endedAt)" [title]="time.iso(t().trace.endedAt)">{{ time.clock(t().trace.endedAt, true) }}</time></dd>
        </div>
        <div class="gw-kv__row">
          <dt class="gw-kv__key">Decisions</dt>
          <dd class="gw-kv__value gw-tabular">{{ decisions() }}</dd>
        </div>
        <div class="gw-kv__row">
          <dt class="gw-kv__key">Waited for people</dt>
          <dd class="gw-kv__value gw-tabular">{{ waited() }}</dd>
        </div>
      </dl>
      @if (phases().length) {
        <ol class="gw-timeline" [attr.aria-label]="'Phases of trace ' + short() + ', ' + title()"><ng-container [ngTemplateOutlet]="phaseItems" /></ol>
      }
    }
  `,
})
export class GwTraceView {
  protected readonly time = inject(TimeService);

  readonly trace = input.required<TraceDetail>();
  readonly level = input(3, { transform: numberAttribute });
  /** Activity › Traces' card (see above). */
  readonly page = input(false, { transform: booleanAttribute });
  /** `page`: Show N calls was clicked (the page filters by `trace:<short id>` and moves to the calls). */
  readonly showCalls = output<string>();

  protected readonly uid = `tr-${++nextId}`;
  protected readonly t = computed(() => this.trace());
  protected readonly short = computed(() => this.t().trace.traceId.slice(0, 6));
  protected readonly title = computed(() => this.t().trace.title ?? `Trace ${this.short()}`);
  protected readonly line = computed(() => traceLine(this.t()));
  protected readonly source = computed(() => (this.t().trace.title ? titleSource(this.t()) : null));
  protected readonly ended = computed(() => this.t().trace.status === 'ended');
  protected readonly waiting = computed(() => this.t().trace.status === 'waiting');
  protected readonly waitingFor = computed(() => waitingTools(this.t()));
  protected readonly decisions = computed(() => decisionsText(this.t().trace.decisions));
  protected readonly decisionBadges = computed(() => decisionCounts(this.t().trace.decisions).map(badge));
  protected readonly waited = computed(() => (this.t().trace.waitedForPeopleMs ? elapsed(this.t().trace.waitedForPeopleMs) : 'Nobody was asked'));
  protected readonly phases = computed(() =>
    this.t().phases.map((phase) => ({
      phase,
      look: phaseLook(phase),
      summary: phase.summary ? literalWords(phase.summary) : [],
      decisions: decisionCounts(phase.decisions).map(badge),
      outlier: phaseOutlier(this.t(), phase),
    })),
  );
}

/** A decision count as a badge: "12 allowed", "2 denied by policy", "1 waiting for approval" (BLUEPRINT §7 words). */
function badge(d: { readonly decision: string; readonly count: number }) {
  const look = decisionLook(d.decision);
  return { ...d, look, words: look.text.toLowerCase() };
}
