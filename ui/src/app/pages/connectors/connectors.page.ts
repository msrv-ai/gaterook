import { DOCUMENT, ChangeDetectionStrategy, Component, Injector, afterNextRender, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CoreStore } from '../../core/stores/core.store';
import { Announcer } from '../../kit/announcer';
import { GwHowItWorks, GwHowItWorksStep } from '../../kit/elements/how-it-works/how-it-works';
import { GwIcon } from '../../kit/icons';
import { GwMenu, GwMenuTrigger } from '../../kit/menu';
import { GwTabs, selectFromFragment } from '../../kit/tabs';
import { ToastService } from '../../kit/toast.service';
import { CatalogView, InstalledView, catalogOffer, installedViews, joinAnd, listsCheckToast, plural, v } from './connectors-view';
import { ConnectorsData } from './connectors.data';
import { GwConfirmDialog } from './dialogs/confirm.dialog';
import { failureText } from './dialogs/failure';
import { GwMcpSignInDialog } from './dialogs/mcp-sign-in.dialog';
import { GwPluginInstallDialog, PluginChecked } from './dialogs/plugin-install.dialog';
import { GwPluginReviewDialog } from './dialogs/plugin-review.dialog';
import { GwPluginUpdateDialog } from './dialogs/plugin-update.dialog';
import { GwTemplateInstallDialog } from './dialogs/template-install.dialog';
import { CatalogAction, GwCatalogPanel } from './panels/catalog.panel';
import { GwInstalledPanel, InstalledAction } from './panels/installed.panel';
import { GwMcpServersPanel, McpServerAction } from './panels/mcp-servers.panel';
import { GwTemplatesPanel, TemplateAction } from './panels/templates.panel';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

/**
 * Connectors (ARCHITECTURE.md §8.4 "connectors"): the types of resource the gateway
 * can reach and the tools each brings, and the three ways to add one without rebuilding — a template, a plugin or an
 * MCP server.
 *
 * - Header: "Add connector" (the page's one primary) — New template (Templates tab), Install plugin or module (a
 *   JAR from this computer: a plugin, or a connector module, GateRook's own or the person's), Add MCP server.
 * - How it works: the three levels of connectors, folded and remembered.
 * - Tabs Installed / Catalog / Templates / MCP servers with their counts; `#installed|catalog|templates|mcp-servers`
 *   deep-links a tab (the app's fragment follower).
 * - The dialogs of the kit page live here, outside the tabs (a dialog inside a hidden panel can't show): Install
 *   plugin → its review (signed, or unsigned in `--danger` with an acknowledgement), Update with the manifest diff,
 *   Uninstall, Add MCP server → its review (`--danger`), Remove MCP server, and the confirmations of templates.
 *
 * Data: `ConnectorsData` (types, catalog, templates, servers; live through `plugin.state` / `mcp-server.state`).
 * States: placeholders after 300 ms, an error with Try again per tab, empty, no search match, not available yet (a
 * route of a step that isn't merged), out of date.
 */
