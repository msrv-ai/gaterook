import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  booleanAttribute,
  Component,
  ElementRef,
  OnInit,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { NavigationStart, Router, RouterLink } from '@angular/router';
import { filter } from 'rxjs';
import { CoreApi, SearchResult } from '../../../core/api/core.api';
import { ApprovalsStore } from '../../../core/stores/approvals.store';
import { Announcer } from '../../announcer';
import { COPY_FAILED, ClipboardWriter } from '../../clipboard';
import { GwDialog } from '../../dialog';
import { GwIcon } from '../../icons';
import { ShortcutService } from '../../shortcuts';
import { ThemeService } from '../../theme';
import { CONNECTOR_ICONS, CONNECTOR_NAMES } from '../shared/connector-icons';

/** One row of the palette: a page or action link, an action button, a connection or an endpoint. */
export interface PaletteOption {
  readonly id: string;
  readonly group: 'goto' | 'actions' | 'connections' | 'endpoints';
  readonly label: string;
  /** A literal after the label, in mono ("Copy URL of endpoint `dev`"). */
  readonly labelMono?: string;
  /** Mono label (slugs, connection names). */
  readonly mono?: boolean;
  readonly icon: string;
  readonly hint?: string | null;
  readonly hintMono?: boolean;
  /** An app route: the row is a link. */
  readonly route?: string;
  /** A button action instead of a route. */
  readonly action?: 'copy' | 'theme';
  readonly copyText?: string;
  readonly env?: string | null;
  readonly counter?: number | null;
  readonly kbd?: string | null;
  readonly keyshortcuts?: string | null;
  /** Words a person might type for it (kit `data-keywords`). */
  readonly keywords: string;
}

const GROUPS: readonly { readonly id: PaletteOption['group']; readonly label: string }[] = [
  { id: 'goto', label: 'Go to' },
  { id: 'actions', label: 'Actions' },
  { id: 'connections', label: 'Connections' },
  { id: 'endpoints', label: 'Endpoints' },
];

/**
 * The kit's pages: the label the sidebar gives each (the core's search names a page by its id; the words are the
 * kit's — "AI apps", not the core's "Clients"), its icon, sidebar group (hint) and the words of `data-keywords`
 * (pages/_shell.html).
 */
const PAGES: Record<string, { readonly label: string; readonly icon: string; readonly hint: string | null; readonly keywords: string }> = {
  overview: { label: 'Overview', icon: 'layout-dashboard', hint: null, keywords: 'home dashboard setup map health' },
  approvals: { label: 'Approvals', icon: 'user-check', hint: null, keywords: 'requests pending queue decide' },
  connections: { label: 'Connections', icon: 'database', hint: 'Resources', keywords: 'databases servers logs clusters' },
  connectors: { label: 'Connectors', icon: 'puzzle', hint: 'Resources', keywords: 'plugins templates mcp servers catalog' },
  secrets: { label: 'Secrets', icon: 'key-round', hint: 'Resources', keywords: 'master key vault encryption' },
  endpoints: { label: 'Endpoints', icon: 'waypoints', hint: 'Access', keywords: 'mcp url tools tokens' },
  clients: { label: 'AI apps', icon: 'app-window', hint: 'Access', keywords: 'clients mcp claude desktop codex cursor antigravity' },
  policies: { label: 'Policies', icon: 'shield', hint: 'Access', keywords: 'limits allowlist environments' },
  playground: { label: 'Playground', icon: 'flask-conical', hint: 'Access', keywords: 'try call tool' },
  activity: { label: 'Activity', icon: 'activity', hint: 'Observe', keywords: 'log audit calls events traces' },
  insights: { label: 'Insights', icon: 'chart-column', hint: 'Observe', keywords: 'analytics charts latency errors' },
  settings: { label: 'Settings', icon: 'settings', hint: null, keywords: 'preferences notifications authorization appearance' },
};

