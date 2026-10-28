import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Router } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { MasterKeyApi, MasterKeyStatus } from '../../core/api/secrets.api';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { ApprovalsStore } from '../../core/stores/approvals.store';
import { CoreStore } from '../../core/stores/core.store';
import { EndpointsStore } from '../../core/stores/endpoints.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwStatusScreen, StatusScreenBusy, StatusScreenNotice } from '../../kit/elements/status-screen/status-screen';
import { GwIcon } from '../../kit/icons';
import { GwReveal } from '../../kit/reveal';
import { ToastService } from '../../kit/toast.service';
import {
  DEFAULT_KEY_PATH,
  ToastText,
  createProblem,
  createdToast,
  desktopError,
  endpointUrls,
  firstCandidate,
  logFileOf,
  portError,
  portOf,
  publicPortError,
  restoreProblem,
  restoredToast,
  statusView,
  usePortError,
} from './core-starting-view';

/** Outside Electron the window is a browser tab: Quit has nothing to quit (it says so, aria-disabled). */
const QUIT_IN_BROWSER = 'This window is a browser tab: close the tab to leave.';

/**
 * Core starting (design/pages/core-starting.html; ARCHITECTURE.md §7.1, §8.1, §8.4): not a route — the status screen
 * over the whole window while the core isn't running, driven by Electron's core state (`onCoreState`: progress lines,
 * the ready line, a failed start, an exit) and `/api/health`.
 *
 * - unlock-required: explain OS protection before Unlock GateRook asks main to access the encryption key;
 * - starting: the four steps of the kit from the progress lines;
 * - port in use: who holds the port, the command that finds it, Quit, and **Use another port** — a warning dialog
 *   with a port main checked is free; main writes `desktop.json` and restarts the core on it (`usePort`). When the
 *   taken port is the tunnel's public listener's, the same dialog moves only that listener (`usePort({ publicPort })`):
 *   no endpoint URL changes, so it has no warning tone; Restart core stays for a process stopped meanwhile;
 * - locked (master key missing or invalid): what `/api/master-key` says (the key file, how many secrets, the last
 *   backup); **Restore from backup** — an optional passphrase, then the system open dialog, and the core unlocks
 *   without a restart; **Create a new key** — the kit's danger confirmation, then the Secrets page, where the
 *   secrets that lost their value are entered again;
 * - stopped (the core ran and exited) and a failed start for any other reason: exit code, reason, uptime, the log
 *   file and its last lines, Open logs and Restart core.
 *
 * Toasts sit below the status screen, so what this screen's own actions answer while it covers the window is told
 * in the card (`notice`) or in the dialog; the toasts after a restore or a new key are for the app that comes back.
 */
