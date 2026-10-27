import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, linkedSignal, signal, viewChild } from '@angular/core';
import { FormField, form } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { PublishPreview } from '../../core/api/tunnel.api';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwCopy } from '../../kit/copy';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { localEndpoints } from '../../kit/elements/client-card/tunnel-view';
import { GwHowItWorks, GwHowItWorksStep } from '../../kit/elements/how-it-works/how-it-works';
import { GwSaveBar } from '../../kit/elements/settings-layout/settings-layout';
import { GwIcon } from '../../kit/icons';
import { GwReveal } from '../../kit/reveal';
import { ToastService } from '../../kit/toast.service';
import { SettingsData } from './settings.data';
import { ProviderCard, formSavedToast, providerCard, providerName, providerOf, savedToast, todayText, tunnelCalls, tunnelLook } from './settings-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Settings › Tunnel (design/pages/settings.html `#settings-tunnel`): the public HTTPS address claude.ai needs.
 *
 * - Status applies at once: Turn on tunnel asks first (`gw-dialog--md --warning` with what becomes public, from
 *   `POST /api/tunnel/publish/preview`; Cancel has focus), Turn off acts at once (publishing again always asks).
 * - Who gets in: the three gates, worded per ARCHITECTURE.md §8.6.4 (the sign-in page opens in your own browser, so
 *   the IP allowlist guards the MCP path, not the sign-in page).
 * - Callers is the only switch of the Anthropic IP allowlist (Clients and Authorization show it read-only and link
 *   here): turning it off asks first (`--warning`, Cancel has focus), turning it on applies at once.
 * - Provider is a Save form (Cloudflare Tunnel or ngrok); the Cloudflare card says a quick tunnel's address changes
 *   (§8.6.9). It saves with the version of Settings › Tunnel (`GET /api/settings/tunnel`; the core refuses a save
 *   without one). ngrok needs the account's authtoken: the card says "you paste its authtoken once", so choosing it
 *   shows the field (a new secret typed once, sent in the save request only, cleared after) until one is stored.
 * - What is published: the MCP path of the published endpoint and the OAuth paths; everything else stays local.
 *
 * Until K3 is merged `/api/tunnel` is 404: the section shows the same structure, says the tunnel isn't in this build,
 * and every control that would act is `aria-disabled` with that reason.
 */
