import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { JsonObject, ToolMode } from '../../core/api/types';
import { DesktopBridge, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { EndpointShareConfirmation, EndpointShareFiles, EndpointShareImport, EndpointShareResult, EndpointShareReview } from '../../core/desktop/endpoint-share';
import { ConnectionsStore } from '../../core/stores/connections.store';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { GwCodeBlock } from '../../code/code-block';
import { GwIcon } from '../../kit/icons';
import { GwToggle } from '../../kit/disclosure';
import { slugError } from '../endpoints/endpoints-view';

/** A file open only selects a source. Decryption, review and the final import are separate user actions. */
@Component({
  selector: 'gw-endpoint-import-page',
  imports: [RouterLink, GwIcon, GwCodeBlock, GwToggle],
  templateUrl: './endpoint-import.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--narrow' },
})
export class EndpointImportPage {
  protected readonly desktop = inject(DesktopBridge);
  protected readonly files = inject(EndpointShareFiles);
  private readonly endpoints = inject(EndpointsStore);
  private readonly connections = inject(ConnectionsStore);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly pass = viewChild<ElementRef<HTMLInputElement>>('pass');
  private alive = true;
  private reviewRevision: number | null = null;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly invalid = signal<'pass' | 'slug' | null>(null);
  protected readonly review = signal<EndpointShareReview | null>(null);
  protected readonly result = signal<EndpointShareResult | null>(null);
  protected readonly picked = signal<ReadonlySet<string>>(new Set());
  protected readonly includeEndpoint = signal(true);
  protected readonly includeSecrets = signal(false);
  protected readonly endpointSlug = signal('');
  private readonly confirmation = signal<{ request: EndpointShareImport; details: EndpointShareConfirmation } | null>(null);
  protected readonly selectedConnections = computed(() => this.review()?.preview.connections.filter((c) => c.supported && this.picked().has(c.id)) ?? []);
  protected readonly secretCount = computed(() => this.selectedConnections().reduce((sum, c) => sum + c.secretCount, 0));
  protected readonly canImport = computed(() => !!this.review() && !this.busy() && (this.includeEndpoint() || this.selectedConnections().length > 0));
  private readonly selection = computed<EndpointShareImport | null>(() => {
    const review = this.review();
    return review ? {
      handle: review.handle,
      connectionIds: this.selectedConnections().map((c) => c.id),
      includeEndpoint: this.includeEndpoint(),
      includeSecrets: this.includeSecrets() && this.secretCount() > 0,
      ...(this.includeEndpoint() ? { endpointSlug: this.endpointSlug().trim() } : {}),
    } : null;
  });
  protected readonly confirmationDetails = computed(() => {
    const confirmation = this.confirmation();
    return confirmation && JSON.stringify(confirmation.request) === JSON.stringify(this.selection()) ? confirmation.details : null;
  });

  constructor() {
    effect(() => {
      this.files.selected();
      untracked(() => {
        this.review.set(null);
        this.result.set(null);
        this.error.set(null);
        this.invalid.set(null);
        this.picked.set(new Set());
        this.includeSecrets.set(false);
        this.confirmation.set(null);
        this.clearPass();
      });
    });
    effect(() => {
      this.selection();
      untracked(() => this.confirmation.set(null));
    });
    inject(DestroyRef).onDestroy(() => {
      this.alive = false;
      this.clearPass();
      const handle = this.review()?.handle;
      if (handle) {
        if (this.files.selected()?.revision === this.reviewRevision) this.files.clear();
        void this.desktop.discardEndpointShare(handle).catch(() => undefined);
      } else if (this.result() && this.files.selected()?.revision === this.reviewRevision) {
        this.files.clear();
      }
    });
  }

