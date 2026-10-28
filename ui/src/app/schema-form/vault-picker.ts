import { ChangeDetectionStrategy, Component, DestroyRef, Injector, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { isApiError } from '../core/api/api-error';
import { SecretStore, SecretsApi, VaultBrowse } from '../core/api/secrets.api';
import { GwAriaDisabled } from '../kit/aria-disabled';
import { SchemaNode } from './compile';
import { SchemaForm } from './schema-form';
import { selectableVaultKey, vaultReferenceParts } from './secret-sources';

interface KeyList {
  readonly state: 'loading' | 'ready' | 'error';
  readonly keys: readonly string[];
  readonly message: string;
}

/** Optional, explicitly requested Vault metadata discovery. Secret values never enter the renderer. */
@Component({
  selector: 'gw-vault-picker',
  imports: [GwAriaDisabled],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './vault-picker.html',
  host: { class: 'gw-form__rows' },
})
export class GwVaultPicker {
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  readonly form = input.required<SchemaForm>();
  readonly node = input.required<SchemaNode>();
  readonly id = computed(() => `${this.form().id(this.node())}-vault`);
  readonly store = computed(() => this.form().secretStore(this.node()) ?? '');
  readonly reference = computed(() => vaultReferenceParts(this.form().secretReference(this.node())));
  readonly stores = signal<readonly SecretStore[] | null>(null);
  readonly storesBusy = signal(false);
  readonly storesError = signal<string | null>(null);
  readonly browsing = signal(false);
  readonly folder = linkedSignal(() => { this.form(); this.node(); this.store(); return ''; });
  private readonly browseContext = computed(() => JSON.stringify([this.store(), this.folder()]));
  readonly listing = linkedSignal(() => { this.form(); this.node(); this.browseContext(); return null as VaultBrowse | null; });
  readonly browseBusy = linkedSignal(() => { this.form(); this.node(); this.browseContext(); return false; });
  readonly browseError = linkedSignal(() => { this.form(); this.node(); this.browseContext(); return null as string | null; });
  private readonly keyContext = computed(() => JSON.stringify([this.store(), this.reference().uri]));
  readonly keyList = linkedSignal(() => { this.form(); this.node(); this.keyContext(); return null as KeyList | null; });
  readonly selectedKey = computed(() => this.keyList()?.keys.includes(this.reference().key) ? this.reference().key : '');
  private browseRequest = 0;

  // Plain schema previews do not need an HTTP client until the user requests Vault metadata.
  private get api(): SecretsApi { return this.injector.get(SecretsApi); }

  private active(): boolean {
    return !this.destroyRef.destroyed && this.form().source() === 'vault' && this.form().visible(this.node());
  }

  private error(error: unknown, fallback: string): string {
    return isApiError(error) ? error.detail : fallback;
  }

  private async ensureStore(): Promise<boolean> {
    if (this.storesBusy()) return false;
    const form = this.form();
    const node = this.node();
    if (!this.stores()?.length) {
      this.storesBusy.set(true);
      this.storesError.set(null);
      try {
        const stores = (await this.api.stores()).filter((store) => store.kind === 'vault');
        if (!this.active() || this.form() !== form || this.node() !== node) return false;
        this.stores.set(stores);
        if (!stores.length) this.storesError.set('Connect Vault or OpenBao in Secrets before choosing a secret.');
      } catch (error) {
        if (this.active()) this.storesError.set(this.error(error, 'Could not load Vault stores. Try again.'));
        return false;
      } finally {
        if (!this.destroyRef.destroyed) this.storesBusy.set(false);
      }
    }
    const stores = this.stores() ?? [];
    const selected = stores.find((store) => store.id === this.store() || store.name === this.store());
    if (selected) this.chooseStore(selected.id);
    else if (!this.store() && stores.length === 1) this.chooseStore(stores[0].id);
    return this.active() && !!this.stores()?.some((store) => store.id === this.store());
  }

  chooseStore(id: string): void {
    if (this.store() === id || !this.stores()?.some((store) => store.id === id)) return;
    this.form().vaultStores.update((stores) => ({ ...stores, [this.node().pointer]: id }));
    this.storesError.set(null);
  }

  async toggleBrowse(): Promise<void> {
    this.browsing.update((open) => !open);
    if (this.browsing()) await this.loadFolder();
  }

  async loadFolder(): Promise<void> {
    if (this.browseBusy() || !await this.ensureStore() || !this.active()) return;
    const form = this.form();
    const node = this.node();
    const context = this.browseContext();
    const request = ++this.browseRequest;
    this.browseBusy.set(true);
    this.browseError.set(null);
    this.listing.set(null);
    const current = () => this.active() && this.form() === form && this.node() === node
      && request === this.browseRequest && context === this.browseContext();
    try {
      const result = await this.api.browseVault(this.store(), this.folder());
      if (current()) this.listing.set(result);
    } catch (error) {
      if (current()) {
        this.browseError.set(this.error(error, 'Could not list Vault secrets. Try again or enter the reference manually.'));
      }
    } finally {
      if (current()) this.browseBusy.set(false);
    }
  }

  async chooseEntry(value: string): Promise<void> {
    const entry = this.listing()?.entries[Number(value)];
    if (value === '' || !entry || this.browseBusy()) return;
    if (entry.directory) {
      this.folder.set(entry.path);
      await this.loadFolder();
    } else {
      this.form().setSecret(this.node(), entry.path);
      await this.loadKeys();
    }
  }

  async loadKeys(): Promise<void> {
    if (this.keyList()?.state === 'loading') return;
    const initialUri = this.reference().uri;
    if (!initialUri || !initialUri.startsWith('vault://') || initialUri === 'vault://') {
      this.keyList.set({ state: 'error', keys: [], message: 'Enter a Vault secret path first, or choose one with Browse Vault.' });
      return;
    }
    if (!await this.ensureStore() || !this.active() || initialUri !== this.reference().uri) return;
    const context = this.keyContext();
    const pending: KeyList = { state: 'loading', keys: [], message: 'Loading key names from Vault…' };
    this.keyList.set(pending);
    const current = () => this.active() && context === this.keyContext() && this.keyList() === pending;
    try {
      const result = await this.api.testReference(initialUri, this.store());
      if (!current()) return;
      if (!result.found) {
        this.keyList.set({ state: 'error', keys: [], message: result.error ?? 'The secret could not be read. Check the path and Vault read permissions.' });
        return;
      }
      const keys = [...new Set(result.keys)].filter((key) => selectableVaultKey(initialUri, key)).sort();
      const message = result.type === 'dynamic'
        ? 'This path issues short-lived credentials. Choose a KV secret, or use the Short-lived secret source.'
        : !keys.length ? 'No selectable keys were returned for this secret.'
        : keys.length < result.keys.length ? 'Choose a key. Some key names cannot be used in a GateRook reference.'
        : 'Choose the key that contains the password or credential. Only key names are shown.';
      this.keyList.set({ state: 'ready', keys, message });
    } catch (error) {
      if (current()) this.keyList.set({ state: 'error', keys: [], message: this.error(error, 'Could not load Vault keys. Try again.') });
    }
  }

  chooseKey(key: string): void {
    if (this.keyList()?.state !== 'ready' || !this.keyList()?.keys.includes(key)) return;
    this.form().setSecret(this.node(), `${this.reference().uri.slice('vault://'.length)}#${key}`);
  }
}