/** The core's actions in the kit's words (verb + object), icon, hint and keywords. */
const ACTIONS: Record<string, { readonly label: string; readonly icon: string; readonly hint: string; readonly keywords: string }> = {
  'add-connection': { label: 'Add connection', icon: 'plus', hint: 'Database, SSH, logs, cluster', keywords: 'new resource postgres mysql sqlserver sql server mongodb redis kubernetes' },
  'create-endpoint': { label: 'Create endpoint', icon: 'square-plus', hint: 'New MCP URL', keywords: 'new mcp url' },
  'test-all': { label: 'Test all connections', icon: 'activity', hint: 'Check every connection', keywords: 'health check' },
  'back-up-key': { label: 'Back up master key', icon: 'key-round', hint: 'Master key', keywords: 'backup export key' },
  'back-up-db': { label: 'Back up the database now', icon: 'database', hint: 'Copy of the database', keywords: 'backup copy database' },
  'connect-client': { label: 'Connect your AI app', icon: 'app-window', hint: 'Claude Desktop, Codex, Cursor', keywords: 'clients ai apps claude codex cursor' },
};

/** Builds the palette's rows from `/api/search` (and what only the renderer knows: theme, the endpoint URL). */
export function paletteOptions(result: SearchResult, opts: { readonly pending: number; readonly address: string }): PaletteOption[] {
  const out: PaletteOption[] = [];
  for (const g of result.goTo) {
    const p = PAGES[g.id] ?? { label: g.label, icon: 'circle-dot', hint: null, keywords: '' };
    out.push({
      id: g.id,
      group: 'goto',
      label: p.label,
      icon: p.icon,
      hint: p.hint,
      route: g.route,
      counter: g.id === 'approvals' && opts.pending > 0 ? opts.pending : null,
      kbd: g.id === 'settings' ? '⌘ ,' : null,
      keyshortcuts: g.id === 'settings' ? 'Meta+Comma Control+Comma' : null,
      keywords: p.keywords,
    });
  }
  for (const a of result.actions) {
    const meta = ACTIONS[a.id] ?? { label: a.label, icon: 'circle-dot', hint: '', keywords: '' };
    out.push({ id: a.id, group: 'actions', label: meta.label, icon: meta.icon, hint: meta.hint, route: a.route, keywords: meta.keywords });
  }
  const first = result.endpoints[0];
  if (first) {
    out.push({
      id: `copy-${first.slug}`,
      group: 'actions',
      label: 'Copy URL of endpoint',
      labelMono: first.slug,
      icon: 'copy',
      hint: `/mcp/${first.slug}`,
      hintMono: true,
      action: 'copy',
      copyText: `http://${opts.address}/mcp/${first.slug}`,
      keywords: 'clipboard link',
    });
  }
  out.push({ id: 'theme', group: 'actions', label: 'Toggle theme', icon: 'sun-moon', hint: 'Light or dark', action: 'theme', keywords: 'dark light appearance' });
  const taken = new Set(out.map((o) => o.id));
  for (const c of result.connections) {
    // The kit's ids are the bare names (`cmdk-o-dev-db`); a name that equals a page or action id gets a prefix.
    const id = taken.has(c.name) ? `c-${c.name}` : c.name;
    taken.add(id);
    out.push({
      id,
      group: 'connections',
      label: c.name,
      mono: true,
      icon: CONNECTOR_ICONS[c.type] ?? 'database',
      hint: CONNECTOR_NAMES[c.type] ?? c.type,
      env: c.env,
      route: c.route,
      keywords: `${c.type} ${CONNECTOR_NAMES[c.type] ?? ''}`,
    });
  }
  for (const e of result.endpoints) {
    out.push({
      id: `ep-${e.slug}`,
      group: 'endpoints',
      label: e.slug,
      mono: true,
      icon: 'waypoints',
      hint: `/mcp/${e.slug}`,
      hintMono: true,
      route: e.route,
      keywords: 'endpoint tools tokens',
    });
  }
  return out;
}

/** kit.js palette filter: the option's text plus its keywords, case-insensitive, whitespace collapsed. */
export function matches(o: PaletteOption, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const text = [o.label, o.labelMono ?? '', o.hint ?? '', o.env ?? '', o.keywords].join(' ').replace(/\s+/g, ' ').toLowerCase();
  return text.includes(q);
}

