import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { IssuedToken, TokensApi } from '../../core/api/endpoints.api';
import { GwIcon } from '../../kit/icons';
import { GwTokenIssueDialog, GwTokens } from '../../kit/elements/token-issue/token-issue';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import { DEMO_ISSUED } from '../fixtures';
import { GwLiveNote, demoFixture, liveRead } from '../live';

/**
 * Dev only: design/elements/token-issue.html (the server-mode IP allowlist preview is left out: desktop only). The
 * tables are the dev core's (`GET /api/endpoints/dev/tokens`, and `incident-prod`, which has none); the issued token
 * is a fixture: the core shows a token once, to the request that issued it, and the gallery never issues one.
 */
@Component({
  selector: 'gw-token-issue-demo',
  imports: [GwDemoHeader, GwTokens, GwTokenIssueDialog, GwIcon, GwLiveNote],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="tki-table">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tki-table">Tokens table</h2>
        <p class="gw-section__description">Only the prefix is listed, never the token. Claude Desktop (the .mcpb bridge) and Codex (the gw-bridge headers helper) never hold a stored token: they get a short-lived one on the fly, so their rows show how, not a prefix, and Revoke ends the client's grant. An expiry under 7 days gets a warning badge, “Last used” opens Activity filtered by the token, and Revoke asks for confirmation that names it. Expired and revoked tokens stay listed for the audit trail.</p>
      </header>
      @if (devTokens.value(); as tokens) {
        <div gwTokens slug="dev" [tokens]="tokens" (issue)="form.open($event)"></div>
      } @else {
        <p [gwLiveNote]="devTokens"></p>
      }
    </section>

    <section class="gw-section" aria-labelledby="tki-dialog">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tki-dialog">Issue token dialog</h2>
        <p class="gw-section__description">For clients that need a pasted bearer token (MCP Inspector, Cursor and Antigravity through their config file, scripts); Claude Desktop and Codex never need one. Name, lifetime (184 days by default) and the fixed endpoint, then the result step with the only copy of the token; its initial focus is Copy. Desktop tokens have no IP allowlist (server mode only). One dialog swaps its body (<code class="gw-code">data-step</code> goes from form to result). The result step has no close button: Done needs “I've stored this token”. Use the buttons to open each state.</p>
      </header>
      <div class="gw-token-issue">
        <div class="gw-toolbar">
          <div class="gw-toolbar__start">
            <button class="gw-btn" type="button" aria-haspopup="dialog" (click)="form.open($any($event.currentTarget))"><svg lucideIcon="plus" class="gw-icon"></svg>Issue form</button>
            <button class="gw-btn" type="button" aria-haspopup="dialog" (click)="withError.open($any($event.currentTarget))"><svg lucideIcon="circle-alert" class="gw-icon"></svg>Issue form with errors</button>
            <button class="gw-btn" type="button" aria-haspopup="dialog" (click)="showResult(result, $any($event.currentTarget))"><svg lucideIcon="key-round" class="gw-icon"></svg>Result step</button>
          </div>
        </div>
        <gw-token-issue-dialog #form slug="dev" suggestedName="cursor-laptop" />
        <gw-token-issue-dialog #withError slug="dev" suggestedName="inspector-debug" error="Endpoint dev already has a token named inspector-debug. Pick another name, or revoke the old token first." />
        <gw-token-issue-dialog #result slug="dev" [issued]="issued()" />
      </div>
    </section>

    <section class="gw-section" aria-labelledby="tki-empty">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="tki-empty">No tokens yet</h2>
        <p class="gw-section__description">An endpoint without tokens: the empty state says what a token is for and one action issues the first, in the same form with this endpoint filled in.</p>
      </header>
      @if (prodTokens.value(); as tokens) {
        <div gwTokens slug="incident-prod" [tokens]="tokens" (issue)="empty.open($event)"></div>
      } @else {
        <p [gwLiveNote]="prodTokens"></p>
      }
      <gw-token-issue-dialog #empty slug="incident-prod" />
    </section>
  `,
})
export class TokenIssueDemo {
  private readonly api = inject(TokensApi);
  protected readonly el = kitElement('token-issue');
  protected readonly devTokens = liveRead(() => this.api.list('dev'));
  protected readonly prodTokens = liveRead(() => this.api.list('incident-prod'));
  private readonly issuedFixture = demoFixture(DEMO_ISSUED);
  protected readonly issued = signal<IssuedToken | null>(null);

  /** As a page does it: the dialog opens on the form, then the core's answer arrives (a new object each time). */
  protected showResult(dialog: GwTokenIssueDialog, opener: HTMLElement): void {
    dialog.open(opener);
    this.issued.set({ ...this.issuedFixture });
  }
}
