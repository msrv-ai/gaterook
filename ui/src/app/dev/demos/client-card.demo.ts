import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ClientId, ClientLink, ConnectPreview, ConnectRequest } from '../../core/api/clients.api';
import { OAuthGrant } from '../../core/api/tunnel.api';
import { GwClaudeAiCard } from '../../kit/elements/client-card/claude-ai-card';
import { ClientPreviewRequest, GwClientCard } from '../../kit/elements/client-card/client-card';
import { GwClientConnectCard } from '../../kit/elements/client-card/client-connect-card';
import { ToastService } from '../../kit/toast.service';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';
import {
  DEMO_CLIENTS,
  DEMO_CLIENT_ENDPOINTS,
  DEMO_CLIENT_FAILURE,
  DEMO_CONSENT,
  DEMO_DISCONNECT_CODEX,
  DEMO_GRANTS,
  DEMO_PREVIEW_CODEX,
  DEMO_PREVIEW_CURSOR,
  DEMO_PUBLISH,
  DEMO_TUNNEL_OFF,
  DEMO_TUNNEL_ON,
} from '../fixtures-clients';

/** The preview the core would answer for a selection: the demo's files, re-aimed at the chosen endpoint and method. */
function demoPreview(client: ClientId, r: ConnectRequest): ConnectPreview {
  const base = client === 'codex' ? DEMO_PREVIEW_CODEX : DEMO_PREVIEW_CURSOR;
  const env = DEMO_CLIENT_ENDPOINTS.find((e) => e.slug === r.endpoint)?.env ?? 'dev';
  const swap = (s: string) => s.replaceAll('/mcp/dev', `/mcp/${r.endpoint}`).replaceAll('gaterook-dev', `gaterook-${r.endpoint}`).replaceAll('"dev"]', `"${r.endpoint}"]`).replace('cursor-laptop', r.token?.name ?? 'cursor-laptop');
  const files = base.files.map((f) => {
    const hunks = (f.diff?.hunks ?? []).map((h) => ({
      header: h.header,
      lines: h.lines.filter((l) => client === 'codex' || r.method === 'token' || !/"headers"|Authorization|^ {6}}$/.test(l.text)).map((l) => ({ ...l, text: swap(l.text) })),
    }));
    const added = hunks.reduce((n, h) => n + h.lines.filter((l) => l.type === 'added').length, 0);
    return { ...f, diff: f.diff ? { ...f.diff, added, hunks } : null };
  });
  const warnings = env === 'prod' ? [`Endpoint ${r.endpoint} reaches prod. Connect it only to the app you use for incidents; its first token in each session waits until you allow it here.`] : [];
  return { ...base, previewId: `pv_${client}_${r.endpoint}_${r.method}`, endpoint: r.endpoint, env, method: r.method, files, warnings };
}

