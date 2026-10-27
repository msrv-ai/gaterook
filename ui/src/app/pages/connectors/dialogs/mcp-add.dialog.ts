import { ChangeDetectionStrategy, Component, ElementRef, inject, output, signal } from '@angular/core';
import { McpServerConfig, McpServerReview, McpServerSource } from '../../../core/api/plugins.api';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwIcon } from '../../../kit/icons';
import { tokenizeCommand } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureText } from './failure';

/**
 * One row of "Variables or headers": the name the process gets. Its value is a new secret of the server, typed in the
 * review right before the start (the core takes no existing secret by name: a server's values are its own secrets).
 */
export interface McpVariableDraft {
  readonly name: string;
}

/**
 * What the review shows and what starting the server sends: the core's review and the request it reviewed. The
 * request carries no values: they are typed in the review and sent once, with the start.
 */
export interface McpReviewed {
  readonly review: McpServerReview;
  readonly source: McpServerSource;
  /** The name and transport as asked (the review may name them too). */
  readonly name: string;
  readonly transport: 'stdio' | 'http';
  /** The command or URL as typed (empty for a catalog server: the core resolves it). */
  readonly target: string;
  /** The variables (or headers) as typed; empty for a catalog server, whose review names them. */
  readonly variables: readonly { readonly name: string }[];
  readonly opener: HTMLElement | null;
}

export interface McpFormErrors {
  readonly name?: string;
  readonly command?: string;
  readonly url?: string;
  readonly variables?: string;
}

const SERVER_NAME = /^[a-z][a-z0-9-]{0,63}$/;
const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const HEADER_NAME = /^[A-Za-z0-9-]{1,64}$/;
const SERVER_URL = /^(https:\/\/[^\s/]+|http:\/\/(127\.0\.0\.1|localhost)[:/])/i;

/**
 * The request "Review and start" sends (`POST /api/mcp-servers/review`), or what keeps the form from being sent. The
 * command becomes argv (quotes only, never a shell); each variable (or header) is a name whose value the review asks
 * for; rows left empty are skipped, a name used twice is refused.
 */
export function mcpRequest(
  name: string,
  transport: 'stdio' | 'http',
  command: string,
  url: string,
  variables: readonly McpVariableDraft[],
): { readonly source: McpServerSource; readonly errors: null } | { readonly source: null; readonly errors: McpFormErrors } {
  const errors: { -readonly [K in keyof McpFormErrors]?: string } = {};
  const n = name.trim();
  if (!n) errors.name = 'Name the server: lowercase letters, digits and -.';
  else if (!SERVER_NAME.test(n)) errors.name = 'Use lowercase letters, digits and -, starting with a letter.';
  let argv: string[] = [];
  if (transport === 'stdio') {
    const t = tokenizeCommand(command);
    if (t.error) errors.command = t.error;
    else if (!t.argv.length) errors.command = 'Enter the command that starts the server.';
    argv = t.argv;
  } else if (!url.trim()) {
    errors.url = 'Enter the URL of the server.';
  } else if (!SERVER_URL.test(url.trim())) {
    errors.url = 'Use an HTTPS URL, or http://127.0.0.1 for a server on this computer.';
  }
  const bindings: { name: string }[] = [];
  const seen = new Set<string>();
  for (const v of variables) {
    const vn = v.name.trim();
    if (!vn) continue;
    if (!(transport === 'http' ? HEADER_NAME : ENV_NAME).test(vn)) {
      errors.variables =
        transport === 'http' ? `${vn} isn't a header name: letters, digits and -.` : `${vn} isn't a variable name: letters, digits and _, not starting with a digit.`;
      break;
    }
    // Header names don't depend on case; variable names do.
    const key = transport === 'http' ? vn.toLowerCase() : vn;
    if (seen.has(key)) {
      errors.variables = `${vn} is named twice. Give each ${transport === 'http' ? 'header' : 'variable'} once.`;
      break;
    }
    seen.add(key);
    bindings.push({ name: vn });
  }
  if (Object.keys(errors).length) return { source: null, errors };
  const config: McpServerConfig =
    transport === 'stdio' ? { name: n, transport, command: argv, env: bindings } : { name: n, transport, url: url.trim(), headers: bindings };
  return { source: { config }, errors: null };
}

let nextId = 0;

/**
 * `<dialog gwMcpAddDialog>` (kit `dlg-add-mcp`): put an MCP server behind the gateway — its name (it prefixes the
 * tool names), stdio command or HTTP URL, and the variables (or headers) whose values come from secrets. Nothing
 * starts here: "Review and start" asks the core what would run (`POST /api/mcp-servers/review`) and opens the review,
 * which starts it. Focus starts on Name; each refusal stays at its field.
 */
