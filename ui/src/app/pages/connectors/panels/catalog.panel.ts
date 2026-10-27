import { ChangeDetectionStrategy, Component, DOCUMENT, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../../code/code-block';
import { CoreStore } from '../../../core/stores/core.store';
import { TimeService } from '../../../core/time/time.service';
import { GwToggle } from '../../../kit/disclosure';
import { GwConnectorCard, GwConnectorCardActions } from '../../../kit/elements/connector-card/connector-card';
import { GwIcon } from '../../../kit/icons';
import { GwSearchClear, GwSearchClearButton } from '../../../kit/search';
import { CatalogView, catalogViews, matches, plural, v } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureDetails, failureText } from '../dialogs/failure';

/** What a catalog card asks the page to do; `install-file` is the toolbar's "Install from file or URL". */
export interface CatalogAction {
  readonly type: CatalogView['action'] | 'install-file';
  readonly view: CatalogView | null;
  readonly opener: HTMLElement | null;
}

/**
 * `<div gwCatalogPanel>` — Connectors › Catalog (kit `#catalog`): the signed catalog (`GET /api/catalog`, step K6)
 * — plugins, templates and MCP servers — with signed / unsigned, the version and one action each: Install (a plugin is
 * checked by the core first, an unsigned one reviewed in a danger dialog), Update (an installed plugin with a newer
 * release: "You run v1.2.0."), Open (an MCP server already installed). The count line says which core it is for and
 * when the index was checked; offline, the last good index stays with its age.
 *
 * Until the core serves the catalog the panel says so and keeps "Install from file or URL".
 */
@Component({
  selector: 'div[gwCatalogPanel]',
  imports: [RouterLink, GwIcon, GwConnectorCard, GwConnectorCardActions, GwSearchClear, GwSearchClearButton, GwToggle, GwCodeBlock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-tabs__panel', role: 'tabpanel', id: 'catalog', 'aria-labelledby': 'tab-catalog', tabindex: '0' },
  templateUrl: './catalog.panel.html',
})
export class GwCatalogPanel {
  protected readonly data = inject(ConnectorsData);
  private readonly core = inject(CoreStore);
  private readonly time = inject(TimeService);
  private readonly doc = inject(DOCUMENT);

  readonly slow = input(false);
  /** The card whose action runs (Install while the core checks the package). */
  readonly busyKey = input<string | null>(null);
  readonly action = output<CatalogAction>();

  protected readonly query = signal('');
  protected readonly views = computed(() => {
    const index = this.data.catalog.value();
    return index ? catalogViews(index) : null;
  });
  protected readonly shown = computed(() => (this.views() ?? []).filter((x) => matches(x.search, this.query())));

  /** "For core v0.4.0 · checked 3 h ago"; offline: the last good index and its age. */
  protected readonly note = computed(() => {
    const index = this.data.catalog.value();
    const version = v(this.core.version());
    const checked = index?.checkedAt ? this.time.relative(index.checkedAt) : null;
    const parts: string[] = [];
    if (version) parts.push(`For core ${version}`);
    if (index?.offline) parts.push(checked ? `offline, checked ${checked}` : 'offline');
    else if (checked) parts.push(`checked ${checked}`);
    return parts.join(' · ');
  });
  protected readonly countText = computed(() => {
    const q = this.query().trim();
    return q ? `${this.shown().length} of ${plural(this.views()?.length ?? 0, 'catalog connector')}` : this.note();
  });
  /** The index's signature didn't verify: the page shows the last good one (§7.5). */
  protected readonly signatureFailed = computed(() => this.data.catalog.value()?.signature?.ok === false);
  /**
   * Why the last check failed or why nothing installs from the catalog now, in the core's words (K6: "No catalog is
   * configured for this build.", "The catalog can't be reached: …", an expired signature); null when all is well.
   */
  protected readonly problem = computed(() => this.data.catalog.value()?.problem?.trim() || null);

  protected readonly errorText = (e: unknown): string => failureText(e);
  protected readonly errorDetails = (e: unknown): string => failureDetails('GET /api/catalog', e);

  protected act(view: CatalogView | null, type: CatalogAction['type'], e: Event | null): void {
    const target = (e?.currentTarget as HTMLElement | null) ?? null;
    this.action.emit({ type, view, opener: target });
  }

  /** The card's own button (connector-card `act`) is the focused element when it fires. */
  protected fromCard(view: CatalogView): void {
    const active = this.doc.activeElement instanceof HTMLElement ? this.doc.activeElement : null;
    this.action.emit({ type: view.action, view, opener: active });
  }

  protected onSearch(value: string): void {
    this.query.set(value);
  }

  protected retry(): void {
    void this.data.catalog.refresh();
  }
}