/** Dev only: design/elements/client-card.html with the demo world's clients. */
@Component({
  selector: 'gw-client-card-demo',
  imports: [GwDemoHeader, GwClientCard, GwClientConnectCard, GwClaudeAiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="s-grid">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-grid">AI apps page</h2>
        <p class="gw-section__description">The five AI apps of the demo set. Claude Desktop reaches two endpoints through its bridge, one extension per endpoint, so it takes the full width with one row per endpoint. Codex is connected, Cursor detected, Antigravity not installed, and claude.ai has the tunnel off. In a grid Connect is a secondary button; it opens the connect dialog. Open config appears only for AI apps set up through a file. A card whose token is issued on the fly says what that protects against: the trust boundary is the OS account.</p>
      </header>
      <div class="gw-stack">
        <article gwClientCard [card]="clients.claudeDesktop" [endpoints]="endpoints" [connectPreview]="preview()" [connectLoading]="loading()" (connectStart)="reset()" (previewRequest)="previewFor($event)" (addEndpoint)="say('success', $event.endpoint + ' added to Claude Desktop', 'Claude Desktop lists its tools in the next new chat.')" (disconnectStart)="disconnectFor($event)" [disconnectPreview]="leaving()" (disconnect)="disconnected($event.link)" (removeBridge)="say('success', 'Bridge removed from Claude Desktop', 'The extension is removed and its tokens for dev and incident-prod are revoked.')"></article>
        <ul class="gw-grid gw-grid--2" aria-label="AI apps set up through a file">
          <li><article gwClientCard [card]="clients.codex" [endpoints]="endpoints" (disconnectStart)="disconnectFor($event)" [disconnectPreview]="leaving()" (disconnect)="disconnected($event.link)" (openConfig)="openConfig($event)"></article></li>
          <li><article gwClientCard [card]="clients.cursor" [endpoints]="endpoints" [connectPreview]="preview()" [connectLoading]="loading()" (connectStart)="reset()" (previewRequest)="previewFor($event)" (connect)="connected($event.client, $event.request)" (openConfig)="openConfig($event)"></article></li>
          <li><article gwClientCard [card]="clients.antigravity" [endpoints]="endpoints" (scan)="say('info', 'Checked this computer', 'Antigravity is still not installed.')"></article></li>
        </ul>
        <article gwClaudeAiCard [tunnel]="tunnelOff" [preview]="publishPreview" [grants]="grants()" [endpoints]="endpoints" [sampleConsent]="consent" (publish)="say('warning', $event + ' published', 'claude.ai can reach ' + $event + ' at the public URL. Turn the tunnel off any time; publishing again asks here first.')" (revoke)="revoked($event)"></article>
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-alone">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-alone">Detected, on its own</h2>
        <p class="gw-section__description">Where the card is the only thing to do (onboarding, the one detected AI app), Connect is the primary button.</p>
      </header>
      <div class="gw-grid gw-grid--2">
        <article gwClientCard variant="alone" [card]="clients.cursor" [endpoints]="endpoints" [connectPreview]="preview()" [connectLoading]="loading()" (connectStart)="reset()" (previewRequest)="previewFor($event)" (connect)="connected($event.client, $event.request)" (openConfig)="openConfig($event)"></article>
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-flow">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-flow">Connect flow</h2>
        <p class="gw-section__description">The connect dialog for Codex, opened inline for review: endpoint, the exact change, the backup, the core's notes, how it authenticates, the restart it needs. The entry is a block between GateRook's markers; its <code class="gw-code">http_headers_helper</code> is one command string that runs the shim <code class="gw-code">~/.gaterook/bin/gw-bridge</code>, which gets the token on the fly, so nothing secret is written. The live dialog opens from Connect on a Cursor card above.</p>
      </header>
      <section gwClientConnectCard [card]="clients.codex" [endpoints]="endpoints" [preview]="inlinePreview()" (previewRequest)="inlinePreview.set(demo($event))" (connect)="connected($event.client, $event.request)" (cancel)="say('info', 'Nothing changed', 'Connect was cancelled before anything was written.')"></section>
    </section>

    <section class="gw-section" aria-labelledby="s-restart">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-restart">Needs restart</h2>
        <p class="gw-section__description">Right after Connect, for an AI app that reads its config only when it starts. The state clears on the AI app's first call.</p>
      </header>
      <div class="gw-grid gw-grid--2">
        <article gwClientCard [card]="clients.codexRestart" [endpoints]="endpoints" backup="~/.codex/config.toml.bak-2026-10-22" (disconnectStart)="disconnectFor($event)" [disconnectPreview]="leaving()" (disconnect)="disconnected($event.link)" (openConfig)="openConfig($event)"></article>
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-error">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-error">Error: config not writable</h2>
        <p class="gw-section__description">What failed, how to fix it, and a way to try again. Nothing was written, so there is nothing to undo.</p>
      </header>
      <div class="gw-grid gw-grid--2">
        <article gwClientCard [card]="clients.cursor" [endpoints]="endpoints" [failure]="failure" [connectPreview]="preview()" [connectLoading]="loading()" (connectStart)="reset()" (previewRequest)="previewFor($event)" (connect)="connected($event.client, $event.request)" (openConfig)="openConfig($event)"></article>
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-tunnel">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-tunnel">claude.ai: tunnel on</h2>
        <p class="gw-section__description">After Publish (the confirm above). claude.ai runs in Anthropic's cloud, so it reaches the endpoint through a public URL. A quick tunnel gets a new address each time it starts; claude.ai must be pointed at the new URL. Use a named tunnel for a stable hostname. The card says exactly what is published and who may call it; the caller allowlist is shown, not switched, here: it is one switch in Settings › Tunnel › Callers. Turn off tunnel acts at once and offers no Undo, because turning it on again always goes through the publish confirm.</p>
      </header>
      <div class="gw-grid gw-grid--2">
        <article gwClaudeAiCard [tunnel]="tunnelOn" [preview]="publishPreview" [grants]="grants()" [endpoints]="endpoints" (stop)="say('success', 'Tunnel off', 'stage-readonly is local again. Publishing it again asks you to confirm first.')"></article>
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-compact">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-compact">Compact, in endpoint lists</h2>
        <p class="gw-section__description">The AI apps tab of endpoint <span class="gw-mono">dev</span>: one row per AI app, status and actions at the end. Rows wrap on a narrow window.</p>
      </header>
      <ul class="gw-stack gw-stack--sm" aria-label="AI apps of endpoint dev">
        <li gwClientCard variant="compact" endpoint="dev" [card]="clients.claudeDesktop" [endpoints]="endpoints" (disconnectStart)="disconnectFor($event)" [disconnectPreview]="leaving()" (disconnect)="disconnected($event.link)"></li>
        <li gwClientCard variant="compact" endpoint="dev" [card]="clients.codex" [endpoints]="endpoints" (disconnectStart)="disconnectFor($event)" [disconnectPreview]="leaving()" (disconnect)="disconnected($event.link)"></li>
        <li gwClientCard variant="compact" endpoint="dev" [card]="clients.cursor" [endpoints]="endpoints" [connectPreview]="preview()" [connectLoading]="loading()" (connectStart)="reset()" (previewRequest)="previewFor($event)" (connect)="connected($event.client, $event.request)"></li>
      </ul>
    </section>
  `,
})
export class ClientCardDemo {
  private readonly toasts = inject(ToastService);
  protected readonly el = kitElement('client-card');
  protected readonly clients = DEMO_CLIENTS;
  protected readonly endpoints = DEMO_CLIENT_ENDPOINTS;
  protected readonly failure = DEMO_CLIENT_FAILURE;
  protected readonly tunnelOff = DEMO_TUNNEL_OFF;
  protected readonly tunnelOn = DEMO_TUNNEL_ON;
  protected readonly publishPreview = DEMO_PUBLISH;
  protected readonly consent = DEMO_CONSENT;

  protected readonly grants = signal<OAuthGrant[]>(DEMO_GRANTS);
  protected readonly preview = signal<ConnectPreview | null>(null);
  protected readonly loading = signal(false);
  protected readonly leaving = signal<ConnectPreview | null>(null);
  protected readonly inlinePreview = signal<ConnectPreview | null>(DEMO_PREVIEW_CODEX);
  private timer: ReturnType<typeof setTimeout> | null = null;

  protected readonly demo = (r: ClientPreviewRequest): ConnectPreview => demoPreview(r.client, r.request);

  protected reset(): void {
    this.preview.set(null);
  }

  /** As the core would: a moment to read the file and build the diff. */
  protected previewFor(r: ClientPreviewRequest): void {
    this.loading.set(true);
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.preview.set(demoPreview(r.client, r.request));
      this.loading.set(false);
    }, 300);
  }

  protected connected(client: ClientId, r: ConnectRequest | null): void {
    const file = client === 'codex' ? '~/.codex/config.toml' : '~/.cursor/mcp.json';
    const body =
      r?.method === 'token'
        ? `One server added to ${file} with token ${r.token?.name}, which expires in ${r.token?.ttlDays} days. Backup kept next to the file.`
        : `One server added to ${file}. Backup kept next to the file.`;
    this.say('success', `${client === 'codex' ? 'Codex' : 'Cursor'} connected to ${r?.endpoint ?? 'dev'}`, body);
  }

  protected disconnectFor(l: ClientLink): void {
    this.leaving.set(null);
    setTimeout(() => this.leaving.set(l.client === 'codex' ? DEMO_DISCONNECT_CODEX : { ...DEMO_DISCONNECT_CODEX, client: l.client, endpoint: l.endpoint, files: [] }), 200);
  }

  protected disconnected(l: ClientLink): void {
    this.say('success', l.client === 'codex' ? 'Codex disconnected' : `Claude Desktop disconnected from ${l.endpoint}`, l.client === 'codex' ? 'The entry is removed and access codex-laptop is revoked.' : `The bridge gets no new tokens for ${l.endpoint}.`);
  }

  protected revoked(g: OAuthGrant): void {
    this.grants.update((list) => list.filter((x) => x.id !== g.id));
    this.say('success', `${g.client} access to ${g.endpoint} revoked`, `${g.client} has to sign in again, and you allow it here.`);
  }

  protected openConfig(client: ClientId): void {
    this.say('info', 'Demo', `Open config of ${client} opens the file in the desktop app.`);
  }

  protected say(tone: 'success' | 'info' | 'warning', title: string, body: string): void {
    this.toasts.show({ tone, title, body });
  }
}
