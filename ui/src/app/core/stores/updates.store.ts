import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { DesktopBridge, UpdateState, desktopErrorText } from '../desktop/desktop-bridge';

/** One desktop update subscription for the window, retained while its pages change. */
@Injectable({ providedIn: 'root' })
export class UpdatesStore {
  private readonly desktop = inject(DesktopBridge);
  private readonly value = signal<UpdateState | null>(null);
  private readonly requesting = signal(false);
  readonly state = this.value.asReadonly();
  readonly busy = computed(() => this.requesting() || this.state()?.state === 'downloading' || this.state()?.state === 'installing');
  readonly actionVisible = computed(() => {
    if (!this.desktop.available) return false;
    const state = this.state();
    return state !== null && (state.state === 'available' || state.state === 'downloading' || state.state === 'downloaded'
      || state.state === 'installing' || (state.state === 'error' && !!state.version));
  });
  readonly error = computed(() => {
    const state = this.state();
    return state?.state === 'error' && state.version ? state.message : null;
  });
  readonly label = computed(() => {
    const state = this.state();
    switch (state?.state) {
      case 'downloading': {
        const percent = downloadPercent(state.percent);
        return percent === null ? 'Downloading…' : `Downloading ${percent}%`;
      }
      case 'installing': return 'Restarting…';
      case 'downloaded': return 'Restart to update';
      case 'error': return 'Retry update';
      default: return 'Update and restart';
    }
  });
  readonly description = computed(() => {
    const state = this.state();
    const version = state && 'version' in state && state.version ? ` ${state.version}` : '';
    if (state?.state === 'error') return state.message;
    if (state?.state === 'downloading') return `Downloading GateRook${version}. The app will restart when the update is ready.`;
    if (state?.state === 'installing') return `Restarting GateRook to install${version}.`;
    if (state?.state === 'downloaded') return `Restart GateRook to install${version}.`;
    return `Download GateRook${version} and restart the app.`;
  });

  constructor() {
    if (this.desktop.available) inject(DestroyRef).onDestroy(this.desktop.onUpdateState((state) => this.value.set(state)));
  }

  async install(): Promise<void> {
    if (!this.actionVisible() || this.busy()) return;
    const previous = this.state();
    this.requesting.set(true);
    this.value.set({
      state: previous?.state === 'downloaded' ? 'installing' : 'downloading',
      ...(previous?.version ? { version: previous.version } : {}),
      ...(previous?.lastCheckedAt ? { lastCheckedAt: previous.lastCheckedAt } : {}),
    });
    try {
      await this.desktop.installUpdate();
    } catch (error) {
      // Main's error can arrive before the rejected IPC result; preserve its precise explanation.
      if (this.state()?.state !== 'error') {
        this.value.set({
          state: 'error',
          message: desktopErrorText(error, 'The update could not be installed. Try again.'),
          ...(previous?.version ? { version: previous.version } : {}),
          ...(previous?.lastCheckedAt ? { lastCheckedAt: previous.lastCheckedAt } : {}),
        });
      }
    } finally {
      this.requesting.set(false);
    }
  }
}

/** Percentages come from a network transfer; invalid values remain indeterminate. */
export function downloadPercent(percent: number | undefined): number | null {
  return typeof percent === 'number' && Number.isFinite(percent) ? Math.floor(Math.max(0, Math.min(100, percent))) : null;
}
