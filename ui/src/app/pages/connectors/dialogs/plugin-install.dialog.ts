import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, output, signal, viewChild } from '@angular/core';
import { PluginCheck } from '../../../core/api/plugins.api';
import { GwDialog, GwDialogClose } from '../../../kit/dialog';
import { GwFileDrop } from '../../../kit/file-drop';
import { GwIcon } from '../../../kit/icons';
import { formatBytes } from '../connectors-view';
import { ConnectorsData } from '../connectors.data';
import { failureText } from './failure';

/** A plugin JAR is at most 50 MB (§8.2 file drop). */
export const PLUGIN_MAX_BYTES = 50 * 1024 * 1024;

/** The check passed on to the review: the report and where the JAR came from (its file name or URL). */
export interface PluginChecked {
  readonly check: PluginCheck;
  readonly source: string | null;
  readonly opener: HTMLElement | null;
}

let nextId = 0;

/**
 * `<dialog gwPluginInstallDialog>` (kit `dlg-install-plugin`): step 1 of installing a plugin — a JAR from this
 * computer (drop or choose, ≤ 50 MB) or an HTTPS URL. "Check plugin" sends it to the core, which stages it and checks
 * the signature and the manifest before anything loads (`POST /api/plugins/check`); the result opens the review
 * (`checked`), signed or unsigned. Focus starts on the file drop; errors stay at their field.
 */
@Component({
  selector: 'dialog[gwPluginInstallDialog]',
  imports: [GwIcon, GwDialogClose, GwFileDrop],
  hostDirectives: [{ directive: GwDialog, inputs: ['gwDialogStatic'], outputs: ['closed'] }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-dialog gw-dialog--md',
    '[attr.aria-labelledby]': 'uid + "-t"',
    '[attr.aria-describedby]': 'uid + "-d"',
  },
  template: `
    <form method="dialog" novalidate (submit)="submit($event)">
      <header class="gw-dialog__header">
        <span class="gw-icon-tile"><svg lucideIcon="puzzle" class="gw-icon"></svg></span>
        <h2 class="gw-dialog__title" [id]="uid + '-t'">Install plugin</h2>
        <!-- Decision 2 (ARCHITECTURE §8.6.8): a plugin runs in its own plugin process, not in a classloader of the core. -->
        <p class="gw-dialog__description" [id]="uid + '-d'">A Java plugin adds a resource type with its own tools. It loads without a restart, in its own plugin process.</p>
        <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
      </header>
      <div class="gw-dialog__body">
        <div class="gw-stack">
          <label class="gw-file-drop" data-state="idle" [gwFileDropMaxBytes]="maxBytes" (fileChange)="onFile($event)" (tooLarge)="onTooLarge($event)">
            <input class="gw-file-drop__input" type="file" accept=".jar" autofocus #fileInput [attr.aria-describedby]="uid + '-file-hint'">
            <span class="gw-file-drop__icon"><svg [lucideIcon]="fileIcon()" class="gw-icon gw-icon--lg"></svg></span>
            <span class="gw-file-drop__title">@if (file(); as f) {<span class="gw-mono">{{ f.name }}</span>} @else {Drop a plugin JAR here or <span class="gw-fg-link">choose a file</span>}</span>
            <span class="gw-file-drop__hint" [id]="uid + '-file-hint'">{{ fileHint() }}</span>
          </label>
          <div class="gw-field">
            <label class="gw-field__label" [for]="uid + '-url'">Or a URL <span class="gw-field__optional">Optional</span></label>
            <input
              class="gw-input gw-input--mono"
              [id]="uid + '-url'"
              type="url"
              #url
              placeholder="https://releases.acme.internal/gw-plugin-1.0.0.jar"
              spellcheck="false"
              autocomplete="off"
              [attr.aria-invalid]="urlError() ? 'true' : null"
              [attr.aria-describedby]="uid + '-url-hint' + (urlError() ? ' ' + uid + '-url-err' : '')"
              (input)="urlError.set(null)"
            >
            <p class="gw-field__hint" [id]="uid + '-url-hint'">HTTPS only. The file is downloaded and checked the same way.</p>
            @if (urlError()) {
              <p class="gw-field__error" [id]="uid + '-url-err'"><svg lucideIcon="circle-alert" class="gw-icon"></svg>{{ urlError() }}</p>
            }
          </div>
          <div class="gw-alert gw-alert--info gw-alert--compact">
            <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
            <div class="gw-alert__content"><p class="gw-alert__body">Nothing loads before the check of the signature and the manifest (tools, hosts and commands it needs). Signed by the catalog: review and install. Unsigned: you confirm the risk first.</p></div>
          </div>
          @if (error()) {
            <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
              <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__title">The plugin wasn't checked</p><p class="gw-alert__body">{{ error() }}</p></div>
            </div>
          }
        </div>
      </div>
      <footer class="gw-dialog__footer">
        <button class="gw-btn" type="button" gwDialogClose>Cancel</button>
        <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-busy]="busy() ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null">@if (busy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="shield-check" class="gw-icon"></svg>} Check plugin</button>
      </footer>
    </form>
  `,
})
export class GwPluginInstallDialog {
  private readonly data = inject(ConnectorsData);
  private readonly dialog = inject(GwDialog);
  private readonly fileDrop = viewChild.required(GwFileDrop);
  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('fileInput');
  private readonly urlInput = viewChild.required<ElementRef<HTMLInputElement>>('url');

