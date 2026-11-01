import { ChangeDetectionStrategy, Component, ElementRef, inject, output, signal, viewChild } from '@angular/core';
import { SecretInputs } from '../../../core/api/connections.api';
import { McpServerAuthReview, McpServerConfig, McpServerReview, McpServerSource, McpSignIn } from '../../../core/api/plugins.api';
import { GwCopy } from '../../../kit/copy';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwEnableWhen } from '../../../kit/enable-when';
import { GwIcon } from '../../../kit/icons';
import { formatArgv, npmPackage, serverPaths } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureText } from './failure';
import { McpReviewed } from './mcp-add.dialog';
import { McpServerConnected } from './mcp-endpoint.dialog';
import { GwMcpSignIn } from './mcp-sign-in';

/** The secret field of an OAuth app's client secret. */
export const CLIENT_SECRET = '/auth/clientSecret';

/** One variable (or header) whose value the review asks for: its name, and where its value goes in `secrets`. */
export interface McpReviewSecret {
  readonly name: string;
  /** The JSON Pointer the core reads the value at (`/env/0/secretRef`, `/headers/1/secretRef`). */
  readonly pointer: string;
}

/** What the review dialog shows: the core's review of the request, put in the kit's rows. */
export interface McpReviewView {
  readonly name: string;
  readonly http: boolean;
  /** Start (stdio) or Connect (HTTP). */
  readonly verb: 'Start' | 'Connect';
  readonly risk: string;
  /** The words of the confirmation the person checks before the start. */
  readonly trust: string;
  readonly targetKey: 'Command' | 'URL';
  /** The command as it will run (resolved argv) or the URL. */
  readonly target: string;
  /** Pinned version (null: the row is left out), or unpinned. */
  readonly version: { readonly pinned: true; readonly version: string } | { readonly pinned: false } | null;
  readonly workdir: string;
  readonly sandbox: string | null;
  /** The variables (or headers) in the core's order: each value is typed here and becomes a secret of the server. */
  readonly secrets: readonly McpReviewSecret[];
  /** Over HTTP: what the review found about signing in, or null (a command, or a core without discovery). */
  readonly auth: McpServerAuthReview | null;
  /** `required`: Connect waits for the sign-in; `optional`: the server answers without one too. */
  readonly signIn: 'required' | 'optional' | null;
  /** The client ID of the person's OAuth app, from Sign-in options, or null. */
  readonly appClient: string | null;
}

/**
 * The review in words (kit `dlg-review-mcp`, filled by its page script): the verb and risk of the transport, the
 * command as the core resolved it (ARCHITECTURE §8.6.7: `node …/dist/index.js` for a catalog server, not `npx`), the
 * pinned version or "Not pinned", where it runs and the secrets it gets, by name. The names and their order are the
 * core's (`env` of the review: a catalog entry names its own, `GITHUB_PERSONAL_ACCESS_TOKEN`); the n-th one's value
 * goes to the core at `/env/n/secretRef` (`/headers/n/…` over HTTP).
 */
