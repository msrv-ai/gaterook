import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, TemplateRef, booleanAttribute, computed, effect, inject, input, model, numberAttribute, output, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ClientCard, ClientMethod, ClientMethodId, ConnectPreview, ConnectRequest, toKitDiff } from '../../../core/api/clients.api';
import { TimeService } from '../../../core/time/time.service';
import { GwDiff } from '../../../code/diff';
import { GwCodeBlock } from '../../../code/code-block';
import { GwCopy } from '../../copy';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import {
  ClientEndpoint,
  ClientFailure,
  TOKEN_NAME,
  authNote,
  changeSummary,
  choiceMeta,
  defaultTokenName,
  endpointOption,
  langLabel,
  expiresOn,
  fileName,
  methodChoice,
  methodsFor,
  restartNote,
} from './client-view';

/** The token lifetimes Connect offers (kit: 7 days by default). */
const TTL_DAYS = [7, 30, 90] as const;

/**
 * `<div gwClientConnectFlow [card]="cursor" [endpoints]="…" [preview]="p" (requestChange)="…">` (client-card.html,
 * "Connect"): the body of Connect, in the connect dialog or inline in a card (`__flow`). Endpoint · for clients with
 * a choice, how it signs in (`gw-choice-card --compact`; methods prod refuses are left out, with the reason) · the
 * token it issues (token in the file) · "What will change": the exact diff of each file and its backup, from the
 * core's preview, before anything is written · the auth note (a token in the file is a warning) · the restart it
 * needs.
 *
 * It writes nothing: every change of the selection emits `requestChange` (a `ConnectRequest`) for the page to ask
 * the core for a new preview, and the page passes it back as `preview` (or `failure`).
 */
@Component({
  selector: 'div[gwClientConnectFlow]',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwDiff, GwCodeBlock, GwCopy, GwToggle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-client-card__flow' },
  templateUrl: './client-connect-flow.html',
})
export class GwClientConnectFlow {
  protected readonly time = inject(TimeService);

  readonly card = input.required<ClientCard>();
  /** The endpoints to choose from (Add endpoint passes only those the client doesn't reach yet). */
  readonly endpoints = input.required<readonly ClientEndpoint[]>();
  readonly preview = input<ConnectPreview | null>(null);
  /** The core is preparing the preview. */
  readonly loading = input(false, { transform: booleanAttribute });
  /** The preview was refused (a file it can't edit safely, a rule). */
  readonly failure = input<ClientFailure | null>(null);
  /** Ids of the fields inside (one flow per dialog). */
  readonly idPrefix = input.required<string>();
  /** The steps' heading level: 3 in the dialog, 4 inline in a card. */
  readonly level = input(3, { transform: numberAttribute });
  /** The endpoint select gets the initial focus (the dialog's). */
  readonly autofocus = input(false, { transform: booleanAttribute });
  /** The diff wraps long lines (the dialog); inline it scrolls. */
  readonly wrap = input(false, { transform: booleanAttribute });
  /**
   * The flow open inline in a detected client's card on the Clients screen (pages/clients.html, Cursor): it says
   * what to do first, the endpoint's warnings sit under its select, each sign-in choice says where it works, the
   * token step ends with where the token goes and a way to revoke it, sign-in through the browser offers the
   * "Add to Cursor" link, and a refused change is the page's "Connect failed" alert (what failed, Open config,
   * Details) instead of a diff.
   */
  readonly page = input(false, { transform: booleanAttribute });
  /** Open config in the page's failure alert needs the desktop app. */
  readonly canOpenConfig = input(true, { transform: booleanAttribute });
  /**
   * The setup review (pages/clients.html `?state=setup`) names the one endpoint above the flow ("What Claude Desktop
   * can use"): with a single endpoint there is no select.
   */
  readonly single = input(false, { transform: booleanAttribute });
  /** What goes right after the page's first line (the setup review's "What Claude Desktop can use"). */
  readonly lead = input<TemplateRef<unknown> | null>(null);
  /** Open config was pressed in the failure alert. */
  readonly openConfig = output<void>();

