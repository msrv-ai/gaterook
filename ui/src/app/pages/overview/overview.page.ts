import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../code/code-block';
import { isApiError } from '../../core/api/api-error';
import { ConnectionsApi } from '../../core/api/connections.api';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { GwAccessSwitch } from '../../kit/access-switch';
import { Announcer } from '../../kit/announcer';
import { GwCopy } from '../../kit/copy';
import { GwDialog, GwDialogClose, GwDialogOpen } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { GwIcon } from '../../kit/icons';
import { GwSearchClear, GwSearchClearButton } from '../../kit/search';
import { ToastService } from '../../kit/toast.service';
import { OverviewData } from './overview.data';
import { OverviewConnectionTools } from './overview-connection-tools';
import { activityLine, overviewState, retestToast, RetestResult } from './overview-view';
import { MapConnection, endpointUnreachable, mapApps, mapConnections } from './overview-map-view';
import { GwOverviewSetup } from './overview-setup';
import { GwOverviewWires, OverviewWireTrace } from './overview-wires';

export const SKELETON_DELAY_MS = 300;

/** Live application counterpart of design/pages/overview-map.html. */
@Component({
  selector: 'gw-overview-page',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    GwIcon,
    GwAccessSwitch,
    GwCopy,
    GwToggle,
    GwCodeBlock,
    GwDialog,
    GwDialogOpen,
    GwDialogClose,
    GwSearchClearButton,
    GwSearchClear,
    GwOverviewSetup,
    GwOverviewWires,
    OverviewConnectionTools,
  ],
  providers: [OverviewData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './overview.page.html',
})
export class OverviewPage {
  protected readonly data = inject(OverviewData);
  private readonly core = inject(CoreStore);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly time = inject(TimeService);
  private readonly announcer = inject(Announcer);
  private readonly toasts = inject(ToastService);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly route = inject(ActivatedRoute);
  private readonly mapTitle = viewChild<ElementRef<HTMLElement>>('mapTitle');
  protected readonly setup = viewChild.required(GwOverviewSetup);
  protected readonly slow = signal(false);
  protected readonly overview = this.data.overview;
  protected readonly loading = computed(() => !this.overview() && !this.data.error());
  protected readonly state = computed(() =>
    this.overview() ? overviewState(this.overview()!) : null,
  );
  protected readonly pending = computed(() =>
    Math.max(this.overview()?.stats.pending ?? 0, this.overview()?.waiting.length ?? 0),
  );
  protected readonly activity = computed(() =>
    activityLine(this.overview()?.stats ?? { calls24h: 0, denied24h: 0 }),
  );
  protected readonly selectedSlug = signal<string | null>(
    this.route.snapshot.queryParamMap.get('endpoint'),
  );
  protected readonly endpoint = computed(
    () =>
      this.data.endpoints()?.find((e) => e.slug === this.selectedSlug()) ??
      this.data.endpoints()?.[0] ??
      null,
  );
  protected readonly query = signal('');
  protected readonly problemsOnly = signal(false);
  protected readonly expanded = signal(false);
  protected readonly selectedNode = signal<string | null>(null);
  protected readonly hoverNode = signal<string | null>(null);
  protected readonly activeNode = computed(() => this.hoverNode() ?? this.selectedNode());
  protected readonly trace = signal<OverviewWireTrace | null>(null);
  protected readonly testing = signal<readonly string[]>([]);
  private traceSequence = 0;

