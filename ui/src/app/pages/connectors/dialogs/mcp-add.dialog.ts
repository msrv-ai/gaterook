import { ChangeDetectionStrategy, Component, ElementRef, inject, output, signal } from '@angular/core';
import { McpServerAuthConfig, McpServerConfig, McpServerReview, McpServerSource } from '../../../core/api/plugins.api';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwToggle } from '../../../kit/disclosure';
import { GwIcon } from '../../../kit/icons';
import { joinAnd, tokenizeCommand } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { parsePastedServer, serverName } from '../mcp-paste';
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
  readonly clientId?: string;
  readonly scopes?: string;
  readonly callbackPort?: string;
}

/**
 * Sign-in options of a server at a URL (Advanced): an OAuth app the person created, because the server doesn't let
 * apps register themselves, or a callback the server accepts. Empty: GateRook signs in as itself, with its published
 * client details or by registering, and listens on any free port.
 */
export interface McpSignInOptions {
  readonly clientId: string;
  readonly scopes: string;
  readonly callbackPort: string;
}

const NO_SIGN_IN_OPTIONS: McpSignInOptions = { clientId: '', scopes: '', callbackPort: '' };

const SERVER_NAME = /^[a-z][a-z0-9-]{0,63}$/;
const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const HEADER_NAME = /^[A-Za-z0-9-]{1,64}$/;
const SERVER_URL = /^(https:\/\/[^\s/]+|http:\/\/(127\.0\.0\.1|localhost)[:/])/i;
const CLIENT_ID = /^[\x21-\x7E]{1,512}$/;
const SCOPES = /^[\x21\x23-\x5B\x5D-\x7E]+( [\x21\x23-\x5B\x5D-\x7E]+)*$/;

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
  signIn: McpSignInOptions = NO_SIGN_IN_OPTIONS,
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
  let auth: McpServerAuthConfig | undefined;
  if (transport === 'http') {
    const clientId = signIn.clientId.trim();
    const scopes = signIn.scopes.trim().replace(/\s+/g, ' ');
    const port = signIn.callbackPort.trim();
    if (clientId && !CLIENT_ID.test(clientId)) errors.clientId = 'A client ID has no spaces or control characters.';
    if (scopes && !SCOPES.test(scopes)) errors.scopes = 'Separate scopes with spaces; a scope has no quotes or backslashes.';
    if (port && (!/^\d{4,5}$/.test(port) || +port < 1024 || +port > 65535)) errors.callbackPort = 'Use a port from 1024 to 65535.';
    if (bindings.some((b) => b.name.toLowerCase() === 'authorization') && clientId) {
      errors.variables = 'The OAuth sign-in sends the Authorization header. Remove that header, or the client ID.';
    }
    if (clientId || scopes || port) {
      // Options the person chose mean an OAuth sign-in; without any, the review finds out whether it needs one.
      auth = { type: 'oauth', ...(clientId ? { clientId } : {}), ...(scopes ? { scopes } : {}), ...(port ? { callbackPort: +port } : {}) };
    }
  }
  if (Object.keys(errors).length) return { source: null, errors };
  const config: McpServerConfig =
    transport === 'stdio'
      ? { name: n, transport, command: argv, env: bindings }
      : { name: n, transport, url: url.trim(), headers: bindings, ...(auth ? { auth } : {}) };
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
  imports: [GwIcon, GwDialogClose, GwToggle],
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
        <p class="gw-dialog__description" [id]="uid + '-d'">Put any MCP server behind the gateway: a command on this computer, or a URL you sign in to as in Claude Desktop, Cursor or Codex. Its tools get modes, approvals and audit.</p>
        <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
      </header>
      <div class="gw-dialog__body">
        <div class="gw-stack">
          <div class="gw-stack gw-stack--sm">
            <div><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" gwToggle [attr.aria-controls]="uid + '-paste'"><svg lucideIcon="file-code" class="gw-icon gw-icon--sm"></svg> Paste a configuration from the server’s docs <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-btn__chevron"></svg></button></div>
            <div class="gw-field" [id]="uid + '-paste'" hidden>
              <label class="gw-field__label" [for]="uid + '-paste-text'">Configuration</label>
              <textarea class="gw-textarea gw-textarea--code" [id]="uid + '-paste-text'" rows="5" spellcheck="false" autocapitalize="off" autocomplete="off" placeholder='{ "mcpServers": { "linear": { "url": "https://mcp.linear.app/mcp" } } }' (input)="paste(value($event))" [attr.aria-invalid]="pasteError() ? 'true' : null" [attr.aria-describedby]="uid + '-paste-hint' + (pasteError() ? ' ' + uid + '-paste-err' : '') + (pasted() ? ' ' + uid + '-paste-done' : '')"></textarea>
              <p class="gw-field__hint" [id]="uid + '-paste-hint'">What the docs give for Claude Desktop, Cursor, VS Code or Codex, a <span class="gw-mono">claude mcp add</span> or <span class="gw-mono">codex mcp add</span> command, or the URL. It fills the fields below and isn’t kept; keys and tokens in it aren’t copied.</p>
              @if (pasteError()) {<p class="gw-field__error" [id]="uid + '-paste-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ pasteError() }}</p>}
              @if (pasted(); as p) {<p class="gw-field__hint" [id]="uid + '-paste-done'" role="status"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Filled in {{ p }}</p>}
            </div>
          </div>
          <div class="gw-field">
            <label class="gw-field__label" [for]="uid + '-name'">Name</label>
            <input class="gw-input gw-input--mono" [id]="uid + '-name'" type="text" autofocus required placeholder="sentry-mcp" spellcheck="false" autocomplete="off" [value]="name()" (input)="name.set(value($event)); suggested.set(null); clear('name')" [attr.aria-invalid]="errors().name ? 'true' : null" [attr.aria-describedby]="uid + '-name-hint' + (errors().name ? ' ' + uid + '-name-err' : '')">
            <p class="gw-field__hint" [id]="uid + '-name-hint'">Lowercase letters, digits and <span class="gw-mono">-</span>. It also prefixes the tool names.</p>
            @if (errors().name) {<p class="gw-field__error" [id]="uid + '-name-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().name }}</p>}
          </div>
          <fieldset class="gw-field" [attr.aria-describedby]="uid + '-transport-hint'">
            <legend class="gw-field__label">Transport</legend>
            <div class="gw-segmented">
              <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-transport'" value="stdio" [checked]="transport() === 'stdio'" (change)="setTransport('stdio')"><span class="gw-segmented__label"><svg lucideIcon="terminal" class="gw-icon gw-icon--sm"></svg>stdio</span></label>
              <label class="gw-segmented__option"><input class="gw-segmented__input" type="radio" [name]="uid + '-transport'" value="http" [checked]="transport() === 'http'" (change)="setTransport('http')"><span class="gw-segmented__label"><svg lucideIcon="globe" class="gw-icon gw-icon--sm"></svg>HTTP</span></label>
            </div>
            <p class="gw-field__hint" [id]="uid + '-transport-hint'">stdio: the gateway starts a command on this computer. HTTP: it calls a URL with Streamable HTTP and signs in to it for you when the server asks.</p>
          </fieldset>
          <div class="gw-field" [hidden]="transport() !== 'stdio'">
            <label class="gw-field__label" [for]="uid + '-command'">Command</label>
            <input class="gw-input gw-input--mono" [id]="uid + '-command'" type="text" required [disabled]="transport() !== 'stdio'" placeholder="npx -y @scope/mcp-server@1.2.3" spellcheck="false" autocomplete="off" [value]="command()" (input)="command.set(value($event)); clear('command')" [attr.aria-invalid]="errors().command ? 'true' : null" [attr.aria-describedby]="uid + '-command-hint' + (errors().command ? ' ' + uid + '-command-err' : '')">
            <p class="gw-field__hint" [id]="uid + '-command-hint'">Runs without a shell, from <span class="gw-mono">~/.gaterook/mcp/</span> and the server name. Pin a version (<span class="gw-mono">&#64;1.2.3</span>): without one, npx can fetch new code on every start.</p>
            @if (errors().command) {<p class="gw-field__error" [id]="uid + '-command-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().command }}</p>}
          </div>
          <div class="gw-field" [hidden]="transport() !== 'http'">
            <label class="gw-field__label" [for]="uid + '-url'">URL</label>
            <input class="gw-input gw-input--mono" [id]="uid + '-url'" type="url" required [disabled]="transport() !== 'http'" placeholder="https://mcp.linear.app/mcp" spellcheck="false" autocomplete="off" [value]="url()" (input)="setUrl(value($event))" [attr.aria-invalid]="errors().url ? 'true' : null" [attr.aria-describedby]="uid + '-url-hint' + (errors().url ? ' ' + uid + '-url-err' : '')">
            <p class="gw-field__hint" [id]="uid + '-url-hint'">HTTPS, or <span class="gw-mono">http://127.0.0.1</span> for a server on this computer. A server that asks for a sign-in gets one in your browser after the review.</p>
            @if (errors().url) {<p class="gw-field__error" [id]="uid + '-url-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().url }}</p>}
          </div>
          <div class="gw-stack gw-stack--sm" [hidden]="transport() !== 'http'">
            <div><button class="gw-btn gw-btn--ghost gw-btn--sm" type="button" gwToggle [attr.aria-controls]="uid + '-signin'" [(expanded)]="signInOpen"><svg lucideIcon="sliders-horizontal" class="gw-icon gw-icon--sm"></svg> Sign-in options <svg lucideIcon="chevron-down" class="gw-icon gw-icon--sm gw-btn__chevron"></svg></button></div>
            <div class="gw-stack gw-stack--sm" [id]="uid + '-signin'" [hidden]="!signInOpen()">
              <p class="gw-field__hint">Leave them empty: GateRook signs in as GateRook, with the client details it publishes or by registering itself, and listens on this computer for the browser. Fill them in for a server that asks you to create an OAuth app (GitHub and Slack do), or that accepts only certain redirect addresses.</p>
              <div class="gw-field">
                <label class="gw-field__label" [for]="uid + '-client'">OAuth client ID <span class="gw-field__optional">Optional</span></label>
                <input class="gw-input gw-input--mono" [id]="uid + '-client'" type="text" spellcheck="false" autocomplete="off" [disabled]="transport() !== 'http'" [value]="clientId()" (input)="clientId.set(value($event)); clear('clientId')" [attr.aria-invalid]="errors().clientId ? 'true' : null" [attr.aria-describedby]="uid + '-client-hint' + (errors().clientId ? ' ' + uid + '-client-err' : '')">
                <p class="gw-field__hint" [id]="uid + '-client-hint'">The ID of the OAuth app you created for GateRook at the server’s provider. The review asks for its secret, if it has one.</p>
                @if (errors().clientId) {<p class="gw-field__error" [id]="uid + '-client-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().clientId }}</p>}
              </div>
              <div class="gw-field">
                <label class="gw-field__label" [for]="uid + '-scopes'">Scopes <span class="gw-field__optional">Optional</span></label>
                <input class="gw-input gw-input--mono" [id]="uid + '-scopes'" type="text" spellcheck="false" autocomplete="off" placeholder="read write" [disabled]="transport() !== 'http'" [value]="scopes()" (input)="scopes.set(value($event)); clear('scopes')" [attr.aria-invalid]="errors().scopes ? 'true' : null" [attr.aria-describedby]="uid + '-scopes-hint' + (errors().scopes ? ' ' + uid + '-scopes-err' : '')">
                <p class="gw-field__hint" [id]="uid + '-scopes-hint'">Default: what the server asks for.</p>
                @if (errors().scopes) {<p class="gw-field__error" [id]="uid + '-scopes-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().scopes }}</p>}
              </div>
              <div class="gw-field">
                <label class="gw-field__label" [for]="uid + '-port'">Callback port <span class="gw-field__optional">Optional</span></label>
                <input class="gw-input gw-input--mono" [id]="uid + '-port'" type="text" inputmode="numeric" spellcheck="false" autocomplete="off" placeholder="7471" [disabled]="transport() !== 'http'" [value]="callbackPort()" (input)="callbackPort.set(value($event)); clear('callbackPort')" [attr.aria-invalid]="errors().callbackPort ? 'true' : null" [attr.aria-describedby]="uid + '-port-hint' + (errors().callbackPort ? ' ' + uid + '-port-err' : '')">
                <p class="gw-field__hint" [id]="uid + '-port-hint'">Where the browser comes back to: <span class="gw-mono">http://localhost:&lt;port&gt;/callback</span>, or <span class="gw-mono">http://127.0.0.1:7471/callback</span> for an OAuth app. Default: any free port.</p>
                @if (errors().callbackPort) {<p class="gw-field__error" [id]="uid + '-port-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ errors().callbackPort }}</p>}
              </div>
            </div>
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
        <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="shield-check" class="gw-icon"></svg>} {{ transport() === 'http' ? 'Review and connect' : 'Review and start' }}</button>
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
  protected readonly clientId = signal('');
  protected readonly scopes = signal('');
  protected readonly callbackPort = signal('');
  protected readonly signInOpen = signal(false);
  protected readonly errors = signal<McpFormErrors>({});
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  /** The name GateRook suggested from the URL; the person's own name is never replaced. */
  protected readonly suggested = signal<string | null>(null);
  protected readonly pasted = signal<string | null>(null);
  protected readonly pasteError = signal<string | null>(null);

  /** Opens empty, as the kit's dialog: stdio, one variable row. */
  open(opener: HTMLElement | null): void {
    this.name.set('');
    this.transport.set('stdio');
    this.command.set('');
    this.url.set('');
    this.variables.set([{ name: '' }]);
    this.clientId.set('');
    this.scopes.set('');
    this.callbackPort.set('');
    this.signInOpen.set(false);
    this.suggested.set(null);
    this.pasted.set(null);
    this.pasteError.set(null);
    const paste = this.host.querySelector<HTMLTextAreaElement>(`#${this.uid}-paste-text`);
    if (paste) paste.value = '';
    this.errors.set({});
    this.error.set(null);
    this.busy.set(false);
    this.dialog.openAfterRender(opener);
  }

  /** The URL as typed; an empty name (or the one suggested before) follows its host: mcp.linear.app → linear-mcp. */
  protected setUrl(url: string): void {
    this.url.set(url);
    this.clear('url');
    if (!this.name().trim() || this.name() === this.suggested()) {
      let suggestion = '';
      try {
        suggestion = serverName('', new URL(url.trim()));
      } catch {
        suggestion = '';
      }
      if (suggestion) {
        this.name.set(suggestion);
        this.suggested.set(suggestion);
        this.clear('name');
      }
    }
  }

  /**
   * A configuration pasted from the server's docs fills the fields. The text leaves the field once it is read: it may
   * hold a key, and keys are typed in the review, where they become secrets of the server.
   */
  protected paste(text: string): void {
    this.pasted.set(null);
    if (!text.trim()) {
      this.pasteError.set(null);
      return;
    }
    const r = parsePastedServer(text);
    if ('error' in r) {
      this.pasteError.set(r.error);
      return;
    }
    this.pasteError.set(null);
    this.transport.set(r.transport);
    if (r.transport === 'http') this.url.set(r.url);
    else this.command.set(r.command);
    if (r.name) {
      this.name.set(r.name);
      this.suggested.set(r.name);
    }
    this.variables.set(r.variables.length ? r.variables.map((name) => ({ name })) : [{ name: '' }]);
    this.errors.set({});
    const field = this.host.querySelector<HTMLTextAreaElement>(`#${this.uid}-paste-text`);
    if (field) field.value = '';
    const kind = r.transport === 'http' ? 'header' : 'variable';
    const parts = [
      ...(r.name ? ['the name'] : []),
      r.transport === 'http' ? 'the URL' : 'the command',
      ...(r.variables.length ? [r.variables.length === 1 ? `one ${kind}` : `${r.variables.length} ${kind}s`] : []),
    ];
    this.pasted.set(
      `${joinAnd(parts)} from the configuration.` +
        (r.others ? ` It named ${r.others + 1} servers; this is the first. Add the others one at a time.` : '') +
        (r.notes.length ? ` ${r.notes.join(' ')}` : ''),
    );
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
    const r = mcpRequest(this.name(), this.transport(), this.command(), this.url(), this.variables(), {
      clientId: this.clientId(),
      scopes: this.scopes(),
      callbackPort: this.callbackPort(),
    });
    if (r.errors) {
      this.errors.set(r.errors);
      if (r.errors.clientId || r.errors.scopes || r.errors.callbackPort) this.signInOpen.set(true);
      const first = r.errors.name
        ? 'name'
        : r.errors.command
          ? 'command'
          : r.errors.url
            ? 'url'
            : r.errors.variables
              ? 'var-name-0'
              : r.errors.clientId
                ? 'client'
                : r.errors.scopes
                  ? 'scopes'
                  : 'port';
      setTimeout(() => this.host.querySelector<HTMLElement>(`#${this.uid}-${first}`)?.focus());
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
