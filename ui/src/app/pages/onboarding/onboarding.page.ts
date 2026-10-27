import {
  ChangeDetectionStrategy,
  Component,
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
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { DesktopBridge } from '../../core/desktop/desktop-bridge';
import { CoreStore } from '../../core/stores/core.store';
import { TimeService } from '../../core/time/time.service';
import { GwCodeBlock } from '../../code/code-block';
import { Announcer } from '../../kit/announcer';
import { GwCopy } from '../../kit/copy';
import { GwToggle } from '../../kit/disclosure';
import { MetaPart } from '../../kit/elements/getting-started/getting-started';
import { GwSetupMap } from '../../kit/elements/setup-map/setup-map';
import { GwIcon } from '../../kit/icons';
import { GwReveal } from '../../kit/reveal';
import { ToastService } from '../../kit/toast.service';
import { OnboardingData } from './onboarding.data';
import {
  MAP_FLOW,
  STEP_IDS,
  allDone,
  backedUpToast,
  clientsDescription,
  doneLine,
  formatText,
  keyFileName,
  landingStep,
  lastBackupAt,
  permissionsText,
  setupNodes,
  stepAnnouncement,
  stepperSteps,
  stepsDone,
} from './onboarding-view';

/** The first render waits this long before it shows placeholders (kit: a skeleton only past ~300 ms). */
export const SKELETON_DELAY_MS = 300;

const NO_ANSWER = 'The core didn’t answer. Try again.';

/** What Back up key is doing: nothing, waiting for the desktop's save dialog, or waiting for the core to write. */
type BackupPhase = 'idle' | 'choosing' | 'writing';

/**
 * Welcome (design/pages/onboarding.html; ARCHITECTURE.md §6 onboarding, §7.1 bootstrap (a), §8.4): the first run,
 * without the sidebar. The welcome (logo, value, three promises), then setup in four steps — a stepper that shows
 * progress (not navigation), the current step's card and, beside it, the setup map in the order of the steps — and
 * where the data lives.
 *
 * - Step 1, Protect master key: the core created the key at its first start, so the step is only its backup. An
 *   optional passphrase, then the desktop's save dialog picks where the copy goes; the core gets the dialog's
 *   one-time token, never a path (`POST /api/master-key/backup`). Busy in place (spinner, aria-busy, the status line
 *   saying what runs); then the next step opens and a toast that stays names the copy. "I'll do it later" leaves the
 *   step not done ("Skipped · no backup yet") for this window and opens the next step.
 * - Steps 2–4 hand over to Add connection, Create endpoint and Clients; coming back, Welcome opens on the first step
 *   that is not done, and a done step's card says what was set up.
 * - Every switch keeps the stepper's ARIA in sync, moves focus to the new step's title and announces the step.
 * - Skip setup (`POST /api/onboarding/skip`) opens Overview, and the next start does too.
 *
 * Data is the core's (OnboardingData): nothing for 300 ms, then a skeleton; an error with Try again and Details; a
 * failed refresh keeps the last answer with "This page may be out of date."
 */
@Component({
  selector: 'gw-onboarding-page',
  imports: [NgTemplateOutlet, RouterLink, GwIcon, GwCopy, GwReveal, GwToggle, GwCodeBlock, GwSetupMap],
  providers: [OnboardingData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page gw-page--narrow' },
  templateUrl: './onboarding.page.html',
})
export class OnboardingPage {
  protected readonly data = inject(OnboardingData);
  protected readonly time = inject(TimeService);
  protected readonly desktop = inject(DesktopBridge);
  private readonly core = inject(CoreStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;

  private readonly passphrase = viewChild<ElementRef<HTMLInputElement>>('passphrase');
  private readonly reveal = viewChild(GwReveal);

  protected readonly mapFlow = MAP_FLOW;

  /** Placeholders only when the first answer is slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.facts() && !this.data.error());

  protected readonly facts = this.data.facts;
  protected readonly key = this.data.key;
  protected readonly done = computed(() => {
    const f = this.facts();
    return f ? stepsDone(f) : null;
  });
  /** Every step is done: nothing is left to skip, and the last card leads to Overview. */
  protected readonly complete = computed(() => {
    const d = this.done();
    return d ? allDone(d) : false;
  });

  /** The step a person moved to with Back or Continue; null follows the first step left to do. */
  private readonly picked = signal<number | null>(null);
  protected readonly viewing = computed(() => {
    const picked = this.picked();
    if (picked !== null) return picked;
    const d = this.done();
    return d ? landingStep(d, this.data.later()) : 0;
  });

  protected readonly steps = computed(() => {
    const f = this.facts();
    // "Backed up 2 min ago" moves on with the clock.
    this.time.minute();
    return f ? stepperSteps(f, this.viewing(), this.data.later(), (at) => this.time.relative(at)) : [];
  });
  protected readonly nodes = computed(() => {
    const f = this.facts();
    return f ? setupNodes(f) : [];
  });
  /** What each done card says was set up (empty for a step not done). */
  protected readonly doneLines = computed<readonly (readonly MetaPart[])[]>(() => {
    const f = this.facts();
    const d = this.done();
    return STEP_IDS.map((id) => (f && d?.[id] ? doneLine(f, id) : []));
  });

  // ── Step 1 ────────────────────────────────────────────────────────────────────────────────────────────────────
  protected readonly permissions = computed(() => {
    const k = this.key();
    return k ? permissionsText(k) : null;
  });
  protected readonly format = computed(() => {
    const k = this.key();
    return k ? formatText(k) : 'Google Tink keyset';
  });
  protected readonly keyFile = computed(() => keyFileName(this.key()?.path));
  /** "just now", "12 days ago": when the current key was last backed up. */
  protected readonly backedUpAt = computed(() => {
    const f = this.facts();
    this.time.minute();
    const at = f ? lastBackupAt(f) : null;
    return at ? this.time.relative(at) : null;
  });
  protected readonly backupPhase = signal<BackupPhase>('idle');
  protected readonly backupBusy = computed(() => this.backupPhase() !== 'idle');
  protected readonly backupError = signal<string | null>(null);
  /** What the Back up button points its description at: what runs, why it can't, or where the copy goes. */
  protected readonly backupDescribedBy = computed(() =>
    this.backupBusy() ? 'ob-backup-status' : this.desktop.available ? 'ob-backup-hint' : 'ob-backup-why',
  );

  // ── Steps 2–4 ─────────────────────────────────────────────────────────────────────────────────────────────────
  protected readonly endpointExample = computed(() => `http://${this.core.address()}/mcp/dev`);
  protected readonly clientsDescription = computed(() => {
    const f = this.facts();
    return f ? clientsDescription(f) : '';
  });

  constructor() {
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    // The core came back (it was away, or another instance answers): what the page shows may be stale.
    let wasRunning = this.core.running();
    let instance = this.core.instanceChanges();
    effect(() => {
      const running = this.core.running();
      const changes = this.core.instanceChanges();
      untracked(() => {
        if (running && (!wasRunning || changes !== instance)) void this.data.refresh();
        wasRunning = running;
        instance = changes;
      });
    });
  }

  protected isDone(index: number): boolean {
    return this.done()?.[STEP_IDS[index]] ?? false;
  }

  // ── Moving between steps ──────────────────────────────────────────────────────────────────────────────────────

  /** Back and Continue: the card of that step, its title focused, the step announced (kit go()). */
  protected go(index: number, before?: string): void {
    this.picked.set(index);
    this.show(index, before);
  }

  /** "I'll do it later": the step stays not done, and the first step left to do opens. */
  protected later(): void {
    this.data.putOff('master-key');
    this.picked.set(null);
    this.show(this.viewing(), 'Master key backup skipped.');
  }

  private show(index: number, before?: string): void {
    this.announcer.announce(stepAnnouncement(index, before));
    afterNextRender(() => this.host.querySelector<HTMLElement>(`#ob-panel-${index + 1}-title`)?.focus(), { injector: this.injector });
  }

  // ── Back up key ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The desktop's save dialog picks where the copy goes (cancelling it changes nothing); the core writes it with the
   * passphrase, which is read from the field only now and cleared once the copy exists. Then the first step left to do
   * opens and a toast that stays names the copy. A refusal stays in the card with the core's words; the passphrase
   * stays for another try.
   */
  protected async backUp(): Promise<void> {
    if (this.backupBusy() || !this.desktop.available) return;
    this.backupError.set(null);
    this.backupPhase.set('choosing');
    let token: string | null;
    try {
      token = await this.desktop.saveFile('master-key-backup');
    } catch {
      token = null;
      this.backupError.set("The save dialog didn't open. Try again.");
    }
    if (!token) {
      // Cancelled (or the dialog failed, which says so): nothing was written.
      this.backupPhase.set('idle');
      return;
    }
    this.backupPhase.set('writing');
    const field = this.passphrase()?.nativeElement;
    try {
      const result = await this.data.backup(token, field?.value ? field.value : null);
      // The passphrase leaves the page with the copy: the field is empty and hidden again.
      if (field) {
        field.value = '';
        field.type = 'password';
      }
      this.reveal()?.shown.set(false);
      this.backupPhase.set('idle');
      this.picked.set(null);
      this.show(this.viewing(), 'Master key backed up.');
      const t = backedUpToast(result);
      this.toasts.show({ tone: t.tone, title: t.title, body: t.body, timeout: 0 });
    } catch (e) {
      this.backupPhase.set('idle');
      this.backupError.set(this.errorText(e));
    }
  }

  // ── Skip setup ────────────────────────────────────────────────────────────────────────────────────────────────

  /** Overview opens either way (the link navigates); if the core didn't take it, Welcome comes back next start. */
  protected skip(): void {
    this.data.skip().catch(() =>
      this.toasts.show({ tone: 'warning', title: "Couldn't skip setup", body: 'Welcome opens again on the next start. You can set up from Overview.' }),
    );
  }

  // ── Errors ────────────────────────────────────────────────────────────────────────────────────────────────────

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : NO_ANSWER;
  }

  protected errorDetails(e: unknown, route: string): string {
    if (isApiError(e)) return `${route} → ${e.status || 'no answer'}\n${e.title}: ${e.detail}`;
    return e instanceof Error ? `${e.name}: ${e.message}` : String(e);
  }
}
