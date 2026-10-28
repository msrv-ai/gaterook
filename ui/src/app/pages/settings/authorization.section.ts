import { ChangeDetectionStrategy, Component, Injector, afterNextRender, computed, inject, linkedSignal, signal, viewChild } from '@angular/core';
import { FormField, form } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { OAuthGrant } from '../../core/api/tunnel.api';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { oauthClientLabel } from '../../kit/elements/client-card/tunnel-view';
import { GwHowItWorks, GwHowItWorksStep } from '../../kit/elements/how-it-works/how-it-works';
import { GwSaveBar } from '../../kit/elements/settings-layout/settings-layout';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { SettingsData } from './settings.data';
import { Registration, TOKEN_TTL_DAYS, dayAt, daysText, expiresText, expiringTokens, formSavedToast, numOf, registrationOf, savedToast } from './settings-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Settings › Authorization (design/pages/settings.html `#settings-authorization`): how clients prove who they are.
 *
 * - How authorization works (four steps, `--vertical`, folded state remembered).
 * - MCP access applies at once: the default lifetime of new named tokens (`authorization.tokenTtlDays`), the IP
 *   allowlist as a server-mode fact, and a warning for named tokens that run out within 7 days.
 * - OAuth 2.1 is a Save form: client registration CIMD or CIMD + DCR (`authorization.clientRegistration`;
 *   picking DCR shows its warning).
 * - Signed in applies at once: the OAuth sign-ins you allowed (`/api/oauth/grants`, K3), Revoke asks first
 *   (`gw-dialog--sm --danger`, Cancel has focus) and focus goes to the empty text when the last one goes.
 * - Sign-in to the UI: server mode only, a read-only preview.
 *
 * The kit's "Preview the consent screen" is not offered: a sign-in request is shown when one really comes (ARCHITECTURE.md §8.6, "Settings (U14)").
 */
@Component({
  selector: 'section[gwAuthorizationSettings]',
  imports: [RouterLink, FormField, GwIcon, GwDialog, GwDialogClose, GwHowItWorks, GwHowItWorksStep, GwSaveBar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-authorization', 'aria-labelledby': 'st-auth-t' },
  templateUrl: './authorization.section.html',
})
export class GwAuthorizationSettings {
  protected readonly data = inject(SettingsData);
  protected readonly time = inject(TimeService);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);

  private readonly revokeDialog = viewChild.required<GwDialog>('revokeDialog');

  private readonly values = computed(() => this.data.section('authorization')?.values);
  protected readonly ttlOptions = TOKEN_TTL_DAYS;
  protected readonly daysText = daysText;
  protected readonly ttl = computed(() => numOf(this.values(), 'tokenTtlDays', 7));
  protected readonly ttlBusy = signal(false);

  /** An endpoint that has tokens, for "See the tokens of endpoint dev". */
  protected readonly tokensEndpoint = computed(() => (this.data.endpoints().value ?? [])[0]?.slug ?? null);
  protected readonly expiring = computed(() => expiringTokens(this.data.tokens(), this.time.minute()));
  protected readonly expiresText = (at: string) => expiresText(at, this.time.minute());

  private readonly savedRegistration = computed(() => registrationOf(this.values()));
  /** Follows the saved value until the person picks another card (a reload never overwrites the choice). */
  protected readonly oauthModel = linkedSignal<Registration, { registration: Registration }>({
    source: this.savedRegistration,
    computation: (saved, previous) => (previous && previous.value.registration !== previous.source ? previous.value : { registration: saved }),
  });
  protected readonly oauthForm = form(this.oauthModel, () => undefined, { injector: this.injector });
  protected readonly oauthDirty = computed(() => this.oauthModel().registration !== this.savedRegistration());
  protected readonly dcr = computed(() => this.oauthModel().registration === 'cimd+dcr');
  protected readonly oauthBusy = signal(false);
  protected readonly oauthError = signal<string | null>(null);

  // ── Signed in ─────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly grants = computed(() => (this.data.grants().value ?? []).filter((g) => g.ingress === 'LOCAL' && (g.state === 'active' || g.state === 'address-changed')));
  protected readonly clientLabel = oauthClientLabel;
  protected readonly revoking = signal<OAuthGrant | null>(null);
  protected readonly revokeBusy = signal(false);
  protected readonly revokeError = signal<string | null>(null);
  protected readonly allowedAt = (at: string) => dayAt(at, this.time.minute());

  /** Default token lifetime applies at once (kit: "Saved · Default token lifetime: 30 days."). */
  protected async setTtl(e: Event): Promise<void> {
    const select = e.target as HTMLSelectElement;
    const days = Number(select.value);
    if (this.ttlBusy()) {
      select.value = String(this.ttl());
      return;
    }
    this.ttlBusy.set(true);
    try {
      await this.data.save('authorization', { tokenTtlDays: days });
      this.toasts.show(savedToast('Default token lifetime', daysText(days)));
    } catch (err) {
      select.value = String(this.ttl());
      this.toasts.show({ tone: 'danger', title: 'Default token lifetime wasn’t saved', body: this.errorText(err) });
    } finally {
      this.ttlBusy.set(false);
    }
  }

  protected async saveOauth(e: Event): Promise<void> {
    e.preventDefault();
    if (this.oauthBusy() || !this.oauthDirty()) return;
    this.oauthBusy.set(true);
    this.oauthError.set(null);
    try {
      await this.data.save('authorization', { clientRegistration: this.oauthModel().registration });
      this.toasts.show(formSavedToast('OAuth settings'));
    } catch (err) {
      this.oauthError.set(this.errorText(err));
      this.announcer.announce('OAuth settings weren’t saved');
    } finally {
      this.oauthBusy.set(false);
    }
  }

  /** Discard: the saved value comes back from the model (the form's own reset would bring back the markup's). */
  protected discardOauth(e: Event): void {
    e.preventDefault();
    this.oauthModel.set({ registration: this.savedRegistration() });
    this.oauthError.set(null);
  }

  protected askRevoke(g: OAuthGrant, e: Event): void {
    this.revoking.set(g);
    this.revokeError.set(null);
    this.revokeBusy.set(false);
    this.revokeDialog().openAfterRender(e.currentTarget as HTMLElement);
  }

  protected async confirmRevoke(): Promise<void> {
    const g = this.revoking();
    if (!g || this.revokeBusy()) return;
    this.revokeBusy.set(true);
    this.revokeError.set(null);
    try {
      await this.data.revokeGrant(g.id);
      const dialog = this.revokeDialog();
      const empty = this.grants().length === 0;
      // The Revoke button is gone: focus goes to the next one, or to the empty text.
      if (empty) dialog.returnTo = null;
      dialog.close('revoke');
      // The empty text is drawn by the render this change starts.
      if (empty) afterNextRender(() => document.getElementById('st-grants-empty')?.focus(), { injector: this.injector });
      const client = oauthClientLabel(g);
      this.toasts.show({ tone: 'success', title: `${client} access to ${g.endpoint ?? 'a removed endpoint'} revoked`, body: `${client} has to sign in again, and you allow it here.` });
    } catch (err) {
      this.revokeError.set(this.errorText(err));
    } finally {
      this.revokeBusy.set(false);
    }
  }

  private errorText(e: unknown): string {
    if (isApiError(e) && e.status === 409) return 'These settings changed meanwhile, in another window. Check them, then save again.';
    return isApiError(e) ? e.detail : NO_ANSWER;
  }
}
