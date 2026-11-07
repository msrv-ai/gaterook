import { ChangeDetectionStrategy, Component, Injectable, Injector, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { ConnectionsApi } from '../core/api/connections.api';
import { EndpointsApi } from '../core/api/endpoints.api';
import { isApiError } from '../core/api/api-error';
import { ToastService } from './toast.service';

/** Shares pending state when one connection appears under several endpoints. */
@Injectable({ providedIn: 'root' })
export class AccessChanges {
  readonly pending = signal<ReadonlySet<string>>(new Set());
}

let nextId = 0;

/** Immediate user access control, composed from the canonical gw-switch. */
@Component({
  selector: 'gw-access-switch',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-switch' },
  template: `
    <input class="gw-switch__input" type="checkbox" role="switch" [id]="id" [checked]="checked()"
      [attr.aria-label]="kind() === 'endpoint' ? 'Access through endpoint ' + name() : 'Access to connection ' + name() + (pausedByEndpoint() ? ' through endpoint ' + pausedByEndpoint() : ' across all endpoints')"
      [attr.aria-describedby]="id + '-hint'" [disabled]="busy()" [attr.aria-disabled]="unavailable() ? 'true' : null"
      [attr.aria-busy]="busy() ? 'true' : null" (change)="toggle($event)">
    <span class="gw-switch__control" aria-hidden="true"></span>
    <label class="gw-switch__label" [class.gw-sr-only]="compact()" [for]="id">{{ checked() ? 'On' : 'Off' }}</label>
    <span class="gw-sr-only" [id]="id + '-hint'">{{ unavailable() || hint() }}{{ busy() ? ' Saving access setting.' : '' }}</span>
  `,
})
export class GwAccessSwitch {
  readonly kind = input.required<'endpoint' | 'connection'>();
  readonly name = input.required<string>();
  readonly enabled = input(true);
  /** Compact map rows provide the visible On/Off caption beside this control. */
  readonly compact = input(false);
  /** Access may be blocked even while the saved global setting remains On. */
  readonly forceOff = input(false);
  readonly blocked = input<string | null>(null);
  /** Effective access in an endpoint view; the connection's global setting is kept. */
  readonly pausedByEndpoint = input<string | null>(null);
  readonly changed = output<boolean>();
  protected readonly id = `access-${++nextId}`;
  protected readonly value = linkedSignal(() => this.enabled());
  protected readonly checked = computed(() => this.value() && !this.pausedByEndpoint() && !this.forceOff());
  protected readonly unavailable = computed(() => this.blocked() || (this.pausedByEndpoint()
    ? `Endpoint ${this.pausedByEndpoint()} is off. Enable the endpoint to restore access here. The saved connection setting is ${this.value() ? 'On' : 'Off'}; access through other endpoints is unchanged.`
    : this.forceOff() ? 'Access is blocked. Review this connection before enabling it.' : null));
  private readonly changes = inject(AccessChanges);
  private readonly injector = inject(Injector);
  private readonly toasts = inject(ToastService);
  protected readonly key = computed(() => `${this.kind()}:${this.name()}`);
  protected readonly busy = computed(() => this.changes.pending().has(this.key()));
  protected readonly hint = computed(() => this.kind() === 'connection'
    ? 'Applies to this connection across all endpoints. When off, AI can still see it and ask you to enable access.'
    : 'Applies to all connections through this endpoint. When off, AI can still see its tools and ask you to enable access.');

  protected async toggle(event: Event): Promise<void> {
    const control = event.target as HTMLInputElement;
    const enabled = control.checked;
    control.checked = this.checked();
    if (this.busy() || this.unavailable()) return;
    const key = this.key();
    const name = this.name();
    this.changes.pending.update((pending) => new Set([...pending, key]));
    try {
      if (this.kind() === 'endpoint') {
        const api = this.injector.get(EndpointsApi);
        await (enabled ? api.enable(name) : api.disable(name));
      } else {
        await this.injector.get(ConnectionsApi).setEnabled(name, enabled);
      }
      // A reused edit page may now show another connection while this request completes.
      if (this.key() !== key) return;
      this.value.set(enabled);
      this.changed.emit(enabled);
      this.toasts.show({ tone: 'neutral', title: `${this.kind() === 'endpoint' ? 'Endpoint' : 'Connection'} ${name} ${enabled ? 'enabled' : 'disabled'}`,
        body: this.kind() === 'connection' ? 'Applies across all endpoints. Tool modes and credentials are kept.' : 'Tool modes, tokens and settings are kept.' });
    } catch (error) {
      this.toasts.show({ tone: 'danger', title: `Could not ${enabled ? 'enable' : 'disable'} ${name}`,
        body: isApiError(error) ? error.detail : 'Refresh to check the current access setting, then try again.' });
    } finally {
      control.checked = this.checked();
      this.changes.pending.update((pending) => new Set([...pending].filter((item) => item !== key)));
    }
  }
}
