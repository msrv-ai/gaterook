import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  numberAttribute,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { IssuedToken, TokenRow } from '../../../core/api/endpoints.api';
import { DEFAULT_TOKEN_TTL_DAYS, tokenTtlDays } from '../../../core/api/token-lifetime';
import { TimeService } from '../../../core/time/time.service';
import { GwCopy } from '../../copy';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwEnableWhen } from '../../enable-when';
import { GwIcon } from '../../icons';
import { Look } from '../shared/dictionaries';
import { clientName } from '../shared/client-mark';

const STATES: Record<string, Look> = {
  active: { tone: 'success', icon: 'check', text: 'Active' },
  expiring: { tone: 'warning', icon: 'hourglass', text: 'Expiring' },
  expired: { tone: 'neutral', icon: 'timer-off', text: 'Expired' },
  revoked: { tone: 'danger', icon: 'ban', text: 'Revoked' },
};

/** The kit's warning badge: a live token that stops working within this many days. */
export const EXPIRING_WITHIN_MS = 7 * 86_400_000;

/** How a token row reads: named tokens by their prefix, tokens issued on the fly by how they are issued. */
export interface TokenView {
  readonly row: TokenRow;
  readonly onTheFly: boolean;
  readonly bridge: boolean;
  /** Active, and expires within 7 days on the core's clock: the kit's "Expiring" badge and "In 3 days". */
  readonly expiring: boolean;
  readonly title: string;
  readonly note: string;
  readonly look: Look;
  /** The client that uses it ("Codex"), when the core knows. */
  readonly client: string | null;
  readonly revocable: boolean;
  /** The Activity query of its calls: `token:<name>`, or the client for the bridge. */
  readonly query: string;
}

/**
 * `now` is the core's clock (`TimeService.now()`): the core sends only active, expired and revoked, so "expiring" is
 * derived here, the same way the kit's table marks a token that stops working within 7 days.
 */
export function tokenView(row: TokenRow, slug: string, now: number): TokenView {
  // The core writes the kind in lower case; compared case-insensitively so a grant can never pass for a named token
  // (that would offer DELETE /api/tokens/{grantId} and a Revoke on the bridge).
  const onTheFly = String(row.kind).toLowerCase() === 'grant';
  const bridge = onTheFly && row.client === 'claude-desktop';
  const expiresAt = row.expiresAt ? Date.parse(row.expiresAt) : Number.NaN;
  const expiring = !onTheFly && row.state === 'active' && expiresAt - now <= EXPIRING_WITHIN_MS;
  const look = STATES[expiring ? 'expiring' : row.state] ?? { tone: 'neutral', icon: 'circle-dashed', text: row.state };
  const client = row.client ? clientName(row.client) : null;
  return {
    row,
    onTheFly,
    bridge,
    expiring,
    title: bridge ? 'Bridge (.mcpb)' : row.name,
    note: bridge
      ? 'Claude Desktop · issued on the fly'
      : onTheFly
        ? `${client ?? 'Client'} · gw-bridge headers helper, issued on the fly`
        : `${row.prefix ?? ''}…`,
    look,
    client,
    revocable: !bridge && row.state === 'active',
    query: bridge ? `endpoint:${slug} client:${row.client}` : `token:${row.name}`,
  };
}

let nextId = 0;

/**
 * `<div gwTokens slug="dev" [tokens]="rows" (issue)="…" (revoke)="…">` (elements/token-issue.html): the tokens of one
 * endpoint. Only the prefix is listed, never the token; Claude Desktop (the .mcpb bridge) and Codex (the gw-bridge
 * headers helper) get a 15-minute token on the fly, so their rows say how, and Revoke ends the AI app's grant. An expiry
 * within 7 days gets a warning badge, "Last used" opens Activity filtered by the token, Revoke asks with a confirmation
 * that names it. Expired and revoked tokens stay listed for the audit trail. No tokens: the empty state.
 */
@Component({
  selector: 'div[gwTokens]',
  imports: [GwIcon, RouterLink, GwDialog, GwDialogClose],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-token-issue' },
  templateUrl: './token-table.html',
})
export class GwTokens {
  protected readonly time = inject(TimeService);

  readonly slug = input.required<string>();
  readonly tokens = input.required<readonly TokenRow[]>();
  readonly level = input(3, { transform: numberAttribute });
  /**
   * The endpoint's own Tokens tab (pages/endpoint-detail.html): titled "Access tokens", the page's description (no
   * endpoint name, the IP allowlist line) and rows that don't repeat the client the page already shows.
   */
  readonly pageCopy = input(false, { transform: booleanAttribute });

  readonly issue = output<HTMLElement>();
  readonly revoke = output<TokenRow>();

  protected readonly uid = `tki-${++nextId}`;
  // Re-derived once a minute: enough for a 7-day threshold, and the table doesn't re-render every second.
  protected readonly views = computed(() =>
    this.tokens().map((t) => {
      const v = tokenView(t, this.slug(), this.time.minute());
      if (!this.pageCopy() || !v.onTheFly) return v;
      return { ...v, note: v.bridge ? 'issued on the fly' : 'gw-bridge headers helper, issued on the fly' };
    }),
  );
  protected readonly summary = computed(() => {
    // The bridge row is how Claude Desktop gets its tokens, not a token of its own: the kit doesn't count it.
    const rows = this.views().filter((v) => !v.bridge).map((v) => v.row);
    const count = (s: string) => rows.filter((t) => t.state === s).length;
    const parts = [`${count('active')} active`];
    if (count('expired')) parts.push(`${count('expired')} expired`);
    if (count('revoked')) parts.push(`${count('revoked')} revoked`);
    return parts.join(', ');
  });
  protected readonly confirming = signal<TokenView | null>(null);
  private readonly confirm = viewChild<GwDialog>('confirm');

