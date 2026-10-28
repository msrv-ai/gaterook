import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwToggle } from '../../kit/disclosure';
import { GwIcon } from '../../kit/icons';
import { GwReveal } from '../../kit/reveal';
import { ToastService } from '../../kit/toast.service';
import { GwJump } from './jump';
import { SecretsData } from './secrets.data';
import {
  ToastText,
  backedUpToast,
  backupFileName,
  backupLead,
  keyAlerts,
  keyFileName,
  createdRow,
  keysText,
  osProtectionText,
  primaryKeyId,
  protectsText,
  rotationView,
} from './secrets-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/**
 * Secrets › Master key (design/pages/secrets.html `#master-key`; ARCHITECTURE.md §6 `/api/master-key/*`, §8.4):
 * where the key is, who can read it, what it protects and when it was last backed up, with what is wrong with it in
 * the kit's words (permissions, OS protection, a synced folder, a missing backup).
 *
 * - Back up key (the card's primary): an optional passphrase, then the desktop's save dialog picks where the copy
 *   goes; the core gets only the dialog's one-time token, never a path. Outside the desktop app it can't save.
 * - Rotate key: what happens in four steps (Cancel has the focus), then the progress dialog — "Re-wrapping data keys
 *   · 5 of 8" from the core's `master-key.rotation` events; it may be closed, the core keeps going, and the card shows
 *   the progress meanwhile.
 * - Fix permissions on the alert that names wrong permissions.
 */
@Component({
  selector: 'section[gwMasterKeyCard]',
  imports: [RouterLink, GwIcon, GwDialog, GwDialogClose, GwReveal, GwToggle, GwCodeBlock, GwJump],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-card', id: 'master-key', 'aria-labelledby': 'mk-title' },
  templateUrl: './master-key-card.html',
})
export class GwMasterKeyCard {
  protected readonly data = inject(SecretsData);
  protected readonly time = inject(TimeService);
  protected readonly desktop = inject(DesktopBridge);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);

  private readonly backupDialog = viewChild.required<GwDialog>('backupDialog');
  private readonly rotateDialog = viewChild.required<GwDialog>('rotateDialog');
  private readonly progressDialog = viewChild.required<GwDialog>('progressDialog');
  private readonly passphrase = viewChild<ElementRef<HTMLInputElement>>('passphrase');

  protected readonly osProtectionText = osProtectionText;
  protected readonly keysText = keysText;

  protected readonly status = computed(() => this.data.masterKey().value);
  protected readonly alerts = computed(() => {
    const s = this.status();
    return s ? keyAlerts(s) : [];
  });
  protected readonly permsWrong = computed(() => this.status()?.problems.includes('perms') ?? false);
  protected readonly backupNeeded = computed(() => this.status()?.problems.includes('backup-needed') ?? false);
  protected readonly primary = computed(() => primaryKeyId(this.status()));
  /** "Jul 14, never rotated" (kit) when the core says when the key was made (step API); else the Keys row. */
  protected readonly created = computed(() => {
    const s = this.status();
    return s ? createdRow(s, (at) => this.time.date(at)) : null;
  });
  protected readonly protects = computed(() => protectsText(this.status()?.protects ?? 0));
  protected readonly backupLead = computed(() => backupLead(this.status()?.protects ?? 0));
  /** The name the save dialog suggests, on the core's clock. */
  protected readonly fileName = computed(() => backupFileName(this.time.minute()));

  protected readonly rotation = computed(() =>
    rotationView(this.data.rotation() ?? { state: 'idle', done: 0, total: 0, targetKeyId: null, error: null }, this.data.oldKeyId(), this.status()?.protects ?? 0),
  );
  /** What the Rotate dialog explains before it starts (the steps of an idle rotation). */
  protected readonly plan = computed(() => rotationView({ state: 'idle', done: 0, total: 0, targetKeyId: null, error: null }, null, this.status()?.protects ?? 0));
  /** While the core rotates, the card shows the progress and Rotate key says why it waits. */
  protected readonly rotating = computed(() => this.rotation().running);
  protected readonly showProgressOnCard = computed(() => {
    const s = this.rotation().state;
    return s === 'running' || s === 'failed';
  });

  protected readonly backupBusy = signal(false);
  protected readonly backupError = signal<string | null>(null);
  protected readonly rotateBusy = signal(false);
  protected readonly rotateError = signal<string | null>(null);
  protected readonly fixBusy = signal(false);

  // ── Back up ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected openBackup(e: Event): void {
    this.backupError.set(null);
    this.backupBusy.set(false);
    this.backupDialog().open(e.currentTarget as HTMLElement);
  }

  /** Choose where to save: the desktop's save dialog, then the core writes the copy (with the passphrase, if any). */
  protected async saveBackup(e: Event): Promise<void> {
    e.preventDefault();
    if (this.backupBusy() || !this.desktop.available) return;
    this.backupError.set(null);
    const token = await this.desktop.saveFile('master-key-backup');
    if (!token) return; // the person cancelled the save dialog: nothing happened
    this.backupBusy.set(true);
    const field = this.passphrase()?.nativeElement;
    const passphrase = field?.value ? field.value : null;
    try {
      const result = await this.data.backup(token, passphrase);
      if (field) field.value = '';
      this.backupDialog().close('saved');
      this.toast(backedUpToast(result));
    } catch (err) {
      this.backupError.set(this.errorText(err));
    } finally {
      this.backupBusy.set(false);
    }
  }

  /** Whatever was typed as a passphrase leaves the page with the dialog. */
  protected onBackupClosed(): void {
    const field = this.passphrase()?.nativeElement;
    if (field) {
      field.value = '';
      field.type = 'password';
    }
    this.backupError.set(null);
  }

  // ── Rotate ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected openRotate(e: Event): void {
    if (this.rotating()) return;
    this.rotateError.set(null);
    this.rotateBusy.set(false);
    this.rotateDialog().open(e.currentTarget as HTMLElement);
  }

  /** Rotate master key: the core starts (202); this dialog hands over to the progress one (kit `data-gw-dialog-next`). */
  protected async startRotation(e: Event): Promise<void> {
    e.preventDefault();
    if (this.rotateBusy()) return;
    this.rotateBusy.set(true);
    this.rotateError.set(null);
    try {
      await this.data.rotateKey();
      const from = this.rotateDialog();
      const opener = from.returnTo;
      from.returnTo = null;
      from.close('next');
      this.progressDialog().open(opener);
      this.announcer.announce('Master key rotation started');
    } catch (err) {
      this.rotateError.set(this.errorText(err));
    } finally {
      this.rotateBusy.set(false);
    }
  }

  /** Show progress (the card's link while the core rotates in the background). */
  protected openProgress(e: Event): void {
    this.progressDialog().open(e.currentTarget as HTMLElement);
  }

  /** After a rotation the old copies don't open the data keys: Back up key from the progress dialog. */
  protected backupAfterRotation(): void {
    const from = this.progressDialog();
    const opener = from.returnTo;
    from.returnTo = null;
    from.close('backup');
    this.backupError.set(null);
    afterNextRender(() => this.backupDialog().open(opener), { injector: this.injector });
  }

  // ── Fix permissions ───────────────────────────────────────────────────────────────────────────────────────────

  protected async fixPermissions(): Promise<void> {
    if (this.fixBusy()) return;
    this.fixBusy.set(true);
    try {
      const s = await this.data.fixPermissions();
      if (s.problems.includes('perms')) {
        this.toast({ tone: 'warning', title: "Permissions didn't change", body: `${keyFileName(s.path)} is still ${s.mode ?? 'readable by others'}. Change them in a terminal, then check again.` });
      } else {
        this.toast({ tone: 'success', title: 'Permissions fixed', body: `${keyFileName(s.path)} is 0400 again: only you can read it.` });
      }
    } catch (err) {
      this.toast({ tone: 'danger', title: "Permissions didn't change", body: this.errorText(err) });
    } finally {
      this.fixBusy.set(false);
    }
  }

  // ── Errors ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : NO_ANSWER;
  }

  protected errorDetails(e: unknown): string {
    if (isApiError(e)) return `GET /api/master-key → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }

  private toast(t: ToastText): void {
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body, timeout: t.timeout });
  }
}
