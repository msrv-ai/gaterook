import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { isApiError } from '../../../core/api/api-error';
import { ConnectionPreview, EndpointsApi } from '../../../core/api/endpoints.api';
import { Env } from '../../../core/api/types';
import { GwAriaDisabled } from '../../../kit/aria-disabled';
import { MODES } from '../../../kit/elements/shared/dictionaries';
import { literalParts } from '../../../kit/elements/shared/literals';
import { GwIcon } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { EndpointChoice, endpointChoices, initialEndpoints } from '../connection-new-view';
import { joinAnd, plural } from '../../connectors/connectors-view';
import { ConnectorsData } from '../../connectors/connectors.data';
import { failureText } from '../../connectors/dialogs/failure';

/** A server that just connected (or started): what the endpoint step needs to know about it. */
export interface McpServerConnected {
  readonly name: string;
  readonly env: Env;
  readonly http: boolean;
  /** How many tools it listed, when the core said. */
  readonly tools: number | null;
  /**
   * It connected with a sign-in made in the review: the host of the sign-in page as the words name it (null for an IP
   * address or localhost, which name nothing). Absent or null: no sign-in.
   */
  readonly signedIn?: { readonly host: string | null } | null;
  readonly opener: HTMLElement | null;
}

/** What adding a server to endpoints came to: where its tools are now, and where they couldn't go and why. */
export interface EndpointOutcome {
  readonly added: readonly string[];
  readonly notAdded: readonly { readonly slug: string; readonly why: string }[];
}

/**
 * The toast that ends adding an MCP server: where its tools went (declared reads start as Read only there), or that they are Off
 * until it joins an endpoint.
 */
export function connectedToast(
  s: Pick<McpServerConnected, 'name' | 'http' | 'tools'>,
  outcome: EndpointOutcome,
): { readonly tone: 'success' | 'warning'; readonly title: string; readonly body: string } {
  const verb = s.http ? 'connected' : 'started';
  const tools = s.tools === null ? 'Its tools' : s.tools === 1 ? 'Its tool' : `Its ${plural(s.tools, 'tool')}`;
  const are = s.tools === 1 ? 'is' : 'are';
  if (outcome.notAdded.length) {
    return {
      tone: 'warning',
      title: `MCP server ${s.name} ${verb}, not added to ${joinAnd(outcome.notAdded.map((n) => n.slug))}`,
      body: [outcome.added.length ? `${tools} ${are} on ${joinAnd(outcome.added)} with their default modes.` : null, ...outcome.notAdded.map((n) => n.why)]
        .filter(Boolean)
        .join(' '),
    };
  }
  if (outcome.added.length) {
    return {
      tone: 'success',
      title: `MCP server ${s.name} ${verb}`,
      body: `${tools} ${are} on ${joinAnd(outcome.added)}. Declared reads start as Read only; other tools start as Approval. Change their modes on the endpoint.`,
    };
  }
  return {
    tone: 'success',
    title: `MCP server ${s.name} ${verb}`,
    body: `${tools} ${are} Off until you add them to an endpoint. Declared reads start as Read only; other tools start as Approval.`,
  };
}

let nextId = 0;

/** One chosen endpoint's tools as the core would start them there, or why they didn't load. */
interface EndpointPreview {
  readonly state: 'loading' | 'ready' | 'error';
  readonly preview: ConnectionPreview | null;
  readonly error: string | null;
}

/**
 * `<form gwMcpEndpoints>` (kit `connection-new`, step 4 of an MCP server): the server is saved and its tools are Off
 * everywhere; here they join endpoints, as the connection wizard's Add to endpoint does. It lists the endpoints its tools
 * may join (the ones that can't take it with their reason), the usual one chosen — or the one the page was opened for
 * (`?endpoint=`) — and, for each chosen endpoint, every tool in the mode the core would start it in there
 * (`POST /api/endpoints/{slug}/connections/preview`). Add to endpoint asks the core again per endpoint before
 * `POST /api/endpoints/{slug}/connections/{name}`; Not now leaves its tools Off. A toast says where the tools went.
 */