@Component({
  selector: 'gw-core-starting-page',
  imports: [GwStatusScreen, GwDialog, GwDialogClose, GwIcon, GwReveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './core-starting.page.html',
})
export class CoreStartingPage {
  protected readonly core = inject(CoreStore);
  protected readonly desktop = inject(DesktopBridge);
  protected readonly time = inject(TimeService);
  private readonly masterKey = inject(MasterKeyApi);
  private readonly approvals = inject(ApprovalsStore);
  private readonly endpoints = inject(EndpointsStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly doc = inject(DOCUMENT);

  private readonly portDialog = viewChild.required<GwDialog>('portDialog');
  private readonly restoreDialog = viewChild.required<GwDialog>('restoreDialog');
  private readonly passphrase = viewChild<ElementRef<HTMLInputElement>>('passphrase');

  /** `GET /api/master-key` while locked: the only facts the locked core answers with. */
  private readonly key = signal<MasterKeyStatus | null>(null);

  protected readonly view = computed(() =>
    statusView(this.core.state(), {
      address: this.core.address(),
      key: this.key(),
      // Known only when this window saw the core answer (the shell loaded the queue then).
      pending: this.core.health() ? this.approvals.pendingCount() : null,
      desktop: this.desktop.available,
    }),
  );
  /** The log file Electron named, or the one the core reported while it ran, or the desktop app's default. */
  protected readonly logFile = computed(() => logFileOf(this.core.state().logFile ?? this.core.info()?.logFile ?? null, this.key()?.path ?? null));
  protected readonly keyPath = computed(() => this.key()?.path ?? DEFAULT_KEY_PATH);
  protected readonly lastBackupAt = computed(() => this.key()?.lastBackupAt ?? null);
  protected readonly quitReason = this.desktop.available ? null : QUIT_IN_BROWSER;

  protected readonly busy = signal<StatusScreenBusy>(null);
  protected readonly notice = signal<StatusScreenNotice | null>(null);

  // ── Use another port ──────────────────────────────────────────────────────────────────────────────────────────

  private readonly currentPort = computed(() => portOf(this.core.address()));
  /**
   * The port the tunnel's public listener couldn't take, when that is what failed: Use another port then moves that
   * listener (`usePort({ publicPort })`) and the local API keeps its port and every endpoint URL. Null: the local API.
   */
  protected readonly publicMove = computed(() => {
    const v = this.view();
    return v.kind === 'port-in-use' && v.publicListener ? (v.takenPort ?? this.core.publicPort()) : null;
  });
  /**
   * The one field of the dialog, as a plain signal: this page is in the start-up bundle (it shows before the core
   * answers), and a single checked field doesn't earn Signal Forms' ~90 kB there. The check is portError().
   */
  protected readonly portText = signal('');
  private readonly portTouched = signal(false);
  protected readonly portSubmitted = signal(false);
  protected readonly portBusy = signal(false);
  /** What main answered when it refused the port (taken, reserved): shown at the field until the value changes. */
  private readonly portRefused = signal<{ readonly port: string; readonly message: string } | null>(null);
  /** The port main checked is free just now. */
  private readonly freePort = signal<number | null>(null);

  /** The port typed, when it reads as one. */
  protected readonly typedPort = computed(() => {
    const t = this.portText().trim();
    return /^\d{1,5}$/.test(t) ? Number(t) : null;
  });
  /** "7475 is free on this computer." only while the field holds the port main checked. */
  protected readonly freeHint = computed(() => {
    const free = this.freePort();
    return free !== null && this.typedPort() === free ? free : null;
  });
  /** The endpoints this window knows (it loaded them while the core ran); none known — the URL pattern instead. */
  protected readonly urls = computed(() => {
    const port = this.typedPort();
    const slugs = (this.endpoints.list() ?? []).map((e) => e.slug);
    return port === null ? null : endpointUrls(port, slugs);
  });
  // ── Restore ───────────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly restoreBusy = signal(false);
  protected readonly restoreError = signal<{ readonly message: string; readonly passphrase: boolean } | null>(null);
  protected readonly restoreLead = computed(() => {
    const n = this.key()?.protects ?? null;
    const what = n === null ? 'the secrets stored here' : n === 1 ? 'the secret stored here' : `the ${n} secrets stored here`;
    return `Choose the copy you saved with Back up master key: the key that encrypted ${what}.`;
  });

  constructor() {
    // Locked: the master-key status (path, last backup, how many secrets) is all the core answers. Asked once per
    // locked spell (again after a restart that comes back locked).
    let asked = false;
    effect(() => {
      const locked = this.core.locked();
      untracked(() => {
        if (!locked) {
          asked = false;
          return;
        }
        if (asked) return;
        asked = true;
        this.masterKey.status().then(
          (k) => this.key.set(k),
          () => undefined, // the card words what it knows without it
        );
      });
    });

    // A new state from Electron or the core ends what this screen was doing about the last one.
    let kind = this.core.state().kind;
    effect(() => {
      const next = this.core.state().kind;
      untracked(() => {
        if (next === kind) return;
        kind = next;
        this.busy.set(null);
        this.notice.set(null);
        if (next !== 'failed') this.portDialog().close();
        if (next !== 'locked') this.restoreDialog().close();
      });
    });

    // The page leaves with the screen (the core runs again): no dialog of it may stay open in the stack.
    inject(DestroyRef).onDestroy(() => {
      this.portDialog().close();
      this.restoreDialog().close();
    });
  }

  // ── Use another port ──────────────────────────────────────────────────────────────────────────────────────────

  protected openPortDialog(): void {
    const taken = this.publicMove();
    const candidate = taken !== null ? firstCandidate(taken, this.currentPort()) : firstCandidate(this.currentPort(), this.core.publicPort());
    this.portText.set(String(candidate));
    this.portTouched.set(false);
    this.portSubmitted.set(false);
    this.portRefused.set(null);
    this.freePort.set(null);
    // The opener is the focused "Use another port": focus comes back to it on Cancel.
    this.portDialog().open();
    // Main probes ports from the candidate up; its answer replaces the guess while the person hasn't typed.
    this.desktop.findFreePort(candidate).then(
      (free) => {
        if (free === null) return;
        if (this.portText() === String(candidate)) this.portText.set(String(free));
        this.freePort.set(free);
      },
      () => undefined,
    );
  }

  protected onPortInput(e: Event): void {
    this.portText.set((e.target as HTMLInputElement).value);
  }

  protected onPortBlur(): void {
    this.portTouched.set(true);
  }

  /** The field's message: main's refusal of this very value, else portError() once the field was left or submitted. */
  protected portErrorText(): string | null {
    const refused = this.portRefused();
    if (refused && refused.port === this.portText()) return refused.message;
    if (!this.portSubmitted() && !this.portTouched()) return null;
    return this.checkPort();
  }

  /** Why the typed port can't be used for the listener this dialog moves, or null. */
  private checkPort(): string | null {
    const taken = this.publicMove();
    return taken !== null
      ? publicPortError(this.portText(), taken, this.currentPort())
      : portError(this.portText(), this.currentPort(), this.core.publicPort());
  }

  /**
   * Start on this port: main writes `desktop.json` and restarts the core there (the local API, or the tunnel's
   * public listener); the screen shows it starting.
   */
  protected async startOnPort(e: Event): Promise<void> {
    e.preventDefault();
    this.portSubmitted.set(true);
    if (this.portBusy() || !this.desktop.available) return;
    if (this.checkPort()) {
      this.focus('core-port-new');
      return;
    }
    const text = this.portText().trim();
    const publicListener = this.publicMove() !== null;
    this.portBusy.set(true);
    this.portRefused.set(null);
    try {
      await this.desktop.usePort(publicListener ? { publicPort: Number(text) } : Number(text));
      this.portDialog().close('start');
      this.announcer.announce(publicListener ? `Starting the core with the internal listener on port ${text}` : `Starting the core on port ${text}`);
    } catch (err) {
      this.portRefused.set({ port: this.portText(), message: usePortError(err) });
      this.focus('core-port-new');
    } finally {
      this.portBusy.set(false);
    }
  }

  protected onPortClosed(): void {
    this.portBusy.set(false);
  }

  // ── Restore from backup ───────────────────────────────────────────────────────────────────────────────────────

  protected openRestore(): void {
    this.restoreError.set(null);
    this.restoreBusy.set(false);
    // The opener is the focused "Restore from backup": focus comes back to it on Cancel.
    this.restoreDialog().open();
  }

  /** Choose the backup file: the desktop's open dialog, then the core checks the copy and unlocks. */
  protected async restore(e: Event): Promise<void> {
    e.preventDefault();
    if (this.restoreBusy() || !this.desktop.available) return;
    this.restoreError.set(null);
    let token: string | null;
    try {
      token = await this.desktop.openFile('master-key-restore');
    } catch (err) {
      // Main couldn't hand the file to the core (not a file, not readable, the core away): say so in the dialog.
      this.restoreError.set({ message: desktopError(err, 'The app couldn’t open that file. Choose the backup file again.'), passphrase: false });
      return;
    }
    if (!token) return; // the person cancelled the file dialog: nothing happened
    this.restoreBusy.set(true);
    const field = this.passphrase()?.nativeElement;
    const passphrase = field?.value ? field.value : null;
    try {
      const result = await this.masterKey.restore(token, passphrase);
      if (field) field.value = '';
      this.restoreDialog().close('restored');
      this.toast(restoredToast(result.secretsReadable));
      await this.core.refresh();
    } catch (err) {
      const problem = restoreProblem(err);
      if (problem.unlocked) {
        // Unlocked meanwhile (409 "The master key works"): there is nothing left to restore.
        this.restoreDialog().close('restored');
        await this.core.refresh();
        return;
      }
      this.restoreError.set({ message: problem.message, passphrase: problem.passphrase });
      if (problem.passphrase) this.focus('core-restore-pass');
    } finally {
      this.restoreBusy.set(false);
    }
  }

  /** Whatever was typed as a passphrase leaves the page with the dialog. */
  protected onRestoreClosed(): void {
    const field = this.passphrase()?.nativeElement;
    if (field) {
      field.value = '';
      field.type = 'password';
    }
    this.restoreError.set(null);
  }

  // ── Create a new key ──────────────────────────────────────────────────────────────────────────────────────────

  /** Confirmed in the kit's danger dialog: the core destroys the old local secrets; Secrets is where they come back. */
  protected async createKey(): Promise<void> {
    if (this.busy()) return;
    this.busy.set('create');
    this.notice.set(null);
    try {
      const result = await this.masterKey.create();
      this.toast(createdToast(result.needValue));
      await this.core.refresh();
      if (result.needValue.length) void this.router.navigateByUrl('/secrets');
    } catch (err) {
      // 409: the core isn't locked any more (a key was restored or created meanwhile) — nothing to create; the
      // window goes back to the app once health says so, as after a restore.
      if (isApiError(err) && err.status === 409) await this.core.refresh();
      else this.notice.set(createProblem(err));
    } finally {
      this.busy.set(null);
    }
  }

  // ── Stopped / failed ──────────────────────────────────────────────────────────────────────────────────────────

  protected async restart(): Promise<void> {
    if (this.busy()) return;
    this.busy.set('restart');
    this.notice.set(null);
    try {
      await this.desktop.restartCore();
    } catch (err) {
      this.notice.set({ title: 'The core didn’t restart', body: desktopError(err, 'The app couldn’t start the core. Try again.') });
    } finally {
      this.busy.set(null);
    }
  }

  protected openLogs(): void {
    void this.desktop.revealInFolder('logs');
  }

  /** Only this action asks main to touch the OS key store; the app never asks for a system password itself. */
  protected async unlock(): Promise<void> {
    const state = this.core.state();
    if (state.kind !== 'unlock-required' || state.unlocking || this.busy() || !this.desktop.available) return;
    this.busy.set('unlock');
    this.notice.set(null);
    try {
      await this.desktop.unlock();
    } catch (err) {
      this.notice.set({ title: 'GateRook is still locked', body: desktopError(err, 'GateRook could not prepare its encryption key. Choose Unlock GateRook to try again.') });
    } finally {
      if (this.busy() === 'unlock') this.busy.set(null);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

  private toast(t: ToastText): void {
    this.toasts.show({ tone: t.tone, title: t.title, body: t.body });
  }

  /** Focus a field of an open dialog once the error that names it is drawn. */
  private focus(id: string): void {
    afterNextRender(() => this.doc.getElementById(id)?.focus(), { injector: this.injector });
  }
}