  readonly endpoint = model<string | null>(null);
  readonly method = model<ClientMethodId | null>(null);
  /** The sign-in the person picked themselves (a prod endpoint may force another for a while). */
  private readonly chosen = signal<ClientMethodId | null>(null);
  readonly tokenName = model<string>('');
  readonly ttlDays = model<number>(7);

  /** The selection changed: ask the core for a new preview. Null while the token name is invalid. */
  readonly requestChange = output<ConnectRequest | null>();

  protected readonly ttls = TTL_DAYS;
  protected readonly endpointOption = endpointOption;
  protected readonly langLabel = langLabel;
  protected readonly selected = computed(() => this.endpoints().find((e) => e.slug === this.endpoint()) ?? null);
  /** The endpoint select, unless the review already named the only endpoint. */
  protected readonly showSelect = computed(() => !(this.single() && this.endpoints().length === 1));
  protected readonly methods = computed(() => methodsFor(this.card(), this.selected()?.env ?? null));
  /**
   * The sign-in choices: what the endpoint allows. The bridge entry is a client's fallback (the only way for prod), so
   * it is a choice only where nothing else is offered, and for Claude Desktop (extension or config entry).
   */
  protected readonly choices = computed(() => {
    const card = this.card();
    const { offered, refused } = this.methods();
    const shown = (m: ClientMethod) => m.id !== 'config-bridge' || card.client === 'claude-desktop' || offered.length === 1;
    const choices = offered.filter(shown).map((m) => ({ method: m, refused: false, ...methodChoice(card, m) }));
    if (!this.page()) return choices;
    // The page keeps the choices a prod endpoint refuses in place, unavailable with the reason (DESIGN.md §8): the
    // layout stays, and the person sees why a token in the file isn't offered here (ARCHITECTURE.md §8.6.2).
    const all = card.methods.filter((m) => refused.includes(m) || choices.some((c) => c.method === m));
    return all.map((m) => choices.find((c) => c.method === m) ?? { method: m, refused: true, ...methodChoice(card, m) });
  });
  protected readonly endpointHint = computed(() => {
    const name = this.card().name;
    return `${name} gets the tools of this endpoint and nothing else.${this.card().client === 'codex' ? '' : ' Mutations still wait for your approval.'}`;
  });
  protected readonly refusedReason = computed(() => {
    const r = this.methods().refused;
    return r.length && this.selected()?.env === 'prod' ? (r.find((m) => m.reason)?.reason ?? null) : null;
  });
  protected readonly tokenValid = computed(() => TOKEN_NAME.test(this.tokenName()) && this.tokenName().length <= 40);
  protected readonly expiry = computed(() => expiresOn(this.time.minute(), this.ttlDays()));
  protected readonly configName = computed(() => fileName(this.card().configDisplay));

  protected readonly files = computed(() =>
    (this.preview()?.files ?? []).map((f) => ({
      file: f,
      diff: f.kind !== 'mcpb' && f.diff ? toKitDiff(f.diff) : null,
      summary: f.diff ? changeSummary(f.diff) : null,
    })),
  );

  protected readonly auth = computed(() => {
    const m = this.method();
    const slug = this.endpoint();
    if (!m || !slug) return null;
    return authNote(this.card(), m, slug, this.tokenName() || null, this.grantLabel());
  });

  protected readonly restart = computed(() => {
    const m = this.method();
    return m ? restartNote(this.card().client, m, this.configName()) : null;
  });

