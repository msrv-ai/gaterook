import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { PluginCheck } from '../../../core/api/plugins.api';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwEnableWhen } from '../../../kit/enable-when';
import { GwIcon } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { plural } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureText } from './failure';
import { PluginFacts, pluginFacts } from './plugin-facts';

let nextId = 0;

/**
 * `<dialog gwPluginReviewDialog>` (kit `dlg-review-clickhouse` and `dlg-review-rabbitmq`): what the core found in a
 * staged plugin before it loads — signature, package, SHA-256, manifest, connection form, tools, the hosts it needs.
 *
 * - Signed by the catalog: a plain dialog, "Install ClickHouse plugin" is its primary.
 * - Unsigned: `gw-dialog--danger` (untrusted code), the acknowledgement must be checked before "Install unsigned
 *   plugin RabbitMQ" works (`aria-disabled` until then), and the core gets `acknowledgeUnsigned`.
 *
 * Focus starts on Cancel. Decision 2 (ARCHITECTURE §8.6.8): the plugin runs in its own plugin process with only its
 * connection's secrets during a call, not "with the gateway's privileges … next to the secrets".
 */
@Component({
  selector: 'dialog[gwPluginReviewDialog]',
  imports: [GwIcon, GwDialogClose, GwEnableWhen],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': '"gw-dialog gw-dialog--md" + (facts()?.signed === false ? " gw-dialog--danger" : "")',
    '[attr.aria-labelledby]': 'facts() ? uid + "-t" : null',
    '[attr.aria-describedby]': 'facts() ? uid + "-d" : null',
  },
  template: `
    @if (facts(); as f) {
      <header class="gw-dialog__header">
        @if (f.signed) {
          <span class="gw-icon-tile gw-icon-tile--success"><svg lucideIcon="badge-check" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" [id]="uid + '-t'">Install {{ f.name }} plugin</h2>
          <p class="gw-dialog__description" [id]="uid + '-d'">It loads without a restart, in its own plugin process. Its {{ toolsText() }} start Off.</p>
        } @else {
          <span class="gw-icon-tile"><svg lucideIcon="shield-alert" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" [id]="uid + '-t'">Install unsigned plugin {{ f.name }}?</h2>
          <p class="gw-dialog__description" [id]="uid + '-d'">Nobody has verified this code. It runs as your user in a separate process and gets only its connection's secrets during a call.</p>
        }
        <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
      </header>
      <div class="gw-dialog__body">
        <div class="gw-stack">
          <dl class="gw-kv gw-kv--dense">
            <div class="gw-kv__row"><dt class="gw-kv__key">Signature</dt><dd class="gw-kv__value">@if (f.signed) {<span class="gw-badge gw-badge--success"><svg lucideIcon="badge-check" class="gw-icon gw-badge__icon"></svg>Signed</span> by the catalog key, matches the published package} @else {<span class="gw-badge gw-badge--warning"><svg lucideIcon="shield-alert" class="gw-icon gw-badge__icon"></svg>Unsigned</span> No signature from the catalog key}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Package</dt><dd class="gw-kv__value"><span class="gw-mono">{{ f.file }}</span>@if (f.size) { · {{ f.size }}}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">SHA-256</dt><dd class="gw-kv__value gw-mono">{{ f.sha256 }}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Manifest</dt><dd class="gw-kv__value">id <span class="gw-mono">{{ f.id }}</span>, version <span class="gw-mono">{{ f.version }}</span></dd></div>
            @if (f.connectionForm) {
              <div class="gw-kv__row"><dt class="gw-kv__key">Connection form</dt><dd class="gw-kv__value">{{ f.connectionForm }}</dd></div>
            }
            <div class="gw-kv__row"><dt class="gw-kv__key">Tools</dt><dd class="gw-kv__value">
              @if (f.allReadOnly) {
                {{ f.toolNames.length }}, all read only <span class="gw-badge gw-badge--outline gw-badge--sm gw-badge--warning" title="The plugin declares these annotations; the gateway can't check what the tools do"><svg lucideIcon="shield-question-mark" class="gw-icon gw-badge__icon"></svg>Declared by {{ f.id }}, not verified</span>. On an endpoint each starts as Approval; Read only needs your confirmation per tool.
              } @else {
                @for (t of f.toolNames; track t) {<code class="gw-code">{{ t }}</code>{{ ' ' }}}{{ f.toolNames.length ? ', ' : '' }}Off until you add them to an endpoint. There each starts as Approval: the plugin declares its own annotations.
              }
            </dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Needs access to</dt><dd class="gw-kv__value">{{ f.access }}</dd></div>
          </dl>
          @if (!f.signed) {
            <label class="gw-checkbox">
              <input class="gw-checkbox__input" type="checkbox" [id]="uid + '-ack'" required #ack>
              <span class="gw-checkbox__control" aria-hidden="true"></span>
              <span class="gw-checkbox__label" [id]="uid + '-ack-label'">I understand this code is not verified and runs on this computer as my user</span>
            </label>
          }
          @if (error()) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">{{ f.name }} wasn't installed</p><p class="gw-alert__body">{{ error() }}</p></div>
            </div>
          }
        </div>
      </div>
      <footer class="gw-dialog__footer">
        <button class="gw-btn" type="button" gwDialogClose autofocus>Cancel</button>
        @if (f.signed) {
          <button class="gw-btn gw-btn--primary" type="button" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null" (click)="install()">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}Install {{ f.name }} plugin</button>
        } @else {
          <button class="gw-btn gw-btn--danger" type="button" aria-disabled="true" [attr.aria-describedby]="uid + '-ack-label'" [gwEnableWhen]="'#' + uid + '-ack'" [attr.aria-busy]="busy() ? 'true' : null" (click)="install()">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}Install unsigned plugin {{ f.name }}</button>
        }
      </footer>
    }
  `,
})
export class GwPluginReviewDialog {
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly toasts = inject(ToastService);
  private readonly host: HTMLDialogElement = inject(ElementRef<HTMLDialogElement>).nativeElement;

