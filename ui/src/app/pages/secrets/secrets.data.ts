import { DestroyRef, Injectable, WritableSignal, computed, inject, signal } from '@angular/core';
import { isNotAvailable } from '../../core/api/api-error';
import { ConnectionsApi } from '../../core/api/connections.api';
import {
  MasterKeyApi,
  MasterKeyRotation,
  MasterKeyStatus,
  SecretRefTest,
  SecretReference,
  SecretReplaced,
  SecretRow,
  SecretStore,
  SecretStoreCreate,
  SecretStoreDisconnected,
  SecretStoreTest,
  SecretsApi,
  ShortLivedCredential,
} from '../../core/api/secrets.api';
import { Env } from '../../core/api/types';
import { EventStream } from '../../core/events/event-stream';

/**
 * One part of the page as loaded: its value (null until the first answer), why its first load failed, and whether
 * this core has the route at all (`unavailable`: the backend step of the area isn't merged — the page says so,
 * never shows made-up data).
 */
export interface Part<T> {
  readonly value: T | null;
  readonly error: unknown;
  readonly unavailable: boolean;
}

const EMPTY: Part<never> = { value: null, error: null, unavailable: false };

/** How a Replace ended: in use, or kept waiting because the test with the new value failed. */
export type ReplaceOutcome = { readonly ok: true; readonly result: SecretReplaced } | { readonly ok: false; readonly result: SecretReplaced };

/**
 * The Secrets page's data (ARCHITECTURE.md §6 secrets, §8.4): the master key card (`/api/master-key`, rotation
 * progress from SSE `master-key.rotation`), the stored secrets (`/api/secrets`, with each connection's env from
 * `/api/connections` for its marker), the external stores (`/api/secret-stores`, step S1) and the short-lived
 * credentials (`/api/secrets/short-lived`). Each part loads on its own: one that fails or doesn't exist in this core
 * leaves the others working. Reloads are coalesced: one in flight and one more after it.
 *
 * Every action here sends what the person typed once and keeps nothing: secret values and passphrases never go into
 * a signal of this service.
 */
@Injectable()
export class SecretsData {
  private readonly secretsApi = inject(SecretsApi);
  private readonly masterKeyApi = inject(MasterKeyApi);
  private readonly connectionsApi = inject(ConnectionsApi);
  private readonly events = inject(EventStream);

  private readonly masterKeySignal = signal<Part<MasterKeyStatus>>(EMPTY);
  private readonly rowsSignal = signal<Part<SecretRow[]>>(EMPTY);
  private readonly storesSignal = signal<Part<SecretStore[]>>(EMPTY);
  private readonly shortLivedSignal = signal<Part<ShortLivedCredential[]>>(EMPTY);
  private readonly envSignal = signal<ReadonlyMap<string, Env>>(new Map());
  private readonly rotationSignal = signal<MasterKeyRotation | null>(null);
  private readonly staleSignal = signal<unknown>(null);
  private readonly refreshingSignal = signal(false);
  /** The primary key before the last rotation this page started or saw running (step 3 names it). */
  private readonly oldKeySignal = signal<number | null>(null);

  readonly masterKey = this.masterKeySignal.asReadonly();
  readonly rows = this.rowsSignal.asReadonly();
  readonly stores = this.storesSignal.asReadonly();
  readonly shortLived = this.shortLivedSignal.asReadonly();
  /** Each connection's env, for the `gw-env` marker after its name (empty when Connections didn't answer). */
  readonly envOf = this.envSignal.asReadonly();
  /** The rotation as last reported (the status, then its events). */
  readonly rotation = this.rotationSignal.asReadonly();
  readonly oldKeyId = this.oldKeySignal.asReadonly();
  /** Why the last reload failed while older data is on screen. */
  readonly stale = this.staleSignal.asReadonly();
  readonly refreshing = this.refreshingSignal.asReadonly();