@Component({
  selector: 'section[gwTunnelSettings]',
  imports: [RouterLink, FormField, GwIcon, GwCopy, GwDialog, GwDialogClose, GwReveal, GwHowItWorks, GwHowItWorksStep, GwSaveBar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-tunnel', 'aria-labelledby': 'st-tunnel-t' },
  templateUrl: './tunnel.section.html',
})
export class GwTunnelSettings {
  protected readonly data = inject(SettingsData);
  protected readonly time = inject(TimeService);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);

  private readonly publishDialog = viewChild.required<GwDialog>('publishDialog');
  private readonly anyDialog = viewChild.required<GwDialog>('anyDialog');

  protected readonly t = computed(() => this.data.tunnel().value);
  protected readonly unavailable = computed(() => this.data.tunnel().unavailable);
  protected readonly failed = computed(() => !!this.data.tunnel().error && !this.unavailable());
  /** Nothing here may act: not in this build, or not loaded. */
  protected readonly off = computed(() => !this.t());
  protected readonly look = computed(() => {
    const t = this.t();
    return t ? tunnelLook(t) : null;
  });
  protected readonly on = computed(() => this.t()?.state === 'on');
  protected readonly starting = computed(() => this.t()?.state === 'starting');
  protected readonly slug = this.data.published;
  protected readonly endpointInfo = computed(() => (this.data.endpoints().value ?? []).find((e) => e.slug === this.slug()) ?? null);
  protected readonly readOnly = computed(() => this.endpointInfo()?.access === 'read-only');
  protected readonly risky = computed(() => this.endpointInfo()?.risk.state === 'present');
  protected readonly local = computed(() => (this.data.endpoints().value ?? []).map((e) => e.slug).filter((s) => s !== this.slug()));
  protected readonly anthropicOnly = computed(() => this.t()?.callers?.anthropicOnly ?? true);
  protected readonly publicUrl = computed(() => {
    const t = this.t();
    const slug = this.slug();
    if (t?.publicUrl) return t.publicUrl;
    return t?.host && slug ? `https://${t.host}/mcp/${slug}` : null;
  });
  protected readonly today = computed(() => {
    const t = this.t();
    return t ? todayText(t, this.time.minute()) : null;
  });
  protected readonly calls = computed(() => {
    const t = this.t();
    return t ? tunnelCalls(t, this.time.minute()) : null;
  });
  protected readonly cloudflared = computed(() => this.t()?.cloudflared?.version ?? null);
  protected readonly signedIn = computed(() => (this.data.grants().value ?? []).filter((g) => g.state === 'active' || g.state === 'address-changed').length);
  protected readonly busy = signal<'publish' | 'stop' | 'callers' | null>(null);

  /** Why Turn on tunnel can't act (the id of the visible reason), or null. */
  protected readonly onWhy = computed(() => {
    if (this.unavailable() || this.failed()) return 'st-tunnel-why';
    if (!this.slug()) return 'st-tunnel-none';
    if (this.starting()) return 'st-tunnel-starting';
    return null;
  });

  // ── Publish ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly preview = signal<PublishPreview | null>(null);
  /** The other endpoints the preview keeps local (its `/mcp/<slug>` lines), or null before it answered. */
  protected readonly previewLocal = computed(() => {
    const p = this.preview();
    return p ? localEndpoints(p.local) : null;
  });
  protected readonly previewError = signal<string | null>(null);
  protected readonly publishError = signal<string | null>(null);
  protected readonly canPublish = computed(() => !!this.preview() && !this.preview()!.refusal && this.busy() === null);

  // ── Provider ──────────────────────────────────────────────────────────────────────────────────────────────────

  private readonly savedProvider = computed<ProviderCard>(() => providerCard(this.t()?.provider));
  protected readonly providerModel = linkedSignal<ProviderCard, { provider: ProviderCard }>({
    source: this.savedProvider,
    computation: (saved, previous) => (previous && previous.value.provider !== previous.source ? previous.value : { provider: saved }),
  });
  protected readonly providerForm = form(this.providerModel, () => undefined, { injector: this.injector });
  private readonly ngrokToken = viewChild<ElementRef<HTMLInputElement>>('ngrokToken');
  /** The core holds an ngrok authtoken already (Settings › Tunnel `ngrokAuthtokenStored`). */
  protected readonly ngrokStored = computed(() => this.data.tunnelSettings().value?.values['ngrokAuthtokenStored'] === true);
  /** Something typed into the authtoken field (only that it is non-empty: the value stays in the field). */
  protected readonly tokenTyped = signal(false);
  protected readonly tokenMissing = signal(false);
  protected readonly providerDirty = computed(() => this.providerModel().provider !== this.savedProvider() || this.tokenTyped());
  protected readonly providerBusy = signal(false);
  protected readonly providerError = signal<string | null>(null);
  protected readonly providerName = providerName;

  protected async askPublish(e: Event): Promise<void> {
    const slug = this.slug();
    if (!slug || this.onWhy() || this.busy()) return;
    this.preview.set(null);
    this.previewError.set(null);
    this.publishError.set(null);
    this.publishDialog().open(e.currentTarget as HTMLElement);
    try {
      this.preview.set(await this.data.publishPreview(slug));
    } catch (err) {
      this.previewError.set(this.errorText(err));
    }
  }

  protected async confirmPublish(): Promise<void> {
    const slug = this.slug();
    if (!slug || !this.canPublish()) return;
    this.busy.set('publish');
    this.publishError.set(null);
    try {
      await this.data.publish(slug);
      const dialog = this.publishDialog();
      // Turn on tunnel is gone once it is on: focus goes to Turn off.
      dialog.returnTo = null;
      dialog.close('publish');
      afterNextRender(() => document.getElementById('st-tunnel-off-btn')?.focus(), { injector: this.injector });
      const url = this.publicUrl();
      this.toasts.show({
        tone: 'warning',
        icon: 'globe',
        title: `${slug} is public`,
        body: `${this.anthropicOnly() ? 'Anthropic IP ranges' : 'Any address'} can reach ${url ?? 'its public URL'}. Add it in claude.ai under Settings, Connectors.`,
      });
    } catch (err) {
      this.publishError.set(this.errorText(err));
    } finally {
      this.busy.set(null);
    }
  }

  /** Turn off acts at once; publishing again always asks. */
  protected async stop(): Promise<void> {
    if (this.busy()) return;
    this.busy.set('stop');
    const slug = this.slug();
    try {
      await this.data.stopTunnel();
      afterNextRender(() => document.getElementById('st-tunnel-on-btn')?.focus(), { injector: this.injector });
      this.toasts.show({
        tone: 'neutral',
        icon: 'globe-lock',
        title: 'Tunnel off',
        body: `claude.ai can't reach ${slug ?? 'any endpoint'}. Its sign-in stays valid; revoke it under Signed in. Turning the tunnel on again asks you to confirm what is published.`,
      });
    } catch (err) {
      this.toasts.show({ tone: 'danger', title: 'The tunnel is still on', body: this.errorText(err) });
    } finally {
      this.busy.set(null);
    }
  }

  // ── Callers ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** Off asks first (the switch stays on until confirmed); on applies at once. */
  protected async setCallers(e: Event): Promise<void> {
    const input = e.target as HTMLInputElement;
    if (this.busy()) {
      input.checked = this.anthropicOnly();
      return;
    }
    if (!input.checked) {
      input.checked = true;
      this.anyDialog().open(input);
      return;
    }
    await this.applyCallers(true, input);
  }

  protected async acceptAny(): Promise<void> {
    const input = document.getElementById('st-anthropic-ips') as HTMLInputElement | null;
    this.anyDialog().close('accept');
    await this.applyCallers(false, input);
  }

  private async applyCallers(anthropicOnly: boolean, input: HTMLInputElement | null): Promise<void> {
    this.busy.set('callers');
    try {
      await this.data.setCallers(anthropicOnly);
      if (anthropicOnly) {
        this.toasts.show(savedToast('Only accept Anthropic IP ranges', 'on'));
      } else {
        const slug = this.slug();
        this.toasts.show({
          tone: 'warning',
          icon: 'shield-off',
          title: 'Calls from any address accepted',
          body: `Sign-in still waits for you on this computer${slug && this.readOnly() ? `, and ${slug} stays read only` : ''}. Recorded in Setup changes.`,
        });
      }
    } catch (err) {
      if (input) input.checked = this.anthropicOnly();
      this.toasts.show({ tone: 'danger', title: 'Callers weren’t changed', body: this.errorText(err) });
    } finally {
      this.busy.set(null);
    }
  }

  // ── Provider ──────────────────────────────────────────────────────────────────────────────────────────────────

  protected onTokenInput(e: Event): void {
    this.tokenTyped.set(!!(e.target as HTMLInputElement).value);
    this.tokenMissing.set(false);
  }

  protected async saveProvider(e: Event): Promise<void> {
    e.preventDefault();
    if (this.providerBusy() || !this.providerDirty() || this.off()) return;
    const ngrok = this.providerModel().provider === 'ngrok';
    const field = this.ngrokToken()?.nativeElement;
    const token = ngrok && field?.value ? field.value : null;
    if (ngrok && !token && !this.ngrokStored()) {
      this.tokenMissing.set(true);
      field?.focus();
      return;
    }
    this.providerBusy.set(true);
    this.providerError.set(null);
    try {
      await this.data.saveProvider(providerOf(this.providerModel().provider, this.t()?.provider), token);
      this.clearToken();
      this.toasts.show(formSavedToast('Provider'));
    } catch (err) {
      this.providerError.set(this.errorText(err));
      this.announcer.announce('The tunnel provider wasn’t saved');
    } finally {
      this.providerBusy.set(false);
    }
  }

  protected discardProvider(e: Event): void {
    e.preventDefault();
    this.providerModel.set({ provider: this.savedProvider() });
    this.providerError.set(null);
    this.clearToken();
  }

  /** The typed authtoken leaves the page with the save (or Discard), hidden again. */
  private clearToken(): void {
    const field = this.ngrokToken()?.nativeElement;
    if (field) {
      field.value = '';
      field.type = 'password';
    }
    this.tokenTyped.set(false);
    this.tokenMissing.set(false);
  }

  /** The core's words ("Turn the tunnel off to change its provider or address."); a stale version, the page's own. */
  private errorText(e: unknown): string {
    if (isApiError(e) && e.status === 409 && e.title === 'Changed meanwhile') return 'The tunnel changed meanwhile, in another window. Check it, then try again.';
    return isApiError(e) ? e.detail : NO_ANSWER;
  }
}
