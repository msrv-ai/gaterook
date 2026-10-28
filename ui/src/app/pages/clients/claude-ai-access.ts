import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, model, output, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OAuthGrant, PublishPreview, TunnelState } from '../../core/api/tunnel.api';
import { TimeService } from '../../core/time/time.service';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { ClientEndpoint, activityQuery } from '../../kit/elements/client-card/client-view';
import { GwClientDisclosure } from '../../kit/elements/client-card/client-disclosure';
import { activeGrants, localEndpoints, oauthClientLabel } from '../../kit/elements/client-card/tunnel-view';
import { GwClientMark } from '../../kit/elements/shared/client-mark';
import { GwIcon } from '../../kit/icons';
import { allowedAt, connectionEnvs, lastCallRow, providerName, tunnelRow } from './clients-view';

/**
 * `<article gwClaudeAiAccess …>` (pages/clients.html, "In the browser"): claude.ai is the only way in from the
 * internet, so the card names everything that guards it — what gets published and what stays local, the three gates
 * (Anthropic IP ranges, shown here and switched only in Settings › Tunnel › Callers; each new sign-in allowed on this
 * computer; the endpoint's own modes), when the tunnel was public, the last call, and every sign-in you allowed, with
 * Revoke (they outlive the tunnel).
 *
 * Publish never acts at once: `gw-dialog--md --warning` lists exactly what becomes public; Cancel has focus. Turn off
 * tunnel acts at once and has no Undo: publishing again always asks. Until the tunnel ships (step K3; `tunnel` null)
 * the card says it isn't available yet and offers nothing. The kit's gate 1 ("other callers never reach the URL") is
 * worded per ARCHITECTURE.md §7.3 / §8.6.4: the sign-in page opens in the person's own browser.
 */
@Component({
  selector: 'article[gwClaudeAiAccess]',
  imports: [RouterLink, GwIcon, GwClientMark, GwDialog, GwDialogClose, GwClientDisclosure],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card gw-client-card', '[class.gw-client-card--compact]': 'collapsible()', '[attr.aria-labelledby]': '"cl-ai-t"' },
  templateUrl: './claude-ai-access.html',
})
export class GwClaudeAiAccess {
  protected readonly time = inject(TimeService);
  readonly collapsible = input(false, { transform: booleanAttribute });
  readonly expanded = model(false);

  /** `GET /api/tunnel`; null while this build has no tunnel. */
  readonly tunnel = input<TunnelState | null>(null);
  /** The endpoint the card is about (the tunnel's, else the one marked published). */
  readonly endpoint = input<string | null>(null);
  /** `POST /api/tunnel/publish/preview` for it. */
  readonly preview = input<PublishPreview | null>(null);
  /** `GET /api/oauth/grants`. */
  readonly grants = input<readonly OAuthGrant[]>([]);
  readonly endpoints = input<readonly ClientEndpoint[]>([]);
  /** Publish or Turn off is on its way. */
  readonly busy = input(false, { transform: booleanAttribute });
  /**
   * The page's state (kit clients.html `?state=`): in first run there is no endpoint to publish, so Publish waits with
   * why; in setup nothing was published yet and Publish is secondary. Either only while the tunnel is off and nobody
   * signed in: anything else is the card in use.
   */
  readonly stage = input<'in-use' | 'setup' | 'first-run'>('in-use');

  readonly publish = output<string>();
  readonly stop = output<void>();
  readonly revoke = output<OAuthGrant>();

  private readonly publishDialog = viewChild<GwDialog>('publishDialog');
  private readonly revokeDialog = viewChild<GwDialog>('revokeDialog');

