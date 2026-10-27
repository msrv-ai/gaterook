import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { isNotAvailable } from '../../core/api/api-error';
import { ClientsApi, ClientsView } from '../../core/api/clients.api';
import { Onboarding, OverviewApi } from '../../core/api/overview.api';
import { MasterKeyApi, MasterKeyStatus } from '../../core/api/secrets.api';
import { EventStream } from '../../core/events/event-stream';
import { STEP_IDS, SetupFacts, StepId } from './onboarding-view';

/** Where "I'll do it later" is remembered: this window's session, so the next start asks again. */
export const LATER_KEY = 'gw-onboarding-later';

/** A client appearing or changing (K1 `clients.drift`) refreshes the cards a moment later: one scan reports several. */
const DRIFT_DELAY_MS = 500;

/** What a backup returned (`POST /api/master-key/backup`). */
export interface BackupResult {
  readonly path: string;
  readonly at: string;
  readonly keyIds: number[];
  readonly passphrase: boolean;
  readonly warnings: string[];
}

/** sessionStorage that never throws: blocked storage (privacy settings, tests) just means "not remembered". */
const session = {
  get(key: string): string | null {
    try {
      return globalThis.sessionStorage?.getItem(key) ?? null;
    } catch {
      return null;
    }
  },
  set(key: string, value: string | null): void {
    try {
      if (value === null) globalThis.sessionStorage?.removeItem(key);
      else globalThis.sessionStorage?.setItem(key, value);
    } catch {
      // not remembered
    }
  },
};

function readLater(): ReadonlySet<StepId> {
  const raw = session.get(LATER_KEY);
  if (!raw) return new Set();
  try {
    const ids: unknown = JSON.parse(raw);
    return new Set(Array.isArray(ids) ? ids.filter((id): id is StepId => (STEP_IDS as readonly unknown[]).includes(id)) : []);
  } catch {
    return new Set();
  }
}

/**
 * Welcome's data (ARCHITECTURE.md §6 onboarding, §8.4): `GET /api/onboarding` (getting started, the setup map,
 * `firstRun`, `skipped`), plus what the step cards show from the merged areas — the master key (`GET /api/master-key`:
 * where the file is, who can read it, whether the current key is backed up) and the clients this computer has
 * (`GET /api/clients`, K1). Only the onboarding answer is required: when another call fails, the part it feeds says
 * so or falls back to the core's own words, never to made-up data.
 *
 * Step 1 writes here (`POST /api/master-key/backup` with the desktop's one-time save token, then everything is read
 * again); Skip setup is `POST /api/onboarding/skip`. Steps 2–4 are other pages. Refreshes are coalesced: one in flight
 * and one more after it.
 */
@Injectable()
export class OnboardingData {
  private readonly api = inject(OverviewApi);
  private readonly masterKey = inject(MasterKeyApi);
  private readonly clientsApi = inject(ClientsApi);
  private readonly events = inject(EventStream);

  private readonly onboardingSignal = signal<Onboarding | null>(null);
  private readonly errorSignal = signal<unknown>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly keySignal = signal<MasterKeyStatus | null>(null);
  private readonly keyErrorSignal = signal<unknown>(null);
  private readonly clientsSignal = signal<ClientsView | null>(null);
  private readonly refreshingSignal = signal(false);
  private readonly laterSignal = signal<ReadonlySet<StepId>>(readLater());

  /** `GET /api/onboarding`; null until the first answer. */
  readonly onboarding = this.onboardingSignal.asReadonly();
  /** Why the first load failed (nothing to show yet). */
  readonly error = this.errorSignal.asReadonly();
  /** Why the last refresh failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  /** The master key's status; null until it answers. */
  readonly key = this.keySignal.asReadonly();
  /** Why the master key's status didn't load (step 1 says so instead of the key's facts). */
  readonly keyError = this.keyErrorSignal.asReadonly();
  /** K1's client cards; null before K1 is merged or when it didn't answer. */
  readonly clients = this.clientsSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();
  /** Steps put off in this window ("I'll do it later"): Welcome opens past them. */
  readonly later = this.laterSignal.asReadonly();