  /** Whether anything has answered yet (the page shows placeholders until then). */
  readonly loaded = computed(
    () =>
      this.isSettled(this.masterKeySignal()) &&
      this.isSettled(this.rowsSignal()) &&
      this.isSettled(this.storesSignal()) &&
      this.isSettled(this.shortLivedSignal()),
  );

  private inFlight: Promise<void> | null = null;
  private again = false;
  private stopped = false;
  private readonly unsubscribe: (() => void)[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  start(): Promise<void> {
    this.unsubscribe.push(this.events.on('master-key.rotation', (r) => this.onRotation(r)));
    return this.refresh();
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe.splice(0).forEach((u) => u());
  }

  /** Reloads every part now; while one runs, one more follows it (a change that came meanwhile is in neither). */
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

  private async load(): Promise<void> {
    const failures: unknown[] = [];
    const part = async <T>(target: WritableSignal<Part<T>>, call: () => Promise<T>): Promise<void> => {
      try {
        const value = await call();
        if (!this.stopped) target.set({ value, error: null, unavailable: false });
      } catch (error) {
        if (this.stopped) return;
        if (isNotAvailable(error)) {
          target.set({ value: null, error: null, unavailable: true });
        } else if (target().value !== null) {
          // Older data stays on screen; the page says it may be out of date.
          failures.push(error);
        } else {
          target.set({ value: null, error, unavailable: false });
        }
      }
    };
    await Promise.all([
      part(this.masterKeySignal, () => this.masterKeyApi.status()).then(() => this.adoptRotation()),
      part(this.rowsSignal, () => this.secretsApi.list()),
      part(this.storesSignal, () => this.secretsApi.stores()),
      part(this.shortLivedSignal, () => this.secretsApi.shortLived()),
      this.connectionsApi.list().then(
        (list) => {
          if (!this.stopped) this.envSignal.set(new Map(list.items.map((c) => [c.name, c.env])));
        },
        () => undefined, // without envs the rows just lose their markers
      ),
    ]);
    if (!this.stopped) this.staleSignal.set(failures[0] ?? null);
  }

  private isSettled(p: Part<unknown>): boolean {
    return p.value !== null || p.error !== null || p.unavailable;
  }

  // ── Master key ────────────────────────────────────────────────────────────────────────────────────────────────

  /** The status carries the rotation the core is running (one resumed at start, too). */
  private adoptRotation(): void {
    const status = this.masterKeySignal().value;
    if (!status) return;
    const r = status.rotation;
    // A rotation that ended stays on screen as this page saw it: the status says idle again only after a restart.
    const current = this.rotationSignal();
    if (r.state !== 'idle' || !current || current.state === 'running') this.rotationSignal.set(r);
    if (r.state === 'running' && this.oldKeySignal() === null) {
      const old = status.keys.find((k) => !k.primary && k.status === 'enabled');
      if (old) this.oldKeySignal.set(old.id);
    }
  }

  private onRotation(e: { readonly state: string; readonly done: number; readonly total: number }): void {
    const before = this.rotationSignal();
    this.rotationSignal.set({
      state: (e.state as MasterKeyRotation['state']) ?? 'running',
      done: e.done,
      total: e.total,
      targetKeyId: before?.targetKeyId ?? null,
      error: e.state === 'failed' ? (before?.error ?? null) : null,
    });
    // The end changes the keyset (old key disabled) or says why it stopped: read the status again.
    if (e.state === 'done' || e.state === 'failed') void this.reloadMasterKey();
  }

  private async reloadMasterKey(): Promise<void> {
    try {
      const status = await this.masterKeyApi.status();
      if (this.stopped) return;
      this.masterKeySignal.set({ value: status, error: null, unavailable: false });
      const r = status.rotation;
      if (r.state !== 'idle') this.rotationSignal.set(r);
    } catch {
      // the events keep the progress; the next reload brings the facts
    }
  }

  /** Rotate master key: the core adds a key and re-wraps every data key in the background (202). */
  async rotateKey(reason?: string | null): Promise<MasterKeyRotation> {
    this.oldKeySignal.set(this.masterKeySignal().value?.keys.find((k) => k.primary)?.id ?? null);
    const r = await this.masterKeyApi.rotate({ reason: reason ?? null });
    // Events may have arrived before the 202: never go back from what they said.
    const seen = this.rotationSignal();
    this.rotationSignal.set(seen && seen.state !== 'idle' && seen.done > r.done ? { ...seen, targetKeyId: r.targetKeyId } : r);
    void this.reloadMasterKey();
    return r;
  }

  /** Back up key with the one-time `saveToken` of the desktop's save dialog; the passphrase is sent once. */
  async backup(saveToken: string, passphrase: string | null): Promise<{ readonly path: string; readonly passphrase: boolean; readonly warnings: string[] }> {
    const result = await this.masterKeyApi.backup(saveToken, passphrase);
    await this.reloadMasterKey();
    return result;
  }

  async fixPermissions(): Promise<MasterKeyStatus> {
    const status = await this.masterKeyApi.fixPermissions();
    if (!this.stopped) this.masterKeySignal.set({ value: status, error: null, unavailable: false });
    return status;
  }

  // ── Stored secrets ────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Replace: version N+1 from the value typed in the dialog. With `testBefore` the owner is tested with it first;
   * a failed test leaves the new version waiting and the old one in use (`ok: false`).
   */
  async replace(id: string, value: string, testBefore: boolean, reason: string | null): Promise<ReplaceOutcome> {
    const result = await this.secretsApi.replace(id, { value, testBefore, reason });
    void this.refresh();
    return result.state === 'testing' ? { ok: false, result } : { ok: true, result };
  }

  /** Rotate at the source: 202, or 409 with the reason the connector can't (the core's words). */
  async rotate(id: string, reason: string | null): Promise<{ readonly jobId: string }> {
    const r = await this.secretsApi.rotate(id, { reason });
    void this.refresh();
    return r;
  }

  testReference(uri: string, store: string | null): Promise<SecretRefTest> {
    return this.secretsApi.testReference(uri, store);
  }

  async setReference(id: string, uri: string, store: string | null, reason: string | null): Promise<SecretReference> {
    const r = await this.secretsApi.setReference(id, uri, store, reason);
    void this.refresh();
    return r;
  }

  // ── External stores ───────────────────────────────────────────────────────────────────────────────────────────

  async testStore(id: string): Promise<SecretStoreTest> {
    try {
      return await this.secretsApi.testStore(id);
    } finally {
      // The card shows the state and latency the core recorded for this test.
      void this.refresh();
    }
  }

  /**
   * Test and connect: the store is created (references of its kind that had no store bind to it), then tested;
   * a failed test leaves it connected and failing, with the core's error on its card.
   */
  async connectStore(request: SecretStoreCreate): Promise<{ readonly store: SecretStore; readonly test: SecretStoreTest | null }> {
    const store = await this.secretsApi.createStore(request);
    let test: SecretStoreTest | null = null;
    try {
      test = await this.secretsApi.testStore(store.id);
    } catch {
      test = null;
    }
    void this.refresh();
    return { store, test };
  }

  async disconnectStore(store: SecretStore): Promise<SecretStoreDisconnected> {
    const r = await this.secretsApi.deleteStore(store.id, store.name);
    this.storesSignal.update((p) => (p.value ? { ...p, value: p.value.filter((s) => s.id !== store.id) } : p));
    void this.refresh();
    return r;
  }

  async keepStore(store: SecretStore): Promise<SecretStore> {
    const kept = await this.secretsApi.confirmStoreIntegrity(store.id);
    this.storesSignal.update((p) => (p.value ? { ...p, value: p.value.map((s) => (s.id === store.id ? kept : s)) } : p));
    void this.refresh();
    return kept;
  }
}