  protected readonly connections = computed(() =>
    mapConnections(this.data.connections(), this.overview()?.health ?? [], this.endpoint()),
  );
  protected readonly apps = computed(() =>
    mapApps(this.data.clients()?.clients ?? null, this.endpoint()),
  );
  protected readonly assigned = computed(() => this.connections()?.filter((c) => c.assigned) ?? []);
  protected readonly filtered = computed(() =>
    this.assigned().filter((c) => this.matches(c) && (!this.problemsOnly() || c.unreachable)),
  );
  protected readonly native = computed(() => {
    const rows = this.filtered().filter((c) => !c.mcp);
    return this.expanded() || this.query().trim() || this.problemsOnly() ? rows : rows.slice(0, 4);
  });
  protected readonly nativeCount = computed(() => this.assigned().filter((c) => !c.mcp).length);
  protected readonly mcp = computed(() => this.filtered().filter((c) => c.mcp));
  protected readonly unassignedMcp = computed(() =>
    this.problemsOnly() || this.data.endpoints() === null
      ? []
      : (this.connections()?.filter((c) => c.mcp && !c.assigned && this.matches(c)) ?? []),
  );
  protected readonly detailConnection = computed(
    () => this.connections()?.find((c) => `service:${c.name}` === this.selectedNode()) ?? null,
  );
  protected readonly detailApp = computed(
    () => this.apps()?.find((a) => `app:${a.client}` === this.selectedNode()) ?? null,
  );
  protected readonly mutationBlocked = computed(() =>
    !this.core.running()
      ? 'The local gateway is unavailable. Reconnect before changing access.'
      : this.data.stale()
        ? 'This view may be out of date. Refresh before changing access.'
        : this.data.connections() === null || this.data.endpoints() === null
          ? 'Connection or endpoint data is unavailable. Retry before changing access.'
          : null,
  );
  protected readonly endpointOn = computed(
    () => this.endpoint()?.status === 'running' && !this.endpoint()?.blockedReason,
  );
  protected readonly protection = computed(() =>
    this.mutationBlocked()
      ? 'Status unavailable'
      : this.endpoint() && !this.endpointOn()
        ? 'Access paused'
        : 'Protected access',
  );
  protected readonly visibleConnections = computed(() => [...this.native(), ...this.mcp()]);
  protected readonly wireServices = computed(() =>
    this.visibleConnections().map((c) => ({
      id: c.name,
      enabled: c.effectiveOn,
      pending: !c.effectiveOn,
    })),
  );
  protected readonly wireApps = computed(() =>
    (this.apps() ?? [])
      .filter((a) => a.assigned)
      .map((a) => ({ id: a.client, enabled: this.endpointOn(), pending: !a.connected })),
  );
  protected readonly detailTitle = computed(
    () =>
      this.detailConnection()?.name ??
      this.detailApp()?.name ??
      (this.data.endpoints() === null
        ? 'Endpoint status unavailable'
        : this.endpoint()
          ? `Access through ${this.endpoint()!.slug}`
          : 'Create your first endpoint'),
  );
  protected readonly detailDescription = computed(() => {
    const c = this.detailConnection();
    if (c)
      return `${c.typeLabel} · ${c.state.label}. ${c.assigned ? `Configured on endpoint ${this.endpoint()?.slug}.` : 'Not connected to this endpoint.'}`;
    const app = this.detailApp();
    if (app)
      return `${app.state.label}${this.endpoint() ? ` on ${this.endpoint()!.slug}` : ''}. ${app.actionReason ?? 'Inspect or manage this app’s endpoint access.'}`;
    const services =
      this.connections() === null
        ? 'Service status unavailable'
        : `${this.assigned().length} services`;
    const apps =
      this.apps() === null
        ? 'AI app status unavailable'
        : `${this.apps()!.filter((a) => a.assigned).length} configured AI apps`;
    if (this.data.endpoints() === null)
      return 'Retry the connection to load endpoints and inspect access.';
    return this.endpoint()
      ? `${services} · ${apps}. ${this.endpoint()!.description ?? 'Select a connection or app to inspect its access.'}`
      : 'An endpoint groups the services and permissions your AI app can use.';
  });
  protected readonly detailRules = computed(
    () =>
      this.mutationBlocked() ??
      this.endpoint()?.blockedReason ??
      (!this.endpointOn() && this.endpoint()
        ? 'All tools are off on this endpoint. Saved settings are kept; other endpoints keep their own access.'
        : this.endpoint()?.access === 'read-only'
          ? 'This endpoint permits read-only tools. Calls are recorded in Activity.'
          : 'GateRook checks saved tool modes, approval rules and limits on every call. Service credentials stay in GateRook.'),
  );
  protected readonly traceReason = computed(() => {
    if (this.mutationBlocked()) return this.mutationBlocked();
    if (!this.endpointOn()) return 'Enable an endpoint first.';
    if (this.detailConnection() && !this.detailConnection()!.effectiveOn)
      return 'Enable this connection before showing its request path.';
    if (this.detailApp() && !this.detailApp()!.connected)
      return 'This app is not connected to the selected endpoint.';
    if (!this.visibleConnections().some((c) => c.effectiveOn))
      return 'Show an enabled connection to see its request path.';
    if (!this.apps()?.some((a) => a.connected))
      return 'A connected AI app is needed to show a request path.';
    return null;
  });

