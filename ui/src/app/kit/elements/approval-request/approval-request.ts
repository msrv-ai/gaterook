import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  numberAttribute,
  output,
  untracked,
  viewChild,
} from '@angular/core';
import { RouterLink, UrlTree } from '@angular/router';
import { ApprovalDecision, ApprovalDetail } from '../../../core/api/approvals.api';
import { Env } from '../../../core/api/types';
import { duration } from '../../../core/time/time-format';
import { TimeService } from '../../../core/time/time.service';
import { GwCodeBlock } from '../../../code/code-block';
import { GwDiff } from '../../../code/diff';
import { ActivationGuard } from '../../activation-guard';
import { Announcer } from '../../announcer';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { GwClientMark } from '../shared/client-mark';
import { TextPart, literalParts, literalWords } from '../shared/literals';
import { GwApprovalCountdown } from './approval-countdown';
import {
  RequestState,
  actionLabel,
  actionNoun,
  affected,
  diffSummary,
  executedMs,
  hiddenWarning,
  historySteps,
  requestState,
  statusBadge,
  tokenText,
  who,
  windowMinutes,
  windowSeconds,
} from './approval-view';

let nextId = 0;

/** Requests whose expiry was announced: the queue row and the detail of one request announce it once. */
const expiryAnnounced = new Set<string>();

/** A fact of the Impact list with its literals in code. */
interface Fact {
  readonly label: string;
  readonly value: TextPart[];
}

/**
 * `<article gwApprovalRequest [request]="detail" [level]="2" (decide)="…">` (elements/approval-request.html): THE
 * decision UI. One mutation an agent asked for, shown verbatim (`gw-code-block` / `gw-diff` from the server's
 * segments, always wrapped, hidden characters boxed and counted), why it needs a person (the policy rule, linked to
 * Policies), its impact, and last the agent's note, marked unverified. Pending: a comment and Deny · Approve and run
 * (the only green button, last; on prod `aria-disabled` until the required comment has text; Enter in the comment
 * never submits). At zero the request expires in place: badge, caption and inert buttons, announced once. Decided
 * requests show their outcome and history instead of the decision.
 *
 * The element decides nothing itself: `decide` carries `{decision, comment, digest}` for
 * `ApprovalsApi.decide`, and the page passes `busy` / `error` back while it runs.
 *
 * Self-approval through the GUI (DECISIONS 6, §9): Deny and Approve ignore activation for 700 ms after the request
 * appears (first render, another request in the same element), after the window gets focus and after a deep link
 * (`ActivationGuard`).
 */
@Component({
  selector: 'article[gwApprovalRequest]',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwClientMark, GwCodeBlock, GwDiff, GwToggle, GwApprovalCountdown],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-card gw-approval-request',
    '[attr.data-state]': 'state()',
    '[attr.aria-labelledby]': 'uid + "-title"',
  },
  templateUrl: './approval-request.html',
})
export class GwApprovalRequest {
  private readonly time = inject(TimeService);
  private readonly announcer = inject(Announcer);
  /** The 700 ms activation delay of the decision buttons. */
  private readonly arm = inject(ActivationGuard).arm();

  readonly request = input.required<ApprovalDetail>();
  /** The title's heading level (2 in the Approvals pane and the Activity drawer); sections are one below. */
  readonly level = input(2, { transform: numberAttribute });
  /** The endpoint's environment, when the page knows it: a prod connection on a non-prod endpoint is called out. */
  readonly endpointEnv = input<Env | null>(null);
  /**
   * Where "View event" leads (the call in Activity), when the page knows its event: a path, or a `UrlTree` for the
   * event's drawer (`/activity#event-<id>`: a fragment can't be written into a `routerLink` string).
   */
  readonly eventLink = input<string | UrlTree | null>(null);
  /** The decision being sent: its button shows the spinner, both are unavailable meanwhile. */
  readonly busy = input<'approve' | 'deny' | null>(null);
  /** Why the last decision didn't go through (409: decided elsewhere, 422: comment or digest). */
  readonly error = input<string | null>(null);
  /**
   * The W3C trace id of the request's call, when the page knows it (its Activity event): the meta row "Trace" links
   * the task in Activity › Traces by its first six hex digits, as the kit writes it (`trace:c7e19d`).
   */
  readonly traceId = input<string | null>(null);

