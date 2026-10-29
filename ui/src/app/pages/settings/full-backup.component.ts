import { ChangeDetectionStrategy, Component, ElementRef, booleanAttribute, inject, input, signal, viewChild } from '@angular/core';
import { DesktopBridge, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { GwDialog } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';

/** Encrypted, portable recovery; native file selection and archive contents stay in the desktop process. */
@Component({
  selector: 'section[gwFullBackup], gw-full-backup',
  imports: [GwDialog, GwIcon],
  templateUrl: './full-backup.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.gw-form__section]': '!restoreOnly()', '[attr.aria-labelledby]': 'restoreOnly() ? null : ids("full-backup-t")' },
})
export class GwFullBackup {
  readonly canCreate = input(false);
  readonly restoreOnly = input(false, { transform: booleanAttribute });
  readonly idPrefix = input('st');
  protected ids(names: string): string {
    return names.split(' ').map((name) => `${this.idPrefix()}-${name}`).join(' ');
  }
  protected readonly desktop = inject(DesktopBridge);
  private readonly toasts = inject(ToastService);
  private readonly createDialog = viewChild.required<GwDialog>('createDialog');
  private readonly restoreDialog = viewChild.required<GwDialog>('restoreDialog');
  private readonly createPass = viewChild.required<ElementRef<HTMLInputElement>>('createPass');
  private readonly repeatPass = viewChild.required<ElementRef<HTMLInputElement>>('repeatPass');
  private readonly restorePass = viewChild.required<ElementRef<HTMLInputElement>>('restorePass');
  private readonly confirmation = viewChild.required<ElementRef<HTMLInputElement>>('confirmation');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly invalid = signal<'create' | 'repeat' | 'restore' | 'confirmation' | null>(null);

  protected openCreate(event: Event): void {
    if (!this.desktop.fullBackupAvailable || !this.canCreate() || this.busy()) return;
    this.clear();
    this.createDialog().open(event.currentTarget as HTMLElement);
  }

  protected openRestore(event: Event): void {
    if (!this.desktop.fullBackupAvailable || this.busy()) return;
    this.clear();
    this.restoreDialog().open(event.currentTarget as HTMLElement);
  }

  protected cancel(kind: 'create' | 'restore'): void {
    if (this.busy()) return;
    (kind === 'create' ? this.createDialog() : this.restoreDialog()).close();
  }

  protected guardCancel(event: Event): void {
    if (this.busy()) event.preventDefault();
  }

  protected clear(): void {
    for (const field of [this.createPass(), this.repeatPass(), this.restorePass(), this.confirmation()]) field.nativeElement.value = '';
    this.error.set(null);
    this.invalid.set(null);
  }

  private fail(field: 'create' | 'repeat' | 'restore' | 'confirmation', message: string): void {
    this.invalid.set(field);
    this.error.set(message);
    ({ create: this.createPass(), repeat: this.repeatPass(), restore: this.restorePass(), confirmation: this.confirmation() })[field].nativeElement.focus();
  }

  protected async create(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.desktop.fullBackupAvailable || !this.canCreate() || this.busy()) return;
    const passphrase = this.createPass().nativeElement.value;
    if (passphrase.length < 12 || passphrase.length > 1024) {
      this.fail('create', 'Use a passphrase between 12 and 1,024 characters.');
      return;
    }
    if (passphrase !== this.repeatPass().nativeElement.value) {
      this.fail('repeat', 'The passphrases do not match. Enter the same passphrase again.');
      return;
    }
    this.clear();
    this.busy.set(true);
    try {
      const result = await this.desktop.createFullBackup(passphrase);
      if (result.cancelled) return;
      this.createDialog().close('saved');
      this.toasts.show({ tone: 'success', title: 'Encrypted full backup saved', body: result.fileName ? `${result.fileName}. Keep the passphrase separately from this file.` : 'Keep the passphrase separately from the backup file.' });
    } catch (err) {
      this.error.set(desktopErrorText(err, 'The backup could not be saved. Enter your passphrase and try again.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async restore(): Promise<void> {
    if (!this.desktop.fullBackupAvailable || this.busy()) return;
    const passphrase = this.restorePass().nativeElement.value;
    if (!passphrase || passphrase.length > 1024) {
      this.fail('restore', 'Enter the passphrase used to encrypt this backup.');
      return;
    }
    if (this.confirmation().nativeElement.value !== 'RESTORE') {
      this.fail('confirmation', 'Type RESTORE exactly to confirm replacing the current gateway data.');
      return;
    }
    this.clear();
    this.busy.set(true);
    try {
      const result = await this.desktop.restoreFullBackup(passphrase, 'RESTORE');
      if (result.cancelled) return;
      this.restoreDialog().close('restored');
      this.toasts.show({ tone: 'success', title: 'Full backup restored', body: 'GateRook will close. Reopen it and reconnect your AI apps.' });
    } catch (err) {
      this.error.set(desktopErrorText(err, 'The backup could not be restored. Enter your passphrase and try again.'));
    } finally {
      this.busy.set(false);
    }
  }
}