@Component({
  selector: 'dialog[gwMcpAddDialog]',
  imports: [GwIcon, GwDialogClose],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-dialog gw-dialog--lg',
    '[attr.aria-labelledby]': 'uid + "-t"',
    '[attr.aria-describedby]': 'uid + "-d"',
  },
  template: `
    <form method="dialog" novalidate (submit)="submit($event)">
      <header class="gw-dialog__header">
        <span class="gw-icon-tile"><svg lucideIcon="plug" class="gw-icon"></svg></span>
        <h2 class="gw-dialog__title" [id]="uid + '-t'">Add MCP server</h2>
        <p class="gw-dialog__description" [id]="uid + '-d'">Put any MCP server behind the gateway. It runs as its own process; its tools get modes, approvals and audit.</p>
        <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
      </header>
      <div class="gw-dialog__body">
        <div class="gw-stack">
          <div class="gw-field">
            <label class="gw-field__label" [for]="uid + '-name'">Name</label>
            <input class="gw-input gw-input--mono" [id]="uid + '-name'" type="text" autofocus required placeholder="sentry-mcp" spellcheck="false" autocomplete="off" [value]="name()" (input)="name.set(value($event)); clear('name')" [attr.aria-invalid]="errors().name ? 'true' : null" [attr.aria-describedby]="uid + '-name-hint' + (errors().name ? ' ' + uid + '-name-err' : '')">
            <p class="gw-field__hint" [id]="uid + '-name-hint'">Lowercase letters, digits and <span class="gw-mono">-</span>. It also prefixes the tool names.</p>
            @if (errors().name) {<p class="gw-field__error" [id]="uid + '-name-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().name }}</p>}
          </div>
          <fieldset class="gw-field" [attr.aria-describedby]="uid + '-transport-hint'">
            <legend class="gw-field__label">Transport</legend>
            <div class="gw-segmented">
              <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-transport'" value="stdio" [checked]="transport() === 'stdio'" (change)="setTransport('stdio')"><span class="gw-segmented__label"><svg lucideIcon="terminal" class="gw-icon gw-icon--sm"></svg>stdio</span></label>
              <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-transport'" value="http" [checked]="transport() === 'http'" (change)="setTransport('http')"><span class="gw-segmented__label"><svg lucideIcon="globe" class="gw-icon gw-icon--sm"></svg>HTTP</span></label>
            </div>
            <p class="gw-field__hint" [id]="uid + '-transport-hint'">stdio: the gateway starts a command on this computer. HTTP: it calls a URL with Streamable HTTP.</p>
          </fieldset>
          <div class="gw-field" [hidden]="transport() !== 'stdio'">
            <label class="gw-field__label" [for]="uid + '-command'">Command</label>
            <input class="gw-input gw-input--mono" [id]="uid + '-command'" type="text" required [disabled]="transport() !== 'stdio'" placeholder="npx -y @scope/mcp-server@1.2.3" spellcheck="false" autocomplete="off" [value]="command()" (input)="command.set(value($event)); clear('command')" [attr.aria-invalid]="errors().command ? 'true' : null" [attr.aria-describedby]="uid + '-command-hint' + (errors().command ? ' ' + uid + '-command-err' : '')">
            <p class="gw-field__hint" [id]="uid + '-command-hint'">Runs without a shell, from <span class="gw-mono">~/.gaterook/mcp/</span> and the server name. Pin a version (<span class="gw-mono">&#64;1.2.3</span>): without one, npx can fetch new code on every start.</p>
            @if (errors().command) {<p class="gw-field__error" [id]="uid + '-command-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().command }}</p>}
          </div>
          <div class="gw-field" [hidden]="transport() !== 'http'">
            <label class="gw-field__label" [for]="uid + '-url'">URL</label>
            <input class="gw-input gw-input--mono" [id]="uid + '-url'" type="url" required [disabled]="transport() !== 'http'" placeholder="https://mcp.acme.internal/sentry/mcp" spellcheck="false" autocomplete="off" [value]="url()" (input)="url.set(value($event)); clear('url')" [attr.aria-invalid]="errors().url ? 'true' : null" [attr.aria-describedby]="uid + '-url-hint' + (errors().url ? ' ' + uid + '-url-err' : '')">
            <p class="gw-field__hint" [id]="uid + '-url-hint'">HTTPS, or <span class="gw-mono">http://127.0.0.1</span> for a server on this computer.</p>
            @if (errors().url) {<p class="gw-field__error" [id]="uid + '-url-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().url }}</p>}
          </div>
          <fieldset class="gw-field">
            <legend class="gw-field__label">Variables or headers</legend>
            @for (row of variables(); track $index; let i = $index) {
              <div class="gw-field">
                <label class="gw-field__label" [for]="uid + '-var-name-' + i">{{ transport() === 'http' ? 'Header' : 'Variable' }} name</label>
                <input class="gw-input gw-input--mono" [id]="uid + '-var-name-' + i" type="text" [placeholder]="transport() === 'http' ? 'Authorization' : 'SENTRY_AUTH_TOKEN'" spellcheck="false" autocomplete="off" [value]="row.name" (input)="setVariable(i, value($event))" [attr.aria-describedby]="uid + '-env-hint'">
              </div>
            }
            <!-- The core takes no existing secret by name (C10: a server's
                 values are its own secrets), so the kit's "Value from secret" select is gone; each value is typed in the
                 review, right before the start, and stored encrypted as a secret of the server. -->
            <p class="gw-field__hint" [id]="uid + '-env-hint'">The review asks for their values before the start. Each is stored encrypted as a secret of this server and never shown again. Only these reach the process, and the agent never sees them.</p>
            @if (errors().variables) {<p class="gw-field__error" [id]="uid + '-var-err'" role="alert"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().variables }}</p>}
            <div><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" (click)="addVariable()"><svg lucideIcon="plus" class="gw-icon gw-icon--sm"></svg>Add variable</button></div>
          </fieldset>
          @if (error()) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">The server wasn't reviewed</p><p class="gw-alert__body">{{ error() }}</p></div>
            </div>
          }
        </div>
      </div>
      <footer class="gw-dialog__footer">
        <button class="gw-btn" type="button" gwDialogClose>Cancel</button>
        <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="shield-check" class="gw-icon"></svg>} Review and start</button>
      </footer>
    </form>
  `,
})
export class GwMcpAddDialog {
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly host: HTMLDialogElement = inject(ElementRef<HTMLDialogElement>).nativeElement;

