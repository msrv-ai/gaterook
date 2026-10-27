import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwCodeBlock } from '../../../code/code-block';
import { Announcer } from '../../../kit/announcer';
import { GwToggle } from '../../../kit/disclosure';
import { GwIcon } from '../../../kit/icons';
import { GwMenu, GwMenuTrigger } from '../../../kit/menu';
import { ToastService } from '../../../kit/toast.service';
import { McpServerView, joinAnd, plural, serverActivityQuery, serverPaths, serverStatus } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureDetails, failureText } from '../dialogs/failure';

/** What a server row asks the page to do: Add MCP server or Remove (their dialogs live outside the tabs). */
export interface McpServerAction {
  readonly type: 'add' | 'remove';
  readonly server: McpServerView | null;
  readonly opener: HTMLElement | null;
}

/**
 * `<div gwMcpServersPanel>` — Connectors › MCP servers (kit `#mcp-servers`): every external MCP server behind the
 * gateway — a connection of the `mcp-server` type — with how it runs (stdio command or HTTP URL), the secrets it
 * gets (by name, linked to Secrets), its tool count and process state. Restart re-lists its tools; the row menu goes
 * to Activity (`tool:github_*`), to an endpoint to add its tools, and removes it. Under the table: the reminder that
 * its tools are Off until added to an endpoint, and "How github-mcp runs" (process, variables, logs, protocol).
 */
@Component({
  selector: 'div[gwMcpServersPanel]',
  imports: [RouterLink, GwIcon, GwMenu, GwMenuTrigger, GwToggle, GwCodeBlock],
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

  protected readonly restarting = signal<string | null>(null);
  protected readonly servers = computed(() => (this.data.connections.value() ? this.data.servers() : null));
  /** Servers whose tools are on no endpoint: the kit's reminder under the table. */
  protected readonly unused = computed(() => (this.servers() ?? []).filter((s) => !s.usedBy.length));

  protected readonly serverStatus = serverStatus;
  protected readonly activityQuery = serverActivityQuery;
  protected readonly paths = serverPaths;
  protected readonly plural = plural;
  protected readonly joinAnd = joinAnd;
  protected readonly errorText = (e: unknown): string => failureText(e);
  protected readonly errorDetails = (e: unknown): string => failureDetails('GET /api/connections', e);

  protected add(e: Event): void {
    this.action.emit({ type: 'add', server: null, opener: e.currentTarget as HTMLElement | null });
  }

  protected remove(s: McpServerView, e: Event): void {
    const target = e.currentTarget as HTMLElement | null;
    const trigger = target?.closest('.gw-menu-host')?.querySelector<HTMLElement>('[aria-haspopup="menu"]') ?? null;
    this.action.emit({ type: 'remove', server: s, opener: trigger ?? target });
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
