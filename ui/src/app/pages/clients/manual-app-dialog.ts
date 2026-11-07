import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ClientCard, ManualAppResult, ManualClientId } from '../../core/api/clients.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { DEFAULT_TOKEN_TTL_DAYS, TOKEN_TTL_DAYS } from '../../core/api/token-lifetime';
import { TimeService } from '../../core/time/time.service';
import { GwCopy } from '../../kit/copy';
import { GwDialog } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { errorSentence } from './clients-view';
import { ClientsData } from './clients.data';

/** A named manual app and its one-time MCP credentials, using the kit's guarded token dialog. */
@Component({
  selector: 'gw-manual-app-dialog',
  imports: [RouterLink, GwCopy, GwDialog, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './manual-app-dialog.html',
})
export class ManualAppDialog {
  readonly endpoints = input.required<readonly EndpointSummary[]>();
  readonly defaultTtlDays = input(DEFAULT_TOKEN_TTL_DAYS);
  readonly saved = output<ClientCard>();
  private readonly data = inject(ClientsData);
  protected readonly time = inject(TimeService);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly injector = inject(Injector);
  private readonly dialog = viewChild.required(GwDialog);
  protected readonly target = signal<ClientCard | null>(null);
  protected readonly name = signal('');
  protected readonly endpoint = signal('');
  protected readonly ttl = signal(DEFAULT_TOKEN_TTL_DAYS);
  protected readonly lifetimes = TOKEN_TTL_DAYS;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly attempted = signal(false);
  protected readonly result = signal<ManualAppResult | null>(null);
  protected readonly stored = signal(false);
  protected readonly selected = computed(() => this.endpoints().find((e) => e.slug === this.endpoint()) ?? null);
  protected readonly endpointReason = computed(() => {
    const e = this.selected();
    if (!e) return 'Create an endpoint first to connect your AI app.';
    return e.blockedReason ?? (e.status === 'disabled' ? 'Turn on this endpoint before connecting your AI app.' : null);
  });

  constructor() {
    // An endpoint response may arrive after the person has already opened this form.
    effect(() => {
      if (this.endpoint() || this.target()) return;
      const first = this.endpoints().find((e) => !e.blockedReason && e.status !== 'disabled');
      if (first) this.endpoint.set(first.slug);
    });
  }

  open(opener?: HTMLElement | null, card: ClientCard | null = null): void {
    this.target.set(card);
    this.name.set(card?.name ?? '');
    this.endpoint.set(card?.links[0]?.endpoint ?? this.endpoints().find((e) => !e.blockedReason && e.status !== 'disabled')?.slug ?? '');
    this.ttl.set(this.defaultTtlDays());
    this.error.set(null);
    this.attempted.set(false);
    this.stored.set(false);
    this.result.set(null);
    this.dialog().openAfterRender(opener);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    if (this.result()) {
      if (this.stored()) this.dialog().close('done');
      return;
    }
    this.attempted.set(true);
    if (!this.name().trim() || this.endpointReason()) {
      this.host.querySelector<HTMLElement>('#manual-app-name')?.focus();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const target = this.target();
      const result = target
        ? await this.data.renewManual(target.client as ManualClientId, this.ttl())
        : await this.data.addManual({ name: this.name().trim(), endpoint: this.endpoint(), ttlDays: this.ttl() });
      this.result.set(result);
      this.saved.emit(result.client);
      afterNextRender(() => this.host.querySelector<HTMLElement>('[data-token-copy]')?.focus(), { injector: this.injector });
    } catch (e) {
      this.error.set(errorSentence(e));
    } finally {
      this.busy.set(false);
    }
  }

  protected cancel(event?: Event): void {
    if (this.busy() || (this.result() && !this.stored())) {
      event?.preventDefault();
      return;
    }
    if (!event) this.dialog().close();
  }

  protected closed(): void {
    // A token is never retained in a closed dialog or recovered from a later list response.
    this.result.set(null);
    this.stored.set(false);
  }
}
