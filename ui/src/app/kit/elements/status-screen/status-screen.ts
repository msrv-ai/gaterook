import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, numberAttribute, output } from '@angular/core';
import { GwCodeBlock } from '../../../code/code-block';
import { Iso } from '../../../core/api/types';
import { duration } from '../../../core/time/time-format';
import { TimeService } from '../../../core/time/time.service';
import { GwDialog, GwDialogClose, GwDialogOpen } from '../../dialog';
import { GwToggle } from '../../disclosure';
import { GwIcon } from '../../icons';
import { TextPart } from '../shared/literals';

/** One start-up step (Electron's progress lines, ARCHITECTURE.md §7.1). */
export interface StatusStep {
  readonly id: 'runtime' | 'master-key' | 'db-migrations' | 'local-api' | string;
  readonly state: 'complete' | 'current' | 'error' | 'upcoming';
  /** The line under the label: "Up to date · 2 applied in 0.4 s". Mono parts are marked by the caller with `mono`. */
  readonly description?: string | null;
  /** A path or address shown in mono after `description`. */
  readonly mono?: string | null;
}

/**
 * What the window shows while the core isn't available (design/elements/status-screen.html):
 * - `starting`: indeterminate progress and the steps done so far;
 * - `port-in-use`: the local API or the tunnel's public listener couldn't bind; the command that finds the other
 *   process, and Use another port;
 * - `locked`: the master key is missing (or doesn't open the secrets): restore the backup, or a new key, which
 *   makes the local secrets unreadable (danger notice + confirmation);
 * - `stopped`: the core ran and exited: exit code, reason, uptime, log file and its last lines;
 * - `start-failed`: the core exited before it was ready for another reason than the port or the key (a second core
 *   holds the database, a migration failed…): the stopped card's facts with the steps, worded by the caller;
 * - `unreachable`: a browser during development, nothing answers on the address (no Electron to say why).
 */
export type StatusScreenView =
  | { readonly kind: 'starting'; readonly steps: readonly StatusStep[] }
  | {
      readonly kind: 'port-in-use';
      readonly steps: readonly StatusStep[];
      readonly portOwner: { readonly pid: number; readonly user: string } | null;
      /** The port that is taken, when it isn't the local API's (the failure line names it). */
      readonly takenPort?: number | null;
      /**
       * The taken port is the tunnel's public listener (the core binds both at start, §7.3): Use another port moves
       * that listener (the endpoint URLs stay), and Restart core is there for a process that was stopped meanwhile.
       */
      readonly publicListener?: boolean;
    }
  | {
      readonly kind: 'locked';
      readonly reason: 'MASTER_KEY_MISSING' | 'MASTER_KEY_INVALID';
      readonly steps: readonly StatusStep[];
      readonly keyPath: string;
      /** Secrets encrypted here (unreadable with a new key), and secret references that keep working. */
      readonly secrets: number | null;
      readonly references: number | null;
      readonly lastBackupAt: Iso | null;
      /**
       * Whose the secrets encrypted here are, in words with names in mono ("6 connections, the github-mcp server token
       * and the Vault sign-in"); null when the core doesn't say (before step API).
       */
      readonly breakdown?: readonly TextPart[] | null;
      /** Where the references point ("Vault, GCP and AWS"); null when the core doesn't say: the kit's three are named. */
      readonly referenceTo?: string | null;
    }
  | {
      readonly kind: 'stopped';
      readonly at: Iso | null;
      readonly exitCode: number | null;
      readonly reason: string | null;
      readonly uptimeMs: number | null;
      readonly logLines: string | null;
      /** Approval requests that were waiting when it exited (kit: "The 3 pending approval requests are kept"). */
      readonly pending?: number | null;
    }
  | {
      readonly kind: 'start-failed';
      /** A Lucide icon of the danger tile. */
      readonly icon: string;
      readonly title: string;
      /** What happened and what to do, in one or two sentences. */
      readonly lead: string;
      readonly steps: readonly StatusStep[];
      readonly exitCode: number | null;
      /** The core's reason code and detail (mono). */
      readonly reason: string;
      readonly logLines: string | null;
    }
  | { readonly kind: 'unreachable'; readonly detail: string | null };

/** A message of this screen's own actions (Restart core, Create a new key) inside the card: toasts sit below it. */
export interface StatusScreenNotice {
  readonly title: string;
  readonly body: string;
}

/** A footer or notice action that runs now: its button is busy and doesn't run twice. */
export type StatusScreenBusy = 'restart' | 'create' | null;

/** Step names of the kit's stepper (decision 2: the core is a native binary, "Native core" not "Java runtime"). */
const STEP_LABELS: Record<string, string> = {
  runtime: 'Native core',
  'master-key': 'Master key',
  'db-migrations': 'Database migrations',
  'local-api': 'Local API',
};

const STATE_SR: Record<StatusStep['state'], string> = {
  complete: ', completed',
  current: ', in progress',
  error: ', failed',
  upcoming: ', not started',
};

let nextId = 0;

/**
 * `<gw-status-screen>`: no shell, no sidebar — the screen IS the window, says what happens and why, and offers the
 * one or two actions that fix it. `contained` puts it in a preview frame (the gallery); in the app it covers the
 * window. Facts come from Electron's core state and `/api/health`; the element only words them as the kit does.
 */
