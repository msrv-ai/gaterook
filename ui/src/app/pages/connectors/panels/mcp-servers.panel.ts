import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { GwAccessSwitch } from '../../../kit/access-switch';
import { RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../../code/code-block';
import { Announcer } from '../../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwToggle } from '../../../kit/disclosure';
import { GwIcon } from '../../../kit/icons';
import { GwMenu, GwMenuTrigger } from '../../../kit/menu';
import { ToastService } from '../../../kit/toast.service';
import { McpServerView, joinAnd, plural, serverActivityQuery, serverPaths, serverStatus, signInText } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { FEATURED_MCP_SERVERS } from '../mcp-featured-servers';
import { profileForRegistryName, profileLogo } from '../mcp-profiles';
import { McpProfile, McpServersApi } from '../../../core/api/plugins.api';
import { failureDetails, failureText } from '../dialogs/failure';
import { GwMcpRegistrySearch, McpRegistrySelection } from './mcp-registry-search';

/**
 * What a server row asks the page to do: Add MCP server, Remove, or sign in to it again and sign out (their dialogs
 * live outside the tabs).
 */
export type McpServerAction = {
  readonly type: 'add' | 'remove' | 'sign-in' | 'sign-out';
  readonly server: McpServerView | null;
  readonly opener: HTMLElement | null;
} | {
  readonly type: 'add-registry';
  readonly registryName: string;
  readonly server: null;
  readonly opener: HTMLElement | null;
} | {
  readonly type: 'add-profile';
  readonly profile: McpProfile;
  readonly server: null;
  readonly opener: HTMLElement | null;
} | (McpRegistrySelection & { readonly type: 'use-registry'; readonly server: null });

/**
 * `<div gwMcpServersPanel>` — Connectors › MCP servers (kit `#mcp-servers`): every external MCP server behind the
 * gateway — a connection of the `mcp-server` type — with how it runs (stdio command or HTTP URL), the secrets it
 * gets (by name, linked to Secrets), its tool count and process state. Restart re-lists its tools; the row menu opens
 * runtime details in a drawer, goes to Activity (`tool:github_*`) or an endpoint to add its tools, and removes it.
 * Under the table: the reminder that its tools are Off until added to an endpoint.
 */
@Component({
  selector: 'div[gwMcpServersPanel]',
  imports: [GwAccessSwitch, RouterLink, GwIcon, GwMenu, GwMenuTrigger, GwToggle, GwCodeBlock, GwDialog, GwDialogClose, GwMcpRegistrySearch],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-tabs__panel', role: 'tabpanel', id: 'mcp-servers', 'aria-labelledby': 'tab-mcp', tabindex: '0' },
  templateUrl: './mcp-servers.panel.html',
})
export class GwMcpServersPanel {
  protected readonly data = inject(ConnectorsData);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);

  readonly slow = input(false);
  readonly action = output<McpServerAction>();
  protected readonly featured = FEATURED_MCP_SERVERS;
  /** GateRook's verified profiles of vendors' own servers (Cloud platforms); empty from a core without them. */
  protected readonly profiles = signal<readonly McpProfile[]>([]);
  protected readonly profileLogo = profileLogo;
  protected readonly profileFor = (registryName: string): McpProfile | null => profileForRegistryName(this.profiles(), registryName);

  constructor() {
    const mcp = inject(McpServersApi);
    // Cards are a shortcut: without them (an older core, a failed load) every server can still be added by hand.
    mcp.profiles().then((p) => this.profiles.set(Array.isArray(p) ? p : []), () => this.profiles.set([]));
  }

  protected readonly restarting = signal<string | null>(null);
  protected readonly selectedServer = signal<McpServerView | null>(null);
  protected readonly servers = computed(() => (this.data.connections.value() ? this.data.servers() : null));
  /** Servers whose tools are on no endpoint: the kit's reminder under the table. */
  protected readonly unused = computed(() => (this.servers() ?? []).filter((s) => !s.usedBy.length));

  protected readonly serverStatus = serverStatus;
  protected readonly signInText = signInText;
  protected readonly activityQuery = serverActivityQuery;
  protected readonly paths = serverPaths;
  protected readonly plural = plural;
  protected readonly joinAnd = joinAnd;
  protected readonly errorText = (e: unknown): string => failureText(e);
  protected readonly errorDetails = (e: unknown): string => failureDetails('GET /api/connections', e);

  protected add(e: Event): void {
    this.action.emit({ type: 'add', server: null, opener: e.currentTarget as HTMLElement | null });
  }

  /** A Popular card sets its server up from GateRook's verified profile when there is one, else from the registry. */
  protected addFeatured(registryName: string, e: Event): void {
    const profile = this.profileFor(registryName);
    if (profile) {
      this.addProfile(profile, e);
      return;
    }
    this.action.emit({ type: 'add-registry', registryName, server: null, opener: e.currentTarget as HTMLElement | null });
  }

  protected addProfile(profile: McpProfile, e: Event): void {
    this.action.emit({ type: 'add-profile', profile, server: null, opener: e.currentTarget as HTMLElement | null });
  }

  protected useRegistry(selection: McpRegistrySelection): void {
    this.action.emit({ type: 'use-registry', server: null, ...selection });
  }

  protected remove(s: McpServerView, e: Event): void {
    this.fromMenu('remove', s, e);
  }

  protected showRuntime(s: McpServerView, e: Event, drawer: GwDialog): void {
    this.selectedServer.set(s);
    drawer.openAfterRender(this.menuOpener(e));
  }

  /** Sign in again (the row's button, or its menu): the page opens the sign-in dialog. */
  protected signIn(s: McpServerView, e: Event): void {
    this.fromMenu('sign-in', s, e);
  }

  protected signOut(s: McpServerView, e: Event): void {
    this.fromMenu('sign-out', s, e);
  }

  /** A menu item's dialog returns focus to the menu's trigger: the item itself is hidden once the menu closes. */
  private fromMenu(type: 'remove' | 'sign-in' | 'sign-out', s: McpServerView, e: Event): void {
    this.action.emit({ type, server: s, opener: this.menuOpener(e) });
  }

  private menuOpener(e: Event): HTMLElement | null {
    const target = e.currentTarget as HTMLElement | null;
    const trigger = target?.closest('.gw-menu-host')?.querySelector<HTMLElement>('[aria-haspopup="menu"]') ?? null;
    return trigger ?? target;
  }

  /** Restart (kit: a toast "Tools listed again: 26, same as before."). */
  protected async restart(s: McpServerView): Promise<void> {
    if (this.restarting()) return;
    this.restarting.set(s.name);
    this.announcer.announce(`Restarting ${s.name}`);
    try {
      const r = await this.data.restartServer(s.name);
      const tools = r?.tools;
      const before = r?.previousTools;
      this.toasts.show({
        tone: 'success',
        title: `${s.name} restarted`,
        body:
          typeof tools === 'number'
            ? `Tools listed again: ${tools.toLocaleString('en-US')}${typeof before === 'number' ? (before === tools ? ', same as before.' : `, ${before.toLocaleString('en-US')} before.`) : '.'}`
            : 'Its tools are listed again.',
      });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${s.name} didn't restart`, body: failureText(e, 'This build of the gateway core can’t restart MCP servers yet.') });
    } finally {
      this.restarting.set(null);
    }
  }

  /** "GITHUB_PERSONAL_ACCESS_TOKEN from the secret github-mcp/token" for the title of a secret's link. */
  protected secretTitle(name: string, secret: string | null): string {
    return secret ? `Value from the secret ${secret}` : `Value of ${name} from a secret`;
  }

  /** "Started by the core on the first call, stopped after 15 min without calls." */
  protected processText(s: McpServerView): string {
    const start = s.start === 'with-core' ? 'Started with the core' : 'Started by the core on the first call';
    const stop = s.idleStopMinutes ? `, stopped after ${s.idleStopMinutes} min without calls.` : ', and kept running.';
    return `${start}${stop} A crash never stops the core.`;
  }

  protected retry(): void {
    void this.data.connections.refresh();
  }

  protected menuId(s: McpServerView): string {
    return `menu-mcp-${s.name.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  }
}
