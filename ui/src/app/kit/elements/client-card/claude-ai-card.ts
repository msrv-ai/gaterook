import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  numberAttribute,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { OAuthConsent, OAuthGrant, PublishPreview, TunnelState } from '../../../core/api/tunnel.api';
import { Env } from '../../../core/api/types';
import { TimeService } from '../../../core/time/time.service';
import { GwCopy } from '../../copy';
import { GwDialog, GwDialogClose } from '../../dialog';
import { GwIcon } from '../../icons';
import { GwClientMark } from '../shared/client-mark';
import { GwOAuthConsent } from './oauth-consent';
import { ClientEndpoint, activityQuery } from './client-view';
import { activeGrants, localEndpoints, tunnelHistory, tunnelLook, whilePublic } from './tunnel-view';

let nextId = 0;

/**
 * `<article gwClaudeAiCard [tunnel]="t" [preview]="p" [grants]="g" …>` (client-card.html, claude.ai): the only path
 * from the internet, so the card names everything that guards it: what gets published and what stays local, the
 * three gates in order (Anthropic IP ranges — shown, switched only in Settings › Tunnel › Callers —, each sign-in
 * allowed on this computer, the endpoint's own modes), when the tunnel was public, and every sign-in you allowed,
 * with Revoke (they outlive the tunnel).
 *
 * Publish never acts at once: it opens `gw-dialog--md --warning` with exactly what becomes public; Cancel has focus.
 * Turn off tunnel acts at once and has no Undo (publishing again always asks). Until the tunnel ships (`tunnel`
 * null), the card says it isn't available yet and offers nothing.
 */
@Component({
  selector: 'article[gwClaudeAiCard]',
  imports: [RouterLink, GwIcon, GwClientMark, GwCopy, GwDialog, GwDialogClose, GwOAuthConsent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card gw-client-card', '[attr.aria-labelledby]': 'uid + "-t"' },
  templateUrl: './claude-ai-card.html',
})
export class GwClaudeAiCard {
  protected readonly time = inject(TimeService);

  /** `GET /api/tunnel`; null while this build has no tunnel (404 before step K3). */
  readonly tunnel = input<TunnelState | null>(null);
  /** `POST /api/tunnel/publish/preview` for the endpoint to publish. */
  readonly preview = input<PublishPreview | null>(null);
  /** `GET /api/oauth/grants` of claude.ai. */
  readonly grants = input<readonly OAuthGrant[]>([]);
  /** The endpoints, for the published one's access and the ones that stay local. */
  readonly endpoints = input<readonly ClientEndpoint[]>([]);
  readonly level = input(3, { transform: numberAttribute });
  /** A sample sign-in request for "Preview a sign-in request" (shown only when given). */
  readonly sampleConsent = input<OAuthConsent | null>(null);
  /** Publish, Turn off or a revoke is being sent. */
  readonly busy = input(false, { transform: booleanAttribute });

  readonly publish = output<string>();
  readonly stop = output<void>();
  readonly revoke = output<OAuthGrant>();

  protected readonly uid = `ai-${++nextId}`;
  private readonly publishDialog = viewChild<GwDialog>('publishDialog');
  private readonly revokeDialog = viewChild<GwDialog>('revokeDialog');

  protected readonly t = computed(() => this.tunnel());
  protected readonly look = computed(() => tunnelLook(this.tunnel()));
  protected readonly on = computed(() => this.tunnel()?.state === 'on');
  protected readonly slug = computed(() => this.preview()?.endpoint ?? this.tunnel()?.endpoint ?? null);
  protected readonly published = computed(() => this.endpoints().find((e) => e.slug === this.slug()) ?? null);
  protected readonly readOnly = computed(() => this.published()?.access === 'read-only');
  protected readonly connectionEnvs = computed<Env[]>(() => [...new Set((this.preview()?.connections ?? []).map((c) => c.env as Env))]);
  protected readonly publicPaths = computed(() => this.preview()?.publicPaths ?? (this.slug() ? [`/mcp/${this.slug()}`, '/oauth/*', '/.well-known/oauth-*'] : []));
  protected readonly local = computed(() => {
    const p = this.preview();
    return p ? localEndpoints(p.local) : this.endpoints().filter((e) => e.slug !== this.slug()).map((e) => e.slug);
  });
  protected readonly anthropicOnly = computed(() => this.tunnel()?.callers?.anthropicOnly ?? true);
  protected readonly signedIn = computed(() => activeGrants(this.grants()));
  protected readonly history = computed(() => {
    const t = this.tunnel();
    return t ? tunnelHistory(t, this.time.minute(), (at) => this.time.relative(at)) : '';
  });
  protected readonly meanwhile = computed(() => {
    const t = this.tunnel();
    return t && !this.on() ? whilePublic(t, this.grants()) : null;
  });
  protected readonly publicUrl = computed(() => {
    const t = this.tunnel();
    const slug = this.slug();
    if (t?.publicUrl) return t.publicUrl;
    return t?.host && slug ? `https://${t.host}/mcp/${slug}` : null;
  });

  protected readonly revoking = signal<OAuthGrant | null>(null);
  protected readonly consentShown = signal<OAuthConsent | null>(null);
  protected readonly activity = activityQuery;

  protected askPublish(opener: HTMLElement): void {
    this.publishDialog()?.open(opener);
  }

  protected confirmPublish(): void {
    const slug = this.slug();
    if (!slug || this.preview()?.refusal) return;
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

  protected previewConsent(): void {
    this.consentShown.set(this.sampleConsent());
  }
}