  readonly decide = output<ApprovalDecision>();
  /** The countdown reached zero while pending: the list moves the request to History. */
  readonly expired = output<void>();

  protected readonly uid = `ar-${++nextId}`;
  /**
   * Everything typed or seen for one request belongs to that request: when the element shows another one (the
   * Approvals split view), its comment starts empty — a prod approval must not inherit the comment that unlocks it,
   * nor send it to another agent and into the audit — and it is pending until its own countdown ends.
   */
  protected readonly comment = linkedSignal({ source: () => this.request().id, computation: () => '' });
  /** Expired in front of the person, before the core's own answer arrives. */
  private readonly expiredHere = linkedSignal({ source: () => this.request().id, computation: () => false });
  private readonly titleEl = viewChild<ElementRef<HTMLElement>>('title');

  protected readonly r = computed(() => this.request());
  protected readonly state = computed<RequestState>(() => {
    const s = requestState(this.r().status);
    return s === 'pending' && this.expiredHere() ? 'expired' : s;
  });
  protected readonly pending = computed(() => this.r().status === 'pending');
  protected readonly badge = computed(() => statusBadge(this.state()));
  protected readonly clientName = computed(() => who(this.r().client));
  protected readonly token = computed(() => tokenText(this.r().client, this.r().tokenLabel));
  protected readonly target = computed(() => this.r().connection ?? this.r().endpoint);
  /** "c7e19d": the short trace id Activity's `trace:` filter accepts as a prefix. */
  protected readonly traceShort = computed(() => this.traceId()?.slice(0, 6).toLowerCase() || null);
  protected readonly what = computed(() => `${this.r().tool} on ${this.target()}`);
  protected readonly prod = computed(() => this.r().env === 'prod');
  protected readonly envMismatch = computed(() => {
    const ep = this.endpointEnv();
    return !!ep && ep !== 'prod' && this.prod();
  });
  protected readonly windowSeconds = computed(() => windowSeconds(this.r()));
  protected readonly windowMinutes = computed(() => windowMinutes(this.r()));

  protected readonly action = computed(() => this.r().action);
  protected readonly isDiff = computed(() => !!this.action()?.diff);
  protected readonly lang = computed(() => actionLabel(this.action()?.kind ?? null, this.action()?.language ?? null));
  protected readonly noun = computed(() => actionNoun(this.action()?.kind ?? null));
  /** "sql_execute · dev-db · db.dev.internal:5432/app" (the target already names the connection). */
  protected readonly filename = computed(() => {
    const r = this.r();
    const target = r.target ?? r.connection ?? r.endpoint;
    return `${r.tool} · ${target}`;
  });
  protected readonly hidden = computed(() => hiddenWarning(this.action()?.segments ?? []));
  protected readonly diffSummary = computed(() => {
    const d = this.action()?.diff;
    return d ? diffSummary(d) : null;
  });
  protected readonly regionLabel = computed(() => {
    const hidden = this.hidden() ? ', with hidden characters shown' : '';
    const verb = this.state() === 'pending' ? 'to approve' : this.state() === 'expired' ? 'that expired' : '';
    return `${this.noun()} ${verb} on ${this.target()}${hidden}`.replace(/\s+/g, ' ');
  });

