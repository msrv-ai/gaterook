import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { PluginUpdatePreview } from '../../../core/api/plugins.api';
import { GwDiff } from '../../../code/diff';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwEnableWhen } from '../../../kit/enable-when';
import { GwIcon } from '../../../kit/icons';
import { ToastService } from '../../../kit/toast.service';
import { formatBytes, joinAnd, plural, v } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureText } from './failure';

let nextId = 0;

/**
 * `<dialog gwPluginUpdateDialog>` (kit `dlg-update-jira`): the same checks as an install plus what changes in the
 * manifest, as a diff (`GET /api/plugins/{id}/update-preview`). Only the confirm loads the new code, side by side:
 * calls in flight finish on the old version, new tools start Off on every endpoint (§7.5). Focus starts on Cancel.
 * Decision 2 (ARCHITECTURE §8.6.5): "in a new plugin process", not "in a new classloader".
 *
 * An unsigned release (the catalog can list one: `signed: false`) loads unverified code like an unsigned install, so
 * the dialog is the install's (§9 "Plugin signatures"): `gw-dialog--danger`, and "Update to unsigned …" stays
 * `aria-disabled` until the acknowledgement is checked (review 2026-10-26: it was a plain primary button).
 */
@Component({
  selector: 'dialog[gwPluginUpdateDialog]',
  imports: [GwIcon, GwDialogClose, GwDiff, GwEnableWhen],
  hostDirectives: [{ directive: GwDialog, outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': '"gw-dialog gw-dialog--lg" + (preview()?.signed === false ? " gw-dialog--danger" : "")',
    '[attr.aria-labelledby]': 'preview() ? uid + "-t" : null',
    '[attr.aria-describedby]': 'preview() ? uid + "-d" : null',
  },
  template: `
    @if (preview(); as p) {
      <header class="gw-dialog__header">
        @if (p.signed) {
          <span class="gw-icon-tile gw-icon-tile--success"><svg lucideIcon="badge-check" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" [id]="uid + '-t'">Update {{ p.name }} to {{ to() }}</h2>
          <p class="gw-dialog__description" [id]="uid + '-d'">Loads without a restart, in a new plugin process; calls in flight finish on {{ from() }}. New tools start Off on every endpoint.</p>
        } @else {
          <span class="gw-icon-tile"><svg lucideIcon="shield-alert" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" [id]="uid + '-t'">Update {{ p.name }} to unsigned {{ to() }}?</h2>
          <p class="gw-dialog__description" [id]="uid + '-d'">Nobody has verified this code. It runs as your user in a separate process and gets only its connection's secrets during a call. It loads without a restart; calls in flight finish on {{ from() }}.</p>
        }
        <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
      </header>
      <div class="gw-dialog__body">
        <div class="gw-stack">
          <dl class="gw-kv gw-kv--dense">
            <div class="gw-kv__row"><dt class="gw-kv__key">Version</dt><dd class="gw-kv__value"><span class="gw-mono">{{ from() }}</span> to <span class="gw-mono">{{ to() }}</span></dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Signature</dt><dd class="gw-kv__value">@if (p.signed) {<span class="gw-badge gw-badge--success"><svg lucideIcon="badge-check" class="gw-icon gw-badge__icon"></svg>Signed</span> by the catalog key, matches the published package} @else {<span class="gw-badge gw-badge--warning"><svg lucideIcon="shield-alert" class="gw-icon gw-badge__icon"></svg>Unsigned</span> No signature from the catalog key}</dd></div>
            @if (p.package; as pkg) {
              <div class="gw-kv__row"><dt class="gw-kv__key">Package</dt><dd class="gw-kv__value"><span class="gw-mono">{{ pkg.file }}</span> · {{ size() }}</dd></div>
              <div class="gw-kv__row"><dt class="gw-kv__key">SHA-256</dt><dd class="gw-kv__value gw-mono">{{ pkg.sha256 }}</dd></div>
            }
          </dl>
          <gw-diff [diff]="p.diff" [filename]="'Manifest · ' + p.id" [summary]="p.summary" [regionLabel]="'Changes to the manifest of ' + p.name" />
          <p class="gw-text-small gw-fg-secondary">@for (part of consequence(); track $index) {@if (part.code) {<code class="gw-code">{{ part.text }}</code>} @else {{{ part.text }}}}</p>
          @if (!p.signed) {
            <label class="gw-checkbox">
              <input class="gw-checkbox__input" type="checkbox" [id]="uid + '-ack'" required>
              <span class="gw-checkbox__control" aria-hidden="true"></span>
              <span class="gw-checkbox__label" [id]="uid + '-ack-label'">I understand this code is not verified and runs on this computer as my user</span>
            </label>
          }
          @if (error()) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">{{ p.name }} wasn't updated</p><p class="gw-alert__body">{{ error() }}</p></div>
            </div>
          }
        </div>
      </div>
      <footer class="gw-dialog__footer">
        <button class="gw-btn" type="button" gwDialogClose autofocus>Cancel</button>
        @if (p.signed) {
          <button class="gw-btn gw-btn--primary" type="button" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null" (click)="update()">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}Update {{ p.name }} to {{ to() }}</button>
        } @else {
          <button class="gw-btn gw-btn--danger" type="button" aria-disabled="true" [attr.aria-describedby]="uid + '-ack-label'" [gwEnableWhen]="'#' + uid + '-ack'" [attr.aria-busy]="busy() ? 'true' : null" (click)="update()">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>}Update to unsigned {{ p.name }} {{ to() }}</button>
        }
      </footer>
    }
  `,
})
export class GwPluginUpdateDialog {
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly toasts = inject(ToastService);
  private readonly host: HTMLDialogElement = inject(ElementRef<HTMLDialogElement>).nativeElement;

  protected readonly uid = `pu-${++nextId}`;
  protected readonly preview = signal<PluginUpdatePreview | null>(null);
  /** Connections of the plugin, for "no connection uses the plugin yet". */
  private readonly connections = signal(0);
  /** Tools of the installed version, for "The 5 existing tools keep their modes". */
  private readonly toolsBefore = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly from = computed(() => v(this.preview()?.from) ?? '');
  protected readonly to = computed(() => v(this.preview()?.to) ?? '');
  protected readonly size = computed(() => formatBytes(this.preview()?.package?.size));

  /**
   * The paragraph under the diff (kit): the new tools start Off and come in as Approval (their annotations are the
   * plugin's word), the existing ones keep their modes, removed ones leave every endpoint; and who uses the plugin.
   */
  protected readonly consequence = computed(() => {
    const p = this.preview();
    if (!p) return [];
    const parts: { text: string; code: boolean }[] = [];
    const say = (text: string, code = false): void => {
      parts.push({ text, code });
    };
    const added = p.toolsAdded;
    added.forEach((t, i) => {
      if (i > 0) say(i === added.length - 1 ? ' and ' : ', ');
      say(t, true);
    });
    if (added.length) {
      say(
        `${added.length === 1 ? ' starts' : ' start'} Off on every endpoint; added to one, ${added.length === 1 ? 'it starts' : 'each starts'} as Approval: ${added.length === 1 ? 'its annotations are' : 'their annotations are'} declared by the plugin, not verified. `,
      );
    }
    if (p.toolsRemoved.length) say(`${joinAnd(p.toolsRemoved)} ${p.toolsRemoved.length === 1 ? 'leaves' : 'leave'} every endpoint. `);
    const before = this.toolsBefore();
    const kept = before === null ? null : before - p.toolsRemoved.length;
    if (kept !== null && kept > 0) say(`The ${plural(kept, 'existing tool')} keep${kept === 1 ? 's' : ''} ${kept === 1 ? 'its mode' : 'their modes'}; `);
    else if (!added.length && !p.toolsRemoved.length) say('The tools keep their modes; ');
    const n = this.connections();
    say(n ? `${plural(n, 'connection')} ${n === 1 ? 'uses' : 'use'} the plugin and ${n === 1 ? 'moves' : 'move'} to the new version.` : 'no connection uses the plugin yet.');
    const first = parts[0];
    if (first && !first.code) parts[0] = { ...first, text: first.text[0].toUpperCase() + first.text.slice(1) };
    return parts;
  });

  show(preview: PluginUpdatePreview, connections: number, toolsBefore: number | null, opener: HTMLElement | null): void {
    // A new update starts unacknowledged, even when the last one was unsigned too (the box is reused).
    const ack = this.host.querySelector<HTMLInputElement>(`#${this.uid}-ack`);
    if (ack?.checked) {
      ack.checked = false;
      ack.dispatchEvent(new Event('change', { bubbles: true }));
    }
    this.preview.set(preview);
    this.connections.set(connections);
    this.toolsBefore.set(toolsBefore);
    this.busy.set(false);
    this.error.set(null);
    this.dialog.openAfterRender(opener);
  }

  protected async update(): Promise<void> {
    const p = this.preview();
    if (!p || this.busy()) return;
    // An unsigned release waits for the acknowledgement (the guard keeps the aria-disabled button inert); the core
    // refuses the update without it too.
    if (!p.signed && !this.host.querySelector<HTMLInputElement>(`#${this.uid}-ack`)?.checked) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.data.updatePlugin(p.id, p.to, !p.signed);
      this.dialog.close('updated');
      const added = p.toolsAdded;
      this.toasts.show({
        tone: p.signed ? 'success' : 'warning',
        title: p.signed ? `${p.name} updated to ${this.to()}` : `${p.name} updated to unsigned ${this.to()}`,
        body: added.length
          ? `Loaded without a restart. ${joinAnd(added)} ${added.length === 1 ? 'is' : 'are'} Off on every endpoint.`
          : 'Loaded without a restart. The tools keep their modes.',
      });
    } catch (e) {
      this.error.set(failureText(e, 'This build of the gateway core can’t update plugins yet.'));
    } finally {
      this.busy.set(false);
    }
  }
}
