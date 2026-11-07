import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { isApiError } from '../../core/api/api-error';
import { ConnectionRow, ConnectionsApi, ConnectionUsedBy } from '../../core/api/connections.api';
import { EndpointSummary, ToolRow } from '../../core/api/endpoints.api';
import { GwIcon } from '../../kit/icons';
import { ConnectionToolModes } from '../connection-new/connection-tool-modes';

/** The existing connection mode editor, scoped to the pair selected on Overview. */
@Component({
  selector: 'gw-overview-connection-tools',
  imports: [GwIcon, ConnectionToolModes],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-connection-tools.html',
})
export class OverviewConnectionTools {
  private readonly api = inject(ConnectionsApi);
  readonly connection = input.required<ConnectionRow>();
  readonly endpoint = input.required<EndpointSummary>();
  readonly blocked = input<string | null>(null);
  /** A saved mode changed; the parent may refresh endpoint summary counts. */
  readonly changed = output<void>();
  protected readonly rows = signal<readonly ConnectionUsedBy[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly version = signal(0);
  protected readonly unavailable = computed(() => this.blocked() ?? this.endpoint().blockedReason
    ?? (!this.connection().verified ? 'Review this connection before changing tool modes.' : null)
    ?? (this.connection().setupRequired ? 'Finish setting up this connection before changing tool modes.' : null));
  protected readonly assigned = computed(() => this.connection().usedBy.includes(this.endpoint().slug));
  protected readonly paused = computed(() => this.endpoint().status === 'disabled'
    ? `Endpoint ${this.endpoint().slug} is off. The modes below are saved settings. Turn on the endpoint to change them.`
    : this.connection().enabled === false
      ? 'This connection is off. The modes below are saved settings. Turn on the connection to change them.'
      : null);
  private readonly loadKey = computed(() => JSON.stringify([
    this.connection().name, this.connection().version, this.endpoint().slug, this.endpoint().version,
    this.unavailable(), this.assigned(),
  ]));
  private generation = 0;
  private modeSnapshot: string | null = null;

  constructor() {
    effect(() => {
      this.loadKey();
      untracked(() => this.reload());
    });
    inject(DestroyRef).onDestroy(() => ++this.generation);
  }

  protected reload(): void {
    const generation = ++this.generation;
    this.rows.set(null);
    this.error.set(null);
    this.modeSnapshot = null;
    if (this.unavailable() || !this.assigned()) return;
    const connection = this.connection().name;
    const endpoint = this.endpoint().slug;
    void this.api.get(connection).then((detail) => {
      if (generation !== this.generation) return;
      this.version.set(detail.version);
      this.rows.set(detail.usedBy.filter((row) => row.endpoint === endpoint));
    }).catch((error) => {
      if (generation !== this.generation) return;
      this.error.set(isApiError(error) ? error.detail : 'Could not load tools. Check the local gateway and try again.');
    });
  }

  protected modesChanged(change: { readonly endpoint: string; readonly tools: readonly ToolRow[] }): void {
    if (change.endpoint !== this.endpoint().slug) return;
    const snapshot = JSON.stringify(change.tools.map((tool) => [tool.toolKey, tool.storedMode, tool.version]));
    const previous = this.modeSnapshot;
    this.modeSnapshot = snapshot;
    if (previous !== null && previous !== snapshot) this.changed.emit();
  }
}
