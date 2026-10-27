import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../../code/code-block';
import { GwToggle } from '../../../kit/disclosure';
import { GwConnectorCard, GwConnectorCardActions, GwConnectorCardNote } from '../../../kit/elements/connector-card/connector-card';
import { GwIcon } from '../../../kit/icons';
import { GwMenu, GwMenuTrigger } from '../../../kit/menu';
import { GwSearchClear, GwSearchClearButton } from '../../../kit/search';
import { InstalledView, installedCountText, matches, onEndpointsText, plural, serverStatus, v } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureDetails, failureText } from '../dialogs/failure';

/** What a card of Installed asks the page to do (the dialogs live outside the tabs). */
export interface InstalledAction {
  readonly type: 'update' | 'disable' | 'enable' | 'restart' | 'uninstall' | 'turn-on' | 'keep';
  readonly view: InstalledView;
  readonly opener: HTMLElement | null;
}

/**
 * `<div gwInstalledPanel>` — Connectors › Installed (kit `#installed`): every connector type this gateway runs —
 * built-in ones, plugins and external MCP servers — as cards with their kind, trust, version, connections and the next
 * step. The search filters the grid as the person types and the count line (a live region) says how many match;
 * nothing matching shows the kit's empty state with Clear search.
 *
 * Plugin cards: Update (when the catalog has a newer version), Disable / Enable, Restart (failed host) and Uninstall in
 * the card's menu; the footer says what the plugin's process does ("Running · 54 MB", "Sleeping").
 */
@Component({
  selector: 'div[gwInstalledPanel]',
  imports: [RouterLink, GwIcon, GwConnectorCard, GwConnectorCardNote, GwConnectorCardActions, GwMenu, GwMenuTrigger, GwSearchClear, GwSearchClearButton, GwToggle, GwCodeBlock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-tabs__panel', role: 'tabpanel', id: 'installed', 'aria-labelledby': 'tab-installed', tabindex: '0' },
  templateUrl: './installed.panel.html',
})
export class GwInstalledPanel {
  protected readonly data = inject(ConnectorsData);

  /** The cards, built by the page from the types, connections, catalog and servers. */
  readonly views = input.required<readonly InstalledView[] | null>();
  /** Placeholders show only when the first answer is slow (kit: after 300 ms). */
  readonly slow = input(false);
  readonly busyKey = input<string | null>(null);
  readonly action = output<InstalledAction>();

  protected readonly query = signal('');
  protected readonly shown = computed(() => (this.views() ?? []).filter((v) => matches(v.search, this.query())));
  protected readonly countText = computed(() => installedCountText(this.views() ?? [], this.shown().length, this.query()));

  protected readonly plural = plural;
  protected readonly ver = v;
  protected readonly onEndpointsText = onEndpointsText;
  protected readonly serverStatus = serverStatus;
  protected readonly errorText = (e: unknown): string => failureText(e);
  protected readonly errorDetails = (e: unknown): string => failureDetails('GET /api/connector-types', e);

  protected act(type: InstalledAction['type'], view: InstalledView, e: Event): void {
    // A menu item's own element is hidden once the menu closes: focus goes back to the menu's trigger.
    const target = e.currentTarget as HTMLElement | null;
    const trigger = target?.closest('.gw-menu-host')?.querySelector<HTMLElement>('[aria-haspopup="menu"]') ?? null;
    this.action.emit({ type, view, opener: target?.getAttribute('role') === 'menuitem' ? trigger : target });
  }

  protected onSearch(value: string): void {
    this.query.set(value);
  }

  protected retry(): void {
    void this.data.types.refresh();
    void this.data.connections.refresh();
  }

  protected menuId(v: InstalledView): string {
    return `menu-plugin-${v.id.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  }
}
