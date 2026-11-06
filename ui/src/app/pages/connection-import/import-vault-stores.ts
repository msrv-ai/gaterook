import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isApiError } from '../../core/api/api-error';
import { ConnectionImportsApi, ImportStoreProposal } from '../../core/api/connection-imports.api';
import { SecretStore, SecretStoreTest, SecretsApi } from '../../core/api/secrets.api';
import { GwToggle } from '../../kit/disclosure';
import { GwIcon } from '../../kit/icons';
import { GwExternalStores, StoreConnectHandler } from '../secrets/external-stores';
import { SecretsData } from '../secrets/secrets.data';
import { storeStatus } from '../secrets/secrets-view';
import { matchingVault } from '../secrets/vault-identity';

/** Import proposals reuse the reviewed store form; rendering never tests or connects a Vault. */
@Component({
  selector: 'gw-import-vault-stores',
  imports: [RouterLink, GwIcon, GwToggle, GwExternalStores],
  providers: [SecretsData],
  templateUrl: './import-vault-stores.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-stack' },
})
export class ImportVaultStores {
  readonly sessionId = input.required<string>();
  readonly proposals = input<readonly ImportStoreProposal[]>([]);
  readonly disabled = input(false);
  readonly changed = output<void>();
  readonly busyChange = output<boolean>();
  readonly data = inject(SecretsData);
  private readonly api = inject(SecretsApi);
  private readonly imports = inject(ConnectionImportsApi);
  private readonly destroy = inject(DestroyRef);
  private readonly form = viewChild(GwExternalStores);
  readonly loading = signal(true);
  readonly busy = signal<string | null>(null);
  readonly error = signal('');
  readonly storeStatus = storeStatus;
  readonly stores = computed(() => this.data.stores().value?.filter(store => store.kind === 'vault') ?? []);
  readonly available = computed(() => !this.loading() && !this.data.stores().error && !this.data.stores().unavailable);
  private active: ImportStoreProposal | null = null;
  private readonly bound = signal<ReadonlyMap<string, string>>(new Map());

  constructor() { void this.refresh(); }

  async refresh(): Promise<void> {
    if (this.busy()) return;
    this.loading.set(true);
    await this.data.refreshStores();
    if (!this.destroy.destroyed) this.loading.set(false);
  }

  match(proposal: ImportStoreProposal): SecretStore | null {
    const selected = proposal.connectedStore ?? this.bound().get(proposal.id);
    if (selected) return this.stores().find(store => store.id === selected) ?? null;
    return matchingVault(this.stores(), proposal.config.address ?? '', proposal.config.namespace ?? '');
  }

  selected(proposal: ImportStoreProposal): boolean {
    return this.hasSelection(proposal) && !!this.match(proposal);
  }

  hasSelection(proposal: ImportStoreProposal): boolean {
    return !!(proposal.connectedStore ?? this.bound().get(proposal.id));
  }

  address(proposal: ImportStoreProposal): string {
    const address = this.match(proposal)?.config['address'];
    return typeof address === 'string' ? address : proposal.config.address ?? '';
  }

  namespace(proposal: ImportStoreProposal): string {
    const store = this.match(proposal);
    if (!store) return proposal.config.namespace ?? '';
    return typeof store.config['namespace'] === 'string' ? store.config['namespace'] : '';
  }

  open(proposal: ImportStoreProposal | null, event: Event): void {
    if (this.disabled() || this.busy() || !this.available() || (proposal && (this.match(proposal) || this.hasSelection(proposal)))) return;
    this.active = proposal;
    this.error.set('');
    this.form()?.openVault({ name: proposal?.name, address: proposal?.config.address, namespace: proposal?.config.namespace,
      method: proposal?.config.auth?.method, roleId: proposal?.config.auth?.roleId, prepared: proposal?.secretSources }, event.currentTarget as HTMLElement);
  }

  readonly submit: StoreConnectHandler = async (request, preparedFields) => {
    const proposal = this.active;
    const sessionId = this.sessionId();
    this.begin(proposal?.id ?? 'manual');
    try {
      await this.data.refreshStores();
      if (this.data.stores().error || this.data.stores().unavailable) throw new Error('Existing Vault stores could not be checked. Try again before connecting.');
      const address = typeof request.config['address'] === 'string' ? request.config['address'] : '';
      const namespace = typeof request.config['namespace'] === 'string' ? request.config['namespace'] : '';
      const existing = matchingVault(this.stores(), address, namespace);
      const { kind: _kind, ...reviewed } = request;
      let store: SecretStore;
      if (existing) {
        const session = proposal ? await this.imports.get(sessionId) : null;
        const previous = session?.manifest?.stores?.find(store => store.id === proposal?.id);
        if (!proposal || previous?.connectedStore !== existing.id) throw new Error(`This Vault is already configured as ${existing.name}. Cancel and use the existing store.`);
        store = await this.imports.connectStore(sessionId, proposal.id, { existingStore: existing.id });
      } else {
        store = proposal ? await this.imports.connectStore(sessionId, proposal.id, { ...reviewed, preparedFields }) : await this.api.createStore(request);
      }
      let test: SecretStoreTest | null = null;
      try { test = await this.api.testStore(store.id); } catch { /* The saved store remains available for a later test. */ }
      await this.data.refreshStores();
      if (!this.destroy.destroyed && sessionId === this.sessionId()) {
        if (proposal) this.bound.update(bound => new Map(bound).set(proposal.id, store.id));
        this.changed.emit();
      }
      return { store, test };
    } finally { this.end(); }
  };

  async useExisting(proposal: ImportStoreProposal): Promise<void> {
    const store = this.match(proposal);
    if (!store || store.verified === false || this.disabled() || this.busy() || !this.available()) return;
    const sessionId = this.sessionId();
    this.begin(proposal.id); this.error.set('');
    try {
      const selected = await this.imports.connectStore(sessionId, proposal.id, { existingStore: store.id });
      if (this.destroy.destroyed || sessionId !== this.sessionId()) return;
      this.bound.update(bound => new Map(bound).set(proposal.id, selected.id));
      this.changed.emit();
    } catch (error) { if (!this.destroy.destroyed) this.error.set(isApiError(error) ? error.detail : error instanceof Error ? error.message : 'The Vault could not be selected.'); }
    finally { this.end(); }
  }

  private begin(id: string): void { this.busy.set(id); this.busyChange.emit(true); }
  private end(): void { if (!this.destroy.destroyed) { this.busy.set(null); this.busyChange.emit(false); } }
}