  /** The core reviewed the server: the review dialog takes over. */
  readonly reviewed = output<McpReviewed>();

  protected readonly uid = `ma-${++nextId}`;
  protected readonly name = signal('');
  protected readonly transport = signal<'stdio' | 'http'>('stdio');
  protected readonly command = signal('');
  protected readonly url = signal('');
  protected readonly variables = signal<readonly McpVariableDraft[]>([{ name: '' }]);
  protected readonly errors = signal<McpFormErrors>({});
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  /** Opens empty, as the kit's dialog: stdio, one variable row. */
  open(opener: HTMLElement | null): void {
    this.name.set('');
    this.transport.set('stdio');
    this.command.set('');
    this.url.set('');
    this.variables.set([{ name: '' }]);
    this.errors.set({});
    this.error.set(null);
    this.busy.set(false);
    this.dialog.openAfterRender(opener);
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected clear(field: keyof McpFormErrors): void {
    if (this.errors()[field]) this.errors.update((e) => ({ ...e, [field]: undefined }));
  }

  protected setTransport(t: 'stdio' | 'http'): void {
    this.transport.set(t);
    this.errors.update((e) => ({ ...e, command: undefined, url: undefined }));
  }

  protected setVariable(i: number, name: string): void {
    this.variables.update((rows) => rows.map((r, j) => (j === i ? { name } : r)));
    this.clear('variables');
  }

  protected addVariable(): void {
    this.variables.update((rows) => [...rows, { name: '' }]);
    const i = this.variables().length - 1;
    // The new row's Name takes the focus, so the keyboard continues where the row appeared.
    setTimeout(() => this.host.querySelector<HTMLElement>(`#${this.uid}-var-name-${i}`)?.focus());
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const r = mcpRequest(this.name(), this.transport(), this.command(), this.url(), this.variables());
    if (r.errors) {
      this.errors.set(r.errors);
      const first = r.errors.name ? 'name' : r.errors.command ? 'command' : r.errors.url ? 'url' : 'var-name-0';
      this.host.querySelector<HTMLElement>(`#${this.uid}-${first}`)?.focus();
      return;
    }
    this.busy.set(true);
    try {
      const review = await this.data.reviewServer(r.source);
      const opener = this.dialog.returnTo;
      this.dialog.returnTo = null;
      this.dialog.close('next');
      this.reviewed.emit({
        review,
        source: r.source,
        name: this.name().trim(),
        transport: this.transport(),
        target: this.transport() === 'http' ? this.url().trim() : this.command().trim(),
        variables: this.variables()
          .map((v) => ({ name: v.name.trim() }))
          .filter((v) => v.name),
        opener,
      });
    } catch (err) {
      this.error.set(failureText(err, 'This build of the gateway core can’t run MCP servers yet.'));
    } finally {
      this.busy.set(false);
    }
  }
}
