import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { TimeService } from '../../core/time/time.service';
import { GwIcon } from '../../kit/icons';
import { SecretsData } from './secrets.data';
import { connectionsQuery, idOf, shortLivedGroups, shortLivedSummary } from './secrets-view';

/**
 * Secrets › Short-lived credentials (design/pages/secrets.html `#short-lived`; ARCHITECTURE.md §6
 * `/api/secrets/short-lived`): what the gateway gets instead of a static key — GCP impersonation, AWS AssumeRole,
 * SSH certificates from Vault CA, Vault dynamic database users — one card per mechanism and identity, with the
 * connections that use it and when it was last issued. The core knows only what it issued since it started: before a
 * first call the section says so.
 */
@Component({
  selector: 'section[gwShortLived]',
  imports: [RouterLink, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-section', id: 'short-lived', 'aria-labelledby': 'sl-title' },
  template: `
    <header class="gw-section__header">
      <h2 class="gw-section__title" id="sl-title">Short-lived credentials</h2>
      <p class="gw-section__description">{{ groups().length ? summary() : 'Instead of a static key, the gateway gets a credential that expires minutes after the call.' }}</p>
    </header>
    @if (data.shortLived().value) {
      @if (groups().length) {
        <ul class="gw-grid gw-grid--2" aria-label="Short-lived credentials">
          @for (g of groups(); track g.key) {
            @let id = idOf('sl', g.title + '-' + g.identity);
            <li>
              <article class="gw-card" [attr.aria-labelledby]="id + '-t'">
                <header class="gw-card__header">
                  <div class="gw-card__heading">
                    <h3 class="gw-card__title" [id]="id + '-t'"><span class="gw-icon-tile gw-icon-tile--sm"><svg [lucideIcon]="g.icon" class="gw-icon gw-icon--sm"></svg></span> {{ g.title }}</h3>
                    <p class="gw-card__description">{{ g.description }}</p>
                  </div>
                </header>
                <div class="gw-card__body">
                  <dl class="gw-kv gw-kv--dense">
                    <div class="gw-kv__row"><dt class="gw-kv__key">{{ g.identityLabel }}</dt><dd class="gw-kv__value gw-mono">{{ g.identity }}</dd></div>
                    @if (g.externalId) {
                      <div class="gw-kv__row"><dt class="gw-kv__key">ExternalId</dt><dd class="gw-kv__value gw-mono">{{ g.externalId }}</dd></div>
                    }
                    @if (g.principal) {
                      <div class="gw-kv__row"><dt class="gw-kv__key">Principal</dt><dd class="gw-kv__value gw-mono">{{ g.principal }}</dd></div>
                    }
                    @if (g.assumedWith) {
                      <div class="gw-kv__row"><dt class="gw-kv__key">Assumed with</dt><dd class="gw-kv__value gw-mono">{{ g.assumedWith }}</dd></div>
                    }
                    <div class="gw-kv__row"><dt class="gw-kv__key">{{ g.lifetimeLabel }}</dt><dd class="gw-kv__value">{{ g.lifetime }}</dd></div>
                    <div class="gw-kv__row">
                      <dt class="gw-kv__key">Used by</dt>
                      <dd class="gw-kv__value">@for (c of g.connections; track c.name) {@if (!$first) {<ng-container>, </ng-container>}<span class="gw-mono">{{ c.name }}</span>@if (c.env) {<ng-container> </ng-container><span class="gw-sr-only">environment</span> <span class="gw-env gw-env--sm" [class]="'gw-env--' + c.env">{{ c.env }}</span>}}</dd>
                    </div>
                    <div class="gw-kv__row">
                      <dt class="gw-kv__key">Last issued</dt>
                      <dd class="gw-kv__value">@if (g.lastIssuedAt) {<time [attr.datetime]="time.iso(g.lastIssuedAt)" [attr.title]="time.absolute(g.lastIssuedAt, false)">{{ time.relative(g.lastIssuedAt) }}</time>} @else {Not yet}</dd>
                    </div>
                  </dl>
                </div>
                <footer class="gw-card__footer">
                  <a class="gw-btn gw-btn--ghost gw-btn--sm" routerLink="/activity" [queryParams]="{ q: connectionsQuery(names(g)) }">View activity<span class="gw-sr-only"> of {{ joinNames(g) }}</span></a>
                </footer>
              </article>
            </li>
          }
        </ul>
      } @else {
        <div class="gw-card">
          <div class="gw-empty gw-empty--compact">
            <span class="gw-empty__icon"><svg lucideIcon="timer" class="gw-icon"></svg></span>
            <h3 class="gw-empty__title">Nothing issued since the core started</h3>
            <p class="gw-empty__text">Connections that use impersonation, AssumeRole, SSH certificates or Vault database users show here after their first call.</p>
          </div>
        </div>
      }
    } @else if (data.shortLived().unavailable) {
      <div class="gw-card">
        <div class="gw-empty gw-empty--compact">
          <span class="gw-empty__icon"><svg lucideIcon="timer" class="gw-icon"></svg></span>
          <h3 class="gw-empty__title">Short-lived credentials aren't available yet</h3>
          <p class="gw-empty__text">This core doesn't report the credentials it issues yet.</p>
        </div>
      </div>
    } @else if (data.shortLived().error; as e) {
      <div class="gw-alert gw-alert--danger" role="alert">
        <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
        <div class="gw-alert__content">
          <p class="gw-alert__title">The short-lived credentials didn't load</p>
          <p class="gw-alert__body">{{ errorText(e) }}</p>
          <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button" (click)="retry()" [attr.aria-busy]="data.refreshing() ? 'true' : null" [attr.aria-disabled]="data.refreshing() ? 'true' : null">Try again</button></div>
        </div>
      </div>
    } @else {
      <ul class="gw-grid gw-grid--2" aria-hidden="true">
        @for (i of [1, 2]; track i) {
          <li><div class="gw-card"><div class="gw-card__body"><div class="gw-stack gw-stack--sm"><span class="gw-skeleton gw-skeleton--text"></span><span class="gw-skeleton gw-skeleton--text"></span></div></div></div></li>
        }
      </ul>
    }
  `,
})
export class GwShortLived {
  protected readonly data = inject(SecretsData);
  protected readonly time = inject(TimeService);
  protected readonly idOf = idOf;
  protected readonly connectionsQuery = connectionsQuery;

  protected readonly groups = computed(() => shortLivedGroups(this.data.shortLived().value ?? [], this.data.envOf(), this.data.rows().value ?? []));
  protected readonly summary = computed(() => shortLivedSummary(this.groups()));

  protected names(g: { readonly connections: readonly { readonly name: string }[] }): string[] {
    return g.connections.map((c) => c.name);
  }

  protected joinNames(g: { readonly connections: readonly { readonly name: string }[] }): string {
    const n = this.names(g);
    return n.length <= 1 ? (n[0] ?? '') : `${n.slice(0, -1).join(', ')} and ${n[n.length - 1]}`;
  }

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : 'The core didn’t answer. Try again.';
  }
}