export function mcpReviewView(r: McpReviewed): McpReviewView {
  const review: McpServerReview = r.review;
  const http = (review.transport ?? r.transport) === 'http';
  const name = review.name || r.name;
  const typed = 'config' in r.source ? r.source.config.command ?? [] : [];
  const pkg = http ? null : npmPackage(typed);
  const catalogVersion = review.catalogEntry?.includes('@') ? review.catalogEntry.split('@').at(-1)! : null;
  const version: McpReviewView['version'] = http
    ? null
    : review.notPinned
      ? { pinned: false }
      : catalogVersion
        ? { pinned: true, version: catalogVersion }
        : pkg?.version
          ? { pinned: true, version: pkg.version }
          : pkg
            ? { pinned: false }
            : null;
  const names = review.env.length ? review.env : r.variables.map((v) => v.name);
  const key = http ? 'headers' : 'env';
  const auth = http ? (review.auth ?? null) : null;
  // A key the person passes as the Authorization header is their choice instead of a sign-in.
  const keyed = http && names.some((n) => n.toLowerCase() === 'authorization');
  const signIn = keyed ? null : auth?.kind === 'oauth' ? 'required' : auth?.kind === 'optional' ? 'optional' : null;
  const config = 'config' in r.source ? r.source.config : null;
  return {
    name,
    http,
    verb: http ? 'Connect' : 'Start',
    risk: http
      ? signIn
        ? `Every call sends your sign-in${names.length ? ' and these secrets' : ''} to this URL. Nobody has verified the server behind it.`
        : names.length
          ? 'Every call sends these secrets to this URL. Nobody has verified the server behind it.'
          : 'Every call goes to this URL. Nobody has verified the server behind it.'
      : names.length
        ? 'This code runs on your computer with these secrets. Nobody has verified it: it can read them and reach anything your user account can.'
        : 'This code runs on your computer. Nobody has verified it: it can reach anything your user account can.',
    trust: http
      ? signIn
        ? `I trust this server with my sign-in${names.length ? ' and these secrets' : ''}`
        : names.length
          ? 'I trust this server with these secrets'
          : 'I trust this server with the calls of my AI apps'
      : names.length
        ? 'I trust this code with these secrets on this computer'
        : 'I trust this code on this computer',
    targetKey: http ? 'URL' : 'Command',
    target: http ? (review.url ?? r.target) : review.argv.length ? formatArgv(review.argv) : r.target,
    version,
    workdir: serverPaths(name).workdir,
    sandbox: review.sandbox,
    secrets: names.map((n, i) => ({ name: n, pointer: `/${key}/${i}/secretRef` })),
    auth,
    signIn,
    appClient: config?.auth?.clientId ?? null,
  };
}

/**
 * The config a sign-in and the add that follows it send: the reviewed one with an OAuth sign-in, and the client ID
 * typed in the review when the server needs the person's OAuth app. Both must be the same: the core binds the
 * sign-in to the URL and sign-in settings it was made for.
 */
export function signInConfig(config: McpServerConfig, clientId: string | null): McpServerConfig {
  const id = clientId?.trim() || config.auth?.clientId || null;
  return { ...config, auth: { ...(config.auth ?? {}), type: 'oauth', ...(id ? { clientId: id } : {}) } };
}

/**
 * The values typed for the review's secrets, by pointer, or the indexes of the ones left empty. A value is sent as
 * typed (a token's spaces are its own); only an empty field is refused.
 */
export function mcpSecretValues(
  secrets: readonly McpReviewSecret[],
  values: readonly string[],
): { readonly secrets: SecretInputs; readonly missing: readonly number[] } {
  const out: { [pointer: string]: { source: 'local'; value: string } } = {};
  const missing: number[] = [];
  secrets.forEach((s, i) => {
    const value = values[i] ?? '';
    if (!value) missing.push(i);
    else out[s.pointer] = { source: 'local', value };
  });
  return { secrets: out, missing };
}

let nextId = 0;

/**
 * `<dialog gwMcpReviewDialog>` (kit `dlg-review-mcp`): the review before an MCP server first starts — what runs, which
 * version, as whom, with which secrets — in `gw-dialog--danger` (untrusted code next to secrets). "Start MCP server
 * sentry-mcp" stays `aria-disabled` until "I trust this code with these secrets on this computer" (for a server at a URL:
 * "I trust this server with my sign-in") is checked; focus
 * starts on Cancel. Starting it (`POST /api/mcp-servers {…, secrets, acknowledge: true}`) lists its tools, Off
 * everywhere.
 *
 * The values of its variables (or headers) are typed here, for a typed server and a catalog one alike: the core keeps
 * each as a new secret of the server (it takes no existing secret by name). They are write-only: never in a signal,
 * read from the fields once when the server starts, sent with that one request and wiped from the fields when the
 * dialog closes or shows another review.
 */