  protected async choose(): Promise<void> {
    if (this.busy() || !this.desktop.endpointShareAvailable) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const file = await this.desktop.chooseEndpointShare();
      if (file && this.alive) this.files.select(file);
    } catch (err) {
      this.error.set(desktopErrorText(err, 'The file could not be opened. Choose a .grshare file and try again.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async unlock(event: Event): Promise<void> {
    event.preventDefault();
    const selected = this.files.selected();
    if (this.busy() || !selected || !this.desktop.endpointShareAvailable) return;
    const passphrase = this.pass()?.nativeElement.value ?? '';
    if (passphrase.length < 12 || passphrase.length > 1024) {
      this.invalid.set('pass');
      this.error.set('Enter the file passphrase, between 12 and 1,024 characters.');
      this.pass()?.nativeElement.focus();
      return;
    }
    this.clearPass();
    this.error.set(null);
    this.invalid.set(null);
    this.busy.set(true);
    try {
      const review = await this.desktop.previewEndpointShare(passphrase);
      if (!this.alive || this.files.selected()?.revision !== selected.revision) {
        await this.desktop.discardEndpointShare(review.handle);
        return;
      }
      this.review.set(review);
      this.reviewRevision = selected.revision;
      this.endpointSlug.set(review.preview.suggestedSlug);
      this.picked.set(new Set(review.preview.connections.filter((c) => c.supported).map((c) => c.id)));
      this.includeEndpoint.set(true);
      this.includeSecrets.set(true);
      this.focusAfterRender('#share-review-title');
    } catch (err) {
      if (this.alive && this.files.selected()?.revision === selected.revision) this.error.set(desktopErrorText(err, 'The file could not be unlocked. Check its passphrase and try again.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected toggle(id: string, checked: boolean): void {
    this.picked.update((current) => {
      const next = new Set(current);
      if (checked) next.add(id); else next.delete(id);
      return next;
    });
  }

  protected async cancel(): Promise<void> {
    if (this.busy()) return;
    const revision = this.files.selected()?.revision;
    this.error.set(null);
    try {
      await this.desktop.discardEndpointShare(this.review()?.handle);
      if (this.alive && this.files.selected()?.revision === revision) this.files.clear();
    } catch (err) {
      if (this.alive && this.files.selected()?.revision === revision) this.error.set(desktopErrorText(err, 'The review could not be closed. Try again.'));
    }
  }

  protected async importSelected(confirm = false): Promise<void> {
    const review = this.review();
    const revision = this.files.selected()?.revision;
    if (!review || !this.canImport()) return;
    if (confirm && !this.confirmationDetails()) return;
    const endpointSlug = this.endpointSlug().trim();
    const validation = this.includeEndpoint() ? slugError(endpointSlug) : null;
    if (validation) {
      this.error.set(validation);
      this.invalid.set('slug');
      this.host.querySelector<HTMLInputElement>('#share-import-slug')?.focus();
      return;
    }
    this.error.set(null);
    this.invalid.set(null);
    this.busy.set(true);
    const selection = this.selection()!;
    try {
      const saved = await this.desktop.importEndpointShare({
        ...selection,
        ...(confirm ? { confirm: 'danger' as const } : {}),
      });
      if (!this.alive || this.files.selected()?.revision !== revision) return;
      if ('confirmationRequired' in saved) {
        this.confirmation.set({ request: selection, details: saved.confirmationRequired });
        this.focusAfterRender('#share-import-confirmation');
        return;
      }
      this.confirmation.set(null);
      this.review.set(null);
      this.result.set(saved);
      void this.endpoints.load();
      void this.connections.load();
      this.focusAfterRender('#share-import-complete');
    } catch (err) {
      if (this.alive && this.files.selected()?.revision === revision) {
        this.confirmation.set(null);
        this.review.set(null);
        this.error.set(`${desktopErrorText(err, 'The selected items could not be imported.')} Unlock the file again to review and retry.`);
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected json(value: JsonObject | null): string { return JSON.stringify(value ?? {}, null, 2); }
  protected mode(mode: ToolMode): string { return ({ OFF: 'Off', READ_ONLY: 'Read only', APPROVAL: 'Approval', BYPASS: 'Approval (shared Bypass)' })[mode]; }
  private clearPass(): void { const field = this.pass(); if (field) field.nativeElement.value = ''; }
  private focusAfterRender(selector: string): void {
    afterNextRender(() => this.host.querySelector<HTMLElement>(selector)?.focus(), { injector: this.injector });
  }
}
