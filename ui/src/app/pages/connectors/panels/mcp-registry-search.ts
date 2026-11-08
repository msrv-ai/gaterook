import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, inject, output, signal } from '@angular/core';
import { GwIcon } from '../../../kit/icons';
import { GwSearchClearButton } from '../../../kit/search';
import { ConnectorsData } from '../connectors.data';
import { failureText } from '../dialogs/failure';
import { RegistryResult, completeRegistryResults, rankRegistryResults, registryResult } from '../mcp-registry-results';

export interface McpRegistrySelection {
  readonly result: RegistryResult;
  readonly opener: HTMLElement | null;
}

let nextId = 0;

/** Search the registry on the page; an empty query leaves the curated discovery cards visible. */
@Component({
  selector: 'section[gwMcpRegistrySearch]',
  imports: [GwIcon, GwSearchClearButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-stack', 'aria-label': 'MCP Registry search' },
  templateUrl: './mcp-registry-search.html',
})
export class GwMcpRegistrySearch {
  private readonly data = inject(ConnectorsData);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  readonly selected = output<McpRegistrySelection>();
  protected readonly uid = `mcp-search-${++nextId}`;
  protected readonly query = signal('');
  readonly active = computed(() => this.query().trim().length > 0);
  protected readonly results = signal<readonly RegistryResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly moreBusy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly searched = signal<string | null>(null);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly registry = signal<string | null>(null);
  private timer: ReturnType<typeof setTimeout> | null = null;
  private sequence = 0;
  private retryAppend = false;

  protected readonly status = computed(() => {
    if (!this.active()) return '';
    if (this.query().trim().length < 3) return 'Type at least 3 characters of the server’s name.';
    if (this.searching()) return 'Searching the MCP registry…';
    if (this.searched() === null) return '';
    const n = this.results().length;
    return `${n === 1 ? '1 server' : `${n.toLocaleString('en-US')} servers`}${this.nextCursor() ? ' so far' : ''}`;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.sequence++;
      this.cancelTimer();
    });
  }

  protected find(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    this.cancelTimer();
    this.sequence++;
    this.results.set([]);
    this.searched.set(null);
    this.nextCursor.set(null);
    this.error.set(null);
    this.moreBusy.set(false);
    this.retryAppend = false;
    const ready = this.query().trim().length >= 3;
    this.searching.set(ready);
    if (ready) this.timer = setTimeout(() => {
      this.timer = null;
      void this.load();
    }, 350);
  }

  protected searchNow(event: Event): void {
    event.preventDefault();
    if (this.searching() && this.timer === null) return;
    this.cancelTimer();
    void this.load();
  }

  protected retry(): void {
    void this.load(this.retryAppend);
  }

  protected more(): void {
    if (this.searching() || this.moreBusy()) return;
    void this.load(true);
  }

  protected choose(result: RegistryResult, event: Event): void {
    if (result.use.kind === 'none') return;
    this.selected.emit({ result, opener: event.currentTarget as HTMLElement | null });
  }

  private cancelTimer(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private async load(append = false): Promise<void> {
    const query = this.query().trim();
    const cursor = append ? this.nextCursor() : null;
    if (query.length < 3 || (append && (!cursor || query !== this.searched()))) return;
    const sequence = ++this.sequence;
    this.searching.set(!append);
    this.moreBusy.set(append);
    this.error.set(null);
    this.retryAppend = append;
    try {
      const page = await this.data.searchRegistry(query, cursor);
      if (sequence !== this.sequence) return;
      if (append && page.registry !== this.registry()) throw new Error('The registry changed. Search again to load its current results.');
      const previous = new Set(this.results().map((r) => r.server.name));
      const rows = append ? page.servers.map((server) => registryResult(server, page.registry))
        : await completeRegistryResults(page, query, (name) => this.data.searchRegistry(name));
      if (sequence !== this.sequence) return;
      this.results.set(rankRegistryResults(append ? [...this.results(), ...rows] : rows));
      this.registry.set(page.registry);
      this.searched.set(query);
      this.nextCursor.set(page.nextCursor ?? null);
      if (append) {
        const index = this.results().findIndex((r) => !previous.has(r.server.name));
        afterNextRender(() => {
          if (sequence === this.sequence && index >= 0 && !this.host.closest('[hidden]')) {
            this.host.querySelector<HTMLElement>(`#${this.uid}-use-${index}`)?.focus();
          }
        }, { injector: this.injector });
      }
    } catch (error) {
      if (sequence === this.sequence) this.error.set(failureText(error, 'This build of the gateway core can’t search the MCP registry.'));
    } finally {
      if (sequence === this.sequence) {
        this.searching.set(false);
        this.moreBusy.set(false);
      }
    }
  }
}
