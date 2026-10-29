import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DesktopBridge, FullBackupResult } from '../../core/desktop/desktop-bridge';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { ToastService } from '../../kit/toast.service';
import { GwFullBackup } from './full-backup.component';

describe('Encrypted full backup dialogs', () => {
  let fixture: ComponentFixture<GwFullBackup>;
  let root: HTMLElement;
  let desktop: {
    fullBackupAvailable: boolean;
    createFullBackup: ReturnType<typeof vi.fn<(passphrase: string) => Promise<FullBackupResult>>>;
    restoreFullBackup: ReturnType<typeof vi.fn<(passphrase: string, confirmation: string) => Promise<FullBackupResult>>>;
  };
  const find = <T extends Element = HTMLElement>(selector: string) => root.querySelector<T>(selector)!;
  const field = (id: string, value: string) => { find<HTMLInputElement>(`#${id}`).value = value; };
  const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const button = (label: string, container: Element = root) => [...container.querySelectorAll<HTMLButtonElement>('button')].find((b) => text(b) === label)!;
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }
  async function click(label: string, container?: Element): Promise<void> {
    button(label, container).click();
    await settle();
  }
  async function submitCreate(): Promise<void> {
    find<HTMLFormElement>('#st-full-backup-dlg form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
  }
  beforeEach(async () => {
    desktop = {
      fullBackupAvailable: true,
      createFullBackup: vi.fn(async () => ({ cancelled: false, fileName: 'gateway.gaterook-backup' })),
      restoreFullBackup: vi.fn(async () => ({ cancelled: false })),
    };
    TestBed.configureTestingModule({ providers: [provideKitIcons(), { provide: DesktopBridge, useValue: desktop }] });
    TestBed.inject(AriaDisabledGuard).install();
    fixture = TestBed.createComponent(GwFullBackup);
    fixture.componentRef.setInput('canCreate', true);
    root = fixture.nativeElement;
    document.body.appendChild(root);
    await settle();
  });
  afterEach(() => { fixture.destroy(); document.body.innerHTML = ''; });

  it('requires a desktop with full backup support', async () => {
    desktop.fullBackupAvailable = false;
    fixture.componentRef.setInput('canCreate', false);
    await settle();
    await click('Create full backup');
    await click('Restore full backup');
    expect(find<HTMLDialogElement>('#st-full-backup-dlg').open).toBe(false);
    expect(find<HTMLDialogElement>('#st-full-restore-dlg').open).toBe(false);
    expect(text(root)).toContain('up-to-date GateRook desktop app');
  });

  it('allows restoring an existing full backup when the current master key is locked', async () => {
    fixture.componentRef.setInput('canCreate', false);
    await settle();
    await click('Create full backup');
    expect(find<HTMLDialogElement>('#st-full-backup-dlg').open).toBe(false);
    await click('Restore full backup');
    expect(find<HTMLDialogElement>('#st-full-restore-dlg').open).toBe(true);
  });

  it('validates passphrase length and confirmation before invoking the desktop', async () => {
    await click('Create full backup');
    expect(document.activeElement?.id).toBe('st-full-backup-pass');
    field('st-full-backup-pass', 'short');
    field('st-full-backup-repeat', 'short');
    await submitCreate();
    expect(desktop.createFullBackup).not.toHaveBeenCalled();
    expect(text(find('#st-full-backup-error'))).toContain('12 and 1,024');
    expect(find('#st-full-backup-pass').getAttribute('aria-invalid')).toBe('true');
    field('st-full-backup-pass', 'twelve words are better');
    await submitCreate();
    expect(document.activeElement?.id).toBe('st-full-backup-repeat');
    expect(desktop.createFullBackup).not.toHaveBeenCalled();
    field('st-full-backup-repeat', 'twelve words are better');
    await submitCreate();
    expect(desktop.createFullBackup).toHaveBeenCalledExactlyOnceWith('twelve words are better');
    expect(find<HTMLInputElement>('#st-full-backup-pass').value).toBe('');
    expect(find<HTMLInputElement>('#st-full-backup-repeat').value).toBe('');
    expect(find<HTMLDialogElement>('#st-full-backup-dlg').open).toBe(false);
    expect(TestBed.inject(ToastService).toasts()[0].title).toBe('Encrypted full backup saved');
  });

  it('prevents duplicate backups and cancellation while work is pending, with no passphrase left in fields', async () => {
    let complete!: (result: FullBackupResult) => void;
    desktop.createFullBackup.mockImplementation(() => new Promise((resolve) => { complete = resolve; }));
    await click('Create full backup');
    field('st-full-backup-pass', 'a long backup passphrase');
    field('st-full-backup-repeat', 'a long backup passphrase');
    await submitCreate();
    const dialog = find<HTMLDialogElement>('#st-full-backup-dlg');
    expect(find<HTMLInputElement>('#st-full-backup-pass').value).toBe('');
    await submitCreate();
    await click('Cancel', dialog);
    const cancel = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(cancel);
    expect(cancel.defaultPrevented).toBe(true);
    expect(dialog.open).toBe(true);
    expect(desktop.createFullBackup).toHaveBeenCalledTimes(1);
    complete({ cancelled: true });
    await settle();
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    await click('Cancel', dialog);
    expect(dialog.open).toBe(false);
  });

  it('shows native failures in the active dialog without retaining the passphrase', async () => {
    desktop.createFullBackup.mockRejectedValue(new Error("Error invoking remote method 'gw:full-backup': Error: Not enough disk space."));
    await click('Create full backup');
    field('st-full-backup-pass', 'a long backup passphrase');
    field('st-full-backup-repeat', 'a long backup passphrase');
    await submitCreate();
    expect(find<HTMLDialogElement>('#st-full-backup-dlg').open).toBe(true);
    expect(text(find('#st-full-backup-error'))).toBe('Not enough disk space.');
    expect(find<HTMLInputElement>('#st-full-backup-pass').value).toBe('');
  });

  it('requires RESTORE exactly and an explicit button activation, with Cancel initially focused', async () => {
    await click('Restore full backup');
    const dialog = find<HTMLDialogElement>('#st-full-restore-dlg');
    expect(text(document.activeElement)).toBe('Cancel');
    expect(dialog.querySelector('form')).toBeNull();
    expect(button('Choose backup and restore', dialog).type).toBe('button');
    field('st-full-restore-pass', 'a long backup passphrase');
    field('st-full-restore-confirm', 'restore');
    await click('Choose backup and restore', dialog);
    expect(desktop.restoreFullBackup).not.toHaveBeenCalled();
    expect(document.activeElement?.id).toBe('st-full-restore-confirm');
    field('st-full-restore-confirm', 'RESTORE');
    await click('Choose backup and restore', dialog);
    expect(desktop.restoreFullBackup).toHaveBeenCalledExactlyOnceWith('a long backup passphrase', 'RESTORE');
    expect(find<HTMLInputElement>('#st-full-restore-pass').value).toBe('');
    expect(find<HTMLInputElement>('#st-full-restore-confirm').value).toBe('');
  });

  it('does not claim success when file selection is cancelled or authentication fails', async () => {
    desktop.restoreFullBackup.mockResolvedValueOnce({ cancelled: true }).mockRejectedValueOnce(new Error('The passphrase is wrong or the backup is damaged.'));
    await click('Restore full backup');
    const dialog = find<HTMLDialogElement>('#st-full-restore-dlg');
    for (let attempt = 0; attempt < 2; attempt++) {
      field('st-full-restore-pass', 'a long backup passphrase');
      field('st-full-restore-confirm', 'RESTORE');
      await click('Choose backup and restore', dialog);
      expect(dialog.open).toBe(true);
      expect(find<HTMLInputElement>('#st-full-restore-pass').value).toBe('');
      expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    }
    expect(text(find('#st-full-restore-error'))).toContain('passphrase is wrong');
  });

  it('clears unsubmitted secrets and errors when either dialog closes', async () => {
    await click('Create full backup');
    field('st-full-backup-pass', 'a long backup passphrase');
    field('st-full-backup-repeat', 'different');
    await submitCreate();
    await click('Cancel', find('#st-full-backup-dlg'));
    await click('Create full backup');
    expect(find<HTMLInputElement>('#st-full-backup-pass').value).toBe('');
    expect(root.querySelector('#st-full-backup-error')).toBeNull();
  });
});