  protected readonly t = computed(() => this.tunnel());
  protected readonly on = computed(() => this.tunnel()?.state === 'on');
  protected readonly look = computed(() => {
    const t = this.tunnel();
    switch (t?.state) {
      case 'on':
        return { tone: 'warning', text: t.endpoint ? `Public · ${t.endpoint}` : 'Tunnel on' };
      case 'starting':
        return { tone: 'info', text: 'Starting tunnel' };
      case 'error':
        return { tone: 'danger', text: 'Tunnel error' };
      case 'off':
        return { tone: 'neutral', text: 'Tunnel off' };
      default:
        return { tone: 'neutral', text: 'Not available yet' };
    }
  });
  protected readonly slug = computed(() => this.preview()?.endpoint ?? this.endpoint());
  protected readonly info = computed(() => this.endpoints().find((e) => e.slug === this.slug()) ?? null);
  protected readonly readOnly = computed(() => this.info()?.access === 'read-only');
  protected readonly connections = computed(() => this.preview()?.connections ?? []);
  protected readonly envs = computed(() => connectionEnvs(this.preview()));
  /** The other endpoints, which stay local: the preview's `/mcp/<slug>` paths (the core words the app's own line). */
  protected readonly local = computed(() => {
    const p = this.preview();
    if (!p) return this.endpoints().filter((e) => e.slug !== this.slug()).map((e) => e.slug);
    return localEndpoints(p.local);
  });
  /**
   * The core's cautions before publishing, except the two the dialog already says in its rows: who can call
   * (Callers) and a quick tunnel's new address (Public URL).
   */
  protected readonly warnings = computed(() =>
    (this.preview()?.warnings ?? []).filter((w) => !/^Callers:/.test(w) && !/new address each time/i.test(w)),
  );
  protected readonly publicPaths = computed(() => this.preview()?.publicPaths ?? (this.slug() ? [`/mcp/${this.slug()}`, '/oauth/*', '/.well-known/oauth-*'] : []));
  protected readonly anthropicOnly = computed(() => this.tunnel()?.callers?.anthropicOnly ?? true);
  protected readonly signedIn = computed(() => activeGrants(this.grants()));
  protected readonly tunnelText = computed(() => {
    const t = this.tunnel();
    return t ? tunnelRow(t, this.time.minute(), (at) => this.time.relative(at)) : '';
  });
  protected readonly lastCall = computed(() => {
    const t = this.tunnel();
    return t ? lastCallRow(t, this.grants(), this.time.minute()) : null;
  });
  protected readonly provider = computed(() => providerName(this.tunnel()?.provider ?? null));
  /** The public URL: the tunnel's while it is on; a named tunnel's stable address before it starts. */
  protected readonly publicUrl = computed(() => {
    const t = this.tunnel();
    const slug = this.slug();
    if (t?.publicUrl) return t.publicUrl;
    return t?.host && slug ? `https://${t.host}/mcp/${slug}` : null;
  });
  protected readonly refusal = computed(() => this.preview()?.refusal ?? null);
  protected readonly canPublish = computed(() => !!this.preview() && !this.refusal() && !this.busy() && this.tunnel()?.state !== 'starting');

  /** The quiet card of first run or setup, or `in-use`: the whole card. */
  protected readonly quiet = computed<'in-use' | 'setup' | 'first-run'>(() => {
    const stage = this.stage();
    const t = this.tunnel();
    if (stage === 'in-use' || !t || t.state !== 'off' || this.signedIn().length) return 'in-use';
    return stage === 'setup' && !this.slug() ? 'in-use' : stage;
  });
  /** The Tunnel row while nothing was ever published (kit: "Never published"). */
  protected readonly neverText = computed(() => (this.tunnel()?.history.length ? this.tunnelText() : 'Never published'));

  protected readonly revoking = signal<OAuthGrant | null>(null);
  protected readonly activity = activityQuery;
  protected readonly oauthClientLabel = oauthClientLabel;
  protected readonly allowedAt = (at: string) => allowedAt(at, this.time.minute());

  protected askPublish(opener: HTMLElement): void {
    if (this.tunnel()?.state === 'starting' || this.busy()) return;
    this.publishDialog()?.open(opener);
  }

  protected confirmPublish(): void {
    const slug = this.slug();
    if (!slug || !this.canPublish()) return;
    this.publish.emit(slug);
    this.publishDialog()?.close('publish');
  }

  protected askRevoke(g: OAuthGrant, opener: HTMLElement): void {
    this.revoking.set(g);
    this.revokeDialog()?.openAfterRender(opener);
  }

  protected confirmRevoke(): void {
    const g = this.revoking();
    if (!g) return;
    this.revoke.emit(g);
    this.revokeDialog()?.close('revoke');
  }
}
