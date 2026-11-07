import { ChangeDetectionStrategy, Component, computed, inject, output, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ConnectionRow } from '../../core/api/connections.api';
import { ConnectionPreview, EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';

let nextId = 0;

/** Overview's service chooser and exact-endpoint review. Only the core decides eligible tools and modes. */
@Component({
  selector: 'gw-overview-setup',
  imports: [RouterLink, GwDialog, GwDialogClose, GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-setup.html',
})
export class GwOverviewSetup {
  private readonly api = inject(EndpointsApi);
  private readonly toasts = inject(ToastService);
  private readonly picker = viewChild.required<GwDialog>('picker');
  private readonly review = viewChild.required<GwDialog>('review');
  readonly added = output<string>();
  readonly mcpRequested = output<{ readonly source: 'url'; readonly opener: HTMLElement | null }>();
  protected readonly uid = `overview-setup-${++nextId}`;
  protected readonly endpoint = signal<EndpointSummary | null>(null);
  protected readonly unconnectedCount = signal(0);
  protected readonly connection = signal<ConnectionRow | null>(null);
  protected readonly preview = signal<ConnectionPreview | null>(null);
  protected readonly pending = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly changed = signal(false);
  protected readonly refusal = computed(() => this.preview()?.refusal ?? this.endpointReason(this.endpoint()));
  protected readonly modes = computed(() => {
    const tools = this.preview()?.tools ?? [];
    return [
      { mode: 'READ_ONLY', label: 'Read only' },
      { mode: 'APPROVAL', label: 'Approval' },
      { mode: 'BYPASS', label: 'Bypass' },
      { mode: 'OFF', label: 'Off' },
    ].map((item) => ({ ...item, count: tools.filter((tool) => tool.mode === item.mode).length })).filter((item) => item.count > 0);
  });
  private generation = 0;

  showPicker(endpoint: EndpointSummary | null, connections: readonly ConnectionRow[], opener: HTMLElement | null): void {
    ++this.generation;
    this.endpoint.set(endpoint);
    this.unconnectedCount.set(endpoint ? connections.filter((row) => !row.usedBy.includes(endpoint.slug)).length : 0);
    this.picker().openAfterRender(opener);
  }

  protected openMcpUrl(): void {
    const picker = this.picker();
    const opener = picker.returnTo;
    picker.returnTo = null;
    picker.close('next');
    this.mcpRequested.emit({ source: 'url', opener });
  }

  showConnection(connection: ConnectionRow, endpoint: EndpointSummary, opener: HTMLElement | null): void {
    const generation = ++this.generation;
    this.endpoint.set(endpoint);
    this.connection.set(connection);
    this.preview.set(null);
    this.changed.set(false);
    this.error.set(this.endpointReason(endpoint) ?? this.connectionReason(connection) ?? (connection.usedBy.includes(endpoint.slug) ? `Already connected to ${endpoint.slug}.` : null));
    this.pending.set(!this.error());
    this.review().openAfterRender(opener);
    if (this.error()) return;
    void this.api.previewConnection(endpoint.slug, connection.name).then(
      (preview) => { if (generation === this.generation) this.preview.set(preview); },
      (error) => { if (generation === this.generation) this.error.set(failureText(error)); },
    ).finally(() => { if (generation === this.generation) this.pending.set(false); });
  }

  protected async add(): Promise<void> {
    const endpoint = this.endpoint();
    const connection = this.connection();
    const preview = this.preview();
    if (!endpoint || !connection || !preview || this.pending() || this.refusal() || this.error()) return;
    this.pending.set(true);
    this.saving.set(true);
    this.changed.set(false);
    try {
      // Recheck the scope before granting access; if it changed, ask the user to review it again.
      const current = await this.api.previewConnection(endpoint.slug, connection.name);
      this.preview.set(current);
      if (current.refusal) return;
      if (JSON.stringify(current) !== JSON.stringify(preview)) {
        this.changed.set(true);
        return;
      }
      await this.api.addConnection(endpoint.slug, connection.name);
      this.review().close('added');
      this.added.emit(endpoint.slug);
      this.toasts.show({ tone: 'success', title: 'Service connected', body: `${connection.name} is connected to ${endpoint.slug}.` });
    } catch (error) {
      this.error.set(failureText(error));
    } finally {
      this.pending.set(false);
      this.saving.set(false);
    }
  }

  protected modeLabel(mode: string): string {
    return ({ READ_ONLY: 'Read only', APPROVAL: 'Approval', BYPASS: 'Bypass', OFF: 'Off' } as Record<string, string>)[mode] ?? mode;
  }

  private endpointReason(endpoint: EndpointSummary | null): string | null {
    if (!endpoint) return 'Choose or create an endpoint to connect an existing service.';
    if (endpoint.blockedReason) return endpoint.blockedReason;
    return endpoint.status === 'disabled' ? `Turn on ${endpoint.slug} before connecting a service.` : null;
  }

  private connectionReason(connection: ConnectionRow): string | null {
    if (connection.setupRequired) return 'Finish setting up this service in Connections first.';
    return !connection.verified ? 'Verify this service in Connections first.' : null;
  }
}

function failureText(error: unknown): string {
  return isApiError(error) ? error.detail : error instanceof Error ? error.message : 'The service could not be checked. Try again.';
}
