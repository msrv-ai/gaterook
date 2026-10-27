import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { DesktopBridge, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { TimeService } from '../../core/time/time.service';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { GwReveal } from '../../kit/reveal';
import { ToastService } from '../../kit/toast.service';
import { SettingsData } from './settings.data';
import { boolOf, dailyHint, dayAt, dbBackedUpToast, fileOf, folderOf, keyBackedUpToast, keyBackupFileName, keyBackupLead, numOf, onOff, protectsText, savedToast, strOf } from './settings-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Settings › Backup (design/pages/settings.html `#settings-backup`): what restoring this gateway elsewhere needs.
 *
 * - Database applies at once: the daily copy (`PUT /api/settings/backup {daily}`; 03:00 or the next start, 7 kept),
 *   the files from `/api/health` and `/api/backup`, Back up now (`POST /api/backup/db`, into the backup folder) and
 *   Open backup folder (desktop only, else unavailable with the reason).
 * - Master key: the key file, what it protects, the last copy; Back up master key opens the kit's dialog
 *   (`gw-dialog--md`, static, Cancel has focus): an optional passphrase (typed once, never kept in a signal), then the
 *   desktop's save dialog names the file (`saveToken`) and the core writes the copy. Outside the desktop app the save
 *   is unavailable with the reason.
 */
@Component({
  selector: 'section[gwBackupSettings]',
  imports: [RouterLink, GwIcon, GwDialog, GwDialogClose, GwReveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-backup', 'aria-labelledby': 'st-backup-t' },
  template: `
    <header class="gw-section__header">
      <h2 class="gw-section__title" id="st-backup-t">Backup</h2>
      <p class="gw-section__description">What you need to restore this gateway on another computer: the database, and the master key kept apart from it.</p>
    </header>
    <hr class="gw-divider">
    <div class="gw-form">
      <section class="gw-form__section" aria-labelledby="st-db-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-db-t">Database</h3>
          <p class="gw-form__description">Connections, endpoints, policies and the audit log. Secrets are inside only in encrypted form.</p>
        </header>
        <div class="gw-form__rows">
          <div class="gw-switch">
            <input class="gw-switch__input" type="checkbox" role="switch" id="st-daily-backup" [checked]="daily()" [attr.aria-busy]="busy() === 'daily' ? 'true' : null" aria-describedby="st-daily-backup-hint" (change)="setDaily($event)">
            <span class="gw-switch__control" aria-hidden="true"></span>
            <label class="gw-switch__label" for="st-daily-backup">Back up the database every day</label>
            <p class="gw-switch__hint" id="st-daily-backup-hint">{{ hint() }}</p>
          </div>
          @let b = data.backup().value;
          <dl class="gw-kv">
            <div class="gw-kv__row"><dt class="gw-kv__key">Database file</dt><dd class="gw-kv__value gw-mono">{{ data.health().value?.db?.path ?? '—' }}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Last backup</dt><dd class="gw-kv__value">
              @if (b?.lastError; as err) {
                <span class="gw-status gw-status--danger"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">Failed@if (b?.lastAttemptAt; as at) { <time [attr.datetime]="time.iso(at)" [attr.title]="time.absolute(at)">{{ time.relative(at) }}</time>}: {{ err }}</span></span>
              } @else if (b?.lastBackupAt; as at) {
                <time [attr.datetime]="time.iso(at)" [attr.title]="time.absolute(at)">{{ dayAt(at) }}</time>@if (b?.lastFile; as f) { · <span class="gw-mono">{{ f }}</span>}
              } @else if (b) {
                None yet
              } @else {
                —
              }
            </dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Backup folder</dt><dd class="gw-kv__value gw-mono">{{ b ? folderText(b.folder) : '—' }}</dd></div>
          </dl>
          <div class="gw-settings-layout__inline">
            <button class="gw-btn" type="button" [attr.aria-busy]="busy() === 'now' ? 'true' : null" [attr.aria-disabled]="busy() ? 'true' : null" (click)="backupNow()">@if (busy() === 'now') {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="database-backup" class="gw-icon"></svg>} Back up now</button>
            <button class="gw-btn gw-btn--ghost" type="button" [attr.aria-disabled]="desktop.available ? null : 'true'" [attr.aria-describedby]="desktop.available ? null : 'st-backup-folder-why'" (click)="openFolder()"><svg lucideIcon="folder-open" class="gw-icon"></svg> Open backup folder</button>
            @if (!desktop.available) {
              <p class="gw-text-caption gw-fg-muted" id="st-backup-folder-why">The desktop app opens folders; this window runs outside it.</p>
            }
          </div>
        </div>
      </section>

      <section class="gw-form__section" aria-labelledby="st-key-t">
        <header class="gw-form__section-header">
          <h3 class="gw-form__legend" id="st-key-t">Master key</h3>
          <p class="gw-form__description">Never stored inside the database or its backups. Keep a copy apart from them.</p>
        </header>
        <div class="gw-form__rows">
          @let k = data.masterKey().value;
          <dl class="gw-kv">
            <div class="gw-kv__row"><dt class="gw-kv__key">Key file</dt><dd class="gw-kv__value gw-mono">{{ k?.path ?? '—' }}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Protects</dt><dd class="gw-kv__value">{{ k ? protects() : '—' }}</dd></div>
            <div class="gw-kv__row"><dt class="gw-kv__key">Last copy</dt><dd class="gw-kv__value">@if (k) {<span class="gw-status" [class.gw-status--warning]="backupNeeded()" [class.gw-status--success]="!backupNeeded()"><span class="gw-status__dot" aria-hidden="true"></span><span class="gw-status__label">@if (k.lastBackupAt; as at) {<time [attr.datetime]="time.iso(at)" [attr.title]="time.absolute(at, false)">{{ time.relative(at) }}</time>} @else {Never}</span></span>} @else {—}</dd></div>
          </dl>
          @if (k) {
            <div class="gw-alert gw-alert--info gw-alert--compact">
              <svg lucideIcon="folder-lock" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__body">Copies of <span class="gw-mono">{{ keyFolder() }}</span> include <span class="gw-mono">{{ keyFile() }}</span>: exclude it from {{ folderBackups() }}, so a copy of the database never travels with its key.</p></div>
            </div>
          }
          <div class="gw-settings-layout__inline">
            <button class="gw-btn" type="button" aria-haspopup="dialog" [attr.aria-disabled]="k && !k.locked ? null : 'true'" [attr.aria-describedby]="k?.locked ? 'st-key-locked' : null" (click)="openKeyBackup($event)"><svg lucideIcon="archive" class="gw-icon"></svg> Back up master key</button>
            <a class="gw-btn gw-btn--ghost" routerLink="/secrets" fragment="master-key"><svg lucideIcon="key-round" class="gw-icon"></svg> Manage master key in Secrets</a>
            @if (k?.locked) {
              <p class="gw-text-caption gw-fg-muted" id="st-key-locked">The master key can't be read, so there is nothing to copy. Secrets says what happened.</p>
            }
          </div>
        </div>
      </section>
    </div>

    <dialog class="gw-dialog gw-dialog--md" id="st-backup-key-dlg" gwDialog gwDialogStatic #keyDialog="gwDialog" aria-labelledby="st-backup-key-t" aria-describedby="st-backup-key-d" (closed)="onKeyDialogClosed()">
      <form method="dialog" id="st-backup-key-form" novalidate (submit)="saveKeyBackup($event)">
        <header class="gw-dialog__header">
          <span class="gw-icon-tile"><svg lucideIcon="archive" class="gw-icon"></svg></span>
          <h2 class="gw-dialog__title" id="st-backup-key-t">Back up master key</h2>
          <p class="gw-dialog__description" id="st-backup-key-d">{{ lead() }}</p>
          <button class="gw-btn gw-btn--ghost gw-btn--icon gw-btn--sm gw-dialog__close" type="button" aria-label="Close" gwDialogClose><svg lucideIcon="x" class="gw-icon"></svg></button>
        </header>
        <div class="gw-dialog__body">
          <div class="gw-stack">
            <dl class="gw-kv gw-kv--dense">
              <div class="gw-kv__row"><dt class="gw-kv__key">Key file</dt><dd class="gw-kv__value gw-mono">{{ data.masterKey().value?.path }}</dd></div>
              <div class="gw-kv__row"><dt class="gw-kv__key">Primary key ID</dt><dd class="gw-kv__value gw-mono">{{ primary() ?? 'None' }}</dd></div>
              <div class="gw-kv__row"><dt class="gw-kv__key">File name</dt><dd class="gw-kv__value gw-mono">{{ fileName() }}</dd></div>
            </dl>
            <div class="gw-field">
              <label class="gw-field__label" for="st-backup-pass">Encrypt the copy with a passphrase <span class="gw-field__optional">Optional</span></label>
              <div class="gw-input-group">
                <!-- A new value typed for the first time: showing it is allowed. Never kept in a signal. -->
                <input #passphrase class="gw-input" id="st-backup-pass" type="password" autocomplete="new-password" spellcheck="false" aria-describedby="st-backup-pass-hint">
                <button class="gw-input-group__addon" type="button" gwReveal aria-controls="st-backup-pass" aria-label="Show passphrase"><svg lucideIcon="eye" class="gw-icon"></svg><svg lucideIcon="eye-off" class="gw-icon"></svg></button>
              </div>
              <p class="gw-field__hint" id="st-backup-pass-hint">Without one, the copy is the plain key: whoever has it and a copy of the database can read every secret. You need the passphrase to restore.</p>
            </div>
            <div class="gw-alert gw-alert--info gw-alert--compact">
              <svg lucideIcon="info" class="gw-icon gw-alert__icon"></svg>
              <div class="gw-alert__content"><p class="gw-alert__body">A save dialog asks where the copy goes. Pick a place off this computer, such as your password manager or an encrypted drive, never next to database backups.@if (desktop.platform === 'darwin') { macOS asks for your password before the copy is written.}</p></div>
            </div>
            @if (!desktop.available) {
              <p class="gw-text-small gw-fg-muted" id="st-backup-key-why">Saving a file needs the MCP Gateway desktop app: it opens the save dialog. Back up the key from there.</p>
            }
            @if (keyError(); as err) {
              <div class="gw-alert gw-alert--danger gw-alert--compact" role="alert">
                <svg lucideIcon="circle-alert" class="gw-icon gw-alert__icon"></svg>
                <div class="gw-alert__content"><p class="gw-alert__title">The copy wasn't written</p><p class="gw-alert__body">{{ err }}</p></div>
              </div>
            }
          </div>
        </div>
        <footer class="gw-dialog__footer">
          <button class="gw-btn" type="button" gwDialogClose autofocus>Cancel</button>
          <button class="gw-btn gw-btn--primary" type="submit" [attr.aria-disabled]="!desktop.available || keyBusy() ? 'true' : null" [attr.aria-busy]="keyBusy() ? 'true' : null" [attr.aria-describedby]="!desktop.available ? 'st-backup-key-why' : null">@if (keyBusy()) {<span class="gw-spinner gw-spinner--sm" aria-hidden="true"></span>} @else {<svg lucideIcon="save" class="gw-icon"></svg>} Choose where to save</button>
        </footer>
      </form>
    </dialog>
  `,
})
export class GwBackupSettings {
  protected readonly data = inject(SettingsData);
  protected readonly desktop = inject(DesktopBridge);
  protected readonly time = inject(TimeService);
  private readonly toasts = inject(ToastService);

  private readonly keyDialog = viewChild.required<GwDialog>('keyDialog');
  private readonly passphrase = viewChild<ElementRef<HTMLInputElement>>('passphrase');

  private readonly values = computed(() => this.data.section('backup')?.values);
  protected readonly daily = computed(() => this.data.backup().value?.daily ?? boolOf(this.values(), 'daily', true));
  protected readonly hint = computed(() => {
    const b = this.data.backup().value;
    return dailyHint(b?.at ?? strOf(this.values(), 'at', '03:00'), b?.keep ?? numOf(this.values(), 'keep', 7));
  });
  protected readonly busy = signal<'daily' | 'now' | null>(null);
  protected readonly dayAt = (at: string) => dayAt(at, this.time.minute());

  protected readonly protects = computed(() => protectsText(this.data.masterKey().value?.protects ?? 0, this.data.references()));
  protected readonly backupNeeded = computed(() => this.data.masterKey().value?.problems.includes('backup-needed') ?? false);
  /** The data folder with its trailing slash (kit: `~/.mcp-gateway/`). */
  protected readonly keyFolder = computed(() => folderOf(this.data.masterKey().value?.path) ?? '');
  protected readonly keyFile = computed(() => fileOf(this.data.masterKey().value?.path ?? 'master.keyset'));
  protected readonly folderBackups = computed(() =>
    this.desktop.platform === 'darwin' ? 'Time Machine and other folder backups' : this.desktop.platform === 'win32' ? 'File History and other folder backups' : 'folder backups',
  );
  protected readonly lead = computed(() => keyBackupLead(this.data.masterKey().value?.protects ?? 0));
  protected readonly primary = computed(() => this.data.masterKey().value?.keys.find((k) => k.primary)?.id ?? null);
  protected readonly fileName = computed(() => keyBackupFileName(this.time.minute()));
  protected readonly keyBusy = signal(false);
  protected readonly keyError = signal<string | null>(null);

  /** The folder with a trailing slash, as the kit writes it. */
  protected folderText(folder: string): string {
    return folder.endsWith('/') ? folder : `${folder}/`;
  }

  protected async setDaily(e: Event): Promise<void> {
    const input = e.target as HTMLInputElement;
    const on = input.checked;
    if (this.busy()) {
      input.checked = !on;
      return;
    }
    this.busy.set('daily');
    try {
      await this.data.save('backup', { daily: on });
      this.toasts.show(savedToast('Back up the database every day', onOff(on)));
    } catch (err) {
      input.checked = !on;
      this.toasts.show({ tone: 'danger', title: 'The daily backup wasn’t changed', body: this.errorText(err) });
    } finally {
      this.busy.set(null);
    }
  }

  protected async backupNow(): Promise<void> {
    if (this.busy()) return;
    this.busy.set('now');
    try {
      this.toasts.show(dbBackedUpToast(await this.data.backupNow()));
    } catch (err) {
      this.toasts.show({ tone: 'danger', title: 'The database wasn’t backed up', body: this.errorText(err) });
    } finally {
      this.busy.set(null);
    }
  }

  protected async openFolder(): Promise<void> {
    if (!this.desktop.available) return;
    await this.desktop.revealInFolder('backups');
  }

  protected openKeyBackup(e: Event): void {
    const k = this.data.masterKey().value;
    if (!k || k.locked) return;
    this.keyError.set(null);
    this.keyBusy.set(false);
    this.keyDialog().open(e.currentTarget as HTMLElement);
  }

  /** Choose where to save: the desktop's save dialog, then the core writes the copy (with the passphrase, if any). */
  protected async saveKeyBackup(e: Event): Promise<void> {
    e.preventDefault();
    if (this.keyBusy() || !this.desktop.available) return;
    this.keyError.set(null);
    let token: string | null;
    try {
      token = await this.desktop.saveFile('master-key-backup');
    } catch (err) {
      // Main couldn't hand the file to the core (the core isn't ready, or it refused the place): say so here.
      this.keyError.set(desktopErrorText(err, 'The app couldn’t save the file there. Choose another place.'));
      return;
    }
    if (!token) return; // the save dialog was cancelled: nothing happened
    this.keyBusy.set(true);
    const field = this.passphrase()?.nativeElement;
    const passphrase = field?.value ? field.value : null;
    try {
      const result = await this.data.backupKey(token, passphrase);
      if (field) field.value = '';
      this.keyDialog().close('saved');
      this.toasts.show(keyBackedUpToast(result));
    } catch (err) {
      this.keyError.set(this.errorText(err));
    } finally {
      this.keyBusy.set(false);
    }
  }

  /** Whatever was typed as a passphrase leaves the page with the dialog. */
  protected onKeyDialogClosed(): void {
    const field = this.passphrase()?.nativeElement;
    if (field) {
      field.value = '';
      field.type = 'password';
    }
    this.keyError.set(null);
  }

  private errorText(e: unknown): string {
    return isApiError(e) ? e.detail : NO_ANSWER;
  }
}
