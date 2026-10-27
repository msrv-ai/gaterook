import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActionSegment, ApprovalDecision, ApprovalDetail, ApprovalItem, ApprovalStatus, ApprovalsApi } from '../../core/api/approvals.api';
import { TimeService } from '../../core/time/time.service';
import { URGENT_SECONDS } from '../../kit/countdown';
import { GwApprovalRequest } from '../../kit/elements/approval-request/approval-request';
import { GwApprovalRequestCompact } from '../../kit/elements/approval-request/approval-request-compact';
import { ToastService } from '../../kit/toast.service';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_APPROVALS, DEMO_WAITING } from '../fixtures-approvals';
import { GwLiveNote, demoFixture, liveRead } from '../live';

type RequestKey = keyof typeof DEMO_APPROVALS;

interface DemoSection {
  readonly id: string;
  readonly title: string;
  readonly description: string;
  readonly request: RequestKey;
}

interface LiveMatch {
  readonly tool: string;
  readonly status: ApprovalStatus;
  readonly env?: string;
  /** Only while it is in its last minute (the section shows the urgent state: danger bar and timer). */
  readonly urgent?: boolean;
}

/** Which request of the demo world shows a section's state, when the world has one now (tool and status). */
const LIVE_MATCH: Partial<Record<RequestKey, LiveMatch>> = {
  sql: { tool: 'sql_execute', status: 'pending' },
  k8s: { tool: 'k8s_rollout_restart', status: 'pending', urgent: true },
  http: { tool: 'http_request', status: 'pending' },
  prod: { tool: 'ssh_run', status: 'pending', env: 'prod' },
  approved: { tool: 'sql_execute', status: 'running' },
  rejected: { tool: 'k8s_apply', status: 'rejected' },
  expired: { tool: 'k8s_rollout_restart', status: 'expired' },
  executed: { tool: 'sql_execute', status: 'completed' },
  failed: { tool: 'sql_execute', status: 'failed' },
};

interface Loaded {
  /** The section's request: the dev core's when the world has that state now, else the fixture. */
  readonly requests: Record<RequestKey, ApprovalDetail>;
  readonly fromCore: ReadonlySet<RequestKey>;
  readonly waiting: readonly { readonly item: ApprovalItem; readonly segments: readonly ActionSegment[] | null; readonly preview: string | null }[];
}

/**
 * Dev only: design/elements/approval-request.html, every state of the kit's demo. A section shows the dev core's
 * request (`GET /api/approvals?status=…`, `GET /api/approvals/{id}`) when the GW_DEMO world has one in that state
 * now; the world is one moment (its requests expire 5 to 10 minutes after the core starts), so the others, and the
 * states it never has (prod runbook, hidden characters, running, executed, failed), are fixtures on the same clock.
 * The compact list is the core's pending queue, or the fixtures when nothing waits.
 */