@Component({
  selector: 'dialog[gwMcpReviewDialog]',
  imports: [GwIcon, GwDialogClose, GwEnableWhen, GwCopy, GwMcpSignIn],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-dialog gw-dialog--md gw-dialog--danger',
    '[attr.aria-labelledby]': 'view() ? uid + "-t" : null',
    '[attr.aria-describedby]': 'view() ? uid + "-d" : null',
    '(close)': 'closed()',
  },
  template: `
    @if (view(); as w) {
      <form method="dialog" novalidate (submit)="start($event)">
        <header class="gw-dialog__header">
          <span class="gw-icon-tile"><svg lucideIcon="shield-alert" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" [id]="uid + '-t'">{{ w.verb }} MCP server <span class="gw-mono">{{ w.name }}</span>?</h2>
          <p class="gw-dialog__description" [id]="uid + '-d'">{{ w.risk }}</p>
          <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
        </header>
        <div class="gw-dialog__body">
          <div class="gw-stack">
            <dl class="gw-kv gw-kv--dense">
              <div class="gw-kv__row"><dt class="gw-kv__key">{{ w.targetKey }}</dt><dd class="gw-kv__value"><code class="gw-code">{{ w.target }}</code></dd></div>
              @if (w.version; as ver) {
                <div class="gw-kv__row"><dt class="gw-kv__key">Version</dt><dd class="gw-kv__value">
                  @if (ver.pinned) {
                    Pinned to <span class="gw-mono">{{ ver.version }}</span>. npm checks the package integrity on every start.
                  } @else {
                    <span class="gw-badge gw-badge--warning gw-badge--sm"><svg lucideIcon="triangle-alert" class="gw-icon gw-badge__icon"></svg>Not pinned</span> npx can fetch new code on every start, with these secrets. Add <span class="gw-mono">&#64;version</span> to the package.
                  }
                </dd></div>
              }
              @if (!w.http) {
                <!-- ARCHITECTURE §7.5: where the OS has no sandbox for the process, the review says so. -->
                <div class="gw-kv__row"><dt class="gw-kv__key">Runs as</dt><dd class="gw-kv__value">
                  @if (w.sandbox) {
                    Your user, without a shell, in an OS sandbox ({{ w.sandbox }}), from <span class="gw-mono">{{ w.workdir }}</span>
                  } @else {
                    Your user, without a shell or a sandbox, from <span class="gw-mono">{{ w.workdir }}</span>. Not sandboxed on this system: it can read everything your account can, including GateRook's data.
                  }
                </dd></div>
              }
              <div class="gw-kv__row"><dt class="gw-kv__key">Secrets passed</dt><dd class="gw-kv__value">
                @if (w.signIn) {Your sign-in, encrypted here and renewed by GateRook@if (w.secrets.length) {, and }}
                @for (s of w.secrets; track s.pointer; let last = $last; let i = $index) {@if (i > 0) {{{ last ? ' and ' : ', ' }}}<span class="gw-mono">{{ s.name }}</span>}@if (w.secrets.length) {{{ w.secrets.length === 1 ? ', from a new secret' : ', each from a new secret' }} of {{ w.name }}, encrypted here} @else if (!w.signIn) {None}
              </dd></div>
              @if (w.signIn && w.auth; as a) {
                <div class="gw-kv__row"><dt class="gw-kv__key">Sign-in</dt><dd class="gw-kv__value">
                  OAuth at <span class="gw-mono">{{ a.authorizationHost || a.issuer }}</span>{{ w.signIn === 'optional' ? ', optional: the server also answers without it, with less access' : '' }}.
                  {{ a.registration === 'metadata-document' ? 'GateRook signs in there as GateRook, with the client details it publishes.' : a.registration === 'dynamic' ? 'GateRook registers itself there as GateRook.' : a.registration === 'app' ? 'As your OAuth app.' : '' }}
                </dd></div>
              }
              <div class="gw-kv__row"><dt class="gw-kv__key">Tools</dt><dd class="gw-kv__value">Listed after the {{ w.http ? 'connection' : 'start' }} and Off everywhere. On an endpoint each starts as Approval: the server declares its own annotations and the gateway can't verify them.</dd></div>
            </dl>
            @if (w.signIn && w.auth; as a) {
              <fieldset class="gw-field" [attr.aria-describedby]="uid + '-signin-hint'">
                <legend class="gw-field__label">Sign in to {{ w.name }}</legend>
                @if (a.registration === 'app-required' || a.registration === 'app') {
                  @if (a.registration === 'app-required') {
                    <div class="gw-alert gw-alert--warning gw-alert--compact">
                      <svg lucideIcon="triangle-alert" class="gw-icon gw-alert__icon"></svg>
                      <div class="gw-alert__content"><p class="gw-alert__title">This server needs your own OAuth app</p><p class="gw-alert__body">{{ a.message }}</p></div>
                    </div>
                  }
                  @if (a.redirectUri) {
                    <div class="gw-field">
                      <label class="gw-field__label" [for]="uid + '-redirect'">Redirect URI of the app</label>
                      <div class="gw-copy-field">
                        <input class="gw-copy-field__value" [id]="uid + '-redirect'" type="text" readonly [value]="a.redirectUri" [attr.aria-describedby]="uid + '-redirect-hint'">
                        <button class="gw-btn gw-btn--sm gw-copy-field__button" type="button" [gwCopyFrom]="'#' + uid + '-redirect'">
                          <span class="gw-copy-field__copy"><svg lucideIcon="copy" class="gw-icon gw-icon--sm"></svg> Copy<span class="gw-sr-only"> redirect URI</span></span>
                          <span class="gw-copy-field__copied"><svg lucideIcon="check" class="gw-icon gw-icon--sm"></svg> Copied</span>
                        </button>
                      </div>
                      <p class="gw-field__hint" [id]="uid + '-redirect-hint'">Register it in the OAuth app at the provider. GateRook listens there on this computer only while you sign in.</p>
                    </div>
                  }
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-client'">OAuth client ID</label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-client'" type="text" spellcheck="false" autocomplete="off" [value]="w.appClient ?? ''" [readOnly]="!!w.appClient" (input)="clientId.set(fieldValue($event))">
                  </div>
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-client-secret'">Client secret <span class="gw-field__optional">Optional</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-client-secret'" type="password" autocomplete="new-password" spellcheck="false" [attr.aria-describedby]="uid + '-client-secret-hint'">
                    <p class="gw-field__hint" [id]="uid + '-client-secret-hint'">Only when the app has one. It is stored encrypted as a secret of {{ w.name }} and never shown again.</p>
                  </div>
                }
                <div gwMcpSignIn [start]="startSignIn" [host]="a.authorizationHost ?? null" [blocked]="signInBlocked()" (signedIn)="signedIn.set($event)"></div>
                <p class="gw-field__hint" [id]="uid + '-signin-hint'">You sign in as you would in Claude Desktop, Cursor or Codex, but the browser hands the sign-in to GateRook, not to the AI app.</p>
              </fieldset>
            } @else if (w.auth; as a) {
              @if ((a.kind === 'refused' || a.kind === 'unknown') && !w.secrets.length) {
                <div class="gw-alert gw-alert--warning gw-alert--compact">
                  <svg lucideIcon="triangle-alert" class="gw-icon gw-alert__icon"></svg>
                  <div class="gw-alert__content"><p class="gw-alert__title">{{ a.kind === 'refused' ? 'It wants a key, not a sign-in' : 'GateRook couldn’t tell how to sign in' }}</p><p class="gw-alert__body">{{ a.message }}{{ a.kind === 'refused' ? ' Close the review and add the header that carries its key.' : ' Connecting may fail.' }}</p></div>
                </div>
              }
            }
            @if (w.secrets.length) {
              <!-- Review 2026-10-26: the core needs a value for each variable to start the server (a catalog entry too). -->
              <fieldset class="gw-field" [attr.aria-describedby]="uid + '-values-hint'">
                <legend class="gw-field__label">{{ w.secrets.length === 1 ? 'Its value' : 'Their values' }}</legend>
                @for (s of w.secrets; track s.pointer; let i = $index) {
                  <div class="gw-field">
                    <label class="gw-field__label" [for]="uid + '-value-' + i">Value of <span class="gw-mono">{{ s.name }}</span></label>
                    <input class="gw-input gw-input--mono" [id]="uid + '-value-' + i" type="password" required autocomplete="new-password" spellcheck="false" (input)="clearMissing(i)" [attr.aria-invalid]="missing().includes(i) ? 'true' : null" [attr.aria-describedby]="missing().includes(i) ? uid + '-value-err-' + i : null">
                    @if (missing().includes(i)) {<p class="gw-field__error" [id]="uid + '-value-err-' + i"><svg lucideIcon="circle-alert" class="gw-icon"></svg>Enter the value of {{ s.name }}.</p>}
                  </div>
                }
                <p class="gw-field__hint" [id]="uid + '-values-hint'">{{ w.secrets.length === 1 ? 'It is' : 'Each is' }} stored encrypted as a secret of {{ w.name }} when it {{ w.http ? 'connects' : 'starts' }}, and never shown again. The agent never sees {{ w.secrets.length === 1 ? 'it' : 'them' }}.</p>
              </fieldset>
            }
            <label class="gw-checkbox">
              <input class="gw-checkbox__input" type="checkbox" [id]="uid + '-ack'" required>
              <span class="gw-checkbox__control" aria-hidden="true"></span>
              <span class="gw-checkbox__label" [id]="uid + '-ack-label'">{{ w.trust }}</span>
            </label>
            @if (error()) {
              <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
                <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content"><p class="gw-alert__title">{{ w.name }} didn't {{ w.http ? 'connect' : 'start' }}</p><p class="gw-alert__body">{{ error() }}</p></div>
              </div>
            }
          </div>
        </div>
        <footer class="gw-dialog__footer">
          <button class="gw-btn" type="button" gwDialogClose autofocus>Cancel</button>
          <button class="gw-btn gw-btn--danger" type="submit" aria-disabled="true" [attr.aria-describedby]="uid + '-ack-label'" [gwEnableWhen]="'#' + uid + '-ack'" [attr.aria-busy]="busy() ? 'true' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}{{ w.verb }} MCP server {{ w.name }}</button>
        </footer>
      </form>
    }
  `,
})
export class GwMcpReviewDialog {
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly host: HTMLDialogElement = inject(ElementRef<HTMLDialogElement>).nativeElement;

