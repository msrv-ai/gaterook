import { ChangeDetectionStrategy, Component, DOCUMENT, Injector, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { CoreStore } from '../../core/stores/core.store';
import { GwHowItWorks, GwHowItWorksStep } from '../../kit/elements/how-it-works/how-it-works';
import { GwIcon } from '../../kit/icons';
import { GwExternalStores } from './external-stores';
import { GwJump, jumpTo } from './jump';
import { GwMasterKeyCard } from './master-key-card';
import { SecretsData } from './secrets.data';
import { GwShortLived } from './short-lived';
import { GwStoredSecrets } from './stored-secrets';

/** The first render waits this long before it shows placeholders (kit: "when it takes longer than 300 ms"). */
export const SKELETON_DELAY_MS = 300;

/** The in-page anchors a link may name (`/secrets#master-key` from Overview's getting started). */
const ANCHORS = /^(master-key|stored-secrets|external-stores|short-lived|store-[a-z0-9-]+)$/;

/**
 * Secrets (design/pages/secrets.html; ARCHITECTURE.md §8.4 "secrets"): how credentials are stored, encrypted and
 * handed to a connector at call time, and where they come from.
 *
 * - The info alert and How it works (what happens to a secret; open by default, remembered).
 * - Master key: where it is, who can read it, what it protects, backup and rotation (`GwMasterKeyCard`).
 * - Stored secrets: the write-only table with Replace / Rotate / Test (`GwStoredSecrets`).
 * - External stores: Vault, GCP SM, AWS SM with their state (`GwExternalStores`).
 * - Short-lived credentials (`GwShortLived`).
 *
 * Each part loads on its own (`SecretsData`); placeholders only when the first answers take longer than 300 ms; a
 * failed reload with data on screen says the page may be out of date. A link to an anchor of the page
 * (`#master-key`, `#stored-secrets`, `#store-aws`) scrolls there and puts the keyboard there once the data is in.
 */
@Component({
  selector: 'gw-secrets-page',
  imports: [RouterLink, GwIcon, GwHowItWorks, GwHowItWorksStep, GwJump, GwMasterKeyCard, GwStoredSecrets, GwExternalStores, GwShortLived],
  providers: [SecretsData],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page' },
  templateUrl: './secrets.page.html',
})
export class SecretsPage {
  protected readonly data = inject(SecretsData);
  private readonly core = inject(CoreStore);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);
  private readonly doc = inject(DOCUMENT);

  /** Placeholders only when the first answers are slow. */
  protected readonly slow = signal(false);
  protected readonly loading = computed(() => !this.data.loaded());

  private readonly fragment = toSignal(this.route.fragment, { initialValue: null });

  constructor() {
    void this.data.start();
    const timer = setTimeout(() => this.slow.set(true), SKELETON_DELAY_MS);
    effect((onCleanup) => {
      if (!this.loading()) clearTimeout(timer);
      onCleanup(() => clearTimeout(timer));
    });
    // A link to an anchor of this page: once the parts are in (the target may be a store card), go there.
    effect(() => {
      const f = this.fragment();
      const ready = !this.loading();
      if (!f || !ready || !ANCHORS.test(f)) return;
      untracked(() => afterNextRender(() => jumpTo(this.doc, f), { injector: this.injector }));
    });
    // The core came back (it was away, or another instance answers): everything may be stale.
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

  protected retry(): void {
    void this.data.refresh();
  }

  protected errorText(e: unknown): string {
    return isApiError(e) ? e.detail : 'The core didn’t answer. Try again.';
  }
}