  /** "In 3 days" next to an expiry within 7 days. */
  protected soon(v: TokenView): string | null {
    if (!v.expiring || !v.row.expiresAt) return null;
    return this.time.relative(v.row.expiresAt).replace(/^in /, 'In ');
  }

  protected askRevoke(v: TokenView, opener: HTMLElement): void {
    this.confirming.set(v);
    // The dialog's content depends on the row: render it, then open.
    this.confirm()?.openAfterRender(opener);
  }

  protected onConfirmClosed(value: string): void {
    const v = this.confirming();
    if (value === 'revoke' && v) this.revoke.emit(v.row);
  }
}

/** One step of the issue dialog: the form, or the result with the only copy of the token. */
export type IssueStep = 'form' | 'result';

/**
 * `<gw-token-issue-dialog slug="dev" [issued]="token" [error]="nameError" (issue)="…">` (elements/token-issue.html,
 * Issue token): name, lifetime (184 days by default) and the fixed endpoint; then the result step with the only copy of
 * the token (initial focus: Copy). The result step has no close button and Done needs "I've stored this token" (the
 * dialog is guarded: Esc and the backdrop don't close it). Desktop tokens have no IP allowlist.
 */
@Component({
  selector: 'gw-token-issue-dialog',
  imports: [GwIcon, GwDialog, GwDialogClose, GwCopy, GwEnableWhen],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './token-issue-dialog.html',
})
export class GwTokenIssueDialog {
  private readonly time = inject(TimeService);

  readonly slug = input.required<string>();
  readonly address = input('127.0.0.1:7465');
  /**
   * The core's answer: switches the dialog to the result step. The dialog keeps its own copy and drops it when it
   * closes or opens again, so "shown once" never depends on the page clearing this input.
   */
  readonly issued = input<IssuedToken | null>(null);
  /** The name's error from the core (409: "Endpoint dev already has a token named …"). */
  readonly error = input<string | null>(null);
  /** A suggested name ("cursor-laptop"). */
  readonly suggestedName = input('');
  readonly defaultTtlDays = input(DEFAULT_TOKEN_TTL_DAYS, { transform: tokenTtlDays });
  readonly busy = input(false, { transform: booleanAttribute });

  readonly issue = output<{ readonly name: string; readonly ttlDays: number }>();
  readonly done = output<void>();

  protected readonly uid = `tki-dlg-${++nextId}`;
  private readonly dialog = viewChild.required<GwDialog>('dlg');
  protected readonly name = signal('');
  protected readonly lifetime = signal(`${DEFAULT_TOKEN_TTL_DAYS}d`);
  protected readonly customDate = signal('');
  /** The only copy of the token this dialog renders; cleared when the dialog closes or opens again. */
  protected readonly shown = signal<IssuedToken | null>(null);
  protected readonly step = computed<IssueStep>(() => (this.shown() ? 'result' : 'form'));

  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  constructor() {
    // Only a new answer of the core shows a token: the same object left in the input after Done never comes back.
    effect(() => {
      const t = this.issued();
      untracked(() => {
        this.shown.set(t);
        // The form step's focused field is gone: the result step's initial focus is Copy (its `autofocus`).
        if (t && this.dialog().isOpen()) {
          afterNextRender(() => this.host.querySelector<HTMLElement>('[data-step="result"] [autofocus]')?.focus(), {
            injector: this.injector,
          });
        }
      });
    });
  }
  protected readonly url = computed(() => `http://${this.address()}/mcp/${this.slug()}`);
  protected readonly ttlDays = computed(() => {
    const l = this.lifetime();
    if (l !== 'custom') return Number.parseInt(l, 10);
    const t = Date.parse(this.customDate());
    return Number.isNaN(t) ? 0 : Math.max(1, Math.ceil((t - this.time.now()) / 86_400_000));
  });
  protected readonly expiresOn = computed(() => this.time.now() + this.ttlDays() * 86_400_000);

  open(opener?: HTMLElement | null): void {
    this.shown.set(null);
    this.name.set(this.suggestedName());
    this.lifetime.set(`${this.defaultTtlDays()}d`);
    this.dialog().open(opener);
  }

  protected dateIso(t: number): string {
    return this.time.iso(new Date(t).toISOString()).slice(0, 10);
  }

  protected dateText(t: number): string {
    return this.time.date(new Date(t).toISOString(), true);
  }

  protected relative(at: string): string {
    return this.time.relative(at).replace(/^in /, 'In ');
  }

  protected date(at: string): string {
    return this.time.date(at, true);
  }

  protected absolute(at: string): string {
    return this.time.absolute(at, false);
  }

  protected iso(at: string): string {
    return this.time.iso(at);
  }

  protected submit(e: Event): void {
    e.preventDefault();
    if (this.step() === 'form') {
      if (this.name().trim() && this.ttlDays() > 0) this.issue.emit({ name: this.name().trim(), ttlDays: this.ttlDays() });
      return;
    }
    this.dialog().close('done');
  }

  protected onClosed(value: string): void {
    const shown = this.step() === 'result';
    // The token leaves the DOM with the dialog: nothing of it stays in a closed <dialog>.
    this.shown.set(null);
    if (value === 'done' || shown) this.done.emit();
  }
}
