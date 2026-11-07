import { ChangeDetectionStrategy, Component, computed, inject, output, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ConnectionRow } from '../../core/api/connections.api';
import { ConnectionPreview, EndpointSummary, EndpointsApi } from '../../core/api/endpoints.api';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';

interface ServiceChoice {
  readonly row: ConnectionRow;
  readonly pending: boolean;
  readonly reason: string | null;
}

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
  protected readonly uid = `overview-setup-${++nextId}`;
  protected readonly endpoint = signal<EndpointSummary | null>(null);
  protected readonly choices = signal<readonly ServiceChoice[]>([]);
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
    const generation = ++this.generation;
    this.endpoint.set(endpoint);
    const rows = connections.filter((row) => !endpoint || !row.usedBy.includes(endpoint.slug));
    this.choices.set(rows.map((row) => ({ row, pending: !!endpoint, reason: null })));
    this.picker().openAfterRender(opener);
    if (!endpoint) return;
    for (const row of rows) {
      const reason = this.endpointReason(endpoint) ?? this.connectionReason(row);
      if (reason) this.updateChoice(row.name, reason, generation);
      else void this.api.previewConnection(endpoint.slug, row.name).then(
        (preview) => this.updateChoice(row.name, preview.refusal, generation),
        (error) => this.updateChoice(row.name, failureText(error), generation),
      );
    }
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

  protected choose(choice: ServiceChoice): void {
    const endpoint = this.endpoint();
    if (!endpoint || choice.pending || choice.reason) return;
    const opener = this.picker().returnTo;
    this.picker().close('next');
    this.showConnection(choice.row, endpoint, opener);
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

  private updateChoice(name: string, reason: string | null, generation: number): void {
    if (generation !== this.generation) return;
    this.choices.update((choices) => choices.map((choice) => choice.row.name === name ? { ...choice, pending: false, reason } : choice));
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