  /** "sql_execute on dev-db: Every change waits…" — the tool and connection are literals. */
  protected readonly why = computed<TextPart[]>(() => {
    const r = this.r();
    const text = r.rule?.text ?? 'This call waits for a person.';
    const lead = text.startsWith(r.tool) ? '' : `${r.tool} on ${this.target()}: `;
    return literalParts(lead + text, [r.tool, r.connection]);
  });
  protected readonly ruleLink = computed(() => {
    const id = this.r().rule?.id ?? '';
    const [env, section] = id.split('.');
    const known = env === 'dev' || env === 'stage' || env === 'prod';
    return known ? { route: `/policies/${env}`, fragment: section ? `${env}-${section}` : undefined } : null;
  });
  protected readonly facts = computed<Fact[]>(() =>
    [...this.r().fields, ...this.r().impact].map((f) => ({ label: f.label, value: literalWords(f.text) })),
  );

  protected readonly commentRequired = computed(() => this.r().commentPolicy === 'required');
  protected readonly commentRecommended = computed(() => this.r().commentPolicy === 'recommended');
  protected readonly approveBlocked = computed(() => this.commentRequired() && this.comment().trim() === '');
  protected readonly locked = computed(() => this.state() !== 'pending' || this.busy() !== null);
  protected readonly commentHint = computed(() => {
    const to = `Sent to ${this.clientName()} with your decision and kept in the audit log.`;
    if (this.commentRequired()) return `Required on ${this.r().env} to approve: say why this has to run now. ${to}`;
    if (this.commentRecommended()) return `Recommended on ${this.r().env}: say why it should run. ${to}`;
    return to;
  });
  protected readonly caption = computed(() => {
    if (this.state() === 'expired') return 'Expired: denied automatically. Nothing ran, and the agent was told the request expired.';
    if (this.hidden()) return 'Runs once, exactly as shown above, hidden characters included.';
    if (this.prod()) return 'Runs once on production, exactly as shown above.';
    return `Runs once, exactly as shown above. ${this.clientName()} gets the result in the same call.`;
  });

  protected readonly history = computed(() => historySteps(this.r()));
  protected readonly ranFor = computed(() => {
    const ms = executedMs(this.r());
    return ms === null ? null : duration(ms);
  });
  protected readonly affected = computed(() => affected(this.r().result?.structured));
  protected readonly output = computed(() => this.r().result?.text || this.r().error || '');

  protected relative(at: string): string {
    return this.time.relative(at);
  }

  protected absolute(at: string): string {
    return this.time.absolute(at);
  }

  protected iso(at: string): string {
    return this.time.iso(at);
  }

  protected clock(at: string): string {
    return this.time.clock(at, true);
  }

  protected since(at: string): string {
    return `Since ${this.time.clock(at)}`;
  }

  constructor() {
    // The delay starts when the decision is on screen, and again whenever the element shows another request.
    afterNextRender(() => this.arm.reset());
    effect(() => {
      this.r().id;
      untracked(() => this.arm.reset());
    });
  }

  /** Opening a request puts focus on its title (never on a decision button). */
  focusTitle(): void {
    this.titleEl()?.nativeElement.focus();
  }

  protected onComment(e: Event): void {
    this.comment.set((e.target as HTMLTextAreaElement).value);
  }

  protected send(decision: 'approve' | 'deny'): void {
    // The aria-disabled guard already swallowed clicks on an unavailable button; this is the second lock.
    if (this.locked() || (decision === 'approve' && this.approveBlocked())) return;
    // Too soon after the request appeared, the window got focus or a deep link: not a person's decision yet.
    if (!this.arm.ready()) return;
    const comment = this.comment().trim() || null;
    this.decide.emit(decision === 'approve' ? { decision, comment, digest: this.r().digest } : { decision, comment });
  }

  /** The window closed while the person was looking: the gateway denied it, so the decision goes inert. */
  protected onEnded(): void {
    if (!this.pending()) return;
    this.expiredHere.set(true);
    const r = this.r();
    const key = r.id;
    if (!expiryAnnounced.has(key)) {
      expiryAnnounced.add(key);
      this.announcer.announce(`${this.what()} expired, denied automatically`);
    }
    this.expired.emit();
  }
}