  /** Everything the view functions read, once the onboarding answer is in. */
  readonly facts = computed<SetupFacts | null>(() => {
    const onboarding = this.onboardingSignal();
    return onboarding ? { onboarding, key: this.keySignal(), clients: this.clientsSignal() } : null;
  });

  private inFlight: Promise<void> | null = null;
  private again = false;
  private stopped = false;
  private driftTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly unsubscribe: (() => void)[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Loads everything and follows the clients K1 finds until the page goes away. */
  start(): Promise<void> {
    this.unsubscribe.push(this.events.on('clients.drift', () => this.scheduleClients()));
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
    if (this.driftTimer) clearTimeout(this.driftTimer);
    this.driftTimer = null;
  }

  /** Reloads now; while one runs, one more follows it. */
  refresh(): Promise<void> {
    if (this.inFlight) {
      this.again = true;
      return this.inFlight;
    }
    this.refreshingSignal.set(true);
    this.inFlight = this.load().finally(() => {
      this.inFlight = null;
      this.refreshingSignal.set(false);
      if (this.again && !this.stopped) {
        this.again = false;
        void this.refresh();
      }
    });
    return this.inFlight;
  }

  /** "I'll do it later": the step stays not done, Welcome opens past it in this window. */
  putOff(id: StepId): void {
    const next = new Set(this.laterSignal());
    next.add(id);
    this.laterSignal.set(next);
    session.set(LATER_KEY, JSON.stringify([...next]));
  }

  /**
   * Back up key: the core writes the copy where the desktop's save dialog pointed (`saveToken`; the UI never sends a
   * path), encrypted with the passphrase if there is one. Then the page reads everything again: the step is done and
   * the secrets node says so. Being put off no longer matters once it is done.
   */
  async backup(saveToken: string, passphrase: string | null): Promise<BackupResult> {
    const result = await this.masterKey.backup(saveToken, passphrase);
    if (this.laterSignal().has('master-key')) {
      const next = new Set(this.laterSignal());
      next.delete('master-key');
      this.laterSignal.set(next);
      session.set(LATER_KEY, next.size ? JSON.stringify([...next]) : null);
    }
    await this.refresh();
    return result;
  }

  /** Skip setup: the next start opens Overview (`onboarding.skipped`). */
  skip(): Promise<{ readonly skipped: boolean }> {
    return this.api.skipOnboarding();
  }

  private scheduleClients(): void {
    if (this.stopped || this.driftTimer) return;
    this.driftTimer = setTimeout(() => {
      this.driftTimer = null;
      void this.loadClients();
    }, DRIFT_DELAY_MS);
  }

  private async load(): Promise<void> {
    const [onboarding] = await Promise.all([
      this.api.onboarding().then(
        (o) => o,
        (e: unknown) => {
          if (this.onboardingSignal()) this.staleSignal.set(e);
          else this.errorSignal.set(e);
          return null;
        },
      ),
      this.loadKey(),
      this.loadClients(),
    ]);
    if (this.stopped || !onboarding) return;
    this.onboardingSignal.set(onboarding);
    this.errorSignal.set(null);
    this.staleSignal.set(null);
  }

  private async loadKey(): Promise<void> {
    try {
      const key = await this.masterKey.status();
      if (this.stopped) return;
      this.keySignal.set(key);
      this.keyErrorSignal.set(null);
    } catch (e) {
      // An older status on screen stays; only a first failure replaces the key's facts with the error.
      if (!this.stopped && !this.keySignal()) this.keyErrorSignal.set(e);
    }
  }

  private async loadClients(): Promise<void> {
    try {
      const clients = await this.clientsApi.list();
      if (!this.stopped) this.clientsSignal.set(clients);
    } catch (e) {
      // Before K1 the core has no clients area: the step names the clients MCP Gateway can connect instead.
      if (!this.stopped && isNotAvailable(e)) this.clientsSignal.set(null);
    }
  }
}