@Component({
  selector: 'gw-status-screen',
  imports: [GwIcon, GwCodeBlock, GwToggle, GwDialog, GwDialogOpen, GwDialogClose, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'gw-status-screen',
    '[class.gw-status-screen--contained]': 'contained()',
    '[attr.data-state]': 'dataState()',
    // Covering the window it is the page's main landmark, named by its title (kit: `<main class="gw-status-screen"
    // aria-labelledby>`); a contained preview is just a figure in a documentation section.
    '[attr.role]': 'contained() ? null : "main"',
    '[attr.aria-labelledby]': 'contained() ? null : uid + "-title"',
  },
  templateUrl: './status-screen.html',
})
export class GwStatusScreen {
  private readonly time = inject(TimeService);

  readonly view = input.required<StatusScreenView>();
  /** `127.0.0.1:7465`. */
  readonly address = input('127.0.0.1:7465');
  readonly version = input<string | null>(null);
  readonly logFile = input('~/.gaterook/logs/core.log');
  /** In a preview frame instead of covering the window. */
  readonly contained = input(false, { transform: booleanAttribute });
  /** The title's heading level: 1 in the app (it is the page), 2 or 3 inside a documentation section. */
  readonly level = input(1, { transform: numberAttribute });
  /**
   * The meta line of the page (pages/core-starting.html): version, core address and log file under every card. The
   * element's demo names either the address or the log, by state.
   */
  readonly fullMeta = input(false, { transform: booleanAttribute });
  /** What an action of this screen answered (a failed Restart core, a new key the core refused). */
  readonly notice = input<StatusScreenNotice | null>(null);
  readonly busy = input<StatusScreenBusy>(null);
  /** Why Quit can't work here (a browser tab can't quit the app): Quit is aria-disabled with this reason. */
  readonly quitReason = input<string | null>(null);

  readonly quit = output<void>();
  readonly usePort = output<void>();
  readonly restore = output<void>();
  readonly createKey = output<void>();
  readonly restart = output<void>();
  readonly openLogs = output<void>();
  readonly retry = output<void>();

  protected readonly uid = `ss-${++nextId}`;
  protected readonly stateSr = STATE_SR;
  /** The port the card is about: the taken one when the failure named another than the local API's. */
  protected readonly port = computed(() => {
    const v = this.view();
    if (v.kind === 'port-in-use' && v.takenPort) return String(v.takenPort);
    return this.address().split(':').at(-1) ?? '';
  });
  protected readonly dataState = computed(() => {
    const k = this.view().kind;
    return k === 'starting' ? 'starting' : k === 'stopped' ? 'stopped' : 'failed';
  });
  /** The kit's meta line: under a failure the log, otherwise the address; the page shows both. */
  protected readonly metaShowsAddress = computed(() => this.fullMeta() || this.dataState() !== 'failed' || this.view().kind === 'unreachable');
  protected readonly metaShowsLog = computed(() => this.fullMeta() || (this.dataState() === 'failed' && this.view().kind !== 'unreachable'));
  protected readonly versionText = computed(() => (this.version() ? `GateRook ${this.version()}` : 'GateRook'));

  protected readonly steps = computed<readonly StatusStep[]>(() => {
    const v = this.view();
    return v.kind === 'starting' || v.kind === 'port-in-use' || v.kind === 'locked' || v.kind === 'start-failed' ? v.steps : [];
  });

  /** "Starting the gateway core: local API, step 4 of 4." */
  protected readonly startingStatus = computed(() => {
    const steps = this.steps();
    const i = steps.findIndex((s) => s.state === 'current');
    if (i < 0) return 'Starting the gateway core.';
    return `Starting the gateway core: ${this.label(steps[i]).toLowerCase()}, step ${i + 1} of ${steps.length}.`;
  });

  protected label(step: StatusStep): string {
    return STEP_LABELS[step.id] ?? step.id;
  }

  protected asStopped() {
    const v = this.view();
    return v.kind === 'stopped' ? v : null;
  }

  protected asLocked() {
    const v = this.view();
    return v.kind === 'locked' ? v : null;
  }

  protected asPort() {
    const v = this.view();
    return v.kind === 'port-in-use' ? v : null;
  }

  protected asStartFailed() {
    const v = this.view();
    return v.kind === 'start-failed' ? v : null;
  }

  protected asUnreachable() {
    const v = this.view();
    return v.kind === 'unreachable' ? v : null;
  }

  protected relative(at: Iso): string {
    return this.time.relative(at);
  }

  protected absolute(at: Iso): string {
    return this.time.absolute(at);
  }

  protected clock(at: Iso): string {
    return this.time.clock(at);
  }

  protected uptime(ms: number): string {
    return duration(ms);
  }

  protected count(n: number, one: string, many: string): string {
    return `${n} ${n === 1 ? one : many}`;
  }

  /** "last 4 lines" (kit: "core.log · last 4 lines"). */
  protected linesLabel(text: string): string {
    const n = text.split('\n').filter((l) => l.length > 0).length;
    return n === 1 ? 'last line' : `last ${n} lines`;
  }
}
