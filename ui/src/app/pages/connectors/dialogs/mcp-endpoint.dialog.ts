import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { isApiError } from '../../../core/api/api-error';
import { EndpointsApi } from '../../../core/api/endpoints.api';
import { Env } from '../../../core/api/types';
import { GwAriaDisabled } from '../../../kit/aria-disabled';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { literalParts } from '../../../kit/elements/shared/literals';
import { GwIcon } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { EndpointChoice, endpointChoices, initialEndpoints } from '../../connection-new/connection-new-view';
import { joinAnd, plural } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureText } from './failure';

/** A server that just connected (or started): what the endpoint step needs to know about it. */
export interface McpServerConnected {
  readonly name: string;
  readonly env: Env;
  readonly http: boolean;
  /** How many tools it listed, when the core said. */
  readonly tools: number | null;
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

/**
 * `<dialog gwMcpEndpointDialog>`: the last step of adding an MCP server, as the connection wizard's Add to endpoint —
 * the endpoints its tools may join (the ones that can't take it with their reason, as in the wizard), the usual one
 * chosen, and each tool starting in its default mode there. Add to endpoint asks the core again per endpoint
 * (`POST /api/endpoints/{slug}/connections/preview`) before `POST /api/endpoints/{slug}/connections/{name}`. Not now
 * leaves its tools Off everywhere. It opens once the endpoints loaded; initial focus is the first endpoint it may join.
 */
@Component({
  selector: 'dialog[gwMcpEndpointDialog]',
  imports: [GwIcon, GwDialogClose, GwAriaDisabled],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-dialog gw-dialog--md',
    '[attr.aria-labelledby]': 'server() ? uid + "-t" : null',
    '[attr.aria-describedby]': 'server() ? uid + "-d" : null',
    '(close)': 'finish()',
  },
  template: `
    @if (server(); as s) {
      <form method="dialog" novalidate (submit)="add($event)">
        <header class="gw-dialog__header">
          <span class="gw-icon-tile"><svg lucideIcon="waypoints" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" [id]="uid + '-t'">Add <span class="gw-mono">{{ s.name }}</span> to an endpoint</h2>
          <p class="gw-dialog__description" [id]="uid + '-d'">AI apps reach its {{ s.tools === null ? 'tools' : plural(s.tools, 'tool') }} through an endpoint. Declared reads start as Read only; other tools start as Approval. You can change all tool modes on the endpoint.</p>
          <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
        </header>
        <div class="gw-dialog__body">
          <div class="gw-stack">
            <p class="gw-text-small">Environment <span [class]="'gw-env gw-env--sm gw-env--' + s.env">{{ s.env }}</span></p>
            @if (loadError(); as e) {
              <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
                <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content"><p class="gw-alert__title">The endpoints didn't load</p><p class="gw-alert__body">{{ e }} Add {{ s.name }} from an endpoint later.</p></div>
              </div>
            } @else if (choices().length) {
              <fieldset class="gw-field">
                <legend class="gw-field__label">Endpoints</legend>
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
                        [attr.autofocus]="c.slug === firstUsable() ? '' : null"
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
              </fieldset>
            } @else {
              <p class="gw-text-small gw-fg-muted">There is no endpoint yet. Create one in Endpoints, then add <span class="gw-mono">{{ s.name }}</span> to it.</p>
            }
            @if (error()) {
              <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
                <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content"><p class="gw-alert__title">Not added</p><p class="gw-alert__body">{{ error() }}</p></div>
              </div>
            }
          </div>
        </div>
        <footer class="gw-dialog__footer">
          <button class="gw-btn" type="button" gwDialogClose [attr.autofocus]="firstUsable() ? null : ''">Not now</button>
          @if (choices().length && !loadError()) {
            <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() || !chosen().length ? 'true' : null" [attr.aria-describedby]="!chosen().length ? uid + '-none' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}{{ chosen().length > 1 ? 'Add to ' + chosen().length + ' endpoints' : 'Add to endpoint' }}</button>
            @if (!chosen().length) {<span class="gw-sr-only" [id]="uid + '-none'">Choose an endpoint first.</span>}
          }
        </footer>
      </form>
    }
  `,
})
export class GwMcpEndpointDialog {
  private readonly endpointsApi = inject(EndpointsApi);
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly uid = `med-${++nextId}`;
  protected readonly plural = plural;
  protected readonly literalParts = literalParts;
  protected readonly server = signal<McpServerConnected | null>(null);
  protected readonly choices = signal<readonly EndpointChoice[]>([]);
  protected readonly chosen = signal<readonly string[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Set once Add to endpoint has run: closing then shows its outcome, not "Not now". */
  private outcome: EndpointOutcome | null = null;

  /** The first endpoint the server may join: the initial focus. */
  protected firstUsable(): string | null {
    return this.choices().find((c) => !c.refusal)?.slug ?? null;
  }

  /** Loads the endpoints, then opens: the choices are there when the dialog takes the focus. */
  async show(server: McpServerConnected): Promise<void> {
    this.server.set(server);
    this.outcome = null;
    this.error.set(null);
    this.busy.set(false);
    this.loadError.set(null);
    this.choices.set([]);
    this.chosen.set([]);
    try {
      const endpoints = await this.endpointsApi.list();
      const choices = endpointChoices(endpoints, { name: server.name, env: server.env });
      this.choices.set(choices);
      this.chosen.set(initialEndpoints(choices, server.env));
    } catch (e) {
      this.loadError.set(failureText(e, 'This build of the core lists no endpoints.'));
    }
    this.dialog.openAfterRender(server.opener);
  }

  protected toggle(c: EndpointChoice, event: Event): void {
    const input = event.target as HTMLInputElement;
    if (c.refusal) {
      input.checked = false;
      return;
    }
    const on = input.checked;
    this.chosen.update((l) => (on ? [...l.filter((s) => s !== c.slug), c.slug] : l.filter((s) => s !== c.slug)));
  }

  /** Add to endpoint: the core checks each endpoint again before the server joins it. */
  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    const s = this.server();
    if (!s || this.busy() || !this.chosen().length) return;
    this.busy.set(true);
    this.error.set(null);
    const usable = new Set(this.choices().filter((c) => !c.refusal).map((c) => c.slug));
    const added: string[] = [];
    const notAdded: { slug: string; why: string }[] = [];
    for (const slug of this.chosen().filter((x) => usable.has(x))) {
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
      // Nothing joined: the dialog stays, with the reasons, for another choice or Not now.
      this.error.set(notAdded.map((n) => `${n.slug}: ${n.why}`).join(' '));
      return;
    }
    this.outcome = { added, notAdded };
    void this.data.connections.refresh();
    this.dialog.close('added');
  }

  /** The dialog closed: the toast says where the tools are now (or that they are Off). */
  protected finish(): void {
    const s = this.server();
    if (!s) return;
    const outcome = this.outcome ?? { added: [], notAdded: [] };
    const toast = connectedToast(s, outcome);
    const first = outcome.added[0];
    this.toasts.show({
      ...toast,
      ...(first ? { actions: [{ label: `Open endpoint ${first}`, onClick: () => void this.router.navigate(['/endpoints', first]) }] } : {}),
    });
    this.server.set(null);
  }
}