@Component({
  selector: 'form[gwMcpEndpoints]',
  imports: [GwIcon, GwAriaDisabled, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-form gw-form--sticky-actions', novalidate: '', '(submit)': 'add($event)' },
  template: `
    @if (server(); as s) {
      <div class="gw-alert gw-alert--success" [id]="uid + '-saved'" role="status">
        <svg lucideIcon="circle-check" class="gw-icon gw-alert__icon"></svg>
        <div class="gw-alert__content">
          @if (s.signedIn; as signed) {
            <p class="gw-alert__title">Signed in to <span class="gw-mono">{{ s.name }}</span></p>
            <p class="gw-alert__body">It connected with your sign-in{{ signed.host ? ' at ' + signed.host : '' }}; the agent never sees it. <span class="gw-mono">{{ s.name }}</span> is saved, and its {{ toolsWord(s) }} {{ s.tools === 1 ? 'is' : 'are' }} Off everywhere until you add {{ s.tools === 1 ? 'it' : 'them' }} here.</p>
          } @else {
            <p class="gw-alert__title"><span class="gw-mono">{{ s.name }}</span> {{ s.http ? 'is connected' : 'started' }}</p>
            <p class="gw-alert__body">It is saved, and its {{ toolsWord(s) }} {{ s.tools === 1 ? 'is' : 'are' }} Off everywhere until you add {{ s.tools === 1 ? 'it' : 'them' }} here.</p>
          }
        </div>
      </div>

      <fieldset class="gw-form__section" [disabled]="busy()">
        <legend class="gw-form__legend">Endpoints</legend>
        <p class="gw-form__description">Pick where agents may use <span class="gw-mono">{{ s.name }}</span>. Every AI app of a chosen endpoint gets its tools on the next call.</p>
        <div class="gw-form__rows">
          @if (loading()) {
            <div class="gw-grid gw-grid--auto" aria-busy="true">
              <span class="gw-skeleton gw-skeleton--block" aria-hidden="true"></span>
              <span class="gw-skeleton gw-skeleton--block" aria-hidden="true"></span>
              <p class="gw-text-small gw-fg-muted" role="status">Loading endpoints…</p>
            </div>
          } @else if (loadError(); as e) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">The endpoints didn’t load</p><p class="gw-alert__body">{{ e }} You can add {{ s.name }} from an endpoint later.</p></div>
              <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button" (click)="reload()">Try again</button></div>
            </div>
          } @else if (choices().length) {
            <div class="gw-grid gw-grid--auto">
              @for (c of choices(); track c.slug; let i = $index) {
                @let base = uid + '-ep-' + i;
                <label class="gw-choice-card gw-choice-card--compact">
                  <input
                    class="gw-choice-card__input"
                    type="checkbox"
                    name="endpoints"
                    [value]="c.slug"
                    [checked]="!c.refusal && chosen().includes(c.slug)"
                    [gwAriaDisabled]="!!c.refusal"
                    [attr.aria-labelledby]="base + '-t'"
                    [attr.aria-describedby]="base + '-d' + (c.meta && !c.refusal ? ' ' + base + '-m' : '')"
                    (change)="toggle(c, $event)"
                  />
                  <span class="gw-choice-card__icon"><svg lucideIcon="waypoints" class="gw-icon"></svg></span>
                  <span class="gw-choice-card__title gw-mono" [id]="base + '-t'">{{ c.slug }}</span>
                  <span class="gw-choice-card__description" [id]="base + '-d'">@for (part of literalParts(c.description, c.refusal ? [s.name] : []); track $index) {@if (part.code) {<span class="gw-mono">{{ part.text }}</span>} @else {{{ part.text }}}}</span>
                  @if (c.meta && !c.refusal) {
                    <span class="gw-choice-card__meta" [id]="base + '-m'">{{ c.meta }}</span>
                  }
                </label>
              }
            </div>
            <p class="gw-text-small gw-fg-muted">Need a separate URL for this? <a class="gw-link" routerLink="/endpoints">Create an endpoint</a>, then add <span class="gw-mono">{{ s.name }}</span> from it.</p>
          } @else {
            <p class="gw-text-small gw-fg-muted">There is no endpoint yet. <a class="gw-link" routerLink="/endpoints">Create one in Endpoints</a>, then add <span class="gw-mono">{{ s.name }}</span> to it.</p>
          }
        </div>
      </fieldset>

      <section class="gw-form__section" [attr.aria-labelledby]="uid + '-tools-t'">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" [id]="uid + '-tools-t'">Tools agents get</h3>
          <p class="gw-form__description">What <span class="gw-mono">{{ s.name }}</span> listed, with the mode each starts in. Declared reads start as Read only and other tools as Approval; the server declares its annotations and GateRook can’t verify them. You can change every mode on the endpoint.</p>
        </header>
        <div class="gw-form__rows">
          @for (slug of shown(); track slug) {
            @let pv = previews()[slug];
            <div class="gw-tool-policy-list">
              <section class="gw-card gw-card--flush" [attr.aria-labelledby]="uid + '-pv-' + slug + '-t'">
                <header class="gw-card__header">
                  <div class="gw-tool-policy-list__group-heading">
                    <span class="gw-icon-tile"><svg lucideIcon="plug" class="gw-icon"></svg></span>
                    <div class="gw-card__heading">
                      <h4 class="gw-card__title" [id]="uid + '-pv-' + slug + '-t'"><span class="gw-mono">{{ s.name }}</span> <span class="gw-sr-only">environment</span> <span [class]="'gw-env gw-env--sm gw-env--' + s.env">{{ s.env }}</span> on endpoint <span class="gw-mono">{{ slug }}</span></h4>
                      @if (pv?.preview; as p) {<p class="gw-card__description">{{ summary(p) }}</p>}
                    </div>
                  </div>
                </header>
                <div class="gw-card__body">
                  @if (!pv || pv.state === 'loading') {
                    <p class="gw-field__hint" role="status"><span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span> Loading its tools on {{ slug }}…</p>
                  } @else if (pv.state === 'error') {
                    <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
                      <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                      <div class="gw-alert__content"><p class="gw-alert__title">Its tools on {{ slug }} didn’t load</p><p class="gw-alert__body">{{ pv.error }}</p></div>
                      <div class="gw-alert__actions"><button class="gw-btn gw-btn--sm" type="button" (click)="preview(slug, true)">Try again</button></div>
                    </div>
                  } @else if (pv.preview; as p) {
                    @for (w of p.warnings; track $index) {
                      <div class="gw-alert gw-alert--warning gw-alert--compact"><svg lucideIcon="triangle-alert" class="gw-icon gw-alert__icon"></svg><div class="gw-alert__content"><p class="gw-alert__body">{{ w }}</p></div></div>
                    }
                    @if (p.refusal) {
                      <p class="gw-text-small gw-fg-muted">{{ p.refusal }}</p>
                    } @else if (p.tools.length) {
                      <div class="gw-table-wrap" role="region" [attr.aria-labelledby]="uid + '-pv-' + slug + '-cap'" tabindex="0">
                        <table class="gw-table">
                          <caption class="gw-sr-only" [id]="uid + '-pv-' + slug + '-cap'">Tools of {{ s.name }} on endpoint {{ slug }}, with the modes they start in</caption>
                          <thead><tr><th scope="col">Tool</th><th scope="col">Starts as</th></tr></thead>
                          <tbody>
                            @for (t of p.tools; track t.toolKey) {
                              @let m = mode(t.mode);
                              <tr class="gw-tool-policy-list__row" [attr.data-mode]="m.data">
                                <th scope="row" class="gw-tool-policy-list__tool">
                                  <div class="gw-tool-policy-list__name"><code class="gw-code">{{ t.tool }}</code></div>
                                  @if (t.locked) {<p class="gw-tool-policy-list__description gw-text-caption gw-fg-muted">{{ t.locked }}</p>}
                                </th>
                                <td><span [class]="'gw-mode ' + m.cls"><svg [lucideIcon]="m.icon" class="gw-icon"></svg>{{ m.text }}</span></td>
                              </tr>
                            }
                          </tbody>
                        </table>
                      </div>
                      <p class="gw-text-caption gw-fg-muted">Declared by <span class="gw-mono">{{ s.name }}</span>, not verified.</p>
                    } @else {
                      <p class="gw-text-small gw-fg-muted">{{ s.name }} lists no tools yet.</p>
                    }
                  }
                </div>
              </section>
            </div>
          } @empty {
            <div class="gw-card gw-card--dashed">
              <div class="gw-empty gw-empty--compact">
                <span class="gw-empty__icon"><svg lucideIcon="waypoints" class="gw-icon"></svg></span>
                <p class="gw-empty__title">Not on an endpoint yet</p>
                <p class="gw-empty__text">Agents can’t reach <span class="gw-mono">{{ s.name }}</span> until it is on an endpoint. Choose one above, or use Not now and add it from the endpoint later.</p>
              </div>
            </div>
          }
        </div>
      </section>

      @if (error()) {
        <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert" [id]="uid + '-error'">
          <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
          <div class="gw-alert__content"><p class="gw-alert__title">Not added</p><p class="gw-alert__body">{{ error() }}</p></div>
        </div>
      }

      <div class="gw-form__actions">
        <p class="gw-form__note"><svg lucideIcon="circle-check" class="gw-icon"></svg><span><span class="gw-mono">{{ s.name }}</span> is saved</span></p>
        <button class="gw-btn gw-btn--ghost" type="button" (click)="notNow()" [attr.aria-disabled]="busy() ? 'true' : null">Not now<span class="gw-sr-only">: leave its tools Off</span></button>
        <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() || !usable().length ? 'true' : null" [attr.aria-describedby]="!usable().length ? uid + '-none' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}{{ usable().length > 1 ? 'Add to ' + usable().length + ' endpoints' : 'Add to endpoint' }}</button>
        @if (!usable().length) {<span class="gw-sr-only" [id]="uid + '-none'">Choose an endpoint first.</span>}
      </div>
    }
  `,
})
export class GwMcpEndpoints {
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly data = inject(ConnectorsData);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);

  /** The person is done here: its tools went where `added` says (none after Not now). */
  readonly finished = output<EndpointOutcome>();

  protected readonly uid = `med-${++nextId}`;
  protected readonly literalParts = literalParts;
  readonly server = signal<McpServerConnected | null>(null);
  protected readonly choices = signal<readonly EndpointChoice[]>([]);
  readonly chosen = signal<readonly string[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly previews = signal<Readonly<Record<string, EndpointPreview>>>({});
  /** The endpoint the page was opened for (Overview's Add service): chosen first when it may take the server. */
  private preferred: string | null = null;
  private generation = 0;

  /** The chosen endpoints that may take the server, in the order of the list. */
  protected readonly usable = computed(() => this.choices().filter((c) => !c.refusal && this.chosen().includes(c.slug)).map((c) => c.slug));
  protected readonly shown = computed(() => (this.loading() || this.loadError() ? [] : this.usable()));

  /** The server that just connected: its endpoints load, the usual one (or the one asked for) chosen. */
  async load(server: McpServerConnected, preferred: string | null = null): Promise<void> {
    const generation = ++this.generation;
    this.server.set(server);
    this.preferred = preferred;
    this.error.set(null);
    this.busy.set(false);
    this.loadError.set(null);
    this.choices.set([]);
    this.chosen.set([]);
    this.previews.set({});
    this.loading.set(true);
    try {
      const endpoints = await this.endpointsApi.list();
      if (generation !== this.generation) return;
      const choices = endpointChoices(endpoints, { name: server.name, env: server.env });
      this.choices.set(choices);
      const wanted = preferred && choices.some((c) => c.slug === preferred && !c.refusal) ? [preferred] : null;
      this.chosen.set(wanted ?? initialEndpoints(choices, server.env));
    } catch (e) {
      if (generation !== this.generation) return;
      this.loadError.set(failureText(e, 'This build of the core lists no endpoints.'));
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
    this.usable().forEach((slug) => void this.preview(slug));
  }

  protected reload(): void {
    const s = this.server();
    if (s) void this.load(s, this.preferred);
  }

  protected toolsWord(s: McpServerConnected): string {
    return s.tools === null ? 'tools' : plural(s.tools, 'tool');
  }

  protected mode(m: string): { readonly cls: string; readonly icon: string; readonly text: string; readonly data: string } {
    const look = MODES[m] ?? MODES['OFF'];
    return { cls: look.cls, icon: look.icon, text: look.text, data: m === 'READ_ONLY' ? 'read' : m.toLowerCase() };
  }

  /** "12 tools · 8 read only · 4 approval" */
  protected summary(p: ConnectionPreview): string {
    const count = (m: string) => p.tools.filter((t) => t.mode === m).length;
    const parts = [plural(p.tools.length, 'tool'),
      ...[['READ_ONLY', 'read only'], ['APPROVAL', 'approval'], ['BYPASS', 'bypass'], ['OFF', 'off']]
        .map(([m, word]) => [count(m), word] as const).filter(([n]) => n > 0).map(([n, word]) => `${n} ${word}`)];
    return parts.join(' · ');
  }

  /** The tools on one endpoint as the core would start them there (asked once per endpoint, or again on retry). */
  async preview(slug: string, retry = false): Promise<void> {
    const s = this.server();
    if (!s || (!retry && this.previews()[slug])) return;
    const generation = this.generation;
    this.previews.update((m) => ({ ...m, [slug]: { state: 'loading', preview: null, error: null } }));
    try {
      const preview = await this.endpointsApi.previewConnection(slug, s.name);
      if (generation !== this.generation) return;
      this.previews.update((m) => ({ ...m, [slug]: { state: 'ready', preview, error: null } }));
    } catch (e) {
      if (generation !== this.generation) return;
      this.previews.update((m) => ({ ...m, [slug]: { state: 'error', preview: null, error: failureText(e, "The core didn't answer.") } }));
    }
  }

  protected toggle(c: EndpointChoice, event: Event): void {
    const input = event.target as HTMLInputElement;
    if (c.refusal) {
      input.checked = false;
      return;
    }
    const on = input.checked;
    this.chosen.update((l) => (on ? [...l.filter((s) => s !== c.slug), c.slug] : l.filter((s) => s !== c.slug)));
    if (on) void this.preview(c.slug);
  }

  /** Add to endpoint: the core checks each endpoint again before the server joins it. */
  async add(e?: Event): Promise<void> {
    e?.preventDefault();
    const s = this.server();
    if (!s || this.busy() || !this.usable().length) return;
    this.busy.set(true);
    this.error.set(null);
    const added: string[] = [];
    const notAdded: { slug: string; why: string }[] = [];
    for (const slug of this.usable()) {
      try {
        const preview = await this.endpointsApi.previewConnection(slug, s.name);
        if (preview.refusal) {
          notAdded.push({ slug, why: preview.refusal });
          continue;
        }
        await this.endpointsApi.addConnection(slug, s.name);
        added.push(slug);
      } catch (err) {
        notAdded.push({ slug, why: isApiError(err) ? err.detail : "The core didn't answer." });
      }
    }
    this.busy.set(false);
    if (!added.length) {
      // Nothing joined: the step stays, with the reasons, for another choice or Not now.
      this.error.set(notAdded.map((n) => `${n.slug}: ${n.why}`).join(' '));
      return;
    }
    void this.data.connections.refresh();
    this.finish({ added, notAdded });
  }

  /** Not now: its tools stay Off everywhere. */
  protected notNow(): void {
    if (this.busy()) return;
    this.finish({ added: [], notAdded: [] });
  }

  private finish(outcome: EndpointOutcome): void {
    const s = this.server();
    if (!s) return;
    const first = outcome.added[0];
    this.toasts.show({
      ...connectedToast(s, outcome),
      ...(first ? { actions: [{ label: `Open endpoint ${first}`, onClick: () => void this.router.navigate(['/endpoints', first]) }] } : {}),
    });
    this.finished.emit(outcome);
  }
}
