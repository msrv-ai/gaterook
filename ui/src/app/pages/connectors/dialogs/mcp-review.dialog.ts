import { ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
import { SecretInputs } from '../../../core/api/connections.api';
import { McpServerReview, McpServerSource } from '../../../core/api/plugins.api';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwEnableWhen } from '../../../kit/enable-when';
import { GwIcon } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { formatArgv, npmPackage, plural, serverPaths } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureText } from './failure';
import { McpReviewed } from './mcp-add.dialog';

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
  readonly targetKey: 'Command' | 'URL';
  /** The command as it will run (resolved argv) or the URL. */
  readonly target: string;
  /** Pinned version (null: the row is left out), or unpinned. */
  readonly version: { readonly pinned: true; readonly version: string } | { readonly pinned: false } | null;
  readonly workdir: string;
  readonly sandbox: string | null;
  /** The variables (or headers) in the core's order: each value is typed here and becomes a secret of the server. */
  readonly secrets: readonly McpReviewSecret[];
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
  return {
    name,
    http,
    verb: http ? 'Connect' : 'Start',
    risk: http
      ? 'Every call sends these secrets to this URL. Nobody has verified the server behind it.'
      : 'This code runs on your computer with these secrets. Nobody has verified it: it can read them and reach anything your user account can.',
    targetKey: http ? 'URL' : 'Command',
    target: http ? (review.url ?? r.target) : review.argv.length ? formatArgv(review.argv) : r.target,
    version,
    workdir: serverPaths(name).workdir,
    sandbox: review.sandbox,
    secrets: names.map((n, i) => ({ name: n, pointer: `/${key}/${i}/secretRef` })),
  };
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
 * sentry-mcp" stays `aria-disabled` until "I trust this code with these secrets on this computer" is checked; focus
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
  imports: [GwIcon, GwDialogClose, GwEnableWhen],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-dialog gw-dialog--md gw-dialog--danger',
    '[attr.aria-labelledby]': 'view() ? uid + "-t" : null',
    '[attr.aria-describedby]': 'view() ? uid + "-d" : null',
    '(close)': 'wipe()',
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
                    Your user, without a shell or a sandbox, from <span class="gw-mono">{{ w.workdir }}</span>. Not sandboxed on this system: it can read everything your account can, including MCP Gateway's data.
                  }
                </dd></div>
              }
              <div class="gw-kv__row"><dt class="gw-kv__key">Secrets passed</dt><dd class="gw-kv__value">
                @for (s of w.secrets; track s.pointer; let last = $last; let i = $index) {@if (i > 0) {{{ last ? ' and ' : ', ' }}}<span class="gw-mono">{{ s.name }}</span>}@if (w.secrets.length) {{{ w.secrets.length === 1 ? ', from a new secret' : ', each from a new secret' }} of {{ w.name }}, encrypted here} @else {None}
              </dd></div>
              <div class="gw-kv__row"><dt class="gw-kv__key">Tools</dt><dd class="gw-kv__value">Listed after the start and Off everywhere. On an endpoint each starts as Approval: the server declares its own annotations and the gateway can't verify them.</dd></div>
            </dl>
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
              <span class="gw-checkbox__label" [id]="uid + '-ack-label'">I trust this code with these secrets on this computer</span>
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
  private readonly toasts = inject(ToastService);
  private readonly host: HTMLDialogElement = inject(ElementRef<HTMLDialogElement>).nativeElement;

  protected readonly uid = `mr-${++nextId}`;
  private readonly source = signal<McpServerSource | null>(null);
  protected readonly view = signal<McpReviewView | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The value fields left empty at the last Start (by index). */
  protected readonly missing = signal<readonly number[]>([]);

  show(reviewed: McpReviewed): void {
    // Every review starts unacknowledged and with empty values (the fields are reused from the last one).
    const ack = this.host.querySelector<HTMLInputElement>(`#${this.uid}-ack`);
    if (ack?.checked) {
      ack.checked = false;
      ack.dispatchEvent(new Event('change', { bubbles: true }));
    }
    this.wipe();
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

  /** The typed values leave the page: the fields are emptied (closing, another review, a started server). */
  protected wipe(): void {
    this.valueFields().forEach((f) => (f.value = ''));
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
    this.busy.set(true);
    this.error.set(null);
    const secrets = w.secrets.length ? { secrets: values.secrets } : {};
    const request: McpServerSource = 'config' in source ? { config: source.config, ...secrets } : { catalog: source.catalog, ...secrets };
    try {
      const started = await this.data.addServer(request);
      this.wipe();
      this.dialog.close('started');
      const tools = started?.tools;
      this.toasts.show({
        tone: 'success',
        title: `MCP server ${w.name} ${w.http ? 'connected' : 'started'}`,
        body: `${tools === 1 ? 'Its tool is' : typeof tools === 'number' ? `Its ${plural(tools, 'tool')} are` : 'Its tools are'} listed and Off until you add them to an endpoint. There each starts as Approval.`,
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