  /** The preview of the current selection (one for another endpoint or method is still on its way to being replaced). */
  protected readonly current = computed(() => {
    const p = this.preview();
    return p && p.endpoint === this.endpoint() && p.method === this.method() ? p : null;
  });
  /** The page: what Connect starts with, in one line. */
  protected readonly intro = computed(() => {
    const name = this.card().name;
    if (!this.showSelect()) {
      // The setup review (kit): the endpoint is named above, so the line says what Connect does.
      if (this.choices().length > 1) return `Found on this computer. Choose how ${name} signs in and check the change. Nothing is written until you click Connect ${name}.`;
      if (this.method() === 'mcpb') return `Found on this computer. Connect installs the GateRook extension in ${name}; no config file is written.`;
      return `Found on this computer. Connect adds one server to ${name}'s config, with a backup first. Nothing is written until you click Connect ${name}.`;
    }
    return this.choices().length > 1
      ? `Found on this computer. Pick an endpoint and how ${name} signs in, then check the change; nothing is written until you connect.`
      : 'Found on this computer. Pick an endpoint, then check the change; nothing is written until you connect.';
  });
  /** The page: the core's warnings about the chosen endpoint (prod), under its select; the select points at them. */
  readonly warnings = computed(() => (this.page() ? (this.current()?.warnings ?? []) : []));
  readonly warningsId = computed(() => `${this.idPrefix()}-warn`);
  /**
   * The core's notes. The page says some of them already, once: the token line in the token step, and what the chosen
   * sign-in does on its choice card (its description, "Turns on OAuth sign-in", the bridge's 15-minute token).
   */
  protected readonly notes = computed(() => {
    const notes = this.preview()?.notes ?? [];
    if (!this.page()) return notes;
    const method = this.method();
    const token = this.tokenName();
    const chosen = this.choices().length > 1 ? this.choices().find((c) => c.method.id === method) : undefined;
    const echoes = (n: string) =>
      (method === 'token' && n.startsWith(`Token ${token} · `)) ||
      (!!chosen &&
        (n === chosen.description ||
          (method === 'oauth' && n.startsWith('Turns on OAuth sign-in for endpoint ')) ||
          (method === 'config-bridge' && n.startsWith('No token is written: the bridge gets'))));
    return notes.filter((n) => !echoes(n));
  });
  /** The page: "Add to Cursor" carries only the endpoint URL; offered with sign-in through the browser. */
  protected readonly deeplink = computed(() => (this.page() && this.method() === 'oauth' ? (this.current()?.link ?? null) : null));
  protected readonly choiceMeta = choiceMeta;

  /** The access label a grant gets ("codex-laptop"); the token method names its token instead. */
  protected readonly grantLabel = computed(() => (this.method() === 'token' ? null : defaultTokenName(this.card().client)));

  /** What Confirm would send to `connectPreview`. */
  readonly request = computed<ConnectRequest | null>(() => {
    const endpoint = this.endpoint();
    const method = this.method();
    if (!endpoint || !method) return null;
    if (method === 'token') {
      return this.tokenValid() ? { endpoint, method, token: { name: this.tokenName(), ttlDays: this.ttlDays() } } : null;
    }
    return { endpoint, method, grantLabel: this.grantLabel() };
  });

  constructor() {
    // Defaults: the first endpoint, the first method that endpoint allows, the client's token name.
    effect(() => {
      const endpoints = this.endpoints();
      untracked(() => {
        if (!endpoints.some((e) => e.slug === this.endpoint())) this.endpoint.set(endpoints[0]?.slug ?? null);
        if (!this.tokenName()) this.tokenName.set(defaultTokenName(this.card().client));
      });
    });
    effect(() => {
      const offered = this.methods().offered;
      untracked(() => {
        if (!offered.some((m) => m.id === this.method())) this.method.set(offered[0]?.id ?? null);
      });
    });
  }

  protected onEndpoint(e: Event): void {
    this.endpoint.set((e.target as HTMLSelectElement).value);
    // The method follows the endpoint before the preview is asked for: a prod endpoint refuses a token in the file.
    // Back on an endpoint that allows more, the person's own choice returns, or the endpoint's first method (kit:
    // from prod back to dev, "Token in the file" again) — not the bridge prod forced.
    const offered = this.methods().offered;
    const chosen = this.chosen();
    const next = chosen && offered.some((m) => m.id === chosen) ? chosen : offered[0]?.id ?? null;
    if (next !== this.method()) this.method.set(next);
    this.emit();
  }

  protected onMethod(id: ClientMethodId): void {
    this.method.set(id);
    this.chosen.set(id);
    this.emit();
  }

  protected onTokenName(e: Event): void {
    this.tokenName.set((e.target as HTMLInputElement).value.trim());
  }

  protected onTokenCommit(): void {
    this.emit();
  }

  protected onTtl(e: Event): void {
    this.ttlDays.set(Number((e.target as HTMLSelectElement).value));
    this.emit();
  }

  /** Re-preview: after the endpoint settles (the methods follow it) or the token name is committed. */
  emit(): void {
    queueMicrotask(() => this.requestChange.emit(this.request()));
  }
}
