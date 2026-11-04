import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, isApiError } from '../../core/api/api-error';
import { ConnectionUsedBy } from '../../core/api/connections.api';
import { EndpointsApi, ToolGroup, ToolRow } from '../../core/api/endpoints.api';
import { ToolMode } from '../../core/api/types';
import { TimeService } from '../../core/time/time.service';
import { GwAriaDisabled } from '../../kit/aria-disabled';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { MODES } from '../../kit/elements/shared/dictionaries';
import { modeNote } from '../../kit/elements/tool-policy-list/tool-policy-view';
import { GwIcon } from '../../kit/icons';
import { GwMenu, GwMenuTrigger, MenuPick } from '../../kit/menu';
import { ToastService } from '../../kit/toast.service';
import { activityQuery, toolMode } from './connection-new-view';

interface EndpointModes {
  readonly tools: readonly ToolRow[];
  readonly readOnly: boolean;
  readonly blocked: string | null;
  readonly loading: boolean;
  readonly saving: boolean;
  readonly error: string | null;
}

/** The connection editor's tool table, composed from the kit's table, mode chips and menus. */
@Component({
  selector: 'gw-connection-tool-modes',
  imports: [RouterLink, GwIcon, GwMenu, GwMenuTrigger, GwAriaDisabled, GwDialog, GwDialogClose],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './connection-tool-modes.html',
})
export class ConnectionToolModes {
  private readonly api = inject(EndpointsApi);
  private readonly toasts = inject(ToastService);
  protected readonly time = inject(TimeService);
  readonly connection = input.required<string>();
  readonly connectionVersion = input(0);
  readonly rows = input.required<readonly ConnectionUsedBy[]>();
  readonly changed = output<{ readonly endpoint: string; readonly tools: readonly ToolRow[] }>();
  private readonly endpoints = signal<Readonly<Partial<Record<string, EndpointModes>>>>({});
  private readonly slugs = computed(() => JSON.stringify([...new Set(this.rows().map((row) => row.endpoint))].sort()));
  private generation = 0;
  protected readonly modes: readonly ToolMode[] = ['OFF', 'READ_ONLY', 'APPROVAL', 'BYPASS'];
  protected readonly looks = MODES;
  protected readonly activityQuery = activityQuery;
  protected readonly confirming = signal<{ endpoint: string; tool: ToolRow } | null>(null);
  protected readonly acknowledged = signal(false);
  private readonly confirmDialog = viewChild<GwDialog>('confirmDialog');

  protected readonly tools = computed(() => {
    const groups = new Map<string, ConnectionUsedBy[]>();
    for (const row of this.rows()) groups.set(row.tool, [...(groups.get(row.tool) ?? []), row]);
    return [...groups].map(([tool, rows], index) => ({ tool, endpoints: rows.map((row, endpointIndex) => {
      const state = this.endpoints()[row.endpoint];
      const source = state?.tools.find((candidate) => candidate.name === tool);
      const mode = source?.storedMode ?? toolMode(row.mode);
      const note = source ? modeNote({ ...source, mode }, { slug: row.endpoint, readOnly: state?.readOnly ?? false }) : null;
      return {
        endpoint: row.endpoint, source, mode, note, state,
        id: `connection-mode-${index}-${endpointIndex}`,
        disabled: !source || !state || !!state.error || !!state.blocked || !!note?.locked,
      };
    }) }));
  });

  constructor() {
    effect(() => {
      const connection = this.connection();
      this.connectionVersion();
      const slugs: string[] = JSON.parse(this.slugs());
      const generation = ++this.generation;
      untracked(() => {
        this.confirmDialog()?.close();
        this.endpoints.set({});
        for (const slug of slugs) void this.load(slug, connection, generation);
      });
    });
    inject(DestroyRef).onDestroy(() => ++this.generation);
  }

  private update(endpoint: string, patch: Partial<EndpointModes>): void {
    this.endpoints.update((states) => ({ ...states, [endpoint]: {
      tools: [], readOnly: false, blocked: null, loading: false, saving: false, error: null,
      ...states[endpoint], ...patch,
    } }));
  }