@Component({
  selector: 'gw-connectors-page',
  imports: [
    RouterLink,
    GwIcon,
    GwMenu,
    GwMenuTrigger,
    GwTabs,
    GwHowItWorks,
    GwHowItWorksStep,
    GwInstalledPanel,
    GwCatalogPanel,
    GwTemplatesPanel,
    GwMcpServersPanel,
    GwPluginInstallDialog,
    GwPluginReviewDialog,
    GwPluginUpdateDialog,
    GwMcpSignInDialog,
    GwConfirmDialog,
    GwTemplateInstallDialog,
  ],
  providers: [ConnectorsData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './connectors.page.html',
})
export class ConnectorsPage {
  protected readonly data = inject(ConnectorsData);
  private readonly core = inject(CoreStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  private readonly doc = inject(DOCUMENT);
  private readonly fragment = toSignal(this.route.fragment);

  /** `?add=mcp-server` (an older link to Add MCP server): Add connection, on its MCP servers. */
  readonly add = input<string | null>(null);

  private readonly installDialog = viewChild.required(GwPluginInstallDialog);
  private readonly reviewDialog = viewChild.required(GwPluginReviewDialog);
  private readonly updateDialog = viewChild.required(GwPluginUpdateDialog);
  private readonly serverSignInDialog = viewChild.required(GwMcpSignInDialog);
  private readonly confirmDialog = viewChild.required(GwConfirmDialog);
  private readonly templateInstallDialog = viewChild.required(GwTemplateInstallDialog);
  private readonly templatesPanel = viewChild.required(GwTemplatesPanel);

  /** Placeholders only when the first answer is slow. */
  protected readonly slow = signal(false);
  /** The card whose action waits for the core (Update's preview, Install's check, Turn on). */
  protected readonly busyKey = signal<string | null>(null);
  /** The Catalog's Check now waits for the core. */
  protected readonly checking = signal(false);

  protected readonly installed = computed<InstalledView[] | null>(() => {
    const types = this.data.types.value();
    if (!types) return null;
    return installedViews(types.installed, this.data.connections.value() ?? [], this.data.catalog.value(), this.data.servers());
  });

  /** Tab counts, once their list answered (the kit's "Installed 12 · Catalog 5 · Templates 7 · MCP servers 1"). */
  protected readonly counts = computed(() => ({
    installed: this.installed()?.length ?? null,
    catalog: this.catalogCount(),
    templates: this.data.templates.value()?.length ?? null,
    servers: this.data.connections.value() ? this.data.servers().length : null,
  }));

  protected readonly plural = plural;

  /**
   * What the Catalog offers: GateRook's own modules that aren't installed, and the signed catalog's entries. Known
   * once the catalog answered, as the panel shows its cards only then.
   */
  private catalogCount(): number | null {
    return this.data.catalog.value() ? this.catalogOffer().length : null;
  }

  private catalogOffer(): CatalogView[] {
    return catalogOffer(this.data.types.value()?.groups, this.data.catalog.value());
  }

  constructor() {
    void this.data.start();
    // Back to a plain /connectors URL restores the default tab on the reused page.
    let hadFragment = false;
    effect(() => {
      const fragment = this.fragment();
      if (!fragment && hadFragment) {
        afterNextRender(() => {
          if (!this.route.snapshot.fragment) selectFromFragment(this.doc, 'installed');
        }, { injector: this.injector });
      }
      hadFragment = !!fragment;
    });
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (this.installed()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    effect(() => {
      if (this.add() !== 'mcp-server') return;
      untracked(() => void this.router.navigate(['/connections/new'], { queryParams: { show: 'mcp' }, replaceUrl: true }));
    });
    // The core came back (it was away, or another instance answers): everything may be stale.
    let wasRunning = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const running = this.core.running();
      const changes = this.core.instanceChanges();
      untracked(() => {
        if (running && (!wasRunning || changes !== instance)) void this.data.refreshAll();
        wasRunning = running;
        instance = changes;
      });
    });
  }

  // ── Header menu ───────────────────────────────────────────────────────────────────────────────────────────────

  protected onTabSelected(tab: string): void {
    const fragment = tab === 'tab-mcp' ? 'mcp-servers' : tab.replace(/^tab-/, '');
    // Keep sidebar shortcuts reusable after switching tabs, without adding a Back entry for each tab.
    void this.router.navigate([], { relativeTo: this.route, fragment, queryParamsHandling: 'preserve', replaceUrl: true });
  }

  protected openInstall(e: Event): void {
    this.installDialog().open(this.triggerOf(e));
  }

  /** Add MCP server: Add connection, on its MCP servers (External MCP server first, then the catalog). */
  protected openAddServer(): void {
    void this.router.navigate(['/connections/new'], { queryParams: { show: 'mcp' } });
  }

  // ── Plugins ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected onChecked(c: PluginChecked): void {
    this.reviewDialog().show(c.check, c.source, c.opener);
  }

  protected async onInstalledAction(a: InstalledAction): Promise<void> {
    const view = a.view;
    switch (a.type) {
      case 'update':
        if (view.update) await this.openUpdate(view.key, view.id, view.update.to, view.connections, view.card.tools, a.opener);
        return;
      case 'disable':
        return this.disable(view);
      case 'enable':
        return this.setEnabled(view, true);
      case 'restart':
        return this.restart(view);
      case 'uninstall':
        return this.confirmDialog().ask(
          {
            tone: 'danger',
            icon: 'trash-2',
            title: `Uninstall plugin ${view.name}?`,
            description: [
              {
                text: `Its code is unloaded and the JAR deleted. ${
                  view.connections
                    ? `${plural(view.connections, 'connection')} ${view.connections === 1 ? 'uses' : 'use'} it: ${view.connections === 1 ? 'its' : 'their'} tools stop working.`
                    : // Tools are added per connection: without one, none of its tools is on an endpoint (kit wording).
                      `No connection uses it and ${view.card.tools !== null ? `its ${plural(view.card.tools, 'tool')} ${view.card.tools === 1 ? 'is' : 'are'}` : 'its tools are'} on no endpoint, so nothing stops working.`
                } Install it again from the catalog to use it.`,
                code: false,
              },
            ],
            confirm: `Uninstall plugin ${view.name}`,
            run: async () => {
              await this.data.uninstallPlugin(view.id);
              this.toasts.show({ tone: 'success', title: `Plugin ${view.name} uninstalled`, body: 'Past calls stay in Activity.' });
            },
          },
          a.opener,
        );
      case 'uninstall-module':
        return this.uninstallModule(view, a.opener);
      case 'replace-module':
        return this.replaceModule(view, a.opener);
      case 'turn-on':
        return this.turnOn(view);
      case 'keep':
        return this.confirmDialog().ask(
          {
            tone: 'warning',
            icon: 'shield-alert',
            title: `Keep ${view.name} as it is?`,
            description: [
              {
                text: `${view.name} was changed outside GateRook and serves nothing until you review it. Keep it only if you made the change: its tools come back as stored.`,
                code: false,
              },
            ],
            confirm: `Keep ${view.name}`,
            run: async () => {
              await this.data.keepType(view.id);
              this.toasts.show({ tone: 'success', title: `${view.name} is in use again`, body: 'Its tools are back on the endpoints that have them.' });
            },
          },
          a.opener,
        );
    }
  }

  /** Every card of the module behind `view`: a module may add several connector types. */
  private moduleCards(view: InstalledView): InstalledView[] {
    const id = view.module?.id;
    return id ? (this.installed() ?? []).filter((x) => x.module?.id === id) : [];
  }

  /** Removing the person's own module takes all of its connector types; the core refuses while connections use one. */
  private uninstallModule(view: InstalledView, opener: HTMLElement | null): void {
    const module = view.module;
    if (!module) return;
    const cards = this.moduleCards(view);
    const names = cards.map((x) => x.name);
    const connections = cards.reduce((n, x) => n + x.connections, 0);
    const what = names.length > 1 ? `the connector types ${joinAnd(names)}` : `the connector type ${view.name}`;
    this.confirmDialog().ask(
      {
        tone: 'danger',
        icon: 'trash-2',
        title: `Uninstall module ${module.id}?`,
        description: [
          {
            text: connections
              ? `${plural(connections, 'connection')} ${connections === 1 ? 'uses' : 'use'} it: remove ${connections === 1 ? 'that connection' : 'those connections'} first, or the module stays. Uninstalling unloads its code, deletes its package and removes ${what}.`
              : `Its code is unloaded, its package deleted and ${what} removed. No connection uses it, so nothing stops working. Install it from its file again to use it.`,
            code: false,
          },
        ],
        confirm: `Uninstall module ${module.id}`,
        run: async () => {
          await this.data.uninstallModule(module.id);
          this.toasts.show({ tone: 'success', title: `Module ${module.id} uninstalled`, body: 'Past calls stay in Activity.' });
        },
      },
      opener,
    );
  }

  /** Back to GateRook's build of a module the person had their own build of: its tools are GateRook's own again. */
  private replaceModule(view: InstalledView, opener: HTMLElement | null): void {
    const module = view.module;
    if (!module?.listedVersion) return;
    const to = v(module.listedVersion);
    this.confirmDialog().ask(
      {
        tone: 'warning',
        icon: 'replace',
        title: `Replace your ${view.name} module with GateRook's ${to}?`,
        description: [
          {
            text: `GateRook downloads its own build, which its signed list has, and switches to it. Requests that wait for approval on its connections are rejected. Each tool keeps the mode it has; GateRook's list updates the module from then on.`,
            code: false,
          },
        ],
        confirm: `Use GateRook's ${to}`,
        run: async () => {
          await this.data.replaceOwnModule(module.id);
          this.toasts.show({ tone: 'success', title: `GateRook's ${view.name} ${to} is installing`, body: 'Its card follows the download. Calls go on with your module until then.' });
        },
      },
      opener,
    );
  }

  private async openUpdate(key: string, id: string, to: string, connections: number, tools: number | null, opener: HTMLElement | null): Promise<void> {
    if (this.busyKey()) return;
    this.busyKey.set(key);
    try {
      const preview = await this.data.updatePreview(id, to);
      this.updateDialog().show(preview, connections, tools, opener);
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `Update to ${v(to)} didn't open`, body: failureText(e, 'This build of the gateway core can’t update plugins yet.') });
    } finally {
      this.busyKey.set(null);
    }
  }

  /** Disable at once, with Undo (kit: reversible actions don't ask first). */
  private async disable(view: InstalledView): Promise<void> {
    try {
      await this.data.setPluginEnabled(view.id, false);
      this.toasts.show({
        tone: 'neutral',
        icon: 'power-off',
        title: `${view.name} disabled`,
        body: 'Unloaded. Its tools are unavailable on every endpoint until you enable it.',
        actions: [{ label: 'Undo', onClick: () => void this.setEnabled(view, true, true) }],
      });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${view.name} wasn't disabled`, body: failureText(e, 'This build of the gateway core can’t manage plugins yet.') });
    }
  }

  private async setEnabled(view: InstalledView, enabled: boolean, undo = false): Promise<void> {
    try {
      await this.data.setPluginEnabled(view.id, enabled);
      if (undo) this.announcer.announce(`${view.name} enabled again`);
      else this.toasts.show({ tone: 'success', title: `${view.name} enabled`, body: 'Loaded. Its tools are back with their modes.' });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${view.name} wasn't enabled`, body: failureText(e, 'This build of the gateway core can’t manage plugins yet.') });
    }
  }

  private async restart(view: InstalledView): Promise<void> {
    try {
      await this.data.restartPlugin(view.id);
      this.toasts.show({ tone: 'success', title: `${view.name} restarted`, body: 'Its process starts on the next call.' });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${view.name} didn't restart`, body: failureText(e, 'This build of the gateway core can’t manage plugins yet.') });
    }
  }

  /** A built-in type switched off: turn it on again (no confirmation: it only gives back what was there). */
  private async turnOn(view: InstalledView): Promise<void> {
    if (this.busyKey()) return;
    this.busyKey.set(view.key);
    try {
      await this.data.enableType(view.id);
      this.toasts.show({ tone: 'success', title: `${view.name} turned on`, body: 'Its connections and tools work again.' });
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: `${view.name} wasn't turned on`, body: failureText(e) });
    } finally {
      this.busyKey.set(null);
    }
  }

  // ── Catalog ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Check now: the core checks both signed lists at once instead of when they are six hours old, and a toast says
   * what the check found. The panel shows the rest: the cards, when the lists were checked, and why one couldn't be.
   */
  protected async checkCatalog(): Promise<void> {
    if (this.checking()) return;
    const before = this.catalogOffer();
    this.checking.set(true);
    try {
      const found = await this.data.checkLists();
      this.toasts.show(listsCheckToast(before, this.catalogOffer(), found.catalog, found.modules));
    } catch (e) {
      this.toasts.show({ tone: 'danger', title: "The catalog wasn't checked", body: failureText(e) });
    } finally {
      this.checking.set(false);
    }
  }

  protected async onCatalogAction(a: CatalogAction): Promise<void> {
    const view = a.view;
    if (a.type === 'install-file' || !view) {
      this.installDialog().open(a.opener);
      return;
    }
    const e = view.entry;
    // One of GateRook's own modules has no entry and nothing to install by hand: its card links to Add connection.
    if (!e) return;
    switch (a.type) {
      case 'install-plugin':
        return this.runBusy(
          view.key,
          async () => {
            const check = await this.data.checkPlugin({ catalog: `${e.id}@${e.version}` });
            this.reviewDialog().show(check, null, a.opener);
          },
          `${e.name} wasn't checked`,
          'This build of the gateway core can’t install plugins yet.',
        );
      case 'update-plugin': {
        const installed = this.installed()?.find((x) => x.kind === 'plugin' && x.id === e.id);
        await this.openUpdate(view.key, e.id, e.version, installed?.connections ?? 0, installed?.card.tools ?? null, a.opener);
        return;
      }
      case 'install-template':
        this.templateInstallDialog().show(e, a.opener);
        return;
      case 'install-server':
        // Add connection sets it up: its environment, the review of what runs, then its endpoints.
        void this.router.navigate(['/connections/new'], { queryParams: { catalog: `${e.id}@${e.version}` } });
        return;
      default:
        return;
    }
  }

  private async runBusy(key: string, run: () => Promise<void>, title: string, notAvailable: string): Promise<void> {
    if (this.busyKey()) return;
    this.busyKey.set(key);
    try {
      await run();
    } catch (e) {
      this.toasts.show({ tone: 'danger', title, body: failureText(e, notAvailable) });
    } finally {
      this.busyKey.set(null);
    }
  }

  // ── Templates ─────────────────────────────────────────────────────────────────────────────────────────────────

  protected onTemplateAction(a: TemplateAction): void {
    const t = a.template;
    if (a.type === 'delete') {
      this.confirmDialog().ask(
        {
          tone: 'danger',
          icon: 'trash-2',
          title: `Delete template ${t.name}?`,
          description: [{ text: 'Its tools leave every endpoint at once and its versions are deleted. Past calls stay in Activity.', code: false }],
          confirm: `Delete template ${t.name}`,
          run: async () => {
            await this.data.deleteTemplate(t.id);
            this.templatesPanel().forget(t.id);
            this.toasts.show({ tone: 'success', title: `Template ${t.name} deleted`, body: 'Past calls stay in Activity.' });
          },
        },
        a.opener,
      );
      return;
    }
    this.confirmDialog().ask(
      {
        tone: 'warning',
        icon: 'shield-alert',
        title: `Keep template ${t.name} as it is?`,
        description: [{ text: `${t.name} was changed outside GateRook and serves nothing until you review it. Keep it only if you made the change.`, code: false }],
        confirm: `Keep template ${t.name}`,
        run: async () => {
          await this.data.keepTemplate(t.id);
          this.toasts.show({ tone: 'success', title: `Template ${t.name} is in use again`, body: 'Its tools are back on the endpoints that have them.' });
        },
      },
      a.opener,
    );
  }

  // ── MCP servers ───────────────────────────────────────────────────────────────────────────────────────────────

  /** A server to add opens Add connection on its Configure (the catalog cards) or on the MCP servers (Add MCP server). */
  protected onServerAction(a: McpServerAction): void {
    if (a.type === 'use-registry') {
      // The result the person chose, from the registry it was found in; a Popular shortcut trusts only the official one.
      void this.router.navigate(['/connections/new'], { queryParams: { registryServer: a.result.server.name } });
      return;
    }
    if (a.type === 'add-registry') {
      void this.router.navigate(['/connections/new'], { queryParams: { registry: a.registryName } });
      return;
    }
    if (a.type === 'add-profile') {
      void this.router.navigate(['/connections/new'], { queryParams: { profile: a.profile.id } });
      return;
    }
    if (a.type === 'add' || !a.server) {
      this.openAddServer();
      return;
    }
    const s = a.server;
    if (a.type === 'sign-in') {
      this.serverSignInDialog().show(s, a.opener);
      return;
    }
    if (a.type === 'sign-out') {
      this.confirmDialog().ask(
        {
          tone: 'warning',
          icon: 'key-round',
          title: `Sign out of ${s.name}?`,
          description: [
            {
              text: `GateRook forgets the sign-in and revokes it at the server where it allows that. The calls of ${s.name} don’t run until you sign in again; its tools and modes stay.`,
              code: false,
            },
          ],
          confirm: `Sign out of ${s.name}`,
          run: async () => {
            await this.data.signOut(s.name);
            this.toasts.show({ tone: 'success', title: `Signed out of ${s.name}`, body: 'Sign in again from its row when you need its tools.' });
          },
        },
        a.opener,
      );
      return;
    }
    const tools = s.tools !== null ? `its ${plural(s.tools, 'tool')}` : 'its tools';
    const used = s.usedBy.length ? `${s.usedBy.join(', ')} ${s.usedBy.length === 1 ? 'loses' : 'lose'} them.` : 'No endpoint uses them yet.';
    const secrets = s.secrets.map((x) => x.secret).filter((x): x is string => !!x);
    const description: { text: string; code: boolean }[] = [{ text: `The process stops and ${tools} are removed. ${used}`, code: false }];
    // Review 2026-10-26: the core deletes the server's connection with its secrets (`secretsDestroyed`); the kit's
    // "stays in Secrets" said the opposite in a danger dialog.
    secrets.forEach((name, i) => {
      description.push({ text: i === 0 ? (secrets.length === 1 ? ' Its secret ' : ' Its secrets ') : i === secrets.length - 1 ? ' and ' : ', ', code: false });
      description.push({ text: name, code: true });
    });
    if (secrets.length) description.push({ text: secrets.length === 1 ? ' is destroyed.' : ' are destroyed.', code: false });
    this.confirmDialog().ask(
      {
        tone: 'danger',
        icon: 'trash-2',
        title: `Remove MCP server ${s.name}?`,
        description,
        confirm: `Remove MCP server ${s.name}`,
        run: async () => {
          await this.data.removeServer(s.name);
          this.toasts.show({
            tone: 'success',
            title: `MCP server ${s.name} removed`,
            body: `Its process stopped${secrets.length ? ` and ${secrets.length === 1 ? 'its secret was' : 'its secrets were'} destroyed` : ''}. Past calls stay in Activity.`,
          });
        },
      },
      a.opener,
    );
  }

  /** The menu item's menu trigger: the item itself is hidden once the menu closes. */
  private triggerOf(e: Event): HTMLElement | null {
    const target = e.currentTarget as HTMLElement | null;
    return target?.closest('.gw-menu-host')?.querySelector<HTMLElement>('[aria-haspopup="menu"]') ?? target;
  }
}