  /** The server connected (or started): the page offers the endpoint step next. */
  readonly connected = output<McpServerConnected>();

  protected readonly uid = `mr-${++nextId}`;
  private readonly source = signal<McpServerSource | null>(null);
  protected readonly view = signal<McpReviewView | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The value fields left empty at the last Start (by index). */
  protected readonly missing = signal<readonly number[]>([]);
  /** The client ID typed in the review, for a server that needs the person's OAuth app. */
  protected readonly clientId = signal('');
  /** The finished sign-in, and the config it was made for (the add sends exactly that one). */
  protected readonly signedIn = signal<McpSignIn | null>(null);
  private signedInConfig: McpServerConfig | null = null;
  private readonly signInBlock = viewChild(GwMcpSignIn);

  /** Why Sign in can't start: a server that needs an OAuth app has no client ID yet. */
  protected signInBlocked(): string | null {
    const w = this.view();
    if (w?.auth?.registration !== 'app-required' || w.appClient || this.clientId().trim()) return null;
    return 'Enter the client ID of your OAuth app first.';
  }

  /** Sign in (`POST /api/mcp-sign-ins`): the reviewed config with the sign-in, the app's secret if one was typed. */
  protected readonly startSignIn = (): Promise<McpSignIn> => {
    const source = this.source();
    if (!source || !('config' in source)) return Promise.reject(new Error('Only a server at a URL signs in.'));
    const config = signInConfig(source.config, this.clientId());
    this.signedInConfig = config;
    this.signedIn.set(null);
    const secret = this.host.querySelector<HTMLInputElement>(`#${this.uid}-client-secret`)?.value ?? '';
    return this.data.startSignIn({ config, ...(secret ? { secrets: { [CLIENT_SECRET]: { source: 'local', value: secret } } } : {}) });
  };