/**
 * `<gw-command-palette>`: the kit's ⌘K palette (elements/command-palette.html, kit.js "palette"): an APG combobox
 * whose DOM focus never leaves the field. Typing filters the rows (text + keywords) and hides empty groups; ↑/↓
 * wrap, Home / End, and a pointer that really moves set the active row (`aria-activedescendant`, `aria-selected`,
 * `gw-menu__item--active`, scrolled into view inside the results only); Enter runs it. "4 results" / "No results"
 * go to the status line; the empty state repeats the query. Esc always closes (the first Esc of a search field would
 * only clear it); closing resets the query and the active row; focus returns where it was.
 *
 * Rows come from `/api/search` (loaded on open), or from `result` (the dev gallery).
 */
@Component({
  selector: 'gw-command-palette',
  imports: [GwDialog, GwIcon, RouterLink, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './command-palette.html',
})
export class GwCommandPalette implements OnInit {
  private readonly core = inject(CoreApi);
  private readonly approvals = inject(ApprovalsStore);
  private readonly theme = inject(ThemeService);
  private readonly clipboard = inject(ClipboardWriter);
  private readonly announcer = inject(Announcer);
  private readonly router = inject(Router);

  /** Rows to show instead of asking the core (the gallery's demo data). */
  readonly result = input<SearchResult | null>(null);
  /** The approvals counter on "Approvals" instead of the live one (the gallery). */
  readonly pending = input<number | null>(null);
  /** The core's address for "Copy URL of endpoint …". */
  readonly address = input('127.0.0.1:7465');
  /** `preview`: the panel in the page flow for documentation (`--preview`), never in the app. */
  readonly preview = input(false, { transform: booleanAttribute });
  /** The preview's accessible name ("Command palette preview, filtered by dev"). */
  readonly previewLabel = input('Command palette preview');
  /** Prefix of every id (`cmdk`: `#cmdk`, `#cmdk-q`, `#cmdk-o-overview`); previews need their own. */
  readonly idPrefix = input('cmdk');
  /** A preview's query and active option (the gallery's "Filtered: dev", Down pressed once). */
  readonly initialQuery = input('');
  readonly initialActive = input<string | null>(null);

  protected readonly dialog = viewChild<GwDialog>('dlg');
  private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');
  private readonly results = viewChild<ElementRef<HTMLElement>>('results');

  protected readonly groups = GROUPS;
  protected readonly p = this.idPrefix;
  protected readonly query = signal('');
  private readonly loaded = signal<SearchResult | null>(null);
  protected readonly activeId = signal<string | null>(null);
  private lastPointer = '';
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly options = computed(() => {
    const result = this.result() ?? this.loaded();
    return result ? paletteOptions(result, { pending: this.pending() ?? this.approvals.pendingCount(), address: this.address() }) : [];
  });
  protected readonly visible = computed(() => this.options().filter((o) => matches(o, this.query())));
  protected readonly visibleIds = computed(() => new Set(this.visible().map((o) => o.id)));
  protected readonly status = computed(() => {
    const q = this.query().trim();
    const n = this.visible().length;
    return q === '' ? '' : n === 0 ? 'No results' : `${n} ${n === 1 ? 'result' : 'results'}`;
  });

  private readonly shortcuts = inject(ShortcutService);

  ngOnInit(): void {
    if (this.preview()) {
      this.query.set(this.initialQuery());
      this.activeId.set(this.initialActive() ?? this.visible()[0]?.id ?? null);
    } else {
      // Only the real modal answers ⌘K / Ctrl+K; documentation previews never do.
      this.shortcuts.registerPalette(() => this.toggle());
    }
  }

  constructor() {
    // A palette link navigates in place: close the palette when navigation starts.
    this.router.events.pipe(filter((e) => e instanceof NavigationStart)).subscribe(() => this.close());
    effect(() => {
      // The first visible row is active whenever the filter changes (kit.js `filter()`).
      const first = this.visible()[0]?.id ?? null;
      const current = this.activeId();
      if (!current || !this.visibleIds().has(current)) this.activeId.set(first);
    });
  }

  /** Opens the palette; focus returns to `opener` (default: what has focus now) when it closes. */
  open(opener: HTMLElement | null = null): void {
    const d = this.dialog();
    if (!d || d.isOpen()) return;
    if (!this.result()) void this.load('');
    d.open(opener);
    this.activeId.set(this.visible()[0]?.id ?? null);
  }

  close(): void {
    this.dialog()?.close('');
  }

  toggle(): void {
    if (this.dialog()?.isOpen()) this.close();
    else this.open();
  }

  protected optionId(o: PaletteOption): string {
    return `${this.p()}-o-${o.id}`;
  }

  protected groupHidden(id: PaletteOption['group']): boolean {
    return !this.visible().some((o) => o.group === id);
  }

  protected onInput(value: string): void {
    this.query.set(value);
    if (this.results()) this.results()!.nativeElement.scrollTop = 0;
    const first = this.visible()[0]?.id ?? null;
    this.activeId.set(first);
    if (!this.result()) {
      if (this.searchTimer) clearTimeout(this.searchTimer);
      // The core finds connections and endpoints beyond the first eight it lists without a query.
      this.searchTimer = setTimeout(() => void this.load(value), 150);
    }
  }

  protected onKeydown(e: KeyboardEvent): void {
    if (e.isComposing) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      this.close();
      return;
    }
    const vis = this.visible();
    if (!vis.length) return;
    const i = vis.findIndex((o) => o.id === this.activeId());
    let next: PaletteOption | undefined;
    if (e.key === 'ArrowDown') next = vis[(i + 1) % vis.length];
    else if (e.key === 'ArrowUp') next = vis[i <= 0 ? vis.length - 1 : i - 1];
    else if (e.key === 'Home') next = vis[0];
    else if (e.key === 'End') next = vis[vis.length - 1];
    else if (e.key === 'Enter') {
      e.preventDefault();
      if (i > -1) document.getElementById(this.optionId(vis[i]))?.click();
      return;
    }
    if (next) {
      e.preventDefault();
      this.setActive(next.id, true);
    }
  }

  protected onMouseMove(e: MouseEvent): void {
    const where = `${e.clientX},${e.clientY}`;
    if (where === this.lastPointer) return;
    this.lastPointer = where;
    const option = e.target instanceof Element ? e.target.closest('[role="option"]') : null;
    const id = option?.id.startsWith(`${this.p()}-o-`) ? option.id.slice(this.p().length + 3) : null;
    if (id && id !== this.activeId()) this.setActive(id, false);
  }

  protected run(o: PaletteOption): void {
    if (o.action === 'theme') this.theme.toggle();
    this.close();
    // Copy after closing: an announcement made inside the palette's dialog would go silent with it, and the
    // fallback copy must not run in a page made inert by the modal. The result decides what is announced.
    if (o.action === 'copy' && o.copyText) void this.copy(o.copyText);
  }

  private async copy(text: string): Promise<void> {
    const ok = await this.clipboard.write(text);
    if (ok) this.announcer.announce('Copied to clipboard');
    else this.announcer.announce(COPY_FAILED, { assertive: true });
  }

  protected onClosed(): void {
    this.query.set('');
    if (this.field()) this.field()!.nativeElement.value = '';
    this.activeId.set(this.options()[0]?.id ?? null);
  }

  private setActive(id: string, scroll: boolean): void {
    this.activeId.set(id);
    if (!scroll) return;
    const results = this.results()?.nativeElement;
    const option = document.getElementById(`${this.p()}-o-${id}`);
    if (!results || !option) return;
    // Keep it in view inside the results scroller only (never scroll the page).
    const r = results.getBoundingClientRect();
    const o = option.getBoundingClientRect();
    if (o.top < r.top) results.scrollTop -= r.top - o.top;
    else if (o.bottom > r.bottom) results.scrollTop += o.bottom - r.bottom;
  }

  private async load(q: string): Promise<void> {
    try {
      const result = await this.core.search(q);
      if (q === '') {
        this.loaded.set(result);
        return;
      }
      // Pages and actions are filtered here (kit keywords); the core adds connections and endpoints it matched.
      const base = this.loaded();
      if (!base || q !== this.query()) return;
      const names = new Set(base.connections.map((c) => c.name));
      const slugs = new Set(base.endpoints.map((e) => e.slug));
      this.loaded.set({
        ...base,
        connections: [...base.connections, ...result.connections.filter((c) => !names.has(c.name))],
        endpoints: [...base.endpoints, ...result.endpoints.filter((e) => !slugs.has(e.slug))],
      });
    } catch {
      // The core is away: keep what we have.
    }
  }
}