@Component({
  selector: 'gw-approval-request-demo',
  imports: [GwDemoHeader, GwApprovalRequest, GwApprovalRequestCompact, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    @if (live.value(); as l) {
    @for (s of sections; track s.id) {
      <section class="gw-section" [attr.aria-labelledby]="'s-' + s.id" [attr.data-demo-source]="l.fromCore.has(s.request) ? 'core' : 'fixture'">
        <header class="gw-section__header">
          <h2 class="gw-section__title" [id]="'s-' + s.id">{{ s.title }}</h2>
          <p class="gw-section__description">{{ s.description }}</p>
        </header>
        <article gwApprovalRequest [request]="l.requests[s.request]" [level]="3" [eventLink]="s.request === 'executed' || s.request === 'failed' ? '/activity' : null" [busy]="busy()[s.request] ?? null" (decide)="decided(s.request, $event)"></article>
      </section>
      @if (s.id === 'hidden') {
        <section class="gw-section" aria-labelledby="s-partial">
          <header class="gw-section__header">
            <h2 class="gw-section__title" id="s-partial">Server mode preview: approved by 1 of 2</h2>
            <p class="gw-section__description">Server mode only. On the desktop there is one local user and every decision reads “by you” (decision 1), so the app has no partial state: a request waits for you, and your decision is the only one.</p>
          </header>
        </section>
      }
    }

    <section class="gw-section" aria-labelledby="s-compact">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-compact">Compact, in a list</h2>
        <p class="gw-section__description">Overview "Waiting for you" and the queue: sorted by time to auto-deny. There is no Approve here; Review opens the full request.</p>
      </header>
      <ul class="gw-stack gw-stack--sm" aria-label="Waiting for approval, 3 requests">
        @for (w of l.waiting; track w.item.id) {
          <li><article gwApprovalRequestCompact [item]="w.item" [segments]="w.segments" [preview]="w.preview" [review]="[]" [reviewFragment]="'s-' + w.item.id"></article></li>
        }
      </ul>
    </section>
    } @else {
      <p [gwLiveNote]="live"></p>
    }
  `,
})
export class ApprovalRequestDemo {
  private readonly toasts = inject(ToastService);
  private readonly api = inject(ApprovalsApi);
  private readonly time = inject(TimeService);
  protected readonly el = kitElement('approval-request');
  private readonly fixtures = demoFixture(DEMO_APPROVALS);
  private readonly waitingFixtures = demoFixture(DEMO_WAITING);
  protected readonly live = liveRead(() => this.load());
  protected readonly busy = signal<Partial<Record<string, 'approve' | 'deny'>>>({});

  protected readonly sections: readonly DemoSection[] = [
    { id: 'sql', request: 'sql', title: 'Pending: SQL statement on dev', description: 'The default state. The statement is shown exactly as it will run; auto-deny counts down from 5 min.' },
    { id: 'k8s', request: 'k8s', title: 'Pending, under a minute left: command without a diff', description: '0:48 left of 5 min. Under 60 s the bar and timer turn to danger. At 0 the request expires in place: the badge reads Expired, the timer says it was denied automatically, and Deny and Approve and run stay focusable but unavailable, with the reason next to them. A restart has nothing to diff, so the action is the command.' },
    { id: 'http', request: 'http', title: 'Pending: HTTP request with a JSON body on stage', description: 'Method, URL, headers and body exactly as sent. The credential the gateway injects stays masked. The stage policy gives 10 min before auto-deny.' },
    { id: 'prod', request: 'prod', title: 'Pending on production: a runbook', description: 'A prod connection is reached only through the prod endpoint (incident-prod), and on prod-vm agents run runbooks only. A danger alert opens the body, the prod marker is red, and the prod policy gives 10 min before auto-deny. Comment required: Approve and run stays aria-disabled, described by “Add a comment to approve on prod”, until the comment has text. Deny never needs a comment.' },
    { id: 'hidden', request: 'hidden', title: 'Pending: the action contains hidden characters', description: "Verbatim is not enough when a character can't be seen. Bidi overrides, zero-width characters, tabs and trailing spaces render as boxed escapes; a look-alike non-ASCII letter outside a string is boxed as itself. A warning above the block counts and names them. Long lines wrap, and a wrapped line hangs under its first line." },
    { id: 'approved', request: 'approved', title: 'Approved, running', description: 'The decision bar is replaced by the history; the current step is the run itself. On the desktop the decision is yours: “Approved by you”.' },
    { id: 'rejected', request: 'rejected', title: 'Rejected: manifest diff', description: 'A human Deny ends as Rejected. The exact action of k8s_apply is a diff; the comment went back to the agent.' },
    { id: 'expired', request: 'expired', title: 'Expired', description: 'No one decided in time, so the call was denied. Neutral and read-only: no comment, no buttons.' },
    { id: 'executed', request: 'executed', title: 'Executed', description: 'The result summary sits above the history, with a link to the audit event.' },
    { id: 'failed', request: 'failed', title: 'Failed after approval', description: 'The danger alert says what happened and what to do; the raw output is one click away.' },
  ];

  private async load(): Promise<Loaded> {
    const [pending, history] = await Promise.all([this.api.list('pending'), this.api.list('history', null, 100)]);
    const items = [...pending.items, ...history.items];
    const requests = { ...this.fixtures };
    const fromCore = new Set<RequestKey>();
    await Promise.all(
      (Object.entries(LIVE_MATCH) as [RequestKey, LiveMatch][]).map(async ([key, m]) => {
        const hit = items.find(
          (i) =>
            i.tool === m.tool &&
            i.status === m.status &&
            (!m.env || i.env === m.env) &&
            (!m.urgent || this.time.secondsUntil(i.expiresAt) <= URGENT_SECONDS),
        );
        if (!hit) return;
        requests[key] = await this.api.get(hit.id);
        fromCore.add(key);
      }),
    );
    const queue = await Promise.all(pending.items.slice(0, 3).map((i) => this.api.get(i.id)));
    const waiting = queue.length
      ? queue.map((d) => ({ item: pending.items.find((i) => i.id === d.id)!, segments: d.action?.segments ?? null, preview: null }))
      : this.waitingFixtures.map((w) => ({ item: w.item, segments: null, preview: w.preview }));
    return { requests, fromCore, waiting };
  }

  /** The gallery decides nothing: a decision shows its busy state for a moment and a toast. */
  protected decided(key: string, d: ApprovalDecision): void {
    this.busy.update((b) => ({ ...b, [key]: d.decision }));
    setTimeout(() => {
      this.busy.update((b) => ({ ...b, [key]: undefined }));
      this.toasts.show({ tone: 'info', title: 'Demo', body: `${d.decision === 'approve' ? 'Approve' : 'Deny'} would be sent to the core${d.comment ? ` with “${d.comment}”` : ''}.` });
    }, 800);
  }
}