  /** The core's report: the review opens with it. */
  readonly checked = output<PluginChecked>();

  protected readonly uid = `pi-${++nextId}`;
  protected readonly maxBytes = PLUGIN_MAX_BYTES;
  protected readonly file = signal<File | null>(null);
  protected readonly tooLarge = signal<File | null>(null);
  protected readonly urlError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly fileIcon = computed(() => (this.tooLarge() ? 'file-x' : this.file() ? 'file-check' : 'upload'));
  protected readonly fileHint = computed(() => {
    const big = this.tooLarge();
    if (big) return `${big.name} is ${formatBytes(big.size)}. Choose a plugin JAR of up to 50 MB.`;
    const f = this.file();
    return f ? `${formatBytes(f.size)}. The core checks its signature and manifest next.` : 'One PF4J plugin JAR, up to 50 MB';
  });

  /** Opens empty: no file, no URL, nothing checked yet. */
  open(opener: HTMLElement | null): void {
    this.file.set(null);
    this.tooLarge.set(null);
    this.urlError.set(null);
    this.error.set(null);
    this.busy.set(false);
    this.fileInput().nativeElement.value = '';
    this.fileInput().nativeElement.removeAttribute('aria-invalid');
    this.urlInput().nativeElement.value = '';
    this.fileDrop().setState('idle');
    this.dialog.open(opener);
  }

  protected onFile(file: File | null): void {
    this.tooLarge.set(null);
    this.file.set(file);
    this.error.set(null);
    this.urlError.set(null);
  }

  protected onTooLarge(file: File): void {
    this.file.set(null);
    this.tooLarge.set(file);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy()) return;
    const file = this.file();
    const url = this.urlInput().nativeElement.value.trim();
    this.error.set(null);
    if (this.tooLarge()) {
      this.fileInput().nativeElement.focus();
      return;
    }
    if (file && url) return this.invalidUrl('Use the file or the URL, not both. Clear the URL to check the file.');
    if (!file && !url) return this.invalidUrl('Choose a plugin JAR above, or enter the URL to download it from.');
    if (!file && !/^https:\/\/[^\s/]+/i.test(url)) return this.invalidUrl('Use an HTTPS URL, starting with https://.');
    this.busy.set(true);
    try {
      const check = await this.data.checkPlugin(file ?? { url });
      const opener = this.dialog.returnTo;
      // The review takes over the dialog chain: focus returns to the first opener when it closes.
      this.dialog.returnTo = null;
      this.dialog.close('next');
      this.checked.emit({ check, source: file?.name ?? url, opener });
    } catch (err) {
      this.error.set(failureText(err, 'This build of the gateway core can’t install plugins yet.'));
    } finally {
      this.busy.set(false);
    }
  }

  private invalidUrl(message: string): void {
    this.urlError.set(message);
    this.urlInput().nativeElement.focus();
  }
}
