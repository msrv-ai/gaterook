import { ChangeDetectionStrategy, Component, Injector, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { FormField, form, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { DesktopBridge, UpdateState, desktopErrorText } from '../../core/desktop/desktop-bridge';
import { ApprovalsStore } from '../../core/stores/approvals.store';
import { UpdatesStore } from '../../core/stores/updates.store';
import { TimeService } from '../../core/time/time.service';
import { Announcer } from '../../kit/announcer';
import { GwDialog, GwDialogClose } from '../../kit/dialog';
import { GwIcon } from '../../kit/icons';
import { GwSingleKeysToggle } from '../../kit/shortcuts';
import { ToastService } from '../../kit/toast.service';
import { SettingsData } from './settings.data';
import {
  boolOf,
  endpointUrl,
  endpointUrlsAt,
  folderOf,
  listText,
  onOff,
  parsePort,
  plural,
  portClients,
  runtimeRows,
  savedToast,
  updateToast,
  updateView,
} from './settings-view';

const NO_ANSWER = 'The core didn’t answer. Try again.';

/** The switches of General and the key each one saves (`PUT /api/settings/general`). */
type GeneralKey = 'openAtLogin' | 'startHidden' | 'autoUpdate' | 'singleKeyShortcuts';

/**
 * Settings › General (design/pages/settings.html `#settings-general`): how the app starts, stays up to date, answers
 * the keyboard and where its core listens. Every switch applies at once (`PUT /api/settings/general`, a toast names
 * the setting and its value; a refusal puts the switch back). Startup is applied by Electron too
 * (`gwDesktop.applyGeneral`), updates come from Electron's updater (`checkForUpdates`, `onUpdateState`), single-key
 * shortcuts switch the app's "/" hook at once (`GwSingleKeysToggle`).
 *
 * Core: the port is read only here; "Change port" asks first (`gw-dialog--warning`, static, the field has focus):
 * which URLs change, who connects again, what happens to calls in flight and waiting requests. The core refuses a
 * port that is taken or the tunnel's; Electron restarts it on the new one (`core.restart-required`). What runs the
 * core and its plugins follows ARCHITECTURE.md §8.6.1 (native core + plugin runtime), from `/api/core/info`.
 */
@Component({
  selector: 'section[gwGeneralSettings]',
  imports: [RouterLink, FormField, GwIcon, GwDialog, GwDialogClose, GwSingleKeysToggle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-settings-layout__section', id: 'settings-general', 'aria-labelledby': 'st-general-t' },
  templateUrl: './general.section.html',
})
export class GwGeneralSettings {
  protected readonly data = inject(SettingsData);
  protected readonly desktop = inject(DesktopBridge);
  protected readonly time = inject(TimeService);
  private readonly approvals = inject(ApprovalsStore);
  private readonly toasts = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);

  private readonly portDialog = viewChild.required<GwDialog>('portDialog');
  private readonly resetDialog = viewChild.required<GwDialog>('resetDialog');

  private readonly values = computed(() => this.data.section('general')?.values);
  protected readonly openAtLogin = computed(() => boolOf(this.values(), 'openAtLogin', true));
  protected readonly startHidden = computed(() => boolOf(this.values(), 'startHidden', true));
  protected readonly autoUpdate = computed(() => boolOf(this.values(), 'autoUpdate', true));
  protected readonly busy = signal<GeneralKey | null>(null);

  // ── Updates ───────────────────────────────────────────────────────────────────────────────────────────────────

  private readonly updates = inject(UpdatesStore);
  private readonly checking = signal(false);
  protected readonly version = computed(() => this.data.info().value?.version ?? this.data.health().value?.version ?? null);
  protected readonly update = computed(() => updateView(this.updates.state(), this.version(), this.desktop.available));

  // ── Core ──────────────────────────────────────────────────────────────────────────────────────────────────────

  private readonly localApi = computed(() => this.data.section('local-api')?.values);
  protected readonly port = computed(() => {
    const p = this.localApi()?.['port'];
    return typeof p === 'number' ? p : (this.data.info().value?.port ?? this.data.health().value?.ports.local ?? null);
  });
  private readonly publicPort = computed(() => {
    const p = this.localApi()?.['publicPort'];
    return typeof p === 'number' ? p : (this.data.health().value?.ports.public ?? null);
  });
  protected readonly slugs = computed(() => (this.data.endpoints().value ?? []).map((e) => e.slug));
  protected readonly exampleUrl = computed(() => {
    const port = this.port();
    return port ? endpointUrl(port, this.slugs()[0] ?? 'dev') : null;
  });
  protected readonly runtime = computed(() => runtimeRows(this.data.info().value));
  protected readonly dataFolder = computed(() => folderOf(this.data.health().value?.db.path));
  protected readonly logFile = computed(() => this.data.info().value?.logFile ?? null);

  // ── Change port ───────────────────────────────────────────────────────────────────────────────────────────────

  protected readonly portModel = signal({ port: '' });
  protected readonly portForm = form(
    this.portModel,
    (p) => {
      validate(p.port, ({ value }) => {
        const error = parsePort(value(), this.port(), this.publicPort()).error;
        return error ? { kind: 'port', message: error } : null;
      });
    },
    { injector: this.injector },
  );
  protected readonly portSubmitted = signal(false);
  protected readonly portBusy = signal(false);
  protected readonly portError = signal<string | null>(null);
  protected readonly newPort = computed(() => parsePort(this.portModel().port, this.port(), this.publicPort()).value);
  protected readonly newUrls = computed(() => endpointUrlsAt(this.newPort(), this.slugs()));
  protected readonly clients = computed(() => portClients(this.data.clients()));
  protected readonly againText = computed(() => listText(this.clients().again));
  protected readonly keepText = computed(() => listText(this.clients().keep));
  protected readonly pendingCount = this.approvals.pendingCount;
  protected readonly plural = plural;

  protected readonly resetConfirmation = signal('');
  protected readonly resetBusy = signal(false);
  protected readonly resetError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const state = this.updates.state();
      if (state) untracked(() => this.onUpdate(state));
    });
  }

  protected portFieldError(): string | null {
    const f = this.portForm.port();
    return this.portSubmitted() || f.touched() ? (f.errors()[0]?.message ?? null) : null;
  }

  /** A switch that applies at once; a refusal puts it back (the core keeps the old value). */
  protected async setGeneral(key: GeneralKey, e: Event, label: string): Promise<void> {
    const input = e.target as HTMLInputElement;
    const on = input.checked;
    if (this.busy()) {
      // One change at a time; this one never happened.
      if (key !== 'singleKeyShortcuts') input.checked = !on;
      return;
    }
    this.busy.set(key);
    try {
      await this.data.save('general', { [key]: on });
      if (key === 'openAtLogin' || key === 'startHidden') await this.desktop.applyGeneral({ [key]: on });
      this.toasts.show(savedToast(label, onOff(on)));
    } catch (err) {
      // Single-key shortcuts already switched in this window (kit: the switch is the app's own); the core's copy
      // stays as it was, which the toast says.
      if (key !== 'singleKeyShortcuts') input.checked = !on;
      this.toasts.show({ tone: 'danger', title: `${label} wasn’t saved`, body: this.errorText(err) });
    } finally {
      this.busy.set(null);
    }
  }

  /** Check now: Electron's updater answers through `onUpdateState`; the toast follows that answer. */
  protected async checkNow(): Promise<void> {
    if (!this.desktop.available || this.checking() || this.update().busy || this.updates.busy()) return;
    this.checking.set(true);
    this.announcer.announce('Checking for updates');
    try {
      await this.desktop.checkForUpdates();
      // A downloaded update can make main keep its current state instead of sending another event.
      const state = this.updates.state();
      if (state) this.onUpdate(state);
    } catch (err) {
      this.toasts.show({ tone: 'danger', title: 'Couldn’t check for updates', body: err instanceof Error ? err.message : NO_ANSWER });
    } finally {
      this.checking.set(false);
    }
  }

  private onUpdate(state: UpdateState): void {
    if (state.state === 'checking' || !this.checking()) return;
    this.checking.set(false);
    const toast = updateToast(state, this.version());
    if (toast) this.toasts.show(toast);
  }

  protected openLogs(): void {
    if (this.desktop.available) void this.desktop.revealInFolder('logs');
  }

  protected openReset(e: Event): void {
    if (!this.desktop.available || this.resetBusy()) return;
    this.resetConfirmation.set('');
    this.resetError.set(null);
    this.resetDialog().open(e.currentTarget as HTMLElement);
  }

  protected cancelReset(): void {
    if (!this.resetBusy()) this.resetDialog().close();
  }

  protected guardResetCancel(e: Event): void {
    if (this.resetBusy()) e.preventDefault();
  }

  protected async resetApplication(): Promise<void> {
    if (!this.desktop.available || this.resetBusy() || this.resetConfirmation() !== 'RESET') return;
    this.resetBusy.set(true);
    this.resetError.set(null);
    try {
      await this.desktop.resetApplication(this.resetConfirmation());
      // The desktop closes after success; keep the confirmation locked until the window goes away.
    } catch (err) {
      this.resetError.set(desktopErrorText(err, 'GateRook could not finish resetting. Try again.'));
      this.resetBusy.set(false);
    }
  }

  // ── Change port ───────────────────────────────────────────────────────────────────────────────────────────────

  protected openPort(e: Event): void {
    this.portModel.set({ port: '' });
    this.portForm().reset();
    this.portSubmitted.set(false);
    this.portError.set(null);
    this.portBusy.set(false);
    this.portDialog().open(e.currentTarget as HTMLElement);
  }

  protected async changePort(e: Event): Promise<void> {
    e.preventDefault();
    this.portSubmitted.set(true);
    if (this.portBusy() || !this.desktop.available) return;
    const port = this.newPort();
    if (port === null) {
      (this.portDialog().element.querySelector('#st-port-new') as HTMLInputElement | null)?.focus();
      return;
    }
    this.portBusy.set(true);
    this.portError.set(null);
    try {
      // The core checks the port (free, not the tunnel's) and asks Electron to restart it there
      // (`core.restart-required`): the window shows the core starting, then comes back on the new port.
      await this.data.save('local-api', { port });
      this.portDialog().close('changed');
      const again = this.clients().again;
      this.toasts.show({
        tone: 'success',
        title: `Core restarting on port ${port}`,
        body: `Endpoint URLs now use port ${port}.${again.length ? ` Connect ${listText(again)} again in AI apps.` : ''}`,
      });
    } catch (err) {
      this.portError.set(this.errorText(err));
    } finally {
      this.portBusy.set(false);
    }
  }

  private errorText(e: unknown): string {
    if (isApiError(e) && e.status === 409) return 'These settings changed meanwhile, in another window. Check them, then try again.';
    return isApiError(e) ? e.detail : NO_ANSWER;
  }
}
