import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ManualSetup } from '../../core/api/clients.api';
import { TokenRow } from '../../core/api/endpoints.api';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { GwCopy } from '../../kit/copy';
import { clientName } from '../../kit/elements/shared/client-mark';
import { GwIcon } from '../../kit/icons';
import { GwTabs } from '../../kit/tabs';
import { expiresIn, otherPaths, snippetLang, snippetTabs } from './clients-view';

/**
 * `<div gwManualSetup [manual]="m" [tokens]="t">` (pages/clients.html, "Manual setup"): for MCP Inspector, scripts
 * and any app not listed above — the endpoint URL with Copy, where tokens come from (Endpoints › Tokens: shown once,
 * pasted where a snippet says ‹token›), the core's snippets by kind (MCP Inspector commands, an `mcpServers` JSON
 * file, TOML) and the tokens already set up by hand on that endpoint. Snippets never hold a token: Inspector reads it
 * from its config file, never from its command line (ARCHITECTURE.md §7.2).
 */
@Component({
  selector: 'div[gwManualSetup]',
  imports: [RouterLink, GwIcon, GwCopy, GwTabs, GwCodeBlock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card' },
  template: `
    <div class="gw-card__body gw-stack">
      <div class="gw-grid gw-grid--2">
        <div class="gw-field">
          <label class="gw-field__label" for="cl-man-url">Endpoint URL</label>
          <div class="gw-copy-field">
            <input class="gw-copy-field__value" id="cl-man-url" type="text" readonly [value]="manual().url" aria-describedby="cl-man-url-hint">
            <button class="gw-btn gw-btn--sm gw-copy-field__button" type="button" gwCopyFrom="#cl-man-url">
              <span class="gw-copy-field__copy"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy<span class="gw-sr-only"> endpoint URL</span></span>
              <span class="gw-copy-field__copied"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Copied</span>
            </button>
          </div>
          <p class="gw-field__hint" id="cl-man-url-hint">Endpoint <span class="gw-mono">{{ manual().endpoint }}</span>, {{ transport() }}.@if (others().length) { The others: @for (o of others(); track o; let last = $last) {<span class="gw-mono">{{ o }}</span>@if (!last) {, }}.}</p>
        </div>
        <div class="gw-alert gw-alert--info">
          <svg lucideIcon="key-round" class="gw-icon gw-alert__icon"></svg>
          <div class="gw-alert__content">
            <p class="gw-alert__title">Tokens come from Endpoints › Tokens</p>
            <p class="gw-alert__body">Create one token per app. It is shown once: paste it where a snippet says <code class="gw-code gw-code--secret">‹token›</code>. Revoke it there any time.</p>
            <div class="gw-alert__actions"><a class="gw-btn gw-btn--sm" [routerLink]="['/endpoints', manual().endpoint]" [queryParams]="{ tab: 'tokens' }">Create a token on {{ manual().endpoint }}</a></div>
          </div>
        </div>
      </div>

      @if (tabs().length) {
        <div class="gw-tabs gw-tabs--contained">
          <div class="gw-tabs__list" role="tablist" aria-label="Setup snippet" gwTabs>
            @for (t of tabs(); track t.id; let first = $first) {
              <button class="gw-tabs__tab" type="button" role="tab" [id]="'cl-snip-t-' + t.id" [attr.aria-selected]="first ? 'true' : 'false'" [attr.aria-controls]="'cl-snip-' + t.id" [attr.tabindex]="first ? null : '-1'"><svg [lucideIcon]="t.icon" class="gw-icon"></svg> {{ t.label }}</button>
            }
          </div>
          @for (t of tabs(); track t.id; let first = $first) {
            <div class="gw-tabs__panel gw-stack gw-stack--sm" role="tabpanel" [id]="'cl-snip-' + t.id" [attr.aria-labelledby]="'cl-snip-t-' + t.id" [attr.hidden]="first ? null : ''">
              @for (s of t.snippets; track s.id) {
                <gw-code-block [text]="s.text" [language]="s.language" [lang]="lang(s.language)" [filename]="s.title" [copyLabel]="s.title" [regionLabel]="s.title" [wrap]="t.id === 'inspector'" [id]="'cl-snip-pre-' + s.id" />
              }
              <p class="gw-text-caption gw-fg-muted">@for (p of t.caption; track $index) {@if (p.code) {<span class="gw-mono">{{ p.text }}</span>} @else {{{ p.text }}}}</p>
            </div>
          }
        </div>
      }
    </div>
    @if (hand().length) {
      @let first = hand()[0];
      <footer class="gw-card__footer gw-card__footer--split">
        <p class="gw-text-small gw-fg-secondary">Set up by hand: {{ first.client ? clientName(first.client) + ' with token' : 'token' }} <code class="gw-code">{{ first.name }}</code> @if (first.prefix) {<span class="gw-mono">{{ first.prefix }}…</span>}, {{ expires(first.expiresAt) }}@if (hand().length > 1) {, and {{ hand().length - 1 }} more on the <a class="gw-link" [routerLink]="['/endpoints', manual().endpoint]" [queryParams]="{ tab: 'tokens' }">Tokens tab</a>}</p>
        <a class="gw-btn gw-btn--link gw-btn--sm" routerLink="/activity" [queryParams]="{ q: 'token:' + first.name }">View activity<span class="gw-sr-only"> of token {{ first.name }}</span></a>
      </footer>
    }
  `,
})
export class GwManualSetup {
  private readonly time = inject(TimeService);

  readonly manual = input.required<ManualSetup>();
  /** Named tokens set up by hand on the endpoint (`handTokens`). */
  readonly hand = input<readonly TokenRow[]>([]);

  protected readonly tabs = computed(() => snippetTabs(this.manual()));
  protected readonly others = computed(() => otherPaths(this.manual()));
  protected readonly transport = computed(() => (this.manual().transport === 'streamable-http' ? 'Streamable HTTP' : this.manual().transport));
  protected readonly lang = snippetLang;
  protected readonly clientName = clientName;
  protected readonly expires = (at: string | null) => expiresIn(at, this.time.now());
}