  protected fieldValue(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  show(reviewed: McpReviewed): void {
    // Every review starts unacknowledged and with empty values (the fields are reused from the last one).
    const ack = this.host.querySelector<HTMLInputElement>(`#${this.uid}-ack`);
    if (ack?.checked) {
      ack.checked = false;
      ack.dispatchEvent(new Event('change', { bubbles: true }));
    }
    this.wipe();
    this.signInBlock()?.reset();
    this.signedIn.set(null);
    this.signedInConfig = null;
    this.clientId.set('');
    this.source.set(reviewed.source);
    this.view.set(mcpReviewView(reviewed));
    this.busy.set(false);
    this.error.set(null);
    this.missing.set([]);
    this.dialog.openAfterRender(reviewed.opener);
  }

  protected clearMissing(i: number): void {
    if (this.missing().includes(i)) this.missing.update((m) => m.filter((x) => x !== i));
  }

  /** Closing the review: typed values leave the page, and a sign-in still waiting is cancelled at the core. */
  protected closed(): void {
    this.wipe();
    this.signInBlock()?.reset();
  }

  /** The typed values leave the page: the fields are emptied (closing, another review, a started server). */
  protected wipe(): void {
    this.valueFields().forEach((f) => (f.value = ''));
    const secret = this.host.querySelector<HTMLInputElement>(`#${this.uid}-client-secret`);
    if (secret) secret.value = '';
  }

  protected async start(e: Event): Promise<void> {
    e.preventDefault();
    const source = this.source();
    const w = this.view();
    const ack = this.host.querySelector<HTMLInputElement>(`#${this.uid}-ack`);
    if (!source || !w || this.busy() || !ack?.checked) return;
    const fields = this.valueFields();
    const values = mcpSecretValues(
      w.secrets,
      w.secrets.map((_, i) => fields[i]?.value ?? ''),
    );
    if (values.missing.length) {
      this.missing.set(values.missing);
      fields[values.missing[0]]?.focus();
      return;
    }
    const signIn = this.signedIn();
    if (w.signIn === 'required' && !signIn) {
      this.error.set(`Sign in to ${w.name} first: it answers only with a sign-in.`);
      this.host.querySelector<HTMLElement>('[gwMcpSignIn] button')?.focus();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    const clientSecret = this.host.querySelector<HTMLInputElement>(`#${this.uid}-client-secret`)?.value ?? '';
    const all: SecretInputs = { ...values.secrets, ...(signIn && clientSecret ? { [CLIENT_SECRET]: { source: 'local' as const, value: clientSecret } } : {}) };
    const secrets = Object.keys(all).length ? { secrets: all } : {};
    const request: McpServerSource =
      'config' in source
        ? signIn && this.signedInConfig
          ? { config: this.signedInConfig, ...secrets, signIn: signIn.id }
          : { config: source.config, ...secrets }
        : { catalog: source.catalog, ...secrets };
    try {
      const started = await this.data.addServer(request);
      this.wipe();
      this.signedIn.set(null);
      // Focus goes back to the opener only after the endpoint step.
      const opener = this.dialog.returnTo;
      this.dialog.returnTo = null;
      this.dialog.close('started');
      this.connected.emit({
        name: started?.name || w.name,
        env: 'dev',
        http: w.http,
        tools: typeof started?.tools === 'number' ? started.tools : null,
        opener,
      });
    } catch (err) {
      // The values stay in their fields for another try; closing the dialog wipes them.
      this.error.set(failureText(err, 'This build of the gateway core can’t run MCP servers yet.'));
    } finally {
      this.busy.set(false);
    }
  }

  private valueFields(): HTMLInputElement[] {
    return Array.from(this.host.querySelectorAll<HTMLInputElement>(`input[id^="${this.uid}-value-"]`));
  }
}