  protected readonly uid = `pr-${++nextId}`;
  private readonly check = signal<PluginCheck | null>(null);
  protected readonly facts = signal<PluginFacts | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly toolsText = computed(() => plural(this.facts()?.toolNames.length ?? 0, 'tool'));

  show(check: PluginCheck, source: string | null, opener: HTMLElement | null): void {
    // A new review starts unacknowledged, even when the last one was an unsigned plugin too (the box is reused).
    const ack = this.host.querySelector<HTMLInputElement>(`#${this.uid}-ack`);
    if (ack?.checked) {
      ack.checked = false;
      ack.dispatchEvent(new Event('change', { bubbles: true }));
    }
    this.check.set(check);
    this.facts.set(pluginFacts(check, source));
    this.busy.set(false);
    this.error.set(null);
    this.dialog.openAfterRender(opener);
  }

  protected async install(): Promise<void> {
    const check = this.check();
    const f = this.facts();
    if (!check || !f || this.busy()) return;
    // The unsigned install waits for the acknowledgement (the guard keeps the aria-disabled button inert; this is
    // the second lock: the core refuses an unsigned install without acknowledgeUnsigned).
    const ack = this.host.querySelector<HTMLInputElement>(`#${this.uid}-ack`);
    if (!f.signed && !ack?.checked) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.data.installPlugin(check.stagingId, !f.signed);
      this.dialog.close('installed');
      const tools = f.toolNames.length;
      this.toasts.show({
        tone: f.signed ? 'success' : 'warning',
        title: f.signed ? `${f.name} plugin installed` : `Unsigned plugin ${f.name} installed`,
        body: `${tools === 1 ? 'Its tool is' : `Its ${tools} tools are`} Off until you add them to an endpoint. There each starts as Approval.`,
      });
    } catch (e) {
      this.error.set(failureText(e, 'This build of the gateway core can’t install plugins yet.'));
    } finally {
      this.busy.set(false);
    }
  }
}