  protected retry(endpoint: string): void {
    void this.load(endpoint, this.connection(), this.generation);
  }

  private async load(endpoint: string, connection: string, generation: number): Promise<void> {
    this.update(endpoint, { loading: true, error: null });
    try {
      const [detail, groups] = await Promise.all([this.api.get(endpoint), this.api.tools(endpoint)]);
      if (generation !== this.generation) return;
      this.update(endpoint, { readOnly: detail.endpoint.access === 'read-only', blocked: detail.endpoint.blockedReason, loading: false });
      this.apply(endpoint, connection, groups);
    } catch (error) {
      if (generation !== this.generation) return;
      this.update(endpoint, { loading: false, error: isApiError(error) ? error.detail : "Couldn't load the endpoint's tool modes. Try again." });
    }
  }

  private apply(endpoint: string, connection: string, groups: readonly ToolGroup[]): void {
    const tools = groups.filter((group) => group.connection === connection).flatMap((group) => group.tools);
    this.update(endpoint, { tools });
    this.changed.emit({ endpoint, tools });
  }

  protected pick(endpoint: string, tool: ToolRow, pick: MenuPick, opener: HTMLElement): void {
    // Menu selection follows the saved mode until the core accepts the change or confirmation.
    pick.item.closest('[role="menu"]')?.querySelectorAll('[role="menuitemradio"]').forEach((item) => {
      item.setAttribute('aria-checked', String(item.getAttribute('data-value') === tool.storedMode));
    });
    const mode = this.modes.find((value) => value === pick.value);
    const state = this.endpoints()[endpoint];
    if (!state) return;
    const note = modeNote({ ...tool, mode: tool.storedMode }, { slug: endpoint, readOnly: state.readOnly });
    if (!mode || mode === tool.storedMode || state.saving || state.loading || state.error || state.blocked || note?.locked
      || (mode === 'BYPASS' && state.readOnly) || (mode === 'READ_ONLY' && note?.readOnlyDisabled)) return;
    if (mode === 'READ_ONLY' && note?.needsConfirmation) {
      if (!tool.definitionHash) return;
      this.acknowledged.set(false);
      this.confirming.set({ endpoint, tool });
      this.confirmDialog()?.openAfterRender(opener);
    } else {
      void this.save(endpoint, tool, mode);
    }
  }

  protected allowReadOnly(): void {
    const confirmation = this.confirming();
    if (!confirmation || !this.acknowledged()) return;
    this.confirmDialog()?.close('allow');
    void this.save(confirmation.endpoint, confirmation.tool, 'READ_ONLY', true);
  }

  private async save(endpoint: string, tool: ToolRow, mode: ToolMode, confirm = false): Promise<void> {
    const generation = this.generation;
    const connection = this.connection();
    if (this.endpoints()[endpoint]?.saving) return;
    this.update(endpoint, { saving: true });
    try {
      let version = tool.version;
      if (confirm) {
        // Confirmation records trust only. Save the chosen mode with the version that acknowledgement returns.
        const confirmed = await this.api.confirmReadOnly(endpoint, { toolKey: tool.toolKey, connection, definitionHash: tool.definitionHash! });
        if (generation !== this.generation) return;
        const current = confirmed.find((group) => group.connection === connection)?.tools.find((row) => row.toolKey === tool.toolKey);
        if (!current) throw new ApiError('conflict', 409, { detail: 'This tool is no longer available on the endpoint. Review its current tools.' });
        version = current.version;
      }
      const groups = await this.api.setMode(endpoint, { toolKey: tool.toolKey, connection, mode, version });
      if (generation !== this.generation) return;
      this.apply(endpoint, connection, groups);
      this.toasts.show({ tone: 'success', title: `${tool.name}: ${MODES[mode].text} on ${endpoint}`, body: 'Applies from the next call.' });
    } catch (error) {
      if (generation !== this.generation) return;
      this.toasts.show({ tone: 'danger', title: "The mode wasn't changed", body: isApiError(error) ? error.detail : "The core didn't answer. Try again." });
      await this.load(endpoint, connection, generation);
    } finally {
      if (generation === this.generation) this.update(endpoint, { saving: false });
    }
  }
}
