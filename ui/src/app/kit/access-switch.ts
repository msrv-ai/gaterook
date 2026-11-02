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
    <input class="gw-switch__input" type="checkbox" role="switch" [id]="id" [checked]="value()"
      [attr.aria-label]="kind() === 'endpoint' ? 'Access through endpoint ' + name() : 'Access to connection ' + name() + ' across all endpoints'"
      [attr.aria-describedby]="id + '-hint'" [disabled]="busy()" [attr.aria-disabled]="blocked() ? 'true' : null"
      [attr.aria-busy]="busy() ? 'true' : null" (change)="toggle($event)">
    <span class="gw-switch__control" aria-hidden="true"></span>
    <label class="gw-switch__label" [for]="id">{{ value() ? 'On' : 'Off' }}</label>
    <span class="gw-sr-only" [id]="id + '-hint'">{{ blocked() || hint() }}{{ busy() ? ' Saving access setting.' : '' }}</span>
  `,
})
export class GwAccessSwitch {
  readonly kind = input.required<'endpoint' | 'connection'>();
  readonly name = input.required<string>();
  readonly enabled = input(true);
  readonly blocked = input<string | null>(null);
  readonly changed = output<boolean>();
  protected readonly id = `access-${++nextId}`;
  protected readonly value = linkedSignal(() => this.enabled());
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
    control.checked = this.value();
    if (this.busy() || this.blocked()) return;
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
      this.value.set(enabled);
      this.changed.emit(enabled);
      this.toasts.show({ tone: 'neutral', title: `${this.kind() === 'endpoint' ? 'Endpoint' : 'Connection'} ${name} ${enabled ? 'enabled' : 'disabled'}`,
        body: this.kind() === 'connection' ? 'Applies across all endpoints. Tool modes and credentials are kept.' : 'Tool modes, tokens and settings are kept.' });
    } catch (error) {
      this.toasts.show({ tone: 'danger', title: `Could not ${enabled ? 'enable' : 'disable'} ${name}`,
        body: isApiError(error) ? error.detail : 'Refresh to check the current access setting, then try again.' });
    } finally {
      control.checked = this.value();
      this.changes.pending.update((pending) => new Set([...pending].filter((item) => item !== key)));
    }
  }
}