  constructor() {
    void this.data.start();
    effect(() => {
      const slug = this.endpoint()?.slug ?? null;
      untracked(() => void this.data.selectEndpoint(slug));
    });
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    inject(DestroyRef).onDestroy(() => clearTimeout(timer));
    effect(() => {
      if (!this.loading()) clearTimeout(timer);
    });
    let running = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const nextRunning = this.core.running();
      const nextInstance = this.core.instanceChanges();
      untracked(() => {
        if (nextRunning && (!running || nextInstance !== instance)) void this.data.refresh();
      });
      running = nextRunning;
      instance = nextInstance;
    });
    // SSE refreshes can pause or remove an endpoint while its problem filter is open.
    effect(() => {
      const endpoints = this.data.endpoints();
      const selected = this.selectedSlug();
      if (endpoints && (!selected || !endpoints.some((ep) => ep.slug === selected))) {
        this.selectedSlug.set(endpoints[0]?.slug ?? null);
        this.problemsOnly.set(false);
        this.resetSelection();
      }
      if (!this.endpointOn()) this.problemsOnly.set(false);
    });
    // Removed endpoints and connections cannot leave a stale detail or example route selected.
    effect(() => {
      const selected = this.selectedNode();
      const exists =
        !selected ||
        this.visibleConnections().some((c) => `service:${c.name}` === selected) ||
        this.apps()?.some((a) => `app:${a.client}` === selected);
      if (!exists) {
        this.selectedNode.set(null);
        this.trace.set(null);
      }
      const trace = this.trace();
      if (
        this.mutationBlocked() ||
        !this.endpointOn() ||
        (trace &&
          (!this.visibleConnections().some((c) => c.name === trace.service && c.effectiveOn) ||
            !this.apps()?.some((a) => a.client === trace.app && a.connected)))
      )
        this.trace.set(null);
    });
  }

  protected matches(c: MapConnection): boolean {
    return `${c.name} ${c.typeLabel}`
      .toLocaleLowerCase()
      .includes(this.query().trim().toLocaleLowerCase());
  }
  protected retry(): void {
    void this.data.refresh();
  }
  protected problemCount(ep: EndpointSummary): number | null {
    return this.mutationBlocked()
      ? null
      : endpointUnreachable(ep, this.data.connections(), this.overview()?.health ?? []);
  }
  protected appCount(ep: EndpointSummary): number | null {
    return (
      mapApps(this.data.clients()?.clients ?? null, ep)?.filter((a) => a.assigned).length ?? null
    );
  }
  protected mark(c: MapConnection): string | null {
    return (
      (
        {
          postgres: 'postgresql',
          mysql: 'mysql',
          mongodb: 'mongodb',
          redis: 'redis',
          opensearch: 'opensearch',
        } as Record<string, string>
      )[c.row.type] ?? null
    );
  }
  protected nodeId(kind: string, value: string): string {
    return `ov-${kind}-${encodeURIComponent(value)}`;
  }
  protected resetSelection(): void {
    this.selectedNode.set(null);
    this.hoverNode.set(null);
    this.trace.set(null);
  }
  protected chooseEndpoint(slug: string): void {
    this.selectedSlug.set(slug);
    this.problemsOnly.set(false);
    this.expanded.set(false);
    this.resetSelection();
    this.announcer.announce(`Showing access through endpoint ${slug}.`);
  }
  protected inspectEndpoint(slug: string): void {
    if (this.endpoint()?.slug === slug) this.resetSelection();
  }
  protected search(value: string): void {
    this.query.set(value);
    this.resetSelection();
    this.announcer.announce(
      `${this.filtered().length} matching connections on ${this.endpoint()?.slug ?? 'no endpoint'}.`,
    );
  }
  protected showAll(): void {
    this.expanded.update((v) => !v);
    this.resetSelection();
  }
  protected showProblems(ep: EndpointSummary): void {
    if (!this.problemCount(ep)) return;
    this.selectedSlug.set(ep.slug);
    this.query.set('');
    this.problemsOnly.set(true);
    this.expanded.set(true);
    this.resetSelection();
    const first = this.filtered()[0];
    if (first) {
      this.selectedNode.set(`service:${first.name}`);
      this.focusNode(first.name);
    }
    this.announcer.announce(
      `Showing ${this.filtered().length} unreachable connections on ${ep.slug}.`,
    );
  }
  protected clearFilters(): void {
    this.problemsOnly.set(false);
    this.query.set('');
    this.expanded.set(true);
    this.resetSelection();
    this.focusMap();
  }
  protected inspect(key: string): void {
    this.selectedNode.set(key);
    this.trace.set(null);
  }
  protected connectionChanged(name: string, enabled: boolean): void {
    this.data.patchConnectionEnabled(name, enabled);
    if (!enabled && this.problemsOnly()) {
      this.problemsOnly.set(false);
      this.expanded.set(true);
    }
    this.trace.set(null);
    this.selectedNode.set(`service:${name}`);
    this.focusNode(name, true);
    void this.data.refresh();
  }
  protected endpointChanged(slug: string, enabled: boolean): void {
    this.data.patchEndpointEnabled(slug, enabled);
    this.problemsOnly.set(false);
    this.trace.set(null);
    void this.data.refresh();
  }
  protected added(slug: string): void {
    this.selectedSlug.set(slug);
    this.clearFilters();
    void this.data.refresh();
  }
  protected picker(event: Event): void {
    this.setup().showPicker(
      this.endpoint(),
      this.data.connections() ?? [],
      event.currentTarget as HTMLElement,
    );
  }
  protected connectService(c: MapConnection, event: Event): void {
    const endpoint = this.endpoint();
    if (endpoint && !this.mutationBlocked())
      this.setup().showConnection(c.row, endpoint, event.currentTarget as HTMLElement);
  }
  protected showPath(): void {
    if (this.traceReason()) return;
    const service =
      this.detailConnection() ?? this.visibleConnections().find((c) => c.effectiveOn)!;
    const app = this.detailApp() ?? this.apps()!.find((a) => a.connected)!;
    this.trace.set({ service: service.name, app: app.client, sequence: ++this.traceSequence });
    this.announcer.announce(
      `Example path: ${app.name} through ${this.endpoint()!.slug} to ${service.name}. No request is sent.`,
    );
  }
  protected clearPath(): void {
    this.trace.set(null);
  }
  protected async testAgain(name: string): Promise<void> {
    if (this.testing().includes(name) || this.mutationBlocked()) return;
    this.testing.update((v) => [...v, name]);
    let result: RetestResult;
    try {
      const answer = await this.connectionsApi.test(name);
      result = {
        name,
        ok: answer.ok,
        latencyMs: answer.ok ? answer.latencyMs : null,
        message: answer.error?.message ?? answer.needsConfirmation?.text ?? null,
      };
    } catch (error) {
      result = {
        name,
        ok: false,
        latencyMs: null,
        message: isApiError(error) ? error.detail : null,
      };
    }
    this.data.patchHealth({
      name,
      state: result.ok ? 'healthy' : 'unreachable',
      latencyMs: result.latencyMs,
      message: result.message,
      checkedAt: new Date(this.time.now()).toISOString(),
    });
    this.testing.update((v) => v.filter((n) => n !== name));
    const toast = retestToast(result);
    this.toasts.show({ tone: toast.tone, title: toast.title, body: toast.body });
    void this.data.refresh();
    this.focusMap();
  }
  protected errorText(error: unknown): string {
    return isApiError(error) ? error.detail : 'Check the local gateway and try again.';
  }
  protected errorDetails(error: unknown): string {
    return isApiError(error)
      ? `${error.status || 'No answer'}: ${error.title}\n${error.detail}`
      : error instanceof Error
        ? error.message
        : String(error);
  }
  private focusNode(name: string, toggle = false): void {
    afterNextRender(
      () => {
        const row = Array.from(this.host.querySelectorAll<HTMLElement>('[data-map-service]')).find(
          (e) => e.dataset['mapService'] === name,
        );
        row
          ?.querySelector<HTMLElement>(toggle ? 'input[role="switch"]' : 'button')
          ?.focus({ preventScroll: true });
      },
      { injector: this.injector },
    );
  }
  private focusMap(): void {
    afterNextRender(() => this.mapTitle()?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
  }
}
