import { ChangeDetectionStrategy, Component, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import { WiredConnection } from '../../core/api/endpoints.api';
import { DesktopBridge, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { GwDialog } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';

@Component({
  selector: 'gw-endpoint-share',
  imports: [GwDialog, GwIcon],
  templateUrl: './endpoint-share.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GwEndpointShare {
  readonly slug = input.required<string>();
  readonly connections = input<readonly WiredConnection[]>([]);
  readonly blocked = input(false);
  readonly showTrigger = input(true);
  protected readonly desktop = inject(DesktopBridge);
  private readonly toasts = inject(ToastService);
  private readonly dialog = viewChild.required<GwDialog>('shareDialog');
  private readonly pass = viewChild.required<ElementRef<HTMLInputElement>>('pass');
  private readonly repeat = viewChild.required<ElementRef<HTMLInputElement>>('repeat');
  protected readonly picked = signal<ReadonlySet<string>>(new Set());
  protected readonly includeSecrets = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly invalid = signal<'pass' | 'repeat' | null>(null);
  available(): boolean { return this.desktop.endpointShareAvailable && !this.blocked(); }
  unavailableReason(): string | null {
    return !this.desktop.endpointShareAvailable ? 'Available in an up-to-date GateRook desktop app' : this.blocked() ? 'Review the endpoint before sharing it' : null;
  }
  protected supported(connection: WiredConnection): boolean { return connection.type !== 'mcp-server'; }

  open(event: Event): void {
    this.openFrom(event.currentTarget as HTMLElement);
  }

  openFrom(opener: HTMLElement | null): void {
    if (!this.available() || this.busy()) return;
    this.clear();
    this.picked.set(new Set(this.connections().filter((c) => this.supported(c)).map((c) => c.name)));
    this.includeSecrets.set(false);
    this.dialog().open(opener);
  }

  protected toggle(name: string, checked: boolean): void {
    this.picked.update((current) => {
      const next = new Set(current);
      if (checked) next.add(name); else next.delete(name);
      return next;
    });
  }

  protected clear(): void {
    this.pass().nativeElement.value = '';
    this.repeat().nativeElement.value = '';
    this.error.set(null);
    this.invalid.set(null);
  }

  protected cancel(): void {
    if (!this.busy()) this.dialog().close();
  }

  protected guardCancel(event: Event): void {
    if (this.busy()) event.preventDefault();
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.available() || this.busy()) return;
    const passphrase = this.pass().nativeElement.value;
    if (passphrase.length < 12 || passphrase.length > 1024) {
      this.fail('pass', 'Use a passphrase between 12 and 1,024 characters.');
      return;
    }
    if (this.repeat().nativeElement.value !== passphrase) {
      this.fail('repeat', 'The passphrases do not match. Enter the same passphrase again.');
      return;
    }
    const request = { endpointSlug: this.slug(), connectionNames: this.connections().filter((c) => this.supported(c) && this.picked().has(c.name)).map((c) => c.name), includeSecrets: this.includeSecrets() };
    this.clear();
    this.busy.set(true);
    try {
      const result = await this.desktop.createEndpointShare(request, passphrase);
      if (result.cancelled) return;
      this.dialog().close('saved');
      this.toasts.show({ tone: 'success', title: 'Endpoint share saved', body: `${result.fileName ?? 'The encrypted file'} is ready to share. Send the passphrase separately.` });
    } catch (err) {
      this.error.set(desktopErrorText(err, 'The share file could not be saved. Enter the passphrase and try again.'));
    } finally {
      this.busy.set(false);
    }
  }

  private fail(field: 'pass' | 'repeat', message: string): void {
    this.invalid.set(field);
    this.error.set(message);
    (field === 'pass' ? this.pass() : this.repeat()).nativeElement.focus();
  }
}
