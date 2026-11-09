import { ChangeDetectionStrategy, Component, inject, signal, viewChild } from '@angular/core';
import { McpSignIn } from '../../../core/api/plugins.api';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwIcon } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { McpServerView } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { GwMcpSignIn } from './mcp-sign-in';

let nextId = 0;

/**
 * `<dialog gwMcpSignInDialog>`: signs in to a stored MCP server again — after the server refused to renew the sign-in,
 * wants more permissions, or after Sign out. The new tokens replace the old ones once the person allowed access, and the
 * server's session starts over with them; its tools and modes stay as they are. Initial focus: Sign in.
 */
@Component({
  selector: 'dialog[gwMcpSignInDialog]',
  imports: [GwIcon, GwDialogClose, GwMcpSignIn],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-dialog gw-dialog--md',
    '[attr.aria-labelledby]': 'server() ? uid + "-t" : null',
    '[attr.aria-describedby]': 'server() ? uid + "-d" : null',
    '(close)': 'block()?.reset()',
  },
  template: `
    @if (server(); as s) {
      <header class="gw-dialog__header">
        <span class="gw-icon-tile"><svg lucideIcon="log-in" class="gw-icon"></svg></span>
        <h2 class="gw-dialog__title" [id]="uid + '-t'">Sign in to <span class="gw-mono">{{ s.name }}</span></h2>
        <p class="gw-dialog__description" [id]="uid + '-d'">{{ s.signIn?.message || 'The new sign-in replaces the old one once you allow access. Its tools and their modes stay as they are.' }}</p>
        <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
      </header>
      <div class="gw-dialog__body">
        <div gwMcpSignIn [start]="start" [host]="host()" [autofocus]="true" (signedIn)="done($event)"></div>
      </div>
      <footer class="gw-dialog__footer">
        <button class="gw-btn" type="button" gwDialogClose>Close</button>
      </footer>
    }
  `,
})
export class GwMcpSignInDialog {
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly toasts = inject(ToastService);

  protected readonly uid = `msd-${++nextId}`;
  protected readonly server = signal<McpServerView | null>(null);
  protected readonly host = signal<string | null>(null);
  protected readonly block = viewChild(GwMcpSignIn);

  protected readonly start = (): Promise<McpSignIn> => {
    const s = this.server();
    return s ? this.data.signInAgain(s.name) : Promise.reject(new Error('No server'));
  };

  show(server: McpServerView, opener: HTMLElement | null): void {
    this.block()?.reset();
    this.server.set(server);
    let host: string | null = null;
    try {
      host = server.signIn?.issuer ? new URL(server.signIn.issuer).host : null;
    } catch {
      host = null;
    }
    this.host.set(host);
    this.dialog.openAfterRender(opener);
  }

  protected async done(s: McpSignIn): Promise<void> {
    const server = this.server();
    if (!server) return;
    await this.data.loadSignIn(server.name);
    this.dialog.close('signed-in');
    this.toasts.show({
      tone: 'success',
      title: `Signed in to ${server.name}`,
      body: `GateRook keeps the new sign-in${s.authorizationHost ? ` from ${s.authorizationHost}` : ''} and renews it. Its next call uses it.`,
    });
  }
}
