// The first OS key-store access waits for the person to unlock GateRook. Kept separate from Electron so tests can
// verify that even the availability probe cannot open a system prompt before the explanation has been accepted.
import type { DesktopCoreState } from '../shared/contract.ts';
import { FileKeyAccessError, type FileKeyPlan } from './file-key.ts';

export type UnlockState = Extract<DesktopCoreState, { kind: 'unlock-required' }>;

interface Options {
  readonly load: () => Promise<FileKeyPlan>;
  readonly changed: (state: UnlockState | null) => void;
  readonly log: (message: string) => void;
  /** Refused or unavailable native key access closes the application. Called synchronously before yielding. */
  readonly denied: () => void;
  readonly keychainService?: string;
}

const CANCELLED: FileKeyPlan = { fileKey: null, protection: 'off', note: null };
const RETRY = 'GateRook could not unlock its encryption key. Check access to its data folder, then try again.';

export class FileKeyUnlock {
  private readonly o: Options;
  private current: UnlockState | null = null;
  private authorized = false;
  private closed = false;
  private pending: Promise<FileKeyPlan> | null = null;
  private resolve: ((plan: FileKeyPlan) => void) | null = null;
  private attempt: Promise<void> | null = null;

  constructor(options: Options) {
    this.o = options;
  }

  state(): UnlockState | null {
    return this.current;
  }

  /** Only a successful unlock grants consent for later core restarts in this app session. */
  prepare(): Promise<FileKeyPlan> {
    if (this.closed) return Promise.resolve(CANCELLED);
    if (this.pending) return this.pending;
    this.pending = new Promise((resolve) => { this.resolve = resolve; });
    const pending = this.pending;
    if (this.authorized) void this.unlock();
    else this.publish(false, null);
    return pending;
  }

  /** Concurrent clicks share one OS operation. Refusing its native prompt closes the app. */
  unlock(): Promise<void> {
    if (this.closed || !this.pending) return Promise.resolve();
    if (this.attempt) return this.attempt;
    this.attempt = this.load().finally(() => { this.attempt = null; });
    return this.attempt;
  }

  /** Release a pending startup immediately, including when a system prompt is still open. */
  close(): void {
    this.closed = true;
    this.current = null;
    this.finish(CANCELLED);
  }

  private async load(): Promise<void> {
    this.publish(true, null);
    try {
      const plan = await this.o.load();
      if (this.closed) {
        plan.fileKey?.fill(0);
        return;
      }
      if (plan.accessFailed) {
        if (plan.note) this.o.log(plan.note);
        this.deny();
        return;
      }
      this.authorized = true;
      this.current = null;
      this.o.changed(null);
      this.finish(plan);
    } catch (error) {
      if (this.closed) return;
      this.o.log(`OS unlock failed: ${error instanceof Error ? error.message : String(error)}`);
      this.authorized = false;
      if (error instanceof FileKeyAccessError) this.deny();
      else this.publish(false, RETRY);
    }
  }

  private finish(plan: FileKeyPlan): void {
    const resolve = this.resolve;
    this.pending = null;
    this.resolve = null;
    resolve?.(plan);
  }

  private deny(): void {
    // Resolve the pending preparation and start shutdown in the same stack. The supervisor marks itself stopped
    // before the resolved promise can resume, so it never spawns a core with an absent protection key.
    this.close();
    this.o.denied();
  }

  private publish(unlocking: boolean, error: string | null): void {
    this.current = { kind: 'unlock-required', unlocking, error,
      ...(this.o.keychainService ? { keychainService: this.o.keychainService } : {}) };
    this.o.changed(this.current);
  }
}
